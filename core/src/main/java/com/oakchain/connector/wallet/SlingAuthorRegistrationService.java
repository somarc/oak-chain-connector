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
package com.oakchain.connector.wallet;

import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Service that registers Sling author with validator, including wallet address.
 * 
 * <p>This service orchestrates the registration of a Sling author instance with
 * a validator, including the wallet address for signed transactions.</p>
 * 
 * <p><strong>Registration Flow:</strong></p>
 * <ol>
 *   <li>Sling author loads/generates wallet (via SlingAuthorWalletService)</li>
 *   <li>This service registers with validator at /v1/register-client</li>
 *   <li>Registration includes: clientId, clientUrl, walletAddress</li>
 *   <li>Validator stores registration for signature verification</li>
 * </ol>
 * 
 * <p>Registration happens automatically when both wallet service and validator URL are available.</p>
 */
@Component(
    service = SlingAuthorRegistrationService.class,
    configurationPolicy = ConfigurationPolicy.OPTIONAL,
    immediate = true,
    property = {
        "service.ranking:Integer=100"  // Lower priority - activate AFTER wallet service
    }
)
@Designate(ocd = SlingAuthorRegistrationService.Configuration.class)
public class SlingAuthorRegistrationService {
    
    private static final Logger log = LoggerFactory.getLogger(SlingAuthorRegistrationService.class);
    
    @ObjectClassDefinition(
        name = "Sling Author Registration Service",
        description = "Registers Sling author with validator, including wallet address"
    )
    @interface Configuration {
        @AttributeDefinition(
            name = "Validator URL",
            description = "URL of the validator to register with (e.g., http://oak-global-store:8090)"
        )
        String validatorUrl() default "";
        
        @AttributeDefinition(
            name = "Client ID",
            description = "Client identifier for this Sling author instance (defaults to HOSTNAME)"
        )
        String clientId() default "";
        
        @AttributeDefinition(
            name = "Client URL",
            description = "URL of this Sling author instance (defaults to http://HOSTNAME:8080)"
        )
        String clientUrl() default "";
        
        @AttributeDefinition(
            name = "Enabled",
            description = "Enable automatic registration"
        )
        boolean enabled() default true;
    }
    
    @Reference(
        cardinality = ReferenceCardinality.MANDATORY,  // REQUIRED - don't activate without wallet
        policy = ReferencePolicy.DYNAMIC,
        bind = "bindWalletService",
        unbind = "unbindWalletService"
    )
    private volatile SlingAuthorWalletService walletService;
    
    @Reference(cardinality = ReferenceCardinality.OPTIONAL, policy = ReferencePolicy.DYNAMIC, 
               target = "(component.name=com.oakchain.connector.persistence.HttpPersistenceService)",
               bind = "bindHttpPersistenceService",
               unbind = "unbindHttpPersistenceService")
    private volatile com.oakchain.connector.persistence.HttpPersistenceService httpPersistenceService;
    
    private String validatorUrl;
    private String clientId;
    private String clientUrl;
    private boolean enabled;
    private boolean registered = false;
    
    @Activate
    protected void activate(Configuration config) {
        this.validatorUrl = config.validatorUrl();
        this.clientId = config.clientId();
        this.clientUrl = config.clientUrl();
        this.enabled = config.enabled();
        
        if (!enabled) {
            log.info("🔐 Sling Author Registration Service disabled");
            return;
        }
        
        // Determine client ID if not provided
        if (clientId == null || clientId.isEmpty()) {
            String hostname = System.getenv("HOSTNAME");
            if (hostname != null && !hostname.isEmpty()) {
                clientId = hostname;
            } else {
                clientId = System.getProperty("sling.instance.id", "sling-author");
            }
        }
        
        // Determine client URL if not provided
        if (clientUrl == null || clientUrl.isEmpty()) {
            String hostname = System.getenv("HOSTNAME");
            if (hostname != null && !hostname.isEmpty()) {
                clientUrl = "http://" + hostname + ":8080";
            } else {
                clientUrl = System.getProperty("sling.server.url", "http://localhost:8080");
            }
        }
        
        // Try to get validator URL from HttpPersistenceService if not configured
        // Since HttpPersistenceService uses globalStoreUrl config, we can use the same URL
        if (validatorUrl == null || validatorUrl.isEmpty()) {
            if (httpPersistenceService != null && httpPersistenceService.getGlobalStoreUrl() != null) {
                validatorUrl = httpPersistenceService.getGlobalStoreUrl();
                log.info("   Using validator URL from HttpPersistenceService: {}", validatorUrl);
            } else {
                // Default: Use oak-global-store alias (configured in docker-compose)
                // This works because Sling authors connect via validator-X-clients networks
                // which have the oak-global-store alias pointing to their validator
                validatorUrl = "http://oak-global-store:8090";
                log.info("   Using default validator URL: {}", validatorUrl);
            }
        }
        
        log.info("Sling Author Registration Service activated");
        log.info("  Validator URL: {}", validatorUrl != null && !validatorUrl.isEmpty() ? validatorUrl : "(not configured)");
        log.info("  Client ID: {}", clientId);
        log.info("  Client URL: {}", clientUrl);
        
        // Registration will happen when wallet service binds (MANDATORY reference)
        // Wallet service activates FIRST (service.ranking=1000), then this service activates
        if (walletService != null && walletService.isAvailable()) {
            attemptRegistration();
        } else {
            log.debug("Waiting for wallet service to become available");
        }
    }
    
