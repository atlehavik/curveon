# Groups: ride together (design draft)

Status: idea and design only, nothing built. Decided in discussion 2026-10-11.

## Goal
A rider can start a group, friends join with a QR code or link, and everyone sees the others on the
map. Anyone can share a route or GPX with the group. No accounts, no sign-up, and the service never
learns who anyone is or where anyone is. Free to run (or close to it).

## Decisions so far
- **Android only**, never Apple. One Kotlin implementation shared by companion and "This device".
- **Typical group 3-4 riders**, hard limit 30 per group in the protocol.
- **Optional and off by default.** Curveon stays offline-first; only this feature uses the internet.
- **The phone is the post office.** The tablet never touches the internet. Tablet <-> companion over
  Bluetooth as today, companion <-> relay over mobile data. On a phone running Curveon itself
  ("This device") the same code runs in-process.
- **Planned for Google Play** later (the whole app, not just this feature), so design for review now.
- Rider has a hosting account (e.g. Cloudflare) for the relay; riders in a group need nothing.

```
tablet --Bluetooth--> companion --mobile data--> relay <-- companion <--Bluetooth-- tablet
```

## Identity and security model
- **Group** = random 128-bit `groupId` (routing only) + random 256-bit `groupKey` (never leaves
  the riders). Created on the rider's phone.
- **Invite** = QR / link carrying both, e.g. `curveon://join?g=<groupId>#k=<groupKey>`. The key is in
  the fragment so it is never sent to a web server if the link is ever opened in a browser.
- **Rider** = random `riderId` made per group (not per device, so groups cannot be linked), plus
  nickname and colour chosen by the rider. Nickname and colour travel only inside encrypted payloads.
