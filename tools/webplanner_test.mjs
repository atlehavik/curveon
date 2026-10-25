// Drives the web planner in a real browser (Playwright, Firefox) against a planner service and a relay, and plays a
// device in the session with webplanner/web/webplan.js: it shows its planned trips, the page opens one, changes and
// updates it, deletes one, and sends a trip drawn before connecting. On the way: legs kept by the page, the height
// profile, nights and days, comparing road styles, places on the map, a link and a GPX file of the trip, and a roundtrip.
// Screenshots go to OUT.
//   [BROWSER=chromium] PLAYWRIGHT_DIR=<folder with node_modules/playwright> node tools/webplanner_test.mjs http://localhost:18788/plan/ localhost:18787 OUT
import { createRequire } from "node:module";
import fs from "node:fs/promises";
import { b64, channel, parseLink, random } from "../webplanner/web/webplan.js";

// BROWSER=chromium for Chrome and Edge's engine (a click on the route behaved differently there); Firefox by default.
const browserType = createRequire((process.env.PLAYWRIGHT_DIR || process.cwd()) + "/")("playwright")[process.env.BROWSER || "firefox"];
const [base, relay, out = "."] = process.argv.slice(2);
const browser = await browserType.launch();
let failed = false;
const check = (ok, what) => { console.log(`${ok ? "ok  " : "FAIL"} ${what}`); if (!ok) failed = true; };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** A device in the session: what it got from the page, and frames it sends (sealed as WebPlan.kt does). */
async function device(link, name) {
  const ch = await channel(link, /^(localhost|127\.)/.test(relay) ? "ws" : "wss");
  const me = random(16);
  const ws = new WebSocket(ch.url);
  const got = { plans: [], deletes: [] };
  // As Curveon: it shows its trips in answer to what it finds in the channel; the page must have said something.
  let heard;
  const hearing = new Promise((r) => { heard = r; });
  ws.onmessage = async (ev) => {
    const m = await ch.open(ev.data);
    if (m?.type === "plan") got.plans.push(m.body);
    if (m?.type === "plan_del") got.deletes.push(m.body.ref);
    if (m) heard();
  };
  // A relay that turns the connection down (e.g. too many new groups from one address) fails the test, not hangs it.
  await new Promise((r, j) => { ws.onopen = r; ws.onerror = () => j(new Error("the relay refused the device")); setTimeout(() => j(new Error("the relay did not answer")), 10000); });
  let seq = 0;
  const send = async (type, body, slot) => ws.send(await ch.seal(type, me, { seq: ++seq, ts: Date.now(), ...body }, slot));
  return {
    me: b64(me), got, close: () => ws.close(),
    /** Its planned trips, as the app publishes them: each one, then the list (once the page said anything). */
    async show(trips) {
      const said = await Promise.race([hearing.then(() => true), sleep(5000).then(() => false)]);
      check(said, `the page speaks first on the channel, so a device answers (${name})`);
      for (const t of trips) await send("plan_have", { trip: t }, "h" + t.id);
      await send("plan_index", { dev: name, ids: trips.map((t) => t.id) }, "index");
    },
    answer: (p) => send("plan_got", { tid: p.tid, rev: p.rev, dev: name, id: 99 }, p.tid),
  };
}

// Bømlo → Odda as an encoded polyline (a few points are enough for the page).
const POLY = "{tzkJsqw^cpVkdlA_sNoaoAsjPgvgB";
const HAVE = { id: 11, rev: "a", name: "Helgetur", group: "Sommer", stops: [], start: { name: "Bømlo", lat: 59.7795, lon: 5.2049 },
  destination: { name: "Odda", lat: 60.0689, lon: 6.5462 }, options: { style: "CURVY", surface: "PAVED" }, poly: POLY, m: 133000, s: 9000, up: 1200, curvy: 50, at: 0 };
const TRACK = { id: 12, rev: "b", name: "Kjørt tur", stops: [], options: {}, poly: POLY, m: 90000, s: 6000, up: 300, curvy: 30, at: 0 };

