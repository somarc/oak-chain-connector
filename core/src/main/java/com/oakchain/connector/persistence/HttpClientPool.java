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

import org.apache.http.client.config.RequestConfig;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Shared HTTP client pool for efficient connection management.
 * 
 * <p>Provides a properly configured Apache HttpClient with:
 * <ul>
 *   <li>Connection pooling (reuse existing connections)</li>
 *   <li>Configurable pool size and timeouts</li>
 *   <li>Automatic connection eviction for stale connections</li>
 *   <li>Metrics tracking for pool usage</li>
 * </ul>
 * 
 * <p><strong>Performance Impact:</strong></p>
 * <ul>
 *   <li>Eliminates TCP handshake overhead (~10-20ms per request)</li>
 *   <li>Reduces memory footprint (shared connections)</li>
 *   <li>Better resource utilization under load</li>
 * </ul>
 */
public class HttpClientPool {
    
    private static final Logger log = LoggerFactory.getLogger(HttpClientPool.class);
    
    // Connection pool configuration
    private static final int MAX_TOTAL_CONNECTIONS = 200;          // Total pool size
    private static final int MAX_PER_ROUTE_CONNECTIONS = 50;       // Per-host limit
    private static final int CONNECTION_TIMEOUT_MS = 5000;         // 5s connect timeout
    private static final int SOCKET_TIMEOUT_MS = 30000;            // 30s read timeout
    private static final int CONNECTION_REQUEST_TIMEOUT_MS = 3000; // 3s wait for connection from pool
    private static final int IDLE_CONNECTION_EVICTION_MS = 60000;  // Evict idle connections after 60s
    
    private final PoolingHttpClientConnectionManager connectionManager;
    private final CloseableHttpClient httpClient;
    private final ConnectionPoolMonitor poolMonitor;
    
    /**
     * Create a new HTTP client pool with default configuration.
     */
    public HttpClientPool() {
        this(MAX_TOTAL_CONNECTIONS, MAX_PER_ROUTE_CONNECTIONS);
    }
    
    /**
     * Create a new HTTP client pool with custom configuration.
     * 
     * @param maxTotal Maximum total connections in the pool
     * @param maxPerRoute Maximum connections per route (host:port)
     */
    public HttpClientPool(int maxTotal, int maxPerRoute) {
        log.info("Initializing HTTP Client Pool (maxTotal={}, maxPerRoute={}, connectTimeout={}ms, socketTimeout={}ms)", 
                 maxTotal, maxPerRoute, CONNECTION_TIMEOUT_MS, SOCKET_TIMEOUT_MS);
        
        // Create connection pool manager
        this.connectionManager = new PoolingHttpClientConnectionManager();
        this.connectionManager.setMaxTotal(maxTotal);
        this.connectionManager.setDefaultMaxPerRoute(maxPerRoute);
        
        // Configure request defaults
        RequestConfig requestConfig = RequestConfig.custom()
            .setConnectTimeout(CONNECTION_TIMEOUT_MS)
            .setSocketTimeout(SOCKET_TIMEOUT_MS)
            .setConnectionRequestTimeout(CONNECTION_REQUEST_TIMEOUT_MS)
            .build();
        
        // Build HTTP client with pooling
        this.httpClient = HttpClients.custom()
            .setConnectionManager(connectionManager)
            .setDefaultRequestConfig(requestConfig)
            .evictIdleConnections(IDLE_CONNECTION_EVICTION_MS, TimeUnit.MILLISECONDS)
            .evictExpiredConnections()
            .build();
        
        // Start pool monitoring
        this.poolMonitor = new ConnectionPoolMonitor(connectionManager);
        this.poolMonitor.start();
        
        log.info("HTTP Client Pool initialized successfully");
    }
    
    /**
     * Get the shared HTTP client.
     * 
     * @return The pooled HTTP client (do NOT close this instance)
     */
    public CloseableHttpClient getHttpClient() {
        return httpClient;
    }
    
    /**
     * Get the connection manager for metrics.
     * 
     * @return The pool connection manager
     */
    public PoolingHttpClientConnectionManager getConnectionManager() {
        return connectionManager;
    }
    
    /**
     * Get current pool statistics.
     * 
     * @return Pool stats as formatted string
     */
    public String getPoolStats() {
        return poolMonitor.getPoolStats();
    }
    
    /**
     * Shutdown the HTTP client pool and release all connections.
     */
    public void shutdown() {
        log.info("Shutting down HTTP Client Pool");
        poolMonitor.shutdown();
        try {
            httpClient.close();
        } catch (IOException e) {
            log.warn("Error closing HTTP client: {}", e.getMessage());
        }
        connectionManager.close();
        log.info("HTTP Client Pool shutdown complete");
    }
    
    /**
     * Internal monitor for connection pool metrics.
     */
    private static class ConnectionPoolMonitor extends Thread {
        
        private static final Logger log = LoggerFactory.getLogger(ConnectionPoolMonitor.class);
        private static final long MONITOR_INTERVAL_MS = 60000; // Log stats every 60s
        
        private final PoolingHttpClientConnectionManager connectionManager;
        private volatile boolean running = true;
        
        public ConnectionPoolMonitor(PoolingHttpClientConnectionManager connectionManager) {
            super("http-pool-monitor");
            this.connectionManager = connectionManager;
            this.setDaemon(true);
        }
        
        @Override
        public void run() {
            while (running) {
                try {
                    Thread.sleep(MONITOR_INTERVAL_MS);
                    logPoolStats();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        
        private void logPoolStats() {
            int leased = connectionManager.getTotalStats().getLeased();
            int pending = connectionManager.getTotalStats().getPending();
            int available = connectionManager.getTotalStats().getAvailable();
            int max = connectionManager.getTotalStats().getMax();
            
            log.debug("HTTP Pool Stats: Leased={}, Pending={}, Available={}, Max={}", 
                leased, pending, available, max);
            
            // Note: Prometheus metrics are updated by SegmentHttpServer if present
            // This allows connection pooling to work in standalone oak-segment-http
            // without requiring oak-segment-consensus dependency
        }
        
        public String getPoolStats() {
            int leased = connectionManager.getTotalStats().getLeased();
            int pending = connectionManager.getTotalStats().getPending();
            int available = connectionManager.getTotalStats().getAvailable();
            int max = connectionManager.getTotalStats().getMax();
            
            return String.format("Leased=%d, Pending=%d, Available=%d, Max=%d", 
                leased, pending, available, max);
        }
        
        public void shutdown() {
            running = false;
            this.interrupt();
        }
    }
}

