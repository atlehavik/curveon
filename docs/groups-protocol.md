# Group protocol, version 1

The wire format for riding together. Design and reasons are in [../groups.md](../groups.md); this
file is what another implementation (iOS, a test client, the relay) must match. Reference code:
`:groups` (Kotlin) and `~/develop/curveon-relay`. Primitives only: AES-256-GCM, HMAC-SHA256, HKDF-SHA256,
X25519, Ed25519. All binary values are base64url without padding unless stated.

## Identifiers and keys
| Name | Size | Made by | Notes |
|---|---|---|---|
| `groupId` | 16 bytes random | group creator | routing only; in URLs as 32 lowercase hex characters |
| `groupKey` | 32 bytes random | group creator | never leaves the riders |
| `riderId` | 16 bytes random | each rider, per group | not per device, so groups cannot be linked |
| admin key | Ed25519 | group creator | public half is in the invite; the private half stays on the admin's device |
| agreement key | X25519 | each rider, per group | public half is sent in `hello` |
| `epoch` | uint32, starts at 0 | rises by 1 at each rekey | |

Derived with HKDF-SHA256 (`ikm = groupKey`, `salt = groupId`, 32 bytes out):
- `encKey` = info `"curveon/1 enc"`
- `accessKey` = info `"curveon/1 access"`

`proof = HMAC-SHA256(accessKey, "curveon/1 proof" || groupId || epoch_be32)`.

## Invite
Everything is in the URI fragment, so a web server never sees it:

    https://<relay>/join#v=1&g=<groupId>&k=<groupKey>&a=<adminPub>&r=<relay>[&n=<name>][&x=<expiry>][&e=<epoch>][&me=<identity>]
    curveon://join#v=1&...            (same fragment)

`v` version (a client rejects other versions), `g`, `k`, `a` as above, `r` relay host (so a moved or
self-run relay still works), `n` optional group name (URL-encoded), `x` optional expiry in epoch seconds
(enforced by the app only: a soft limit), `e` the key epoch `k` belongs to (0 when left out; a group that has
had a rider removed is past 0, so an invite made after that must say so).

### The same rider on two devices
One person with a tablet and a phone should be one rider, not two. A device that is already in the group makes a
link for the other one with `me=<identity>` added, where `identity` is `riderId (16 bytes) || agreement private
key (32) [|| admin signing private key (32), only for an admin]`, base64url. The other device that opens it
joins as that rider: same `riderId`, same X25519 key pair (so a rekey can be opened on both), and the admin
signing key if it was an admin. Such a link gives full access as that rider: show it only on screen, set a
short `x` (the apps use 15 minutes), and never share it through a chat. A client that does not know `me`
ignores it and joins as a new rider (a harmless fallback); one that cannot read it must refuse the link.
Frames from one's own `riderId` are ignored (except `claim`, below), so the two devices do not show each other as riders.

Every payload carries `dev`, the sending device's own id (not shared between a rider's devices). The replay
guard counts `seq` per rider, epoch **and device**, so two devices that number from slightly different clocks
do not drop each other's frames.

### Which device sends the position
A rider's devices must not both send their position. A device that is about to send claims the role with a
`claim` frame (payload: `dev`); the relay keeps the latest `claim` per rider and replays it to a device that
connects later. The newest claim (by `ts`, ties to the larger `dev`) wins: a device that sees another device's
newer claim stops sending `pos` until the rider takes over again (a new claim from it). The claimant repeats
its claim every minute while it sends; a claim more than three minutes old no longer holds, so a device left
behind in a pocket does not block the other one. Only `pos` is held back: profile, SOS and routes are not.

## Relay connection
`wss://<relay>/g/<groupId hex>?p=<proof>[&a=<adminPub>][&pv=<revision>]`

`pv` is what the client understands (`Wire.REVISION`; none means 1, the clients from before it). Revision 2 knows
the `relay` frame, any frame type and keeping in slots. The relay ignores query parameters it does not know.

- The first connection to a group creates it and fixes the `proof` and the admin public key (`a`).
  Later connections must send the same `proof`, or the relay answers 403.
- A `rekey` frame carries the next `proof` and is signed by the admin (see below); the relay then
  accepts only the new `proof`, which locks out a removed rider.
- The relay limits sockets per group, groups created per IP and hour, frame size and rate.
- Answers: 403 wrong or missing proof, 429 too many new groups from this address, 503 relay full.
- On connect the relay first sends its `relay` frame (see "What the relay says"), then replays what it stored:
  `hello`, `route`, then the rest in the order it came (`sos`, `claim`, `invite`, `trip`, slots), then what it keeps in
  memory (the latest `pos` of each rider, memory slots). Positions are never written to disk.
