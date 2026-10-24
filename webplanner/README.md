# Web planner

Plan trips on a big screen at **https://relay.curveon.no/plan/** (also linked as curveon.no/plan), in a session with
Curveon: scanning the page's code connects a phone or tablet, the page shows its planned trips to open, change and
update there, and new trips go over too. Same map style (the app's `assets/map`), same roads (the app's `:routing`,
BRouter), and a trip arrives among the planned trips with its stops and its exact line.

```
browser ──POST /plan/api/route──> webplanner-server.jar (this module, 127.0.0.1:8788, 2 searches at once + a queue)
   │  map: /plan/map/norway.pmtiles (Range requests), styles, fonts, sprites — static, Caddy
   │  search: Photon and Kartverket, straight from the browser
   └─ wss /g/<id> ── relay ── app (Planned → From the web): plan frames, end-to-end encrypted (webplan.js / WebPlan.kt)
```

- `src/` the routing service (JDK HTTP server; `Planner` = limits and the answer, `Main` = HTTP).
- `web/` the page: `planner.js` (UI, texts in English and Norwegian), `webplan.js` (channel crypto, shared with tests),
  `planner.css`. Libraries are fetched at pinned versions by `tools/webplanner_assemble.sh`.
- `server/` the systemd unit and the Caddyfile for the VPS.
- Protocol: `docs/groups-protocol.md`, "Web planner".

## Try it on this PC

```
tools/webplanner_assemble.sh
(cd ~/develop/curveon-relay && PORT=8787 node src/server.ts) &
SEGMENTS=~/develop/Curveon-data/brouter/segments4 WEB_DIR=build/webplanner/web MAP_DIR=build/webplanner/web/map \
  PORT=8788 java -jar build/webplanner/webplanner-server.jar
# http://localhost:8788/plan/?relay=localhost:8787
```

Tests: `./gradlew :webplanner:test :groups:test` (unit, relay integration, JS↔Kotlin interop), and the whole page in
Firefox with `tools/webplanner_test.mjs` (Playwright; see its header). `tools/webplanner_send_test_trip.mjs` sends a
trip as the page does, to try the app's side without a browser.

## Deploy

`tools/deploy-webplanner.sh` (dry run) / `--apply`: Java and rsync on the VPS if missing, the jar, segments, page and
map to `/opt/curveon-planner`, the systemd unit and Caddyfile, then restart and reload. Measured on the VPS
(2026-10-24): A→B 1–8 s, two at once ~660 MB together; the relay kept answering in ≤0.12 s.
