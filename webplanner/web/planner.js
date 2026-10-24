// Curveon's web planner: plan a trip on a big screen, in a session with Curveon on the rider's phone or tablet
// (webplanner/README.md). Scanning the page's code connects a device: the page then shows that device's planned trips
// to open and work on, and sends new and changed trips back. The route comes from the planner service (the app's own
// routing); everything between page and device goes end-to-end encrypted through the relay (webplan.js).
import { b64, channel, decodePolyline, linkUri, newLink, parseLink, random, unb64 } from "./webplan.js";

// ---------- Texts (English and Norwegian, as in the app) ----------
const TEXTS = {
  en: {
    title: "Curveon planner", search_hint: "Search for a place, or click the map", lang: "Norsk",
    theme_dark: "Dark map", theme_light: "Light map",
    hint_empty: "Click the map or search to set where you start. Every place you add after that becomes where you are going, and the one before it a stop. Drag the line to make it take another road.",
    opt_style: "Roads", opt_surface: "Surface", opt_ferries: "Ferries", opt_motorways: "Motorways",
    style_fastest: "Fastest", style_curvy: "Curvy", style_super_curvy: "Super curvy",
    surface_tarmac_only: "Tarmac only", surface_mixed: "Mixed", surface_gravel: "Gravel", surface_offroad: "Offroad",
    ferries_ok: "Ferries OK", no_ferries: "No ferries", motorways_ok: "Motorways OK", no_motorways: "No motorways",
    no_motorways_e_roads: "No motorways or E-roads",
    start: "Start", via: "Through here", map_point: "Map point", remove: "Remove", up: "Earlier", down: "Later",
    km: "km", h: "h", min: "min", curvy: "curvy", up_m: "m climb", ferries_n: (n) => n === 1 ? "1 ferry" : `${n} ferries`,
    routing: "Finding the road…", queued: "Busy right now, your route is next in line…",
    err_busy: "The planner is busy. Try again in a moment.", err_no_route: "No road found between these places. Move a point onto a road.",
    err_too_long: "That is too long for one trip (over 1500 km). Split it into days.", err_slow_down: "That was a lot of routes at once. Wait a few minutes.",
    err_outside: "Routes can be worked out in Europe so far. Move the points there.",
    err_failed: "The route could not be worked out. Try again.", err_offline: "No connection to the planner. Check your internet connection.",
    name_hint: "Name of the trip", send: "Send to Curveon", update_on: (d) => `Update on ${d}`, send_new: "Save as a new trip",
    sending: "Sending…", sent_ok: "Sent. It shows under Planned in Curveon the next time the app is online.",
    updated_ok: (d) => `Sent. The trip on ${d} is updated the next time the app is online.`,
    send_failed: "The trip could not be sent. Check your internet connection and try again.",
    pending_title: "Waiting for Curveon", waiting: "Not fetched yet", forget: "Remove from this list",
    connect: "Connect Curveon", connect_more: "Connect another device", connected_to: (d) => `Connected to ${d}`,
    on_device: (d) => `On ${d}`, no_trips: "No planned trips there yet.", loose: "Not in a group",
    edit: "Open to work on it", delete_on: (d) => `Delete from ${d}`,
    confirm_delete: (n, d) => `Delete “${n}” from ${d}? It is gone from Planned there the next time the app is online.`,
    view_only: "A line without stops (a ride kept as a trip, or a loop): it can be shown here, not changed.",
    editing_from: (d) => `Opened from ${d}: Update changes the trip there.`,
    new_trip: "New trip",
    pair_title: "Connect Curveon to this page", pair_step1: "Open Curveon on your phone or tablet.",
    pair_step2: "Go to Planned and tap From the web.", pair_step3: "Point the camera at this code.",
    pair_more: "Once per phone or tablet. Then this page shows the trips on it, and what you plan here goes there.",
    pair_done: "Close", confirm_new: "Start a new trip? The one on the map is not sent yet.",
    attribution: "Routes: Curveon (BRouter) · Search: Photon, Kartverket",
  },
  nb: {
    title: "Curveon-planlegger", search_hint: "Søk etter et sted, eller klikk i kartet", lang: "English",
    theme_dark: "Mørkt kart", theme_light: "Lyst kart",
    hint_empty: "Klikk i kartet eller søk for å velge hvor du starter. Hvert sted du legger til etter det blir målet, og det forrige blir et stopp. Dra i linjen for å få den til å ta en annen vei.",
    opt_style: "Veier", opt_surface: "Underlag", opt_ferries: "Ferjer", opt_motorways: "Motorvei",
    style_fastest: "Raskest", style_curvy: "Svingete", style_super_curvy: "Supersvingete",
    surface_tarmac_only: "Kun asfalt", surface_mixed: "Blandet", surface_gravel: "Grus", surface_offroad: "Offroad",
    ferries_ok: "Ferjer OK", no_ferries: "Ingen ferjer", motorways_ok: "Motorvei OK", no_motorways: "Ingen motorvei",
    no_motorways_e_roads: "Ingen motorvei eller E-vei",
    start: "Start", via: "Gjennom her", map_point: "Kartpunkt", remove: "Fjern", up: "Tidligere", down: "Senere",
    km: "km", h: "t", min: "min", curvy: "svingete", up_m: "m stigning", ferries_n: (n) => n === 1 ? "1 ferje" : `${n} ferjer`,
    routing: "Finner veien …", queued: "Travelt akkurat nå, ruten din står først i køen …",
    err_busy: "Planleggeren har mye å gjøre. Prøv igjen om litt.", err_no_route: "Fant ingen vei mellom disse stedene. Flytt et punkt inn på en vei.",
    err_too_long: "Det er for langt for én tur (over 1500 km). Del den opp i dager.", err_slow_down: "Det ble mange ruter på kort tid. Vent noen minutter.",
    err_outside: "Ruter kan foreløpig bare regnes ut i Europa. Flytt punktene dit.",
    err_failed: "Ruten kunne ikke regnes ut. Prøv igjen.", err_offline: "Får ikke kontakt med planleggeren. Sjekk internettforbindelsen.",
    name_hint: "Navn på turen", send: "Send til Curveon", update_on: (d) => `Oppdater på ${d}`, send_new: "Lagre som ny tur",
    sending: "Sender …", sent_ok: "Sendt. Den dukker opp under Planlagte i Curveon neste gang appen er på nett.",
    updated_ok: (d) => `Sendt. Turen på ${d} oppdateres neste gang appen er på nett.`,
    send_failed: "Turen ble ikke sendt. Sjekk internettforbindelsen og prøv igjen.",
    pending_title: "Venter på Curveon", waiting: "Ikke hentet ennå", forget: "Fjern fra listen",
    connect: "Koble til Curveon", connect_more: "Koble til en enhet til", connected_to: (d) => `Koblet til ${d}`,
    on_device: (d) => `På ${d}`, no_trips: "Ingen planlagte turer der ennå.", loose: "Uten gruppe",
    edit: "Åpne for å jobbe med den", delete_on: (d) => `Slett fra ${d}`,
    confirm_delete: (n, d) => `Slette «${n}» fra ${d}? Den forsvinner fra Planlagte der neste gang appen er på nett.`,
    view_only: "En linje uten stopp (en kjørt tur lagret som tur, eller en rundtur): den kan vises her, men ikke endres.",
    editing_from: (d) => `Åpnet fra ${d}: Oppdater endrer turen der.`,
    new_trip: "Ny tur",
    pair_title: "Koble Curveon til denne siden", pair_step1: "Åpne Curveon på telefonen eller nettbrettet.",
    pair_step2: "Gå til Planlagte og trykk Fra nettet.", pair_step3: "Rett kameraet mot denne koden.",
    pair_more: "Én gang per telefon eller nettbrett. Da viser denne siden turene der, og det du planlegger her havner der.",
    pair_done: "Lukk", confirm_new: "Begynne på en ny tur? Den på kartet er ikke sendt ennå.",
    attribution: "Ruter: Curveon (BRouter) · Søk: Photon, Kartverket",
  },
};

