# OSGi Configuration Guide

Complete guide to configuring the Oak Chain Connector for AEM.

## Overview

The connector uses AEM's standard OSGi configuration pattern with:

- **Config files**: `.cfg.json` files in `ui.config` module
- **Environment variable substitution**: `$[env:VAR_NAME;default=value]`
- **Runmode-specific configs**: `config.author` for author instances

## Configuration Files

### Location

All OSGi configurations are in:

```
ui.config/src/main/content/jcr_root/apps/oak-chain-connector/osgiconfig/config.author/
```

**Why `config.author`?**

The connector is designed for AEM Author instances that need to mount remote oak-chain content. Publish instances typically don't need this functionality.

### Available Configurations

1. `com.oakchain.connector.persistence.HttpPersistenceService.cfg.json`
2. `com.oakchain.connector.wallet.SlingAuthorWalletService.cfg.json`

---

## HttpPersistenceService

**Purpose**: Configures HTTP-based access to remote oak-chain validators.

**PID**: `com.oakchain.connector.persistence.HttpPersistenceService`

### Configuration File

```json
{
  "globalStoreUrl": "$[env:OAK_CHAIN_VALIDATOR_URL;default=http://localhost:8090]",
  "lazyMount": true,
  "healthCheckIntervalSeconds": 10,
  "connectionTimeoutMs": 3000
}
```

### Properties

#### globalStoreUrl (String)

**Description**: URL of the oak-chain validator to connect to.

**Format**: `http://hostname:port` or `https://hostname:port`

**Default**: `http://localhost:8090`

**Environment Variable**: `OAK_CHAIN_VALIDATOR_URL`

**Examples:**
```bash
# Local development
export OAK_CHAIN_VALIDATOR_URL="http://localhost:8090"

# Docker Compose
export OAK_CHAIN_VALIDATOR_URL="http://oak-global-store:8090"

# Kubernetes
export OAK_CHAIN_VALIDATOR_URL="http://validator.blockchain-aem.svc.cluster.local:8090"

# Public validator
export OAK_CHAIN_VALIDATOR_URL="https://oak-chain.io"
```

#### lazyMount (Boolean)

**Description**: Whether to mount the remote store lazily (wait for validator availability).

**Default**: `true`

**When to use:**
- `true`: AEM can start even if validator is unreachable (recommended)
- `false`: AEM startup fails if validator is unreachable (strict mode)

**Behavior with `lazyMount=true`:**
1. AEM starts normally
2. Connector periodically checks validator health
3. Once validator is available, mount activates
4. Content becomes accessible

**Behavior with `lazyMount=false`:**
1. AEM checks validator during startup
2. If validator unreachable, connector activation fails
3. AEM may fail to start or run without oak-chain mount

#### healthCheckIntervalSeconds (Integer)

**Description**: Interval (in seconds) between validator health checks when lazy mounting.

**Default**: `10`

**Range**: `1` to `3600` (1 second to 1 hour)

**Only applies when `lazyMount=true`**.

**Tuning:**
- **Fast**: `5` seconds - Quick detection, more network overhead
- **Balanced**: `10` seconds - Default
- **Slow**: `30-60` seconds - Less overhead, slower detection

#### connectionTimeoutMs (Integer)

**Description**: Timeout (in milliseconds) for HTTP connections to validator.

**Default**: `3000` (3 seconds)

**Range**: `100` to `60000` (100ms to 60 seconds)

**Tuning:**
- **Local**: `1000-3000ms` - Fast local network
- **Cloud**: `5000-10000ms` - Cross-region latency
- **Unstable**: `10000-30000ms` - High latency or packet loss

---

## SlingAuthorWalletService

**Purpose**: Manages Ethereum wallet for write proposals and registration.

**PID**: `com.oakchain.connector.wallet.SlingAuthorWalletService`

### Configuration File

```json
{
  "enabled": true,
  "keystorePath": "$[env:OAK_CHAIN_KEYSTORE_PATH;default=]"
}
```

### Properties

#### enabled (Boolean)

**Description**: Whether to enable wallet functionality.

**Default**: `true`

**When to disable:**
- Read-only AEM instances (no write proposals)
- Testing without wallet
- Troubleshooting mount issues

**Impact when disabled:**
- ✅ Can still mount and read oak-chain content
- ❌ Cannot propose writes
- ❌ Cannot register with validators

#### keystorePath (String)

**Description**: Filesystem path to the wallet keystore file.

