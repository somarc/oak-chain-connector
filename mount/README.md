# Oak Chain composite mount for AEM 6.5 LTS

A read-only, lazily connected view of an Oak Chain validator's segment store, mounted into
AEM's repository at `/oak-chain` through Oak's own `CompositeNodeStore`.

**No Oak bundle is modified.** AEM 6.5 LTS SP3 ships Apache Oak 2.4.0 byte-identical
(sha256 of `oak-segment-tar`, `oak-store-composite`, `oak-store-spi` and `oak-core` match
the Apache release), and this bundle builds against those exact artifacts.

This is Oak Chain's supported path for AEM 6.5 LTS. Operators start from
[AEM 6.5 LTS Integration](https://oakchain.net/guide/aem-integration); this README is the
developer reference behind it.

## How it fits together

```
SlingRepositoryManager ──binds NodeStore──▶ CompositeNodeStore (stock oak-store-composite 2.4.0)
                                              ├─ global  = SegmentNodeStoreFactory (role=composite-global)
                                              │            └─ TarPersistenceService ─▶ the existing repository/segmentstore
                                              │               (opened in place; FileDataStore unchanged)
                                              └─ /oak-chain (read-only) = NodeStoreProvider
                                                   role=composite-mount-oak-chain   ◀── this bundle
                                                   └─ LazyRemoteNodeStore
                                                        └─ ReadOnlyFileStore (Segment Tar 2.4.0, embedded privately)
                                                             └─ HttpPersistence ──GET──▶ validator
                                                                /journal.log /manifest /gc.log /segments/{uuid}
```

## Design rules (each one closes a recorded failure mode)

| Rule | Why |
|---|---|
| Register **only** a `NodeStoreProvider` with a role. Never a `SegmentNodeStorePersistence`, `BlobStore` or `NodeStore`. | `SegmentNodeStoreService` and `SegmentNodeStoreFactory` bind any `SegmentNodeStorePersistence` through an untargeted static-greedy reference, and `SlingRepositoryManager` binds `NodeStore` static-greedy. Publishing either restarts the repository at runtime. In AEM the only `NodeStoreProvider` consumers filter by role. |
| Embed Segment Tar privately (`-conditionalpackage`), including its `spi.*` packages. | Stock Oak exports only `segment.spi*`, but `SegmentArchiveReader.getGraph()` returns `segment.file.tar.SegmentGraph`, which is not exported (OAK-11808). A private copy keeps everything in one class loader. It also means our persistence implements a *private* SPI and cannot accidentally satisfy AEM's references. |
| Generate DS/metatype only for `com.oakchain.connector.mount.*`. | The embedded `org.apache.jackrabbit.oak.segment` package contains `@Component` classes. |
| "Lazy" lives inside the `NodeStore`; the provider registers once and stays. | If a mount provider unbinds, `CompositeNodeStoreService` unregisters the composite and the repository goes down. |
| Empty root until the validator answers; a background thread connects and follows the journal head. `getRoot()` never throws. | The composite calls the mount's `getRoot()` on every repository root read. |
| No checkpoints on the mount. | `CompositeNodeStore` checkpoints only the global store and reads read-only mounts at their current root. |
| Journal read newest-first. | Oak's `JournalReader` expects that; the validator serves the file oldest-first. |

## Opt-in flow

The bundle jar is also the opt-in tool. Java is already present wherever AEM runs, so nothing
else needs installing. One offline step and one restart, the same shape as any Oak storage
change:

```bash
JAR=oak-chain-connector.mount-0.1.0-SNAPSHOT.jar
AEM=/opt/aem/author                      # the directory that contains crx-quickstart

java -jar $JAR preflight $AEM --validator http://validator:8090   # read-only; safe while AEM runs
# stop AEM
java -jar $JAR enable    $AEM --validator http://validator:8090   # refuses unless AEM is stopped
# start AEM
OAKCHAIN_AEM_AUTH=admin:admin java -jar $JAR verify $AEM --aem-url http://localhost:4502
java -jar $JAR status    $AEM                                     # what is applied; detects drift

# opt out: stop AEM, then
java -jar $JAR disable   $AEM                                     # restores the originals byte for byte
# start AEM; verify with --expect vanilla
```

**preflight** (changes nothing) checks:
- the AEM build;
- that AEM is stopped (the TarMK `repo.lock` is free);
- that storage is TarMK, not MongoMK, standby or a custom segment store;
- that no foreign composite, mount or TAR-persistence configuration exists;
- that `oak-store-composite` and `TarPersistenceService` are installed;
- that every `Import-Package` of this bundle resolves against *this* instance's bundle cache and is allowed by the AEM 6.5 LTS API controller;
- the validator's manifest version, journal head and head segment. An unreachable validator is a warning, because the mount attaches lazily.

**enable** writes eight files and touches nothing under `repository/`:

| File (under `crx-quickstart/`) | Purpose |
|---|---|
| `launchpad/config/…/segment/SegmentNodeStoreService.config` | Primary store made inert (`customSegmentStore=true`, target matches nothing). The original is backed up; operator settings are kept. |
| `install/org.apache.jackrabbit.oak.segment.SegmentNodeStoreService.config` | The same override at installer priority 100, so the launchpad (55) original is never re-applied |
| `launchpad/config/…/segment/osgi/TarPersistenceService.config` | Opens the **existing** `repository/segmentstore` in place, `role=oakchain-global-tar` |
| `launchpad/config/…/segment/SegmentNodeStoreFactory%007eoakchain-global.config` | Global store: `role=composite-global`, bound to that persistence. Copies the primary's tuning; `dispatchChanges` and `registerDescriptors` on. |
| `launchpad/config/…/composite/MountInfoProviderService.config` | Read-only mount at the mount path |
| `launchpad/config/…/composite/CompositeNodeStoreService.config` | `enabled=false` (read by Package Manager), `enableChecks=false`. Package Manager's placeholder is backed up. |
| `launchpad/config/com/oakchain/connector/mount/OakChainMountProvider.config` | Validator URL and refresh interval; the optional token goes in a private property in a 0600 file |
| `install/15/oak-chain-connector.mount.jar` | This bundle |

Configs go straight into Felix's persisted store so the first boot cannot race the installer.
Every managed file carries an `oakchain.optin` marker and its SHA-256 is recorded in
`crx-quickstart/oak-chain-opt-in/state.properties`. Replaced files are copied to
`oak-chain-opt-in/backup/`.

- `enable` is idempotent: re-running it with new parameters updates in place and keeps the original backups.
- `disable` refuses if a managed file was edited since enable (override with `--force`).
- `verify` checks, for the current boot only:
  - AEM up and all bundles active;
  - the mount bundle active and the mount path served;
  - exactly one composite registration, i.e. no repository restart;
  - the mount connected and no resolution errors.
  - With `--write-probe` it also checks that a write under the mount is rejected while a global write and delete succeed.

Tokens and credentials never go on the command line: `--token-env VAR` and `--auth-env VAR`
name environment variables instead.

`lab/cycle.sh <vanilla-aem-home> <work-dir> <validator-url> [rounds] [port]` runs the whole
lifecycle repeatedly on a fresh copy:
preflight → enable → start → verify → stop → status → disable → start → verify (opted out) → stop.
`lab/relay.py` is a disposable TCP relay for outage tests.

## AEM 6.5 LTS API controller

LTS restricts which bundles may wire to some exports. `com.adobe.granite.apicontroller.FilterResolverHookFactory` maps each restricted exporter to the importer name patterns it accepts.
- `org.apache.jackrabbit.oak-segment-tar` and `com.adobe.granite.dropwizard.metrics` only accept `com.adobe.*`, `com.day.*`, `org.apache.jackrabbit.*` and `org.apache.sling.*`. Any third-party bundle that imports the Segment Tar SPI or `com.codahale.metrics` is refused at resolve time.
- This bundle therefore embeds both privately and imports nothing restricted.
- Renaming the bundle into an allowed namespace would get past the check, but it is the wrong fix.
- `preflight` checks both rules offline against the target instance's own bundle cache.

## Verified on AEM 6.5 LTS SP3 (6.6.3.20260819), 2026-11-01

Lab copy of a virgin instance, live 3-validator cluster, validator-0 on `:8090`:

| Check | Result |
|---|---|
| Boot on composite | `CompositeNodeStoreService` mounts `oak-chain` and registers the composite; the repository and all 591 bundles come up; 0 ERRORs in the final run |
| `/oak-chain` via Sling/JCR | Genesis tree readable (`/oak-chain/00/00/00/0x000…/content/genesis`) |
| External binary | `…/genesis/do-it-live.jpeg` served by AEM; its SHA-256 equals the content hash in its Oak blob id |
| Write under the mount | Rejected by Oak's `ReadOnlyBuilder` (stock composite); writes to `/content` succeed |
| Boot with validator down | Repository up immediately; `/oak-chain` 404 |
| Validator comes up | `/oak-chain` appears within one refresh; still one composite registration, one repository start |
| Validator outage after connect | Cached nodes stay readable; binaries and uncached segments fail; global reads and writes unaffected; recovers without a restart |
| Updating this bundle | Repository bounces once by design (provider unbind); AEM recovers; logs typical repository-restart noise |
| Opt-in lifecycle, 2 rounds (`lab/cycle.sh` on a fresh copy of a virgin instance) | 16/16 steps pass. Opted in: 591/591 bundles active, one composite registration, mount connected, 0 ERROR lines, mount write rejected, global write OK. Opted out: 590/590 active, bundle uninstalled, `/oak-chain` 404, primary TarMK back on `repository/segmentstore`, 0 ERROR lines. enable ≈1 s, start+verify ≈11 s |

Unit tests (`mvn test`) reproduce the Oak layer in-process: Apache Oak 2.4.0 `FileStore` served over the validator HTTP contract and mounted in a stock `CompositeNodeStore`.

## Repository restart triggers (static-greedy references in AEM 6.5 LTS)

| Trigger | Rebinds | Mitigation |
|---|---|---|
| A new `SegmentNodeStorePersistence` service | `SegmentNodeStoreService`, `SegmentNodeStoreFactory` | Never publish one. `enable` targets both references by role, so an unrelated persistence service cannot match. |
| A new, higher-ranked `NodeStore` | `SlingRepositoryManager` | Never publish one. The composite is the only NodeStore. |
| A new unfiltered `BlobStore` | the global store | Never publish one. The mount's `HttpBlobStore` is private. |
| Mount provider unbind (bundle update/stop, config change) | `CompositeNodeStoreService` unregisters the composite | Treat like an Oak bundle: change it in a maintenance window |
| `MountInfoProviderService` config change | the composite (static) | Same |

## Known limits

- No JCR observation events for mount changes. The validator's `/v1/events/stream` is the natural source.
- No query indexing of mount content.
- A binary the validator's binary GC has released answers 404 on `/api/blob`, so reading it through AEM fails.
- Validators run segment GC by default. The mount moves to the compacted head on its next refresh; a compaction while AEM is mounted has not been run yet.
- A reader is pinned to one validator (logical, not physical, convergence).
- Unsupported topology for AEM 6.5 LTS on TarMK.