// ---------- Small helpers ----------
const $ = (id) => document.getElementById(id);
const el = (tag, cls, text) => { const e = document.createElement(tag); if (cls) e.className = cls; if (text !== undefined) e.textContent = text; return e; };
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
  document.querySelectorAll("[data-t]").forEach((e) => { e.textContent = t(e.dataset.t); });
  document.querySelectorAll("[data-tp]").forEach((e) => { e.placeholder = t(e.dataset.tp); });
  $("lang").textContent = t("lang");
  $("theme").textContent = theme === "dark" ? "☀" : "☾";
  $("theme").title = t(theme === "dark" ? "theme_light" : "theme_dark");
  $("theme").ariaLabel = $("theme").title;
  $("fold").textContent = $("panel").classList.contains("folded") ? "▴" : "▾";
}

// ---------- State ----------
// points: [{ name, lat, lon, via }] start first, destination last.
// editing: what the map holds, if it was sent or opened from a device: { tid, ref: { r, id } | null, dev }.
// viewOnly: a device's line without stops, shown but not changed.
let points = [];
let result = null; // the planner's answer for these points and options (or the device's saved line)
let editing = null;
let viewOnly = false;
let options = Object.assign({ style: "CURVY", surface: "PAVED", ferries: false, motorways: false, trunk: false }, store.get("options", {}));
/** The connected devices, by their id on the channel: { dev, ids: [trip ids, newest first], trips: Map id → trip, at }. */
const devices = new Map();
/** Trips the page asked a device to delete ("rider:id"), hidden until its list says they are gone. */
const deleting = new Set();

