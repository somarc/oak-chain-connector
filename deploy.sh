#!/bin/bash
#
# Oak Chain Connector - Deployment Script
# 
# Usage:
#   ./deploy.sh                              # Build and deploy to localhost:4502
#   ./deploy.sh -h HOST -p PORT              # Deploy to custom AEM instance
#   ./deploy.sh --bundle-only                # Deploy core bundle only (faster)
#   ./deploy.sh --profile aem65lts           # Exact AEM 6.5 LTS / 6.6 runtime package build/install
#   ./deploy.sh --oak-version 1.92.0         # Custom Oak branch build
#   ./deploy.sh --oak-version 1.92.0 --target oak-1.92.0
#

set -e

# Default values
AEM_HOST="${AEM_HOST:-localhost}"
AEM_PORT="${AEM_PORT:-4502}"
VAULT_USER="${VAULT_USER:-admin}"
VAULT_PASSWORD="${VAULT_PASSWORD:-admin}"
BUNDLE_ONLY=false
BUILD_PROFILE="${BUILD_PROFILE:-}"
OAK_VERSION="${OAK_VERSION:-}"
CONNECTOR_TARGET="${CONNECTOR_TARGET:-}"

# Parse arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        -h|--host)
            AEM_HOST="$2"
            shift 2
            ;;
        -p|--port)
            AEM_PORT="$2"
            shift 2
            ;;
        -u|--user)
            VAULT_USER="$2"
            shift 2
            ;;
        --password)
            VAULT_PASSWORD="$2"
            shift 2
            ;;
        --profile)
            BUILD_PROFILE="$2"
            shift 2
            ;;
        --oak-version)
            OAK_VERSION="$2"
            shift 2
            ;;
        --target)
            CONNECTOR_TARGET="$2"
            shift 2
            ;;
        --bundle-only)
            BUNDLE_ONLY=true
            shift
            ;;
        *)
            echo "Unknown option: $1"
            echo "Usage: $0 [-h HOST] [-p PORT] [-u USER] [--password PASSWORD] [--profile PROFILE] [--oak-version VERSION] [--target TARGET] [--bundle-only]"
            exit 1
            ;;
    esac
done

if [[ -n "${OAK_VERSION}" && -z "${CONNECTOR_TARGET}" ]]; then
    CONNECTOR_TARGET="oak-${OAK_VERSION}"
fi

echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "🚀 Oak Chain Connector - Deployment"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "Target: http://${AEM_HOST}:${AEM_PORT}"
echo "User: ${VAULT_USER}"
echo "Build Profile: ${BUILD_PROFILE:-default}"
if [[ -n "${OAK_VERSION}" ]]; then
    echo "Oak Version Override: ${OAK_VERSION}"
fi
if [[ -n "${CONNECTOR_TARGET}" ]]; then
    echo "Connector Target: ${CONNECTOR_TARGET}"
fi

# Check if AEM is reachable
echo ""
echo "▶ Checking AEM availability..."
if ! curl -s -u "${VAULT_USER}:${VAULT_PASSWORD}" \
     "http://${AEM_HOST}:${AEM_PORT}/system/console/bundles.json" > /dev/null; then
    echo "❌ Cannot reach AEM at http://${AEM_HOST}:${AEM_PORT}"
    echo "   Make sure AEM is running and credentials are correct"
    exit 1
fi
echo "✅ AEM is reachable"

# Build and deploy
echo ""
ACTIVE_PROFILES=()
if [[ -n "${BUILD_PROFILE}" ]]; then
    ACTIVE_PROFILES+=("${BUILD_PROFILE}")
fi

if [[ "${BUNDLE_ONLY}" = true ]]; then
    ACTIVE_PROFILES+=("autoInstallBundle")
else
    ACTIVE_PROFILES+=("autoInstallPackage")
fi

PROFILE_ARG=$(IFS=,; echo "${ACTIVE_PROFILES[*]}")
MAVEN_ARGS=(clean install "-P${PROFILE_ARG}")

if [[ -n "${OAK_VERSION}" ]]; then
    MAVEN_ARGS+=("-Doak.version=${OAK_VERSION}")
fi

if [[ -n "${CONNECTOR_TARGET}" ]]; then
    MAVEN_ARGS+=("-Dconnector.target=${CONNECTOR_TARGET}")
fi

if [ "$BUNDLE_ONLY" = true ]; then
    echo "▶ Building and deploying core bundle only..."
    mvn "${MAVEN_ARGS[@]}" \
        -Daem.host="${AEM_HOST}" \
        -Daem.port="${AEM_PORT}" \
        -Dvault.user="${VAULT_USER}" \
        -Dvault.password="${VAULT_PASSWORD}" \
        -pl core
else
    echo "▶ Building and deploying runtime package..."
    mvn "${MAVEN_ARGS[@]}" \
        -Daem.host="${AEM_HOST}" \
        -Daem.port="${AEM_PORT}" \
        -Dvault.user="${VAULT_USER}" \
        -Dvault.password="${VAULT_PASSWORD}"
fi

if [ $? -eq 0 ]; then
    echo ""
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo "✅ Deployment successful!"
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo ""
    echo "Next steps:"
    echo "  1. Verify bundle: http://${AEM_HOST}:${AEM_PORT}/system/console/bundles"
    echo "  2. Install connector config package from ui.config/target/"
    echo "  3. Optional: install Oak bootstrap package from ui.config.oak-bootstrap/target/ during a maintenance window, then restart AEM"
    echo "  4. Check logs: http://${AEM_HOST}:${AEM_PORT}/system/console/slinglog"
    echo ""
else
    echo ""
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo "❌ Deployment failed!"
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    exit 1
fi
