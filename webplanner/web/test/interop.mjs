// Run by groups' WebPlanInteropTest: proves the page's WebCrypto frames and groups/WebPlan.kt understand each other.
// `node interop.mjs seal` prints {link, url, frame}; `node interop.mjs open <link> <frame>` prints what the frame holds.
import { channel, decodePolyline, linkUri, newLink, parseLink, random } from "../webplan.js";

const [mode, a, b] = process.argv.slice(2);
if (mode === "seal") {
  const link = newLink("relay.curveon.no");
  const ch = await channel(link);
  const trip = {
    v: 1, name: "Hardanger på langs", start: { name: "Bømlo", lat: 59.79, lon: 5.14 },
    destination: { name: "Odda", lat: 60.069, lon: 6.5459 }, stops: [{ name: "", lat: 60, lon: 6.2, via: true }],
    options: { style: "VERY_CURVY", surface: "PAVED", ferries: true }, line: "59.79000,5.14000;60.06900,6.54590",
    m: 133000, s: 9000, up: 1200, curvy: 70,
  };
  const ref = { r: "devicerider", id: 42 };
  console.log(JSON.stringify({
    link: linkUri(link), url: ch.url, frame: await ch.plan(random(16), 1, "tid-1", 4, trip),
    changed: await ch.plan(random(16), 2, "r42", 5, { ...trip, ref }), del: await ch.del(random(16), 3, ref),
  }));
} else if (mode === "open") {
  const ch = await channel(parseLink(a));
  const opened = await ch.open(b);
  const poly = opened?.body?.trip?.poly;
  console.log(JSON.stringify({ url: ch.url, opened, line: poly ? decodePolyline(poly) : null }));
}
