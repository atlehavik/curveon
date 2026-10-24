// Plays the planner page against a real app, to try the app's side of a session without a browser:
//   node tools/webplanner_send_test_trip.mjs http://localhost:18788/plan/api/route relay.curveon.no
//   adb shell am start -a android.intent.action.VIEW -d "'<printed link>'" com.curveon.app
// It sends a new trip (Bømlo → Rosendal → Odda), then waits: it prints the app's answer and the trips the app shows,
// and once it has them, renames the first of those through a change ("… (from the web)") to see the app update it.
import { channel, decodePolyline, linkUri, newLink, parseLink, random } from "../webplanner/web/webplan.js";

const [api, relay = "relay.curveon.no", linkArg] = process.argv.slice(2);
const points = [[59.794218, 5.144344], [59.98, 6.01], [60.069, 6.5459]];
const options = { style: "CURVY", surface: "PAVED", ferries: false, motorways: false, trunk: false, toll: false };
const r = await (await fetch(api, { method: "POST", body: JSON.stringify({ points, options }) })).json();
if (!r.line) throw new Error(JSON.stringify(r));

const link = linkArg ? parseLink(linkArg) : newLink(relay);
const ch = await channel(link, relay.startsWith("localhost") || relay.startsWith("127.") ? "ws" : "wss");
const stop = (name, [lat, lon]) => ({ name, lat, lon });
const trip = {
  v: 1, name: "Test fra nettet", start: stop("Bømlo", points[0]), destination: stop("Odda", points[2]), stops: [stop("Rosendal", points[1])],
  options, line: r.line.map(([la, lo]) => `${la.toFixed(5)},${lo.toFixed(5)}`).join(";"), m: r.m, s: r.s, up: r.up, curvy: r.curvy,
};
const me = random(16);
const ws = new WebSocket(ch.url);
await new Promise((ok, fail) => { ws.onopen = ok; ws.onerror = fail; });
ws.send(await ch.plan(me, Date.now(), "test-" + Date.now().toString(36), Date.now(), trip));
console.log(`sent ${Math.round(r.m / 1000)} km, ${r.line.length} points`);
console.log(linkUri(link));

const haves = new Map();
let changed = false;
ws.onmessage = async (ev) => {
  const m = await ch.open(ev.data);
  if (!m) return;
  if (m.type === "plan_got") console.log("app has it:", JSON.stringify(m.body));
  if (m.type === "plan_have" && m.body.trip) haves.set(m.body.trip.id, { ...m.body.trip, rider: m.rider });
  if (m.type === "plan_index") {
    console.log(`app shows: ${m.body.dev}, trips ${JSON.stringify(m.body.ids)}`);
    const first = haves.get(m.body.ids[0]);
    if (first && !changed && first.destination) {
      changed = true;
      const line = decodePolyline(first.poly);
      const name = first.name + " (from the web)";
      const change = { v: 1, name, start: first.start || stop("Start", line[0]), destination: first.destination, stops: first.stops,
        options: first.options, line: line.map(([la, lo]) => `${la.toFixed(5)},${lo.toFixed(5)}`).join(";"), m: first.m, s: first.s, up: first.up,
        curvy: first.curvy, ref: { r: first.rider, id: first.id } };
      ws.send(await ch.plan(me, Date.now(), "r" + first.id, Date.now(), change));
      console.log(`changed trip ${first.id} to "${name}"`);
    }
  }
};
await new Promise((ok) => setTimeout(ok, Number(process.env.WAIT_MS ?? 60000)));
ws.close();
