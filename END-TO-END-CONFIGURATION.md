# End-to-End Connector Configuration Guide

This guide covers the full operator setup for mounting oak-chain into AEM through `oak-chain-connector`.

It separates three concerns that are easy to conflate:

| Concern | Required for read-only mount | Required for writes/deletes |
| --- | --- | --- |
| Connector bundle + HTTP persistence | Yes | Yes |
| AEM composite NodeStore configuration | Yes | Yes |
| Wallet + client registration | No | Yes |
| Shared validator auth token | Only if validator auth is enabled | Only if validator auth is enabled |

## Direct Answer

An AEM client does not need to register to read oak-chain in read-only mode.

Read-only mounting only depends on:

1. `com.oakchain.connector.persistence.HttpPersistenceService` being configured.
2. The validator being reachable for `GET /journal.log`, `GET /manifest`, `GET /gc.log`, and `GET|HEAD /segments/{uuid}`.
3. AEM composite mount services being configured so `/oak-chain` is actually mounted.
4. A matching `Authorization` token on the AEM side if the validator has token auth enabled.

Wallet registration is only needed before the validator will accept write/delete proposals.

## Runtime Model

### Read-only mount path

The connector activates `HttpPersistenceService`, checks validator reachability via `/journal.log`, and then registers `SegmentNodeStorePersistence`. Oak then mounts the remote store as a read-only composite mount under `/oak-chain`.

No wallet lookup or client registration happens in the read-only fetch path.

### Registration path

`SlingAuthorRegistrationService` is separate from the read-only persistence path. Its only purpose is to register a wallet-backed Sling client with the validator so the validator can authorize write/delete proposal flows.

The connector now prefers:

- `POST /v1/client/register`

and falls back to:

- `POST /v1/register-client`

for older validator builds.

### Write/delete path

Write and delete proposal services depend on:

1. a wallet being available,
2. the wallet being registered with the validator,
3. the validator accepting the proposal over HTTP,
4. the validator verifying the signature and on-chain payment identifiers.

## What Your Current Config Covers

The config you shared for `com.oakchain.connector.persistence.HttpPersistenceService` is only the connector-side read transport:

```json
{
  "globalStoreUrl": "http://localhost:8090",
  "lazyMount": true,
  "healthCheckIntervalSeconds": 10,
  "connectionTimeoutMs": 3000
}
```

That is necessary, but it is not sufficient by itself.

You still need the AEM composite NodeStore configuration that tells Oak:

- which NodeStore is the global/local repository,
- which NodeStore is the remote oak-chain mount,
- which path is mounted as `/oak-chain`,
- and that composite mode should be activated.

The default connector config package intentionally stops at connector-owned PIDs.

The surrounding Oak composite/bootstrap PIDs now live in a separate opt-in package or can be configured manually. That boundary is deliberate: those Oak-side configs reconfigure the primary repository and should be handled in a maintenance window with a planned restart, not auto-applied as part of the default connector install.

## Required Configuration for Read-only Mount

### 1. Build and install the correct connector package

For AEM 6.5 LTS / 6.6:

```bash
mvn -Paem65lts -DskipTests clean package
```

Build artifacts:

- `all/target/oak-chain-connector-aem65lts-1.0.0-SNAPSHOT.zip`
- `ui.config/target/oak-chain-connector-connector-config-aem65lts-1.0.0-SNAPSHOT.zip`
- `ui.config.oak-bootstrap/target/oak-chain-connector-oak-bootstrap-aem65lts-1.0.0-SNAPSHOT.zip`

Install order:

1. Install the runtime package from `all/target/`.
2. Install the connector-owned config package from `ui.config/target/`.
3. If you want the packaged Oak composite/bootstrap path, install the opt-in bootstrap package from `ui.config.oak-bootstrap/target/` during a maintenance window and restart AEM.

The full maintenance-window sequence is captured in [OFFLINE-OAK-BOOTSTRAP-RUNBOOK.md](./OFFLINE-OAK-BOOTSTRAP-RUNBOOK.md).

