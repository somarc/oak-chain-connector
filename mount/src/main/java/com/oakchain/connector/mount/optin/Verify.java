package com.oakchain.connector.mount.optin;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.oakchain.connector.mount.optin.OptInPlan.Check;
import com.oakchain.connector.mount.optin.OptInPlan.Status;

/**
 * Post-start verification against a running AEM: the composite came up once, the mount is
 * wired and (once the validator answers) visible, and nothing else was disturbed.
 * With {@code expectMount=false} it verifies a clean, opted-out instance instead.
 */
final class Verify {

    private static final String BUNDLE = "com.oakchain.connector.mount";

    private final String aemUrl;
    private final String auth;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    Verify(String aemUrl, String userColonPassword) {
        this.aemUrl = aemUrl.replaceAll("/+$", "");
        this.auth = "Basic " + Base64.getEncoder().encodeToString(userColonPassword.getBytes(StandardCharsets.UTF_8));
    }

    List<Check> run(AemHome aem, boolean expectMount, String mountPath, int timeoutSeconds, boolean writeProbe)
            throws IOException, InterruptedException {
        List<Check> out = new ArrayList<>();
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;

        int login = poll("/libs/granite/core/content/login.html", 200, deadline);
        out.add(new Check(login == 200 ? Status.PASS : Status.FAIL, "AEM up", "login page HTTP " + login));
        if (login != 200) {
            return out;
        }

        // Bundles: wait for the framework to settle, then inspect.
        String bundles = "";
        int[] counts = null;
        while (System.currentTimeMillis() < deadline) {
            bundles = body("/system/console/bundles.json");
            counts = counts(bundles);
            if (counts != null && counts[3] == 0 && counts[4] == 0) {
                break;
            }
            Thread.sleep(3000);
        }
        if (counts == null) {
            out.add(new Check(Status.FAIL, "Bundles", "cannot read /system/console/bundles.json"));
        } else {
            boolean all = counts[3] == 0 && counts[4] == 0;
            out.add(new Check(all ? Status.PASS : Status.WARN, "Bundles",
                    counts[0] + " total, " + (counts[1] + counts[2]) + " active, " + counts[3] + " resolved, " + counts[4] + " installed"));
        }
        String state = bundleState(bundles);
        if (expectMount) {
            out.add(new Check("Active".equals(state) ? Status.PASS : Status.FAIL, "Mount bundle", BUNDLE + " " + (state == null ? "not installed" : state)));
        } else {
            out.add(new Check(state == null ? Status.PASS : Status.FAIL, "Mount bundle",
                    state == null ? "not installed" : "still installed (" + state + ")"));
        }

        int mount = expectMount ? poll(mountPath + ".json", 200, Math.min(deadline, System.currentTimeMillis() + 60_000)) : status(mountPath + ".json");
        if (expectMount) {
            out.add(new Check(mount == 200 ? Status.PASS : Status.WARN, "Mounted path",
                    mountPath + " HTTP " + mount + (mount == 200 ? "" : " (validator not attached yet; the mount is lazy)")));
        } else {
            out.add(new Check(mount == 404 ? Status.PASS : Status.FAIL, "Mounted path", mountPath + " HTTP " + mount + " (expected 404)"));
        }

        if (aem != null) {
            List<String> log = aem.currentBootLog();
            long composites = log.stream().filter(l -> l.contains("Registering the composite node store")).count();
            // Not our own --write-probe: Sling logs its intended rejection at ERROR.
            long errors = log.stream().filter(l -> l.contains("*ERROR*") && !l.contains("oakchain-verify-probe")).count();
            boolean unresolved = log.stream().anyMatch(l -> l.contains("Unable to resolve " + BUNDLE));
            if (expectMount) {
                out.add(new Check(composites == 1 ? Status.PASS : Status.FAIL, "Composite registrations this boot",
                        composites + (composites == 1 ? " (no repository restart)" : " (expected exactly 1)")));
                boolean connected = log.stream().anyMatch(l -> l.contains("Oak Chain mount '") && l.contains("': connected to"));
                out.add(new Check(connected ? Status.PASS : Status.WARN, "Mount connected this boot",
                        connected ? "connected to validator" : "not yet connected"));
            } else {
                // Vanilla: "TarMK opened at .../repository/segmentstore, mmap=..." (the composite global
                // store logs its factory directory, ".../segmentstore-composite-global,").
                boolean primary = log.stream().anyMatch(l -> l.contains("TarMK opened at") && l.contains("/segmentstore,"));
                out.add(new Check(composites == 0 ? Status.PASS : Status.FAIL, "Composite registrations this boot",
                        composites + " (expected 0)"));
                out.add(new Check(primary ? Status.PASS : Status.WARN, "Primary TarMK", primary ? "SegmentNodeStoreService opened segmentstore" : "no 'TarMK opened' line found"));
            }
            out.add(new Check(unresolved ? Status.FAIL : Status.PASS, "Bundle resolution", unresolved ? BUNDLE + " failed to resolve" : "no resolution errors"));
            out.add(new Check(errors == 0 ? Status.PASS : Status.INFO, "ERROR lines this boot", String.valueOf(errors)));
        }

        if (expectMount) {
            // AEM's Revision Clean Up must name the global store's role, or daily maintenance never compacts.
            String role = null;
            try {
                role = configValue(body("/system/console/status-Configurations.txt"), OptInPlan.REVISION_CLEANUP_PID, "role");
            } catch (RuntimeException e) {
                // reported below
            }
            boolean ok = OptInPlan.GLOBAL_ROLE.equals(role);
            out.add(new Check(ok ? Status.PASS : Status.FAIL, "Revision Clean Up role",
                    ok ? "role=" + role + " (daily maintenance compacts the global store)"
                       : "effective role is " + (role == null ? "unset" : role) + "; expected " + OptInPlan.GLOBAL_ROLE
                         + " (a code-base config of RevisionCleanupTask overrides it?)"));
        }

        if (writeProbe && expectMount) {
            int rejected = post(mountPath + "/oakchain-verify-probe", "probe=1");
            out.add(new Check(rejected >= 400 ? Status.PASS : Status.FAIL, "Write under mount rejected", "HTTP " + rejected));
            int created = post("/var/oakchain-verify-probe", "probe=1");
            int deleted = post("/var/oakchain-verify-probe", ":operation=delete");
            out.add(new Check(created / 100 == 2 && deleted / 100 == 2 ? Status.PASS : Status.FAIL, "Global write",
                    "create HTTP " + created + ", delete HTTP " + deleted));
        }
        return out;
    }

