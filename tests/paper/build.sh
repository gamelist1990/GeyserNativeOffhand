#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
: "${EXTRA_CP:?Set EXTRA_CP to the tested Paper libraries}"
mkdir -p build/placement-test-hooks
find build/placement-test-hooks -type f -delete
javac --release 25 -cp "$EXTRA_CP" -d build/placement-test-hooks tests/paper/PlacementTestHooks.java
cp tests/paper/plugin.yml build/placement-test-hooks/
jar --create --file build/NativeOffhandPlacementTestHooks.jar -C build/placement-test-hooks .
