package com.oakchain.connector.mount;

import java.io.File;
import java.time.Duration;

import com.oakchain.connector.mount.http.ValidatorHttpClient;
import org.apache.jackrabbit.oak.spi.state.NodeStore;
import org.apache.jackrabbit.oak.spi.state.NodeStoreProvider;
import org.osgi.framework.BundleContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.AttributeType;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Supplies the read-only Oak Chain mount to Oak's {@code CompositeNodeStoreService}.
 *
 * <p>The only service this bundle registers is a {@link NodeStoreProvider} carrying
 * {@code role=composite-mount-<mount name>}. In AEM 6.5 LTS the only components that bind
 * {@code NodeStoreProvider} filter by role (composite, copy-on-write, secondary), so this
 * registration cannot rebind or restart any other store.
 *
 * <p>Activation never blocks on the validator: the provider registers immediately with an
 * empty mount and connects in the background. Keep this component stable at runtime; the
 * composite store, and therefore the repository, depends on it.
 */
@Component(
        service = NodeStoreProvider.class,
        configurationPolicy = ConfigurationPolicy.REQUIRE,
        immediate = true,
        property = NodeStoreProvider.ROLE + "=composite-mount-oak-chain")
@Designate(ocd = OakChainMountProvider.Config.class)
public class OakChainMountProvider implements NodeStoreProvider {

    private static final Logger LOG = LoggerFactory.getLogger(OakChainMountProvider.class);

    @ObjectClassDefinition(
            name = "Oak Chain Composite Mount",
            description = "Read-only, lazily connected view of an Oak Chain validator's segment store, "
                    + "provided to Oak's CompositeNodeStoreService as a read-only mount.")
    public @interface Config {

        @AttributeDefinition(name = "Role",
                description = "Must be composite-mount-<mountName>, matching MountInfoProviderService's mountName.")
        String role() default "composite-mount-oak-chain";

        @AttributeDefinition(name = "Validator URL", description = "Base URL of the validator HTTP endpoint.")
        String validator_url() default "http://localhost:8090";

        @AttributeDefinition(name = "Auth token",
                description = "Optional token sent verbatim as the Authorization header. Private: not published as a service property.",
                type = AttributeType.PASSWORD)
        String _auth_token() default "";

        @AttributeDefinition(name = "Refresh interval (s)", description = "How often to connect or follow the journal head.")
        int refresh_interval_seconds() default 5;

        @AttributeDefinition(name = "Connect timeout (ms)")
        int connect_timeout_ms() default 3000;

        @AttributeDefinition(name = "Request timeout (ms)")
        int request_timeout_ms() default 15000;

        @AttributeDefinition(name = "Segment cache (MB)")
        int segment_cache_mb() default 32;
    }

    private LazyRemoteNodeStore store;

    @Activate
    protected void activate(BundleContext context, Config config) {
        String role = config.role();
        String mountName = role.startsWith("composite-mount-") ? role.substring("composite-mount-".length()) : role;
        File workDir = context.getDataFile("mount-" + mountName.replaceAll("[^A-Za-z0-9._-]", "_"));
        ValidatorHttpClient http = new ValidatorHttpClient(
                config.validator_url(),
                config._auth_token(),
                Duration.ofMillis(config.connect_timeout_ms()),
                Duration.ofMillis(config.request_timeout_ms()));
        store = new LazyRemoteNodeStore(mountName, http, workDir, config.refresh_interval_seconds(), config.segment_cache_mb());
        store.start();
        LOG.info("Oak Chain mount provider active: role={} validator={} refresh={}s (connects in background)",
                role, http.getBaseUrl(), config.refresh_interval_seconds());
    }

    @Deactivate
    protected void deactivate() {
        LazyRemoteNodeStore s = store;
        store = null;
        if (s != null) {
            s.close();
        }
    }

    @Override
    public NodeStore getNodeStore() {
        return store;
    }
}
