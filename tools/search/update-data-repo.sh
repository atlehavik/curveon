#!/usr/bin/env bash
# Pushes the search index and speed limit builders and the area → Geofabrik mapping to the public data repo
# (github.com/atlehavik/curveon-data), whose GitHub Action builds an index per country monthly.
# Run after changing build_search_index.py or the country catalog. Pass area ids (or "all") to
# also start a build right away, e.g.:  tools/search/update-data-repo.sh norway,sweden
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="${DATA_REPO:-$HERE/../../../curveon-data-repo}"
[ -d "$REPO/.git" ] || gh repo clone atlehavik/curveon-data "$REPO"
cp "$HERE/build_search_index.py" "$REPO/"
cp "$HERE/../speedlimits/build_speedlimits.py" "$REPO/"
python3 "$HERE/geofabrik_areas.py" "$HERE/../../app/src/main/assets/map/countries.json" "$REPO/areas.json"
cd "$REPO"
git add -A
git diff --cached --quiet || { git commit -qm "Update builders and areas from Curveon"; git push -q; }
[ -z "${1:-}" ] || gh workflow run search.yml -R atlehavik/curveon-data -f areas="$1"
