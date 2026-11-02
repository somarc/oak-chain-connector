package com.oakchain.connector.mount.optin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Enable then disable must leave an AEM home byte-identical, however often it is applied. */
public class OptInPlanTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Path home;
    private Path jar;
    private OptInPlan plan;
    private final OptInPlan.Params params =
            new OptInPlan.Params("http://localhost:8090", "oak-chain", "/oak-chain", 5, null);

    @Before
    public void setUp() throws IOException {
        home = tmp.newFolder("aem").toPath();
        Path q = home.resolve("crx-quickstart");
        write(q.resolve("conf/sling.properties"), "repository.home=${sling.home}/repository\n");
        write(q.resolve("conf/quickstart.properties"), "quickstart.build=6.6.3.20260819\n");
        Files.createDirectories(q.resolve("launchpad/felix"));
        write(q.resolve("launchpad/config/org/apache/jackrabbit/oak/segment/SegmentNodeStoreService.config"),
                ":org.apache.felix.configadmin.revision:=L\"1\"\ncustomBlobStore=B\"true\"\nhost=\"localhost\"\n"
                        + "name=\"Oak-Segment-Tar\"\nservice.pid=\"org.apache.jackrabbit.oak.segment.SegmentNodeStoreService\"\n"
                        + "service.ranking=I\"100\"\ntarmk.size=I\"512\"\n");
        write(q.resolve("launchpad/config/org/apache/jackrabbit/oak/composite/CompositeNodeStoreService.config"),
                ":org.apache.felix.configadmin.revision:=L\"1\"\n_felix_.cm.newConfiguration=B\"true\"\n"
                        + "service.pid=\"org.apache.jackrabbit.oak.composite.CompositeNodeStoreService\"\n");
        // As AEM 6.5 LTS ships it (launchpad resource crx3/...RevisionCleanupTask-main.config).
        write(q.resolve("launchpad/config/com/adobe/granite/maintenance/crx/impl/RevisionCleanupTask%007emain.config"),
                ":org.apache.felix.configadmin.revision:=L\"1\"\njob.topics=\"com/adobe/granite/maintenance/job/RevisionCleanupTask\"\n"
                        + "service.factoryPid=\"com.adobe.granite.maintenance.crx.impl.RevisionCleanupTask\"\n"
                        + "service.pid=\"com.adobe.granite.maintenance.crx.impl.RevisionCleanupTask~main\"\n");
        write(q.resolve("repository/segmentstore/data00000a.tar"), "tar");
        write(q.resolve("repository/segmentstore/journal.log"), "journal");
        Files.createDirectories(q.resolve("install"));
        jar = tmp.newFile("oak-chain-connector.mount.jar").toPath();
        Files.writeString(jar, "jar-bytes");
        plan = new OptInPlan(new AemHome(home));
    }

    @Test
    public void enableWritesTheCompositeWiringWithoutTouchingTheRepository() throws IOException {
        Map<String, String> repoBefore = snapshot(home.resolve("crx-quickstart/repository"));
        plan.enable(params, jar);

        Path cfg = home.resolve("crx-quickstart/launchpad/config/org/apache/jackrabbit/oak");
        String primary = Files.readString(cfg.resolve("segment/SegmentNodeStoreService.config"));
        assertTrue(primary.contains("customSegmentStore=B\"true\""));
        assertTrue(primary.contains("segmentStore.target=\"(role\\=oakchain-primary-disabled)\""));
        assertTrue("operator settings kept", primary.contains("tarmk.size=I\"512\""));

        String global = Files.readString(cfg.resolve("segment/SegmentNodeStoreFactory%007eoakchain-global.config"));
        assertTrue(global.contains("role=\"composite-global\""));
        assertTrue(global.contains("customBlobStore=B\"true\""));
        assertTrue(global.contains("dispatchChanges=B\"true\""));
        assertTrue(global.contains("registerDescriptors=B\"true\""));
        assertTrue("tuning copied to the global store", global.contains("tarmk.size=I\"512\""));
        assertFalse(global.contains("service.ranking"));

        String tar = Files.readString(cfg.resolve("segment/osgi/TarPersistenceService.config"));
        assertTrue(tar.contains("storage.directory=\"" + home.resolve("crx-quickstart/repository/segmentstore").toRealPath().toString()
                .replace(" ", "\\ ") + "\"") || tar.contains("repository/segmentstore\""));

        String composite = Files.readString(cfg.resolve("composite/CompositeNodeStoreService.config"));
        assertTrue("Package Manager reads 'enabled'", composite.contains("enabled=B\"false\""));
        assertTrue(Files.readString(cfg.resolve("composite/MountInfoProviderService.config")).contains("mountedPaths=[\"/oak-chain\"]"));
        assertTrue(Files.isRegularFile(home.resolve("crx-quickstart/install/15/oak-chain-connector.mount.jar")));
        assertTrue(Files.readString(home.resolve("crx-quickstart/install/org.apache.jackrabbit.oak.segment.SegmentNodeStoreService.config"))
                .contains("customSegmentStore=B\"true\""));

        String cleanup = Files.readString(home.resolve(
                "crx-quickstart/launchpad/config/com/adobe/granite/maintenance/crx/impl/RevisionCleanupTask%007emain.config"));
        assertTrue("Revision Clean Up targets the global store", cleanup.contains("role=\"composite-global\""));
        assertTrue("edited in place", cleanup.contains("service.factoryPid=\"com.adobe.granite.maintenance.crx.impl.RevisionCleanupTask\""));
        assertTrue(cleanup.contains("job.topics=\"com/adobe/granite/maintenance/job/RevisionCleanupTask\""));

        assertEquals("repository/ is never modified", repoBefore, snapshot(home.resolve("crx-quickstart/repository")));
        assertTrue(plan.drift().isEmpty());
    }

    /**
     * Re-running enable when the managed file set changed (a newer tool, or a config that
     * appeared since) must still back up every original and restore all of them on disable.
     */
    @Test
    public void reEnableWithAChangedFileSetKeepsEveryOriginal() throws IOException {
        Map<String, String> before = snapshot(home);
        Path main = home.resolve("crx-quickstart/launchpad/config/com/adobe/granite/maintenance/crx/impl/RevisionCleanupTask%007emain.config");
        String original = Files.readString(main);
        Files.delete(main);
        plan.enable(params, jar); // manages one file fewer
        write(main, original);
        plan.enable(params, jar); // now also manages ~main, inserted mid-list
        assertTrue(Files.readString(main).contains("role=\"composite-global\""));
        plan.disable();
        assertEquals("every original restored byte for byte, nothing of ours left", before, snapshot(home));
    }

    @Test
    public void noRevisionCleanupConfigIsCreatedWhenAemHasNone() throws IOException {
        Path main = home.resolve("crx-quickstart/launchpad/config/com/adobe/granite/maintenance/crx/impl/RevisionCleanupTask%007emain.config");
        Files.delete(main);
        plan.enable(params, jar);
        assertFalse("a new instance could start a second task", Files.exists(main));
        plan.disable();
        assertFalse(Files.exists(main));
    }

    @Test
    public void readsAPropertyFromTheConfigurationStatus() {
        String status = "PID = a.B~main\n  Factory PID = a.B\n  role = composite-global\n\nPID = a.C\n  role = other\n";
        assertEquals("composite-global", Verify.configValue(status, "a.B~main", "role"));
        assertEquals(null, Verify.configValue(status, "a.B~main", "missing"));
        assertEquals(null, Verify.configValue(status, "a.D", "role"));
    }

    @Test
    public void disableRestoresByteIdenticalEvenAfterRepeatedEnable() throws IOException {
        Map<String, String> before = snapshot(home);
        plan.enable(params, jar);
        plan.enable(new OptInPlan.Params("http://validator:8090", "oak-chain", "/oak-chain", 10, null), jar);
        assertTrue(plan.state().getProperty("validator.url").equals("http://validator:8090"));
        plan.disable();
        assertEquals(before, snapshot(home));
        assertFalse(plan.enabled());

        // and the full cycle again
        plan.enable(params, jar);
        plan.disable();
        assertEquals(before, snapshot(home));
    }

    @Test
    public void driftIsReported() throws IOException {
        plan.enable(params, jar);
        Path mip = home.resolve("crx-quickstart/launchpad/config/org/apache/jackrabbit/oak/composite/MountInfoProviderService.config");
        Files.writeString(mip, Files.readString(mip) + "edited=B\"true\"\n");
        assertEquals(1, plan.drift().size());
        assertTrue(plan.drift().get(0).path().endsWith("MountInfoProviderService.config"));
    }

    @Test
    public void persistedPathEncodesFactoryPids() {
        Path base = Path.of("/x");
        assertEquals(Path.of("/x/org/apache/jackrabbit/oak/segment/SegmentNodeStoreFactory%007eoakchain-global.config"),
                FelixConfig.persistedPath(base, OptInPlan.GLOBAL_PID));
        assertEquals(Path.of("/x/org/apache/sling/commons/threads/impl/DefaultThreadPool/factory%007eoak.config"),
                FelixConfig.persistedPath(base, "org.apache.sling.commons.threads.impl.DefaultThreadPool.factory~oak"));
    }

    @Test
    public void felixConfigRoundTripsTypedAndEscapedValues() throws IOException {
        Path f = tmp.newFile("x.config").toPath();
        Files.writeString(f, ":org.apache.felix.configadmin.revision:=L\"1\"\n"
                + "packageBuilder.target=\"(name\\=socialpubsync-vlt)\"\nflag=B\"true\"\n"
                + "list=[ \\\n  \"/apps\", \\\n  \"/libs\" \\\n  ]\n");
        FelixConfig c = FelixConfig.read(f);
        assertEquals("\"(name\\=socialpubsync-vlt)\"", c.raw("packageBuilder.target"));
        assertTrue(c.bool("flag", false));
        assertTrue(c.raw("list").contains("\"/apps\""));
        assertEquals("\"(role\\=a\\ b)\"", FelixConfig.quote("(role=a b)"));
    }

    @Test
    public void versionRangesAndGlobs() {
        assertTrue(OsgiCheck.inRange("2.4.0", "[2.4,3)"));
        assertFalse(OsgiCheck.inRange("3.0.0", "[2.4,3)"));
        assertFalse(OsgiCheck.inRange("1.3.0", "[3.0,4)"));
        assertTrue(OsgiCheck.inRange("0.0.0", null));
        assertTrue(OsgiCheck.glob("org.apache.jackrabbit.*", "org.apache.jackrabbit.oak-core"));
        assertFalse(OsgiCheck.glob("com.adobe.*", "com.oakchain.connector.mount"));
    }

    private static void write(Path p, String content) throws IOException {
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    private static Map<String, String> snapshot(Path root) throws IOException {
        Map<String, String> out = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                String rel = root.relativize(p).toString();
                out.put(rel, Files.isDirectory(p) ? "<dir>" : OptInPlan.sha256(p));
            }
        }
        return out;
    }
}
