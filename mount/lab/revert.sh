#!/usr/bin/env bash
# Undo apply.sh on a STOPPED quickstart.
# Note: the mount bundle and its configs were installed by the Sling installer; removing the
# files from install/ uninstalls them on the next start.
set -euo pipefail

QS="${1:?crx-quickstart path}"
CFG="$QS/launchpad/config/org/apache/jackrabbit/oak"
BACKUP="$QS/../.oakchain-lab-backup"
[ -d "$BACKUP/persisted" ] || { echo "no backup at $BACKUP" >&2; exit 1; }

rm -f "$QS/install/15/oak-chain-connector.mount.jar" \
      "$QS/install/com.oakchain.connector.mount.OakChainMountProvider.config" \
      "$QS/install/org.apache.jackrabbit.oak.segment.SegmentNodeStoreService.config"
rm -f "$CFG/segment/SegmentNodeStoreFactory%007ecomposite-global.config" \
      "$CFG/composite/MountInfoProviderService.config"
cp -p "$BACKUP/persisted/SegmentNodeStoreService.config" "$CFG/segment/"
[ -f "$BACKUP/persisted/CompositeNodeStoreService.config" ] && cp -p "$BACKUP/persisted/CompositeNodeStoreService.config" "$CFG/composite/"
mv "$QS/repository/segmentstore-composite-global" "$QS/repository/segmentstore"
rm -rf "$BACKUP"
echo "reverted"
