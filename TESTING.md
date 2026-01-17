# Testing Guide

## Overview

This document describes how to test the Oak Chain Connector on AEM instances.

## Prerequisites

- AEM 6.5.x or AEM as a Cloud Service instance
- Running oak-chain validator accessible via HTTP
- Maven 3.6+
- Java 11+

## Unit Tests

Run unit tests without AEM instance:

```bash
mvn test
```

## Bundle Activation Test (Manual)

### Step 1: Build and Deploy

```bash
mvn clean install -PautoInstallPackage \
  -Daem.host=localhost \
  -Daem.port=4502 \
  -Dvault.user=admin \
  -Dvault.password=admin
```

### Step 2: Verify Bundle Installation

1. Navigate to: `http://localhost:4502/system/console/bundles`
2. Search for: `com.oakchain.connector.core`
3. Verify status is **Active** (green checkmark)

Expected output:
```
Bundle Symbolic Name: com.oakchain.connector.core
Version: 1.0.0.SNAPSHOT
State: Active
```

### Step 3: Check OSGi Components

Navigate to: `http://localhost:4502/system/console/components`

Verify these components are **Active**:
- `com.oakchain.connector.persistence.HttpPersistenceService`
- `com.oakchain.connector.wallet.SlingAuthorWalletService`

### Step 4: Verify Package Imports

Check Import-Package resolution in bundle details:

Expected imports (all should be resolved):
- `org.apache.jackrabbit.oak.segment.spi`
- `org.apache.jackrabbit.oak.segment.spi.persistence`
- `org.apache.jackrabbit.oak.segment.remote`
- `org.apache.jackrabbit.oak.commons`

### Common Issues

**Bundle won't activate:**
- Check Oak version compatibility
- Verify Oak SPI packages are exported by AEM
- Review error.log for ClassNotFoundException

**Components won't activate:**
- Check OSGi configuration files installed correctly
- Verify configuration syntax in .cfg.json files
- Check service dependencies satisfied

## Integration Test - Validator Connection

### Prerequisites

Start a local oak-chain validator:

```bash
cd /path/to/blockchain-aem-infra
./scripts/local-development/local-dev.sh start validator-only
```

Verify validator running:

```bash
curl http://localhost:8090/journal.log
# Should return journal entries or empty response (not 404)
```

### Test Steps

#### 1. Configure HTTP Persistence Service

Via Felix Console (`/system/console/configMgr`):

```
Global Store URL: http://localhost:8090
Lazy Mount: true
Health Check Interval Seconds: 10
Connection Timeout Ms: 3000
```

Or via JCR:

```bash
curl -u admin:admin -F"globalStoreUrl=http://localhost:8090" \
  -F"lazyMount=true" \
  http://localhost:4502/system/console/configMgr/com.oakchain.connector.persistence.HttpPersistenceService
```

#### 2. Monitor Logs

Tail AEM logs:

```bash
tail -f crx-quickstart/logs/error.log | grep -i "oakchain\|HttpPersistence"
```

Expected log entries:
```
INFO  HttpPersistenceService - Activating HTTP Segment Persistence
INFO  HttpPersistenceService - Global Store URL: http://localhost:8090
INFO  HttpPersistenceService - Lazy Mount: true
INFO  HttpPersistence - Initialized HttpPersistence (HTTP/2) for: http://localhost:8090
INFO  HttpPersistenceService - Validator is now reachable at: http://localhost:8090
INFO  HttpPersistenceService - SegmentNodeStorePersistence registered - oak-chain mount will now initialize
```

#### 3. Verify Composite Mount

Check JCR for composite mount:

```bash
curl -u admin:admin http://localhost:4502/oak-chain.json
```

Expected: Either content from validator or 404 (if validator has no content yet)

NOT expected: Connection errors or AEM failing to start

#### 4. Test Segment Fetch

If validator has content, test segment access:

```bash
# Get validator's latest journal entry
REVISION=$(curl -s http://localhost:8090/journal.log | tail -1 | cut -d' ' -f1)

# Verify AEM can read it
curl -u admin:admin \
  "http://localhost:4502/oak-chain.json?jcr:primaryType=oak:Unstructured"
```

#### 5. Test Write Proposal (if wallet configured)

```bash
# Verify wallet service active
curl -u admin:admin http://localhost:4502/system/console/components \
  | grep SlingAuthorWalletService

# Check wallet address in logs
tail -f crx-quickstart/logs/error.log | grep "Wallet Address"
```

Expected:
```
INFO  SlingAuthorWalletService - Sling Author Wallet Service activated
INFO  SlingAuthorWalletService - Wallet Address: 0x...
```

## End-to-End Test Checklist

- [ ] Bundle installs without errors
- [ ] All OSGi components activate
- [ ] HTTP Persistence Service registers successfully
- [ ] Lazy mount health check connects to validator
- [ ] SegmentNodeStorePersistence registered in OSGi
- [ ] Composite mount created at `/oak-chain`
- [ ] Journal fetches succeed
- [ ] Segment fetches succeed (if validator has content)
- [ ] Wallet service activated (if enabled)
- [ ] No errors in error.log during operation

## Performance Testing

### HTTP/2 Verification

Check logs for HTTP/2 usage:

```bash
tail -f crx-quickstart/logs/error.log | grep "HTTP/2"
```

Expected:
```
INFO  Http2ClientPool - HTTP/2 Client Pool initialized
INFO  Http2ClientPool - Requests=100, HTTP/2=98 (98.0%), HTTP/1.1=2, BytesReceived=1048576
```

### Segment Fetch Latency

Monitor segment fetch times:

```bash
tail -f crx-quickstart/logs/error.log | grep "Fetched segment" | awk '{print $NF}'
```

Expected latency (local validator):
- < 50ms for local network
- < 200ms for remote validator
- HTTP/2 should show 20-30% improvement vs HTTP/1.1

## Troubleshooting

### Validator Not Reachable

```bash
# Check network connectivity
curl -v http://localhost:8090/journal.log

# Check Docker network (if using Docker)
docker network inspect bridge

# Verify validator logs
docker logs oak-global-store-1
```

### Segment Fetch Failures

```bash
# Enable DEBUG logging
curl -u admin:admin -X POST \
  -d "logger.level=DEBUG" \
  http://localhost:4502/system/console/slinglog/com.oakchain.connector

# Check specific segment exists on validator
curl http://localhost:8090/segments/[segment-uuid]
```

### Composite Mount Not Created

```bash
# Verify SegmentNodeStorePersistence service
curl -u admin:admin http://localhost:4502/system/console/services \
  | grep SegmentNodeStorePersistence

# Check Oak composite configuration
cat crx-quickstart/repository/composite-configuration.json
```

## Automated Testing (Future)

Future releases will include:
- Integration tests using AEM Mocks
- Docker-based integration test suite
- Performance benchmarking suite
- Chaos testing for validator failures
