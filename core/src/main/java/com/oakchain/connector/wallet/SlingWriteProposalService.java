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
import java.util.Locale;

/**
 * Service for Sling authors to submit chain-backed signed write proposals to validators.
 * 
 * <p>This service allows Sling author instances to:
 * - Create chain-backed write proposals
 * - Sign proposal payloads with their Ethereum wallet
 * - Submit proposals with client-supplied {@code proposalId} and Ethereum payment hash</p>
 * 
 * <p><strong>Chain-backed Flow:</strong></p>
 * <ol>
 *   <li>Sling author obtains a contract-backed {@code proposalId}</li>
 *   <li>Sling author pays on-chain and gets {@code ethereumTxHash}</li>
 *   <li>Service signs the proposal message with the wallet private key</li>
 *   <li>Signed proposal submitted to validator</li>
 *   <li>Validator verifies signature and payment proof using the supplied identifiers</li>
 *   <li>If valid, validator processes write via consensus</li>
 * </ol>
 */
@Component(
    service = SlingWriteProposalService.class,
    configurationPolicy = ConfigurationPolicy.OPTIONAL,
    immediate = true
)
@Designate(ocd = SlingWriteProposalService.Configuration.class)
public class SlingWriteProposalService {
    
    private static final Logger log = LoggerFactory.getLogger(SlingWriteProposalService.class);
    
    @ObjectClassDefinition(
        name = "Sling Author Write Proposal Service",
        description = "Service for proposing signed write transactions to validators"
    )
    @interface Configuration {
        @AttributeDefinition(
            name = "Validator URL",
            description = "URL of the validator to submit write proposals to (e.g., http://oak-global-store:8090)"
        )
        String validatorUrl() default "http://oak-global-store:8090";
        
        @AttributeDefinition(
            name = "Client ID",
            description = "Client identifier for this Sling author instance"
        )
        String clientId() default "";
        
        @AttributeDefinition(
            name = "Enabled",
            description = "Enable write proposal functionality"
        )
        boolean enabled() default true;
    }
    
    @Reference
    private volatile SlingAuthorWalletService walletService;
    
    private String validatorUrl;
    private String clientId;
    private boolean enabled;
    
