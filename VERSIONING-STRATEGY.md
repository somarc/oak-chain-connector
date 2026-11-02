# Oak Chain Connector - Versioning Strategy

## Core Rule

The connector must be compiled against the Oak line exported by the target AEM runtime or Oak branch. Treat `oak.version` as a deployment contract, not a loose library preference.

## Current Targets

As of 2026-03-26, the working assumptions in this repo are:

| Target | Oak Coordinate | How to Build | Notes |
|--------|----------------|--------------|-------|
| Public compatibility baseline | `1.78.0` | `mvn clean install` | Keeps local/public builds reproducible from Maven Central |
| AEM 6.5 LTS / 6.6 exact build | runtime `1.78.1.B002` / Maven `1.78.1-B002` | `mvn clean install -Paem65lts` | Requires Adobe artifact access |
| Other Oak branches | exact branch version | `mvn clean install -Doak.version=<version> -Dconnector.target=<label>` | Use for upstream/fork branch validation |

## Why This Exists

- The repo previously hard-pinned `1.40.0`, which is too old for the current AEM target line.
- AEM-targeted builds can require Adobe-patched Oak coordinates whose runtime and Maven strings differ, such as runtime `1.78.1.B002` vs Maven `1.78.1-B002`.
- Those patched coordinates are not guaranteed to exist on Maven Central, so public builds and exact AEM builds need different expectations.

## Packaging Strategy

The deployable ZIP name includes the chosen target:

- `oak-chain-connector-oak-1.78.0-1.0.0-SNAPSHOT.zip`
- `oak-chain-connector-aem65lts-1.0.0-SNAPSHOT.zip`
- `oak-chain-connector-oak-1.92.0-1.0.0-SNAPSHOT.zip`

This keeps artifacts from different Oak lines distinct without changing Maven coordinates.

## Exact AEM Builds

Use the exact runtime-aligned profile when building for AEM 6.5 LTS / 6.6:

```bash
mvn clean install -Paem65lts
```

That profile pins:

- `oak.version=1.78.1-B002`
- `connector.target=aem65lts`

## Custom Oak Branch Builds

For upstream or fork branch work, set the Oak coordinate explicitly:

```bash
mvn clean install -Doak.version=1.92.0 -Dconnector.target=oak-1.92.0
```

If you only override `oak.version`, the connector can still compile, but the ZIP name will be clearer if `connector.target` is set deliberately.

## AEMaaCS Guidance

Do not assume a generic `1.89.0` AEMaaCS target. Build against the exact Oak coordinate exported by the target SDK or runtime, which may be Adobe-patched and may not be present on Maven Central.

## Repository Access

- Public baseline builds resolve from Maven Central.
- Exact AEM builds may require Adobe Maven settings, VPN access, and trusted corporate certificates.

## Validation Status

- The current connector source compiles successfully against public Apache Oak `1.78.0`.
- The exact `aem65lts` profile packages successfully against Adobe Artifactory using Maven coordinate `1.78.1-B002` for runtime line `1.78.1.B002`.