// ---------- Map ----------
const emptyLine = () => ({ type: "FeatureCollection", features: [] });
// Light by default, dark when the rider picks it (both in the app's normal contrast); the page follows the map.
let theme = ["light", "dark"].includes(params.get("theme")) ? params.get("theme") : store.get("theme", "light");
document.documentElement.dataset.theme = theme;
const MAP = location.origin + BASE + "map/";
const protocol = new pmtiles.Protocol();
maplibregl.addProtocol("pmtiles", protocol.tile);
// The Norway map, also asked directly whether it covers the view (else the online map shows, as in the app).
const norway = new pmtiles.PMTiles(MAP + "norway.pmtiles");
protocol.add(norway);
let norwayMaxZoom = 15;
norway.getHeader().then((h) => { norwayMaxZoom = h.maxZoom; }).catch(() => {});

async function loadStyle() {
  const style = await (await fetch(`${BASE}map/styles/${theme}.json`)).json();
  style.sources.protomaps.url = "pmtiles://" + MAP + "norway.pmtiles";
  style.glyphs = MAP + "fonts/{fontstack}/{range}.pbf";
  style.sprite = MAP + "sprites/" + theme;
  // Beyond the Norway map there is only background: the sea's colour, so the edge of the map doesn't show.
  const water = style.layers.find((l) => l.id === "water")?.paint?.["fill-color"];
  const bg = style.layers.find((l) => l.id === "background");
  if (bg && typeof water === "string") bg.paint = { ...bg.paint, "background-color": water };
  return style;
}

const map = new maplibregl.Map({
  container: "map", style: withRoute(await loadStyle()), center: [8.5, 61.2], zoom: 5.2, minZoom: 3.5, maxZoom: 18,
  attributionControl: { compact: true, customAttribution: t("attribution") },
  // Where the map is stays in the address (#zoom/lat/lon): a view can be shared, and comes back on reload.
  hash: true,
});
if (params.has("debug")) window.curveonMap = map; // for tools/webplanner_test.mjs
map.addControl(new maplibregl.NavigationControl({ showCompass: false }), "top-right");
map.addControl(new maplibregl.GeolocateControl({ positionOptions: { enableHighAccuracy: false }, trackUserLocation: false }), "top-right");