    /** A property of one PID in the web console's Configurations status ("PID = ..." blocks, "  key = value"). */
    static String configValue(String status, String pid, String key) {
        int at = status.indexOf("PID = " + pid + "\n");
        if (at < 0) {
            return null;
        }
        int end = status.indexOf("\n\n", at);
        for (String line : status.substring(at, end < 0 ? status.length() : end).split("\n")) {
            String t = line.trim();
            if (t.startsWith(key + " = ")) {
                return t.substring(key.length() + 3).trim();
            }
        }
        return null;
    }

    private static final Pattern COUNTS = Pattern.compile("\"s\"\\s*:\\s*\\[\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)");

    static int[] counts(String bundlesJson) {
        Matcher m = COUNTS.matcher(bundlesJson);
        if (!m.find()) {
            return null;
        }
        int[] c = new int[5];
        for (int i = 0; i < 5; i++) {
            c[i] = Integer.parseInt(m.group(i + 1));
        }
        return c;
    }

    static String bundleState(String bundlesJson) {
        int at = bundlesJson.indexOf("\"symbolicName\":\"" + BUNDLE + "\"");
        if (at < 0) {
            return null;
        }
        int objStart = bundlesJson.lastIndexOf('{', at);
        int objEnd = bundlesJson.indexOf('}', at);
        Matcher m = Pattern.compile("\"state\"\\s*:\\s*\"([^\"]+)\"").matcher(bundlesJson.substring(objStart, objEnd));
        return m.find() ? m.group(1) : "unknown";
    }

    private int poll(String path, int want, long deadline) throws InterruptedException {
        int last = -1;
        while (true) {
            last = status(path);
            if (last == want || System.currentTimeMillis() > deadline) {
                return last;
            }
            Thread.sleep(2000);
        }
    }

    private int status(String path) throws InterruptedException {
        try {
            return http.send(req(path).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (IOException e) {
            return -1;
        }
    }

    private String body(String path) throws InterruptedException {
        try {
            HttpResponse<String> r = http.send(req(path).GET().build(), HttpResponse.BodyHandlers.ofString());
            return r.statusCode() == 200 ? r.body() : "";
        } catch (IOException e) {
            return "";
        }
    }

    private int post(String path, String form) throws InterruptedException {
        try {
            return http.send(req(path).header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (IOException e) {
            return -1;
        }
    }

    private HttpRequest.Builder req(String path) {
        return HttpRequest.newBuilder(URI.create(aemUrl + path)).timeout(Duration.ofSeconds(20)).header("Authorization", auth);
    }
}
