# Oak Chain composite mount for AEM 6.5 LTS

A read-only, lazily connected view of an Oak Chain validator's segment store, mounted into
AEM's repository at `/oak-chain` through Oak's own `CompositeNodeStore`.

**No Oak bundle is modified.** AEM 6.5 LTS SP3 ships Apache Oak 2.4.0 byte-identical
(sha256 of `oak-segment-tar`, `oak-store-composite`, `oak-store-spi` and `oak-core` match
the Apache release), and this bundle builds against those exact artifacts.

## How it fits together

```
SlingRepositoryManager ──binds NodeStore──▶ CompositeNodeStore (stock oak-store-composite 2.4.0)
                                              ├─ global  = SegmentNodeStoreFactory~composite-global
                                              │            (AEM's existing TarMK store + FileDataStore)
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

## Lab: convert a stopped LTS quickstart

`lab/apply.sh <crx-quickstart> target/oak-chain-connector.mount-*.jar`, and `lab/revert.sh <crx-quickstart>` to undo.

- **Global store:** renames `repository/segmentstore` to `segmentstore-composite-global`, the directory `SegmentNodeStoreFactory` derives from its role.
- **Primary store:** makes `SegmentNodeStoreService` inert. It is overridden with `customSegmentStore=true` plus a `segmentStore.target` that matches nothing, so its registrar returns before opening any FileStore. The config stays present for AEM tools that look for it.
- **Oak configs:** writes the factory, `MountInfoProviderService` and `CompositeNodeStoreService` configs into Felix's persisted config before the first boot, so the composite never starts without its mount.
  - `CompositeNodeStoreService`: `enabled=false` is read by Package Manager (it fails with a NullPointerException when the key is missing; `true` restricts packages to content). `enableChecks=false` is set because the boot-time mount checks are a one-off snapshot and don't cover content that arrives later.
  - Global factory store: `dispatchChanges=true` and `registerDescriptors=true` keep JCR observation and Sling discovery working.
  - `MountInfoProviderService`: `pathsSupportingFragments=["/oak-chain"]` keeps per-mount index fragments in the writable global store.

## AEM 6.5 LTS API controller

LTS restricts which bundles may wire to some exports. `com.adobe.granite.apicontroller.FilterResolverHookFactory` maps each restricted exporter to the importer name patterns it accepts.
- `org.apache.jackrabbit.oak-segment-tar` and `com.adobe.granite.dropwizard.metrics` only accept `com.adobe.*`, `com.day.*`, `org.apache.jackrabbit.*` and `org.apache.sling.*`. Any third-party bundle that imports the Segment Tar SPI or `com.codahale.metrics` is refused at resolve time.
- This bundle therefore embeds both privately and imports nothing restricted.
- Renaming the bundle into an allowed namespace would get past the check, but it is the wrong fix.
- `lab/check-imports.py` verifies both rules offline against an AEM bundle cache.

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

Unit tests (`mvn test`) reproduce the Oak layer in-process: Apache Oak 2.4.0 `FileStore` served over the validator HTTP contract and mounted in a stock `CompositeNodeStore`.

## Repository restart triggers (static-greedy references in AEM 6.5 LTS)

| Trigger | Rebinds | Mitigation |
|---|---|---|
| A new `SegmentNodeStorePersistence` service | `SegmentNodeStoreService`, `SegmentNodeStoreFactory` | Never publish one. The lab also sets `segmentStore.target` on both to a role nothing has. |
| A new, higher-ranked `NodeStore` | `SlingRepositoryManager` | Never publish one. The composite is the only NodeStore. |
| A new unfiltered `BlobStore` | the global store | Never publish one. The mount's `HttpBlobStore` is private. |
| Mount provider unbind (bundle update/stop, config change) | `CompositeNodeStoreService` unregisters the composite | Treat like an Oak bundle: change it in a maintenance window |
| `MountInfoProviderService` config change | the composite (static) | Same |

## Known limits

- No JCR observation events for mount changes. The validator's `/v1/events/stream` is the natural source.
- No query indexing of mount content.
- External (IPFS) binaries under the mount throw when streamed. Inlined binaries work.
- A reader is pinned to one validator (logical, not physical, convergence).
- Unsupported topology for AEM 6.5 LTS on TarMK.