    @Activate
    protected void activate(Configuration config) {
        this.validatorUrl = config.validatorUrl();
        this.clientId = config.clientId();
        this.enabled = config.enabled();
        
        if (!enabled) {
            log.info("Sling Write Proposal Service disabled");
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
        
        log.info("Sling Write Proposal Service activated");
        log.info("  Validator URL: {}", validatorUrl);
        log.info("  Client ID: {}", clientId);
        if (walletService != null && walletService.isAvailable()) {
            log.info("  Wallet Address: {}", walletService.getWalletAddress());
        } else {
            log.warn("  Wallet Service: Not available (writes will fail)");
        }
    }
    
    /**
     * Legacy helper retained for compatibility.
     *
     * <p>Chain-backed Oak writes now require a client-supplied {@code proposalId}
     * and an on-chain {@code ethereumTxHash}. Call
     * {@link #proposeChainBackedWrite(String, String, String, String, String)}
     * instead.</p>
     */
    public WriteResult proposeWrite(String contentType, String message) {
        return new WriteResult(false,
            "Chain-backed Oak writes require proposalId and ethereumTxHash. " +
            "Use proposeChainBackedWrite(proposalId, contentType, message, ethereumTxHash, paymentTier).");
    }

    public WriteResult proposeChainBackedWrite(
            String proposalId,
            String contentType,
            String message,
            String ethereumTxHash) {
        return proposeChainBackedWrite(proposalId, contentType, message, ethereumTxHash, "standard");
    }

    /**
     * Propose a chain-backed signed write.
     *
     * <p>The proposal message is signed exactly as submitted to the validator.
     * This keeps the connector aligned to the current Oak validator contract.</p>
     */
    public WriteResult proposeChainBackedWrite(
            String proposalId,
            String contentType,
            String message,
            String ethereumTxHash,
            String paymentTier) {
        if (!enabled) {
            return new WriteResult(false, "Write proposal service is disabled");
        }
        
        if (walletService == null || !walletService.isAvailable()) {
            return new WriteResult(false, "Wallet service not available");
        }
        
        String walletAddress = walletService.getWalletAddress();
        if (walletAddress == null || walletAddress.isEmpty()) {
            return new WriteResult(false, "Wallet address not available");
        }

        if (!isValidProposalId(proposalId)) {
            return new WriteResult(false,
                "Invalid proposalId. Expected 0x-prefixed 32-byte hex value.");
        }

        if (!isValidTransactionHash(ethereumTxHash)) {
            return new WriteResult(false,
                "Invalid ethereumTxHash. Expected 0x-prefixed 32-byte transaction hash.");
        }

        String normalizedTier = normalizePaymentTier(paymentTier);
        if (normalizedTier == null) {
            return new WriteResult(false,
                "Invalid paymentTier. Must be standard, express, or priority.");
        }
        
        try {
            String normalizedMessage = message != null ? message : "";

            // Sign the exact proposal message the validator will verify.
            String signature = walletService.sign(normalizedMessage);
            
            if (signature == null) {
                return new WriteResult(false, "Failed to sign write proposal");
            }
            
            log.info("Submitting chain-backed signed write proposal");
            log.debug("  Proposal ID: {}", proposalId);
            log.debug("  Ethereum Tx: {}", ethereumTxHash);
            log.debug("  Wallet: {}", walletAddress);
            log.debug("  Content Type: {}", contentType);
            log.debug("  Payment Tier: {}", normalizedTier);
            log.debug("  Signature: {}...{}", 
                signature.substring(0, Math.min(10, signature.length())),
                signature.length() > 10 ? signature.substring(signature.length() - 4) : "");
            
            // Submit signed proposal to validator
            String writeUrl = validatorUrl + "/v1/propose-write";
            URL url = new URL(writeUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setRequestProperty("X-Client-Id", clientId);
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(30000);
            
            // Build form parameters
            String params = String.format(
                "walletAddress=%s&proposalId=%s&signature=%s&message=%s&contentType=%s&clientId=%s&ethereumTxHash=%s&paymentTier=%s",
                java.net.URLEncoder.encode(walletAddress, StandardCharsets.UTF_8),
                java.net.URLEncoder.encode(proposalId, StandardCharsets.UTF_8),
                java.net.URLEncoder.encode(signature, StandardCharsets.UTF_8),
                java.net.URLEncoder.encode(normalizedMessage, StandardCharsets.UTF_8),
                java.net.URLEncoder.encode(contentType, StandardCharsets.UTF_8),
                java.net.URLEncoder.encode(clientId, StandardCharsets.UTF_8),
                java.net.URLEncoder.encode(ethereumTxHash, StandardCharsets.UTF_8),
                java.net.URLEncoder.encode(normalizedTier, StandardCharsets.UTF_8)
            );
            
            // Send request
            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = params.getBytes(StandardCharsets.UTF_8);
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
            
            if (responseCode >= 200 && responseCode < 300) {
                log.info("Write proposal accepted by validator");
                return new WriteResult(true, "Write proposal accepted", responseBody);
            } else {
                log.warn("Write proposal rejected: HTTP {} - {}", responseCode, responseBody);
                return new WriteResult(false, "Write proposal rejected: " + responseBody);
            }
            
        } catch (Exception e) {
            log.error("Failed to propose chain-backed write", e);
            return new WriteResult(false, "Failed to propose write: " + e.getMessage());
        }
    }

    private static boolean isValidProposalId(String proposalId) {
        return proposalId != null && proposalId.matches("(?i)^0x[a-f0-9]{64}$");
    }

    private static boolean isValidTransactionHash(String ethereumTxHash) {
        return ethereumTxHash != null && ethereumTxHash.matches("(?i)^0x[a-f0-9]{64}$");
    }

    private static String normalizePaymentTier(String paymentTier) {
        if (paymentTier == null || paymentTier.trim().isEmpty()) {
            return "standard";
        }
        String normalized = paymentTier.trim().toLowerCase(Locale.ROOT);
        switch (normalized) {
            case "standard":
            case "express":
            case "priority":
                return normalized;
            default:
                return null;
        }
    }
    
    /**
     * Result of a write proposal.
     */
    public static class WriteResult {
        public final boolean success;
        public final String message;
        public final String responseBody;
        
        public WriteResult(boolean success, String message) {
            this(success, message, null);
        }
        
        public WriteResult(boolean success, String message, String responseBody) {
            this.success = success;
            this.message = message;
            this.responseBody = responseBody;
        }
    }
}