/**
 * The route's line in [style], under the place names (which stay on top of every line, as in the app). Part of the
 * style itself, so a change between light and dark keeps it.
 */
function withRoute(style) {
  style.sources.route = { type: "geojson", data: routeData() };
  const at = style.layers.findIndex((l) => l.id.startsWith("places_"));
  style.layers.splice(at < 0 ? style.layers.length : at, 0,
    { id: "route-casing", type: "line", source: "route", layout: { "line-join": "round", "line-cap": "round" },
      paint: { "line-color": "#5a3000", "line-width": ["interpolate", ["linear"], ["zoom"], 5, 5, 12, 10, 16, 14] } },
    { id: "route", type: "line", source: "route", layout: { "line-join": "round", "line-cap": "round" },
      paint: { "line-color": "#ff9100", "line-width": ["interpolate", ["linear"], ["zoom"], 5, 3, 12, 6, 16, 9] } },
    { id: "route-hit", type: "line", source: "route", paint: { "line-color": "#000", "line-opacity": 0, "line-width": 22 } });
  return style;
}
map.on("load", showOnline);

/** The online map where the Norway map has no tiles in view (its middle or a corner), hidden where it does. */
async function showOnline() {
  const ids = map.getStyle().layers.map((l) => l.id).filter((id) => id.startsWith("online-"));
  if (!ids.length) return;
  const z = Math.max(0, Math.min(Math.floor(map.getZoom()), norwayMaxZoom));
  const n = 2 ** z;
  const c = map.getContainer();
  const spots = [[0.5, 0.5], [0.1, 0.1], [0.9, 0.1], [0.1, 0.9], [0.9, 0.9]].map(([fx, fy]) => map.unproject([c.clientWidth * fx, c.clientHeight * fy]));
  let covered = true;
  for (const ll of spots) {
    const lat = Math.max(-85, Math.min(85, ll.lat)) * Math.PI / 180;
    const x = Math.min(n - 1, Math.max(0, Math.floor((ll.lng + 180) / 360 * n)));
    const y = Math.min(n - 1, Math.max(0, Math.floor((1 - Math.log(Math.tan(lat) + 1 / Math.cos(lat)) / Math.PI) / 2 * n)));
    if (!(await norway.getZxy(z, x, y).catch(() => null))) { covered = false; break; }
  }
  const v = covered ? "none" : "visible";
  for (const id of ids) if (map.getLayer(id) && map.getLayoutProperty(id, "visibility") !== v) map.setLayoutProperty(id, "visibility", v);
}
let onlineTimer;
map.on("moveend", () => { clearTimeout(onlineTimer); onlineTimer = setTimeout(showOnline, 150); });


function routeData() { return result ? { type: "Feature", geometry: { type: "LineString", coordinates: result.line.map(([la, lo]) => [lo, la]) } } : emptyLine(); }

function drawRoute() {
  map.getSource("route")?.setData(routeData());
}

function fitTo(coords) {
  if (!coords.length) return;
  const b = coords.reduce((bb, [la, lo]) => bb.extend([lo, la]), new maplibregl.LngLatBounds([coords[0][1], coords[0][0]], [coords[0][1], coords[0][0]]));
  const narrow = innerWidth <= 640;
  map.fitBounds(b, { padding: narrow ? { top: 40, bottom: innerHeight * 0.55, left: 30, right: 30 } : { top: 60, bottom: 60, left: 420, right: 60 }, maxZoom: 12 });
}

// ---------- Markers ----------
let markers = [];
function kindOf(i) { return i === 0 ? "start" : i === points.length - 1 ? "end" : points[i].via ? "via" : "stop"; }
function stopNumber(i) { let n = 0; for (let k = 1; k <= i; k++) if (!points[k].via && k < points.length - 1) n++; return n; }
const pinText = (kind, i) => kind === "start" ? "A" : kind === "end" ? "B" : kind === "stop" ? String(stopNumber(i)) : "";

