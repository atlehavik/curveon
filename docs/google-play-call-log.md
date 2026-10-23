# Play Console: unntakssøknad for `READ_CALL_LOG`

Utkast 2026-10-18. Teksten under er på engelsk fordi skjemaet i Play Console er det: lim inn og tilpass til
feltene Console faktisk viser (jeg har ikke sett skjemaet, bare policysiden:
https://support.google.com/googleplay/android-developer/answer/10208820).

## Hvorfor vi har rett til unntak

Googles tabell over unntak (policysiden, «Permitted uses») har to rader som dekker `READ_CALL_LOG`:

| App | Unntak | Hvorfor det passer |
|---|---|---|
| Companion (`com.curveon.companion`) | **Connected device companion apps that enable sending/receiving of SMS or calls**: «connect a mobile device to a connected device (for example a smartwatch, **automotive technology**, smart home device) and send/receive texts and phone calls». Tillatelser: `READ_CALL_LOG`, `PROCESS_OUTGOING_CALLS`, `WRITE_CALL_LOG` m.fl. | Koble telefonen til Curveon-tavla på motorsykkelen (frittstående, offline) og ringe/se anrop derfra. |
| Hovedappen (`com.curveon.app`) | **In-vehicle hands-free use and projected display**: «Apps whose core functionality (like **navigation**) directly relates to driving/mobility, especially where the user's physical interactions with a device are limited». Tillatelser: `READ_CALL_LOG` m.fl. | Navigasjonsapp for motorsykkel. Rytteren har hanskene på og kan ikke bruke telefonen mens hen kjører. |

Merk: hovedappen er frittstående på mobil og tablet, og er sin egen «companion» når den kjører på telefonen
(«Denne enheten»). Det er navigasjonsraden som bærer søknaden for den, ikke companion-raden.

Vi bruker bare `READ_CALL_LOG` (ikke SMS-tillatelsene: meldinger leses fra varsler via notification listener,
og `PROCESS_OUTGOING_CALLS`/`WRITE_CALL_LOG` er ikke i manifestet). `CALL_PHONE` er ikke i den begrensede
gruppen, men nevnes i teksten fordi den hører til samme funksjon.

## Utkast: hovedappen (Curveon)

**Core functionality that requires the permission**

Curveon is an offline motorcycle navigation dashboard. A motorcycle rider cannot hold or look at a phone while
riding, so Curveon shows the rider's recent calls (incoming, missed, outgoing) on the large handlebar display, in the
"Recents" tab of the phone screen next to the map, and lets them call back with one tap on the screen. The call log is
what that tab shows. Without `READ_CALL_LOG` the rider can no longer see who called while riding or call back
without picking up the phone, which defeats the hands-free purpose of the app.

**Why a policy exception applies**

In-vehicle hands-free use and projected display: the app's core functionality is navigation for a vehicle, and
the rider's physical interaction with a device is limited while riding (gloves, handlebars, no free hand).

**How the data is used**

- Read only when the rider's phone is connected and when the rider opens the phone screen (never in the
  background, no content observer), at most the 200 most recent entries: number, call type (incoming, outgoing, missed, rejected) and time. Names and photos come
  from the rider's own contacts (`READ_CONTACTS`) when allowed.
- Shown on the rider's own display. Not stored on a server, not sent to us or any third party, not used for
  advertising, analytics, profiling or sale. The call log is never written to or modified.
- Held in memory only (never written to a file or database by the app); cleared when the phone is forgotten or the app closes.
- Asked for with a clear explanation in the app before the system dialog, and the app works without it (the
  Recents tab then says it needs permission).

## Utkast: companion (Curveon Companion)

**Core functionality that requires the permission**

Curveon Companion connects the rider's phone to the Curveon dashboard on the motorcycle (a separate, offline
automotive display) over Bluetooth. Its only job is to relay phone events to that display: calls, messages (from
notifications), music and voice replies. It has no screen of its own for any of this. The call log is relayed so the
display can show its Recents tab (missed and recent calls) and let the rider call back hands-free.

**Why a policy exception applies**

Connected device companion apps: the app lets the user connect a mobile device to a connected device (automotive
technology) and send/receive phone calls from it.

**How the data is used**

As above. In addition, the companion app has no `INTERNET` permission, so it technically cannot send the call
log off the phone; it only speaks to the paired display over Bluetooth.

## Demovideo (trengs trolig)

Skjermopptak, ca. 60–90 s, uten tale nødvendig, med tekstplakater:
1. Parkert motorsykkel, tavla på styret, telefonen paret (companion: «Connected»). Vis permission-forklaringen og tillatelsen som gis.
2. Noen ringer og legges på.
3. Åpne telefonskjermen på tavla, fanen Recents: det ubesvarte anropet står øverst. Trykk på det og ring tilbake: telefonen ringer ut.
4. Vis at appen fungerer uten tillatelsen (kortet ber om tillatelse) og at ingenting sendes på nett (companion har ikke INTERNET: `adb shell dumpsys package com.curveon.companion | grep INTERNET` gir ingen treff; bekreftet i manifestet 2026-10-18).
Emulator har ikke Bluetooth, så opptaket må gjøres på X7 og Pixel.

## Obs: endres når kjøregrupper kommer

Setningen «the companion app has no `INTERNET` permission, so it technically cannot send the call log off the phone»
gjelder bare til `groups.md` er bygget (da får companion `INTERNET`). Da må teksten i stedet si at anropslogg,
kontakter og varsler aldri sendes til reléet eller noen server, og at gruppekoden er adskilt fra `:phone`.
Hvis første Play-versjon sendes uten grupper, kan setningen brukes som den står.

## Det som ikke er avklart

- Det eksakte skjemaet i Play Console, og om det krever én erklæring per app (trolig ja).
- Om `CALL_PHONE` blir flagget (policysiden nevner den ikke; se `google-play.md`).
- At Google godkjenner. Unntak vurderes manuelt, og «core functionality» må kunne forsvares: vi må vise at
  Recents-fanen er en reell del av navigasjonsopplevelsen, ikke et ekstra.
- Personvernerklæringen må si det samme som teksten over (anropsloggen leses, vises på egen skjerm, forlater ikke enhetene).
