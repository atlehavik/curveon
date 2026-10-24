// Curveon's web planner: plan a trip on a big screen and send it to the app (docs: webplanner/README.md).
// The route comes from the planner service (the app's own routing), the trip goes to the app end-to-end encrypted
// through the relay (webplan.js). Nothing is stored on a server but the sealed trip, until the app has fetched it.
import { b64, channel, linkUri, newLink, parseLink, random, unb64 } from "./webplan.js";

// ---------- Texts (English and Norwegian, as in the app) ----------
const TEXTS = {
  en: {
    title: "Curveon planner", search_hint: "Search for a place, or click the map", lang: "Norsk",
    hint_empty: "Click the map or search to set where you start. Every place you add after that becomes where you are going, and the one before it a stop. Drag the line to make it take another road.",
    opt_style: "Roads", opt_surface: "Surface", opt_ferries: "Ferries", opt_motorways: "Motorways",
    style_fastest: "Fastest", style_curvy: "Curvy", style_super_curvy: "Super curvy",
    surface_tarmac_only: "Tarmac only", surface_mixed: "Mixed", surface_gravel: "Gravel", surface_offroad: "Offroad",
    ferries_ok: "Ferries OK", no_ferries: "No ferries", motorways_ok: "Motorways OK", no_motorways: "No motorways",
    no_motorways_e_roads: "No motorways or E-roads",
    start: "Start", destination: "Destination", stop: "Stop", via: "Through here", map_point: "Map point",
    remove: "Remove", up: "Earlier", down: "Later",
    km: "km", h: "h", min: "min", curvy: "curvy", up_m: "m climb", ferries_n: (n) => n === 1 ? "1 ferry" : `${n} ferries`,
    routing: "Finding the road…", queued: "Busy right now, your route is next in line…",
    err_busy: "The planner is busy. Try again in a moment.", err_no_route: "No road found between these places. Move a point onto a road.",
    err_too_long: "That is too long for one trip (over 1500 km). Split it into days.", err_slow_down: "That was a lot of routes at once. Wait a few minutes.",
    err_failed: "The route could not be worked out. Try again.", err_offline: "No connection to the planner. Check your internet connection.",
    name_hint: "Name of the trip", send: "Send to Curveon", resend: "Send changes",
    sending: "Sending…", sent_ok: "Sent. It shows under Planned in Curveon the next time the app is online.",
    send_failed: "The trip could not be sent. Check your internet connection and try again.",
    sent_title: "Sent to Curveon", waiting: "Waiting for Curveon to fetch it", got_on: (d) => `In Curveon on ${d}`,
    changed: "changed since", forget: "Remove from this list", edit: "Open to change it",
    new_trip: "New trip", show_code: "Code for the app",
    pair_title: "Connect Curveon to this page", pair_step1: "Open Curveon on your phone or tablet.",
    pair_step2: "Go to Planned and tap From the web.", pair_step3: "Point the camera at this code.",
    pair_more: "Do it once per phone or tablet. Each one that has scanned the code gets every trip you send from this browser.",
    pair_done: "Done", confirm_new: "Start a new trip? The one on the map is not sent yet.",
    attribution: "Routes: Curveon (BRouter) · Search: Photon, Kartverket",
  },
  nb: {
    title: "Curveon-planlegger", search_hint: "Søk etter et sted, eller klikk i kartet", lang: "English",
    hint_empty: "Klikk i kartet eller søk for å velge hvor du starter. Hvert sted du legger til etter det blir målet, og det forrige blir et stopp. Dra i linjen for å få den til å ta en annen vei.",
    opt_style: "Veier", opt_surface: "Underlag", opt_ferries: "Ferjer", opt_motorways: "Motorvei",
    style_fastest: "Raskest", style_curvy: "Svingete", style_super_curvy: "Supersvingete",
    surface_tarmac_only: "Kun asfalt", surface_mixed: "Blandet", surface_gravel: "Grus", surface_offroad: "Offroad",
    ferries_ok: "Ferjer OK", no_ferries: "Ingen ferjer", motorways_ok: "Motorvei OK", no_motorways: "Ingen motorvei",
    no_motorways_e_roads: "Ingen motorvei eller E-vei",
    start: "Start", destination: "Mål", stop: "Stopp", via: "Gjennom her", map_point: "Kartpunkt",
    remove: "Fjern", up: "Tidligere", down: "Senere",
    km: "km", h: "t", min: "min", curvy: "svingete", up_m: "m stigning", ferries_n: (n) => n === 1 ? "1 ferje" : `${n} ferjer`,
    routing: "Finner veien …", queued: "Travelt akkurat nå, ruten din står først i køen …",
    err_busy: "Planleggeren har mye å gjøre. Prøv igjen om litt.", err_no_route: "Fant ingen vei mellom disse stedene. Flytt et punkt inn på en vei.",
    err_too_long: "Det er for langt for én tur (over 1500 km). Del den opp i dager.", err_slow_down: "Det ble mange ruter på kort tid. Vent noen minutter.",
    err_failed: "Ruten kunne ikke regnes ut. Prøv igjen.", err_offline: "Får ikke kontakt med planleggeren. Sjekk internettforbindelsen.",
    name_hint: "Navn på turen", send: "Send til Curveon", resend: "Send endringene",
    sending: "Sender …", sent_ok: "Sendt. Den dukker opp under Planlagte i Curveon neste gang appen er på nett.",
    send_failed: "Turen ble ikke sendt. Sjekk internettforbindelsen og prøv igjen.",
    sent_title: "Sendt til Curveon", waiting: "Venter på at Curveon henter den", got_on: (d) => `I Curveon på ${d}`,
    changed: "endret siden", forget: "Fjern fra listen", edit: "Åpne for å endre",
    new_trip: "Ny tur", show_code: "Kode til appen",
    pair_title: "Koble Curveon til denne siden", pair_step1: "Åpne Curveon på telefonen eller nettbrettet.",
    pair_step2: "Gå til Planlagte og trykk Fra nettet.", pair_step3: "Rett kameraet mot denne koden.",
    pair_more: "Det gjør du én gang per telefon eller nettbrett. Alle som har skannet koden får hver tur du sender fra denne nettleseren.",
    pair_done: "Ferdig", confirm_new: "Begynne på en ny tur? Den på kartet er ikke sendt ennå.",
    attribution: "Ruter: Curveon (BRouter) · Søk: Photon, Kartverket",
  },
};

