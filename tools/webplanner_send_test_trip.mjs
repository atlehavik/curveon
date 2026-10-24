// Sends a test trip the way the planner page does, to try the app's side without a browser: a route from a planner
// service, sealed on a new channel (or the one in LINK) on the relay, then prints the link to open in the app:
//   node tools/webplanner_send_test_trip.mjs http://localhost:18788/plan/api/route relay.curveon.no
//   adb shell am start -a android.intent.action.VIEW -d "'<printed link>'" com.curveon.app
// Then waits a minute for the app's answer (plan_got) and prints it.
import { channel, linkUri, newLink, parseLink, random } from "../webplanner/web/webplan.js";

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
const ws = new WebSocket(ch.url);
await new Promise((ok, fail) => { ws.onopen = ok; ws.onerror = fail; });
ws.send(await ch.plan(random(16), Date.now(), "test-" + Date.now().toString(36), 1, trip));
console.log(`sent ${Math.round(r.m / 1000)} km, ${r.line.length} points`);
console.log(linkUri(link));
ws.onmessage = async (ev) => { const m = await ch.open(ev.data); if (m?.type === "plan_got") console.log("app has it:", JSON.stringify(m.body)); };
await new Promise((ok) => setTimeout(ok, Number(process.env.WAIT_MS ?? 60000)));
ws.close();
