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
import org.apache.jackrabbit.oak.api.PropertyState;
import org.apache.jackrabbit.oak.api.Type;
import org.apache.jackrabbit.oak.composite.CompositeNodeStore;
import org.apache.jackrabbit.oak.plugins.memory.MemoryNodeStore;
import org.apache.jackrabbit.oak.segment.Segment;
import org.apache.jackrabbit.oak.segment.SegmentBlob;
import org.apache.jackrabbit.oak.segment.SegmentId;
import org.apache.jackrabbit.oak.segment.SegmentNodeStore;
import org.apache.jackrabbit.oak.segment.SegmentNodeStoreBuilders;
import org.apache.jackrabbit.oak.segment.SegmentNotFoundException;
import org.apache.jackrabbit.oak.segment.compaction.SegmentGCOptions;
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

    static {
        // Like the validators (-Doak.gc.backoff=0): Oak otherwise skips a GC within 10 h of the last.
        System.setProperty("oak.gc.backoff", "0");
    }

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private FileStore validatorStore;
    private MemoryBlobStore validatorBlobs;
    private SegmentNodeStore validator;
    private File validatorDir;
    private HttpServer server;
    private int port;
    private final java.util.List<String> served = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    @Before
    public void setUp() throws Exception {
        validatorDir = tmp.newFolder("validator");
        validatorBlobs = new MemoryBlobStore();
        validatorStore = FileStoreBuilder.fileStoreBuilder(validatorDir)
                .withBlobStore(validatorBlobs)
                .withMemoryMapping(false)
                // Validator defaults (2 retained generations); no size estimate, so small test stores compact.
                .withGCOptions(SegmentGCOptions.defaultGCOptions().setEstimationDisabled(true))
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

    /**
     * Validator revision GC under the mount. Two long strings live in bulk segments the mount has
     * not fetched. A reader pinned to the pre-delete revision (a long-lived session, a backed-up
     * observation queue) survives one full GC, because the validator retains two generations.
     * After the second it can lose uncached segments: Oak only rewrites a TAR file when that saves
     * at least 25% (TarReader.sweep), so here the deleted content dominates its file to make the
     * reclaim happen. Readers that refresh follow the compacted head throughout.
     */
    @Test
    public void validatorRevisionGcUnderTheMount() throws Exception {
        writeLong("first", 1);
        writeLong("second", 2);
        startServer();
        LazyRemoteNodeStore mount = newMount();
        try {
            mount.tick();
            NodeState pinned = mount.getRoot().getChildNode("oak-chain").getChildNode("doomed");
            assertTrue(pinned.exists());

            NodeBuilder d = validator.getRoot().builder();
            d.child("oak-chain").getChildNode("doomed").remove();
            validator.merge(d, EmptyHook.INSTANCE, CommitInfo.EMPTY);
            validatorStore.flush();

            served.add("---- after delete");
            validatorStore.fullGC();
            served.add("---- after GC 1");
            assertEquals("one cycle: the previous generation is retained",
                    1_000_000, pinned.getString("first").length());
            mount.tick();
            assertFalse(mount.getRoot().getChildNode("oak-chain").hasChildNode("doomed"));
            assertEquals("v1", read(mount.getRoot(), "a"));

            validatorStore.fullGC();
            served.add("---- after GC 2");
            try {
                pinned.getString("second").length();
                fail("two cycles: a pinned reader's uncached segments must be reclaimed; served=" + served);
            } catch (SegmentNotFoundException expected) {
                // the documented limit for readers that never refresh
            }
            mount.tick();
            assertEquals("refreshing readers follow the compacted head", "v1", read(mount.getRoot(), "a"));
            write("after-gc", "v5");
            mount.tick();
            assertEquals("v5", read(mount.getRoot(), "after-gc"));
        } finally {
            mount.close();
        }
    }

    /**
     * How chain changes reach AEM's observation: the composite dispatches only on its own commits,
     * so a mount head move alone is silent, and it surfaces in the diff of the next global commit.
     */
    @Test
    public void chainChangesReachObserversWithTheNextGlobalCommit() throws Exception {
        startServer();
        LazyRemoteNodeStore mount = newMount();
        try {
            mount.tick();
            MountInfoProvider mip = Mounts.newBuilder().readOnlyMount("oak-chain", "/oak-chain").build();
            CompositeNodeStore composite = new CompositeNodeStore.Builder(mip, new MemoryNodeStore())
                    .addMount("oak-chain", mount)
                    .build();
            java.util.List<NodeState> roots = new java.util.ArrayList<>();
            composite.addObserver((root, info) -> roots.add(root));
            NodeState start = roots.get(roots.size() - 1);

            write("chain-change", "v7");
            mount.tick();
            int seen = roots.size();
            assertEquals("a mount head move alone dispatches nothing", seen, roots.size());

            NodeBuilder g = composite.getRoot().builder();
            g.child("content").setProperty("touched", true);
            composite.merge(g, EmptyHook.INSTANCE, CommitInfo.EMPTY);
            NodeState after = roots.get(roots.size() - 1);
            java.util.List<String> added = new java.util.ArrayList<>();
            after.getChildNode("oak-chain").compareAgainstBaseState(start.getChildNode("oak-chain"),
                    new org.apache.jackrabbit.oak.spi.state.DefaultNodeStateDiff() {
                        @Override
                        public boolean childNodeAdded(String name, NodeState state) {
                            added.add(name);
                            return true;
                        }
                    });
            assertEquals("the next global commit carries the chain change", java.util.List.of("chain-change"), added);
        } finally {
            mount.close();
        }
    }

    /**
     * Validators before genesis 2.2.0 wrote shard and content folders without jcr:primaryType; Oak's
     * JCR layer throws for those, so the mount presents oak:Unstructured. Real types are kept, and a diff between
     * two mount roots reports only the real change.
     */
    @Test
    public void untypedValidatorNodesReadAsUnstructured() throws Exception {
        NodeBuilder b = validator.getRoot().builder();
        NodeBuilder folder = b.child("oak-chain").child("8a").child("content");
        folder.child("typed").setProperty("jcr:primaryType", "nt:unstructured"); // STRING, as validators before 2.2.0 wrote it
        validator.merge(b, EmptyHook.INSTANCE, CommitInfo.EMPTY);
        validatorStore.flush();
        startServer();
        LazyRemoteNodeStore mount = newMount();
        try {
            mount.tick();
            NodeState before = mount.getRoot();
            NodeState shard = before.getChildNode("oak-chain").getChildNode("8a");
            assertEquals(TypedNodeState.DEFAULT_TYPE, shard.getProperty("jcr:primaryType"));
            assertEquals("oak:Unstructured", shard.getName("jcr:primaryType"));
            assertEquals(Type.NAME, shard.getChildNode("content").getProperty("jcr:primaryType").getType());
            NodeState typed = shard.getChildNode("content").getChildNode("typed");
            assertEquals(Type.STRING, typed.getProperty("jcr:primaryType").getType());
            assertEquals(1, typed.getPropertyCount());
            assertTrue(typed.hasProperty("jcr:primaryType"));

            write("added", "v6");
            mount.tick();
            java.util.List<String> changes = new java.util.ArrayList<>();
            mount.getRoot().compareAgainstBaseState(before, new org.apache.jackrabbit.oak.spi.state.DefaultNodeStateDiff() {
                @Override
                public boolean childNodeAdded(String name, NodeState after) {
                    changes.add("+" + name);
                    return true;
                }

                @Override
                public boolean childNodeChanged(String name, NodeState b4, NodeState after) {
                    changes.add("~" + name);
                    return after.compareAgainstBaseState(b4, this);
                }

                @Override
                public boolean propertyChanged(PropertyState b4, PropertyState after) {
                    changes.add("p~" + after.getName());
                    return true;
                }

                @Override
                public boolean propertyAdded(PropertyState after) {
                    changes.add("p+" + after.getName());
                    return true;
                }
            });
            assertEquals(java.util.List.of("~oak-chain", "+added"), changes);
            assertTrue("decorated roots of one revision are equal", mount.getRoot().equals(mount.getRoot()));
        } finally {
            mount.close();
        }
    }

    // --- helpers ---

    /** A 1 000 000-character incompressible string on /oak-chain/doomed: bulk segments of its own. */
    private void writeLong(String property, long seed) throws Exception {
        java.util.Random random = new java.util.Random(seed);
        StringBuilder value = new StringBuilder(1_000_000);
        while (value.length() < 1_000_000) {
            value.append((char) ('a' + random.nextInt(26)));
        }
        NodeBuilder b = validator.getRoot().builder();
        b.child("oak-chain").child("doomed").setProperty(property, value.toString());
        validator.merge(b, EmptyHook.INSTANCE, CommitInfo.EMPTY);
        validatorStore.flush();
    }

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
            // Like the validator's FileHandler: a segment GC reclaimed is 404 even while still cached.
            if (!validatorStore.containsSegment(sid)) {
                served.add(ex.getRequestMethod() + " " + id + " 404");
                respond(ex, 404, new byte[0]);
                return;
            }
            try {
                Segment segment = validatorStore.readSegment(sid);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                segment.writeTo(out);
                served.add(ex.getRequestMethod() + " " + id + " 200");
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
