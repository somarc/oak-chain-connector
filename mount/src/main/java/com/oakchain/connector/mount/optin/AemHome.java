package com.oakchain.connector.mount.optin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.stream.Stream;

/** An AEM installation on disk: the directory that contains {@code crx-quickstart}. */
final class AemHome {

    final Path home;
    final Path quickstart;
    final Path launchpadConfig;
    final Path felixCache;
    final Path install;
    final Path optInDir;
    private final Properties sling = new Properties();

    AemHome(Path home) throws IOException {
        this.home = home.toAbsolutePath().normalize();
        this.quickstart = this.home.resolve("crx-quickstart");
        this.launchpadConfig = quickstart.resolve("launchpad/config");
        this.felixCache = quickstart.resolve("launchpad/felix");
        this.install = quickstart.resolve("install");
        this.optInDir = quickstart.resolve("oak-chain-opt-in");
        Path props = quickstart.resolve("conf/sling.properties");
        if (Files.isRegularFile(props)) {
            try (InputStream in = Files.newInputStream(props)) {
                sling.load(in);
            }
        }
    }

    boolean looksLikeAem() {
        return Files.isDirectory(launchpadConfig) && Files.isDirectory(felixCache);
    }

    String quickstartBuild() {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(quickstart.resolve("conf/quickstart.properties"))) {
            p.load(in);
        } catch (IOException e) {
            return "unknown";
        }
        return p.getProperty("quickstart.build", "unknown");
    }

    String allSlingPropertyValues() {
        StringBuilder b = new StringBuilder();
        for (String k : sling.stringPropertyNames()) {
            b.append(sling.getProperty(k)).append(',');
        }
        return b.toString();
    }

    String slingProperty(String key) {
        return sling.getProperty(key);
    }

    /** {@code repository.home}, resolving the usual {@code ${sling.home}} prefix. */
    Path repositoryHome() {
        String v = sling.getProperty("repository.home", "${sling.home}/repository");
        v = v.replace("${sling.home}", quickstart.toString());
        Path p = Path.of(v);
        return p.isAbsolute() ? p.normalize() : home.resolve(p).normalize();
    }

    Path segmentStore() {
        return repositoryHome().resolve("segmentstore");
    }

    /**
     * Running check without side effects: Oak holds an exclusive lock on {@code repo.lock}
     * for as long as the TarMK store is open.
     */
    Optional<Boolean> isRunning() {
        Path lock = segmentStore().resolve("repo.lock");
        if (!Files.isRegularFile(lock)) {
            return Optional.empty();
        }
        try (FileChannel ch = FileChannel.open(lock, StandardOpenOption.WRITE)) {
            FileLock l = ch.tryLock();
            if (l == null) {
                return Optional.of(true);
            }
            l.release();
            return Optional.of(false);
        } catch (OverlappingFileLockException e) {
            return Optional.of(true);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** Manifests of the newest revision of every bundle in the Felix cache. */
    List<Manifest> cachedBundleManifests() throws IOException {
        List<Manifest> out = new ArrayList<>();
        if (!Files.isDirectory(felixCache)) {
            return out;
        }
        try (Stream<Path> bundles = Files.list(felixCache)) {
            for (Path b : (Iterable<Path>) bundles.filter(p -> p.getFileName().toString().startsWith("bundle"))::iterator) {
                Optional<Path> newest;
                try (Stream<Path> versions = Files.list(b)) {
                    newest = versions.filter(p -> p.getFileName().toString().startsWith("version"))
                            .max(Comparator.comparing(AemHome::revisionKey));
                }
                if (newest.isEmpty()) {
                    continue;
                }
                Path jar = newest.get().resolve("bundle.jar");
                if (!Files.isRegularFile(jar)) {
                    continue;
                }
                try (JarFile jf = new JarFile(jar.toFile())) {
                    Manifest m = jf.getManifest();
                    if (m != null) {
                        out.add(m);
                    }
                } catch (IOException ignored) {
                    // unreadable cache entry: skip
                }
            }
        }
        return out;
    }

    private static String revisionKey(Path versionDir) {
        String[] parts = versionDir.getFileName().toString().replace("version", "").split("\\.");
        StringBuilder key = new StringBuilder();
        for (String p : parts) {
            key.append(String.format("%08d", p.matches("\\d+") ? Integer.parseInt(p) : 0));
        }
        return key.toString();
    }

    Path apiControllerConfig() {
        return launchpadConfig.resolve("com/adobe/granite/apicontroller/FilterResolverHookFactory.config");
    }

    /** Current boot's section of error.log, starting at the last Logback startup banner. */
    List<String> currentBootLog() throws IOException {
        Path log = quickstart.resolve("logs/error.log");
        if (!Files.isRegularFile(log)) {
            return List.of();
        }
        List<String> lines = Files.readAllLines(log);
        int start = 0;
        for (int i = lines.size() - 1; i >= 0; i--) {
            if (lines.get(i).contains("LogbackManager initialized at bundle startup")) {
                start = i;
                break;
            }
        }
        return lines.subList(start, lines.size());
    }
}
