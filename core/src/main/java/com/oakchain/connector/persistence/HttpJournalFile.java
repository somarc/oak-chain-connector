/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.oakchain.connector.persistence;

import org.apache.jackrabbit.oak.segment.spi.persistence.JournalFile;
import org.apache.jackrabbit.oak.segment.spi.persistence.JournalFileReader;
import org.apache.jackrabbit.oak.segment.spi.persistence.JournalFileWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * HTTP/2-enabled journal file implementation for read-only segment store access.
 */
public class HttpJournalFile implements JournalFile {
    
    private static final Logger log = LoggerFactory.getLogger(HttpJournalFile.class);
    
    private final String baseUrl;
    private final Http2ClientPool http2ClientPool;
    
    public HttpJournalFile(String baseUrl, Http2ClientPool http2ClientPool) {
        this.baseUrl = baseUrl;
        this.http2ClientPool = http2ClientPool;
        log.debug("Initialized HttpJournalFile (HTTP/2) for: {} (stats: {})", baseUrl, http2ClientPool.getPoolStats());
    }
    
    /**
     * Legacy constructor for backward compatibility.
     * @deprecated Use constructor with Http2ClientPool instead
     */
    @Deprecated
    public HttpJournalFile(String baseUrl, HttpClientPool httpClientPool) {
        this(baseUrl, new Http2ClientPool());
        log.warn("Using deprecated HttpClientPool constructor - consider upgrading to Http2ClientPool");
    }
    
    @Override
    public JournalFileReader openJournalReader() throws IOException {
        String url = baseUrl + "/journal.log";
        log.debug("Fetching journal via HTTP/2 from: {}", url);
        
        try {
            String content = http2ClientPool.getString(url);
            List<String> lines = new ArrayList<>(Arrays.asList(content.split("\n")));
            log.debug("Loaded {} journal entries via HTTP/2", lines.size());
            return new HttpJournalFileReader(lines);
        } catch (Exception e) {
            throw new IOException("Failed to fetch journal.log via HTTP/2: " + e.getMessage(), e);
        }
    }
    
    @Override
    public JournalFileWriter openJournalWriter() throws IOException {
        // Read-only mount - return a no-op writer
        // DO NOT call checkWritingAllowed() - it blocks forever!
        // Oak's TarRevisions requires a writer even for read-only stores
        log.debug("Returning no-op journal writer for read-only HTTP mount");
        return new NoOpJournalFileWriter();
    }
    
    /**
     * No-op journal writer for read-only HTTP mounts.
     * All write operations are silently ignored since this is a read-only view.
     */
    private static class NoOpJournalFileWriter implements JournalFileWriter {
        @Override
        public void truncate() throws IOException {
            // No-op for read-only mount
        }

        @Override
        public void writeLine(String line) throws IOException {
            // No-op for read-only mount - writes are silently ignored
        }

        @Override
        public void batchWriteLines(java.util.List<String> lines) throws IOException {
            // No-op for read-only mount - writes are silently ignored
        }

        @Override
        public void close() throws IOException {
            // No-op
        }
    }
    
    @Override
    public String getName() {
        return "journal.log";
    }
    
    @Override
    public boolean exists() {
        String url = baseUrl + "/journal.log";
        return http2ClientPool.exists(url);
    }
    
    /**
     * In-memory journal reader for HTTP-fetched content.
     */
    private static class HttpJournalFileReader implements JournalFileReader {
        private final List<String> lines;
        private int currentIndex = -1;
        
        HttpJournalFileReader(List<String> lines) {
            this.lines = lines;
        }
        
        @Override
        public String readLine() throws IOException {
            currentIndex++;
            if (currentIndex < lines.size()) {
                return lines.get(currentIndex);
            }
            return null;
        }
        
        @Override
        public void close() throws IOException {
            // No-op for in-memory reader
        }
    }
}

