package com.oakchain.connector.mount.optin;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.stream.Stream;

/**
 * The offline opt-in: what changes on disk, and how to put it back exactly.
 *
 * <p>Nothing under {@code repository/} is touched. The existing TarMK store is opened in
 * place by a role-tagged {@code TarPersistenceService}, which becomes the composite's global
 * store. AEM's primary {@code SegmentNodeStoreService} is left configured but inert.
 */
final class OptInPlan {

    static final String VERSION = "0.1.0";
    static final String MARKER = "oakchain.optin";

    static final String PRIMARY_PID = "org.apache.jackrabbit.oak.segment.SegmentNodeStoreService";
    static final String FACTORY_PID = "org.apache.jackrabbit.oak.segment.SegmentNodeStoreFactory";
    static final String GLOBAL_PID = FACTORY_PID + "~oakchain-global";
    static final String TAR_PID = "org.apache.jackrabbit.oak.segment.osgi.TarPersistenceService";
    static final String MIP_PID = "org.apache.jackrabbit.oak.composite.MountInfoProviderService";
    static final String COMPOSITE_PID = "org.apache.jackrabbit.oak.composite.CompositeNodeStoreService";
    static final String PROVIDER_PID = "com.oakchain.connector.mount.OakChainMountProvider";
    static final String DOCUMENT_PID = "org.apache.jackrabbit.oak.plugins.document.DocumentNodeStoreService";
    /** AEM's "Revision Clean Up" maintenance task: the factory config AEM 6.5 LTS ships (launchpad resource). */
    static final String REVISION_CLEANUP_PID = "com.adobe.granite.maintenance.crx.impl.RevisionCleanupTask~main";
    static final String GLOBAL_ROLE = "composite-global";

    static final String GLOBAL_TAR_ROLE = "oakchain-global-tar";
    static final String PRIMARY_DISABLED_ROLE = "oakchain-primary-disabled";
    static final String BUNDLE_FILE = "install/15/oak-chain-connector.mount.jar";
    static final String PRIMARY_INSTALL_FILE = "install/" + PRIMARY_PID + ".config";

    /** Keys of the primary store config that must not be copied to the global factory store. */
    private static final Set<String> NOT_COPIED = Set.of(
            ":org.apache.felix.configadmin.revision:", "service.pid", "service.factoryPid", "service.ranking",
            "name", "host", "standby", "secondary", "role", "customSegmentStore", "segmentStore.target",
            "splitPersistence", "_felix_.cm.newConfiguration", MARKER);

    record Params(String validatorUrl, String mountName, String mountPath, int refreshSeconds, String authToken) {
        String role() {
            return "composite-mount-" + mountName;
        }
    }

    enum Status { PASS, WARN, FAIL, INFO }

    record Check(Status status, String name, String detail) { }

    private final AemHome aem;

    OptInPlan(AemHome aem) {
        this.aem = aem;
    }

    Path persisted(String pid) {
        return FelixConfig.persistedPath(aem.launchpadConfig, pid);
    }

    boolean enabled() {
        return Files.isRegularFile(aem.optInDir.resolve("state.properties"));
    }

    // ------------------------------------------------------------------ preflight