If you are upgrading from an older build where `ui.config` still included the Oak bootstrap PIDs, remove those legacy Oak-side config nodes first. Installing the new connector-owned config package does not remove them automatically. The cleanup list is also in [OFFLINE-OAK-BOOTSTRAP-RUNBOOK.md](./OFFLINE-OAK-BOOTSTRAP-RUNBOOK.md).

### 2. Configure the connector persistence service

Shipped config file:

- `ui.config/.../com.oakchain.connector.persistence.HttpPersistenceService.cfg.json`

Example:

```json
{
  "globalStoreUrl": "$[env:OAK_CHAIN_VALIDATOR_URL;default=http://localhost:8090]",
  "lazyMount": true,
  "healthCheckIntervalSeconds": 10,
  "connectionTimeoutMs": 3000
}
```

Recommended for read-only-only deployments:

- keep `lazyMount=true`
- keep wallet, registration, and write/delete services disabled unless you actually need the write plane

That read-only-first shape is now the default connector config package.

### 3. Configure validator auth only if the validator enables it

Validator-side auth is controlled by:

- JVM property: `oak.validator.auth.token`
- env var: `OAK_VALIDATOR_AUTH_TOKEN`

If the validator does not set either of those, auth is off and AEM does not need a token.

If the validator does set one, AEM must send the same token. On the AEM side the connector reads it from:

- JVM property: `oak.validator.auth.token`
- env var: `OAK_VALIDATOR_AUTH_TOKEN`

Example:

```bash
export OAK_VALIDATOR_AUTH_TOKEN="replace-me"
java -jar aem-quickstart.jar
```

This token is not an OSGi config property.

### 4. Ensure AEM has a global SegmentNodeStore role

The composite store expects one global/local NodeStore with role:

- `composite-global`

On a vanilla AEM install you may already have the default local SegmentNodeStore. Do not duplicate it blindly. Verify that the active local/global instance carries the `composite-global` role.

Use the separate `ui.config.oak-bootstrap` package if you want a packaged bootstrap for this PID. Otherwise, set it manually. In either case, treat it as a maintenance-window change because this PID touches the primary SegmentNodeStore role.

If you are managing configs as files, the shape is:

File:

- `org.apache.jackrabbit.oak.segment.SegmentNodeStoreFactory~default.cfg`

Example:

```properties
name="Default NodeStore"
role="composite-global"
repository.home="crx-quickstart/repository"
customBlobStore=B"false"
registerDescriptors=B"true"
```

The packaged default keeps this minimal and only sets the role/name so Oak can keep using the runtime/framework repository home where available.

### 5. Add the remote oak-chain SegmentNodeStore role

You need a second `SegmentNodeStoreFactory` factory config for the mounted store.

File:

- `org.apache.jackrabbit.oak.segment.SegmentNodeStoreFactory~oak-chain.cfg`

Example:

```properties
name="Oak Chain HTTP Mount"
role="composite-mount-oak-chain"
repository.home="crx-quickstart/http-segment-cache"
customBlobStore=B"false"
customSegmentStore=B"true"
registerDescriptors=B"false"
dispatchChanges=B"false"
```

Important points:

- `role="composite-mount-oak-chain"` must match the mount name suffix.
- `customSegmentStore=B"true"` is what makes Oak wait for the connector-provided `SegmentNodeStorePersistence`.
- `repository.home` is still needed as the local cache/work directory for the mount.

### 6. Define the `/oak-chain` mount

Use `MountInfoProviderService` to map the mount name to the path.

File:

- `org.apache.jackrabbit.oak.composite.MountInfoProviderService.cfg`

Example:

```properties
mountedPaths=["/oak-chain"]
mountName="oak-chain"
pathsSupportingFragments=["/oak:index/*$"]
readOnlyMount=B"true"
```

### 7. Activate the composite NodeStore service

The composite service in the current Oak code requires a config instance to exist. Older notes often mention `enabled=true`; the current code path does not define an `enabled` property, so the important part is that the config exists.

File:

- `org.apache.jackrabbit.oak.composite.CompositeNodeStoreService.cfg`

Example:

```properties
seedMount="oak-chain"
enableChecks=B"false"
pathStats=B"false"
```

For initial bring-up, `enableChecks=false` is pragmatic.

The working Sling Starter feature also carried:

```properties
enabled=B"true"
partialReadOnly=B"true"
```

Those properties are tolerated in the starter feature config, but the current `CompositeNodeStoreService` code in this workspace explicitly consumes `seedMount`, `enableChecks`, and `pathStats`.

### 8. Recommended: enable background sync for live refresh

Initial mounting and ongoing refresh are separate concerns.

Without background sync, the read-only mount can come up correctly and still fail to observe later validator revisions until restart. The working Sling Starter setup enabled `HttpSegmentStoreSync` for that reason.

File:

- `org.apache.jackrabbit.oak.segment.sync.HttpSegmentStoreSync.cfg`

Example:

```properties
globalStoreUrl="http://localhost:8090"
syncInterval=L"60"
enabled=B"true"
onlyFinalized=B"false"
scheduler.period=L"60"
scheduler.concurrent=B"false"
```

Notes:

- This service lives in `oak-segment-tar`, not in the connector bundle.
- It is recommended if you expect `/oak-chain` to track validator updates after startup.
- In the current upstream code, it polls `/journal.log` directly.
- If validator token auth is enabled, this sync service also needs matching auth-header support on the Oak side.

The optional `ui.config.oak-bootstrap` package ships this config too, but it only takes effect if the runtime Oak bundle actually includes `org.apache.jackrabbit.oak.segment.sync.HttpSegmentStoreSync`.

## Optional Configuration for Write/Delete Flows

These are not required for read-only access.

### Wallet service

PID:

- `com.oakchain.connector.wallet.SlingAuthorWalletService`

Shipped config file:

- `ui.config/.../com.oakchain.connector.wallet.SlingAuthorWalletService.cfg.json`

Example:

```json
{
  "enabled": false,
  "keystorePath": "$[env:OAK_CHAIN_KEYSTORE_PATH;default=]"
}
```

Notes:

- If `enabled=false`, read-only mount can still work.
- If `keystorePath` is blank, the service will generate/load a wallet under `sling.home`.
- In production, use an explicit keystore path backed by secret storage.

### Registration service

PID:

- `com.oakchain.connector.wallet.SlingAuthorRegistrationService`

Shipped config file:

- `ui.config/.../com.oakchain.connector.wallet.SlingAuthorRegistrationService.cfg.json`

Shipped config:

```json
{
  "enabled": false,
  "validatorUrl": "$[env:OAK_CHAIN_VALIDATOR_URL;default=http://localhost:8090]",
  "clientId": "$[env:HOSTNAME;default=]",
  "clientUrl": "$[env:SYSTEM_PUBLIC_URL;default=]"
}
```

Notes:

- The shipped default keeps this disabled.
- Set `enabled=true` when you intentionally turn on the write plane.
- `validatorUrl` can be omitted; the service will try to inherit it from `HttpPersistenceService`.
- Registration is required before the validator will accept write/delete proposals for that wallet.

### Write proposal service

PID:

- `com.oakchain.connector.wallet.SlingWriteProposalService`

Shipped config:

```json
{
  "enabled": false,
  "validatorUrl": "$[env:OAK_CHAIN_VALIDATOR_URL;default=http://localhost:8090]",
  "clientId": "$[env:HOSTNAME;default=]"
}
```

- The shipped default keeps this disabled.
- Set `enabled=true` when you intentionally turn on the write plane.

### Delete proposal service

PID:

- `com.oakchain.connector.wallet.SlingDeleteProposalService`

Shipped config:

```json
{
  "enabled": false,
  "validatorUrl": "$[env:OAK_CHAIN_VALIDATOR_URL;default=http://localhost:8090]",
  "clientId": "$[env:HOSTNAME;default=]"
}
```

- The shipped default keeps this disabled.
- Set `enabled=true` when you intentionally turn on the write plane.

