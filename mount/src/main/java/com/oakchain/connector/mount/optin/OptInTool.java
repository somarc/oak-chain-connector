package com.oakchain.connector.mount.optin;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

import com.oakchain.connector.mount.optin.OptInPlan.Check;
import com.oakchain.connector.mount.optin.OptInPlan.Params;
import com.oakchain.connector.mount.optin.OptInPlan.Status;

/**
 * Opt-in CLI shipped inside the mount bundle itself:
 * {@code java -jar oak-chain-connector.mount.jar <command> <aem-home> [options]}.
 *
 * <pre>
 *   preflight &lt;aem-home&gt; --validator URL        checks only, changes nothing
 *   enable    &lt;aem-home&gt; --validator URL        AEM must be stopped; start AEM afterwards
 *   verify    &lt;aem-home&gt; --aem-url URL          AEM running; checks the opted-in (or opted-out) state
 *   status    &lt;aem-home&gt;                        what is applied, and any drift
 *   disable   &lt;aem-home&gt; [--force]              AEM must be stopped; restores byte-identical originals
 * </pre>
 *
 * Secrets never go on the command line: {@code --token-env VAR} names an environment variable
 * holding the validator token; {@code --auth-env VAR} (default {@code OAKCHAIN_AEM_AUTH}) holds
 * {@code user:password} for verify.
 */
public final class OptInTool {

    private OptInTool() {
    }

    public static void main(String[] args) {
        int code;
        try {
            code = run(args);
        } catch (IllegalArgumentException e) {
            System.err.println("error: " + e.getMessage());
            System.err.println();
            usage();
            code = 2;
        } catch (Exception e) {
            System.err.println("error: " + e);
            code = 1;
        }
        System.exit(code);
    }

