#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
: "${GEYSER_JAR:?Set GEYSER_JAR to the tested Geyser-Spigot.jar}"
: "${PAPER_SERVER_JAR:?Set PAPER_SERVER_JAR to the tested remapped Paper server JAR}"
python3 scripts/check-mojang-hud-source.py
python3 scripts/generate-native-hud-aux.py
mkdir -p build/classes
find build/classes -type f -delete
find src/main/java -name '*.java' > build/sources.txt
javac --release 25 -cp "$GEYSER_JAR:$PAPER_SERVER_JAR:${EXTRA_CP:-}" -d build/classes @build/sources.txt
cp -a src/main/resources/. build/classes/
cp LICENSE build/classes/LICENSE
cp THIRD_PARTY_NOTICES.md build/classes/THIRD_PARTY_NOTICES.md
python3 - <<'PYZIP'
from pathlib import Path
import zipfile
with zipfile.ZipFile('build/classes/GeyserNativeOffhand.mcpack','w',zipfile.ZIP_DEFLATED) as pack:
    for path in sorted(Path('resource-pack').rglob('*')):
        if path.is_file():pack.write(path,str(path.relative_to('resource-pack')))
PYZIP
jar --create --file build/GeyserNativeOffhand-0.2.35-rewrite.35.jar -C build/classes .
echo "Built build/GeyserNativeOffhand-0.2.35-rewrite.35.jar"