function drawMarkers() {
  markers.forEach((m) => m.remove());
  markers = points.map((p, i) => {
    const kind = kindOf(i);
    const e = el("div", "pin " + kind, pinText(kind, i));
    e.title = p.name || t(kind === "via" ? "via" : "map_point");
    const m = new maplibregl.Marker({ element: e, draggable: true }).setLngLat([p.lon, p.lat]).addTo(map);
    m.on("dragstart", () => { draggingLine = true; });
    m.on("dragend", async () => {
      const ll = m.getLngLat();
      points[i] = { ...points[i], lat: ll.lat, lon: ll.lng };
      setTimeout(() => { draggingLine = false; }, 50);
      changed();
      if (!points[i].via) { points[i].name = await placeName(ll.lat, ll.lng); drawList(); }
    });
    // A click on a through-point removes it: it was only there to bend the line.
    e.addEventListener("click", (ev) => { ev.stopPropagation(); if (points[i]?.via) { points.splice(i, 1); changed(); } });
    return m;
  });
}

// ---------- The list of places ----------
function drawList() {
  const ol = $("stops");
  ol.replaceChildren();
  points.forEach((p, i) => {
    const kind = kindOf(i);
    const li = el("li", kind);
    const name = el("span", "name", kind === "via" ? t("via") : p.name || t("map_point"));
    name.title = name.textContent;
    name.onclick = () => map.easeTo({ center: [p.lon, p.lat], zoom: Math.max(map.getZoom(), 11) });
    li.append(el("span", "dot", pinText(kind, i)), name);
    const btn = (label, title, fn) => { const b = el("button", "", label); b.type = "button"; b.title = title; b.ariaLabel = title; b.onclick = fn; li.append(b); };
    if (i > 0) btn("↑", t("up"), () => { [points[i - 1], points[i]] = [points[i], points[i - 1]]; changed(); });
    if (i < points.length - 1) btn("↓", t("down"), () => { [points[i + 1], points[i]] = [points[i], points[i + 1]]; changed(); });
    btn("✕", t("remove"), () => { points.splice(i, 1); changed(); });
    ol.append(li);
  });
  $("hint").textContent = viewOnly ? t("view_only") : editing?.ref ? t("editing_from", editing.dev) : t("hint_empty");
  $("hint").hidden = !viewOnly && !editing?.ref && points.length >= 2;
}

