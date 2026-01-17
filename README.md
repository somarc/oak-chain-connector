# Oak Chain Connector

AEM integration add-on for mounting the oak-chain distributed content network.

## Overview

This project enables existing Adobe Experience Manager (AEM) instances to mount and access the oak-chain distributed content repository via Oak's public SPI layer. It follows the Adobe AEM Project Archetype structure for seamless integration with AEM 6.5.x and AEM as a Cloud Service.

## Architecture

The connector uses Oak's public `SegmentNodeStorePersistence` SPI to provide HTTP-based access to remote oak-chain validators. This approach:

- **Uses only public APIs** - No Oak internals required
- **Matches proven patterns** - Same approach as `oak-segment-azure` and `oak-segment-aws`
- **Works with standard AEM** - No fork or modification needed

### Key Components

- **HttpPersistence**: Implements `SegmentNodeStorePersistence` for HTTP/2-based segment access
- **HttpSegmentArchiveManager**: Manages remote segment archives via HTTP
- **HttpSegmentArchiveReader**: Fetches individual segments on-demand
- **SlingAuthorWalletService**: Manages Ethereum wallet for write proposals
- **SlingAuthorRegistrationService**: Registers Sling author with validators

## Project Structure

```
oak-chain-connector/
├── core/                    # OSGi bundle with Java code
├── ui.apps/                 # Content package (/apps)
├── ui.config/               # OSGi configurations
└── all/                     # Complete deployable package
```

## Build

```bash
mvn clean install
```

The build creates:
- `core/target/oak-chain-connector.core-1.0.0-SNAPSHOT.jar` - OSGi bundle
- `all/target/oak-chain-connector.all-1.0.0-SNAPSHOT.zip` - Complete package

## Installation

### Via Package Manager

1. Upload `all/target/oak-chain-connector.all-1.0.0-SNAPSHOT.zip` to AEM Package Manager
2. Install the package
3. Configure the OSGi services (see Configuration below)

### Via Maven

```bash
# Install to local AEM Author instance
mvn clean install -PautoInstallPackage

# Install bundle only (faster for development)
mvn clean install -PautoInstallBundle -pl core
```

## Configuration

### HTTP Persistence Service

Configure at: `/system/console/configMgr/com.oakchain.connector.persistence.HttpPersistenceService`

**Properties:**
- `globalStoreUrl`: URL of oak-chain validator (e.g., `http://oak-global-store:8090`)
- `lazyMount`: Enable lazy mounting (waits for validator availability)
- `healthCheckIntervalSeconds`: Health check interval when lazy mount enabled
- `connectionTimeoutMs`: Connection timeout for health checks

**Environment Variables:**
- `OAK_CHAIN_VALIDATOR_URL`: Validator URL (overrides default)

### Sling Author Wallet Service

Configure at: `/system/console/configMgr/com.oakchain.connector.wallet.SlingAuthorWalletService`

**Properties:**
- `enabled`: Enable wallet functionality
- `keystorePath`: Path to wallet keystore file

**Environment Variables:**
- `OAK_CHAIN_KEYSTORE_PATH`: Keystore path (overrides default)

## AEM Compatibility

| AEM Version | Oak Version | Status |
|-------------|-------------|--------|
| AEM 6.5.x | 1.22+ | Compatible |
| AEM as a Cloud Service | 1.40+ | Compatible |

The connector uses stable Oak SPI interfaces that have been consistent since Oak 1.10.

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
5. **Wallet Integration**: Sling author uses secp256k1 wallet for write proposals

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

## Support

For issues and questions:
- GitHub Issues: https://github.com/oakchain/oak-chain-connector/issues
- Oak Chain Docs: https://docs.oakchain.io
