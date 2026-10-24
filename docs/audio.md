# Lyd: varsler og stemmeassistert navigering

Arbeidsnotat, oppdateres etter hvert som arbeidet går. Startet 2026-10-16.

## Mål

- **Varsler for navigering** som et alternativ til stemme: subtile lyder som ikke plager.
- **Varsler for feilkjøring:** også subtile, og kan slås av og på hver for seg.
- **Stemmeassistert navigering** med stemmer som lastes ned i appen under Innstillinger. Kilden må
  være åpen og gratis, også i framtiden. Skandinavisk og engelsk prioriteres.

## Hvor lyden spilles

- **Kun mobil:** Curveon spiller selv, rett til hjelmen.
- **Nettbrett + telefon:** lyden går til hjelmen via telefonen (companion). Nettbrettet bestemmer
  hva som sies og når, og sender en kort melding over den eksisterende linken (`:protocol`):
  `PlayCue(kind)` eller `Speak(text, lang)`. Companion spiller bare av.
- Teksten (ordlyd, avstander, veinavn, språk) lages bare ett sted, på enheten som navigerer.
- Avspilling: `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE` med lydfokus `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`,
  så musikken senkes et øyeblikk i stedet for å pause.

## Lyder (varsler)

- Vi lager lydene selv med et skript. Da eier vi dem, de er gratis og tar noen få kB, og de ligger i
  både `app` og `companion` (eller `:phone`).
- Korte (0,2–0,4 s) og myke.
- **Sving:** en myk totone før svingen og et lite «tikk» ved svingen. Lyden legges i venstre eller
  høyre øre etter svingretningen (intercomen har stereo).
- **Tidspunkt:** etter tid til svingen, ikke faste meter, så det passer både på landevei og i by.
- **Feilkjøring:** én dyp, dempet dobbeltlyd når `offRoute` slår inn, uten gjentakelse. En lys
  bekreftelse når ny rute er klar eller du er tilbake på ruten. En egen lyd når rytteren må velge
  (`OffRouteDecision.Ask` / `wrongTurn`).
- **Koblingspunkter i koden:** `Navigator.kt` (`maybeReroute`, `adopt`, `wrongTurn`) og
  `NavigationProgress` (`distanceToNextManeuver`, `nextManeuver.type`, `offRoute`, `arrived`).

## Stemme

### Motor

- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx), Apache-2.0, offline på Android, kjører
  Piper/VITS-modeller. Siste versjon sjekket: v1.13.8 (2026-10).
- Til uttale bruker den **eSpeak NG (GPL-3)**. En APK med eSpeak må deles under GPL-3. Curveons egen
  kode kan fortsatt være MIT. Må stå i `THIRD_PARTY_LICENSES.md`. **Ikke avklart med rytteren ennå.**

### Stemmer: bare de som er trent fra bunnen av på åpne data