## Verification Checklist

### Read-only verification

1. Bundle is `Active` in `/system/console/bundles`.
2. `HttpPersistenceService` is configured in `/system/console/configMgr`.
3. Validator responds to:
   - `GET /journal.log`
   - `GET /manifest`
   - `GET /gc.log`
   - `GET /segments/{uuid}`
4. AEM logs show:
   - `Activating HTTP Segment Persistence`
   - `Validator is now reachable`
   - `SegmentNodeStorePersistence registered`
5. `/oak-chain.json` resolves from AEM.
6. If `HttpSegmentStoreSync` is enabled, logs show periodic syncs and HEAD refreshes when validator revisions advance.

### If read-only mount fails

Check these in order:

1. Missing composite configs: the connector alone will not create the `/oak-chain` mount.
2. Validator token mismatch: a 401 on `/journal.log` means auth is enabled and AEM is not sending the right token.
3. Wrong Oak build target: the connector must match the Oak line exported by the AEM runtime.
4. Duplicate or misconfigured `SegmentNodeStoreFactory` roles.

### Write/delete verification

1. Wallet service is active and has a wallet address.
2. Registration service logs successful registration.
3. Validator shows the wallet as a registered client.
4. Write/delete proposals no longer fail with "wallet not registered".

## Recommended Deployment Modes

### Read-only-only AEM

Use this when AEM should consume oak-chain content but not author into it.

- `HttpPersistenceService`: enabled
- `HttpSegmentStoreSync`: recommended
- `SlingAuthorWalletService`: disabled
- registration service: disabled
- write/delete proposal services: disabled

### Read + wallet-backed write/delete

Use this when AEM authors should be able to submit signed proposals.

- `HttpPersistenceService`: enabled
- `HttpSegmentStoreSync`: recommended
- `SlingAuthorWalletService`: enabled
- registration service: enabled
- write/delete proposal services: enabled
- validator auth token: configured on both sides if auth is enabled

## Reference: Working Sling Starter Config

The working feature config in:

- `/Users/mhess/aem/aem-code/oak-upstream/sling-org-apache-sling-starter/src/main/features/oak/persistence/oak_persistence_blockchain.json`

used this shape:

- `org.apache.jackrabbit.oak.segment.SegmentNodeStoreFactory~default`
  - `role="composite-global"`
- `org.apache.jackrabbit.oak.segment.http.HttpPersistenceService`
  - `globalStoreUrl`, `lazyMount`, `healthCheckIntervalSeconds`, `connectionTimeoutMs`
- `org.apache.jackrabbit.oak.segment.SegmentNodeStoreFactory~oak-chain`
  - `role="composite-mount-oak-chain"`
  - `repository.home="launcher/http-segment-cache"`
  - `customSegmentStore=true`
- `org.apache.jackrabbit.oak.composite.MountInfoProviderService`
  - `mountedPaths=["/oak-chain"]`
  - `mountName="oak-chain"`
  - `readOnlyMount=true`
- `org.apache.jackrabbit.oak.composite.CompositeNodeStoreService`
  - `seedMount="oak-chain"`
  - `enabled=true`
  - `enableChecks=false`
  - `partialReadOnly=true`
- `org.apache.jackrabbit.oak.segment.sync.HttpSegmentStoreSync`
  - `globalStoreUrl`
  - `syncInterval=60`
  - `enabled=true`
  - `scheduler.period=60`

Mapping that to the connector world:

- starter `org.apache.jackrabbit.oak.segment.http.HttpPersistenceService`
  becomes connector `com.oakchain.connector.persistence.HttpPersistenceService`
- starter `OAK_GLOBAL_STORE_URL`
  becomes connector `OAK_CHAIN_VALIDATOR_URL`
- starter `launcher/...` paths
  become AEM `crx-quickstart/...` paths

## See Also

- [README.md](./README.md)
- [OSGI-CONFIGURATION.md](./OSGI-CONFIGURATION.md)
- [TESTING.md](./TESTING.md)
