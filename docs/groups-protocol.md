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
`wss://<relay>/g/<groupId hex>?p=<proof>[&a=<adminPub>]`

- The first connection to a group creates it and fixes the `proof` and the admin public key (`a`).
  Later connections must send the same `proof`, or the relay answers 403.
- A `rekey` frame carries the next `proof` and is signed by the admin (see below); the relay then
  accepts only the new `proof`, which locks out a removed rider.
- The relay limits sockets per group, groups created per IP and hour, frame size and rate.
- Answers: 403 wrong or missing proof, 429 too many new groups from this address, 503 relay full.
- On connect the relay replays what it stored: `hello`, `route`, then `sos`. Positions are never stored.
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
`n`, `c` and nothing else; a frame of an unknown type or with extra fields is dropped.

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
| `rekey` | forward; keep ~30 days | see below (not encrypted with `encKey`) |

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