// ---------- Small helpers ----------
const $ = (id) => document.getElementById(id);
const store = {
  get(key, fallback) { try { const v = localStorage.getItem("curveon." + key); return v === null ? fallback : JSON.parse(v); } catch { return fallback; } },
  set(key, value) { try { localStorage.setItem("curveon." + key, JSON.stringify(value)); } catch { /* private window: works until the tab closes */ } },
};
const params = new URLSearchParams(location.search);
// The relay is the host the page comes from (relay.curveon.no/plan); ?relay=localhost:8787 when trying it on a PC.
const RELAY = params.get("relay") || location.host;
const WS_SCHEME = params.get("relay") || location.protocol === "http:" ? "ws" : "wss";
const BASE = location.pathname.replace(/[^/]*$/, ""); // "/plan/"
const API = BASE + "api/route";

let lang = store.get("lang", /^(nb|nn|no)\b/i.test(navigator.language) ? "nb" : "en");
const t = (key, ...a) => { const v = TEXTS[lang][key] ?? TEXTS.en[key] ?? key; return typeof v === "function" ? v(...a) : v; };

function applyTexts() {
  document.documentElement.lang = lang === "nb" ? "nb" : "en";
  document.title = t("title");
  document.querySelectorAll("[data-t]").forEach((el) => { el.textContent = t(el.dataset.t); });
  document.querySelectorAll("[data-tp]").forEach((el) => { el.placeholder = t(el.dataset.tp); });
  $("lang").textContent = t("lang");
  $("fold").textContent = $("panel").classList.contains("folded") ? "▴" : "▾";
}

// ---------- State ----------
// points: [{ name, lat, lon, via }] start first, destination last. editing: the sent trip being changed, if any.
let points = [];
let result = null; // the planner's answer for these points and options
let editing = null; // { tid, rev }
let options = Object.assign({ style: "CURVY", surface: "PAVED", ferries: false, motorways: false, trunk: false }, store.get("options", {}));

// ---------- Map ----------
const dark = matchMedia("(prefers-color-scheme: dark)").matches;
const protocol = new pmtiles.Protocol();
maplibregl.addProtocol("pmtiles", protocol.tile);

