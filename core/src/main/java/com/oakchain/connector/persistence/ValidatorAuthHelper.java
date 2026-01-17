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

import org.apache.http.HttpRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.HttpURLConnection;

/**
 * Helper utility for adding authentication headers to HTTP requests to validators.
 * 
 * <p>Token can be configured via:
 * <ul>
 *   <li>System property: {@code oak.validator.auth.token}</li>
 *   <li>Environment variable: {@code OAK_VALIDATOR_AUTH_TOKEN}</li>
 * </ul>
 * 
 * <p>If no token is configured, no Authorization header is added (POC mode).
 * This matches the validator's behavior - if no token is configured, auth is disabled.
 */
public class ValidatorAuthHelper {
    
    private static final Logger log = LoggerFactory.getLogger(ValidatorAuthHelper.class);
    
    /**
     * System property name for auth token (matches validator's property name).
     */
    public static final String TOKEN_PROPERTY_NAME = "oak.validator.auth.token";
    
    /**
     * Environment variable name for auth token (matches validator's env var name).
     */
    public static final String TOKEN_ENV_VAR_NAME = "OAK_VALIDATOR_AUTH_TOKEN";
    
    /**
     * HTTP header name for authorization.
     */
    public static final String AUTHORIZATION_HEADER = "Authorization";
    
    private static String cachedToken;
    private static boolean tokenInitialized = false;
    
    /**
     * Get the configured auth token (if any).
     * 
     * @return The auth token, or null if not configured (POC mode)
     */
    public static String getAuthToken() {
        if (!tokenInitialized) {
            synchronized (ValidatorAuthHelper.class) {
                if (!tokenInitialized) {
                    // Try system property first, then environment variable
                    String token = System.getProperty(TOKEN_PROPERTY_NAME);
                    if (token == null || token.trim().isEmpty()) {
                        token = System.getenv(TOKEN_ENV_VAR_NAME);
                    }
                    
                    if (token != null && !token.trim().isEmpty()) {
                        cachedToken = token.trim();
                        log.debug("Validator auth token configured (from {} or {})", 
                            TOKEN_PROPERTY_NAME, TOKEN_ENV_VAR_NAME);
                    } else {
                        cachedToken = null;
                        log.debug("No validator auth token configured - requests will be unauthenticated (POC mode)");
                    }
                    tokenInitialized = true;
                }
            }
        }
        return cachedToken;
    }
    
    /**
     * Add Authorization header to HttpURLConnection if token is configured.
     * 
     * @param conn The HTTP connection
     */
    public static void addAuthHeader(HttpURLConnection conn) {
        String token = getAuthToken();
        if (token != null) {
            conn.setRequestProperty(AUTHORIZATION_HEADER, token);
        }
    }
    
    /**
     * Add Authorization header to Apache HttpClient request if token is configured.
     * 
     * @param request The HTTP request
     */
    public static void addAuthHeader(HttpRequest request) {
        String token = getAuthToken();
        if (token != null) {
            request.setHeader(AUTHORIZATION_HEADER, token);
        }
    }
    
    /**
     * Check if authentication is enabled (token is configured).
     * 
     * @return true if token is configured, false otherwise (POC mode)
     */
    public static boolean isAuthEnabled() {
        return getAuthToken() != null;
    }
}

