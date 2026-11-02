# Oak Chain Connector

AEM integration add-on for mounting the oak-chain distributed content network.

**Package**: `com.oakchain.connector.*`  
**Source**: Migrated from `oak-segment-http` with AEM-compatible package names

## Overview

This project enables existing Adobe Experience Manager (AEM) instances to mount and access the oak-chain distributed content repository via Oak's public SPI layer. It follows the Adobe AEM Project Archetype structure and uses profile-driven builds so the connector can be compiled against the Oak line exported by the target AEM runtime.

## Relationship to `oak-segment-http`

**This connector is a migration** of code from `oak-segment-http` (in the fork) with renamed packages:

- **Source**: `org.apache.jackrabbit.oak.segment.http.*` → **Target**: `com.oakchain.connector.*`
- **15 Java files migrated** (persistence layer + wallet services)
- **Why migrated**: AEM security restrictions prevent using Apache package names
- **Why both exist**: 
  - **Connector** (this): For AEM customers (AEM-compatible packages)
  - **`oak-segment-http`** (fork): For validators (cross-cluster reads, shard routing)

See [IMPLEMENTATION-COMPLETE.md](IMPLEMENTATION-COMPLETE.md) for migration details.

## Architecture

The connector uses Oak's public `SegmentNodeStorePersistence` SPI to provide HTTP-based access to remote oak-chain validators. This approach:

- **Uses only public APIs** - No Oak internals required
- **Matches proven patterns** - Same approach as `oak-segment-azure` and `oak-segment-aws`
- **Works with standard AEM** - No fork or modification needed

### Key Components

- **HttpPersistence**: Implements `SegmentNodeStorePersistence` for HTTP/2-based segment access
- **HttpSegmentArchiveManager**: Manages remote segment archives via HTTP
- **HttpSegmentArchiveReader**: Fetches individual segments on-demand
- **SlingAuthorWalletService**: Optional Ethereum wallet for future write/delete proposal flows
- **SlingAuthorRegistrationService**: Optional validator registration for future write/delete proposal flows

## Project Structure

```
oak-chain-connector/
├── core/                    # OSGi bundle with Java code
├── ui.apps/                 # Content package (/apps)
├── ui.config/               # Connector-owned OSGi configurations
├── ui.config.oak-bootstrap/ # Optional Oak composite/bootstrap configs
└── all/                     # Runtime deployable package
```

## Build

```bash
mvn clean install
```

Default builds compile against public Apache Oak `1.78.0`, which is the open-source compatibility baseline for the current connector code.

Exact AEM 6.5 LTS / 6.6 builds use the pinned Oak line:

```bash
mvn clean install -Paem65lts
```

That profile pins `oak.version=1.78.1-B002`.

For other Oak branches, override the version explicitly:

```bash
mvn clean install -Doak.version=1.92.0 -Dconnector.target=oak-1.92.0
```

The runtime package is emitted under `all/target/` as:
- `oak-chain-connector-<target>-1.0.0-SNAPSHOT.zip`

The direct-install config packages are emitted under:
- `ui.config/target/oak-chain-connector-connector-config-<target>-1.0.0-SNAPSHOT.zip`
- `ui.config.oak-bootstrap/target/oak-chain-connector-oak-bootstrap-<target>-1.0.0-SNAPSHOT.zip`

Examples:
- `oak-chain-connector-oak-1.78.0-1.0.0-SNAPSHOT.zip`
- `oak-chain-connector-aem65lts-1.0.0-SNAPSHOT.zip`

For AEM-patched builds, note the runtime-to-Maven version translation:
- AEM runtime may report `1.78.1.B002`
- Adobe Maven artifacts are published as `1.78.1-B002`

Exact AEM-patched versions require access to Adobe artifact repositories.

## Installation

### Via Package Manager

1. Install the runtime package from `all/target/`
2. Install the connector config package from `ui.config/target/`
3. If you want the Oak composite mount bootstrap path, install the opt-in bootstrap package from `ui.config.oak-bootstrap/target/` during a maintenance window and restart AEM

For the exact sequence, see **[OFFLINE-OAK-BOOTSTRAP-RUNBOOK.md](OFFLINE-OAK-BOOTSTRAP-RUNBOOK.md)**.

If you are upgrading from an earlier build where `ui.config` also carried the Oak bootstrap PIDs, remove those older Oak-side config nodes first. The new connector-only `ui.config` package does not delete legacy bootstrap nodes automatically. The explicit cleanup list is in [OFFLINE-OAK-BOOTSTRAP-RUNBOOK.md](OFFLINE-OAK-BOOTSTRAP-RUNBOOK.md).

### Via Maven

```bash
# Install the runtime package to local AEM Author using the exact AEM 6.5 LTS build
mvn clean install -Paem65lts -PautoInstallPackage

# Install bundle only (faster for development)
mvn clean install -PautoInstallBundle -pl core
```

`autoInstallPackage` installs the runtime package. Install the config packages separately per [OFFLINE-OAK-BOOTSTRAP-RUNBOOK.md](OFFLINE-OAK-BOOTSTRAP-RUNBOOK.md).

Or use the helper script:

```bash
./deploy.sh --profile aem65lts
```