- A frame is dropped unless `e` equals the group's current epoch.
- After an accepted `rekey` the relay closes every socket with code **4001**; clients reconnect with the
  new proof. A client that connects with the proof from just before the last rekey (it was offline)
  receives the stored `rekey` and is closed with code **4002**; it then reconnects with the new proof.
  Two rekeys while offline cannot be caught up: that rider needs a new invite.
- A rekey drops everything stored (profiles, routes, SOS belong to the old key); riders send `hello` again.

## Frame (what the relay sees)
JSON, one WebSocket text message:

    { "v": 1, "t": "<type>", "r": "<riderId>", "e": <epoch>, "n": "<nonce>", "c": "<ciphertext>" }

`n` is a fresh random 96-bit nonce per frame. `c` is AES-256-GCM(`encKey`, `n`, payload) with a 128-bit
tag. Associated data (authenticated, not hidden):

    "curveon/1" || groupId || riderId || epoch_be32 || type (ASCII)

so a frame cannot be moved to another group, rider, epoch or type. The relay forwards `v`, `t`, `r`, `e`,
`n`, `c` and nothing else: other fields are not passed on (an unknown one is ignored, not a reason to drop the frame).

`t` is any name matching `[a-z][a-z0-9_]{0,23}` except `rekey` (its own shape, below) and names starting with `relay`
(only the relay sends those). The relay forwards a type it does not know like any other; the types in the table also
have a meaning of their own on the relay. A client ignores a frame of a type it does not know.

The envelope may also ask the relay to keep the frame (see "Keeping a frame"): `k`, `x`, `m`. These are for the
relay only and are never forwarded.

| `t` | Relay | Payload (JSON, inside the ciphertext) |
|---|---|---|
| `pos` | forward only | `seq`, `ts`, lat, lon, speed, heading, accuracy, optional battery, `paused`, `stopped`, `stoppedSince` |
| `hello` | forward; keep latest per rider | `seq`, `ts`, nickname, colour, agreement public key, avatar |
| `route` | forward; keep the last few | `seq`, `ts`, id, name, kind, gzipped GPX or route data |
| `sos` | forward; keep until the sender cancels it (`sos_cancel`) or 12 hours | `seq`, `ts`, position |
| `sos_ack` | forward | `seq`, `ts`, the `sos` it answers, `coming` true/false |
| `sos_cancel` | forward; removes the stored `sos` | `seq`, `ts` |
| `leave` | forward | `seq`, `ts` |
| `claim` | forward; keep the latest per rider | `seq`, `ts`, `dev` (see below) |
| `nudge` | forward; the sender asks for a slot, 15 min | `seq`, `ts`, `id`, `to` (riderId): "I am trying to reach you", for `to` only; shown once per `id`, not when older than 15 min |
| `nudge_seen` | forward; the sender asks for a slot, 15 min | `seq`, `ts`, `id` (of the nudge), `to` (who sent it); the rider's other devices drop the nudge too |
| `rekey` | forward; keep ~30 days | see below (not encrypted with `encKey`) |

## Keeping a frame
A sender asks the relay to hand a frame to those who connect later with three optional envelope fields:

| Field | Meaning |
|---|---|
| `k` | the slot, base64url, at most 43 characters. The relay keeps the **latest frame per rider and slot**. |
| `x` | seconds to keep it (left out: as long as the relay allows). `0` forgets the slot (the frame is still forwarded). |
| `m` | `true`: in memory only (gone at a relay restart). |

- Slots are opaque to the relay. Clients make them with `slot = base64url(HMAC-SHA256(slotKey, "curveon/1 slot" || name)[0..16])`,
  `slotKey` = HKDF-SHA256(`ikm = groupKey`, `salt = groupId`, info `"curveon/1 slot"`), so the relay cannot tell what
  a slot holds, and slots change with every rekey.
- A frame with a slot is kept that way instead of by the relay's own rule for its type. The type's other effects
  stay (`leave` still removes the rider's profile, `sos_cancel` the stored `sos`).
- A `pos` is never written to disk, whatever it asks: it is kept in memory.
- `leave` removes every slot of that rider; a rekey drops them all (they were made with the old key).
- The relay cuts what it is asked to its own limits, sent in its `relay` frame: the longest time on disk (`keep`,
  30 days) and in memory (`memKeep`, 1 hour), the largest frame kept in memory (`memFrame`), and slots and bytes per
  rider (`slots`, `slotBytes`). Past a quota the rider's (or the group's) oldest slots are forgotten first.
- A malformed `k`, `x` or `m` is ignored: the frame is forwarded and kept by the type's own rule.
- A relay from before slots ignores the fields, so a client that asks gets the old behaviour there, never an error.