- **Joining must be as easy as possible.** One "Invite" screen with a large QR code and a "Share link"
  button (Android share sheet: Signal, WhatsApp, SMS ...), so friends can join on the spot or the
  group can be made at home and the link sent beforehand. Opening the link shows one prompt ("Join
  *Lofoten trip*, 3 riders?"), then nickname and avatar, and they are in.
  NFC is skipped: Android Beam is gone since Android 14, so it would need card emulation, which is
  fiddly and varies by device.
- **Remote invites** (a friend in another part of the country): "Share link" sends the invite through
  any chat, and he opens it whenever he likes, no need to be online together. Rules:
  - The link is the key: anyone who gets it can join, so the app says to share it only with people
    you want in the group.
  - The invite can carry an optional expiry (default 7 days). Enforced by the app, not the relay, so
    it is a soft limit, but an old link does not stay valid forever in some chat.
  - The group is told when someone joins ("Ola joined"), so an unexpected guest is noticed.
  - If a link leaks: rotate the group key and send a fresh invite to the right people (same
    mechanism as removing a rider).
  - To work for someone without Curveon the link must be `https` to a small page that explains and
    points to the download (APK until Play). That needs a domain, so the `https` link moves from
    "later" to "required" for remote invites.
- **Scanning works in both apps.** A tablet fixed to the handlebars is awkward to scan with, so the
  companion on the rider's phone scans (and can show its own QR), then tells the tablet over
  Bluetooth. A rider with only Curveon on a phone ("This device") scans inside Curveon. The camera
  permission is asked only when scanning starts. Fallbacks: open the shared link, scan with the
  phone's normal camera app, or paste the link / type a short code. If both apps are on one phone,
  the companion handles invites when it is paired with a tablet, otherwise Curveon does.
- **Everything is end-to-end encrypted** with the group key (AES-256-GCM, fresh random nonce per
  message, `groupId` + `riderId` as associated data). The relay sees ciphertext, sizes and times only.
  GCM authenticity also stops anyone without the key from injecting fake positions.
- **Removing a rider.** Everyone in a group shares one key, so a removed rider still holds it.
  Removing means giving everyone else a new key that he cannot read:
  - Each rider has a key pair per group (X25519); the public half travels in `hello`.
  - The group creator is **admin** (can make other admins). To remove someone, an admin makes a new
    group key and sends it separately to each remaining rider, encrypted to that rider's public key,
    and signs the message (Ed25519; the admin public key is part of the group). Riders accept key
    changes only if the signature checks out, so an ordinary member cannot kick anyone. The removed
    rider gets no copy and is left outside.
  - The relay holds the rekey message for ~30 days, so riders who are offline pick it up on return.
  - Limits to be honest about: he has already seen what was shared before, and a rider who is
    offline during the change is out of the loop until he reconnects.
  - The removed rider's app shows "You were removed" if it sees the notice, otherwise the group
    just goes quiet. Anyone can leave on their own at any time. If the admin vanishes, another admin
    can take over (hence "make admin").
  - A leaked invite link is handled the same way: rekey and invite the right people again.
- **The group lives on the riders' devices** (id + key), not on the relay. The relay creates its
  state when the first rider connects, so if it has forgotten everything (rest day, no signal for
  days) riders just reconnect, resend `hello`, and nothing needs to be set up again. A group ends when
  a rider leaves it, or at an optional end date set for the trip ("Summer holiday, until 20 July").
- **Many groups, one active.** A rider can belong to any number of groups (friends, Saturday ride,
  club trip) and picks which one is active today. Only the active group gets the rider's position and
  is shown on the map; the others cost nothing (no connection, no battery) and are not told anything.
  Switching is one tap in the group list; going back to an earlier group needs no new invite. Sharing
  with several groups at once is not planned (more complexity, weaker privacy).
- **Profile per group:** by default one profile (nickname, colour, avatar) for all groups, with the
  option to override it per group. `riderId` is already per group, so the relay cannot link groups.
- **Relay expiry:** latest positions after minutes, routes and profiles after ~30 days idle. Nothing
  is lost for good, because each device keeps shared routes locally.
- **Guessing groups:** 128-bit ids cannot be enumerated; the relay offers no listing.

### Main risk: unwanted tracking
Sharing must never be forgotten or silent. Required: persistent foreground notification while
sharing, a clear "sharing with N riders" indicator on the tablet, one-tap pause and leave, and a
prompt before joining. Position *sharing* stops automatically at the end of a ride or after a time
limit, but the *group* stays, so the next morning is one tap on "Start sharing", not a new group.

## SOS ("I need help")
- **Trigger:** a long press (about 3 s, works with gloves), then a 10 s countdown with Cancel, so an
  accidental press does not alarm anyone.
- **Receivers** get a full-screen alert over whatever they are doing, with sound and vibration, the
  rider's marker and distance, and it stays until dismissed or cancelled. Actions: "I'm coming"
  (visible to the whole group), "Navigate to him" and "Call him". No "Call 112" button: contacting emergency services is the rider's own judgement, outside this app.
- **Sender** sees delivery ("received by 2 of 3") and a clear warning if it has not got through
  (no coverage), so he knows to call someone himself.
- **Relay** keeps an active `sos` until everyone has acknowledged it. No Google push is needed; the
  companion already holds a connection while a ride is on.
- **Not an emergency service.** The app says so plainly (also needed for Play review). Automatic
  crash detection is left out for now: many false alarms and a larger responsibility.

## Update rate and data use
- Rough numbers (to be measured): one encrypted position is ~100-150 bytes, and each rider receives
  one from every other rider. 4 riders at 5 s is about 0.4 MB/hour (~3.5 MB for 8 h); 4 riders at 2 s
  about 1 MB/hour; 20 riders at 5 s about 2 MB/hour. Small next to music streaming (~70 MB/hour).
- Battery matters more than data: frequent small sends keep the radio awake, so sending less often
  is the main saving.
- **Smoothness comes from the receiver, not the rate:** it animates each marker forward using the
  last speed and heading and corrects when the next position arrives, so ~5 s looks smooth.
- **Default ~5 s while moving;** adaptive (faster in bends or at high speed, ~30 s when stopped,
  slower for riders far away); a **data saver** option (~15 s) for riders with little data or a
  foreign subscription (roaming is free in the EEA, not elsewhere). The app shows data used today.
- Cloudflare free tier checked 2026-10-11 (Durable Objects docs): 100 000 requests/day with incoming
  WebSocket messages counted 20:1 (outgoing free), 13 000 GB-s/day active duration, SQLite storage
  only with 100 000 row writes/day, 32 MiB max incoming message, ~1000 req/s soft limit per object.
  Messages are no problem (4 riders x 8 h is ~1 150 requests-equivalent, 20 riders ~5 800). Active
  duration is the real limit: messages every 5 s keep the object awake, roughly 3 600 GB-s per group
  per 8 h (assuming 128 MB billing, not confirmed), so the free tier covers about 3-4 such groups
  a day: plenty for friends, not for a public app. Paid: 400 000 GB-s/month included, then
  $12.50 per million GB-s, about $0.05 per 8 h group-day, plus the Workers Paid subscription (price
  not verified; check). Longer intervals while stopped (30 s) let the object hibernate and cost nothing.

## Relay (Cloudflare Workers + Durable Objects, one object per group)
Check current free-tier limits before committing; the traffic is tiny (about 100 bytes per position
per rider every 5-10 s). Fall back to a small VPS (~50 NOK/month) if limits do not fit.

- `GET /g/<groupId>` upgrades to WebSocket (hibernating, so idle groups cost nothing).
- The relay also keeps the latest `hello` per rider (with avatar) so a late joiner gets everyone's
  picture straight away. 30 riders x 8 KB is still tiny.
- Client frame: `{ "t": "pos"|"route"|"hello", "r": "<riderId>", "n": "<nonce>", "c": "<ciphertext>" }`.
- Relay broadcasts to the other sockets and stores only rare things (latest `hello` per rider, last
  few `route` frames, `rekey`, active `sos`), replaying them to a newly connected client. **Positions
  are not stored:** they are relayed and forgotten, since the next one arrives in seconds. (Storing
  each one would hit the free tier's 100 000 row writes/day after about 4 group-days.)
- Limits: 30 sockets per group, frame max ~512 KB (GPX gzipped), per-socket rate limit, max groups
  per IP per hour. Nothing else is logged.
- Reconnect with backoff; the relay needs no state beyond the above.

## If the app becomes popular (cost and scale)
- On Cloudflare the cost grows with the number of active groups (each keeps an object awake): very
  roughly $0.02-0.05 per group-day, so thousands of active groups a day means thousands of dollars a
  month. Rough order of magnitude only.
- A plain VPS scales much more cheaply: cost follows CPU, bandwidth and memory, and one decent
  server (10-20 EUR/month) should hold tens of thousands of WebSocket connections (to be tested).
- So keep the relay portable: the logic (copy an encrypted message to the others in the group,
  keep the few rare things) is a small pure module with a thin Cloudflare adapter, and could get a
  VPS adapter later. Plain WebSocket protocol; the client does not care where it runs.
- The app has a **relay address** setting, so anyone can run their own relay and it is an escape
  hatch if the default one gets too expensive.
- Cost control either way: cap concurrent groups and connections per IP ("service is full" rather
  than a surprise bill), drop idle connections quickly, sharing stops automatically after a ride,
  and set a spending alert with the host.
- Who pays if it grows (voluntary support, or riders running their own relay) is undecided. No
  payment system: that would go against "no accounts".

## Hosting options (undecided; the rider cannot afford usage-based costs)
The point is a fixed price with a cap, not pay-per-use.
1. **Small fixed-price VPS** (about 4-5 EUR/month, e.g. Hetzner). Cannot get more expensive; if too
   many use it, it slows down or says "full". Probably holds thousands of riders (to be tested).
   Needs someone to keep it running; a small process. Leading candidate.
2. **Other people's public servers** (Nostr relays, MQTT brokers, ntfy.sh): free and nothing to run,
   and E2E encryption keeps content private. But it depends on their goodwill and limits, and they
   may block a large app. Fine for prototyping, not as the lasting answer.
3. **Phone to phone with Nearby Connections:** no server, free, works without internet, but only
   within tens to ~100 m and needs Google Play services. A useful addition when riders are close,
   with the relay for when they are apart.
4. **Riders host their own relay** (Raspberry Pi etc.): zero cost for the developer, but not
   "no setup". Possible for a few, not the main path.
Cloudflare stays an option for a prototype or small scale (free tier is enough for friends).

## Messages inside the encrypted payload
- `pos`: lat, lon, speed, heading, accuracy, timestamp, optional battery and "paused", plus movement:
  `stopped` and `stoppedSince`. The sender decides (for example under ~3 km/h for more than a minute,
  so a red light does not count), so everyone sees the same state. While stopped the position is sent
  less often (about every 30 s) and speeds up again when he moves, which saves battery.
- `hello`: nickname, colour, public key and avatar (sent on join and on change, not with every
  position). The avatar is one of three alternatives for the same slot on the marker:
  - **Photo:** a small square image (about 96 px, JPEG/WebP, at most ~8 KB) picked from the phone's
    gallery and cropped round. Receivers cache it per `riderId`.
  - **Emoji / icon:** chosen from a fixed, curated set bundled in the app (bike, helmet, mountain ...).
    Sent as a short id (a few bytes); every device draws the same artwork. No free emoji input.
  - **Initials** on the rider's colour, when nothing else is chosen.
- `route`: name, kind (planned route / GPX track), gzipped GPX or the route's own format, id.
- `sos`: rider asks for help; high priority, carries position, stays active until cancelled.
  `sos_ack`: a rider says "I'm coming" (and every device acknowledges receipt, so the sender sees
  delivery). `sos_cancel`: the sender ends it.
- `leave`: rider is going away.
- `rekey`: admin-signed, new group key encrypted separately to each remaining rider's public key.

## Bluetooth protocol additions (`:protocol`, bump `VERSION`)
Tablet -> companion:
- `GroupCreate` / `GroupJoin(invite)` / `GroupLeave` / `GroupPause(on)` / `GroupSelect(id)` (set the active group)
- `MyPosition(lat, lon, speed, heading, ts)` while sharing
- `ShareRoute(name, data)`
- `SosStart` / `SosCancel` / `SosAck`

Companion -> tablet:
- `GroupList(groups, activeId)` and `GroupState(name, members, status: connected | offline | paused, sharingUntil)` for the active group
- `GroupMembers(list of rider: id, nickname, colour, avatar)` sent when someone joins or changes profile
  (images travel once, like `MediaArt`, and the tablet caches them)
- `GroupPositions(list of rider: id, lat, lon, heading, ts)` which stays small and refers to members by id
- `GroupSos(from, position, state, acks)` shown as a full-screen alert on the tablet
- `GroupRoute(from, name, data)` which the tablet offers to open or save
- `GroupInvite(uri)` so the tablet can show the QR to friends
- (Invites scanned or opened on the phone arrive as `GroupJoin(invite)` handled by the companion itself)

The tablet only draws markers and shows "last seen 4 min ago"; it holds no keys and no network code.
The companion queues outgoing positions during coverage gaps and sends only the latest.

## Modules
- New `:groups` (pure Kotlin): crypto, invite parse/build, relay client, outgoing queue. Used by
  `:companion` and by Curveon's "This device" mode. Unit-test crypto and invite round trips.
- `:companion` gets `INTERNET` and a foreground service of the right type; today it has no network
  permission, so the status UI must say plainly when it is sharing.
- Profile: nickname, colour and avatar are set once on the phone (companion) and reused for every
  group. A photo never leaves the rider's devices unencrypted. Markers on the map show the avatar
  (round photo, icon or initials) with a coloured ring, heading as a small arrow while moving (a quiet pause dot while stopped, and tapping the marker
  says "Ola has been stopped for 12 min"; no alarms or popups), and a faded look when "last seen" is old.
- Tablet UI: group entry in the settings/menu (no button clutter), markers on the map, group sheet
  with members, invite QR, share route, pause/leave.
- Relay: small TypeScript worker in `tools/relay/` (or its own repo), deployed from the rider's account.

## Play Store notes (for later)
- **Read first: [google-play.md](google-play.md) → «Kjøregrupper og Play».** Groups give the companion `INTERNET`,
  which changes the privacy policy (`privacy/`), the Data safety draft and the call log exception text
  (`google-play-call-log.md`). Those files say what to rewrite when groups ship. Keep `:groups` separate from `:phone`.
- Background location needs an in-app disclosure before the permission prompt, a Play Console
  declaration and usually a short demo video. Companion probably needs no GPS of its own (position
  comes from the tablet over Bluetooth); "This device" mode does.
- Foreground service types: location, plus connected device for Bluetooth.
- Privacy policy and Data safety form: no accounts, no identifiers, positions only end-to-end
  encrypted with a key the service never has, deleted after the TTLs above.
- Both tablet app and companion are reviewed; notification-listener access gets extra scrutiny.
- With public users the relay needs abuse limits, and someone to keep it running.

## Open questions
- `https` invite link needs a domain and a tiny landing page. Start with scheme + QR for testing, but
  remote invites to people without the app need the `https` link.
- Tablet showing the invite QR vs. the phone? Probably both.
- Avatar: decided, photo or curated emoji/icon or initials. Open: which icons go in the set.
- Marker clutter beyond ~8 riders: collapse names, show "Ola, 400 m behind".
- Admins: creator is admin and can promote others. Decide how many admins and how handover works.
