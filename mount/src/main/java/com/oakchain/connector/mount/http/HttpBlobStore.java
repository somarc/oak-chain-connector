package com.oakchain.connector.mount.http;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import org.apache.jackrabbit.oak.spi.blob.BlobOptions;
import org.apache.jackrabbit.oak.spi.blob.BlobStore;
import org.jetbrains.annotations.NotNull;

/**
 * Read-only {@link BlobStore} for external binaries referenced from the validator's segments,
 * streamed from {@code GET /api/blob/{contentHash}}.
 *
 * <p>Private to the mount's {@code ReadOnlyFileStore}; never registered as an OSGi service
 * (AEM's node store services bind {@code BlobStore} static-greedy).
 *
 * <p>Oak data-store blob ids carry a length suffix ({@code <hash>#<length>}); the validator
 * resolves the bare content hash, and the suffix answers {@link #getBlobLength} without I/O.
 */
public final class HttpBlobStore implements BlobStore {

    private final ValidatorHttpClient http;

    public HttpBlobStore(ValidatorHttpClient http) {
        this.http = http;
    }

    static String contentHash(String blobId) {
        int hash = blobId.indexOf('#');
        return hash < 0 ? blobId : blobId.substring(0, hash);
    }

    @Override
    public InputStream getInputStream(String blobId) throws IOException {
        InputStream in = http.getStream("/api/blob/" + URLEncoder.encode(contentHash(blobId), StandardCharsets.UTF_8));
        if (in == null) {
            throw new IOException("Blob not found on validator: " + blobId);
        }
        return in;
    }

    @Override
    public long getBlobLength(String blobId) throws IOException {
        int hash = blobId.indexOf('#');
        if (hash >= 0) {
            try {
                return Long.parseLong(blobId.substring(hash + 1));
            } catch (NumberFormatException ignored) {
                // fall through to counting
            }
        }
        try (InputStream in = getInputStream(blobId)) {
            return in.transferTo(OutputStreamNull.INSTANCE);
        }
    }

    @Override
    public int readBlob(String blobId, long pos, byte[] buff, int off, int length) throws IOException {
        try (InputStream in = getInputStream(blobId)) {
            in.skipNBytes(pos);
            int n = in.readNBytes(buff, off, length);
            return n == 0 && length > 0 ? -1 : n;
        }
    }

    @Override
    public String writeBlob(InputStream in) throws IOException {
        throw new IOException("Oak Chain mount is read-only");
    }

    @Override
    public String writeBlob(InputStream in, BlobOptions options) throws IOException {
        throw new IOException("Oak Chain mount is read-only");
    }

    @Override
    public String getBlobId(@NotNull String reference) {
        return null;
    }

    @Override
    public String getReference(@NotNull String blobId) {
        return null;
    }

    @Override
    public void close() {
        // the HTTP client is owned by the mount
    }

    private static final class OutputStreamNull extends java.io.OutputStream {
        static final OutputStreamNull INSTANCE = new OutputStreamNull();

        @Override
        public void write(int b) {
        }

        @Override
        public void write(byte[] b, int off, int len) {
        }
    }
}
