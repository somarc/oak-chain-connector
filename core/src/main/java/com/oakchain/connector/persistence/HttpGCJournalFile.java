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

import org.apache.jackrabbit.oak.segment.spi.persistence.GCJournalFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * HTTP/2-enabled GC journal file for read-only access.
 */
public class HttpGCJournalFile implements GCJournalFile {
    
    private static final Logger log = LoggerFactory.getLogger(HttpGCJournalFile.class);
    
    private final String baseUrl;
    private final Http2ClientPool http2ClientPool;
    
    public HttpGCJournalFile(String baseUrl, Http2ClientPool http2ClientPool) {
        this.baseUrl = baseUrl;
        this.http2ClientPool = http2ClientPool;
    }
    
    /**
     * Legacy constructor for backward compatibility.
     * @deprecated Use constructor with Http2ClientPool instead
     */
    @Deprecated
    public HttpGCJournalFile(String baseUrl, HttpClientPool httpClientPool) {
        this(baseUrl, new Http2ClientPool());
        log.warn("Using deprecated HttpClientPool constructor - consider upgrading to Http2ClientPool");
    }
    
    @Override
    public void writeLine(String line) throws IOException {
        // No-op for read-only HTTP mount
        // Silently ignore GC journal writes - this is expected for read-only stores
    }
    
    @Override
    public List<String> readLines() throws IOException {
        String url = baseUrl + "/gc.log";
        try {
            String content = http2ClientPool.getString(url);
            return new ArrayList<>(Arrays.asList(content.split("\n")));
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("404")) {
                // GC journal doesn't exist yet - return empty
                return new ArrayList<>();
            }
            throw new IOException("Failed to fetch gc.log via HTTP/2: " + e.getMessage(), e);
        }
    }
    
    @Override
    public void truncate() throws IOException {
        // No-op for read-only HTTP mount
        // Silently ignore truncation - this is expected for read-only stores
    }
}