De fleste Piper-stemmene er videretrent fra den amerikanske «lessac»-stemmen. Lessac-dataene er
[kun for forskning, ikke kommersiell bruk](https://www.cstr.ed.ac.uk/projects/blizzard/2013/lessac_blizzard2013/license.html).
Disse unngås derfor: `no_NO-talesyntese`, `da_DK-talesyntese`, `sv_SE-lisa` (videretrent fra
talesyntese), `en_GB-alba`, `en_US-lessac/ryan/amy/libritts_r`, `en_GB-jenny_dioco`.

| Språk | Stemme | Opplæring | Datalisens | Størrelse |
|---|---|---|---|---|
| Norsk | `no_NO-nvcc-medium` (10 talere) | trent fra bunnen | CC0 (Språkbanken, NB) | 77 MB |
| Svensk | `sv_SE-nst-medium` | trent fra bunnen (KBLab) | CC0 | 63 MB |
| Engelsk | `en_GB-cori-medium` | trent fra bunnen | Public domain (LibriVox) | 64 MB |
| Dansk | ingen ren stemme i dag | – | – | – |

- `sv_SE-alma-medium` (CC BY 4.0) kan være et alternativ, men modellkortet sier ikke om den er
  trent fra bunnen. Må sjekkes.
- **Dansk:** Språkbanken har et dansk datasett med CC0 (oai-nb-no-sbr-21), så vi kan trene en ren
  stemme selv senere. Fram til da brukes telefonens innebygde tale (Android `TextToSpeech`).
- Kilde: [rhasspy/piper-voices](https://huggingface.co/rhasspy/piper-voices). Hver stemmes
  `MODEL_CARD` sjekket 2026-10-16.
- Int8-kvantiserte varianter kan kanskje gjøre filene mindre. Kvaliteten må testes.

### Nedlasting og hosting

- Vi legger egne kopier av de valgte modellene som filer på Curveons GitHub-releases (gratis for
  åpne repoer, uavhengig av Hugging Face). Lisens og modellkort følger med.
- Stemmen lastes ned under Innstillinger, på enheten som spiller lyden. I nettbrett +
  telefon-oppsettet laster telefonen ned selv, og nettbrettet viser fremdriften. Overføring over
  Bluetooth (ca. 70 MB) er bare en reserveløsning.
- Følger «offline first»-regelen: en stemme er en stor ressurs og lastes ned når den trengs. Vi bør
  vurdere å tilby den sammen med kartet for landet.
- Er stemmen ikke lastet ned ennå, brukes telefonens innebygde tale.

## Innstillinger (forslag)

Under **Innstillinger → Navigasjon → Lyd**:

- **Navigeringsvarsler:** Av / Lyder / Stemme. Velges Stemme første gang, vises en knapp for å laste
  ned stemmen for språket som er valgt under Visning, med størrelse.
- **Varsel ved feilkjøring:** På/Av. Følger valget over, så det blir lyd eller talt melding.

## Kostnader

- **Appstørrelse:** sherpa-onnx med onnxruntime gjør hver app ca. 10–15 MB større (companion er
  ca. 6 MB i dag). Stemmen kommer i tillegg, som nedlasting.
- **Batteri:** det tar ca. 0,1–0,3 s å lage en setning på Pixel 9a, og det skjer bare når noe sies.
  Lydene koster nesten ingenting. Ikke målt ennå.
- **Intercom:** mange intercomer bruker opptil et sekund på å «våkne» når de får lyd, og da kan
  første stavelse bli borte. Vi legger inn litt stillhet før lyden. Må testes på rytterens hjelm.

## Google Play

- Stemmemodeller er data (ONNX), ikke kjørbar kode, så det er lov å laste dem ned etter
  installasjon.
- Native biblioteker må være 16 KB-justert (Android 15+). sherpa-onnx har rettet dette
  ([#2413](https://github.com/k2-fsa/sherpa-onnx/issues/2413),
  [#3291](https://github.com/k2-fsa/sherpa-onnx/issues/3291)). Sjekk APK-en med
  `zipalign -c -P 16 -v 4` når biblioteket er lagt inn.
- GPL-3 (eSpeak NG) er lov på Play, men kildekoden må være tilgjengelig (den er det, på GitHub).
- Ingen nye tillatelser trengs. Avspilling skjer fra tjenester som finnes allerede: companions
  forgrunnstjeneste av typen connectedDevice, og navigasjonen (location) når Curveon kjører alene på
  mobilen.
- Data safety-skjemaet endres ikke: ingenting sendes fra enheten. Nedlastingen henter bare filer fra
  GitHub.

## Rekkefølge

1. Lyder for navigering og feilkjøring, på mobil og via companion (melding i `:protocol`).
2. Tale med telefonens innebygde tale: gir Stemme-valget raskt, og vi lager ordlyden på begge språk.
3. ~~Piper-stemmer via sherpa-onnx~~ (strøket 2026-10-16).
4. ~~Trene en egen dansk stemme~~ (strøket; dansk bruker Androids stemme).

## Beslutninger

- 2026-10-16: **sherpa-onnx og Piper-stemmer er strøket.** Androids innebygde talemotor (Googles
  norske stemme) var veldig bra. Det som står om sherpa-onnx, Piper, lisenser og hosting under
  «Stemme» er beholdt som bakgrunn, men skal ikke bygges. GPL-3 blir dermed ikke aktuelt.

- 2026-10-16: GPL-3 for APK-ene (pga. eSpeak NG) er greit. Alle forslagene over er godkjent; steg 1 startet.

## Status

### Steg 1: lyder (ferdig 2026-10-16, venter på test på hjelm)

- `Message.PlayCue(cue, side)` i `:protocol`; eldre companion ignorerer den.
- `CueSounds` (`:phone`) lager lydene i kode (44,1 kHz stereo, 0,3 s stillhet først, panorert med
  30 % i det andre øret). `CuePlayer` spiller dem som navigasjonslyd med `MAY_DUCK`, og hopper over
  når den ikke får lydfokus (samtale).
- `NavCues` (`app`, `data/routing`) bestemmer når: forvarsel ved `fart × (12 + 2) s` (80–600 m), tikk ved
  `fart × (3 + 2) s` (30–160 m); de 2 s dekker GPS-forsinkelse, 1 Hz-fixer, stillheten først og
  Bluetooth (tikket kom for sent på demotur 2026-10-16). Fart regnes som minst 30 km/t. Forvarsel droppes hvis det ville kommet
  under 3 s før tikket. Feilkjøring: én lyd, ikke igjen på 30 s; «tilbake» når ruten er funnet eller
  rytteren er tilbake; spørsmålslyd når `wrongTurn` vises. Ikke mens rytteren kjører til starten
  (`approach`). Testet i `NavCuesTest` og `CueSoundsTest`.
- Avspilling: `PhoneCompanionManager.playCue` sender til telefonen når en companion er koblet til,
  ellers spiller enheten selv (Curveon på mobil, eller nettbrett uten telefon).
- Innstillinger → Navigasjon → Lyd: «Navigeringslyder» (Av/Lyder, senere Stemme) og «Lyd ved
  feilkjøring» (på/av). Begge på som standard. Valget spiller en prøvelyd.
- Lydene som WAV for å lytte på PC: `~/develop/Curveon-data/cue-sounds/`.

- Demotur 2026-10-16: lydene hørtes bra ut, men tikket kom når svingen var i gang. Flyttet 2 s
  tidligere (`LAG_S`). Feiltilstandene er ikke prøvd ennå.

### Steg 2: tale med Androids innebygde talemotor (ferdig 2026-10-16, venter på test)

- Valget «Stemme» under Navigeringslyder. Alt som ellers gir lyd, blir sagt; feilkjøring også.
- `Message.Speak(text, language, interrupt, cue, side)`: teksten lages på enheten som navigerer
  (`NavSpeech` i `app`), på språket appen vises på (nb-NO, en-GB, en-US med mph). Den bruker de samme
  svingtekstene som skjermen: «Om 300 meter, sving til høyre inn på Stavlandsvegen, deretter hold til
  venstre». Ved svingen: «Sving til høyre». Avstander rundes til 10/50/100 m (fot/miles med mph).
- `Speaker` (`:phone`) bruker `TextToSpeech`, startes først når noe skal sies, og velger en
  nedlastet offline-stemme når den finnes (Google velger ellers nettstemmen når det er dekning).
  Mangler stemmen for språket, spilles lyden i stedet (`cue`). Tikket ved svingen avbryter et
  forvarsel som fortsatt leses opp. 0,3 s stillhet først, som for lydene.
- `<queries>` for `TTS_SERVICE` i `:phone`-manifestet (kreves fra Android 11 for å finne motoren).
  Ingen nye tillatelser.
- Prøvd på X7: norsk Google-stemme, `nb-no-x-tfs-local` (offline). Ikke prøvd via companion ennå.

### Etter første test av stemmen (2026-10-16)

- Valg «Forvarsel» (på/av) når Stemme er valgt: av gir bare «Sving til høyre …» ved svingen, ingen
  «Om 300 meter». (Lyder er uberørt.)
- Tips om offline-stemme: talemotoren melder etter hver setning om stemmen er nedlastet
  (`Message.SpeechVoice`: OFFLINE / ONLINE_ONLY / MISSING). Er den ikke det, viser Innstillinger hvor
  den lastes ned (på telefonen, eller her med en knapp som åpner talemotorens nedlasting).
  Kun-nett-stemme uten dekning gir lyden i stedet; feiler talen underveis, spilles lyden.
  Companion trenger `ACCESS_NETWORK_STATE` (vanlig tillatelse, ingen Play-erklæring).

## Åpne spørsmål

- Treffer tidspunktene og volumet på hjelmen? Justeres etter test.
- Er setningene for lange når svingene kommer tett? Forvarselet kan da kortes ned (uten vei/«deretter»).
