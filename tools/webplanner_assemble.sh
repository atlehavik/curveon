#!/usr/bin/env bash
# Puts together what the web planner serves, in one folder (default build/webplanner):
#   web/            the page (webplanner/web) with the map libraries in web/vendor (pinned versions, checked by sha256)
#   web/map/        the app's map style, fonts and sprites, and the Norway map (norway.pmtiles from ~/develop/Curveon-data)
#   webplanner-server.jar
# tools/deploy-webplanner.sh copies it to the VPS. To try it on this PC:
#   SEGMENTS=~/develop/Curveon-data/brouter/segments4 WEB_DIR=build/webplanner/web MAP_DIR=build/webplanner/web/map \
#     PORT=8788 java -jar build/webplanner/webplanner-server.jar     # then http://localhost:8788/plan/?relay=localhost:8787
set -euo pipefail
cd "$(dirname "$0")/.."
OUT=${1:-build/webplanner}
DATA=${CURVEON_DATA:-$HOME/develop/Curveon-data}

# name  url  sha256 — maplibre-gl and pmtiles: BSD-3-Clause; qrcode-generator: MIT (THIRD_PARTY_LICENSES.md).
LIBS="
maplibre-gl.js  https://cdn.jsdelivr.net/npm/maplibre-gl@5.6.0/dist/maplibre-gl.js   07e6ca71a5b6d835a7c3803fb484f0a6e5f0dcbfa131407666edb9e9316564f9
maplibre-gl.css https://cdn.jsdelivr.net/npm/maplibre-gl@5.6.0/dist/maplibre-gl.css  792ac997dcf6ae6f643eb4e2dee4630c85e7056526bd8fb85ffe83c67d6c41b4
pmtiles.js      https://cdn.jsdelivr.net/npm/pmtiles@4.3.0/dist/pmtiles.js           afc49d216fd24c0a3c0ff3cd2e0c62d6cdaf062854c3dced778dcab168824f79
qrcode.js       https://cdn.jsdelivr.net/npm/qrcode-generator@1.4.4/qrcode.js        18ae399f81182bc9de916e9c77b195df20cc58d6f2d55a62b085a299f1bf1780
"

mkdir -p "$OUT/web/vendor" "$OUT/web/map"
rsync -a --delete --exclude vendor --exclude map --exclude test webplanner/web/ "$OUT/web/"

echo "$LIBS" | while read -r name url sum; do
  [ -z "$name" ] && continue
  f="$OUT/web/vendor/$name"
  if [ ! -f "$f" ] || ! echo "$sum  $f" | sha256sum -c --quiet - 2>/dev/null; then
    curl -fsSL "$url" -o "$f.part"
    echo "$sum  $f.part" | sha256sum -c --quiet - || { echo "checksum mismatch: $name" >&2; rm -f "$f.part"; exit 1; }
    mv "$f.part" "$f"
  fi
done

rsync -a --delete app/src/main/assets/map/styles app/src/main/assets/map/fonts app/src/main/assets/map/sprites "$OUT/web/map/"
# The map is 1.6 GB: linked here, copied to the VPS only when it changed (deploy-webplanner.sh).
ln -sfn "$DATA/norway.pmtiles" "$OUT/web/map/norway.pmtiles"

./gradlew -q :webplanner:serverJar
cp webplanner/build/libs/webplanner-server.jar "$OUT/"
echo "assembled in $OUT"
