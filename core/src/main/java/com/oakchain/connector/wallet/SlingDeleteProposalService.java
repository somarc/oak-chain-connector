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
 * Service for Sling authors to submit chain-backed signed delete proposals.
 * 
 * <p>This service allows Sling author instances to:
 * - Propose deletion of existing content
 * - Verify content ownership against the current wallet shard root
 * - Sign delete proposals with their Ethereum wallet
 * - Submit proposals with client-supplied {@code proposalId} and Ethereum payment hash</p>
 * 
 * <p><strong>Chain-backed Flow:</strong></p>
 * <ol>
 *   <li>Sling author proposes delete (contentPath)</li>
 *   <li>Service verifies ownership (path must be under the wallet shard root)</li>
 *   <li>Service signs the exact delete payload with the wallet private key</li>
 *   <li>Signed proposal submitted to validator with {@code proposalId} and {@code ethereumTxHash}</li>
 *   <li>Validator verifies signature and ownership</li>
 *   <li>If valid, validator processes delete via consensus</li>
 * </ol>
 * 
 * <p><strong>Ownership Verification:</strong>
 * Only content created by the same wallet address can be deleted.
 * This enforces content ownership and is the foundation for tokenomics
 * around revision cleanup in Oak (TBD).</p>
 */
@Component(
    service = SlingDeleteProposalService.class,
    configurationPolicy = ConfigurationPolicy.OPTIONAL,
    immediate = true
)
@Designate(ocd = SlingDeleteProposalService.Configuration.class)
public class SlingDeleteProposalService {
    
    private static final Logger log = LoggerFactory.getLogger(SlingDeleteProposalService.class);
    
    @ObjectClassDefinition(
        name = "Sling Author Delete Proposal Service",
        description = "Service for proposing signed delete transactions to validators"
    )
    @interface Configuration {
        @AttributeDefinition(
            name = "Validator URL",
            description = "URL of the validator to submit delete proposals to (e.g., http://oak-global-store:8090)"
        )
        String validatorUrl() default "http://oak-global-store:8090";
        
        @AttributeDefinition(
            name = "Client ID",
            description = "Client identifier for this Sling author instance"
        )
        String clientId() default "";
        
        @AttributeDefinition(
            name = "Enabled",
            description = "Enable delete proposal functionality"
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
            log.info("🔐 Sling Delete Proposal Service disabled");
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
        
        log.info("Sling Delete Proposal Service activated");
        log.info("  Validator URL: {}", validatorUrl);
        log.info("  Client ID: {}", clientId);
        if (walletService != null && walletService.isAvailable()) {
            log.info("  Wallet Address: {}", walletService.getWalletAddress());
        } else {
            log.warn("  Wallet Service: Not available (deletes will fail)");
        }
    }
    
    /**
     * Legacy helper retained for compatibility.
     */
    public DeleteResult proposeDelete(String contentPath) {
        return new DeleteResult(false,
            "Chain-backed Oak deletes require proposalId and ethereumTxHash. " +
            "Use proposeChainBackedDelete(proposalId, contentPath, ethereumTxHash, paymentTier).");
    }

    public DeleteResult proposeChainBackedDelete(
            String proposalId,
            String contentPath,
            String ethereumTxHash) {
        return proposeChainBackedDelete(proposalId, contentPath, ethereumTxHash, "standard");
    }