async function loadStyle() {
  const style = await (await fetch(`${BASE}map/styles/${dark ? "dark" : "light"}.json`)).json();
  const abs = location.origin + BASE + "map/";
  style.sources.protomaps.url = "pmtiles://" + abs + "norway.pmtiles";
  style.glyphs = abs + "fonts/{fontstack}/{range}.pbf";
  style.sprite = abs + "sprites/" + (dark ? "dark" : "light");
  // Beyond the Norway map there is only background: the sea's colour, so the edge of the map doesn't show.
  const water = style.layers.find((l) => l.id === "water")?.paint?.["fill-color"];
  const bg = style.layers.find((l) => l.id === "background");
  if (bg && typeof water === "string") bg.paint = { ...bg.paint, "background-color": water };
  return style;
}

const map = new maplibregl.Map({
  container: "map", style: await loadStyle(), center: [8.5, 61.2], zoom: 5.2, minZoom: 3.5, maxZoom: 18,
  attributionControl: { compact: true, customAttribution: t("attribution") }, hash: false,
});
if (params.has("debug")) window.curveonMap = map; // for tools/webplanner_test.mjs
map.addControl(new maplibregl.NavigationControl({ showCompass: false }), "top-right");
map.addControl(new maplibregl.GeolocateControl({ positionOptions: { enableHighAccuracy: false }, trackUserLocation: false }), "top-right");

map.on("load", () => {
  map.addSource("route", { type: "geojson", data: emptyLine() });
  // Under the place names, which stay on top of every line (as in the app).
  const firstName = map.getStyle().layers.find((l) => l.id.startsWith("places_"))?.id;
  map.addLayer({ id: "route-casing", type: "line", source: "route", layout: { "line-join": "round", "line-cap": "round" },
    paint: { "line-color": "#5a3000", "line-width": ["interpolate", ["linear"], ["zoom"], 5, 5, 12, 10, 16, 14] } }, firstName);
  map.addLayer({ id: "route", type: "line", source: "route", layout: { "line-join": "round", "line-cap": "round" },
    paint: { "line-color": "#ff9100", "line-width": ["interpolate", ["linear"], ["zoom"], 5, 3, 12, 6, 16, 9] } }, firstName);
  map.addLayer({ id: "route-hit", type: "line", source: "route", paint: { "line-color": "#000", "line-opacity": 0, "line-width": 22 } }, firstName);
  drawRoute();
});

const emptyLine = () => ({ type: "FeatureCollection", features: [] });

function drawRoute() {
  const src = map.getSource("route");
  if (!src) return;
  src.setData(result ? { type: "Feature", geometry: { type: "LineString", coordinates: result.line.map(([la, lo]) => [lo, la]) } } : emptyLine());
}

// ---------- Markers ----------
let markers = [];
function kindOf(i) { return i === 0 ? "start" : i === points.length - 1 ? "end" : points[i].via ? "via" : "stop"; }
function stopNumber(i) { let n = 0; for (let k = 1; k <= i; k++) if (!points[k].via && k < points.length - 1) n++; return n; }

function drawMarkers() {
  markers.forEach((m) => m.remove());
  markers = points.map((p, i) => {
    const el = document.createElement("div");
    const kind = kindOf(i);
    el.className = "pin " + kind;
    el.textContent = kind === "start" ? "A" : kind === "end" ? "B" : kind === "stop" ? String(stopNumber(i)) : "";
    el.title = p.name || t(kind === "via" ? "via" : "map_point");
    const m = new maplibregl.Marker({ element: el, draggable: true }).setLngLat([p.lon, p.lat]).addTo(map);
    m.on("dragstart", () => { draggingLine = true; });
    m.on("dragend", async () => {
      const ll = m.getLngLat();
      points[i] = { ...points[i], lat: ll.lat, lon: ll.lng };
      setTimeout(() => { draggingLine = false; }, 50);
      changed();
      if (!points[i].via) { points[i].name = await placeName(ll.lat, ll.lng); drawList(); }
    });
    // A click on a through-point removes it: it was only there to bend the line.
    el.addEventListener("click", (e) => { e.stopPropagation(); if (points[i]?.via) { points.splice(i, 1); changed(); } });
    return m;
  });
}