for (const [name, viewport] of Object.entries({ desktop: { width: 1400, height: 900 }, phone: { width: 390, height: 844 } })) {
  const page = await browser.newPage({ viewport, locale: name === "phone" ? "nb-NO" : "en-GB" });
  const errors = [];
  page.on("pageerror", (e) => errors.push(e.message));
  page.on("console", (m) => { if (m.type() === "error") errors.push(m.text()); });
  page.on("dialog", (d) => d.accept());
  // What the planner service is asked for: the number of points in each route request.
  const asked = [];
  page.on("request", (r) => { if (r.url().endsWith("/api/route")) asked.push(JSON.parse(r.postData()).points.length); });
  await page.goto(/^(localhost|127\.)/.test(relay) ? `${base}?relay=${relay}&debug` : `${base}?debug`);
  await page.waitForFunction(() => window.curveonMap?.loaded(), null, { timeout: 30000 });
  await page.waitForTimeout(1500);
  const fitsInside = async (sel) => page.$eval(sel, (e) => { const r = e.getBoundingClientRect(); return r.width > 0 && r.left >= 0 && r.top >= 0 && r.right <= innerWidth && r.bottom <= innerHeight; });

  // Drawn before connecting: it stays and becomes part of the session.
  const clickAt = async (lat, lon, button) => {
    const p = await page.evaluate(([la, lo]) => { const m = window.curveonMap; m.jumpTo({ center: [lo, la], zoom: 9 }); const q = m.project([lo, la]); const r = m.getCanvas().getBoundingClientRect(); return { x: r.left + q.x, y: r.top + q.y }; }, [lat, lon]);
    await page.waitForTimeout(300);
    await page.mouse.click(p.x, p.y, button ? { button } : {});
    return p;
  };
  // A click asks what the place is: first only the start, then the destination, then stop, via point or new destination.
  const menuItems = () => page.$$eval(".menu button", (b) => b.map((x) => x.lastChild.textContent));
  const pick = async (label) => { await page.locator(".menu button", { hasText: label }).click(); await page.waitForTimeout(200); };
  // The road style and surface are lists with a sign for each choice: open, then click the choice.
  const choose = async (id, value) => { await page.click(`#${id}-btn`); await page.click(`.isel-list li[data-value="${value}"]`); };
  check(await page.isVisible("#connect-intro") && (await page.getAttribute("#pair", "class")) === "primary", `${name}: not connected: a card says how to connect`);
  if (name === "phone") await page.click("#fold");
  await clickAt(59.7795, 5.2049);
  check(JSON.stringify(await menuItems()) === JSON.stringify([name === "phone" ? "Start her" : "Start here"]), `${name}: the first click offers the start (${await menuItems()})`);
  if (name === "phone") await page.screenshot({ path: `${out}/${name}-0-menu.png` });
  await pick(name === "phone" ? "Start her" : "Start here");
  await clickAt(60.0689, 6.5462);
  check((await menuItems()).length === 3, `${name}: the second click offers via point, stop or destination (${await menuItems()})`);
  await pick(name === "phone" ? "Mål her" : "Destination here");
  if (name === "phone") await page.click("#fold");
  await page.waitForSelector("#summary:not([hidden])", { timeout: 60000 });
  check(true, `${name}: a route drawn before connecting`);
  // Under the numbers: the heights along the route, and Compare; under Send: GPX and a link. All on screen.
  for (const sel of ["#profile-canvas", "#compare", "#save-gpx", "#share"]) {
    await page.locator(sel).scrollIntoViewIfNeeded();
    check(await page.isVisible(sel) && await fitsInside(sel), `${name}: ${sel} shows and fits`);
  }
  check((await page.$$eval("#stops li .leg", (e) => e.map((x) => x.textContent)))[0]?.includes("km"), `${name}: the destination says how far from the start`);
  // The options' lists show a sign for every choice, and fit on the screen.
  await page.locator("#motorways-btn").scrollIntoViewIfNeeded();
  await page.click("#motorways-btn");
  check((await page.$$eval(".isel-list li svg", (e) => e.length)) === 3 && await fitsInside(".isel-list"), `${name}: the motorway list has a sign for each choice and fits`);
  if (name === "desktop") await page.screenshot({ path: `${out}/${name}-0-options.png` });
  await page.keyboard.press("Escape");
  check(!(await page.$(".isel-list")), `${name}: Escape closes the list`);
  // Everything says what it does when the pointer rests on it.
  const untitled = await page.$$eval("#panel button:not([hidden]), #panel input, #panel select", (els) => els.filter((e) => e.offsetParent && !e.title && !e.closest("#stops, .trips, #compare-list")).map((e) => e.id || e.textContent));
  check(untitled.length === 0, `${name}: every button and field has a tooltip (${untitled.join(", ")})`);
  // Coloured as in the app: blue, with gravel and tracks known for each leg.
  check((await page.evaluate(() => window.curveonMap.getPaintProperty("route", "line-color"))) === "#0091ea" &&
    (await page.evaluate(() => !!window.curveonMap.getSource("cv-surface"))), `${name}: the route is blue, with a layer for gravel and tracks`);
  await page.screenshot({ path: `${out}/${name}-0-route.png` });

  // With a route: a third click offers stop, via point and new destination; a click on a point offers to change or remove it.
  if (name === "phone") await page.click("#fold");
  await clickAt(60.39, 5.32);
  check((await menuItems()).length === 3, `${name}: a third click offers stop, via point, new destination (${await menuItems()})`);
  if (name === "desktop") await page.screenshot({ path: `${out}/${name}-0-menu.png` });
  await pick(name === "phone" ? "Stopp her" : "Stop here");
  check((await page.$$eval("#stops li", (e) => e.length)) === 3 && (await page.$$eval("#stops li", (e) => e[1].className)) === "stop", `${name}: the stop went in between start and destination`);
  await page.locator(".pin.stop").click();
  check((await menuItems()).length === 3, `${name}: a click on a stop offers via point, the night there, and remove (${await menuItems()})`);
  await pick(name === "phone" ? "Gjør til hjelpepunkt" : "Make it a via point");
  check((await page.$$eval("#stops li", (e) => e[1].className)) === "via", `${name}: the stop became a via point`);
  await page.locator(".pin.via").click();
  await pick(name === "phone" ? "Fjern" : "Remove");
  check((await page.$$eval("#stops li", (e) => e.length)) === 2, `${name}: the via point was removed`);
  // Undo brings it back, and once more makes it a stop again; then it goes for good.
  if (name === "phone") await page.click("#fold");
  await page.locator("#undo").scrollIntoViewIfNeeded();
  check(await fitsInside("#undo"), `${name}: Undo on screen`);
  await page.click("#undo");
  check((await page.$$eval("#stops li", (e) => e.length)) === 3 && (await page.$$eval("#stops li", (e) => e[1].className)) === "via", `${name}: undo brought the via point back`);
  await page.click("#undo");
  check((await page.$$eval("#stops li", (e) => e[1].className)) === "stop", `${name}: undo again made it a stop again`);
  if (name === "phone") await page.click("#fold");
  await page.locator(".pin.stop").click();
  await pick(name === "phone" ? "Fjern" : "Remove");
  if (name === "phone") await page.click("#fold");
  check((await page.$$eval("#stops li", (e) => e.length)) === 2, `${name}: the stop was removed again`);
  if (name === "phone") await page.click("#fold");
  if (name === "desktop") {
    // Right-click is a context menu about the place: on the route it tells the road and how far along the route it is.
    await page.waitForSelector("#summary:not([hidden])", { timeout: 60000 });
    const routeMid = () => page.evaluate(() => { const m = window.curveonMap; const l = m.getStyle().sources.route.data.geometry.coordinates; const c = l[Math.floor(l.length / 2)]; const q = m.project(c); const r = m.getCanvas().getBoundingClientRect(); return { x: r.left + q.x, y: r.top + q.y }; });
    await page.evaluate(() => { const m = window.curveonMap; const l = m.getStyle().sources.route.data.geometry.coordinates; m.jumpTo({ center: l[Math.floor(l.length / 2)], zoom: 12 }); });
    // The road there must be in the tiles: over the internet they take a moment.
    await page.waitForFunction(() => window.curveonMap.loaded() && window.curveonMap.areTilesLoaded(), null, { timeout: 20000 }).catch(() => {});
    await page.waitForTimeout(500);
    const at = await routeMid();
    await page.mouse.click(at.x, at.y, { button: "right" });
    const info = await page.$eval(".menu", (e) => e.innerText);
    check(/km \d+ of \d+ on the route/.test(info) && /road|Motorway/i.test(info) && /Copy coordinates/.test(info) && (await page.$$eval("#stops li", (e) => e.length)) === 2,
      `desktop: right-click on the route tells about the road and adds nothing (${info.replace(/\s+/g, " ")})`);
    await page.screenshot({ path: `${out}/${name}-0-info.png` });
    await page.keyboard.press("Escape");
    // A left click on the route: only a via point or stop there.
    await page.mouse.click(at.x, at.y);
    check((await menuItems()).length === 2, `desktop: a click on the route offers via point or stop (${await menuItems()})`);
    await page.keyboard.press("Escape");
    await page.evaluate(() => { const m = window.curveonMap; const l = m.getStyle().sources.route.data.geometry.coordinates; m.jumpTo({ center: l[l.length - 1] }); });
    await page.waitForTimeout(300);
    await page.locator(".pin.end").click({ button: "right" });
    check((await menuItems()).includes("Remove"), "desktop: right-click on a point gives its menu");
    await page.keyboard.press("Escape");
    // Dragging the line: a dashed guess through the pointer while dragging, a via point after.
    await page.evaluate(() => { const m = window.curveonMap; const l = m.getStyle().sources.route.data.geometry.coordinates; m.jumpTo({ center: l[Math.floor(l.length / 2)] }); });
    await page.waitForTimeout(500);
    const mid = await routeMid();
    await page.mouse.move(mid.x, mid.y);
    await page.mouse.down();
    await page.mouse.move(mid.x + 40, mid.y + 60, { steps: 5 });
    await page.waitForTimeout(200);
    const dashed = await page.evaluate(() => window.curveonMap.querySourceFeatures("drag").length);
    await page.screenshot({ path: `${out}/${name}-0-drag.png` });
    await page.mouse.up();
    check(dashed > 0, "desktop: a dashed line while dragging the route");
    check((await page.$$eval("#stops li", (e) => e.length)) === 3, "desktop: dragging the route made a via point");
    await page.waitForSelector("#summary:not([hidden])", { timeout: 60000 });

    // Adventure: draw round the trip with the mouse button held down; it comes back as via points along fun roads.
    await page.click("#adv");
    check(await page.isVisible("#adv-box") && (await page.textContent("#adv-hint")).length > 20 && await page.isVisible("#draw") && await fitsInside("#draw-cancel"),
      "desktop: Adventure asks for an area to be drawn, on the map too, with Cancel");
    const box = await page.evaluate(() => {
      const m = window.curveonMap; const l = m.getStyle().sources.route.data.geometry.coordinates;
      const b = l.reduce((bb, c) => bb.extend(c), new maplibregl.LngLatBounds(l[0], l[0]));
      m.fitBounds(b, { padding: { top: 120, bottom: 120, left: 520, right: 140 }, animate: false });
      const r = m.getCanvas().getBoundingClientRect(); const sw = m.project(b.getSouthWest()); const ne = m.project(b.getNorthEast());
      return { x0: r.left + sw.x, y0: r.top + ne.y, x1: r.left + ne.x, y1: r.top + sw.y };
    });
    await page.waitForTimeout(800);
    const cx = (box.x0 + box.x1) / 2, cy = (box.y0 + box.y1) / 2, rx = (box.x1 - box.x0) / 2 + 70, ry = (box.y1 - box.y0) / 2 + 70;
    await page.mouse.move(cx + rx, cy);
    await page.mouse.down();
    for (let k = 1; k <= 48; k++) await page.mouse.move(cx + rx * Math.cos((k / 48) * 2 * Math.PI), cy + ry * Math.sin((k / 48) * 2 * Math.PI));
    await page.mouse.up();
    check(!(await page.$(".menu")), "desktop: ending the drawing opens no menu");
    await page.screenshot({ path: `${out}/${name}-0-adventure-searching.png` });
    const t0 = Date.now();
    await page.waitForSelector("#adv-again:not([hidden])", { timeout: 150000 });
    const advStops = await page.$$eval(".pin", (e) => e.length);
    check((await page.$$eval("#stops li", (e) => e.length)) === 3, "desktop: the adventure's via points are one line in the list");
    check(advStops >= 6 && (await page.isVisible("#summary")) && !(await page.$eval("#adv-hint", (e) => e.classList.contains("error"))),
      `desktop: an adventure came back as a trip with ${advStops - 2} via points in ${((Date.now() - t0) / 1000).toFixed(0)} s (${await page.textContent("#adv-hint")})`);
    await page.screenshot({ path: `${out}/${name}-0-adventure.png` });
    await page.click("#undo");
    check((await page.$$eval("#stops li", (e) => e.length)) === 3, "desktop: undo goes back to the trip before the adventure");
    await page.click("#adv-close");
    check(await page.isHidden("#adv-box"), "desktop: the adventure box closes");
    await page.waitForSelector("#summary:not([hidden])", { timeout: 60000 });

    // Leg by leg: back to a road style seen before, the page has every leg and asks the planner for nothing.
    const done = () => page.waitForFunction(() => !document.querySelector("#summary").hidden, null, { timeout: 60000 });
    await choose("style", "FASTEST");
    await done();
    const n0 = asked.length;
    await choose("style", "CURVY");
    await done();
    check(asked.length === n0, `desktop: back to Curvy came from the legs the page had (${asked.length - n0} requests)`);

    // The height profile: pointing at it shows the place on the map and how high it is.
    const pbox = await page.locator("#profile-canvas").boundingBox();
    await page.mouse.move(pbox.x + pbox.width / 2, pbox.y + 40);
    const hover = await page.evaluate(() => window.curveonMap.getStyle().sources["cv-hover"].data);
    check(/km \d+ · -?\d+ m/.test(await page.textContent("#profile-at")) && hover?.geometry?.type === "Point", `desktop: pointing at the profile shows the place (${await page.textContent("#profile-at")})`);

    // Compare the road styles: all three with their numbers, the other two on the map; picking one asks for nothing new.
    await page.click("#compare");
    await page.waitForFunction(() => [...document.querySelectorAll("#compare-list small")].every((x) => / km /.test(x.textContent)), null, { timeout: 120000 });
    const alts = await page.evaluate(() => window.curveonMap.getStyle().sources["cv-alt"].data.features.length);
    check(alts === 2, `desktop: three road styles side by side, the other two on the map (${await page.$eval("#compare-list", (e) => e.innerText.replace(/\s+/g, " "))})`);
    await page.screenshot({ path: `${out}/${name}-0-compare.png` });
    const n1 = asked.length;
    await page.locator("#compare-list li", { hasText: "Fastest" }).locator("button").click();
    await page.waitForFunction(() => document.querySelector("#style").value === "FASTEST" && !document.querySelector("#summary").hidden, null, { timeout: 60000 });
    check(asked.length === n1 && await page.isVisible("#compare-box"), `desktop: picking Fastest is at once, and the comparison stays (${asked.length - n1} requests)`);
    await page.click("#compare-close");
    check(await page.isHidden("#compare-box") && (await page.evaluate(() => window.curveonMap.getStyle().sources["cv-alt"].data.features.length)) === 0, "desktop: closing the comparison takes the other lines away");
    await choose("style", "CURVY");
    await done();

    // A night on the way: the list shows the trip day by day, and it can go to Curveon a day at a time.
    await clickAt(60.39, 5.32);
    await pick("Stop here");
    await done();
    await page.locator(".pin.stop").click();
    await pick("Stay the night here");
    const dayLines = await page.$$eval("#stops li.day", (e) => e.map((x) => x.innerText.replace(/\s+/g, " ")));
    check(dayLines.length === 2 && dayLines.every((d) => / km · /.test(d)) && (await page.$$eval(".pin.night", (e) => e.length)) === 1,
      `desktop: a night makes two days (${dayLines.join(" | ")})`);
    check(await page.$eval('#send-as option[value="days"]', (o) => !o.hidden), "desktop: with a night, Send as offers each day as a trip");
    await page.screenshot({ path: `${out}/${name}-0-days.png` });

    // A link to the trip: opened elsewhere, the same places, nights and name, routed there.
    await page.fill("#name", "Linktur");
    await page.evaluate(() => { navigator.clipboard.writeText = async (x) => { window.copied = x; }; });
    await page.click("#share");
    const tripUrl = await page.evaluate(() => window.copied);
    check(/[?&]trip=/.test(tripUrl || "") && /copied/i.test(await page.textContent("#status")), "desktop: Copy link puts the trip in a link");
    const other = await browser.newPage({ viewport, locale: "en-GB" });
    await other.goto(tripUrl + "&debug");
    await other.waitForSelector("#summary:not([hidden])", { timeout: 60000 });
    check((await other.inputValue("#name")) === "Linktur" && (await other.$$eval(".pin", (e) => e.length)) === await page.$$eval(".pin", (e) => e.length) &&
      (await other.$$eval(".pin.night", (e) => e.length)) === 1 && !/trip=/.test(other.url()), "desktop: the link opens the same trip, with its night, and leaves the address");
    await other.close();

    // Save as GPX: the line with its heights, the places as waypoints and as a route.
    const dl = page.waitForEvent("download");
    await page.click("#save-gpx");
    const gpx = await fs.readFile(await (await dl).path(), "utf8");
    check(/<trkpt[^>]+><ele>-?\d+<\/ele>/.test(gpx) && (gpx.match(/<wpt /g) || []).length === 3 && /<rte>/.test(gpx) && gpx.includes("<name>Linktur</name>"), "desktop: Save as GPX gives the line with heights, the places and the route");

    // Back to the trip as it was, for the session below.
    await page.fill("#name", "");
    await page.click("#undo");
    await page.click("#undo");
    check((await page.$$eval("#stops li", (e) => e.length)) === 3, "desktop: undo takes the night and the stop away again");
    await done();

    // Places on the map: fuel stations near by, and a click on one offers it as a place on the trip.
    await page.click(".places-btn");
    await page.locator(".places-pop label", { hasText: "Fuel" }).click();
    await page.evaluate(() => { window.curveonMap.jumpTo({ center: [5.33, 60.39], zoom: 12 }); });
    await page.waitForFunction(() => window.curveonMap.getStyle().sources["cv-pois"].data.features.length > 0, null, { timeout: 15000 }).catch(() => {});
    await page.waitForTimeout(1500);
    await page.screenshot({ path: `${out}/${name}-0-places.png` });
    const poi = await page.evaluate(() => {
      // One out on the map, not behind the panel or the places list.
      const m = window.curveonMap; const w = m.getCanvas().clientWidth;
      const f = m.queryRenderedFeatures({ layers: ["cv-pois"] }).find((x) => { const q = m.project(x.geometry.coordinates); return x.properties.name && q.x > 440 && q.x < w - 300 && q.y > 60 && q.y < 840; });
      if (!f) return null; const q = m.project(f.geometry.coordinates); const r = m.getCanvas().getBoundingClientRect();
      return { x: r.left + q.x, y: r.top + q.y, name: f.properties.name };
    });
    check(!!poi, "desktop: fuel stations show on the map");
    if (poi) {
      await page.click(".places-btn");
      await page.mouse.click(poi.x, poi.y);
      await page.waitForSelector(".menu");
      check((await page.textContent(".menu-title")) === poi.name && (await menuItems()).length >= 2, `desktop: a click on ${poi.name} offers it as a place on the trip (${await menuItems()})`);
      await page.keyboard.press("Escape");
    }
  }
  if (name === "phone") await page.click("#fold");

  // Connect: the code shows, a device scans it and shows its trips, and the code goes away by itself.
  await page.locator("#pair").scrollIntoViewIfNeeded();
  check(await fitsInside("#pair"), `${name}: Connect on screen`);
  await page.click("#pair");
  await page.waitForSelector("#pairing[open]");
  check(await fitsInside("#pairing button"), `${name}: Close on the code on screen`);
  await page.screenshot({ path: `${out}/${name}-1-pairing.png` });
  const link = parseLink(JSON.parse(await page.evaluate(() => localStorage.getItem("curveon.link"))));
  const dev = await device(link, "Test Pixel");
  await dev.show([HAVE, TRACK]);
  // A second device with the same trip (as the page sends to all at once): Update changes both copies.
  const dev2 = await device(link, "Test X7");
  await dev2.show([{ ...HAVE, id: 21 }]);
  await page.waitForSelector("#pairing:not([open])", { state: "attached", timeout: 10000 }).then(() => check(true, `${name}: the code closes when the device connects`), () => check(false, `${name}: the code closes when the device connects`));
  await page.waitForSelector(".device ul.trips li", { timeout: 10000 });
  const listed = await page.$$eval(".device", (els) => els.map((e) => e.innerText.replace(/\s+/g, " ")));
  check(/test pixel/i.test(listed[0] || "") && listed[0].includes("Helgetur") && listed[0].includes("Sommer"), `${name}: the device's trips in their groups (${listed[0]})`);
  check((await page.$$eval("#stops li", (e) => e.length)) === (name === "desktop" ? 3 : 2), `${name}: what was drawn is still on the map`);
  await page.screenshot({ path: `${out}/${name}-2-session.png` });

  // Send what was drawn: a new trip on the device.
  await page.locator("#send").scrollIntoViewIfNeeded();
  check(await fitsInside("#send"), `${name}: Send on screen`);
  await page.click("#send");
  for (let i = 0; i < 50 && !dev.got.plans.length; i++) await sleep(100);
  const sentNew = dev.got.plans[0];
  check(sentNew && !sentNew.trip.ref && sentNew.trip.line.split(";").length > 50, `${name}: the drawn trip reached the device as a new one`);
  if (sentNew) await dev.answer(sentNew);
  await page.waitForSelector("#pending", { state: "hidden", timeout: 10000 }).then(() => check(true, `${name}: no longer waiting once the device has it`), () => check(false, `${name}: no longer waiting once the device has it`));

  // Changed after sending, then another trip clicked: the page asks first, and keeps it as a draft.
  check(await page.isHidden("#save-draft"), `${name}: nothing to save right after sending`);
  await choose("surface", "MIXED");
  check(await page.isVisible("#save-draft"), `${name}: a change shows Save as draft`);
  await page.locator(".device").first().locator("ul.trips li .what", { hasText: "Helgetur" }).click();
  await page.waitForSelector("#unsaved[open]", { timeout: 5000 }).then(() => check(true, `${name}: opening another trip asks about the unsaved one`), () => check(false, `${name}: opening another trip asks about the unsaved one`));
  check(await fitsInside("#unsaved button[value=save]"), `${name}: Save as draft on screen in the question`);
  await page.screenshot({ path: `${out}/${name}-2b-unsaved.png` });
  await page.click("#unsaved button[value=save]");
  await page.waitForSelector("#drafts:not([hidden])", { timeout: 5000 });
  check((await page.$$eval("#drafts-list li", (e) => e.length)) === 1, `${name}: it is kept among the drafts`);
  await choose("surface", "PAVED");

  // Open the device's trip, change it, update it there.
  if ((await page.inputValue("#name")) !== "Helgetur") await page.locator(".device").first().locator("ul.trips li .what", { hasText: "Helgetur" }).click();
  check((await page.inputValue("#name")) === "Helgetur" && (await page.textContent("#send")).includes("Test Pixel") && (await page.textContent("#send")).includes("Test X7"), `${name}: the device's trip opened, Update names both devices with it (${await page.textContent("#send")})`);
  check((await page.$$eval("#stops li", (e) => e.length)) === 2, `${name}: its start and destination on the map`);
  await page.screenshot({ path: `${out}/${name}-3-opened.png` });
  await choose("style", "VERY_CURVY");
  await page.waitForFunction(() => !document.querySelector("#summary").hidden && !document.querySelector("#send-row").hidden, null, { timeout: 60000 });
  await page.locator("#send").scrollIntoViewIfNeeded();
  await page.click("#send");
  // Both devices: over the real relay the second one's copy may come a moment after the first one's.
  for (let i = 0; i < 50 && (dev.got.plans.length < 3 || !dev2.got.plans.some((p) => p.trip.ref?.r === dev2.me)); i++) await sleep(100);
  const update = dev.got.plans.find((p) => p.trip.ref?.r === dev.me);
  const copy = dev2.got.plans.find((p) => p.trip.ref?.r === dev2.me);
  check(copy?.trip.ref.id === 21 && copy.trip.options.style === "VERY_CURVY" && copy.tid !== update?.tid, `${name}: Update also changed the copy on the second device, as its own trip 21`);
  check(update?.trip.ref?.id === 11 && update.trip.ref.r === dev.me && update.trip.options.style === "VERY_CURVY", `${name}: Update reached the device as a change of its trip 11`);
  await page.locator("#send-new").scrollIntoViewIfNeeded();
  check(await fitsInside("#send-new"), `${name}: Save as a new trip on screen`);

  // A line without stops: shown, not changed, until a via point or stop is put on the line.
  await page.locator(".device ul.trips li .what", { hasText: "Kjørt tur" }).click();
  check(await page.isHidden("#send-row") && (await page.textContent("#hint")).length > 20, `${name}: a ride kept as a trip is shown only`);
  if (name === "desktop") {
    const onLine = await page.evaluate(() => {
      const m = window.curveonMap; const l = m.getStyle().sources.route.data.geometry.coordinates; const c = l[Math.floor(l.length / 2)];
      m.jumpTo({ center: c, zoom: 10 }); const q = m.project(c); const r = m.getCanvas().getBoundingClientRect(); return { x: r.left + q.x, y: r.top + q.y };
    });
    await page.waitForTimeout(800);
    await page.mouse.click(onLine.x, onLine.y);
    check(JSON.stringify(await menuItems()) === JSON.stringify(["Via point here", "Stop here"]), `desktop: a click on a shown line offers a via point or stop (${await menuItems()})`);
    await pick("Via point here");
    await page.waitForSelector("#summary:not([hidden])", { timeout: 60000 });
    check(await page.isVisible("#send-row") && (await page.$$eval(".pin", (e) => e.length)) >= 3, "desktop: with a via point on it, the line became a trip to change and send");
    await page.click("#new");
    if (await page.isVisible("#unsaved[open]")) await page.click("#unsaved button[value=discard]");
  }

  // Delete from the device.
  await page.locator(".device").first().locator("ul.trips li", { hasText: "Helgetur" }).locator("button").click();
  for (let i = 0; i < 50 && !dev.got.deletes.some((d) => d.id === 11); i++) await sleep(100);
  const gone = dev.got.deletes.find((d) => d.id === 11);
  check(gone?.r === dev.me, `${name}: delete reached the device`);
  check(!(await page.locator(".device").first().locator("ul.trips li", { hasText: "Helgetur" }).count()), `${name}: the deleted trip left the list`);
  await page.screenshot({ path: `${out}/${name}-4-after.png` });

  // A GPX file with several tracks (as the TET country downloads): each on its own on the map, never joined into one
  // line; one opened from the map or the list, all sent at once as GPX tracks, and the file closed by New.
  const SECTIONS = [["TET_N-01-Bømlo-Odda_20260827", [59.78, 5.20], [60.07, 6.55]], ["TET_N-02-Lom-Grotli_20260827", [61.86, 8.68], [61.81, 7.71]],
    ["TET_N-03-Lakselv-Nordkapp_20260827", [70.05, 24.97], [71.17, 25.78]]];
  const sectionLine = ([, a, b]) => Array.from({ length: 200 }, (_, k) => [a[0] + ((b[0] - a[0]) * k) / 199, a[1] + ((b[1] - a[1]) * k) / 199]);
  const multi = `<?xml version="1.0" encoding="UTF-8"?>\n<gpx version="1.1" creator="test" xmlns="http://www.topografix.com/GPX/1/1">
<metadata><name>TET_N_20260827</name></metadata><wpt lat="61.85" lon="8.5"><name>FUEL_Lom</name></wpt>
${SECTIONS.map((s) => `<trk><name>${s[0]}</name><trkseg>${sectionLine(s).map(([la, lo], k) => `<trkpt lat="${la.toFixed(6)}" lon="${lo.toFixed(6)}"><ele>${100 + k}</ele></trkpt>`).join("")}</trkseg></trk>`).join("\n")}
</gpx>`;
  if (await page.isVisible("#unsaved[open]")) await page.click("#unsaved button[value=discard]");
  await page.setInputFiles("#gpx-file", { name: "tet-test.gpx", mimeType: "application/gpx+xml", buffer: Buffer.from(multi) });
  await page.waitForTimeout(300);
  if (await page.isVisible("#unsaved[open]")) await page.click("#unsaved button[value=discard]");
  await page.waitForSelector("#file-block:not([hidden])", { timeout: 5000 }).catch(() => {});
  const fileState = () => page.evaluate(() => {
    const s = window.curveonMap.getStyle().sources;
    return { tracks: s["cv-file"].data.features.length, route: s.route.data.geometry?.coordinates || [],
      rows: [...document.querySelectorAll("#file-list li")].map((li) => li.querySelector("b").textContent), open: [...document.querySelectorAll("#file-list li")].findIndex((li) => li.classList.contains("open")),
      name: document.querySelector("#name").value };
  });
  let fs1 = await fileState();
  check(fs1.tracks === 3 && fs1.route.length === 0 && fs1.rows.join("|") === "TET N-01 Bømlo-Odda|TET N-02 Lom-Grotli|TET N-03 Lakselv-Nordkapp" &&
    (await page.$$eval(".pin", (e) => e.length)) === 0 && (await page.textContent("#file-name")) === "TET N",
    `${name}: a GPX file with several tracks shows each on its own, none opened yet (${fs1.tracks} on the map: ${fs1.rows.join(", ")})`);
  for (const sel of ["#file-send", "#file-close"]) { await page.locator(sel).scrollIntoViewIfNeeded(); check(await fitsInside(sel), `${name}: ${sel} is wholly on screen`); }
  await page.waitForFunction(() => !window.curveonMap.isMoving(), null, { timeout: 10000 }).catch(() => {});
  await page.screenshot({ path: `${out}/${name}-5-gpx-file.png` });
  // A click on the second track on the map opens it, with its own line only (nothing from the others). Clicked high on
  // the map: on a phone the panel covers its lower half.
  const onTrack = sectionLine(SECTIONS[1])[100];
  const at = await page.evaluate(([la, lo]) => {
    const m = window.curveonMap; const r = m.getCanvas().getBoundingClientRect();
    m.jumpTo({ center: [lo, la], zoom: 9 }); m.panBy([0, r.height / 2 - innerHeight * 0.25], { animate: false });
    const q = m.project([lo, la]); return { x: r.left + q.x, y: r.top + q.y };
  }, onTrack);
  await page.waitForTimeout(300);
  await page.mouse.click(at.x, at.y);
  await page.waitForTimeout(500);
  fs1 = await fileState();
  check(fs1.name === "TET N-02 Lom-Grotli" && fs1.open === 1 && fs1.route.length === 200 && Math.abs(fs1.route[0][1] - 61.86) < 1e-6 && Math.abs(fs1.route[199][1] - 61.81) < 1e-6,
    `${name}: a click on a track on the map opens just that track (${fs1.name}, ${fs1.route.length} points)`);
  // Unchanged, another one opens from the list without asking.
  await page.locator("#file-list li").nth(2).locator(".what").click();
  await page.waitForTimeout(300);
  fs1 = await fileState();
  check(!(await page.isVisible("#unsaved[open]")) && fs1.name === "TET N-03 Lakselv-Nordkapp" && fs1.open === 2 && fs1.tracks === 3,
    `${name}: another track opens from the list, without asking to save the one before (${fs1.name})`);
  const before0 = dev.got.plans.length;
  await page.locator("#file-send").scrollIntoViewIfNeeded();
  await page.click("#file-send");
  for (let i = 0; i < 50 && dev.got.plans.length < before0 + 3; i++) await sleep(100);
  const sent3 = dev.got.plans.slice(before0).map((p) => p.trip);
  check(sent3.length === 3 && sent3.every((p, i) => p.track && !p.destination && p.name === fs1.rows[i] && p.line.split(";").length === 200 &&
    Math.abs(Number(p.line.split(";")[0].split(",")[0]) - SECTIONS[i][1][0]) < 1e-4),
    `${name}: Send all sent each track as a GPX track of its own (${sent3.map((p) => p.name).join(", ")})`);
  await page.click("#new");
  if (await page.isVisible("#unsaved[open]")) await page.click("#unsaved button[value=discard]");
  fs1 = await fileState();
  check(await page.isHidden("#file-block") && fs1.tracks === 0, `${name}: New closes the file and takes its tracks off the map`);

  // A GPX file opened on the page: shown as it is, sent as a GPX track (no destination, no stops), or as both.
  if (await page.isVisible("#unsaved[open]")) await page.click("#unsaved button[value=discard]");
  page.once("filechooser", () => {});
  await page.setInputFiles("#gpx-file", process.env.GPX || `${out}/../gpxtest/fjellrunden.gpx`);
  await page.waitForFunction(() => document.querySelector("#name").value === "Fjellrunden", null, { timeout: 5000 }).catch(() => {});
  check((await page.inputValue("#name")) === "Fjellrunden" && (await page.inputValue("#send-as")) === "track" && (await page.$$eval(".pin", (e) => e.length)) >= 4,
    `${name}: a GPX file opens as it is, with points along it, to send as a track`);
  await page.screenshot({ path: `${out}/${name}-5-gpx.png` });
  const before = dev.got.plans.length;
  await page.selectOption("#send-as", "both");
  await page.locator("#send").scrollIntoViewIfNeeded();
  await page.click("#send");
  for (let i = 0; i < 50 && dev.got.plans.length < before + 2; i++) await sleep(100);
  const got = dev.got.plans.slice(before);
  const track = got.find((p) => p.trip.track), asTrip = got.find((p) => !p.trip.track);
  check(track && !track.trip.destination && track.trip.stops.length === 0 && track.trip.line.split(";").length > 100 && asTrip?.trip.destination && asTrip.tid !== track.tid,
    `${name}: sent as both, a GPX track and a trip (${got.map((p) => p.trip.name).join(", ")})`);

  if (name === "desktop") {
    // Each day as a trip: a via point of the GPX trip made a stop and a night, then sent a day at a time.
    await page.waitForFunction(() => !window.curveonMap.isMoving(), null, { timeout: 10000 });
    await page.locator(".pin.via").nth(1).click();
    await pick("Make it a stop");
    await page.locator(".pin.stop").click();
    await pick("Stay the night here");
    const before2 = dev.got.plans.length;
    await page.selectOption("#send-as", "days");
    await page.locator("#send").scrollIntoViewIfNeeded();
    await page.click("#send");
    for (let i = 0; i < 50 && dev.got.plans.length < before2 + 2; i++) await sleep(100);
    const daysGot = dev.got.plans.slice(before2).map((p) => p.trip);
    check(daysGot.length === 2 && daysGot[0].name.endsWith(" – day 1") && daysGot[1].name.endsWith(" – day 2") &&
      Math.abs(daysGot[0].destination.lat - daysGot[1].start.lat) < 1e-6 && daysGot.every((d) => d.line.split(";").length > 20),
      `desktop: each day went as a trip of its own (${daysGot.map((d) => `${d.name} ${Math.round(d.m / 1000)} km`).join(", ")})`);

    // A roundtrip: only a start, then draw round where to ride; back to the start through via points.
    await page.click("#new");
    if (await page.isVisible("#unsaved[open]")) await page.click("#unsaved button[value=discard]");
    await clickAt(59.7795, 5.2049);
    await pick("Start here");
    check(await page.isVisible("#loop"), "desktop: with only a start, Roundtrip is offered");
    await page.click("#loop");
    // While drawing, the middle button still moves the map (the left one draws).
    const before3 = await page.evaluate(() => window.curveonMap.getCenter().lng);
    await page.mouse.move(800, 450);
    await page.mouse.down({ button: "middle" });
    await page.mouse.move(700, 450, { steps: 5 });
    await page.mouse.up({ button: "middle" });
    const after3 = await page.evaluate(() => window.curveonMap.getCenter().lng);
    check(after3 > before3 && !(await page.$(".menu")), `desktop: the middle button moves the map while drawing (${before3.toFixed(3)} → ${after3.toFixed(3)})`);
    const c = await page.evaluate(() => { const m = window.curveonMap; m.jumpTo({ center: [5.45, 59.75], zoom: 9 }); const q = m.project([5.45, 59.75]); const r = m.getCanvas().getBoundingClientRect(); return { x: r.left + q.x, y: r.top + q.y }; });
    await page.waitForTimeout(500);
    await page.mouse.move(c.x + 150, c.y);
    await page.mouse.down();
    for (let k = 1; k <= 48; k++) await page.mouse.move(c.x + 150 * Math.cos((k / 48) * 2 * Math.PI), c.y + 110 * Math.sin((k / 48) * 2 * Math.PI));
    await page.mouse.up();
    const t1 = Date.now();
    await page.waitForTimeout(300);
    check(await page.isVisible("#adv-busy") && await page.isVisible("#busy"), `desktop: while the roundtrip is looked for, the map and the box show it is working (${await page.textContent("#busy")})`);
    await page.waitForSelector("#adv-again:not([hidden])", { timeout: 150000 }).catch(() => {});
    const pins = await page.$$eval(".pin", (e) => e.length);
    const ends = await page.evaluate(() => { const l = window.curveonMap.getStyle().sources.route.data.geometry?.coordinates || []; return l.length ? [l[0], l[l.length - 1]] : null; });
    check(pins >= 6 && ends && Math.hypot(ends[0][0] - ends[1][0], ends[0][1] - ends[1][1]) < 0.01,
      `desktop: a roundtrip came back to the start with ${pins - 2} via points in ${((Date.now() - t1) / 1000).toFixed(0)} s (${await page.textContent("#adv-hint")})`);
    await page.screenshot({ path: `${out}/${name}-6-roundtrip.png` });
    check(await page.isHidden("#busy"), "desktop: the working chip goes when the roundtrip is there");
    // A click on the roundtrip's line offers a via point there.
    const mid2 = await page.evaluate(() => {
      const m = window.curveonMap; const l = m.getStyle().sources.route.data.geometry.coordinates; const c = l[Math.floor(l.length / 3)];
      m.jumpTo({ center: c, zoom: 11 }); const q = m.project(c); const r = m.getCanvas().getBoundingClientRect(); return { x: r.left + q.x, y: r.top + q.y };
    });
    await page.waitForTimeout(800);
    const pinsBefore = await page.$$eval(".pin", (e) => e.length);
    await page.mouse.click(mid2.x, mid2.y);
    check((await menuItems()).includes("Via point here"), `desktop: a click on the roundtrip offers a via point (${await menuItems()})`);
    await pick("Via point here");
    check((await page.$$eval(".pin", (e) => e.length)) === pinsBefore + 1, "desktop: the via point went on the roundtrip");

    // Working forward without a destination: a via point clicked between two places goes between them, not last.
    await page.click("#new");
    if (await page.isVisible("#unsaved[open]")) await page.click("#unsaved button[value=discard]");
    await clickAt(59.7795, 5.2049);
    await pick("Start here");
    await clickAt(60.0689, 6.5462);
    await pick("Stop here");
    await page.waitForSelector("#summary:not([hidden])", { timeout: 60000 });
    const order = () => page.$$eval("#stops li", (e) => e.map((x) => x.className.split(" ")[0]).join(","));
    await clickAt(59.95, 5.95);
    const offered = await menuItems();
    check(offered.some((x) => x.startsWith("Via point between")), `desktop: a point near the way between two places offers to go between them (${offered})`);
    await pick("Via point here");
    check((await order()) === "start,stop,via", `desktop: Via point here goes on from the last place (${await order()})`);
    await page.click("#undo");
    await clickAt(59.95, 5.95);
    await pick("Via point between");
    check((await order()) === "start,via,stop", `desktop: Via point between puts it between them (${await order()})`);
    await page.waitForSelector("#summary:not([hidden])", { timeout: 60000 });

    // Disconnect: this browser forgets the connection; the devices are gone from the page.
    await page.locator("#unpair").scrollIntoViewIfNeeded();
    await page.click("#unpair");
    check(await page.isHidden("#devices") && (await page.textContent("#pair")) === "Show the QR code" &&
      (await page.evaluate(() => localStorage.getItem("curveon.link"))) === null, "desktop: Disconnect forgets the connection and shows Connect again");
    await page.screenshot({ path: `${out}/${name}-7-disconnected.png` });
  }

  dev.close();
  dev2.close();
  // Photon (place names) turning a burst of lookups away, or not answering after many test runs, is theirs, not the
  // page's: the page goes on without the name ("Map point").
  const own = errors.filter((e) => !/photon\.komoot\.io.*Status code: (429|5\d\d|\(null\))/.test(e));
  check(own.length === 0, `${name}: no errors on the page ${own.join(" | ")}`);
  await page.close();
}
await browser.close();
process.exit(failed ? 1 : 0);
