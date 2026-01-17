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
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.jackrabbit.oak.segment.spi.persistence.SegmentNodeStorePersistence;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * OSGi service that provides HTTP-based segment persistence for SegmentNodeStoreFactory.
 *
 * <p>This service registers an {@link HttpPersistence} implementation that can be injected
 * into SegmentNodeStoreFactory when {@code customSegmentStore=true} is configured.</p>
 *
 * <p><strong>Lazy Mount Mode:</strong></p>
 * <p>When {@code lazyMount=true}, the SegmentNodeStorePersistence service is NOT registered
 * immediately. Instead, a background thread checks if the validator is reachable. Once the
 * validator becomes available, the service is registered, triggering the composite mount.
 * This allows Sling to start without blocking even if the validator is down.</p>
 *
 * <p>Configuration properties:</p>
 * <ul>
 *   <li>globalStoreUrl: URL of the GlobalStoreServer (e.g., http://oak-global-store:8090)</li>
 *   <li>lazyMount: If true, defer service registration until validator is reachable (default: false)</li>
 *   <li>healthCheckIntervalSeconds: How often to check validator health in lazy mode (default: 10)</li>
 * </ul>
 */
@Component(
    // NOTE: When lazyMount=true, we register SegmentNodeStorePersistence dynamically
    // When lazyMount=false (default), we act as SegmentNodeStorePersistence immediately
    service = HttpPersistenceService.class,
    configurationPolicy = ConfigurationPolicy.REQUIRE
)
@Designate(ocd = HttpPersistenceService.Configuration.class)
public class HttpPersistenceService implements SegmentNodeStorePersistence {

    @ObjectClassDefinition(
        name = "HTTP Segment Persistence Configuration",
        description = "Configures HTTP-based segment persistence for read-only remote access"
    )
    public @interface Configuration {
        @AttributeDefinition(
            name = "Global Store URL",
            description = "Base URL of the remote GlobalStoreServer (e.g., http://oak-global-store:8090)"
        )
        String globalStoreUrl();

        @AttributeDefinition(
            name = "Lazy Mount",
            description = "If true, defer SegmentNodeStorePersistence registration until validator is reachable. " +
                          "This allows Sling to start even if the validator is down."
        )
        boolean lazyMount() default false;

        @AttributeDefinition(
            name = "Health Check Interval (seconds)",
            description = "How often to check if the validator is reachable (only used when lazyMount=true)"
        )
        int healthCheckIntervalSeconds() default 10;

        @AttributeDefinition(
            name = "Connection Timeout (ms)",
            description = "Timeout for connecting to the validator during health checks"
        )
        int connectionTimeoutMs() default 3000;
    }

    private static final Logger log = LoggerFactory.getLogger(HttpPersistenceService.class);

    private HttpPersistence delegate;
    private String globalStoreUrl;
    private boolean lazyMount;
    private int healthCheckIntervalSeconds;
    private int connectionTimeoutMs;
    
    // For lazy mount mode
    private BundleContext bundleContext;
    private ServiceRegistration<SegmentNodeStorePersistence> persistenceRegistration;
    private Thread healthCheckThread;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicBoolean validatorAvailable = new AtomicBoolean(false);

    @Activate
    protected void activate(BundleContext bundleContext, Configuration config) {
        this.bundleContext = bundleContext;
        this.globalStoreUrl = config.globalStoreUrl();
        this.lazyMount = config.lazyMount();
        this.healthCheckIntervalSeconds = config.healthCheckIntervalSeconds();
        this.connectionTimeoutMs = config.connectionTimeoutMs();

        log.info("Activating HTTP Segment Persistence");
        log.info("  Global Store URL: {}", globalStoreUrl);
        log.info("  Lazy Mount: {}", lazyMount);
        if (lazyMount) {
            log.info("  Health Check Interval: {}s", healthCheckIntervalSeconds);
            log.info("  Connection Timeout: {}ms", connectionTimeoutMs);
        }

        this.delegate = new HttpPersistence(globalStoreUrl);

        if (lazyMount) {
            // Don't block startup - start background health check
            log.info("Lazy mount enabled - starting background validator health check");
            log.info("⏳ SegmentNodeStorePersistence will be registered when validator becomes available");
            startHealthCheckThread();
        } else {
            // Immediate mode - register as SegmentNodeStorePersistence now
            // This maintains backward compatibility
            log.info("Immediate mount mode - registering SegmentNodeStorePersistence now");
            registerPersistenceService();
        }

        log.info("HTTP Segment Persistence activated successfully");
    }

    /**
     * Start background thread to check validator health and register service when ready.
     */
    private void startHealthCheckThread() {
        healthCheckThread = new Thread(() -> {
            log.info("Health check thread started for: {}", globalStoreUrl);
            
            while (running.get() && !validatorAvailable.get()) {
                if (checkValidatorHealth()) {
                    log.info("✅ Validator is now reachable at: {}", globalStoreUrl);
                    validatorAvailable.set(true);
                    registerPersistenceService();
                    log.info("✅ SegmentNodeStorePersistence registered - oak-chain mount will now initialize");
                    break;
                } else {
                    log.debug("Validator not yet reachable, will retry in {}s", healthCheckIntervalSeconds);
                }
                
                try {
                    Thread.sleep(healthCheckIntervalSeconds * 1000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            
            log.info("Health check thread finished");
        }, "http-persistence-health-check");
        
        healthCheckThread.setDaemon(true);
        healthCheckThread.start();
    }

    /**
     * Check if the validator is reachable by making a lightweight HTTP request.
     */
    private boolean checkValidatorHealth() {
        // Use a short timeout for health checks
        RequestConfig requestConfig = RequestConfig.custom()
            .setConnectTimeout(connectionTimeoutMs)
            .setSocketTimeout(connectionTimeoutMs)
            .setConnectionRequestTimeout(connectionTimeoutMs)
            .build();

        try (CloseableHttpClient client = HttpClients.custom()
                .setDefaultRequestConfig(requestConfig)
                .build()) {
            
            // Try to fetch journal.log - this is what the mount will need
            String url = globalStoreUrl + "/journal.log";
            HttpGet request = new HttpGet(url);
            
            try (CloseableHttpResponse response = client.execute(request)) {
                int status = response.getStatusLine().getStatusCode();
                if (status == 200) {
                    return true;
                } else {
                    log.debug("Health check failed: HTTP {} from {}", status, url);
                    return false;
                }
            }
        } catch (IOException e) {
            log.debug("Health check failed: {} - {}", globalStoreUrl, e.getMessage());
            return false;
        }
    }

    /**
     * Register this service as SegmentNodeStorePersistence in OSGi.
     * This triggers SegmentNodeStoreFactory to create the oak-chain NodeStore.
     */
    private void registerPersistenceService() {
        if (persistenceRegistration != null) {
            log.warn("SegmentNodeStorePersistence already registered");
            return;
        }

        Dictionary<String, Object> props = new Hashtable<>();
        props.put("globalStoreUrl", globalStoreUrl);
        
        persistenceRegistration = bundleContext.registerService(
            SegmentNodeStorePersistence.class,
            this,
            props
        );
        
        log.info("Registered SegmentNodeStorePersistence service for: {}", globalStoreUrl);
    }

    /**
     * Unregister the SegmentNodeStorePersistence service from OSGi.
     */
    private void unregisterPersistenceService() {
        if (persistenceRegistration != null) {
            try {
                persistenceRegistration.unregister();
                log.info("Unregistered SegmentNodeStorePersistence service");
            } catch (IllegalStateException e) {
                // Already unregistered
                log.debug("Service was already unregistered");
            }
            persistenceRegistration = null;
        }
    }
    
    /**
     * Check if the validator is currently available (useful for monitoring).
     * @return true if validator is reachable
     */
    public boolean isValidatorAvailable() {
        return validatorAvailable.get();
    }
    
    /**
     * Check if lazy mount mode is enabled.
     * @return true if lazyMount=true in configuration
     */
    public boolean isLazyMount() {
        return lazyMount;
    }
    
    /**
     * Get the global store URL configured for this service.
     * @return The global store URL (e.g., http://oak-global-store:8090)
     */
    public String getGlobalStoreUrl() {
        return globalStoreUrl;
    }

    @Deactivate
    protected void deactivate() {
        log.info("Deactivating HTTP Segment Persistence");
        
        // Stop health check thread
        running.set(false);
        if (healthCheckThread != null) {
            healthCheckThread.interrupt();
            try {
                healthCheckThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        
        // Unregister persistence service
        unregisterPersistenceService();
        
        this.delegate = null;
        log.info("HTTP Segment Persistence deactivated");
    }

    // Delegate all methods to HttpPersistence

    @Override
    public org.apache.jackrabbit.oak.segment.spi.persistence.SegmentArchiveManager createArchiveManager(
            boolean mmap, boolean offHeapAccess,
            org.apache.jackrabbit.oak.segment.spi.monitor.IOMonitor ioMonitor,
            org.apache.jackrabbit.oak.segment.spi.monitor.FileStoreMonitor fileStoreMonitor,
            org.apache.jackrabbit.oak.segment.spi.monitor.RemoteStoreMonitor remoteStoreMonitor) {
        return delegate.createArchiveManager(mmap, offHeapAccess, ioMonitor, fileStoreMonitor, remoteStoreMonitor);
    }

    @Override
    public boolean segmentFilesExist() {
        return delegate.segmentFilesExist();
    }

    @Override
    public org.apache.jackrabbit.oak.segment.spi.persistence.JournalFile getJournalFile() {
        return delegate.getJournalFile();
    }

    @Override
    public org.apache.jackrabbit.oak.segment.spi.persistence.GCJournalFile getGCJournalFile() throws java.io.IOException {
        return delegate.getGCJournalFile();
    }

    @Override
    public org.apache.jackrabbit.oak.segment.spi.persistence.ManifestFile getManifestFile() throws java.io.IOException {
        return delegate.getManifestFile();
    }

    @Override
    public org.apache.jackrabbit.oak.segment.spi.persistence.RepositoryLock lockRepository() throws java.io.IOException {
        return delegate.lockRepository();
    }
}