    List<Check> preflight(Params p, Manifest bundle, boolean requireStopped) throws IOException {
        List<Check> out = new ArrayList<>();
        if (!aem.looksLikeAem()) {
            out.add(new Check(Status.FAIL, "AEM home", aem.home + " has no crx-quickstart/launchpad (start AEM once first)"));
            return out;
        }
        String build = aem.quickstartBuild();
        out.add(new Check(build.startsWith("6.6.") ? Status.PASS : Status.WARN, "AEM build",
                build + (build.startsWith("6.6.") ? " (AEM 6.5 LTS)" : " (verified on 6.5 LTS SP3 / 6.6.3 only)")));

        var running = aem.isRunning();
        if (running.isEmpty()) {
            out.add(new Check(requireStopped ? Status.FAIL : Status.WARN, "AEM stopped",
                    "cannot determine (no lock file at " + aem.segmentStore().resolve("repo.lock") + ")"));
        } else if (running.get()) {
            out.add(new Check(requireStopped ? Status.FAIL : Status.WARN, "AEM stopped",
                    "repository lock is held: AEM is running" + (requireStopped ? "; stop it first" : "; stop it before enable")));
        } else {
            out.add(new Check(Status.PASS, "AEM stopped", "repository lock is free"));
        }

        Path segmentStore = segmentStorePath();
        boolean hasTar;
        try (Stream<Path> s = Files.isDirectory(segmentStore) ? Files.list(segmentStore) : Stream.empty()) {
            hasTar = s.anyMatch(f -> f.getFileName().toString().matches("data\\d+a\\.tar"));
        }
        out.add(new Check(hasTar ? Status.PASS : Status.FAIL, "TarMK store", segmentStore + (hasTar ? "" : " has no data*.tar")));

        Path primary = persisted(PRIMARY_PID);
        if (!Files.isRegularFile(primary)) {
            out.add(new Check(Status.FAIL, "Primary store config", "missing " + primary + " (only TarMK installs are supported)"));
        } else {
            FelixConfig c = FelixConfig.read(primary);
            boolean ours = c.has(MARKER);
            if (!ours && c.bool("customSegmentStore", false)) {
                out.add(new Check(Status.FAIL, "Primary store config", "already uses a custom segment store; not supported"));
            } else if (!ours && (c.bool("standby", false) || c.bool("secondary", false))) {
                out.add(new Check(Status.FAIL, "Primary store config", "standby/secondary instance; not supported"));
            } else {
                out.add(new Check(Status.PASS, "Primary store config", ours ? "already managed by opt-in" : "TarMK SegmentNodeStoreService"));
            }
        }
        if (Files.isRegularFile(persisted(DOCUMENT_PID))) {
            out.add(new Check(Status.FAIL, "Storage", "DocumentNodeStoreService (MongoMK) is configured; only TarMK is supported"));
        }

        out.add(foreignCheck("Composite config", persisted(COMPOSITE_PID), true));
        out.add(foreignCheck("Mount info config", persisted(MIP_PID), false));
        out.add(foreignCheck("TAR persistence config", persisted(TAR_PID), false));
        out.add(foreignCheck("Global store config", persisted(GLOBAL_PID), false));
        out.add(foreignCheck("install/ primary override", aem.quickstart.resolve(PRIMARY_INSTALL_FILE), false));
        boolean cleanup = Files.isRegularFile(persisted(REVISION_CLEANUP_PID));
        out.add(new Check(cleanup ? Status.PASS : Status.WARN, "Revision Clean Up task",
                cleanup ? "RevisionCleanupTask~main present; enable sets role=" + GLOBAL_ROLE
                        : "RevisionCleanupTask~main not found; set role=" + GLOBAL_ROLE
                        + " on your RevisionCleanupTask config, or daily maintenance will not compact the repository"));

        OsgiCheck osgi = new OsgiCheck(aem);
        boolean composite = hasBundle("org.apache.jackrabbit.oak-store-composite", null);
        out.add(new Check(composite ? Status.PASS : Status.FAIL, "Oak composite store", composite ? "oak-store-composite installed" : "oak-store-composite not installed"));
        boolean tarPersistence = hasBundle("org.apache.jackrabbit.oak-segment-tar",
                "org/apache/jackrabbit/oak/segment/osgi/TarPersistenceService.class");
        out.add(new Check(tarPersistence ? Status.PASS : Status.FAIL, "Oak TAR persistence service",
                tarPersistence ? "available in oak-segment-tar" : "TarPersistenceService not found in oak-segment-tar"));
        if (bundle != null) {
            List<OsgiCheck.Problem> problems = osgi.check(bundle);
            if (problems.isEmpty()) {
                out.add(new Check(Status.PASS, "Bundle wiring", "all Import-Package clauses resolve against " + osgi.exporterCount()
                        + " exported packages" + (osgi.apiControllerKnown() ? "; none restricted by the API controller" : "")));
            } else {
                for (OsgiCheck.Problem pr : problems) {
                    out.add(new Check(Status.FAIL, "Bundle wiring", pr.pkg() + ": " + pr.reason()));
                }
            }
        }
        if (p != null) {
            out.addAll(Validator.checks(p.validatorUrl(), p.authToken()));
        }
        return out;
    }

    private Check foreignCheck(String name, Path file, boolean placeholderAllowed) throws IOException {
        if (!Files.isRegularFile(file)) {
            return new Check(Status.PASS, name, "not present");
        }
        FelixConfig c = FelixConfig.read(file);
        if (c.has(MARKER)) {
            return new Check(Status.PASS, name, "managed by opt-in");
        }
        if (placeholderAllowed && c.keys().stream().allMatch(k -> k.startsWith(":") || k.equals("service.pid")
                || k.equals("_felix_.cm.newConfiguration"))) {
            return new Check(Status.PASS, name, "Package Manager placeholder (will be backed up)");
        }
        return new Check(Status.FAIL, name, "foreign configuration at " + file + "; refusing to overwrite");
    }