**Default**: Empty (no keystore)

**Environment Variable**: `OAK_CHAIN_KEYSTORE_PATH`

**Format**: Absolute filesystem path

**Examples:**
```bash
# Local development
export OAK_CHAIN_KEYSTORE_PATH="/Users/you/oak-chain/keystore/author-wallet.properties"

# AEM QuickStart directory
export OAK_CHAIN_KEYSTORE_PATH="crx-quickstart/oak-chain/author-wallet.properties"

# Kubernetes (via volume mount)
export OAK_CHAIN_KEYSTORE_PATH="/opt/aem/secrets/oak-chain-wallet.properties"
```

**Keystore Format:**

```properties
# author-wallet.properties
walletAddress=0x742d35Cc6634C0532925a3b844Bc9e7595f0bEb0
privateKey=0x1234567890abcdef... (64 hex chars)
```

**Security Warning:**

⚠️ The keystore contains a private key. Protect this file:
- Set restrictive permissions: `chmod 600 author-wallet.properties`
- Never commit to version control
- Use secrets management in production (Vault, AWS Secrets Manager)

---

## Environment Variable Substitution

AEM supports environment variable substitution in OSGi configs:

### Syntax

```json
{
  "property": "$[env:VAR_NAME;default=fallback_value]"
}
```

- `env:VAR_NAME`: Environment variable to read
- `default=value`: Fallback if variable not set

### Examples

**Simple substitution:**
```json
{
  "globalStoreUrl": "$[env:OAK_CHAIN_VALIDATOR_URL]"
}
```

**With default:**
```json
{
  "globalStoreUrl": "$[env:OAK_CHAIN_VALIDATOR_URL;default=http://localhost:8090]"
}
```

**Multiple substitutions:**
```json
{
  "globalStoreUrl": "$[env:OAK_CHAIN_VALIDATOR_URL;default=http://localhost:8090]",
  "keystorePath": "$[env:OAK_CHAIN_KEYSTORE_PATH;default=]",
  "enabled": "$[env:OAK_CHAIN_WALLET_ENABLED;default=true]"
}
```

### Setting Environment Variables

**Start script:**
```bash
export OAK_CHAIN_VALIDATOR_URL="http://oak-validator:8090"
export OAK_CHAIN_KEYSTORE_PATH="/opt/aem/secrets/wallet.properties"

java -jar aem-quickstart.jar
```

**Docker:**
```dockerfile
ENV OAK_CHAIN_VALIDATOR_URL=http://oak-validator:8090
ENV OAK_CHAIN_KEYSTORE_PATH=/opt/aem/secrets/wallet.properties
```

**Kubernetes:**
```yaml
env:
  - name: OAK_CHAIN_VALIDATOR_URL
    value: http://validator.blockchain-aem.svc.cluster.local:8090
  - name: OAK_CHAIN_KEYSTORE_PATH
    value: /opt/aem/secrets/wallet.properties
```

---

## Configuration Scenarios

### Local Development

**Scenario**: Testing against local oak-chain validator

**Environment:**
```bash
export OAK_CHAIN_VALIDATOR_URL="http://localhost:8090"
export OAK_CHAIN_KEYSTORE_PATH="$HOME/oak-chain/keystore/author-wallet.properties"
```

**Config:**
- Uses defaults in `.cfg.json` files
- Environment variables override if set
- Fast iteration, easy debugging

### Docker Compose

**Scenario**: AEM + validators in Docker network

**docker-compose.yml:**
```yaml
services:
  aem-author:
    image: aem:6.5
    environment:
      - OAK_CHAIN_VALIDATOR_URL=http://oak-global-store:8090
      - OAK_CHAIN_KEYSTORE_PATH=/opt/aem/oak-chain/wallet.properties
    volumes:
      - ./secrets/wallet.properties:/opt/aem/oak-chain/wallet.properties:ro
```

### Kubernetes

**Scenario**: Production AEM in Kubernetes

**ConfigMap:**
```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: aem-oak-chain-config
data:
  OAK_CHAIN_VALIDATOR_URL: "http://validator.blockchain-aem.svc.cluster.local:8090"
```

**Secret:**
```yaml
apiVersion: v1
kind: Secret
metadata:
  name: aem-oak-chain-wallet
type: Opaque
stringData:
  wallet.properties: |
    walletAddress=0x742d35Cc6634C0532925a3b844Bc9e7595f0bEb0
    privateKey=0x...
```