// ---------- The list of places ----------
function drawList() {
  const ol = $("stops");
  ol.replaceChildren();
  points.forEach((p, i) => {
    const kind = kindOf(i);
    const li = document.createElement("li");
    li.className = kind;
    const dot = document.createElement("span");
    dot.className = "dot";
    dot.textContent = kind === "start" ? "A" : kind === "end" ? "B" : kind === "stop" ? String(stopNumber(i)) : "";
    const name = document.createElement("span");
    name.className = "name";
    name.textContent = kind === "via" ? t("via") : p.name || t("map_point");
    name.title = name.textContent;
    li.append(dot, name);
    const btn = (label, title, fn) => { const b = document.createElement("button"); b.type = "button"; b.textContent = label; b.title = title; b.ariaLabel = title; b.onclick = fn; li.append(b); };
    if (i > 0) btn("↑", t("up"), () => { [points[i - 1], points[i]] = [points[i], points[i - 1]]; changed(); });
    if (i < points.length - 1) btn("↓", t("down"), () => { [points[i + 1], points[i]] = [points[i], points[i + 1]]; changed(); });
    btn("✕", t("remove"), () => { points.splice(i, 1); changed(); });
    li.querySelector(".name").onclick = () => map.easeTo({ center: [p.lon, p.lat], zoom: Math.max(map.getZoom(), 11) });
    ol.append(li);
  });
  $("hint").hidden = points.length >= 2;
}

function addPlace(p) {
  points.push({ name: p.name, lat: p.lat, lon: p.lon, via: false });
  changed();
}

// ---------- Search ----------
let searchSeq = 0;
let hits = [];
let picked = -1;

async function search(q) {
  const seq = ++searchSeq;
  if (q.trim().length < 2) return showHits([]);
  const c = map.getCenter();
  const photon = fetch(`https://photon.komoot.io/api/?q=${encodeURIComponent(q)}&limit=8&lat=${c.lat}&lon=${c.lng}`)
    .then((r) => r.json()).then((j) => j.features.map((f) => {
      const p = f.properties;
      const name = p.name || (p.street ? `${p.street}${p.housenumber ? " " + p.housenumber : ""}` : null);
      return name && { name, area: p.city || p.county || p.country || "", lat: f.geometry.coordinates[1], lon: f.geometry.coordinates[0] };
    }).filter(Boolean)).catch(() => []);
  const names = fetch(`https://ws.geonorge.no/stedsnavn/v1/navn?sok=${encodeURIComponent(q.trim() + "*")}&treffPerSide=8&utkoordsys=4258`)
    .then((r) => r.json()).then((j) => (j.navn || []).map((n) => n.representasjonspunkt && {
      name: n.skrivemåte, area: n.kommuner?.[0]?.kommunenavn || "", lat: n.representasjonspunkt.nord, lon: n.representasjonspunkt["øst"],
    }).filter(Boolean)).catch(() => []);
  const [a, b] = await Promise.all([photon, names]);
  if (seq !== searchSeq) return;
  const all = [];
  for (const h of [...a, ...b]) if (!all.some((x) => x.name === h.name && Math.abs(x.lat - h.lat) + Math.abs(x.lon - h.lon) < 0.01)) all.push(h);
  showHits(all.slice(0, 10));
}

function showHits(list) {
  hits = list;
  picked = list.length ? 0 : -1;
  const ul = $("hits");
  ul.replaceChildren(...list.map((h, i) => {
    const li = document.createElement("li");
    li.role = "option";
    li.ariaSelected = String(i === picked);
    const b = document.createElement("span"); b.textContent = h.name;
    const s = document.createElement("small"); s.textContent = h.area;
    li.append(b, s);
    li.onmousedown = (e) => { e.preventDefault(); choose(i); };
    return li;
  }));
}

function choose(i) {
  const h = hits[i];
  if (!h) return;
  $("q").value = "";
  showHits([]);
  addPlace(h);
  map.easeTo({ center: [h.lon, h.lat], zoom: Math.max(map.getZoom(), 9) });
}

