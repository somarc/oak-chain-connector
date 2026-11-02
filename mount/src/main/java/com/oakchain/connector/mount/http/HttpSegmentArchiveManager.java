package com.oakchain.connector.mount.http;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.apache.jackrabbit.oak.segment.spi.persistence.SegmentArchiveManager;
import org.apache.jackrabbit.oak.segment.spi.persistence.SegmentArchiveReader;
import org.apache.jackrabbit.oak.segment.spi.persistence.SegmentArchiveWriter;
import org.jetbrains.annotations.NotNull;

/**
 * Presents the whole remote store as one logical, read-only archive.
 *
 * <p>The validator serves any segment by UUID regardless of which TAR file holds it,
 * so a single archive that proxies {@code /segments/{uuid}} reaches every segment,
 * including segments written after this archive was opened.
 */
final class HttpSegmentArchiveManager implements SegmentArchiveManager {

    static final String ARCHIVE_NAME = "data00000a.tar";

    private final ValidatorHttpClient http;

    HttpSegmentArchiveManager(ValidatorHttpClient http) {
        this.http = http;
    }

    @Override
    public List<String> listArchives() {
        return Collections.singletonList(ARCHIVE_NAME);
    }

    @Override
    public SegmentArchiveReader open(@NotNull String archiveName) {
        return new HttpSegmentArchiveReader(http, archiveName);
    }

    @Override
    public SegmentArchiveReader forceOpen(String archiveName) {
        return open(archiveName);
    }

    @Override
    public SegmentArchiveWriter create(@NotNull String archiveName) throws IOException {
        throw new IOException("Oak Chain mount is read-only; cannot create archive " + archiveName);
    }

    @Override
    public boolean delete(@NotNull String archiveName) {
        return false;
    }

    @Override
    public boolean renameTo(@NotNull String from, @NotNull String to) {
        return false;
    }

    @Override
    public void copyFile(@NotNull String from, @NotNull String to) throws IOException {
        throw new IOException("Oak Chain mount is read-only");
    }

    @Override
    public boolean exists(@NotNull String archiveName) {
        return ARCHIVE_NAME.equals(archiveName);
    }

    @Override
    public void recoverEntries(@NotNull String archiveName, @NotNull LinkedHashMap<UUID, byte[]> entries) throws IOException {
        throw new IOException("Oak Chain mount is read-only");
    }

    @Override
    public void backup(@NotNull String archiveName, @NotNull String backupArchiveName, @NotNull Set<UUID> recoveredEntries) throws IOException {
        throw new IOException("Oak Chain mount is read-only");
    }

    @Override
    public boolean isReadOnly(String archiveName) {
        return true;
    }
}
