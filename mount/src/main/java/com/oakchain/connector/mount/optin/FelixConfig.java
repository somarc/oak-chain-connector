package com.oakchain.connector.mount.optin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal reader/writer for Felix ConfigAdmin's persisted {@code .config} format
 * ({@code key=B"true"}, {@code key=I"5"}, {@code key="text"}, {@code key=["a","b"]}).
 *
 * <p>Values are kept as raw typed literals so unknown properties round-trip untouched.
 */
final class FelixConfig {

    private final Map<String, String> raw = new LinkedHashMap<>();

    static FelixConfig read(Path file) throws IOException {
        FelixConfig c = new FelixConfig();
        StringBuilder logical = new StringBuilder();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String t = line.strip();
            if (t.endsWith("\\") && !t.endsWith("\\\\")) {
                logical.append(t, 0, t.length() - 1);
                continue;
            }
            logical.append(t);
            String l = logical.toString();
            logical.setLength(0);
            if (l.isEmpty() || l.startsWith("#")) {
                continue;
            }
            int eq = unescapedEquals(l);
            if (eq > 0) {
                c.raw.put(l.substring(0, eq), l.substring(eq + 1));
            }
        }
        return c;
    }

    private static int unescapedEquals(String l) {
        for (int i = 0; i < l.length(); i++) {
            char ch = l.charAt(i);
            if (ch == '\\') {
                i++;
            } else if (ch == '=') {
                return i;
            }
        }
        return -1;
    }

    java.util.Set<String> keys() {
        return raw.keySet();
    }

    String raw(String key) {
        return raw.get(key);
    }

    boolean has(String key) {
        return raw.containsKey(key);
    }

    boolean bool(String key, boolean dflt) {
        String v = raw.get(key);
        if (v == null) {
            return dflt;
        }
        return v.replace("B", "").replace("\"", "").trim().equalsIgnoreCase("true");
    }

    FelixConfig putRaw(String key, String typedLiteral) {
        raw.put(key, typedLiteral);
        return this;
    }

    FelixConfig putString(String key, String value) {
        return putRaw(key, quote(value));
    }

    FelixConfig putBool(String key, boolean value) {
        return putRaw(key, "B\"" + value + "\"");
    }

    FelixConfig putInt(String key, int value) {
        return putRaw(key, "I\"" + value + "\"");
    }

    FelixConfig putStrings(String key, List<String> values) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            b.append(quote(values.get(i)));
        }
        return putRaw(key, b.append(']').toString());
    }

    FelixConfig remove(String key) {
        raw.remove(key);
        return this;
    }

    /** Felix persisted form: revision marker first, then keys sorted, like ConfigAdmin writes them. */
    String render() {
        StringBuilder b = new StringBuilder(":org.apache.felix.configadmin.revision:=L\"1\"\n");
        raw.entrySet().stream()
                .filter(e -> !e.getKey().equals(":org.apache.felix.configadmin.revision:"))
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> b.append(e.getKey()).append('=').append(e.getValue()).append('\n'));
        return b.toString();
    }

    /** Installer form (crx-quickstart/install): no ConfigAdmin bookkeeping keys. */
    String renderForInstaller() {
        StringBuilder b = new StringBuilder();
        raw.entrySet().stream()
                .filter(e -> !e.getKey().startsWith(":") && !e.getKey().equals("service.pid")
                        && !e.getKey().equals("service.factoryPid"))
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> b.append(e.getKey()).append('=').append(e.getValue()).append('\n'));
        return b.toString();
    }

    static String quote(String value) {
        StringBuilder b = new StringBuilder("\"");
        for (char ch : value.toCharArray()) {
            switch (ch) {
                case '\\' -> b.append("\\\\");
                case '"' -> b.append("\\\"");
                case '=' -> b.append("\\=");
                case ' ' -> b.append("\\ ");
                default -> b.append(ch);
            }
        }
        return b.append('"').toString();
    }

    /** Felix encodes a PID as a path: dots become directories, '~' becomes %007e. */
    static Path persistedPath(Path launchpadConfig, String pid) {
        String encoded = pid.replace("~", "%007e");
        int tilde = encoded.indexOf("%007e");
        String head = tilde < 0 ? encoded : encoded.substring(0, tilde);
        String tail = tilde < 0 ? "" : encoded.substring(tilde);
        return launchpadConfig.resolve(head.replace('.', '/') + tail + ".config");
    }
}
