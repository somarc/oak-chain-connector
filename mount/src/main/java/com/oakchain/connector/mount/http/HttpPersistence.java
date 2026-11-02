package com.oakchain.connector.mount.http;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import org.apache.jackrabbit.oak.segment.spi.monitor.FileStoreMonitor;
import org.apache.jackrabbit.oak.segment.spi.monitor.IOMonitor;
import org.apache.jackrabbit.oak.segment.spi.monitor.RemoteStoreMonitor;
import org.apache.jackrabbit.oak.segment.spi.persistence.GCJournalFile;
import org.apache.jackrabbit.oak.segment.spi.persistence.JournalFile;
import org.apache.jackrabbit.oak.segment.spi.persistence.JournalFileReader;
import org.apache.jackrabbit.oak.segment.spi.persistence.JournalFileWriter;
import org.apache.jackrabbit.oak.segment.spi.persistence.ManifestFile;
import org.apache.jackrabbit.oak.segment.spi.persistence.RepositoryLock;
import org.apache.jackrabbit.oak.segment.spi.persistence.SegmentArchiveManager;
import org.apache.jackrabbit.oak.segment.spi.persistence.SegmentNodeStorePersistence;

/**
 * Read-only {@link SegmentNodeStorePersistence} over a validator's HTTP segment endpoints.
 *
 * <p>This class implements the <em>privately embedded</em> copy of the Segment Tar SPI.
 * It is never registered as an OSGi service: AEM's {@code SegmentNodeStoreService} and
 * {@code SegmentNodeStoreFactory} bind any {@code SegmentNodeStorePersistence} through an
 * untargeted static-greedy reference, which would restart the repository.
 *
 * <p>Every write path is a no-op or throws; the store built on it is a
 * {@code ReadOnlyFileStore}, which never writes.
 */
public final class HttpPersistence implements SegmentNodeStorePersistence {

    private final ValidatorHttpClient http;

    public HttpPersistence(ValidatorHttpClient http) {
        this.http = http;
    }

    @Override
    public SegmentArchiveManager createArchiveManager(boolean memoryMapping, boolean offHeapAccess, IOMonitor ioMonitor,
                                                      FileStoreMonitor fileStoreMonitor, RemoteStoreMonitor remoteStoreMonitor) {
        return new HttpSegmentArchiveManager(http);
    }

    @Override
    public boolean segmentFilesExist() {
        return true;
    }

    @Override
    public JournalFile getJournalFile() {
        return new HttpJournalFile(http);
    }

    @Override
    public GCJournalFile getGCJournalFile() {
        return new GCJournalFile() {
            @Override
            public void writeLine(String line) {
                // read-only
            }

            @Override
            public List<String> readLines() throws IOException {
                String body = http.getString("/gc.log");
                return body == null ? Collections.emptyList() : nonEmptyLines(body);
            }

            @Override
            public void truncate() {
                // read-only
            }
        };
    }

    @Override
    public ManifestFile getManifestFile() {
        return new ManifestFile() {
            @Override
            public boolean exists() {
                try {
                    return http.exists("/manifest");
                } catch (IOException e) {
                    return false;
                }
            }

            @Override
            public Properties load() throws IOException {
                Properties properties = new Properties();
                String body = http.getString("/manifest");
                if (body != null) {
                    properties.load(new StringReader(body));
                }
                return properties;
            }

            @Override
            public void save(Properties properties) {
                // read-only
            }
        };
    }

    @Override
    public RepositoryLock lockRepository() {
        // A remote, read-only view takes no lock on the validator's store.
        return () -> { };
    }

    static List<String> nonEmptyLines(String body) {
        List<String> lines = new ArrayList<>();
        for (String line : body.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                lines.add(trimmed);
            }
        }
        return lines;
    }

    /** Journal served oldest-first by the validator; Oak reads newest-first. */
    static final class HttpJournalFile implements JournalFile {

        private final ValidatorHttpClient http;

        HttpJournalFile(ValidatorHttpClient http) {
            this.http = http;
        }

        @Override
        public JournalFileReader openJournalReader() throws IOException {
            String body = http.getString("/journal.log");
            List<String> lines = body == null ? new ArrayList<>() : nonEmptyLines(body);
            Collections.reverse(lines);
            return new JournalFileReader() {
                private int next;

                @Override
                public String readLine() {
                    return next < lines.size() ? lines.get(next++) : null;
                }

                @Override
                public void close() {
                    // nothing to release
                }
            };
        }

        @Override
        public JournalFileWriter openJournalWriter() {
            return new JournalFileWriter() {
                @Override
                public void truncate() {
                    // read-only
                }

                @Override
                public void writeLine(String line) {
                    // read-only
                }

                @Override
                public void batchWriteLines(List<String> lines) {
                    // read-only
                }

                @Override
                public void close() {
                    // nothing to release
                }
            };
        }

        @Override
        public String getName() {
            return "journal.log";
        }

        @Override
        public boolean exists() {
            return true;
        }
    }
}
