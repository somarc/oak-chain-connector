# Offline Oak Bootstrap Runbook

This runbook is for enterprise installs where the connector should remain read-plane-first by default, while the Oak composite mount/bootstrap PIDs are applied as an explicit maintenance-window step.

## Why This Is Separate

The connector-owned config package is safe to ship by default:

- `HttpPersistenceService`
- disabled wallet service
- disabled registration service
- disabled write proposal service
- disabled delete proposal service

The Oak bootstrap package is intentionally separate because it reconfigures the primary Oak repository:

- `SegmentNodeStoreFactory~default`
- `SegmentNodeStoreFactory~oak-chain`
- `MountInfoProviderService`
- `CompositeNodeStoreService`
- `HttpSegmentStoreSync`

Treat those PIDs like repository bootstrap, not routine OSGi toggles.

## Build

For AEM 6.5 LTS / 6.6:

```bash
mvn -Paem65lts -DskipTests clean package
```

Artifacts:

- runtime package: `all/target/oak-chain-connector-aem65lts-1.0.0-SNAPSHOT.zip`
- connector config package: `ui.config/target/oak-chain-connector-connector-config-aem65lts-1.0.0-SNAPSHOT.zip`
- Oak bootstrap package: `ui.config.oak-bootstrap/target/oak-chain-connector-oak-bootstrap-aem65lts-1.0.0-SNAPSHOT.zip`

## Upgrade Note For Older Combined Config Builds

Earlier builds shipped the Oak bootstrap PIDs inside the main `ui.config` package.

If you are upgrading from one of those builds and you want the new read-plane-only default, remove the old Oak bootstrap nodes before relying on the split packaging:

- `/apps/oak-chain-connector/osgiconfig/config.author/org.apache.jackrabbit.oak.segment.SegmentNodeStoreFactory~default.cfg.json`
- `/apps/oak-chain-connector/osgiconfig/config.author/org.apache.jackrabbit.oak.segment.SegmentNodeStoreFactory~oak-chain.cfg.json`
- `/apps/oak-chain-connector/osgiconfig/config.author/org.apache.jackrabbit.oak.composite.MountInfoProviderService.cfg.json`
- `/apps/oak-chain-connector/osgiconfig/config.author/org.apache.jackrabbit.oak.composite.CompositeNodeStoreService.cfg.json`
- `/apps/oak-chain-connector/osgiconfig/config.author/org.apache.jackrabbit.oak.segment.sync.HttpSegmentStoreSync.cfg.json`

Installing the new connector-owned config package does not delete those older repository nodes automatically.

## Install Sequence

### 1. Install runtime package

Install:

- `all/target/oak-chain-connector-aem65lts-1.0.0-SNAPSHOT.zip`

This puts the bundle/application content in place.

### 2. Install connector-owned config package

Install:

- `ui.config/target/oak-chain-connector-connector-config-aem65lts-1.0.0-SNAPSHOT.zip`

This gives you:

- validator URL defaulting to `http://localhost:8090`
- `lazyMount=true`
- wallet disabled
- registration disabled
- write proposal service disabled
- delete proposal service disabled

At this point, the connector remains read-plane-first and does not alter the primary Oak repository shape.

### 3. Maintenance window: install Oak bootstrap package

Only do this if you want the packaged Oak composite mount/bootstrap path.

Install:

- `ui.config.oak-bootstrap/target/oak-chain-connector-oak-bootstrap-aem65lts-1.0.0-SNAPSHOT.zip`

Expected effect:

- OSGi registration churn
- Oak repository services reconfigure
- repository-facing components may stop/start

Do not treat this as a hot, no-risk config change.

### 4. Restart AEM

Restart AEM immediately after the Oak bootstrap package install.

This is the supported path for stabilizing the repository after the composite/bootstrap PIDs are applied.

### 5. Verify

Check:

- `com.oakchain.connector.persistence.HttpPersistenceService`
- `com.oakchain.connector.wallet.SlingAuthorWalletService`
- `com.oakchain.connector.wallet.SlingAuthorRegistrationService`
- `com.oakchain.connector.wallet.SlingWriteProposalService`
- `com.oakchain.connector.wallet.SlingDeleteProposalService`
- `org.apache.jackrabbit.oak.segment.SegmentNodeStoreFactory~default`
- `org.apache.jackrabbit.oak.segment.SegmentNodeStoreFactory~oak-chain`
- `org.apache.jackrabbit.oak.composite.MountInfoProviderService`
- `org.apache.jackrabbit.oak.composite.CompositeNodeStoreService`
- `org.apache.jackrabbit.oak.segment.sync.HttpSegmentStoreSync`

Then verify:

- bundle is active
- validator reachability via `/journal.log`
- `/oak-chain` resolves after repository restart
- wallet/write services remain disabled unless intentionally enabled

## Rollback

If the Oak bootstrap package needs to be backed out, remove the persisted Oak-side ConfigAdmin files and restart AEM.

Paths observed on local 6.5 LTS:

- `crx-quickstart/launchpad/config/org/apache/jackrabbit/oak/composite/CompositeNodeStoreService.config`
- `crx-quickstart/launchpad/config/org/apache/jackrabbit/oak/composite/MountInfoProviderService.config`
- `crx-quickstart/launchpad/config/org/apache/jackrabbit/oak/segment/SegmentNodeStoreFactory%007edefault.config`
- `crx-quickstart/launchpad/config/org/apache/jackrabbit/oak/segment/SegmentNodeStoreFactory%007eoak-chain.config`
- `crx-quickstart/launchpad/config/org/apache/jackrabbit/oak/segment/sync/HttpSegmentStoreSync.config`

If the bootstrap nodes were also installed into the repository, remove these too before the restart:

- `/apps/oak-chain-connector/osgiconfig/config.author/org.apache.jackrabbit.oak.segment.SegmentNodeStoreFactory~default.cfg.json`
- `/apps/oak-chain-connector/osgiconfig/config.author/org.apache.jackrabbit.oak.segment.SegmentNodeStoreFactory~oak-chain.cfg.json`
- `/apps/oak-chain-connector/osgiconfig/config.author/org.apache.jackrabbit.oak.composite.MountInfoProviderService.cfg.json`
- `/apps/oak-chain-connector/osgiconfig/config.author/org.apache.jackrabbit.oak.composite.CompositeNodeStoreService.cfg.json`
- `/apps/oak-chain-connector/osgiconfig/config.author/org.apache.jackrabbit.oak.segment.sync.HttpSegmentStoreSync.cfg.json`

Do not remove the connector-owned configs unless you also intend to back out the connector itself:

- `crx-quickstart/launchpad/config/com/oakchain/connector/persistence/HttpPersistenceService.config`
- `crx-quickstart/launchpad/config/com/oakchain/connector/wallet/SlingAuthorWalletService.config`
- `crx-quickstart/launchpad/config/com/oakchain/connector/wallet/SlingAuthorRegistrationService.config`
- `crx-quickstart/launchpad/config/com/oakchain/connector/wallet/SlingWriteProposalService.config`
- `crx-quickstart/launchpad/config/com/oakchain/connector/wallet/SlingDeleteProposalService.config`

## Operational Stance

Default package:

- connector code
- connector-owned configs
- write plane kept present but disabled

Optional bootstrap package:

- Oak composite mount bootstrap
- maintenance-window install
- restart expected
