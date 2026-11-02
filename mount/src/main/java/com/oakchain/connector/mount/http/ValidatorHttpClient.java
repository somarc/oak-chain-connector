package com.oakchain.connector.mount.http;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Minimal JDK HTTP client for a validator's raw segment-store read endpoints
 * ({@code /journal.log}, {@code /manifest}, {@code /gc.log}, {@code /segments/{uuid}}).
 *
 * <p>Read-only by construction: it only issues GET and HEAD. The optional token is
 * sent verbatim in {@code Authorization}, which is what the validator's
 * {@code AuthTokenValidator} compares against.
 */
public final class ValidatorHttpClient implements AutoCloseable {

    private final String baseUrl;
    private final String authToken;
    private final Duration requestTimeout;
    private final ExecutorService executor;
    private final HttpClient client;

    public ValidatorHttpClient(String baseUrl, String authToken, Duration connectTimeout, Duration requestTimeout) {
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.authToken = authToken == null || authToken.isBlank() ? null : authToken.trim();
        this.requestTimeout = requestTimeout;
        AtomicInteger n = new AtomicInteger();
        this.executor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "oak-chain-mount-http-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        this.client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(connectTimeout)
                .executor(executor)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    /** GET returning the body, or {@code null} on 404. Other non-2xx statuses throw. */
    public byte[] getBytes(String path) throws IOException {
        HttpResponse<byte[]> response = send(request(path).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        int status = response.statusCode();
        if (status == 404) {
            return null;
        }
        if (status / 100 != 2) {
            throw new IOException("GET " + baseUrl + path + " returned HTTP " + status);
        }
        return response.body();
    }

    /** GET returning the body as UTF-8 text, or {@code null} on 404. */
    public String getString(String path) throws IOException {
        byte[] body = getBytes(path);
        return body == null ? null : new String(body, StandardCharsets.UTF_8);
    }

    /** GET returning a streaming body, or {@code null} on 404. Other non-2xx statuses throw. */
    public java.io.InputStream getStream(String path) throws IOException {
        HttpResponse<java.io.InputStream> response = send(request(path).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        int status = response.statusCode();
        if (status == 404) {
            response.body().close();
            return null;
        }
        if (status / 100 != 2) {
            response.body().close();
            throw new IOException("GET " + baseUrl + path + " returned HTTP " + status);
        }
        return response.body();
    }

    /** HEAD: {@code true} on 2xx, {@code false} on 404. Other statuses throw. */
    public boolean exists(String path) throws IOException {
        HttpResponse<Void> response = send(
                request(path).method("HEAD", HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.discarding());
        int status = response.statusCode();
        if (status == 404) {
            return false;
        }
        if (status / 100 != 2) {
            throw new IOException("HEAD " + baseUrl + path + " returned HTTP " + status);
        }
        return true;
    }

    private HttpRequest.Builder request(String path) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(requestTimeout);
        if (authToken != null) {
            builder.header("Authorization", authToken);
        }
        return builder;
    }

    private <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) throws IOException {
        try {
            return client.send(request, handler);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted: " + request.method() + " " + request.uri(), e);
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    private static String stripTrailingSlash(String url) {
        String u = url.trim();
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }
}