let searchTimer;
$("q").addEventListener("input", (e) => { clearTimeout(searchTimer); searchTimer = setTimeout(() => search(e.target.value), 250); });
$("q").addEventListener("keydown", (e) => {
  if (e.key === "ArrowDown" || e.key === "ArrowUp") {
    e.preventDefault();
    picked = Math.max(0, Math.min(hits.length - 1, picked + (e.key === "ArrowDown" ? 1 : -1)));
    [...$("hits").children].forEach((li, i) => { li.ariaSelected = String(i === picked); });
  } else if (e.key === "Enter") { e.preventDefault(); choose(picked); }
  else if (e.key === "Escape") showHits([]);
});
$("q").addEventListener("blur", () => setTimeout(() => showHits([]), 150));

/** A name for a point clicked on the map: the nearest place Photon knows, else "Map point". */
async function placeName(lat, lon) {
  try {
    const j = await (await fetch(`https://photon.komoot.io/reverse?lat=${lat}&lon=${lon}&limit=1`)).json();
    const p = j.features?.[0]?.properties;
    return p?.name || p?.street || p?.city || p?.county || t("map_point");
  } catch {
    return t("map_point");
  }
}

// ---------- Clicking and dragging on the map ----------
let draggingLine = false;
map.on("click", async (e) => {
  if (draggingLine) return;
  const p = { name: t("map_point"), lat: e.lngLat.lat, lon: e.lngLat.lng };
  addPlace(p);
  const i = points.length - 1;
  const name = await placeName(p.lat, p.lon);
  if (points[i] && points[i].lat === p.lat) { points[i].name = name; drawList(); drawMarkers(); }
});

// Drag the line: a through-point goes in where it was grabbed, on the leg it belongs to.
map.on("mouseenter", "route-hit", () => { map.getCanvas().style.cursor = "grab"; });
map.on("mouseleave", "route-hit", () => { map.getCanvas().style.cursor = ""; });
map.on("mousedown", "route-hit", (e) => startLineDrag(e));
map.on("touchstart", "route-hit", (e) => { if (e.points.length === 1) startLineDrag(e); });

function startLineDrag(e) {
  if (!result) return;
  e.preventDefault();
  draggingLine = true;
  const leg = legAt(e.lngLat);
  const el = document.createElement("div");
  el.className = "pin via";
  const ghost = new maplibregl.Marker({ element: el }).setLngLat(e.lngLat).addTo(map);
  const move = (ev) => ghost.setLngLat(ev.lngLat);
  const up = () => {
    map.off("mousemove", move); map.off("touchmove", move);
    const ll = ghost.getLngLat();
    ghost.remove();
    points.splice(leg + 1, 0, { name: "", lat: ll.lat, lon: ll.lng, via: true });
    changed();
    setTimeout(() => { draggingLine = false; }, 50);
  };
  map.on("mousemove", move); map.on("touchmove", move);
  map.once("mouseup", up); map.once("touchend", up);
}

/** Which leg (from points[i] to points[i + 1]) the line is at [ll]: where the nearest line point falls between the places. */
function legAt(ll) {
  const line = result.line;
  const d = (a, b) => (a[0] - b[0]) ** 2 + ((a[1] - b[1]) * Math.cos((a[0] * Math.PI) / 180)) ** 2;
  const nearest = (q, from) => { let best = from, bd = Infinity; for (let k = from; k < line.length; k++) { const v = d(line[k], q); if (v < bd) { bd = v; best = k; } } return best; };
  const at = nearest([ll.lat, ll.lng], 0);
  let from = 0;
  for (let i = 1; i < points.length - 1; i++) {
    from = nearest([points[i].lat, points[i].lon], from);
    if (from > at) return i - 1;
  }
  return points.length - 2;
}

// ---------- Routing ----------
let routeCtl = null;
let routeTimer;

function changed() {
  drawList();
  drawMarkers();
  result = null;
  drawRoute();
  drawSummary();
  clearTimeout(routeTimer);
  routeTimer = setTimeout(route, 200);
}

function setStatus(text, error = false) {
  $("status").textContent = text || "";
  $("status").classList.toggle("error", error);
}

async function route() {
  routeCtl?.abort();
  if (points.length < 2) { setStatus(""); return; }
  const ctl = (routeCtl = new AbortController());
  setStatus(t("routing"));
  const slow = setTimeout(() => { if (routeCtl === ctl) setStatus(t("queued")); }, 6000);
  try {
    const r = await fetch(API, {
      method: "POST", signal: ctl.signal, headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ points: points.map((p) => [p.lat, p.lon]), options: routeOptions() }),
    });
    const j = await r.json().catch(() => ({ error: "failed" }));
    if (routeCtl !== ctl) return;
    if (!r.ok) { setStatus(t("err_" + (j.error || "failed")), true); return; }
    result = j;
    setStatus("");
    drawRoute();
    drawSummary();
  } catch (e) {
    if (e.name !== "AbortError" && routeCtl === ctl) setStatus(t("err_offline"), true);
  } finally {
    clearTimeout(slow);
  }
}

