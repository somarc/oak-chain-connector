#!/usr/bin/env bash
# Convert a STOPPED AEM 6.5 LTS (Oak 2.4.0, crx3tar) quickstart to an Oak composite
# repository: global = the existing TarMK store, read-only mount /oak-chain = Oak Chain validator.
#
# No Oak bundle is modified. What changes:
#   - repository/segmentstore           -> repository/segmentstore-composite-global (rename)
#   - primary SegmentNodeStoreService   -> inert (customSegmentStore=true, target matches nothing)
#   - SegmentNodeStoreFactory~composite-global, MountInfoProviderService, CompositeNodeStoreService
#     written into Felix's persisted config (so the first boot cannot race the installer)
#   - install/: primary-store override (beats launchpad priority), mount bundle, mount config
#
# Usage: apply.sh <path-to-crx-quickstart> <mount-bundle.jar>
# Undo:  revert.sh <path-to-crx-quickstart>
set -euo pipefail

QS="${1:?crx-quickstart path}"
JAR="${2:?mount bundle jar}"
HERE="$(cd "$(dirname "$0")" && pwd)"
CFG="$QS/launchpad/config/org/apache/jackrabbit/oak"
BACKUP="$QS/../.oakchain-lab-backup"

[ -d "$QS/launchpad/config" ] || { echo "not a crx-quickstart: $QS" >&2; exit 1; }
PIDFILE="$QS/conf/cq.pid"
if [ -f "$PIDFILE" ] && kill -0 "$(cat "$PIDFILE")" 2>/dev/null; then
  echo "AEM appears to be running (pid $(cat "$PIDFILE")); stop it first" >&2; exit 1
fi
[ -e "$BACKUP" ] && { echo "backup already exists ($BACKUP); already applied?" >&2; exit 1; }

mkdir -p "$BACKUP/persisted"
cp -p "$CFG/segment/SegmentNodeStoreService.config" "$BACKUP/persisted/"
cp -p "$CFG/composite/CompositeNodeStoreService.config" "$BACKUP/persisted/" 2>/dev/null || true

# 1. Global store reuses the existing TarMK store under the factory's role-based directory name.
mv "$QS/repository/segmentstore" "$QS/repository/segmentstore-composite-global"

# 2. Persisted OSGi configs (read by ConfigAdmin before any bundle starts).
mkdir -p "$CFG/segment" "$CFG/composite"
cp "$HERE/persisted/SegmentNodeStoreService.config"                    "$CFG/segment/SegmentNodeStoreService.config"
cp "$HERE/persisted/SegmentNodeStoreFactory%007ecomposite-global.config" "$CFG/segment/"
cp "$HERE/persisted/MountInfoProviderService.config"                   "$CFG/composite/"
cp "$HERE/persisted/CompositeNodeStoreService.config"                  "$CFG/composite/"

# 3. Installer resources.
mkdir -p "$QS/install/15"
cp "$HERE/install/"*.config "$QS/install/"
cp "$JAR" "$QS/install/15/oak-chain-connector.mount.jar"

echo "applied; backup in $BACKUP"
