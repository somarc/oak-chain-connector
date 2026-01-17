# Oak Chain Connector - Versioning Strategy

## Critical Discovery

The Oak Chain Connector **cannot be a single artifact** for all AEM versions due to breaking changes in Oak APIs across major versions.

**Root Cause**: 
- AEM 6.5.x uses Oak 1.22.x
- AEM 6.5 LTS uses Oak 1.78.x (tracking AEMaaCS)
- AEM as a Cloud Service uses Oak 1.89+ (close to trunk)
- Our development fork uses Oak 1.91-SNAPSHOT

The APIs we depend on (`oak-segment-remote`, SPI patterns) have changed significantly across these versions.

---

## Required Connector Flavors

### ❌ AEM 6.5.x Classic - NOT SUPPORTED

**Decision**: Do NOT support AEM 6.5 classic (pre-LTS)

**Rationale**:
- Oak 1.22 is too far removed from modern APIs
- Unknown unknowns in API compatibility
- Classic 6.5 is end-of-life path (customers should migrate to LTS)
- Testing/maintenance burden not justified
- Blockchain use cases demand modern, supported platforms

**Migration Path**: Customers must upgrade to AEM 6.5 LTS or AEMaaCS

---

### 1. AEM 6.5 LTS (Oak 1.78.x)

**Target**: AEM 6.5 LTS (March 2025 release, SP22+)

**Oak Version**: `1.78.x`

**Compatibility**:
- ✅ Works with: AEM 6.5 LTS (SP22+)
- ✅ Forward compatible with newer LTS patches
- ❌ Does not work with: Classic AEM 6.5 (not supported)

**Package Naming**: `oak-chain-connector-aem65lts-1.0.0.zip`

**Build Command**:
```bash
mvn clean install -Doak.version=1.78.0
```

**Testing Priority**: HIGH (enterprise LTS customers)

**Note**: LTS tracks AEMaaCS Oak version for convergence

---

### 2. AEM as a Cloud Service (Oak 1.89+)

**Target**: AEM as a Cloud Service (production)

**Oak Version**: `1.89.0` (AEMaaCS production baseline)

**Compatibility**:
- ✅ Works with: AEMaaCS production environments
- ✅ Compatible with: Cloud Manager deployments
- ⚠️ Requires: Bundle signing for production

**Package Naming**: `oak-chain-connector-aemaacs-1.0.0.zip`

**Build Command**:
```bash
mvn clean install -Doak.version=1.89.0
```

**Testing Priority**: HIGHEST (cloud-first strategy, future of AEM)

**Special Requirements**:
- Bundle must be signed
- Must pass Cloud Manager quality gates
- Requires approval for `org.apache.jackrabbit.oak.segment.spi.*` imports

---

### 3. Development/Head (Oak 1.91-SNAPSHOT)

**Target**: Development environments, testing, contributing back to Oak

**Oak Version**: `1.91-SNAPSHOT` (tracks our fork)

**Compatibility**:
- ✅ Works with: Custom Oak builds from our fork
- ✅ Compatible with: Latest Oak trunk features
- ❌ Does not work with: Any production AEM

**Package Naming**: `oak-chain-connector-dev-1.0.0-SNAPSHOT.zip`

**Build Command**:
```bash
mvn clean install -Doak.version=1.91-SNAPSHOT
```

**Testing Priority**: CONTINUOUS (our development baseline)

**Use Cases**:
- Testing new Oak features before they reach AEM
- Contributing changes back to Oak community
- Proof-of-concept deployments

---

## Implementation Plan

### Phase 1: Version Matrix (IMMEDIATE)

1. **Update parent POM** to support `oak.version` property override
2. **Create Maven profiles** for 3 target versions (LTS, AEMaaCS, Dev)
3. **Document version detection** - script to detect AEM/Oak version
4. **CI/CD matrix build** - Build all 3 flavors automatically

### Phase 2: API Compatibility Layer (FUTURE)

If API drift becomes unmanageable:

1. Create abstraction layer for Oak SPI differences
2. Use compile-time/runtime version detection
3. Ship single artifact with multi-version support
4. **Trade-off**: Increased complexity vs single artifact

**Decision**: Start with separate artifacts (simpler, clearer)

### Phase 3: Testing Matrix (CRITICAL)

Test matrix required:

