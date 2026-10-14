#!/usr/bin/env bash
#
# Re-vendor the native AIS-catcher C++ sources into this repo.
#
# porttracker-ais is self-contained: the native sources
# (AIS-catcher, libusb, rtl-sdr) live under app/src/main/jni/ and are
# committed, so the project builds without any sibling checkout. This script
# refreshes that vendored copy from an upstream AIS-catcher-for-Android tree
# (with submodules initialised).
#
# Usage:
#   scripts/vendor-native.sh [PATH_TO_AIS-catcher-for-Android]
#
# Default upstream path: ../AIS-catcher-for-Android (next to this repo).
# Prepare upstream first:
#   git clone --recurse-submodules https://github.com/jvde-github/AIS-catcher-for-Android.git
#
# After running, rebuild to verify:  ./gradlew assembleSideloadDebug
#
# NOTE: CMakeLists.txt lists the exact source files compiled; if upstream adds
# or renames files, update app/src/main/jni/CMakeLists.txt accordingly.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DST="$SCRIPT_DIR/../app/src/main/jni"
UPSTREAM="${1:-$SCRIPT_DIR/../../AIS-catcher-for-Android}"
SRC="$UPSTREAM/app/src/main/jni"

if [ ! -d "$SRC" ]; then
  echo "ERROR: upstream sources not found at: $SRC" >&2
  echo "Clone with: git clone --recurse-submodules https://github.com/jvde-github/AIS-catcher-for-Android.git" >&2
  exit 1
fi

# Subtrees referenced by CMakeLists.txt. CMakeLists.txt itself is NOT overwritten
# (it is maintained in this repo).
SUBTREES=(libusb rtl-sdr AIS-catcher JNI)

echo "Vendoring native sources: $SRC -> $DST"
for d in "${SUBTREES[@]}"; do
  if [ ! -e "$SRC/$d" ]; then
    echo "  WARNING: $d missing upstream (submodule not initialised?)" >&2
    continue
  fi
  rm -rf "${DST:?}/$d"
  rsync -a --exclude='.git' "$SRC/$d" "$DST/"
  echo "  vendored $d"
done

echo "Done. Verify with: ./gradlew assembleSideloadDebug"
