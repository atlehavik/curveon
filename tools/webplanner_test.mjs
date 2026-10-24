// Drives the web planner in a real browser (Playwright, Firefox) against a planner service and a relay, and plays a
// device in the session with webplanner/web/webplan.js: it shows its planned trips, the page opens one, changes and
// updates it, deletes one, and sends a trip drawn before connecting. Screenshots go to OUT.
//   PLAYWRIGHT_DIR=<folder with node_modules/playwright> node tools/webplanner_test.mjs http://localhost:18788/plan/ localhost:18787 OUT
import { createRequire } from "node:module";
import { b64, channel, parseLink, random } from "../webplanner/web/webplan.js";

const { firefox } = createRequire((process.env.PLAYWRIGHT_DIR || process.cwd()) + "/")("playwright");
const [base, relay, out = "."] = process.argv.slice(2);
const browser = await firefox.launch();
let failed = false;
const check = (ok, what) => { console.log(`${ok ? "ok  " : "FAIL"} ${what}`); if (!ok) failed = true; };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** A device in the session: what it got from the page, and frames it sends (sealed as WebPlan.kt does). */
async function device(link, name) {
  const ch = await channel(link, /^(localhost|127\.)/.test(relay) ? "ws" : "wss");
  const me = random(16);
  const ws = new WebSocket(ch.url);
  const got = { plans: [], deletes: [] };
  ws.onmessage = async (ev) => {
    const m = await ch.open(ev.data);
    if (m?.type === "plan") got.plans.push(m.body);
    if (m?.type === "plan_del") got.deletes.push(m.body.ref);
  };
  // A relay that turns the connection down (e.g. too many new groups from one address) fails the test, not hangs it.
  await new Promise((r, j) => { ws.onopen = r; ws.onerror = () => j(new Error("the relay refused the device")); setTimeout(() => j(new Error("the relay did not answer")), 10000); });
  let seq = 0;
  const send = async (type, body, slot) => ws.send(await ch.seal(type, me, { seq: ++seq, ts: Date.now(), ...body }, slot));
  return {
    me: b64(me), got, close: () => ws.close(),
    /** Its planned trips, as the app publishes them: each one, then the list. */
    async show(trips) {
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

  // With a route: a third click offers stop, via point and new destination; a click on a point offers to change or remove it.
  if (name === "phone") await page.click("#fold");
  await clickAt(60.39, 5.32);
  check((await menuItems()).length === 3, `${name}: a third click offers stop, via point, new destination (${await menuItems()})`);
  if (name === "desktop") await page.screenshot({ path: `${out}/${name}-0-menu.png` });
  await pick(name === "phone" ? "Stopp her" : "Stop here");
  check((await page.$$eval("#stops li", (e) => e.length)) === 3 && (await page.$$eval("#stops li", (e) => e[1].className)) === "stop", `${name}: the stop went in between start and destination`);
  await page.locator(".pin.stop").click();
  check((await menuItems()).length === 2, `${name}: a click on a stop offers via point and remove (${await menuItems()})`);
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
    await page.waitForTimeout(1500);
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
    check(await page.isVisible("#adv-box") && (await page.textContent("#adv-hint")).length > 20, "desktop: Adventure asks for an area to be drawn");
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
  await page.selectOption("#surface", "MIXED");
  check(await page.isVisible("#save-draft"), `${name}: a change shows Save as draft`);
  await page.locator(".device").first().locator("ul.trips li .what", { hasText: "Helgetur" }).click();
  await page.waitForSelector("#unsaved[open]", { timeout: 5000 }).then(() => check(true, `${name}: opening another trip asks about the unsaved one`), () => check(false, `${name}: opening another trip asks about the unsaved one`));
  check(await fitsInside("#unsaved button[value=save]"), `${name}: Save as draft on screen in the question`);
  await page.screenshot({ path: `${out}/${name}-2b-unsaved.png` });
  await page.click("#unsaved button[value=save]");
  await page.waitForSelector("#drafts:not([hidden])", { timeout: 5000 });
  check((await page.$$eval("#drafts-list li", (e) => e.length)) === 1, `${name}: it is kept among the drafts`);
  await page.selectOption("#surface", "PAVED");

  // Open the device's trip, change it, update it there.
  if ((await page.inputValue("#name")) !== "Helgetur") await page.locator(".device").first().locator("ul.trips li .what", { hasText: "Helgetur" }).click();
  check((await page.inputValue("#name")) === "Helgetur" && (await page.textContent("#send")).includes("Test Pixel") && (await page.textContent("#send")).includes("Test X7"), `${name}: the device's trip opened, Update names both devices with it (${await page.textContent("#send")})`);
  check((await page.$$eval("#stops li", (e) => e.length)) === 2, `${name}: its start and destination on the map`);
  await page.screenshot({ path: `${out}/${name}-3-opened.png` });
  await page.selectOption("#style", "VERY_CURVY");
  await page.waitForFunction(() => !document.querySelector("#summary").hidden && !document.querySelector("#send-row").hidden, null, { timeout: 60000 });
  await page.locator("#send").scrollIntoViewIfNeeded();
  await page.click("#send");
  for (let i = 0; i < 50 && dev.got.plans.length < 3; i++) await sleep(100);
  const update = dev.got.plans.find((p) => p.trip.ref?.r === dev.me);
  const copy = dev2.got.plans.find((p) => p.trip.ref?.r === dev2.me);
  check(copy?.trip.ref.id === 21 && copy.trip.options.style === "VERY_CURVY" && copy.tid !== update?.tid, `${name}: Update also changed the copy on the second device, as its own trip 21`);
  check(update?.trip.ref?.id === 11 && update.trip.ref.r === dev.me && update.trip.options.style === "VERY_CURVY", `${name}: Update reached the device as a change of its trip 11`);
  await page.locator("#send-new").scrollIntoViewIfNeeded();
  check(await fitsInside("#send-new"), `${name}: Save as a new trip on screen`);

  // A line without stops: shown, not changed.
  await page.locator(".device ul.trips li .what", { hasText: "Kjørt tur" }).click();
  check(await page.isHidden("#send-row") && (await page.textContent("#hint")).length > 20, `${name}: a ride kept as a trip is shown only`);

  // Delete from the device.
  await page.locator(".device").first().locator("ul.trips li", { hasText: "Helgetur" }).locator("button").click();
  for (let i = 0; i < 50 && !dev.got.deletes.length; i++) await sleep(100);
  check(dev.got.deletes[0]?.id === 11 && dev.got.deletes[0].r === dev.me, `${name}: delete reached the device`);
  check(!(await page.locator(".device").first().locator("ul.trips li", { hasText: "Helgetur" }).count()), `${name}: the deleted trip left the list`);
  await page.screenshot({ path: `${out}/${name}-4-after.png` });

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

  dev.close();
  dev2.close();
  check(errors.length === 0, `${name}: no errors on the page ${errors.join(" | ")}`);
  await page.close();
}
await browser.close();
process.exit(failed ? 1 : 0);
