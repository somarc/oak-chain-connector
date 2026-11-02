package com.oakchain.connector.mount.optin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Offline approximation of the two gates a bundle must pass on AEM 6.5 LTS:
 * package resolution against the bundles actually installed, and the Granite API
 * controller, which restricts which bundles may wire to some exporters.
 */
final class OsgiCheck {

    record Problem(String pkg, String reason) { }

    private static final Pattern VERSION_ATTR = Pattern.compile("version\\s*=\\s*\"?([^\";,]+)\"?");
    private static final Pattern RANGE = Pattern.compile("([\\[(])\\s*([^,]+)\\s*,\\s*([^\\])]+)\\s*([\\])])");

    private final Map<String, List<String[]>> exports = new HashMap<>();
    private final Map<String, List<String>> apiRules = new HashMap<>();
    private final String systemPackages;

    OsgiCheck(AemHome aem) throws IOException {
        for (Manifest m : aem.cachedBundleManifests()) {
            String bsn = header(m, "Bundle-SymbolicName").split(";")[0].trim();
            for (String clause : clauses(header(m, "Export-Package"))) {
                String[] parts = clause.split(";");
                String version = "0.0.0";
                for (String p : parts) {
                    Matcher vm = VERSION_ATTR.matcher(p.trim());
                    if (vm.matches()) {
                        version = vm.group(1);
                    }
                }
                for (String p : parts) {
                    if (!p.contains("=")) {
                        exports.computeIfAbsent(p.trim(), k -> new ArrayList<>()).add(new String[] {version, bsn});
                    }
                }
            }
        }
        Path api = aem.apiControllerConfig();
        if (Files.isRegularFile(api)) {
            FelixConfig rules = FelixConfig.read(api);
            for (String exporter : rules.keys()) {
                if (exporter.startsWith(":") || exporter.startsWith("service.")) {
                    continue;
                }
                List<String> patterns = new ArrayList<>();
                for (String p : rules.raw(exporter).replace("\"", "").split(",")) {
                    patterns.add(p.trim());
                }
                apiRules.put(exporter, patterns);
            }
        }
        // The framework's package list is assembled from jre-* properties via ${...} indirection.
        systemPackages = aem.allSlingPropertyValues();
    }

    int exporterCount() {
        return exports.size();
    }

    boolean apiControllerKnown() {
        return !apiRules.isEmpty();
    }

    /** Problems for each Import-Package clause of {@code bundle}; empty means it should wire. */
    List<Problem> check(Manifest bundle) {
        String bsn = header(bundle, "Bundle-SymbolicName").split(";")[0].trim();
        List<Problem> problems = new ArrayList<>();
        for (String clause : clauses(header(bundle, "Import-Package"))) {
            String[] parts = clause.split(";");
            String pkg = parts[0].trim();
            if (providedByFramework(pkg) || clause.contains("resolution:=optional")) {
                continue;
            }
            String range = null;
            for (String p : parts) {
                Matcher vm = VERSION_ATTR.matcher(p.trim());
                if (vm.matches()) {
                    range = vm.group(1);
                }
            }
            List<String[]> candidates = exports.getOrDefault(pkg, List.of());
            List<String[]> inRange = new ArrayList<>();
            for (String[] c : candidates) {
                if (inRange(c[0], range)) {
                    inRange.add(c);
                }
            }
            if (inRange.isEmpty()) {
                if (range == null && systemPackages.contains(pkg)) {
                    continue;
                }
                problems.add(new Problem(pkg, candidates.isEmpty()
                        ? "no exporter installed"
                        : "no exporter in range " + range + " (found " + candidates.get(0)[0] + ")"));
                continue;
            }
            boolean allowedSomewhere = false;
            String blockedBy = null;
            for (String[] c : inRange) {
                List<String> rule = apiRules.get(c[1]);
                if (rule == null || rule.stream().anyMatch(p -> glob(p, bsn))) {
                    allowedSomewhere = true;
                } else {
                    blockedBy = c[1] + " (API controller allows " + rule + ")";
                }
            }
            if (!allowedSomewhere) {
                problems.add(new Problem(pkg, "restricted exporter " + blockedBy));
            }
        }
        return problems;
    }

    private boolean providedByFramework(String pkg) {
        return pkg.startsWith("java.") || pkg.startsWith("org.osgi.framework") || pkg.startsWith("org.osgi.resource");
    }

    static boolean glob(String pattern, String value) {
        String regex = ("\\Q" + pattern + "\\E").replace("*", "\\E.*\\Q");
        return value.matches(regex);
    }

    static boolean inRange(String version, String range) {
        if (range == null) {
            return true;
        }
        Matcher m = RANGE.matcher(range);
        int[] v = parse(version);
        if (!m.matches()) {
            return compare(v, parse(range)) >= 0;
        }
        int lo = compare(v, parse(m.group(2)));
        int hi = compare(v, parse(m.group(3)));
        boolean okLo = m.group(1).equals("[") ? lo >= 0 : lo > 0;
        boolean okHi = m.group(4).equals("]") ? hi <= 0 : hi < 0;
        return okLo && okHi;
    }

    private static int[] parse(String v) {
        String[] p = v.trim().split("\\.");
        int[] out = new int[3];
        for (int i = 0; i < 3 && i < p.length; i++) {
            String digits = p[i].replaceAll("\\D.*", "");
            out[i] = digits.isEmpty() ? 0 : Integer.parseInt(digits);
        }
        return out;
    }

    private static int compare(int[] a, int[] b) {
        for (int i = 0; i < 3; i++) {
            if (a[i] != b[i]) {
                return Integer.compare(a[i], b[i]);
            }
        }
        return 0;
    }

    static String header(Manifest m, String name) {
        String v = m.getMainAttributes().getValue(name);
        return v == null ? "" : v;
    }

    /** Splits an OSGi header on commas that are outside quotes. */
    static List<String> clauses(String header) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (char ch : header.toCharArray()) {
            if (ch == '"') {
                quoted = !quoted;
            }
            if (ch == ',' && !quoted) {
                if (!cur.toString().isBlank()) {
                    out.add(cur.toString().trim());
                }
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        if (!cur.toString().isBlank()) {
            out.add(cur.toString().trim());
        }
        return out;
    }
}
