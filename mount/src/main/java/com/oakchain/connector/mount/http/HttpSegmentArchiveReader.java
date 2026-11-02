package com.oakchain.connector.mount.http;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.jackrabbit.oak.commons.Buffer;
import org.apache.jackrabbit.oak.segment.file.tar.SegmentGraph;
import org.apache.jackrabbit.oak.segment.spi.persistence.SegmentArchiveEntry;
import org.apache.jackrabbit.oak.segment.spi.persistence.SegmentArchiveReader;

/**
 * Reads segments by UUID from {@code GET /segments/{uuid}}.
 *
 * <p>No index, graph or binary-reference sidecars are exposed by the validator, so those
 * are empty; Oak's read path only needs {@link #containsSegment} and {@link #readSegment}.
 * Segments are immutable, so positive existence answers are cached.
 */
final class HttpSegmentArchiveReader implements SegmentArchiveReader {

    private final ValidatorHttpClient http;
    private final String archiveName;
    private final Set<UUID> known = ConcurrentHashMap.newKeySet();

    HttpSegmentArchiveReader(ValidatorHttpClient http, String archiveName) {
        this.http = http;
        this.archiveName = archiveName;
    }

    @Override
    public Buffer readSegment(long msb, long lsb) throws IOException {
        UUID id = new UUID(msb, lsb);
        byte[] data = http.getBytes("/segments/" + id);
        if (data == null) {
            return null;
        }
        known.add(id);
        return Buffer.wrap(data);
    }

    @Override
    public boolean containsSegment(long msb, long lsb) {
        UUID id = new UUID(msb, lsb);
        if (known.contains(id)) {
            return true;
        }
        try {
            if (http.exists("/segments/" + id)) {
                known.add(id);
                return true;
            }
            return false;
        } catch (IOException e) {
            // Unreachable validator: report absent; the caller surfaces SegmentNotFound.
            return false;
        }
    }

    @Override
    public List<SegmentArchiveEntry> listSegments() {
        return Collections.emptyList();
    }

    @Override
    public SegmentGraph getGraph() {
        return new SegmentGraph();
    }

    @Override
    public Buffer getBinaryReferences() {
        return null;
    }

    @Override
    public long length() {
        return 0;
    }

    @Override
    public String getName() {
        return archiveName;
    }

    @Override
    public void close() {
        known.clear();
    }

    @Override
    public int getEntrySize(int size) {
        return size;
    }

    @Override
    public boolean isRemote() {
        return true;
    }
}
