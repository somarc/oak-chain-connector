package com.oakchain.connector.mount;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.oakchain.connector.mount.http.HttpBlobStore;
import com.oakchain.connector.mount.http.HttpPersistence;
import com.oakchain.connector.mount.http.ValidatorHttpClient;
import org.apache.jackrabbit.oak.api.Blob;
import org.apache.jackrabbit.oak.api.CommitFailedException;
import org.apache.jackrabbit.oak.plugins.memory.EmptyNodeState;
import org.apache.jackrabbit.oak.segment.SegmentNodeStore;
import org.apache.jackrabbit.oak.segment.SegmentNodeStoreBuilders;
import org.apache.jackrabbit.oak.segment.file.FileStoreBuilder;
import org.apache.jackrabbit.oak.segment.file.ReadOnlyFileStore;
import org.apache.jackrabbit.oak.spi.commit.CommitHook;
import org.apache.jackrabbit.oak.spi.commit.CommitInfo;
import org.apache.jackrabbit.oak.spi.state.NodeBuilder;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.apache.jackrabbit.oak.spi.state.NodeStore;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Read-only {@link NodeStore} over a validator's segment store, connected lazily.
 *
 * <p>Contract with the composite store:
 * <ul>
 *   <li>This object is created once and never replaced. The composite captures it when it
 *       is built, and unbinding a mount provider takes the whole repository down, so
 *       "lazy" lives inside this class rather than in service (un)registration.</li>
 *   <li>Until the validator answers, the root is {@link EmptyNodeState#EMPTY_NODE}: the
 *       mounted path simply does not exist yet.</li>
 *   <li>A single background thread connects and then follows the validator's journal head.
 *       Request threads never fetch the journal. They only fetch segments they read,
 *       which Oak caches.</li>
 *   <li>{@link #getRoot()} never throws: the composite calls it on every root read, for the
 *       whole repository.</li>
 *   <li>Checkpoints: the composite only checkpoints the global store and reads read-only
 *       mounts at their current root, so this store exposes none.</li>
 * </ul>
 */
final class LazyRemoteNodeStore implements NodeStore, Closeable {

    private static final Logger LOG = LoggerFactory.getLogger(LazyRemoteNodeStore.class);

    private final String name;
    private final ValidatorHttpClient http;
    private final File workDir;
    private final int refreshSeconds;
    private final int segmentCacheMb;
    private final ScheduledExecutorService scheduler;

    private volatile Remote remote;
    private volatile NodeState lastRoot;
    private volatile String lastError;
    private boolean connectFailureLogged;
    private boolean refreshFailing;

    /** The live remote view once connected. */
    private static final class Remote {
        final ReadOnlyFileStore fileStore;
        final SegmentNodeStore nodeStore;
        volatile String head;

        Remote(ReadOnlyFileStore fileStore, SegmentNodeStore nodeStore, String head) {
            this.fileStore = fileStore;
            this.nodeStore = nodeStore;
            this.head = head;
        }
    }

    LazyRemoteNodeStore(String name, ValidatorHttpClient http, File workDir, int refreshSeconds, int segmentCacheMb) {
        this.name = name;
        this.http = http;
        this.workDir = workDir;
        this.refreshSeconds = Math.max(1, refreshSeconds);
        this.segmentCacheMb = Math.max(8, segmentCacheMb);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "oak-chain-mount-" + name);
            t.setDaemon(true);
            return t;
        });
    }

    void start() {
        scheduler.scheduleWithFixedDelay(this::tick, 0, refreshSeconds, TimeUnit.SECONDS);
    }

    boolean isConnected() {
        return remote != null;
    }

    String getHead() {
        Remote r = remote;
        return r == null ? null : r.head;
    }

    String getLastError() {
        return lastError;
    }

    /** One background step: connect if needed, otherwise follow the journal head. */
    void tick() {
        try {
            if (remote == null) {
                connect();
            } else {
                refresh(remote);
                if (refreshFailing) {
                    refreshFailing = false;
                    LOG.info("Oak Chain mount '{}': validator {} reachable again at head {}", name, http.getBaseUrl(), remote.head);
                }
            }
            lastError = null;
        } catch (Throwable t) {
            lastError = t.toString();
            if (remote == null) {
                if (!connectFailureLogged) {
                    LOG.info("Oak Chain mount '{}': validator {} not available yet ({}); retrying every {}s",
                            name, http.getBaseUrl(), t.toString(), refreshSeconds);
                    connectFailureLogged = true;
                } else {
                    LOG.debug("Oak Chain mount '{}': connect retry failed", name, t);
                }
            } else if (!refreshFailing) {
                refreshFailing = true;
                LOG.warn("Oak Chain mount '{}': validator unreachable, keeping head {}; cached nodes stay readable, "
                        + "uncached segments and binaries fail until it returns ({})", name, remote.head, t.toString());
            } else {
                LOG.debug("Oak Chain mount '{}': refresh still failing", name, t);
            }
        }
    }

    private void connect() throws Exception {
        if (!workDir.isDirectory() && !workDir.mkdirs()) {
            throw new IOException("Cannot create work directory " + workDir);
        }
        ReadOnlyFileStore fileStore = FileStoreBuilder.fileStoreBuilder(workDir)
                .withCustomPersistence(new HttpPersistence(http))
                .withBlobStore(new HttpBlobStore(http))
                .withMemoryMapping(false)
                .withSegmentCacheSize(segmentCacheMb)
                .withStringCacheSize(Math.max(1, segmentCacheMb / 4))
                .withTemplateCacheSize(Math.max(1, segmentCacheMb / 8))
                .buildReadOnly();
        SegmentNodeStore nodeStore;
        String head;
        try {
            nodeStore = SegmentNodeStoreBuilders.builder(fileStore).build();
            head = fileStore.getRevisions().getHead().toString10();
        } catch (RuntimeException e) {
            fileStore.close();
            throw e;
        }
        remote = new Remote(fileStore, nodeStore, head);
        connectFailureLogged = false;
        LOG.info("Oak Chain mount '{}': connected to {} at head {}", name, http.getBaseUrl(), head);
    }

    private void refresh(Remote r) throws IOException {
        String body = http.getString("/journal.log");
        if (body == null) {
            return;
        }
        String newest = newestRecordId(body);
        if (newest == null || newest.equals(r.head)) {
            return;
        }
        // Only advance to a head whose segment the validator can actually serve.
        String segment = newest.substring(0, newest.indexOf(':'));
        if (!http.exists("/segments/" + segment)) {
            LOG.debug("Oak Chain mount '{}': journal head {} not yet servable", name, newest);
            return;
        }
        r.fileStore.setRevision(newest);
        String previous = r.head;
        r.head = newest;
        LOG.info("Oak Chain mount '{}': head advanced {} -> {}", name, previous, newest);
    }

    /** The validator serves the journal oldest-first; the record id is the first token of a line. */
    static String newestRecordId(String journal) {
        List<String> lines = journal.lines().map(String::trim).filter(l -> !l.isEmpty()).toList();
        if (lines.isEmpty()) {
            return null;
        }
        String last = lines.get(lines.size() - 1);
        int space = last.indexOf(' ');
        String recordId = space < 0 ? last : last.substring(0, space);
        return recordId.indexOf(':') > 0 ? recordId : null;
    }

    @Override
    public NodeState getRoot() {
        Remote r = remote;
        if (r == null) {
            return EmptyNodeState.EMPTY_NODE;
        }
        try {
            NodeState root = r.nodeStore.getRoot();
            lastRoot = root;
            return root;
        } catch (RuntimeException e) {
            LOG.warn("Oak Chain mount '{}': cannot read head {}, serving last known root ({})", name, r.head, e.toString());
            NodeState fallback = lastRoot;
            return fallback == null ? EmptyNodeState.EMPTY_NODE : fallback;
        }
    }

    @Override
    public NodeState merge(@NotNull NodeBuilder builder, @NotNull CommitHook commitHook, @NotNull CommitInfo info)
            throws CommitFailedException {
        throw new CommitFailedException("OakChainMount", 1, "Mount '" + name + "' is read-only");
    }

    @Override
    public NodeState rebase(@NotNull NodeBuilder builder) {
        throw new UnsupportedOperationException("Mount '" + name + "' is read-only");
    }

    @Override
    public NodeState reset(@NotNull NodeBuilder builder) {
        throw new UnsupportedOperationException("Mount '" + name + "' is read-only");
    }

    @Override
    public Blob createBlob(InputStream inputStream) throws IOException {
        throw new IOException("Mount '" + name + "' is read-only");
    }

    @Override
    public Blob getBlob(@NotNull String reference) {
        // The composite asks every mount for unknown references: answer quietly.
        Remote r = remote;
        if (r == null) {
            return null;
        }
        try {
            return r.nodeStore.getBlob(reference);
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public String checkpoint(long lifetime, @NotNull Map<String, String> properties) {
        throw new UnsupportedOperationException("Mount '" + name + "' does not create checkpoints");
    }

    @Override
    public String checkpoint(long lifetime) {
        throw new UnsupportedOperationException("Mount '" + name + "' does not create checkpoints");
    }

    @Override
    public Map<String, String> checkpointInfo(@NotNull String checkpoint) {
        return Collections.emptyMap();
    }

    @Override
    public Iterable<String> checkpoints() {
        return Collections.emptyList();
    }

    @Override
    public NodeState retrieve(@NotNull String checkpoint) {
        return null;
    }

    @Override
    public boolean release(@NotNull String checkpoint) {
        return false;
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
        try {
            scheduler.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        Remote r = remote;
        remote = null;
        if (r != null) {
            r.fileStore.close();
        }
        http.close();
    }

    @Override
    public String toString() {
        return "OakChainMount[" + name + " -> " + http.getBaseUrl() + ", head=" + getHead() + "]";
    }
}
