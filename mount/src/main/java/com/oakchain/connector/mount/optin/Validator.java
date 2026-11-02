package com.oakchain.connector.mount.optin;

import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import com.oakchain.connector.mount.optin.OptInPlan.Check;
import com.oakchain.connector.mount.optin.OptInPlan.Status;

/** Read-only probes of a validator's segment-store endpoints, as the mount will use them. */
final class Validator {

    private Validator() {
    }

    static List<Check> checks(String baseUrl, String token) {
        List<Check> out = new ArrayList<>();
        String base = baseUrl.replaceAll("/+$", "");
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        try {
            HttpResponse<String> manifest = get(http, base + "/manifest", token);
            if (manifest.statusCode() != 200) {
                out.add(new Check(Status.FAIL, "Validator manifest", base + "/manifest returned HTTP " + manifest.statusCode()));
                return out;
            }
            Properties m = new Properties();
            m.load(new StringReader(manifest.body()));
            String v = m.getProperty("store.version", "?");
            boolean ok = v.equals("1") || v.equals("2");
            out.add(new Check(ok ? Status.PASS : Status.FAIL, "Validator manifest",
                    "store.version=" + v + (ok ? " (readable by Oak 2.4.0)" : " (unsupported)")));

            HttpResponse<String> journal = get(http, base + "/journal.log", token);
            String head = null;
            if (journal.statusCode() == 200) {
                for (String line : journal.body().split("\n")) {
                    if (!line.isBlank()) {
                        head = line.trim().split(" ")[0];
                    }
                }
            }
            if (head == null || !head.contains(":")) {
                out.add(new Check(Status.FAIL, "Validator journal", "no head revision (HTTP " + journal.statusCode() + ")"));
                return out;
            }
            out.add(new Check(Status.PASS, "Validator journal", "head " + head));

            String segment = head.substring(0, head.indexOf(':'));
            HttpResponse<Void> seg = http.send(request(base + "/segments/" + segment, token)
                    .method("HEAD", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding());
            out.add(new Check(seg.statusCode() == 200 ? Status.PASS : Status.FAIL, "Validator head segment",
                    "HEAD /segments/" + segment + " -> HTTP " + seg.statusCode()));
        } catch (IOException e) {
            out.add(new Check(Status.WARN, "Validator", base + " unreachable (" + e.getClass().getSimpleName()
                    + "); the mount is lazy and will attach when it answers"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return out;
    }

    private static HttpResponse<String> get(HttpClient http, String url, String token) throws IOException, InterruptedException {
        return http.send(request(url, token).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpRequest.Builder request(String url, String token) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10));
        if (token != null && !token.isBlank()) {
            b.header("Authorization", token.trim());
        }
        return b;
    }
}