What the reference client keeps this way: every `trip` (slot `trip/<tid>/<device id>`, 30 days), so a trip that
changes often never pushes other trips, the sync document or "I share with you" out of what a later joiner gets.

## What the relay says
The first message on every connection (not a catch-up after a rekey) is the relay's own:

    { "v": 1, "t": "relay", "f": ["any-type", "keep", "keep-memory"],
      "lim": { "frame": 524288, "rate10s": 30, "keep": 2592000, "memKeep": 3600, "memFrame": 4096, "slots": 64, "slotBytes": 2097152 } }

`f` lists what this relay can do; a feature is only ever added, never renamed or taken away. `lim` are its limits
(bytes, frames per 10 s per socket, seconds). Clients from before it drop it (it has no `r`), which is why it may be sent
to everyone. A client may use it to choose how to send; none needs it to work.

## Evolving the protocol
Riders do not all update at once, and a friend may run a version from months ago. Every change must leave the older
apps working, and the relay must keep serving every older client. The rules:

1. **Add, never change.** Payload fields are only added, and optional: an older app ignores fields it does not know.
   A field is never removed, renamed or given another type or meaning; a new meaning is a new field. Fields an
   older app reads as required must always be sent: `seq`, `ts` (all), `lat`, `lon` (`pos`, `sos`), `nick`, `colour`,
   `ak` (`hello`), `id`, `name`, `data` (`route`), `sos` (`sos_ack`), `dev` (`claim`), `oid`, `link` (`invite`),
   `tid`, `rev`, `doc` (`trip`); inside a trip document `destination` with `lat`, `lon`, and `lat`, `lon` of each
   stop; inside the sync document (`tid` `me`) `items` with `k` and `t`. Leaving one out makes older apps drop the
   whole message, silently.
2. **Something new is a new frame type**, not a new use of an old one. Older apps ignore an unknown type; the relay
   forwards it with no change on the server. (Not, say, a new `tid` in `trip`: older apps would try to read it as a trip.)
3. **What should reach those who connect later** asks for a slot (`k`, `x`, `m`) instead of a new rule on the relay.
4. **The relay changes only for something it must do itself.** Then the relay goes out first and the apps after,
   and what it does for older clients stays as it was. It can tell clients apart by `pv`, and tells them what it can
   do in its `relay` frame.
5. **`v` stays 1.** It is raised only for an envelope older clients and relays could not read at all, which would cut
   them off; a new field or type never needs it. The same goes for `v` in the trip document (`TripDoc`) and the invite.
6. **Close codes:** a client reconnects with back-off on any code it does not know, so a new code never strands one.
7. **Before it goes out:** `npm test` in `curveon-relay`, and `RelayIntegrationTest` in `:groups`, which runs the
   Kotlin clients against the real relay. A relay change is also tried with the clients of the release before
   (an older `:groups` against the new relay).

## Replay protection
Every payload has `seq`, a number that rises per rider and epoch, and `ts`. A receiver accepts a frame
only if `seq` is higher than the last accepted from that rider in that epoch (`ReplayGuard`), and may
ignore a `pos` whose `ts` is far in the past. A frame of another epoch is not opened.

## Rekey (removing a rider, or a leaked invite)
An admin makes a new `groupKey'`, sets `epoch' = epoch + 1` and sends one frame to everyone:

    { "v": 1, "t": "rekey", "r": "<admin riderId>", "e": <epoch'>, "p": "<proof'>",
      "keys": [ { "r": "<riderId>", "ep": "<ephemeral X25519 public>", "n": "<nonce>", "c": "<sealed groupKey'>" }, ... ],
      "s": "<Ed25519 signature>" }

- For each remaining rider: `secret = X25519(ephemeralPrivate, riderAgreementPublic)`,
  `wrapKey = HKDF-SHA256(ikm = secret, salt = groupId, info = "curveon/1 rekey", 32)`,
  `c = AES-256-GCM(wrapKey, n, groupKey')` with associated data `"curveon/1 rekey" || groupId || riderId || epoch'_be32`.
- `s` signs `"curveon/1 rekey-sig" || groupId || epoch'_be32 || proof' (ASCII) || for each key: riderId || ep || n || c`
  with the admin Ed25519 key. Receivers and the relay check it against the admin public key from the
  invite; a member cannot kick anyone, and cannot swap `proof'` to lock everyone out. Receivers also check that `proof'` is what the new key gives.
- The removed rider gets no copy. The relay switches to `proof'` when it has checked the signature.
- Limits (also in groups.md): the removed rider has seen what was shared before, and a rider who is
  offline during the change picks the rekey up on return.

## Test vectors
Not written yet. Add them (fixed keys, nonce, expected ciphertext and proof) before anyone else writes
a client; until then `groups/src/test` is the reference.