## Configuration

The connector uses OSGi configuration with environment variable substitution.

For the full operator setup, including the AEM composite NodeStore configuration that sits around the connector, see **[END-TO-END-CONFIGURATION.md](END-TO-END-CONFIGURATION.md)**.

For connector-specific OSGi property details, see **[OSGI-CONFIGURATION.md](OSGI-CONFIGURATION.md)**.

For the enterprise offline/bootstrap sequence, see **[OFFLINE-OAK-BOOTSTRAP-RUNBOOK.md](OFFLINE-OAK-BOOTSTRAP-RUNBOOK.md)**.

Important distinction:

- Read-only mounting does not require client registration.
- Wallet registration is only required for write/delete proposal flows.
- The shipped `ui.config` package contains connector-owned read-plane defaults only.
- Oak composite/bootstrap configs now live in a separate `ui.config.oak-bootstrap` package.
- Wallet, registration, and write/delete proposal services ship disabled by default.
- Those defaults assume a local QuickStart-style setup and a validator at `http://localhost:8090`.

### Quick Configuration

**Set environment variables:**
```bash
export OAK_CHAIN_VALIDATOR_URL="http://localhost:8090"
export OAK_CHAIN_KEYSTORE_PATH="/path/to/wallet.properties"
```

Packaged local defaults:

- validator URL defaults to `http://localhost:8090`
- wallet service defaults to disabled
- registration and write/delete proposal services default to disabled
- Oak composite mount configs are not auto-applied by the default package

**Or configure via Web Console:** `/system/console/configMgr`

### HTTP Persistence Service

**PID**: `com.oakchain.connector.persistence.HttpPersistenceService`

**Key Properties:**
- `globalStoreUrl` - Validator URL (env: `OAK_CHAIN_VALIDATOR_URL`)
- `lazyMount` - Wait for validator availability (default: true in shipped config)
- `healthCheckIntervalSeconds` - Health check interval (default: 10)
- `connectionTimeoutMs` - Connection timeout (default: 3000)

### Sling Author Wallet Service

**PID**: `com.oakchain.connector.wallet.SlingAuthorWalletService`

**Key Properties:**
- `enabled` - Enable wallet functionality (default: false in shipped config)
- `keystorePath` - Wallet keystore path (env: `OAK_CHAIN_KEYSTORE_PATH`)

**See [OSGI-CONFIGURATION.md](OSGI-CONFIGURATION.md) for detailed configuration guide.**

## AEM Compatibility

| Target | Oak Version | Build Mode | Notes |
|--------|-------------|------------|-------|
| AEM 6.5 LTS / 6.6 | runtime `1.78.1.B002` / Maven `1.78.1-B002` | `-Paem65lts` | Exact AEM-targeted build |
| Public compatibility baseline | `1.78.0` | default | Reproducible local/public compile target |
| Other Oak branches | exact branch version | `-Doak.version=...` | Use `-Dconnector.target=...` for distinct ZIP naming |

For AEM as a Cloud Service, build against the exact Oak coordinate from the target SDK/runtime rather than assuming a generic `1.89.0`.

## Development

### Building Core Bundle

```bash
cd core
mvn clean install
```

### Running Tests

```bash
mvn test
```

### Code Style

The project follows standard Apache coding conventions:
- 4-space indentation
- Opening braces on same line
- Javadoc for public APIs

## How It Works

1. **Service Activation**: `HttpPersistenceService` registers as `SegmentNodeStorePersistence`
2. **Composite Mount**: Oak's composite store detects the new persistence layer
3. **Lazy Initialization**: Health checks ensure validator is available before mounting
4. **HTTP/2 Access**: Segments fetched on-demand via multiplexed HTTP/2 connections
5. **Optional Wallet Integration**: Sling author can use a secp256k1 wallet for write proposals when the write plane is explicitly enabled

## Troubleshooting

### Bundle Not Activating

Check OSGi console (`/system/console/bundles`) for:
- Missing dependencies (especially Oak SPI packages)
- Configuration errors
- Import-Package resolution failures

### Cannot Connect to Validator

1. Verify `globalStoreUrl` configuration
2. Check network connectivity to validator
3. Review logs at `/system/console/slinglog`
4. Confirm validator is running and accessible

### Permission Errors

Some AEM configurations may restrict package imports. If you see OSGi resolution errors:
1. Contact Adobe support to allowlist `org.apache.jackrabbit.oak.segment.spi.*`
2. Verify bundle is properly signed (required for AEMaaCS in some cases)

## License

Licensed under the Apache License, Version 2.0. See LICENSE file for details.

## Contributing

Contributions welcome! Please:
1. Fork the repository
2. Create a feature branch
3. Submit a pull request with tests

## Related Modules

| Module | Relationship |
|--------|--------------|
| `oak-segment-http` (fork) | **Source code** - Validators use this for cross-cluster operations |
| `oak-segment-consensus` | Validator server that this connector connects to |
| `oak-chain-sdk` | JavaScript/TypeScript SDK for non-AEM applications |

## Support

For issues and questions:
- GitHub Issues: https://github.com/oakchain/oak-chain-connector/issues
- Oak Chain Docs: https://docs.oakchain.io