**Deployment:**
```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: aem-author
spec:
  template:
    spec:
      containers:
        - name: aem
          envFrom:
            - configMapRef:
                name: aem-oak-chain-config
          env:
            - name: OAK_CHAIN_KEYSTORE_PATH
              value: /opt/aem/secrets/wallet.properties
          volumeMounts:
            - name: wallet
              mountPath: /opt/aem/secrets
              readOnly: true
      volumes:
        - name: wallet
          secret:
            secretName: aem-oak-chain-wallet
            defaultMode: 0600
```

### AEM as a Cloud Service

**Scenario**: AEMaaCS deployment

**Cloud Manager Environment Variables:**

Set in Cloud Manager UI:
- `OAK_CHAIN_VALIDATOR_URL` = `https://oak-chain.io`
- `OAK_CHAIN_KEYSTORE_PATH` = `/mnt/secrets/oak-chain-wallet.properties`

**Secret Management:**

Use Adobe's secret management:
```bash
# Upload wallet via aio CLI
aio cloudmanager:set-secret OAK_CHAIN_WALLET /path/to/wallet.properties
```

---

## Advanced Configuration

### Multiple Validators (Load Balancing)

The connector currently connects to a single validator URL. For high availability:

**Option 1: Load Balancer**
```bash
export OAK_CHAIN_VALIDATOR_URL="https://validators.oak-chain.io"
# Load balancer distributes to multiple validators
```

**Option 2: Kubernetes Service**
```bash
export OAK_CHAIN_VALIDATOR_URL="http://validator.blockchain-aem.svc.cluster.local:8090"
# K8s service load-balances across pods
```

### Read-Only Mode

To mount oak-chain content without wallet (read-only):

```json
{
  "globalStoreUrl": "$[env:OAK_CHAIN_VALIDATOR_URL;default=http://localhost:8090]",
  "lazyMount": true,
  "healthCheckIntervalSeconds": 10,
  "connectionTimeoutMs": 3000
}
```

```json
{
  "enabled": false,
  "keystorePath": ""
}
```

### Custom Health Check Intervals

For unreliable networks:

```json
{
  "globalStoreUrl": "$[env:OAK_CHAIN_VALIDATOR_URL;default=http://localhost:8090]",
  "lazyMount": true,
  "healthCheckIntervalSeconds": 30,
  "connectionTimeoutMs": 10000
}
```

---

## Troubleshooting

### Connector Bundle Not Active

**Check in Web Console**: `/system/console/bundles`

**Symptoms:**
- Bundle shows "Installed" not "Active"
- "Unsatisfied References" in bundle details

**Solutions:**

1. Check dependencies:
   ```
   Required Oak bundles:
   - org.apache.jackrabbit.oak-segment-remote
   - org.apache.jackrabbit.oak-commons
   ```

2. Check OSGi configuration:
   - `/system/console/configMgr`
   - Search for "HttpPersistenceService"
   - Verify configuration present

### Cannot Connect to Validator

**Symptoms:**
- Logs show "Connection refused" or "Timeout"
- Health checks fail

**Solutions:**

1. Verify validator URL:
   ```bash
   curl http://localhost:8090/health
   ```

2. Check environment variable:
   ```bash
   # In AEM logs, look for:
   grep "OAK_CHAIN_VALIDATOR_URL" error.log
   ```

3. Test connectivity from AEM:
   ```bash
   # If AEM in Docker:
   docker exec aem-author curl -v http://oak-global-store:8090/health
   ```

### Wallet Not Loading

**Symptoms:**
- Logs show "Keystore not found"
- Write proposals fail

**Solutions:**

1. Verify keystore path:
   ```bash
   ls -la $OAK_CHAIN_KEYSTORE_PATH
   ```

2. Check file permissions:
   ```bash
   chmod 600 /path/to/wallet.properties
   ```

3. Verify keystore format:
   ```properties
   walletAddress=0x...
   privateKey=0x...
   ```

### Environment Variables Not Applied

**Symptom:** Configuration uses default values, not environment variables

**Solutions:**

1. Check AEM start script exports variables
2. Verify syntax: `$[env:VAR_NAME;default=value]`
3. Restart AEM (env vars loaded at startup)
4. Check AEM logs for substitution errors

---

## Configuration Best Practices

### Development

```json
{
  "globalStoreUrl": "$[env:OAK_CHAIN_VALIDATOR_URL;default=http://localhost:8090]",
  "lazyMount": true,
  "healthCheckIntervalSeconds": 5,
  "connectionTimeoutMs": 3000
}
```

