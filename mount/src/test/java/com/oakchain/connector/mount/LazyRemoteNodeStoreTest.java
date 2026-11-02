package com.oakchain.connector.mount;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.time.Duration;
import java.util.UUID;

import com.oakchain.connector.mount.http.ValidatorHttpClient;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.apache.jackrabbit.oak.api.Blob;
import org.apache.jackrabbit.oak.api.CommitFailedException;
import org.apache.jackrabbit.oak.api.Type;
import org.apache.jackrabbit.oak.composite.CompositeNodeStore;
import org.apache.jackrabbit.oak.plugins.memory.MemoryNodeStore;
import org.apache.jackrabbit.oak.segment.Segment;
import org.apache.jackrabbit.oak.segment.SegmentBlob;
import org.apache.jackrabbit.oak.segment.SegmentId;
import org.apache.jackrabbit.oak.segment.SegmentNodeStore;
import org.apache.jackrabbit.oak.segment.SegmentNodeStoreBuilders;
import org.apache.jackrabbit.oak.segment.SegmentNotFoundException;
import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.apache.jackrabbit.oak.segment.file.FileStoreBuilder;
import org.apache.jackrabbit.oak.spi.blob.MemoryBlobStore;
import org.apache.jackrabbit.oak.spi.commit.CommitInfo;
import org.apache.jackrabbit.oak.spi.commit.EmptyHook;
import org.apache.jackrabbit.oak.spi.mount.MountInfoProvider;
import org.apache.jackrabbit.oak.spi.mount.Mounts;
import org.apache.jackrabbit.oak.spi.state.NodeBuilder;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * End-to-end on the exact Oak line AEM 6.5 LTS SP3 runs (Apache Oak 2.4.0):
 * a real FileStore ("validator") served over the validator HTTP read contract,
 * mounted read-only into a stock CompositeNodeStore.
 */
public class LazyRemoteNodeStoreTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private FileStore validatorStore;
    private MemoryBlobStore validatorBlobs;
    private SegmentNodeStore validator;
    private File validatorDir;
    private HttpServer server;
    private int port;

    @Before
    public void setUp() throws Exception {
        validatorDir = tmp.newFolder("validator");
        validatorBlobs = new MemoryBlobStore();
        validatorStore = FileStoreBuilder.fileStoreBuilder(validatorDir)
                .withBlobStore(validatorBlobs)
                .withMemoryMapping(false)
                .build();
        validator = SegmentNodeStoreBuilders.builder(validatorStore).build();
        write("a", "v1");
        port = freePort();
    }

    @After
    public void tearDown() {
        if (server != null) {
            server.stop(0);
        }
        validatorStore.close();
    }

    @Test
    public void lazyEmptyUntilValidatorAnswersThenFollowsHead() throws Exception {
        LazyRemoteNodeStore mount = newMount();
        try {
            // Validator down: mount is an empty root, never throws.
            mount.tick();
            assertFalse(mount.isConnected());
            assertNotNull(mount.getLastError());
            assertTrue(mount.getRoot().exists());
            assertFalse(mount.getRoot().hasChildNode("oak-chain"));

            // Validator up: next tick connects and the content appears.
            startServer();
            mount.tick();
            assertTrue(mount.isConnected());
            assertEquals("v1", read(mount.getRoot(), "a"));

            // New commit on the validator: next tick advances the head.
            write("b", "v2");
            assertFalse(mount.getRoot().getChildNode("oak-chain").hasChildNode("b"));
            mount.tick();
            assertEquals("v2", read(mount.getRoot(), "b"));
            assertEquals("v1", read(mount.getRoot(), "a"));
        } finally {
            mount.close();
        }
    }

    @Test
    public void compositeOnStockOakReadsMountAndRejectsWrites() throws Exception {
        startServer();
        LazyRemoteNodeStore mount = newMount();
        try {
            mount.tick();
            MountInfoProvider mip = Mounts.newBuilder().readOnlyMount("oak-chain", "/oak-chain").build();
            MemoryNodeStore global = new MemoryNodeStore();
            NodeBuilder g = global.getRoot().builder();
            g.child("content").setProperty("site", "aem");
            global.merge(g, EmptyHook.INSTANCE, CommitInfo.EMPTY);

            CompositeNodeStore composite = new CompositeNodeStore.Builder(mip, global)
                    .addMount("oak-chain", mount)
                    .build();

            NodeState root = composite.getRoot();
            assertEquals("aem", root.getChildNode("content").getString("site"));
            assertEquals("v1", read(root, "a"));

            // Global writes still work.
            NodeBuilder ok = composite.getRoot().builder();
            ok.child("content").setProperty("edited", true);
            composite.merge(ok, EmptyHook.INSTANCE, CommitInfo.EMPTY);
            assertTrue(composite.getRoot().getChildNode("content").getBoolean("edited"));

            // Writes under the mount are rejected by the composite, not by us.
            NodeBuilder bad = composite.getRoot().builder();
            try {
                bad.child("oak-chain").child("x").setProperty("p", "nope");
                composite.merge(bad, EmptyHook.INSTANCE, CommitInfo.EMPTY);
                fail("write under the read-only mount must fail");
            } catch (CommitFailedException | UnsupportedOperationException expected) {
                // OK
            }

            // Checkpoints (async indexing) live on the global store only and still resolve.
            String cp = composite.checkpoint(60_000);
            NodeState atCp = composite.retrieve(cp);
            assertNotNull(atCp);
            assertEquals("v1", read(atCp, "a"));
            assertTrue(composite.release(cp));

            // Head advances on the validator become visible through the composite.
            write("c", "v3");
            mount.tick();
            assertEquals("v3", read(composite.getRoot(), "c"));

            // Unknown blob references are answered quietly by the mount.
            assertNull(mount.getBlob("does-not-exist"));
        } finally {
            mount.close();
        }
    }

    @Test
    public void externalBinariesStreamFromValidatorBlobEndpoint() throws Exception {
        byte[] data = new byte[64 * 1024];
        new java.util.Random(42).nextBytes(data);
        NodeBuilder b = validator.getRoot().builder();
        Blob written = validator.createBlob(new java.io.ByteArrayInputStream(data));
        b.child("oak-chain").child("bin").setProperty("data", written);
        validator.merge(b, EmptyHook.INSTANCE, CommitInfo.EMPTY);
        validatorStore.flush();
        Blob onValidator = validator.getRoot().getChildNode("oak-chain").getChildNode("bin")
                .getProperty("data").getValue(Type.BINARY);
        assertTrue("fixture must store the binary externally", ((SegmentBlob) onValidator).isExternal());

        startServer();
        LazyRemoteNodeStore mount = newMount();
        try {
            mount.tick();
            MountInfoProvider mip = Mounts.newBuilder().readOnlyMount("oak-chain", "/oak-chain").build();
            CompositeNodeStore composite = new CompositeNodeStore.Builder(mip, new MemoryNodeStore())
                    .addMount("oak-chain", mount)
                    .build();
            Blob blob = composite.getRoot().getChildNode("oak-chain").getChildNode("bin")
                    .getProperty("data").getValue(Type.BINARY);
            assertEquals(data.length, blob.length());
            try (java.io.InputStream in = blob.getNewStream()) {
                org.junit.Assert.assertArrayEquals(data, in.readAllBytes());
            }
        } finally {
            mount.close();
        }
    }

    @Test
    public void outageAfterConnectKeepsHeadAndCachedReadsThenRecovers() throws Exception {
        startServer();
        LazyRemoteNodeStore mount = newMount();
        try {
            mount.tick();
            assertEquals("v1", read(mount.getRoot(), "a"));
            String head = mount.getHead();

            server.stop(0);
            server = null;
            mount.tick();
            assertTrue("stays connected through an outage", mount.isConnected());
            assertEquals(head, mount.getHead());
            assertNotNull(mount.getLastError());
            assertEquals("cached content stays readable", "v1", read(mount.getRoot(), "a"));

            write("d", "v4");
            startServer();
            mount.tick();
            assertNull(mount.getLastError());
            assertEquals("v4", read(mount.getRoot(), "d"));
        } finally {
            mount.close();
        }
    }

    @Test
    public void newestRecordIdTakesLastJournalLine() {
        String journal = "aaaaaaaa-0000-0000-0000-000000000000:10 root 1\n"
                + "bbbbbbbb-0000-0000-0000-000000000000:20 root 2\n\n";
        assertEquals("bbbbbbbb-0000-0000-0000-000000000000:20", LazyRemoteNodeStore.newestRecordId(journal));
        assertNull(LazyRemoteNodeStore.newestRecordId("\n"));
    }

    // --- helpers ---

    private LazyRemoteNodeStore newMount() throws IOException {
        ValidatorHttpClient http = new ValidatorHttpClient("http://127.0.0.1:" + port, null,
                Duration.ofMillis(500), Duration.ofSeconds(5));
        return new LazyRemoteNodeStore("oak-chain", http, tmp.newFolder(), 1, 16);
    }

    private void write(String child, String value) throws Exception {
        NodeBuilder b = validator.getRoot().builder();
        b.child("oak-chain").child(child).setProperty("p", value);
        validator.merge(b, EmptyHook.INSTANCE, CommitInfo.EMPTY);
        validatorStore.flush();
    }

    private static String read(NodeState root, String child) {
        return root.getChildNode("oak-chain").getChildNode(child).getString("p");
    }

    /** Serves the validator's read contract: /journal.log, /manifest, /gc.log, /segments/{uuid}. */
    private void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/journal.log", ex -> file(ex, new File(validatorDir, "journal.log")));
        server.createContext("/manifest", ex -> file(ex, new File(validatorDir, "manifest")));
        server.createContext("/gc.log", ex -> respond(ex, 404, new byte[0]));
        server.createContext("/api/blob/", ex -> {
            // Like the validator: resolves the bare content id (no #length suffix).
            String id = java.net.URLDecoder.decode(
                    ex.getRequestURI().getRawPath().substring("/api/blob/".length()), java.nio.charset.StandardCharsets.UTF_8);
            if (id.contains("#")) {
                respond(ex, 500, new byte[0]);
                return;
            }
            try (java.io.InputStream in = validatorBlobs.getInputStream(id)) {
                respond(ex, 200, in.readAllBytes());
            } catch (IOException e) {
                respond(ex, 404, new byte[0]);
            }
        });
        server.createContext("/segments/", ex -> {
            String id = ex.getRequestURI().getPath().substring("/segments/".length());
            UUID uuid = UUID.fromString(id);
            SegmentId sid = validatorStore.getSegmentIdProvider()
                    .newSegmentId(uuid.getMostSignificantBits(), uuid.getLeastSignificantBits());
            try {
                Segment segment = validatorStore.readSegment(sid);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                segment.writeTo(out);
                respond(ex, 200, out.toByteArray());
            } catch (SegmentNotFoundException e) {
                respond(ex, 404, new byte[0]);
            }
        });
        server.start();
    }

    private static void file(HttpExchange ex, File f) throws IOException {
        if (!f.exists()) {
            respond(ex, 404, new byte[0]);
        } else {
            respond(ex, 200, Files.readAllBytes(f.toPath()));
        }
    }

    private static void respond(HttpExchange ex, int status, byte[] body) throws IOException {
        boolean head = "HEAD".equals(ex.getRequestMethod());
        ex.sendResponseHeaders(status, head || body.length == 0 ? -1 : body.length);
        if (!head && body.length > 0) {
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        }
        ex.close();
    }

    private static int freePort() throws IOException {
        try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
            return s.getLocalPort();
        }
    }
}