| AEM Version | Oak Version | Connector Flavor | Test Status |
|-------------|-------------|------------------|-------------|
| 6.5 Classic | 1.22.x      | N/A              | ❌ NOT SUPPORTED |
| 6.5 LTS     | 1.78.x      | aem65lts         | ⏳ TODO |
| AEMaaCS     | 1.89.x      | aemaacs          | ⏳ TODO |
| Dev         | 1.91        | dev              | ✅ Working |

---

## Maven Profile Strategy

Add to parent `pom.xml`:

```xml
<profiles>
    <!-- AEM 6.5 LTS -->
    <profile>
        <id>aem65lts</id>
        <properties>
            <oak.version>1.78.0</oak.version>
            <connector.classifier>aem65lts</connector.classifier>
        </properties>
    </profile>
    
    <!-- AEM as a Cloud Service -->
    <profile>
        <id>aemaacs</id>
        <properties>
            <oak.version>1.89.0</oak.version>
            <connector.classifier>aemaacs</connector.classifier>
        </properties>
    </profile>
    
    <!-- Development (default) -->
    <profile>
        <id>dev</id>
        <activation>
            <activeByDefault>true</activeByDefault>
        </activation>
        <properties>
            <oak.version>1.91-SNAPSHOT</oak.version>
            <connector.classifier>dev</connector.classifier>
        </properties>
    </profile>
</profiles>
```

**Build Commands**:
```bash
# AEM 6.5 LTS
mvn clean install -Paem65lts

# AEM as a Cloud Service  
mvn clean install -Paemaacs

# Development (default)
mvn clean install -Pdev
```

---

## Distribution Strategy

### Option 1: Separate Releases (RECOMMENDED)

Release 3 separate ZIP files:
- `oak-chain-connector-aem65lts-1.0.0.zip`
- `oak-chain-connector-aemaacs-1.0.0.zip`
- `oak-chain-connector-dev-1.0.0-SNAPSHOT.zip`

**Pros**: Clear, simple, no version conflicts, modern platforms only
**Cons**: 3x build/test/release overhead (acceptable)

### Option 2: Unified Release with Classifiers

Single release with Maven classifiers:
- `oak-chain-connector-1.0.0-aem65lts.zip`
- `oak-chain-connector-1.0.0-aemaacs.zip`

**Pros**: Single version number, grouped releases
**Cons**: Requires Maven coordinates understanding

**Recommendation**: Option 1 for initial releases (3 flavors manageable)

---

## Documentation Updates Required

1. **README.md**: Add "Which Version Do I Need?" section
2. **INSTALLATION.md**: Version detection script
3. **TROUBLESHOOTING.md**: "Wrong Oak version" section
4. **COMPATIBILITY-MATRIX.md**: New document (detailed version table)

---

## Immediate Action Items

- [ ] Rebuild connector with `-Doak.version=1.78.0` (AEM 6.5 LTS)
- [ ] Test on AEM 6.5 LTS instance
- [ ] Rebuild connector with `-Doak.version=1.89.0` (AEMaaCS)
- [ ] Test on AEMaaCS SDK
- [ ] Document exact Oak version requirements
- [ ] Create version detection script
- [ ] Add Maven profiles for 3 flavors
- [ ] Set up CI matrix builds

---

## Long-Term Considerations

### When APIs Stabilize

If Oak SPI becomes truly stable across versions:
- Could return to single artifact approach
- Would need extensive compatibility testing
- Runtime version detection becomes feasible

### Contributing Back to Oak

Our learnings about API compatibility could help Oak:
- Document SPI stability guarantees
- Semantic versioning for SPIs
- Compatibility test suite

---

## Summary

**Current State**: Single connector built for Oak 1.40.0
**Problem**: Incompatible with production AEM versions
**Solution**: 3 flavors targeting modern, supported AEM platforms
**Supported Platforms**: AEM 6.5 LTS and AEMaaCS only
**Not Supported**: Classic AEM 6.5 (legacy, too much API drift)
**Next Step**: Rebuild for AEM 6.5 LTS (Oak 1.78.0) and AEMaaCS (Oak 1.89.0)

**Key Insight**: Oak Chain Connector is tied to Oak version, not AEM version. Focus on modern, supported platforms to minimize maintenance burden and unknown unknowns.
