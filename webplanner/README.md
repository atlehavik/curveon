# Web planner

Plan trips on a big screen at **https://relay.curveon.no/plan/** (also linked as curveon.no/plan), in a session with
Curveon: scanning the page's code connects a phone or tablet, the page shows its planned trips to open, change and
update there, and new trips go over too. Same map style (the app's `assets/map`), same roads (the app's `:routing`,
BRouter), and a trip arrives among the planned trips with its stops and its exact line.

```
browser ──POST /plan/api/route──> webplanner-server.jar (this module, 127.0.0.1:8788, 2 searches at once + a queue)
   │  POST /plan/api/adventure, /plan/api/roundtrip (all places), GET /plan/api/pois (places in a box, from memory)
   │  map: /plan/map/norway.pmtiles (Range requests), styles, fonts, sprites — static, Caddy
   │  search: Photon and Kartverket, straight from the browser
   └─ wss /g/<id> ── relay ── app (Planned → From the web): plan frames, end-to-end encrypted (webplan.js / WebPlan.kt)
```

- `src/` the routing service (JDK HTTP server; `Planner` = limits and the answer, `Main` = HTTP, `Pois` = places in
  memory, `Stats` = counts per hour).
- **Leg by leg.** A route is asked for and answered one leg at a time (point to point, leaving each point the way the leg
  before arrived: `heading`, `arrive`). The page keeps every leg it got and asks only for the legs it lacks, so dragging
  one point of a long trip is two short searches and switching back to a road style seen before is none. A newer
  request from the same page (`tab`) makes the service give up the one before, waiting or running. Each leg comes with
  its heights (`ele`, for the height profile) and the fuel stations along it (`fuel`, for the warning about long
  stretches without).
- **Places** (fuel, viewpoints, passes, camping, food, ferries, motorcycle shops): `tools/webplanner_pois.py` takes them
  out of the app's search indexes (the `search` release of atlehavik/curveon-data) as TSVs, which the service holds in
  memory (`POIS`). The deploy updates them; only areas whose index changed are downloaded again.
- **Where the gravel is** (`GET /plan/api/heat?t=E5_N60`, `Heat`): the app's gravel heat map, one segment file at a
  time. Counted on one low-priority thread right after a start and kept in `HEAT` (`/var/lib/curveon-planner/heat`),
  so a rider never waits for it; a file asked for before it is counted goes first and the page asks again.
- **How it keeps up:** `curl 127.0.0.1:8788/plan/api/stats` on the VPS (only there): requests, legs, waits, busy and
  given-up searches this hour and for the last 24; every hour also a line `planner hour:` in the journal
  (`journalctl -u curveon-planner | grep "planner hour"`). Counts only, nothing about riders or places.
- `web/` the page: `planner.js` (UI, texts in English and Norwegian), `webplan.js` (channel crypto, shared with tests),
  `planner.css`. Libraries are fetched at pinned versions by `tools/webplanner_assemble.sh`.
- `server/` the systemd unit and the Caddyfile for the VPS.
- Protocol: `docs/groups-protocol.md`, "Web planner".

## Try it on this PC

```
tools/webplanner_assemble.sh
(cd ~/develop/curveon-relay && PORT=8787 node src/server.ts) &
python3 -I tools/webplanner_pois.py /tmp/pois --sqlite ~/develop/Curveon-data/norway-search.sqlite   # places, if wanted
SEGMENTS=~/develop/Curveon-data/brouter/segments4 WEB_DIR=build/webplanner/web MAP_DIR=build/webplanner/web/map POIS=/tmp/pois \
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
