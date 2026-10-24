// The web planner's channel to the rider's apps, as groups/WebPlan.kt has it (docs/groups-protocol.md, "Web planner"):
// keys made here and shown as a QR code, trips sealed with AES-256-GCM and kept by the relay until the app fetches them.
// WebCrypto only, so it runs the same in the browser and in Node (webplanner/web/test/interop.mjs).

const subtle = globalThis.crypto.subtle;
const enc = new TextEncoder();
const dec = new TextDecoder();

export const KEEP_SECONDS = 30 * 86400;
const PROTOCOL_REVISION = 2;

export function b64(bytes) {
  let s = "";
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

export function unb64(s) {
  const bin = atob(s.replace(/-/g, "+").replace(/_/g, "/") + "===".slice((s.length + 3) % 4));
  return Uint8Array.from(bin, (c) => c.charCodeAt(0));
}

export const random = (n) => globalThis.crypto.getRandomValues(new Uint8Array(n));
const hex = (bytes) => Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("");
const concat = (...parts) => {
  const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0));
  let i = 0;
  for (const p of parts) { out.set(p, i); i += p.length; }
  return out;
};
const be32 = (n) => new Uint8Array([(n >>> 24) & 255, (n >>> 16) & 255, (n >>> 8) & 255, n & 255]);

async function hkdf(groupKey, groupId, info) {
  const ikm = await subtle.importKey("raw", groupKey, "HKDF", false, ["deriveBits"]);
  return new Uint8Array(await subtle.deriveBits({ name: "HKDF", hash: "SHA-256", salt: groupId, info: enc.encode(info) }, ikm, 256));
}

/** A new channel: what the QR code carries. Nobody holds an admin key; `a` is only what the relay fixes the channel to. */
export function newLink(relayHost) {
  return { g: random(16), k: random(32), a: random(32), r: relayHost };
}

export const linkUri = (l) => `curveon://webplan#v=1&g=${b64(l.g)}&k=${b64(l.k)}&a=${b64(l.a)}&r=${encodeURIComponent(l.r)}`;

export function parseLink(uri) {
  if (!uri.startsWith("curveon://webplan#")) return null;
  const p = new URLSearchParams(uri.slice(uri.indexOf("#") + 1));
  if (p.get("v") !== "1") return null;
  return { g: unb64(p.get("g")), k: unb64(p.get("k")), a: unb64(p.get("a")), r: p.get("r") };
}

/** Keys of a channel, worked out once. */
export async function channel(link, scheme = "wss") {
  const encKey = await subtle.importKey("raw", await hkdf(link.k, link.g, "curveon/1 enc"), "AES-GCM", false, ["encrypt", "decrypt"]);
  const access = await subtle.importKey("raw", await hkdf(link.k, link.g, "curveon/1 access"), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const proof = b64(new Uint8Array(await subtle.sign("HMAC", access, concat(enc.encode("curveon/1 proof"), link.g, be32(0)))));
  const url = `${scheme}://${link.r}/g/${hex(link.g)}?p=${proof}&a=${b64(link.a)}&pv=${PROTOCOL_REVISION}`;
  const aad = (rider, type) => concat(enc.encode("curveon/1"), link.g, rider, be32(0), enc.encode(type));

  async function seal(type, rider, body, slot) {
    const n = random(12);
    const c = new Uint8Array(await subtle.encrypt({ name: "AES-GCM", iv: n, additionalData: aad(rider, type), tagLength: 128 }, encKey, enc.encode(JSON.stringify(body))));
    const frame = { v: 1, t: type, r: b64(rider), e: 0, n: b64(n), c: b64(c) };
    if (slot !== undefined) Object.assign(frame, { k: slotOf(slot), x: KEEP_SECONDS });
    return JSON.stringify(frame);
  }

  /** The frame's type and payload, or null for anything this key did not seal. */
  async function open(text) {
    try {
      const f = JSON.parse(text);
      if (f.v !== 1 || f.e !== 0 || typeof f.t !== "string" || f.t === "relay") return null;
      const plain = await subtle.decrypt({ name: "AES-GCM", iv: unb64(f.n), additionalData: aad(unb64(f.r), f.t), tagLength: 128 }, encKey, unb64(f.c));
      return { type: f.t, rider: f.r, body: JSON.parse(dec.decode(plain)) };
    } catch {
      return null;
    }
  }

  return {
    url,
    open,
    /** Any frame on the channel (the app's kinds too: tools/webplanner_test.mjs plays a device with it). */
    seal,
    /** A trip ({ tid, rev, trip }) for the app, kept by the relay under its id. */
    plan: (rider, seq, tid, rev, trip) => seal("plan", rider, { seq, ts: Date.now(), tid, rev, trip }, tid),
    /** Asks the device [ref.r] to delete its trip [ref.id]; kept, so a device that is off now does it later. */
    del: (rider, seq, ref) => seal("plan_del", rider, { seq, ts: Date.now(), ref }, "d" + ref.id + ref.r.slice(0, 8)),
    /** Tells the relay to forget [tid]: a frame of the same slot kept for 0 seconds. */
    forget: async (rider, seq, tid) => {
      const f = JSON.parse(await seal("plan", rider, { seq, ts: Date.now(), tid, rev: 0, gone: true }, tid));
      f.x = 0;
      return JSON.stringify(f);
    },
  };
}

/** Relay slots are base64url; the same filter as WebPlan.slot. */
export const slotOf = (tid) => (String(tid).replace(/[^A-Za-z0-9_-]/g, "").slice(0, 43) || "t");

/** Google's encoded polyline (1e-5), as groups/WebPlan.kt Polyline: [[lat, lon], ...]. */
export function decodePolyline(s) {
  const out = [];
  let i = 0, lat = 0, lon = 0;
  const next = () => {
    let shift = 0, result = 0, b;
    do { b = s.charCodeAt(i++) - 63; result |= (b & 0x1f) << shift; shift += 5; } while (b >= 0x20);
    return result & 1 ? ~(result >> 1) : result >> 1;
  };
  while (i < s.length) { lat += next(); lon += next(); out.push([lat / 1e5, lon / 1e5]); }
  return out;
}