**Why:**
- Default points to local validator
- Lazy mount allows flexible startup order
- Fast health checks (5s) for quick feedback
- Short timeout (3s) for local network

### Staging/QA

```json
{
  "globalStoreUrl": "$[env:OAK_CHAIN_VALIDATOR_URL;default=http://staging-validator:8090]",
  "lazyMount": true,
  "healthCheckIntervalSeconds": 15,
  "connectionTimeoutMs": 5000
}
```

**Why:**
- Points to staging validator
- Lazy mount for resilience
- Moderate health checks (15s)
- Longer timeout (5s) for cloud latency

### Production

```json
{
  "globalStoreUrl": "$[env:OAK_CHAIN_VALIDATOR_URL;default=https://oak-chain.io]",
  "lazyMount": true,
  "healthCheckIntervalSeconds": 30,
  "connectionTimeoutMs": 10000
}
```

**Why:**
- HTTPS required in production
- Lazy mount for high availability
- Conservative health checks (30s) to reduce overhead
- Long timeout (10s) for reliability

---

## Security Considerations

### Wallet Keystore

**DO:**
- ✅ Store in secure location outside web root
- ✅ Set restrictive permissions (600)
- ✅ Use secrets management (Vault, AWS Secrets Manager)
- ✅ Rotate keys periodically

**DON'T:**
- ❌ Commit to version control
- ❌ Store in `/apps` or `/content`
- ❌ Use world-readable permissions
- ❌ Hardcode in config files

### Environment Variables

**DO:**
- ✅ Use environment variable substitution
- ✅ Document required variables
- ✅ Provide sensible defaults

**DON'T:**
- ❌ Hardcode sensitive data in .cfg.json
- ❌ Expose private keys in logs
- ❌ Use HTTP for production validators

### Network Security

**Production checklist:**
- [ ] Use HTTPS for validator connections
- [ ] Implement mTLS for validator authentication
- [ ] Restrict AEM → validator traffic (firewall rules)
- [ ] Monitor for failed connection attempts
- [ ] Rate limit validator API calls

---

## Monitoring

### Key Metrics to Monitor

**HttpPersistenceService:**
- Connection success/failure rate
- Health check response times
- Mount activation time
- Segment fetch latency

**SlingAuthorWalletService:**
- Wallet activation success
- Write proposal submissions
- Signature generation time

### Log Messages

**Successful activation:**
```
INFO com.oakchain.connector.persistence.HttpPersistenceService - Activated with validator URL: http://localhost:8090
INFO com.oakchain.connector.wallet.SlingAuthorWalletService - Wallet loaded: 0x742d35...
```

**Health check (lazy mount):**
```
DEBUG com.oakchain.connector.persistence.HttpPersistenceService - Health check passed
```

**Connection failure:**
```
WARN com.oakchain.connector.persistence.HttpPersistenceService - Health check failed: Connection refused
```

### Felix Web Console

Check status at: `/system/console/components`

Search for:
- `HttpPersistenceService`
- `SlingAuthorWalletService`

**Healthy state:**
- Status: "Active"
- "Satisfied" references
- No error messages

---

## Migration from Development to Production

### Development Config

```json
{
  "globalStoreUrl": "$[env:OAK_CHAIN_VALIDATOR_URL;default=http://localhost:8090]",
  "lazyMount": true,
  "healthCheckIntervalSeconds": 5,
  "connectionTimeoutMs": 3000
}
```

### Production Config

Changes needed:

1. **Update validator URL**:
   ```bash
   OAK_CHAIN_VALIDATOR_URL="https://oak-chain.io"
   ```

2. **Increase timeouts**:
   ```json
   {
     "healthCheckIntervalSeconds": 30,
     "connectionTimeoutMs": 10000
   }
   ```

3. **Secure wallet**:
   - Move keystore to secrets management
   - Update `OAK_CHAIN_KEYSTORE_PATH`

4. **Test thoroughly**:
   - Verify connectivity
   - Test write proposals
   - Monitor for 24 hours

---

## See Also

- [README.md](README.md) - Project overview
- [TESTING.md](TESTING.md) - Testing guide
- [VERSIONING-STRATEGY.md](VERSIONING-STRATEGY.md) - AEM/Oak version compatibility
- [AEM OSGi Configuration](https://experienceleague.adobe.com/docs/experience-manager-cloud-service/implementing/deploying/configuring-osgi.html) - Adobe documentation