function routeOptions() {
  return { style: options.style, surface: options.surface, ferries: options.ferries, motorways: options.motorways, trunk: options.trunk, toll: false };
}

function drawSummary() {
  const s = $("summary");
  $("send-row").hidden = !result;
  if (!result) { s.hidden = true; return; }
  const h = Math.floor(result.s / 3600), m = Math.round((result.s % 3600) / 60);
  const part = (v, unit) => { const e = document.createElement("span"); e.textContent = v; const u = document.createElement("small"); u.textContent = unit; e.append(u); return e; };
  s.replaceChildren(
    part(Math.round(result.m / 1000), t("km")),
    ...(h ? [part(h, t("h")), part(m, t("min"))] : [part(m, t("min"))]),
    part(`${result.curvy}/100`, t("curvy")),
    part(result.up, t("up_m")),
    ...(result.ferries ? [Object.assign(document.createElement("span"), { textContent: t("ferries_n", result.ferries) })] : []),
  );
  s.hidden = false;
  if (!$("name").value) $("name").placeholder = defaultName();
  $("send").textContent = editing ? t("resend") : t("send");
}

const defaultName = () => points.length >= 2 ? `${points[0].name || t("map_point")} – ${points[points.length - 1].name || t("map_point")}` : t("name_hint");

// Options, as the app keeps them.
function readOptions() {
  options = {
    style: $("style").value, surface: $("surface").value, ferries: $("ferries").value === "no",
    motorways: $("motorways").value !== "ok", trunk: $("motorways").value === "noe",
  };
  store.set("options", options);
  changed();
}
function showOptions() {
  $("style").value = options.style;
  $("surface").value = options.surface;
  $("ferries").value = options.ferries ? "no" : "ok";
  $("motorways").value = options.trunk ? "noe" : options.motorways ? "no" : "ok";
}
["style", "surface", "ferries", "motorways"].forEach((id) => $(id).addEventListener("change", readOptions));

// ---------- The channel to the app ----------
let link = store.get("link", null) && parseLink(store.get("link"));
const rider = unb64(store.get("rider", null) || (() => { const r = b64(random(16)); store.set("rider", r); return r; })());
const nextSeq = () => { const s = store.get("seq", 0) + 1; store.set("seq", s); return s; };
let ch = null;
let socket = null;
let backoff = 1000;

async function ensureLink() {
  if (!link) { link = newLink(RELAY); store.set("link", linkUri(link)); }
  if (!ch) ch = await channel(link, WS_SCHEME);
  return ch;
}

/** Stays connected while the page is open, to hear the app say it has a trip. */
async function connect() {
  if (!link || socket) return;
  const c = await ensureLink();
  const ws = (socket = new WebSocket(c.url));
  ws.onopen = () => { backoff = 1000; };
  ws.onmessage = async (ev) => {
    const m = await c.open(ev.data);
    if (m?.type !== "plan_got") return;
    const list = store.get("sent", []);
    const s = list.find((x) => x.tid === m.body.tid);
    if (!s) return;
    s.got = Object.assign(s.got || {}, { [m.body.dev || "?"]: m.body.rev });
    store.set("sent", list);
    drawSent();
  };
  ws.onclose = () => {
    socket = null;
    if (document.visibilityState === "visible") setTimeout(connect, (backoff = Math.min(backoff * 2, 60000)));
  };
}
document.addEventListener("visibilitychange", () => { if (document.visibilityState === "visible") connect(); });

/** Sends one frame on a connection of its own if need be, and says whether it went. */
async function sendFrame(frame) {
  await connect();
  for (let i = 0; i < 50 && socket?.readyState === WebSocket.CONNECTING; i++) await new Promise((r) => setTimeout(r, 100));
  if (socket?.readyState !== WebSocket.OPEN) return false;
  socket.send(frame);
  return true;
}