    static int run(String[] args) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException(args.length == 0 ? "missing command" : "missing <aem-home>");
        }
        String command = args[0];
        AemHome aem = new AemHome(Path.of(args[1]));
        Map<String, String> opt = options(args);
        OptInPlan plan = new OptInPlan(aem);

        switch (command) {
            case "preflight" -> {
                List<Check> checks = plan.preflight(params(opt, plan, false), selfManifest(opt), false);
                return report("preflight " + aem.home, checks);
            }
            case "enable" -> {
                Params p = params(opt, plan, true);
                List<Check> checks = plan.preflight(p, selfManifest(opt), true);
                if (report("preflight " + aem.home, checks) != 0) {
                    System.out.println("\nenable aborted: fix the FAIL items above. Nothing was changed.");
                    return 1;
                }
                System.out.println();
                for (String line : plan.enable(p, selfJar(opt))) {
                    System.out.println("  " + line);
                }
                System.out.println("\nOpt-in applied. Start AEM, then run:\n  java -jar " + selfJar(opt).getFileName()
                        + " verify " + aem.home + " --aem-url http://localhost:4502");
                return 0;
            }
            case "verify" -> {
                String url = opt.getOrDefault("aem-url", "http://localhost:4502");
                String auth = env(opt.getOrDefault("auth-env", "OAKCHAIN_AEM_AUTH"));
                if (auth == null) {
                    throw new IllegalArgumentException("set " + opt.getOrDefault("auth-env", "OAKCHAIN_AEM_AUTH") + "=user:password");
                }
                boolean expectMount = opt.containsKey("expect") ? opt.get("expect").equals("mount") : plan.enabled();
                String mountPath = plan.state().getProperty("mount.path", opt.getOrDefault("mount-path", "/oak-chain"));
                int timeout = Integer.parseInt(opt.getOrDefault("timeout", "300"));
                List<Check> checks = new Verify(url, auth).run(aem, expectMount, mountPath, timeout, opt.containsKey("write-probe"));
                return report("verify " + url + " (" + (expectMount ? "opted in" : "opted out") + ")", checks);
            }
            case "status" -> {
                if (!plan.enabled()) {
                    System.out.println("not opted in: " + aem.home);
                    return 0;
                }
                Properties s = plan.state();
                System.out.println("opted in since " + s.getProperty("enabledAt") + " (tool " + s.getProperty("version") + ")");
                System.out.println("  validator      " + s.getProperty("validator.url"));
                System.out.println("  mount          " + s.getProperty("mount.path") + " (" + s.getProperty("mount.name") + ")");
                System.out.println("  global store   " + s.getProperty("segmentstore") + " (unchanged location)");
                var running = aem.isRunning();
                System.out.println("  AEM            " + (running.isEmpty() ? "unknown" : running.get() ? "running" : "stopped"));
                var drift = plan.drift();
                if (drift.isEmpty()) {
                    System.out.println("  files          all managed files match what enable wrote");
                    return 0;
                }
                for (var d : drift) {
                    System.out.println("  DRIFT          " + d.path() + " (" + (d.actual().equals("missing") ? "missing" : "modified") + ")");
                }
                return 1;
            }
            case "disable" -> {
                if (!plan.enabled()) {
                    System.out.println("not opted in: nothing to do");
                    return 0;
                }
                var running = aem.isRunning();
                if (running.isPresent() && running.get()) {
                    System.out.println("FAIL  AEM is running (repository lock held); stop it first");
                    return 1;
                }
                var drift = plan.drift();
                if (!drift.isEmpty() && !opt.containsKey("force")) {
                    for (var d : drift) {
                        System.out.println("DRIFT " + d.path());
                    }
                    System.out.println("managed files changed since enable; re-run with --force to restore originals anyway");
                    return 1;
                }
                for (String line : plan.disable()) {
                    System.out.println("  " + line);
                }
                System.out.println("\nOpt-out applied. Start AEM; verify with: verify " + aem.home + " --expect vanilla");
                return 0;
            }
            default -> throw new IllegalArgumentException("unknown command: " + command);
        }
    }

    private static Params params(Map<String, String> opt, OptInPlan plan, boolean required) throws IOException {
        Properties state = plan.state();
        String url = opt.getOrDefault("validator", state.getProperty("validator.url"));
        if (url == null) {
            if (required) {
                throw new IllegalArgumentException("--validator URL is required");
            }
            return null;
        }
        String name = opt.getOrDefault("mount-name", state.getProperty("mount.name", "oak-chain"));
        String path = opt.getOrDefault("mount-path", state.getProperty("mount.path", "/" + name));
        if (!path.startsWith("/") || path.equals("/") || path.chars().filter(c -> c == '/').count() != 1) {
            throw new IllegalArgumentException("--mount-path must be a single top-level path such as /oak-chain");
        }
        int refresh = Integer.parseInt(opt.getOrDefault("refresh", state.getProperty("refresh.interval.seconds", "5")));
        String token = opt.containsKey("token-env") ? env(opt.get("token-env")) : null;
        return new Params(url, name, path, refresh, token);
    }

    private static String env(String name) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? null : v;
    }

    static Path selfJar(Map<String, String> opt) throws URISyntaxException {
        if (opt.containsKey("bundle")) {
            return Path.of(opt.get("bundle"));
        }
        Path self = Path.of(OptInTool.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        if (!Files.isRegularFile(self)) {
            throw new IllegalArgumentException("not running from the bundle jar; pass --bundle <jar>");
        }
        return self;
    }

    private static Manifest selfManifest(Map<String, String> opt) throws IOException, URISyntaxException {
        Path jar;
        try {
            jar = selfJar(opt);
        } catch (IllegalArgumentException e) {
            return null;
        }
        try (JarFile jf = new JarFile(jar.toFile())) {
            return jf.getManifest();
        }
    }

    static Map<String, String> options(String[] args) {
        Map<String, String> opt = new HashMap<>();
        for (int i = 2; i < args.length; i++) {
            String a = args[i];
            if (!a.startsWith("--")) {
                throw new IllegalArgumentException("unexpected argument: " + a);
            }
            String key = a.substring(2);
            boolean flag = key.equals("force") || key.equals("write-probe");
            if (flag) {
                opt.put(key, "true");
            } else {
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("missing value for " + a);
                }
                opt.put(key, args[++i]);
            }
        }
        return opt;
    }

    private static int report(String title, List<Check> checks) {
        System.out.println(title);
        boolean failed = false;
        for (Check c : checks) {
            System.out.printf("  %-5s %-34s %s%n", c.status(), c.name(), c.detail());
            failed |= c.status() == Status.FAIL;
        }
        System.out.println(failed ? "RESULT: FAIL" : "RESULT: OK");
        return failed ? 1 : 0;
    }

    private static void usage() {
        System.err.println("""
                usage: java -jar oak-chain-connector.mount.jar <command> <aem-home> [options]

                  preflight <aem-home> [--validator URL] [--token-env VAR]
                  enable    <aem-home> --validator URL [--mount-name oak-chain] [--mount-path /oak-chain]
                                       [--refresh 5] [--token-env VAR]          (AEM stopped)
                  verify    <aem-home> [--aem-url http://localhost:4502] [--auth-env OAKCHAIN_AEM_AUTH]
                                       [--expect mount|vanilla] [--timeout 300] [--write-probe]
                  status    <aem-home>
                  disable   <aem-home> [--force]                                (AEM stopped)

                <aem-home> is the directory that contains crx-quickstart.""");
    }
}
