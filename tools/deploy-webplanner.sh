#!/usr/bin/env bash
# Puts the web planner on the relay VPS (ssh host curveon-vps, see ~/.curveon-vps/README.md), next to the relay:
#   /opt/curveon-planner/webplanner-server.jar   routing service (systemd curveon-planner, 127.0.0.1:8788)
#   /opt/curveon-planner/segments/               BRouter segments for Europe (fetched by the VPS from brouter.de)
#   /opt/curveon-planner/plan/                   the page and the map, served by Caddy at https://relay.curveon.no/plan/
# Without --apply it only shows what it would do. The map (1.6 GB) and segments are copied only when they changed.
set -euo pipefail
cd "$(dirname "$0")/.."
APPLY=${1:-}
HOST=curveon-vps
DATA=${CURVEON_DATA:-$HOME/develop/Curveon-data}
OUT=build/webplanner

tools/webplanner_assemble.sh "$OUT" >/dev/null
echo "Assembled in $OUT:"
du -shL "$OUT/web" "$OUT/webplanner-server.jar"
if [ "$APPLY" != "--apply" ]; then
  echo
  echo "Would: install openjdk-21-jre-headless and rsync, add user 'planner', copy the above to /opt/curveon-planner,"
  echo "install webplanner/server/curveon-planner.service and webplanner/server/Caddyfile, restart curveon-planner, reload caddy."
  echo "Run again with --apply to do it."
  exit 0
fi

ssh "$HOST" 'set -e
  command -v java >/dev/null && command -v rsync >/dev/null || DEBIAN_FRONTEND=noninteractive apt-get install -y -q openjdk-21-jre-headless rsync
  id planner >/dev/null 2>&1 || useradd --system --home-dir /opt/curveon-planner --shell /usr/sbin/nologin planner
  mkdir -p /opt/curveon-planner/segments /opt/curveon-planner/plan'

# Routing data for all of Europe, fetched by the VPS itself from brouter.de (where the app gets its own, so the
# roads are the same): only tiles that changed since last time; open sea has none.
ssh "$HOST" 'set -e; cd /opt/curveon-planner/segments
  for lon in -25 -20 -15 -10 -5 0 5 10 15 20 25 30 35 40; do for lat in 35 40 45 50 55 60 65 70; do
    f=$( [ $lon -lt 0 ] && echo W$((-lon)) || echo E$lon )_N$lat.rd5
    if curl -fsSR -z "$f" -o "$f.part" "https://brouter.de/brouter/segments4/$f" && [ -s "$f.part" ]; then mv "$f.part" "$f"; fi
    rm -f "$f.part"
  done; done
  echo "segments: $(ls *.rd5 | wc -l) tiles, $(du -sh . | cut -f1)"'
rsync -a --partial --info=progress2 --copy-links "$OUT/web/map/norway.pmtiles" "$HOST:/opt/curveon-planner/plan/map/"
rsync -a --delete --exclude map/norway.pmtiles "$OUT/web/" "$HOST:/opt/curveon-planner/plan/"
rsync -a "$OUT/webplanner-server.jar" "$HOST:/opt/curveon-planner/"
scp -q webplanner/server/curveon-planner.service "$HOST:/etc/systemd/system/curveon-planner.service"
scp -q webplanner/server/Caddyfile "$HOST:/etc/caddy/Caddyfile.new"

ssh "$HOST" 'set -e
  chown -R root:root /opt/curveon-planner && chmod -R a+rX /opt/curveon-planner
  systemctl daemon-reload
  systemctl enable -q curveon-planner
  systemctl restart curveon-planner
  caddy validate --adapter caddyfile --config /etc/caddy/Caddyfile.new >/dev/null
  mv /etc/caddy/Caddyfile /etc/caddy/Caddyfile.bak && mv /etc/caddy/Caddyfile.new /etc/caddy/Caddyfile
  systemctl reload caddy
  for i in $(seq 1 30); do curl -fs http://127.0.0.1:8788/plan/api/health >/dev/null && break; sleep 1; done
  echo "planner: $(curl -fs http://127.0.0.1:8788/plan/api/health)"'
echo "Live: https://relay.curveon.no/plan/"