    private boolean hasBundle(String bsn, String requiredEntry) throws IOException {
        if (!Files.isDirectory(aem.felixCache)) {
            return false;
        }
        try (Stream<Path> jars = Files.find(aem.felixCache, 3, (f, a) -> f.getFileName().toString().equals("bundle.jar"))) {
            for (Path jar : (Iterable<Path>) jars::iterator) {
                try (JarFile jf = new JarFile(jar.toFile())) {
                    Manifest m = jf.getManifest();
                    if (m != null && OsgiCheck.header(m, "Bundle-SymbolicName").split(";")[0].trim().equals(bsn)
                            && (requiredEntry == null || jf.getEntry(requiredEntry) != null)) {
                        return true;
                    }
                } catch (IOException ignored) {
                    // skip
                }
            }
        }
        return false;
    }

    Path segmentStorePath() throws IOException {
        Path primary = persisted(PRIMARY_PID);
        if (Files.isRegularFile(primary)) {
            String home = FelixConfig.read(primary).raw("repository.home");
            if (home != null) {
                String h = home.replace("\"", "").replace("${sling.home}", aem.quickstart.toString());
                Path hp = Path.of(h);
                return (hp.isAbsolute() ? hp : aem.home.resolve(hp)).resolve("segmentstore").normalize();
            }
        }
        return aem.segmentStore();
    }

    // ------------------------------------------------------------------ enable

    /** Target content for every file the opt-in owns, keyed by path relative to crx-quickstart. */
    Map<String, String> render(Params p) throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        Path primaryFile = persisted(PRIMARY_PID);
        FelixConfig original = originalPrimary();

        FelixConfig primary = FelixConfig.read(primaryFile);
        if (primary.has(MARKER)) {
            primary = originalPrimary(); // fresh copy: `original` is read below for the global store
            primary.putString("service.pid", PRIMARY_PID);
        }
        primary.putBool("customSegmentStore", true)
                .putString("segmentStore.target", "(role=" + PRIMARY_DISABLED_ROLE + ")")
                .putBool(MARKER, true);
        files.put(rel(primaryFile), primary.render());
        files.put(PRIMARY_INSTALL_FILE, primary.renderForInstaller());

        FelixConfig global = new FelixConfig();
        for (String k : original.keys()) {
            if (!NOT_COPIED.contains(k)) {
                global.putRaw(k, original.raw(k));
            }
        }
        global.putString("role", GLOBAL_ROLE)
                .putBool("customSegmentStore", true)
                .putString("segmentStore.target", "(role=" + GLOBAL_TAR_ROLE + ")")
                .putBool("customBlobStore", original.bool("customBlobStore", false))
                .putBool("dispatchChanges", true)
                .putBool("registerDescriptors", true)
                .putBool(MARKER, true)
                .putString("service.pid", GLOBAL_PID)
                .putString("service.factoryPid", FACTORY_PID);
        files.put(rel(persisted(GLOBAL_PID)), global.render());

        files.put(rel(persisted(TAR_PID)), new FelixConfig()
                .putString("storage.directory", segmentStorePath().toString())
                .putString("role", GLOBAL_TAR_ROLE)
                .putBool(MARKER, true)
                .putString("service.pid", TAR_PID).render());

        files.put(rel(persisted(MIP_PID)), new FelixConfig()
                .putString("mountName", p.mountName())
                .putStrings("mountedPaths", List.of(p.mountPath()))
                .putStrings("pathsSupportingFragments", List.of(p.mountPath()))
                .putBool("readOnlyMount", true)
                .putBool(MARKER, true)
                .putString("service.pid", MIP_PID).render());

        files.put(rel(persisted(COMPOSITE_PID)), new FelixConfig()
                .putBool("enabled", false)
                .putBool("enableChecks", false)
                .putBool("pathStats", false)
                .putBool(MARKER, true)
                .putString("service.pid", COMPOSITE_PID).render());

        // Revision Clean Up asks Oak's RepositoryManager for the RevisionGCMBean without a role, which
        // matches only beans with no role property. After opt-in the one revision GC is the global
        // store's (role=composite-global), so without this AEM's daily maintenance never compacts.
        // Only the shipped ~main instance is edited in place; creating one could start a second task.
        if (hasOriginal(REVISION_CLEANUP_PID)) {
            files.put(rel(persisted(REVISION_CLEANUP_PID)), originalConfig(REVISION_CLEANUP_PID)
                    .putString("role", GLOBAL_ROLE)
                    .putBool(MARKER, true).render());
        }

