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

import org.apache.jackrabbit.oak.segment.spi.monitor.FileStoreMonitor;
import org.apache.jackrabbit.oak.segment.spi.monitor.IOMonitor;
import org.apache.jackrabbit.oak.segment.spi.monitor.RemoteStoreMonitor;
import org.apache.jackrabbit.oak.segment.spi.persistence.GCJournalFile;
import org.apache.jackrabbit.oak.segment.spi.persistence.JournalFile;
import org.apache.jackrabbit.oak.segment.spi.persistence.ManifestFile;
import org.apache.jackrabbit.oak.segment.spi.persistence.RepositoryLock;
import org.apache.jackrabbit.oak.segment.spi.persistence.SegmentArchiveManager;
import org.apache.jackrabbit.oak.segment.spi.persistence.SegmentNodeStorePersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * HTTP/2-enabled persistence for remote segment stores.
 * 
 * <p>This implementation enables read-only access to a remote segment store
 * via HTTP/2, suitable for mounting global blockchain stores in Blockchain AEM.</p>
 * 
 * <p><strong>HTTP/2 Benefits:</strong></p>
 * <ul>
 *   <li>Multiplexing: Multiple segment requests on single connection</li>
 *   <li>Header compression: Reduced overhead for repeated requests</li>
 *   <li>20-30% latency improvement over HTTP/1.1</li>
 *   <li>Falls back to HTTP/1.1 if server doesn't support HTTP/2</li>
 * </ul>
 * 
 * <p><strong>Design:</strong></p>
 * <ul>
 *   <li>Segments fetched on-demand via HTTP/2</li>
 *   <li>Read-only access (no writes supported)</li>
 *   <li>Journal/manifest handled via HTTP endpoints</li>
 *   <li>No repository locking (read-only mount)</li>
 * </ul>
 */
public class HttpPersistence implements SegmentNodeStorePersistence {
    
    private static final Logger log = LoggerFactory.getLogger(HttpPersistence.class);
    
    private final String baseUrl;
    private final Http2ClientPool http2ClientPool;
    
    /**
     * Create a new HTTP/2 persistence layer.
     * 
     * @param baseUrl Base URL of the GlobalStoreServer (e.g., "http://oak-global-store:8090")
     */
    public HttpPersistence(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.http2ClientPool = new Http2ClientPool();
        // HTTP persistence is read-only by design
        log.info("Initialized HttpPersistence (HTTP/2) for: {}", this.baseUrl);
        log.info("HTTP/2 Client Pool: {}", http2ClientPool.getPoolStats());
    }
    
    @Override
    public SegmentArchiveManager createArchiveManager(boolean mmap, boolean offHeapAccess, 
                                                     IOMonitor ioMonitor, 
                                                     FileStoreMonitor fileStoreMonitor,
                                                     RemoteStoreMonitor remoteStoreMonitor) {
        log.debug("Creating HttpSegmentArchiveManager (HTTP/2, mmap={}, offHeapAccess={})", mmap, offHeapAccess);
        try {
            HttpSegmentArchiveManager manager = new HttpSegmentArchiveManager(baseUrl, ioMonitor, http2ClientPool);
            log.debug("HttpSegmentArchiveManager (HTTP/2) created successfully");
            return manager;
        } catch (Exception e) {
            log.error("Failed to create HttpSegmentArchiveManager", e);
            throw e;
        }
    }
    
    @Override
    public boolean segmentFilesExist() {
        log.debug("Checking if segment files exist at: {}", baseUrl);
        // Assume segments exist if server is reachable
        // In production, would check via HTTP HEAD to /archives endpoint
        return true;
    }
    
    @Override
    public JournalFile getJournalFile() {
        log.debug("Creating HttpJournalFile (HTTP/2) for: {}", baseUrl);
        try {
            HttpJournalFile journalFile = new HttpJournalFile(baseUrl, http2ClientPool);
            return journalFile;
        } catch (Exception e) {
            log.error("Failed to create HttpJournalFile", e);
            throw e;
        }
    }
    
    @Override
    public GCJournalFile getGCJournalFile() throws IOException {
        log.debug("Creating HttpGCJournalFile (HTTP/2) for: {}", baseUrl);
        try {
            HttpGCJournalFile gcFile = new HttpGCJournalFile(baseUrl, http2ClientPool);
            return gcFile;
        } catch (Exception e) {
            log.error("Failed to create HttpGCJournalFile", e);
            throw e;
        }
    }
    
    @Override
    public ManifestFile getManifestFile() throws IOException {
        log.debug("Creating HttpManifestFile (HTTP/2) for: {}", baseUrl);
        try {
            HttpManifestFile manifestFile = new HttpManifestFile(baseUrl, http2ClientPool);
            return manifestFile;
        } catch (Exception e) {
            log.error("Failed to create HttpManifestFile", e);
            throw e;
        }
    }
    
    @Override
    public RepositoryLock lockRepository() throws IOException {
        log.debug("Creating no-op repository lock for read-only HTTP store");
        // Read-only mount doesn't need locking
        return new NoOpRepositoryLock();
    }
    
    /**
     * No-op repository lock for read-only mounts.
     */
    private static class NoOpRepositoryLock implements RepositoryLock {
        @Override
        public void unlock() throws IOException {
            // No-op
        }
    }
}

