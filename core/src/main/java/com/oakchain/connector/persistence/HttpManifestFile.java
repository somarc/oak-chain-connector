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

import org.apache.jackrabbit.oak.segment.spi.persistence.ManifestFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.StringReader;
import java.util.Properties;

/**
 * HTTP/2-enabled manifest file for read-only access.
 */
public class HttpManifestFile implements ManifestFile {
    
    private static final Logger log = LoggerFactory.getLogger(HttpManifestFile.class);
    
    private final String baseUrl;
    private final Http2ClientPool http2ClientPool;
    
    public HttpManifestFile(String baseUrl, Http2ClientPool http2ClientPool) {
        this.baseUrl = baseUrl;
        this.http2ClientPool = http2ClientPool;
    }
    
    /**
     * Legacy constructor for backward compatibility.
     * @deprecated Use constructor with Http2ClientPool instead
     */
    @Deprecated
    public HttpManifestFile(String baseUrl, HttpClientPool httpClientPool) {
        this(baseUrl, new Http2ClientPool());
        log.warn("Using deprecated HttpClientPool constructor - consider upgrading to Http2ClientPool");
    }
    
    @Override
    public boolean exists() {
        String manifestUrl = baseUrl + "/manifest";
        log.debug("Checking if manifest exists via HTTP/2 at: {}", manifestUrl);
        return http2ClientPool.exists(manifestUrl);
    }
    
    @Override
    public Properties load() throws IOException {
        String url = baseUrl + "/manifest";
        try {
            String content = http2ClientPool.getString(url);
            Properties props = new Properties();
            props.load(new StringReader(content));
            return props;
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("404")) {
                // Manifest doesn't exist - return empty properties
                return new Properties();
            }
            throw new IOException("Failed to fetch manifest via HTTP/2: " + e.getMessage(), e);
        }
    }
    
    @Override
    public void save(Properties properties) throws IOException {
        // No-op for read-only HTTP mount
        // Silently ignore manifest writes - this is expected for read-only stores
        // DO NOT throw exception - Oak initialization requires this to succeed
    }
}