// ---------- Sending ----------
$("send").addEventListener("click", async () => {
  if (!result) return;
  const first = !store.get("link", null);
  await ensureLink();
  if (first) await showPairing();
  const name = $("name").value.trim() || defaultName();
  const tid = editing?.tid || b64(random(12));
  const rev = (editing?.rev || 0) + 1;
  const stop = (p) => ({ name: p.name || "", lat: +p.lat.toFixed(6), lon: +p.lon.toFixed(6), ...(p.via ? { via: true } : {}) });
  const trip = {
    v: 1, name, start: stop(points[0]), destination: stop(points[points.length - 1]), stops: points.slice(1, -1).map(stop),
    options: routeOptions(), line: result.line.map(([la, lo]) => `${la.toFixed(5)},${lo.toFixed(5)}`).join(";"),
    m: result.m, s: result.s, up: result.up, curvy: result.curvy,
  };
  $("send").disabled = true;
  setStatus(t("sending"));
  const ok = await sendFrame(await ch.plan(rider, nextSeq(), tid, rev, trip));
  $("send").disabled = false;
  if (!ok) { setStatus(t("send_failed"), true); return; }
  const list = store.get("sent", []).filter((x) => x.tid !== tid);
  list.unshift({ tid, rev, name, km: Math.round(result.m / 1000), at: Date.now(), points, options, got: editing ? list.find((x) => x.tid === tid)?.got : {} });
  store.set("sent", list.slice(0, 50));
  editing = { tid, rev };
  setStatus(t("sent_ok"));
  drawSent();
  drawSummary();
});

function drawSent() {
  const list = store.get("sent", []);
  $("sent").hidden = list.length === 0;
  $("sent-list").replaceChildren(...list.map((s) => {
    const li = document.createElement("li");
    const what = document.createElement("div");
    what.className = "what";
    what.title = t("edit");
    const b = document.createElement("b"); b.textContent = s.name;
    const small = document.createElement("small");
    const got = Object.entries(s.got || {});
    const current = got.filter(([, rev]) => rev >= s.rev).map(([d]) => d);
    small.textContent = `${s.km} ${t("km")} · ` + (current.length ? t("got_on", current.join(", ")) : t("waiting"));
    small.className = current.length ? "got" : "";
    what.append(b, small);
    what.onclick = () => {
      points = s.points.map((p) => ({ ...p }));
      options = Object.assign(options, s.options);
      showOptions();
      editing = { tid: s.tid, rev: s.rev };
      $("name").value = s.name;
      changed();
      const bounds = points.reduce((bb, p) => bb.extend([p.lon, p.lat]), new maplibregl.LngLatBounds([points[0].lon, points[0].lat], [points[0].lon, points[0].lat]));
      map.fitBounds(bounds, { padding: 80, maxZoom: 12 });
    };
    const x = document.createElement("button");
    x.type = "button"; x.textContent = "✕"; x.title = t("forget"); x.ariaLabel = t("forget");
    x.onclick = async () => {
      store.set("sent", store.get("sent", []).filter((y) => y.tid !== s.tid));
      if (ch) sendFrame(await ch.forget(rider, nextSeq(), s.tid));
      if (editing?.tid === s.tid) editing = null;
      drawSent(); drawSummary();
    };
    li.append(what, x);
    return li;
  }));
}

// ---------- Pairing ----------
async function showPairing() {
  await ensureLink();
  const qr = qrcode(0, "M");
  qr.addData(linkUri(link));
  qr.make();
  $("qr").innerHTML = qr.createSvgTag({ cellSize: 6, margin: 2, scalable: true });
  const d = $("pairing");
  d.showModal();
  connect();
  await new Promise((r) => d.addEventListener("close", r, { once: true }));
}
$("pair").addEventListener("click", showPairing);

// ---------- The rest of the panel ----------
$("new").addEventListener("click", () => {
  if (result && !editing && !confirm(t("confirm_new"))) return;
  points = []; editing = null; $("name").value = "";
  changed();
});
$("lang").addEventListener("click", () => { lang = lang === "nb" ? "en" : "nb"; store.set("lang", lang); applyTexts(); drawList(); drawSummary(); drawSent(); });
$("fold").addEventListener("click", () => {
  const folded = $("panel").classList.toggle("folded");
  $("fold").ariaExpanded = String(!folded);
  applyTexts();
});

applyTexts();
showOptions();
drawList();
drawSent();
connect();
