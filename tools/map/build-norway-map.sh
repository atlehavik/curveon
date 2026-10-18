#!/usr/bin/env bash
# Builds the offline Norway vector map (norway.pmtiles, ~1.7 GB) and regenerates the bundled
# MapLibre styles. Needs curl, python3 and node. Output goes to $OUT (default ../Curveon-data).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="${OUT:-$HERE/../../../Curveon-data}"
mkdir -p "$OUT"
cd "$OUT"

PMTILES_VERSION=1.31.2
if [ ! -x ./pmtiles ]; then
  curl -sSL "https://github.com/protomaps/go-pmtiles/releases/download/v${PMTILES_VERSION}/go-pmtiles_${PMTILES_VERSION}_Linux_x86_64.tar.gz" | tar xz pmtiles
fi

# Latest daily planet build from Protomaps
BUILD=$(curl -s https://build-metadata.protomaps.dev/builds.json | python3 -c "import json,sys;print(json.load(sys.stdin)[-1]['key'])")
echo "Extracting Norway from $BUILD"
./pmtiles extract "https://build.protomaps.com/$BUILD" norway.pmtiles \
  --region="$HERE/norway.geojson" --maxzoom=14 --download-threads=8

# Styles (dark/light) → app assets
cd "$HERE"
npm install --silent
for flavor in dark light; do
  node gen-style.mjs "$flavor"
  mv "style-$flavor.json" "$HERE/../../app/src/main/assets/map/styles/$flavor.json"
done

cat <<MSG
Done: $OUT/norway.pmtiles
Install on the tablet by copying it to
  Android/data/com.curveon.app/files/maps/
over USB, or with: adb push "$OUT/norway.pmtiles" /sdcard/Android/data/com.curveon.app/files/maps/
MSG