    /**
     * Propose a chain-backed signed delete.
     */
    public DeleteResult proposeChainBackedDelete(
            String proposalId,
            String contentPath,
            String ethereumTxHash,
            String paymentTier) {
        if (!enabled) {
            return new DeleteResult(false, "Delete proposal service is disabled");
        }
        
        if (walletService == null || !walletService.isAvailable()) {
            return new DeleteResult(false, "Wallet service not available");
        }
        
        String walletAddress = walletService.getWalletAddress();
        if (walletAddress == null || walletAddress.isEmpty()) {
            return new DeleteResult(false, "Wallet address not available");
        }

        if (!isValidProposalId(proposalId)) {
            return new DeleteResult(false,
                "Invalid proposalId. Expected 0x-prefixed 32-byte hex value.");
        }

        if (!isValidTransactionHash(ethereumTxHash)) {
            return new DeleteResult(false,
                "Invalid ethereumTxHash. Expected 0x-prefixed 32-byte transaction hash.");
        }

        String normalizedTier = normalizePaymentTier(paymentTier);
        if (normalizedTier == null) {
            return new DeleteResult(false,
                "Invalid paymentTier. Must be standard, express, or priority.");
        }
        
        String normalizedContentPath = contentPath != null ? contentPath : "";

        // Verify path ownership: must be under the current wallet shard root.
        String expectedPrefix = getShardRoot(walletAddress).toLowerCase(Locale.ROOT) + "/";
        if (!normalizedContentPath.toLowerCase(Locale.ROOT).startsWith(expectedPrefix)) {
            return new DeleteResult(false, 
                String.format("Path ownership violation: Content at %s does not belong to wallet %s. " +
                             "Only content under %s can be deleted.",
                             normalizedContentPath, walletAddress, expectedPrefix));
        }
        
        try {
            // Sign the exact content path the validator will process.
            String signature = walletService.sign(normalizedContentPath);
            
            if (signature == null) {
                return new DeleteResult(false, "Failed to sign delete proposal");
            }
            
            log.info("Submitting chain-backed signed delete proposal");
            log.debug("  Proposal ID: {}", proposalId);
            log.debug("  Ethereum Tx: {}", ethereumTxHash);
            log.debug("  Wallet: {}", walletAddress);
            log.debug("  Content Path: {}", normalizedContentPath);
            log.debug("  Payment Tier: {}", normalizedTier);
            log.debug("  Signature: {}...{}", 
                signature.substring(0, Math.min(10, signature.length())),
                signature.length() > 10 ? signature.substring(signature.length() - 4) : "");
            
            // Submit signed transaction to validator
            String deleteUrl = validatorUrl + "/v1/propose-delete";
            URL url = new URL(deleteUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setRequestProperty("X-Client-Id", clientId);
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(30000);
            
            // Build form parameters
            String params = String.format(
                "walletAddress=%s&proposalId=%s&signature=%s&contentPath=%s&clientId=%s&ethereumTxHash=%s&paymentTier=%s",
                java.net.URLEncoder.encode(walletAddress, StandardCharsets.UTF_8),
                java.net.URLEncoder.encode(proposalId, StandardCharsets.UTF_8),
                java.net.URLEncoder.encode(signature, StandardCharsets.UTF_8),
                java.net.URLEncoder.encode(normalizedContentPath, StandardCharsets.UTF_8),
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
                log.info("Delete proposal accepted by validator: {}", normalizedContentPath);
                return new DeleteResult(true, "Delete proposal accepted", responseBody);
            } else {
                log.warn("Delete proposal rejected: HTTP {} - {}", responseCode, responseBody);
                return new DeleteResult(false, "Delete proposal rejected: " + responseBody);
            }
            
        } catch (Exception e) {
            log.error("Failed to propose chain-backed delete", e);
            return new DeleteResult(false, "Failed to propose delete: " + e.getMessage());
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

    private static String getShardRoot(String walletAddress) {
        String normalized = normalizeWalletAddress(walletAddress);
        return String.format("/oak-chain/%s/%s/%s/0x%s",
            normalized.substring(0, 2),
            normalized.substring(2, 4),
            normalized.substring(4, 6),
            normalized);
    }

    private static String normalizeWalletAddress(String walletAddress) {
        if (walletAddress == null) {
            throw new IllegalArgumentException("walletAddress required");
        }
        String value = walletAddress.trim();
        if (!value.matches("(?i)^0x[a-f0-9]{40}$")) {
            throw new IllegalArgumentException("Invalid wallet address format");
        }
        return value.substring(2).toLowerCase(Locale.ROOT);
    }
    
    /**
     * Result of a delete proposal.
     */
    public static class DeleteResult {
        public final boolean success;
        public final String message;
        public final String responseBody;
        
        public DeleteResult(boolean success, String message) {
            this(success, message, null);
        }
        
        public DeleteResult(boolean success, String message, String responseBody) {
            this.success = success;
            this.message = message;
            this.responseBody = responseBody;
        }
    }
}
