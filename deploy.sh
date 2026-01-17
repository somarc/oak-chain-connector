#!/bin/bash
#
# Oak Chain Connector - Deployment Script
# 
# Usage:
#   ./deploy.sh                    # Build and deploy to localhost:4502
#   ./deploy.sh -h HOST -p PORT    # Deploy to custom AEM instance
#   ./deploy.sh --bundle-only      # Deploy core bundle only (faster)
#

set -e

# Default values
AEM_HOST="${AEM_HOST:-localhost}"
AEM_PORT="${AEM_PORT:-4502}"
VAULT_USER="${VAULT_USER:-admin}"
VAULT_PASSWORD="${VAULT_PASSWORD:-admin}"
BUNDLE_ONLY=false

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
        --bundle-only)
            BUNDLE_ONLY=true
            shift
            ;;
        *)
            echo "Unknown option: $1"
            echo "Usage: $0 [-h HOST] [-p PORT] [-u USER] [--password PASSWORD] [--bundle-only]"
            exit 1
            ;;
    esac
done

echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "🚀 Oak Chain Connector - Deployment"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "Target: http://${AEM_HOST}:${AEM_PORT}"
echo "User: ${VAULT_USER}"

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
if [ "$BUNDLE_ONLY" = true ]; then
    echo "▶ Building and deploying core bundle only..."
    mvn clean install -PautoInstallBundle \
        -Daem.host="${AEM_HOST}" \
        -Daem.port="${AEM_PORT}" \
        -Dvault.user="${VAULT_USER}" \
        -Dvault.password="${VAULT_PASSWORD}" \
        -pl core
else
    echo "▶ Building and deploying complete package..."
    mvn clean install -PautoInstallPackage \
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
    echo "  2. Configure: http://${AEM_HOST}:${AEM_PORT}/system/console/configMgr"
    echo "  3. Check logs: http://${AEM_HOST}:${AEM_PORT}/system/console/slinglog"
    echo ""
else
    echo ""
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo "❌ Deployment failed!"
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    exit 1
fi
