#!/usr/bin/env bash
# Rebuilds the country catalog for map downloads (app/src/main/assets/map/countries.json) from
# Natural Earth borders, sized against the current Protomaps build and brouter.de. Downloads
# ~360 MB once (the world build's tile index) into ../Curveon-data/countries. Needs python3.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
DATA="$HERE/../../../Curveon-data/countries"
mkdir -p "$DATA"
cd "$DATA"
NE=https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson
[ -f ne_50m_admin_0_countries.geojson ] || curl -sSLO "$NE/ne_50m_admin_0_countries.geojson"
[ -f ne_50m_admin_1_states_provinces.geojson ] || curl -sSLO "$NE/ne_50m_admin_1_states_provinces.geojson"

# Countries too big for one download come as states/provinces/federal districts.
python3 "$HERE/countries.py" ne_50m_admin_0_countries.geojson ne_50m_admin_1_states_provinces.geojson \
  "Russia,United States of America,Canada" areas.tsv

cd "$HERE/../.."
./gradlew :pmtiles:test --tests '*CountryCatalogTool*' -Dcurveon.catalog=true --rerun-tasks