    /**
     * Called when wallet service becomes available.
     */
    protected void bindWalletService(SlingAuthorWalletService walletService) {
        this.walletService = walletService;
        log.debug("Wallet service bound - attempting registration");
        attemptRegistration();
    }
    
    /**
     * Called when wallet service becomes unavailable.
     */
    protected void unbindWalletService(SlingAuthorWalletService walletService) {
        if (this.walletService == walletService) {
            this.walletService = null;
            log.debug("Wallet service unbound");
        }
    }
    
    /**
     * Called when HttpPersistenceService becomes available.
     */
    protected void bindHttpPersistenceService(com.oakchain.connector.persistence.HttpPersistenceService service) {
        this.httpPersistenceService = service;
        // Update validator URL from HttpPersistenceService if not already configured
        if ((validatorUrl == null || validatorUrl.isEmpty()) && service != null && service.getGlobalStoreUrl() != null) {
            validatorUrl = service.getGlobalStoreUrl();
            log.info("Updated validator URL from HttpPersistenceService: {}", validatorUrl);
            attemptRegistration();
        } else {
            log.debug("HttpPersistenceService bound - attempting registration");
            attemptRegistration();
        }
    }
    
    /**
     * Called when HttpPersistenceService becomes unavailable.
     */
    protected void unbindHttpPersistenceService(com.oakchain.connector.persistence.HttpPersistenceService service) {
        if (this.httpPersistenceService == service) {
            this.httpPersistenceService = null;
        }
    }
    
    /**
     * Attempt to register with validator.
     * Will retry if wallet service is not yet available.
     */
    private void attemptRegistration() {
        if (!enabled || registered) {
            return;
        }
        
        if (validatorUrl == null || validatorUrl.isEmpty()) {
            log.debug("Validator URL not configured - skipping registration");
            return;
        }
        
        // Wait for wallet service if not available
        if (walletService == null || !walletService.isAvailable()) {
            log.debug("Wallet service not available - will retry registration when available");
            // Schedule retry
            new Thread(() -> {
                try {
                    Thread.sleep(2000);
                    attemptRegistration();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }).start();
            return;
        }
        
        String walletAddress = walletService.getWalletAddress();
        if (walletAddress == null || walletAddress.isEmpty()) {
            log.warn("Wallet address not available - cannot register");
            return;
        }
        
        try {
            log.info("Registering Sling author with validator");
            log.debug("  Client ID: {}", clientId);
            log.debug("  Client URL: {}", clientUrl);
            log.debug("  Wallet Address: {}", walletAddress);
            
            String registrationUrl = validatorUrl + "/v1/register-client";
            URL url = new URL(registrationUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(10000);
            
            // Build JSON payload with wallet address
            String jsonPayload = String.format(
                "{\"clientId\":\"%s\",\"clientUrl\":\"%s\",\"walletAddress\":\"%s\"}",
                clientId.replace("\"", "\\\""),
                clientUrl.replace("\"", "\\\""),
                walletAddress.replace("\"", "\\\"")
            );
            
            // Send request
            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = jsonPayload.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }
            
            int responseCode = conn.getResponseCode();
            String responseBody = "";
            
            // Read response
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(
                        responseCode >= 200 && responseCode < 300 
                            ? conn.getInputStream() 
                            : conn.getErrorStream(),
                        StandardCharsets.UTF_8))) {
                String line;
                StringBuilder response = new StringBuilder();
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                responseBody = response.toString();
            }
            
            if (responseCode == 200) {
                registered = true;
                log.info("Sling author registered with validator");
                log.info("  Client ID: {}", clientId);
                log.info("  Wallet Address: {}", walletAddress);
                log.info("  Validator: {}", validatorUrl);
            } else {
                log.warn("Registration failed: HTTP {} - {}", responseCode, responseBody);
            }
            
        } catch (Exception e) {
            log.warn("Failed to register with validator (will retry): {}", e.getMessage());
            // Schedule retry
            new Thread(() -> {
                try {
                    Thread.sleep(5000);
                    attemptRegistration();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }).start();
        }
    }
    
    /**
     * Check if registration was successful.
     */
    public boolean isRegistered() {
        return registered;
    }
}