        FelixConfig provider = new FelixConfig()
                .putString("role", p.role())
                .putString("validator.url", p.validatorUrl())
                .putInt("refresh.interval.seconds", p.refreshSeconds())
                .putBool(MARKER, true)
                .putString("service.pid", PROVIDER_PID);
        if (p.authToken() != null && !p.authToken().isBlank()) {
            provider.putString(".auth.token", p.authToken());
        }
        files.put(rel(persisted(PROVIDER_PID)), provider.render());
        return files;
    }

    /** Whether a config existed before opt-in (it is backed up, or present and not written by us). */
    private boolean hasOriginal(String pid) throws IOException {
        Path backup = aem.optInDir.resolve("backup").resolve(rel(persisted(pid)));
        return Files.isRegularFile(backup)
                || (Files.isRegularFile(persisted(pid)) && !FelixConfig.read(persisted(pid)).has(MARKER));
    }

    /** A config as it was before opt-in: the backup when already enabled, else the current file, else empty. */
    private FelixConfig originalConfig(String pid) throws IOException {
        Path backup = aem.optInDir.resolve("backup").resolve(rel(persisted(pid)));
        if (Files.isRegularFile(backup)) {
            return FelixConfig.read(backup);
        }
        Path current = persisted(pid);
        if (Files.isRegularFile(current)) {
            FelixConfig c = FelixConfig.read(current);
            if (!c.has(MARKER)) {
                return c;
            }
        }
        return new FelixConfig();
    }

    /** The primary store config as it was before opt-in (from the backup when already enabled). */
    private FelixConfig originalPrimary() throws IOException {
        Path backup = aem.optInDir.resolve("backup").resolve(rel(persisted(PRIMARY_PID)));
        return FelixConfig.read(Files.isRegularFile(backup) ? backup : persisted(PRIMARY_PID));
    }

    List<String> enable(Params p, Path bundleJar) throws IOException {
        List<String> log = new ArrayList<>();
        Properties state = loadState();
        Map<String, String> files = render(p);
        Path backupDir = aem.optInDir.resolve("backup");
        Files.createDirectories(backupDir);

        for (Map.Entry<String, String> f : files.entrySet()) {
            Path target = aem.quickstart.resolve(f.getKey());
            String key = stateKey(state, f.getKey());
            boolean already = state.containsKey(key + ".path");
            if (!already && Files.exists(target)) {
                Path backup = backupDir.resolve(f.getKey());
                Files.createDirectories(backup.getParent());
                Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                state.setProperty(key + ".backup", "true");
                log.add("backed up " + f.getKey());
            }
            boolean secret = f.getKey().equals(rel(persisted(PROVIDER_PID))) && p.authToken() != null && !p.authToken().isBlank();
            writeAtomically(target, f.getValue().getBytes(StandardCharsets.UTF_8), secret);
            state.setProperty(key + ".path", f.getKey());
            state.setProperty(key + ".sha256", sha256(target));
            log.add((already ? "updated " : "wrote ") + f.getKey());
        }
        Path jar = aem.quickstart.resolve(BUNDLE_FILE);
        Files.createDirectories(jar.getParent());
        Path tmp = jar.resolveSibling(jar.getFileName() + ".tmp");
        Files.copy(bundleJar, tmp, StandardCopyOption.REPLACE_EXISTING);
        Files.move(tmp, jar, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        state.setProperty("bundle.path", BUNDLE_FILE);
        state.setProperty("bundle.sha256", sha256(jar));
        log.add("installed " + BUNDLE_FILE);

        state.setProperty("version", VERSION);
        state.setProperty("enabledAt", Instant.now().toString());
        state.setProperty("validator.url", p.validatorUrl());
        state.setProperty("mount.name", p.mountName());
        state.setProperty("mount.path", p.mountPath());
        state.setProperty("refresh.interval.seconds", String.valueOf(p.refreshSeconds()));
        state.setProperty("segmentstore", segmentStorePath().toString());
        state.setProperty("cleanup.emptyDir", aem.repositoryHome().resolve("segmentstore-composite-global").toString());
        saveState(state);
        return log;
    }

    /**
     * The state slot of a managed file: its existing slot, else the first free one. By path, not
     * position, so a re-enable that manages a different set of files never mixes up originals.
     */
    static String stateKey(Properties state, String path) {
        int n = 0;
        for (; state.containsKey("file." + n + ".path"); n++) {
            if (path.equals(state.getProperty("file." + n + ".path"))) {
                return "file." + n;
            }
        }
        return "file." + n;
    }

    // ------------------------------------------------------------------ status / disable

    record Drift(String path, String expected, String actual) { }

    List<Drift> drift() throws IOException {
        List<Drift> out = new ArrayList<>();
        Properties s = loadState();
        for (int i = 0; s.containsKey("file." + i + ".path"); i++) {
            String path = s.getProperty("file." + i + ".path");
            Path f = aem.quickstart.resolve(path);
            String actual = Files.isRegularFile(f) ? sha256(f) : "missing";
            if (!actual.equals(s.getProperty("file." + i + ".sha256"))) {
                out.add(new Drift(path, s.getProperty("file." + i + ".sha256"), actual));
            }
        }
        Path jar = aem.quickstart.resolve(BUNDLE_FILE);
        String actual = Files.isRegularFile(jar) ? sha256(jar) : "missing";
        if (s.containsKey("bundle.sha256") && !actual.equals(s.getProperty("bundle.sha256"))) {
            out.add(new Drift(BUNDLE_FILE, s.getProperty("bundle.sha256"), actual));
        }
        return out;
    }

    Properties state() throws IOException {
        return loadState();
    }

    List<String> disable() throws IOException {
        List<String> log = new ArrayList<>();
        Properties s = loadState();
        Path backupDir = aem.optInDir.resolve("backup");
        for (int i = 0; s.containsKey("file." + i + ".path"); i++) {
            String path = s.getProperty("file." + i + ".path");
            Path target = aem.quickstart.resolve(path);
            if (Boolean.parseBoolean(s.getProperty("file." + i + ".backup"))) {
                Files.copy(backupDir.resolve(path), target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                log.add("restored " + path);
            } else if (Files.deleteIfExists(target)) {
                log.add("removed " + path);
                pruneEmptyParents(target.getParent());
            }
        }
        Path jar = aem.quickstart.resolve(BUNDLE_FILE);
        if (Files.deleteIfExists(jar)) {
            log.add("removed " + BUNDLE_FILE + " (the installer uninstalls the bundle on next start)");
            pruneEmptyParents(jar.getParent());
        }
        String emptyDir = s.getProperty("cleanup.emptyDir");
        if (emptyDir != null) {
            Path d = Path.of(emptyDir);
            if (Files.isDirectory(d)) {
                try (Stream<Path> c = Files.list(d)) {
                    if (c.findAny().isEmpty()) {
                        Files.delete(d);
                        log.add("removed empty " + aem.quickstart.relativize(d));
                    }
                }
            }
        }
        deleteRecursively(aem.optInDir);
        log.add("removed " + aem.quickstart.relativize(aem.optInDir));
        return log;
    }

    // ------------------------------------------------------------------ helpers

    private String rel(Path p) {
        return aem.quickstart.relativize(p).toString();
    }

    private Properties loadState() throws IOException {
        Properties s = new Properties();
        Path f = aem.optInDir.resolve("state.properties");
        if (Files.isRegularFile(f)) {
            try (InputStream in = Files.newInputStream(f)) {
                s.load(in);
            }
        }
        return s;
    }

    private void saveState(Properties s) throws IOException {
        Files.createDirectories(aem.optInDir);
        Path f = aem.optInDir.resolve("state.properties");
        Path tmp = f.resolveSibling("state.properties.tmp");
        try (OutputStream out = Files.newOutputStream(tmp)) {
            s.store(out, "Oak Chain composite mount opt-in. Managed by: java -jar oak-chain-connector.mount.jar");
        }
        Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private void pruneEmptyParents(Path dir) throws IOException {
        Path stop = aem.launchpadConfig;
        Path stop2 = aem.install;
        Path d = dir;
        while (d != null && !d.equals(stop) && !d.equals(stop2) && d.startsWith(aem.quickstart)) {
            try (Stream<Path> c = Files.list(d)) {
                if (c.findAny().isPresent()) {
                    return;
                }
            }
            Files.delete(d);
            d = d.getParent();
        }
    }

    private static void writeAtomically(Path target, byte[] content, boolean ownerOnly) throws IOException {
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".oakchain-tmp");
        Files.write(tmp, content);
        if (ownerOnly) {
            try {
                Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // non-POSIX filesystem
            }
        }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    static String sha256(Path f) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(f)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) {
                    md.update(buf, 0, n);
                }
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) walk.sorted((a, b) -> b.getNameCount() - a.getNameCount())::iterator) {
                Files.delete(p);
            }
        }
    }
}
