// Drives the web planner in a real browser (Playwright, Firefox) against a planner service and a relay, and plays the
// app's part with webplanner/web/webplan.js: plan Bømlo → Rosendal → Odda, send it, fetch it as the app, answer, and see
// the page say so. Screenshots go to OUT.
//   PLAYWRIGHT_DIR=<folder with node_modules/playwright> node tools/webplanner_test.mjs http://localhost:18788/plan/ localhost:18787 OUT
import { createRequire } from "node:module";
import { channel, parseLink, random } from "../webplanner/web/webplan.js";

const { firefox } = createRequire((process.env.PLAYWRIGHT_DIR || process.cwd()) + "/")("playwright");
const [base, relay, out = "."] = process.argv.slice(2);
const viewports = { desktop: { width: 1400, height: 900 }, phone: { width: 390, height: 844 } };
const browser = await firefox.launch();
let failed = false;
const check = (ok, what) => { console.log(`${ok ? "ok  " : "FAIL"} ${what}`); if (!ok) failed = true; };

for (const [name, viewport] of Object.entries(viewports)) {
  const page = await browser.newPage({ viewport, locale: name === "phone" ? "nb-NO" : "en-GB" });
  const errors = [];
  page.on("pageerror", (e) => errors.push(e.message));
  page.on("console", (m) => { if (m.type() === "error") errors.push(m.text()); });
  await page.goto(`${base}?relay=${relay}&debug`);
  await page.waitForFunction(() => window.curveonMap?.loaded(), null, { timeout: 30000 });
  await page.waitForTimeout(1500);
  await page.screenshot({ path: `${out}/${name}-1-empty.png` });

  const clickAt = async (lat, lon) => {
    const p = await page.evaluate(([la, lo]) => { const m = window.curveonMap; m.jumpTo({ center: [lo, la], zoom: 9 }); const q = m.project([lo, la]); const r = m.getCanvas().getBoundingClientRect(); return { x: r.left + q.x, y: r.top + q.y }; }, [lat, lon]);
    await page.waitForTimeout(300);
    await page.mouse.click(p.x, p.y);
  };
  if (name === "phone") await page.click("#fold"); // the sheet out of the way of the map
  await clickAt(59.7795, 5.2049); // Bømlo
  await clickAt(60.0689, 6.5462); // Odda
  if (name === "phone") await page.click("#fold");
  await page.waitForSelector("#summary:not([hidden])", { timeout: 60000 });
  check(true, `${name}: route between two map clicks`);

  await page.fill("#q", "Rosendal");
  await page.waitForSelector("#hits li", { timeout: 15000 });
  await page.keyboard.press("Enter");
  // Rosendal went in last, so it is the destination: move it up to be the stop on the way.
  await page.locator("#stops li").nth(2).locator("button", { hasText: "↑" }).click();
  await page.waitForFunction(() => document.querySelectorAll("#stops li").length === 3 && !document.querySelector("#summary").hidden, null, { timeout: 60000 });
  const names = await page.$$eval("#stops li .name", (els) => els.map((e) => e.textContent));
  check(names[1].includes("Rosendal"), `${name}: stops ${names.join(" → ")}`);
  const fitsInside = async (sel) => page.$eval(sel, (e) => { const r = e.getBoundingClientRect(); return r.left >= 0 && r.top >= 0 && r.right <= innerWidth && r.bottom <= innerHeight; });
  await page.screenshot({ path: `${out}/${name}-2-route.png` });

  if (name === "desktop") {
    // Drag the line sideways: a through-point goes in on the leg that was grabbed, and the route is worked out again.
    const grab = await page.evaluate(() => {
      const m = window.curveonMap;
      const c = m.getSource("route").serialize().data.geometry.coordinates;
      const p = c[Math.floor(c.length * 0.25)];
      m.jumpTo({ center: p, zoom: 11 });
      const q = m.project(p); const r = m.getCanvas().getBoundingClientRect();
      return { x: r.left + q.x, y: r.top + q.y };
    });
    await page.waitForTimeout(500);
    await page.mouse.move(grab.x, grab.y);
    await page.mouse.down();
    await page.mouse.move(grab.x + 40, grab.y + 60, { steps: 8 });
    await page.mouse.up();
    await page.waitForFunction(() => document.querySelectorAll("#stops li").length === 4 && !document.querySelector("#summary").hidden, null, { timeout: 60000 });
    const kinds = await page.$$eval("#stops li", (els) => els.map((e) => e.className));
    check(kinds.join(",") === "start,via,stop,end", `${name}: dragging the line adds a through-point on the right leg (${kinds.join(",")})`);
    await page.screenshot({ path: `${out}/${name}-2b-dragged.png` });
  }

  await page.locator("#send").scrollIntoViewIfNeeded();
  check(await fitsInside("#send"), `${name}: Send button on screen`);
  await page.click("#send");
  await page.waitForSelector("#pairing[open]");
  await page.screenshot({ path: `${out}/${name}-3-pairing.png` });
  check(await fitsInside("#pairing button"), `${name}: Done button on screen`);
  await page.click("#pairing button");
  await page.waitForFunction(() => !document.querySelector("#sent").hidden, null, { timeout: 15000 });

  // The app: fetch what is kept for the channel, answer for the first trip.
  const link = parseLink(JSON.parse(await page.evaluate(() => localStorage.getItem("curveon.link"))));
  const ch = await channel(link, "ws");
  const ws = new WebSocket(ch.url);
  const trips = [];
  ws.onmessage = async (ev) => { const m = await ch.open(ev.data); if (m?.type === "plan") trips.push(m.body); };
  await new Promise((r) => { ws.onopen = r; });
  await new Promise((r) => setTimeout(r, 1000));
  check(trips.length === 1 && trips[0].trip.line.split(";").length > 100, `${name}: app got the trip (${trips[0]?.trip.name}, ${trips[0]?.trip.line.split(";").length} points)`);
  if (trips[0]) {
    // As WebPlan.gotFrame: plan_got, kept under the trip id.
    const { b64 } = await import("../webplanner/web/webplan.js");
    const sealed = await (async () => {
      const rider = random(16);
      const enc = new TextEncoder();
      const keyBits = await crypto.subtle.deriveBits({ name: "HKDF", hash: "SHA-256", salt: link.g, info: enc.encode("curveon/1 enc") },
        await crypto.subtle.importKey("raw", link.k, "HKDF", false, ["deriveBits"]), 256);
      const key = await crypto.subtle.importKey("raw", keyBits, "AES-GCM", false, ["encrypt"]);
      const n = random(12);
      const aad = new Uint8Array([...enc.encode("curveon/1"), ...link.g, ...rider, 0, 0, 0, 0, ...enc.encode("plan_got")]);
      const c = new Uint8Array(await crypto.subtle.encrypt({ name: "AES-GCM", iv: n, additionalData: aad }, key,
        enc.encode(JSON.stringify({ seq: 1, ts: Date.now(), tid: trips[0].tid, rev: trips[0].rev, dev: "Test Pixel" }))));
      return JSON.stringify({ v: 1, t: "plan_got", r: b64(rider), e: 0, n: b64(n), c: b64(c), k: trips[0].tid, x: 3600 });
    })();
    ws.send(sealed);
  }
  await page.waitForFunction(() => document.querySelector("#sent-list small.got")?.textContent.includes("Test Pixel"), null, { timeout: 10000 })
    .then(() => check(true, `${name}: page shows the app has it`), () => check(false, `${name}: page shows the app has it`));
  ws.close();
  await page.screenshot({ path: `${out}/${name}-4-sent.png` });
  check(errors.length === 0, `${name}: no errors on the page ${errors.join(" | ")}`);
  await page.close();
}
await browser.close();
process.exit(failed ? 1 : 0);