function addPlace(p) {
  if (viewOnly) { viewOnly = false; result = null; editing = null; }
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
  $("hits").replaceChildren(...list.map((h, i) => {
    const li = el("li");
    li.role = "option";
    li.ariaSelected = String(i === picked);
    li.append(el("span", "", h.name), el("small", "", h.area));
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
map.on("mouseenter", "route-hit", () => { if (!viewOnly) map.getCanvas().style.cursor = "grab"; });
map.on("mouseleave", "route-hit", () => { map.getCanvas().style.cursor = ""; });
map.on("mousedown", "route-hit", (e) => startLineDrag(e));
map.on("touchstart", "route-hit", (e) => { if (e.points.length === 1) startLineDrag(e); });

function startLineDrag(e) {
  if (!result || viewOnly || points.length < 2) return;
  e.preventDefault();
  draggingLine = true;
  const leg = legAt(e.lngLat);
  const ghost = new maplibregl.Marker({ element: el("div", "pin via") }).setLngLat(e.lngLat).addTo(map);
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

/** The places or options changed: the road is worked out again (a trip opened from a device keeps its line until then). */
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
  $("send-row").hidden = !result || viewOnly;
  $("send-new").hidden = !result || viewOnly || !editing?.ref;
  if (!result) { s.hidden = true; return; }
  const h = Math.floor(result.s / 3600), m = Math.round((result.s % 3600) / 60);
  const part = (v, unit) => { const e = el("span", "", v); e.append(el("small", "", unit)); return e; };
  s.replaceChildren(
    part(Math.round(result.m / 1000), t("km")),
    ...(h ? [part(h, t("h")), part(m, t("min"))] : [part(m, t("min"))]),
    part(`${result.curvy}/100`, t("curvy")),
    part(result.up, t("up_m")),
    ...(result.ferries ? [el("span", "", t("ferries_n", result.ferries))] : []),
  );
  s.hidden = false;
  if (!$("name").value) $("name").placeholder = defaultName();
  $("send").textContent = editing?.ref ? t("update_on", editing.dev) : t("send");
}

const defaultName = () => points.length >= 2 ? `${points[0].name || t("map_point")} – ${points[points.length - 1].name || t("map_point")}` : t("name_hint");

// Options, as the app keeps them.
function readOptions() {
  options = {
    style: $("style").value, surface: $("surface").value, ferries: $("ferries").value === "no",
    motorways: $("motorways").value !== "ok", trunk: $("motorways").value === "noe",
  };
  store.set("options", options);
  if (!viewOnly) changed();
}
function showOptions() {
  $("style").value = options.style;
  $("surface").value = options.surface;
  $("ferries").value = options.ferries ? "no" : "ok";
  $("motorways").value = options.trunk ? "noe" : options.motorways ? "no" : "ok";
}
["style", "surface", "ferries", "motorways"].forEach((id) => $(id).addEventListener("change", readOptions));

// ---------- The session with the devices ----------
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

/** Stays connected while the page is open: the devices' trips, and their word that a trip arrived. */
async function connect() {
  if (!link || socket) return;
  const c = await ensureLink();
  const ws = (socket = new WebSocket(c.url));
  ws.onopen = () => { backoff = 1000; };
  ws.onmessage = async (ev) => {
    const m = await c.open(ev.data);
    if (!m) return;
    if (m.type === "plan_index") gotIndex(m.rider, m.body);
    else if (m.type === "plan_have") gotTrip(m.rider, m.body);
    else if (m.type === "plan_got") gotAnswer(m.body);
  };
  ws.onclose = () => {
    socket = null;
    if (document.visibilityState === "visible") setTimeout(connect, (backoff = Math.min(backoff * 2, 60000)));
  };
}
document.addEventListener("visibilitychange", () => { if (document.visibilityState === "visible") connect(); });

function device(r) {
  if (!devices.has(r)) devices.set(r, { dev: "Curveon", ids: [], trips: new Map(), at: 0 });
  return devices.get(r);
}

function gotIndex(r, body) {
  const d = device(r);
  if (body.ts < d.at) return;
  Object.assign(d, { dev: body.dev || d.dev, ids: body.ids || [], at: body.ts });
  for (const id of [...d.trips.keys()]) if (!d.ids.includes(id)) d.trips.delete(id);
  for (const k of [...deleting]) if (k.startsWith(r + ":") && !d.ids.includes(Number(k.split(":")[1]))) deleting.delete(k);
  if (editing?.ref?.r === r) editing.dev = d.dev;
  // A device that just scanned the code: the session is on, the code can go.
  if (pairingOpenedAt && body.ts >= pairingOpenedAt - 5000) $("pairing").close();
  drawDevices();
  drawSummary();
}

function gotTrip(r, body) {
  if (!body.trip) return; // one the device took off its list
  const d = device(r);
  const prev = d.trips.get(body.trip.id);
  if (prev && prev.ts > body.ts) return;
  d.trips.set(body.trip.id, { ...body.trip, ts: body.ts });
  drawDevices();
}

function gotAnswer(body) {
  const list = store.get("sent", []);
  const s = list.find((x) => x.tid === body.tid);
  if (!s) return;
  s.got = Object.assign(s.got || {}, { [body.dev || "?"]: body.rev });
  store.set("sent", list);
  drawPending();
}

/** Sends one frame, connecting first if need be, and says whether it went. */
async function sendFrame(frame) {
  await connect();
  for (let i = 0; i < 50 && socket?.readyState === WebSocket.CONNECTING; i++) await new Promise((r) => setTimeout(r, 100));
  if (socket?.readyState !== WebSocket.OPEN) return false;
  socket.send(frame);
  return true;
}

// The devices' trips, in their groups.
function drawDevices() {
  const box = $("devices");
  box.replaceChildren();
  for (const [r, d] of devices) {
    if (!d.at) continue; // trips seen before the device's list: wait for it
    const sec = el("section", "device");
    sec.append(el("h2", "", t("on_device", d.dev)));
    const trips = d.ids.map((id) => d.trips.get(id)).filter((x) => x && !deleting.has(r + ":" + x.id));
    if (!trips.length) sec.append(el("p", "hint", t("no_trips")));
    const groups = new Map();
    for (const x of trips) { const g = x.group || ""; if (!groups.has(g)) groups.set(g, []); groups.get(g).push(x); }
    const named = [...groups.keys()].filter(Boolean).sort((a, b) => a.localeCompare(b));
    for (const g of [...named, ...(groups.has("") ? [""] : [])]) {
      if (named.length) sec.append(el("h3", "", g || t("loose")));
      const ul = el("ul", "trips");
      for (const x of groups.get(g)) {
        const li = el("li", editing?.ref?.r === r && editing.ref.id === x.id ? "open" : "");
        const what = el("div", "what");
        what.title = t("edit");
        what.append(el("b", "", x.name), el("small", "", `${Math.round(x.m / 1000)} ${t("km")}`));
        what.onclick = () => openTrip(r, d, x);
        const del = el("button", "", "✕");
        del.type = "button"; del.title = t("delete_on", d.dev); del.ariaLabel = del.title;
        del.onclick = async () => {
          if (!confirm(t("confirm_delete", x.name, d.dev))) return;
          await ensureLink();
          if (await sendFrame(await ch.del(rider, nextSeq(), { r, id: x.id }))) {
            deleting.add(r + ":" + x.id);
            if (editing?.ref?.r === r && editing.ref.id === x.id) editing = null;
            drawDevices(); drawList(); drawSummary();
          } else setStatus(t("send_failed"), true);
        };
        li.append(what, del);
        ul.append(li);
      }
      sec.append(ul);
    }
    box.append(sec);
  }
  box.hidden = box.childElementCount === 0;
  const names = [...devices.values()].filter((d) => d.at).map((d) => d.dev);
  $("session").textContent = names.length ? t("connected_to", names.join(", ")) : "";
  $("session").hidden = !names.length;
  $("pair").textContent = names.length ? t("connect_more") : t("connect");
}

/** A device's trip on the map, with its own line: changed here and sent back with Update. */
function openTrip(r, d, x) {
  const line = decodePolyline(x.poly);
  if (line.length < 2) return;
  routeCtl?.abort();
  clearTimeout(routeTimer);
  result = { line, m: x.m, s: x.s, up: x.up, curvy: x.curvy, ferries: 0 };
  if (x.destination) {
    const start = x.start || { name: t("start"), lat: line[0][0], lon: line[0][1] };
    points = [start, ...(x.stops || []), x.destination].map((p) => ({ name: p.name || "", lat: p.lat, lon: p.lon, via: !!p.via }));
    viewOnly = false;
    editing = { tid: "r" + x.id + "-" + r.slice(0, 8), ref: { r, id: x.id }, dev: d.dev };
  } else {
    points = [];
    viewOnly = true;
    editing = null;
  }
  if (x.options) { options = Object.assign(options, x.options); showOptions(); }
  $("name").value = x.name;
  setStatus("");
  drawList(); drawMarkers(); drawRoute(); drawSummary(); drawDevices();
  fitTo(line);
}

// ---------- Sending ----------
async function send(asNew) {
  if (!result || viewOnly) return;
  const first = !store.get("link", null);
  await ensureLink();
  if (first) await showPairing();
  const name = $("name").value.trim() || defaultName();
  const target = asNew ? null : editing;
  const tid = target?.tid || b64(random(12));
  const stop = (p) => ({ name: p.name || "", lat: +p.lat.toFixed(6), lon: +p.lon.toFixed(6), ...(p.via ? { via: true } : {}) });
  const trip = {
    v: 1, name, start: stop(points[0]), destination: stop(points[points.length - 1]), stops: points.slice(1, -1).map(stop),
    options: routeOptions(), line: result.line.map(([la, lo]) => `${la.toFixed(5)},${lo.toFixed(5)}`).join(";"),
    m: result.m, s: result.s, up: result.up, curvy: result.curvy, ...(target?.ref ? { ref: target.ref } : {}),
  };
  $("send").disabled = $("send-new").disabled = true;
  setStatus(t("sending"));
  // Every send is newer than the one before: the time is the revision.
  const rev = Date.now();
  const ok = await sendFrame(await ch.plan(rider, nextSeq(), tid, rev, trip));
  $("send").disabled = $("send-new").disabled = false;
  if (!ok) { setStatus(t("send_failed"), true); return; }
  if (!target?.ref) {
    const list = store.get("sent", []).filter((x) => x.tid !== tid);
    list.unshift({ tid, rev, name, km: Math.round(result.m / 1000), at: Date.now(), got: {} });
    store.set("sent", list.slice(0, 30));
    editing = { tid, ref: null, dev: null };
  }
  setStatus(target?.ref ? t("updated_ok", target.dev) : t("sent_ok"));
  drawPending();
  drawSummary();
}
$("send").addEventListener("click", () => send(false));
$("send-new").addEventListener("click", () => send(true));

/** Trips sent from here that no device has fetched yet; the rest show in the devices' lists. */
function drawPending() {
  const list = store.get("sent", []);
  const waiting = list.filter((s) => !Object.values(s.got || {}).some((rev) => rev >= s.rev));
  if (waiting.length !== list.length) store.set("sent", waiting);
  $("pending").hidden = waiting.length === 0;
  $("pending-list").replaceChildren(...waiting.map((s) => {
    const li = el("li");
    const what = el("div", "what");
    what.append(el("b", "", s.name), el("small", "", `${s.km} ${t("km")} · ${t("waiting")}`));
    const x = el("button", "", "✕");
    x.type = "button"; x.title = t("forget"); x.ariaLabel = t("forget");
    x.onclick = async () => {
      store.set("sent", store.get("sent", []).filter((y) => y.tid !== s.tid));
      if (ch) sendFrame(await ch.forget(rider, nextSeq(), s.tid));
      if (editing?.tid === s.tid) editing = null;
      drawPending(); drawSummary();
    };
    li.append(what, x);
    return li;
  }));
}

// ---------- Pairing ----------
let pairingOpenedAt = 0;
async function showPairing() {
  await ensureLink();
  const qr = qrcode(0, "M");
  qr.addData(linkUri(link));
  qr.make();
  $("qr").innerHTML = qr.createSvgTag({ cellSize: 6, margin: 2, scalable: true });
  const d = $("pairing");
  pairingOpenedAt = Date.now();
  d.showModal();
  connect();
  await new Promise((r) => d.addEventListener("close", r, { once: true }));
  pairingOpenedAt = 0;
}
$("pair").addEventListener("click", showPairing);

// ---------- The rest of the panel ----------
$("new").addEventListener("click", () => {
  if (result && !editing && !viewOnly && !confirm(t("confirm_new"))) return;
  points = []; editing = null; viewOnly = false; $("name").value = "";
  changed();
  drawDevices();
});
$("theme").addEventListener("click", async () => {
  theme = theme === "dark" ? "light" : "dark";
  store.set("theme", theme);
  document.documentElement.dataset.theme = theme;
  applyTexts();
  map.setStyle(withRoute(await loadStyle()));
  map.once("idle", showOnline);
});
$("lang").addEventListener("click", () => { lang = lang === "nb" ? "en" : "nb"; store.set("lang", lang); applyTexts(); drawList(); drawSummary(); drawDevices(); drawPending(); });
$("fold").addEventListener("click", () => {
  const folded = $("panel").classList.toggle("folded");
  $("fold").ariaExpanded = String(!folded);
  applyTexts();
});

applyTexts();
showOptions();
drawList();
drawDevices();
drawPending();
connect();
