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
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

/**
 * OSGi service for Sling authors to manage their Ethereum wallet.
 * 
 * <p>This service provides wallet generation, loading, and signing capabilities
 * for Sling author instances that need to propose writes to validators.</p>
 * 
 * <p>Each Sling author instance has its own wallet, stored in the Sling data directory.
 * The wallet address is used for:
 * - Client registration with validators
 * - Signing write proposals
 * - Content ownership verification (for deletes)</p>
 */
@Component(
    service = SlingAuthorWalletService.class,
    configurationPolicy = ConfigurationPolicy.OPTIONAL,
    immediate = true,
    property = {
        "service.ranking:Integer=1000"  // High priority - activate FIRST before other services
    }
)
@Designate(ocd = SlingAuthorWalletService.Configuration.class)
public class SlingAuthorWalletService {
    
    private static final Logger log = LoggerFactory.getLogger(SlingAuthorWalletService.class);
    
    @ObjectClassDefinition(
        name = "Sling Author Ethereum Wallet",
        description = "Ethereum wallet for Sling author write proposals"
    )
    @interface Configuration {
        @AttributeDefinition(
            name = "Keystore Path",
            description = "Path to wallet keystore file (e.g., /opt/sling/launcher/sling-author-keystore.properties)"
        )
        String keystorePath() default "";
        
        @AttributeDefinition(
            name = "Enabled",
            description = "Enable wallet functionality (required for write proposals)"
        )
        boolean enabled() default true;
    }
    
    private EthereumWallet wallet;
    private boolean enabled;
    private String keystorePath;
    
    @Activate
    protected void activate(Configuration config) {
        this.enabled = config.enabled();
        this.keystorePath = config.keystorePath();
        
        if (!enabled) {
            log.info("Sling Author Wallet Service disabled");
            return;
        }
        
        try {
            // Determine keystore path
            String actualKeystorePath = keystorePath;
            if (actualKeystorePath == null || actualKeystorePath.isEmpty()) {
                // Default: Use Sling launcher directory
                String slingHome = System.getProperty("sling.home", "/opt/sling/launcher");
                actualKeystorePath = slingHome + "/sling-author-keystore.properties";
            }
            
            // Ensure parent directory exists
            File keystoreFile = new File(actualKeystorePath);
            File parentDir = keystoreFile.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }
            
            // Set file permissions to 600 (owner read/write only)
            if (keystoreFile.exists()) {
                keystoreFile.setReadable(false, false);
                keystoreFile.setWritable(false, false);
                keystoreFile.setReadable(true, true);
                keystoreFile.setWritable(true, true);
            }
            
            // Load or generate wallet
            this.wallet = new EthereumWallet(actualKeystorePath);
            
            log.info("Sling Author Wallet Service activated");
            log.info("  Wallet Address: {}", wallet.getWalletAddress());
            log.info("  Keystore: {}", actualKeystorePath);
            
        } catch (Exception e) {
            log.error("Failed to initialize Sling Author wallet", e);
            this.wallet = null;
        }
    }
    
    /**
     * Get the wallet address (0x...).
     * 
     * @return Wallet address, or null if wallet not initialized
     */
    public String getWalletAddress() {
        return wallet != null ? wallet.getWalletAddress() : null;
    }
    
    /**
     * Sign a message with the wallet's private key.
     * 
     * @param message Message to sign
     * @return Signature (hex-encoded with 0x prefix), or null if wallet not initialized
     */
    public String sign(String message) {
        if (wallet == null) {
            log.warn("Cannot sign: Wallet not initialized");
            return null;
        }
        try {
            return wallet.sign(message);
        } catch (Exception e) {
            log.error("Failed to sign message", e);
            return null;
        }
    }
    
    /**
     * Check if wallet is initialized and ready.
     * 
     * @return true if wallet is available
     */
    public boolean isAvailable() {
        return wallet != null && enabled;
    }
    
    /**
     * Get the public key (hex-encoded with 0x prefix).
     * 
     * @return Public key, or null if wallet not initialized
     */
    public String getPublicKeyHex() {
        return wallet != null ? wallet.getPublicKeyHex() : null;
    }
}

