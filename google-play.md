# Google Play: status og plan

Notater fra samtalene 2026-10-11/12 og 2026-10-18. Ikke committet.

## Status

- Opplastingsnøkkel: `~/.curveon-signing/upload-key.jks`, passord i `~/.curveon-signing/signing.env`
  (alias `upload`). **Ta backup av hele mappen.** Mistes nøkkelen kan Play nullstille den, men det er tungvint.
- Begge appene har signert bygg for Play:
  `source ~/.curveon-signing/signing.env && ./gradlew :app:bundlePlay :companion:bundleRelease`
  - `app/build/outputs/bundle/play/app-play.aab` (hovedappen, `com.curveon.app`, ca. 30 MB)
  - `companion/build/outputs/bundle/release/companion-release.aab` (telefon, `com.curveon.companion`, ca. 6 MB)
- **`play`-byggtypen i `:app`** (2026-10-18) er `release` uten selvoppdatering: `BuildConfig.SELF_UPDATE` er
  `false`, `src/play/AndroidManifest.xml` fjerner `REQUEST_INSTALL_PACKAGES`, og oppdateringssjekken
  (oppstart og «Se etter oppdatering» i Settings → Help) er av. «Hva er nytt» og GitHub-lenken er der fortsatt.
  GitHub-APK-en (`assembleRelease`) har alt som før. Companion har ingen selvoppdatering.
- Kartdata ligger ikke i appen (lastes ned i Settings), så størrelsen er ikke et problem.
- `.gitignore` dekker `*.jks`, `*.keystore`, `signing.env`.
- Release-bygg skjuler utviklerverktøy via `BuildConfig.DEV` (demo data, fake OBD, companionens test-kort).
  Demoturen er med i alle bygg.
- GitHub-release med signerte APK-er (sideloading): https://github.com/atlehavik/curveon/releases
- Øk `versionCode` for hver opplasting til Play.
- **Mål:** Play-versjonen skal også være den du bruker selv, med dagens telefoni-UI.

## Viktig: hovedappen har også telefontillatelsene

`:app` bruker `:phone` (som «Denne enheten» på Pixel, der hovedappen er frittstående), og manifestene slås sammen. Hovedappen har derfor
`READ_CONTACTS`, `READ_CALL_LOG`, `CALL_PHONE` og `RECORD_AUDIO` akkurat som companion. Begge appene må
altså igjennom samme vurdering; «hovedappen først» gjør det ikke enklere.

## Telefontillatelser og Googles regler

Kilde: https://support.google.com/googleplay/android-developer/answer/10208820 (lest 2026-10-18).

- **`READ_CALL_LOG`** er begrenset. Tillatt for standard ringeapp, eller etter unntak som søkes om i Play Console
  (Permissions Declaration Form) og vurderes av Google. Unntak som passer Curveon:
  - **«Companion apps for connected devices»** (klokker, biler, smarthus): companion er følgeappen til tavla.
  - **«In-vehicle hands-free use and projected display»**: samtaler på tavla mens du kjører.
  Apper som 1881 Hvem Ringer (anropslogg og SMS-logg, uten å være standard ringeapp) bruker mest sannsynlig
  unntaket «Caller ID, spam detection or blocking».
  Appen må være ubrukelig uten tillatelsen (kjernefunksjon), og dataene kan ikke brukes til noe annet.
  Ferdig utkast til søknaden (tekst per app og manus til demovideo): [google-play-call-log.md](google-play-call-log.md).
  Begge tabellradene i Googles policy dekker `READ_CALL_LOG` (companion: «connected device companion», hovedappen:
  «in-vehicle hands-free use and projected display» fordi den er navigasjon). Utfallet er ikke garantert.
  Hovedappen faller under navigasjonsraden også når den er sin egen companion på Pixel. Reserveplan hvis det avslås: egen
  avskalert Play-variant (anropslogg erstattes av en liste Curveon fører selv over anrop den ser), eller
  READ_CALL_LOG bare i companion.
- **`SEND_SMS`** (lagt til 2026-10-21, Telefon → kontakt → SMS: rytteren snakker meldingen, telefonen sender den
  med `SmsManager`) er begrenset på samme måte som `READ_CALL_LOG`, og dekkes av de samme to unntaksradene
  («connected device companion» og «in-vehicle hands-free»). Den må med i samme erklæring og søknad. Uten
  tillatelsen finnes ingen håndfri vei: `ACTION_SENDTO` åpner SMS-appen og krever et trykk på telefonen. Avslås den,
  fjernes SMS-knappen i Play-varianten (stemmesvar på varsler bruker varselets egen svarhandling og trenger den ikke).
- **`CALL_PHONE`**: Googles policyside om SMS og anropslogg nevner bare SMS- og anropsloggtillatelsene, ikke
  `CALL_PHONE`. Den er altså trolig ikke begrenset (sjekkes i erklæringsskjemaet i Play Console).
  Vi bruker den i `PhoneBook.placeCall` (`TelecomManager.placeCall`). **Ikke bytt til `ACTION_DIAL`:** den må
  starte en aktivitet, og det får en tjeneste i bakgrunnen (telefonen i lommen) ikke lov til, så «ring fra
  tavla» ville sluttet å virke. Brukt i `PhoneBook.kt`, `PhoneLinkDialog.kt`, `companion/.../MainActivity.kt`.
- **`READ_CONTACTS`** og **`RECORD_AUDIO`**: ikke begrenset, men må stå i Data safety og ha tydelig forklaring i
  appen før brukeren blir spurt.
- **Notification listener**: erklæres i Play Console. Brukes til varsler, musikkøkter og samtaler.

## `QUERY_ALL_PACKAGES` er fjernet (2026-10-18)

Erstattet av `<queries>` i `phone/src/main/AndroidManifest.xml`: alle apper med startikon (`MAIN`/`LAUNCHER`)
og alle med `MediaBrowserService`. Det gir de samme appnavnene («Spotify», «WhatsApp») og musikklisten som før,
uten erklæring. Gjelder begge appene. Risiko: en app som sender varsler uten startikon vises med pakkenavn.
Sjekkes på enhet (se `device-checks.md`).

## Kjøregrupper (`groups.md`) og Play

Gruppene gjør at **companion får `INTERNET`** (relé over mobildata, slik at tavla kan være offline). Hovedappen har
`INTERNET` fra før og kjører relé-klienten selv når den er frittstående på mobil eller online tablet. Det som
endrer seg på Play-siden:

- **Setningen «companion kan ikke sende noe ut på nett» faller bort** når gruppene sendes. Den står i
  personvernerklæringen, i Data safety-utkastet og som et argument i `google-play-call-log.md`. Argumentet kan
  erstattes av at anropsloggen (og kontakter og varsler) ikke går til reléet. Det er sant, men må kunne
  forklares, og anropslogg + internett i samme app vil få mer oppmerksomhet i vurderingen. Hold koden for
  grupper (`:groups`) adskilt fra `:phone` så det er lett å vise.
- **Data safety for begge apper:** posisjon (og kallenavn, farge, bilde, ruter) sendes til reléet. Alt er ende-til-ende-kryptert
  med en nøkkel reléet aldri har. Google regner ende-til-ende-kryptert data som utvikler ikke kan lese som ikke
  «samlet inn», men det kravet må være sant i koden. Reléet ser likevel IP-adresse, tilfeldige `groupId` og `riderId`
  og tidspunkt/størrelser: dette er trolig «Device or other IDs» og må med **(skjønn)**.
- **Personvernerklæringen** må få et eget avsnitt om grupper (valgfritt, av som standard, hva reléet ser, hvor lenge det
  lagrer: posisjoner lagres ikke, øvrig ca. 30 dager) og si hvem som drifter reléet.
- **Foreground service og posisjon:** companion trenger en tjeneste av riktig type; deklarasjon og demovideo i Play Console.
- **Anbefaling: første Play-versjon uten grupper.** (Companion er nå bare status, QR-skanning og lenker; alt oppsett gjøres i Curveon selv.) Da har companion fortsatt ingen `INTERNET`, den sterkeste
  setningen i unntakssøknaden står, og første vurdering blir enklere. Gruppene kommer som en oppdatering sammen med
  oppdatert Data safety og personvernerklæring. Test gruppene i lukket testing og via GitHub-APK først.
- Domenet som `groups.md` trenger for `https`-invitasjonslenken kan også hoste personvernerklæringen (én løsning for begge).

### Venner ser alltid hvor du er (2026-10-22)

- **Posisjon deles nå også utenfor en kjøretur:** under Mine venner kan rytteren la valgte venner se posisjonen (omtrent hvert minutt)
  så lenge Curveon kjører og har GPS. Av som standard, valgt per venn, og en grønn prikk på Venner-knappen viser at noen ser deg.
  Det er «deling av posisjon med andre brukere» i Plays forstand: Data safety må si at posisjon deles (ende-til-ende-kryptert),
  og personvernerklæringen må beskrive venne-delingen. Ingen bakgrunnsposisjon (`ACCESS_BACKGROUND_LOCATION`) er lagt til: posisjonen
  kommer bare fra Curveons egen GPS mens appen kjører, så kravet om tydelig forhåndsvarsel for bakgrunnsposisjon gjelder ikke **(skjønn)**.
- **Reléet holder nå siste (krypterte) posisjon per rytter i minnet i 30 min**, så en venn som titter innom ser hvor du var sist.
  Den skrives aldri til disk, og reléet kan ikke lese den. Avsnittet i personvernerklæringen om at «posisjoner lagres ikke» må endres
  til «siste posisjon holdes kryptert i minnet i inntil 30 minutter».

- **Invitasjonslenker som App Links (2026-10-22):** `https://relay.curveon.no/join` åpner Curveon direkte, verifisert med
  `/.well-known/assetlinks.json` på reléet (`~/develop/curveon-relay/public/assetlinks.json`), som lister GitHub-release-nøkkelen og
  debug-nøkkelen. **Med Play App Signing signerer Google appen med en annen nøkkel:** dens SHA-256 (Play Console → App integrity) må legges
  til i `assetlinks.json` før Play-versjonen, ellers åpnes lenken i nettleseren (siden har da en «Åpne i Curveon»-knapp).

### Slik ble det bygget (2026-10-19), og hva det betyr for Play

- **Companion har `INTERNET` nå**, via `:phone`-manifestet (det samme manifestet gir hovedappen det den allerede hadde). Hver bygging
  av companion har det, også en Play-bygging: for «første Play-versjon uten grupper» må `INTERNET` fjernes i en play-bygging
  (`tools:node="remove"`, slik `app/src/play` gjør for `REQUEST_INSTALL_PACKAGES`) og gruppene slås av. Ikke gjort.
- **Nye biblioteker, alle Apache 2.0, ingen native kode (16 KB-kravet berøres ikke):** Tink (`tink-android`, X25519/Ed25519/HKDF),
  ZXing core (QR-kode for invitasjon), OkHttp (var der fra før, nå også i `:phone`). Ført i `THIRD_PARTY_LICENSES.md` (Tink; ZXing mangler
  der ennå).
- **Nøkler på enheten:** gruppene (nøkkel og rytter-identitet) lagres kryptert med en nøkkel i Android Keystore (`GroupStore`), og er
  utelatt fra Android-backup (`backup_rules.xml`, `data_extraction_rules.xml`). Ny telefon eller reinstallasjon betyr ny invitasjon.
- **Ny tillatelse i hovedappen:** `VIBRATE` (vanlig, gis automatisk) til SOS-varselet. Ikke brukt: `USE_FULL_SCREEN_INTENT`. SOS-varselet er et
  vindu i appen og vises bare når Curveon er åpen; på en telefon i lommen kommer ingenting (varsel for det er ikke bygget).
- **Foreground service-typen i companion er uendret** (`connectedDevice|microphone`). Å sende posisjoner over nett for en tilkoblet tavle er
  en grei, men ikke opplagt, beskrivelse av `connectedDevice`: skriv det rett ut i deklarasjonen. Posisjon hentes på tavla, ikke i companion.
- **Kamera (QR-skanning):** `CAMERA` i hovedappen, spurt først når rytteren starter skanning, med `uses-feature camera required=false` så Play ikke
  skjuler appen for enheter uten kamera. Bildet leses på enheten (ZXing, ingen Play-tjenester) og lagres eller sendes ikke. CameraX (Jetpack,
  Apache 2.0, ingen egen native kode). Skanneren ligger i en egen modul (`:qrscan`) som både Curveon og companion bruker, så companion har også `CAMERA` (kravet kommer fra modulens manifest).
- **Profilbilde:** velges med systemets bildevelger (`PickVisualMedia`, uten tillatelse) eller tas med kameraappen (`TakePicture` via en `FileProvider` i cachemappa; bildet slettes straks etter at det er beskåret). Beskjæres i appen. Bildet skaleres til 96 px (maks 8 KB) og går ende-til-ende-kryptert
  til gruppa; det er personopplysning som må med i personvernerklæringen og Data safety (valgfritt, bare til gruppa).
- **Flere enheter, én rytter:** overføringslenken inneholder rytterens private nøkler og vises bare som QR på skjermen, utløper etter 15 minutter. Ingenting
  av det går til noen server.
- **Ukryptert `ws://`** brukes aldri i produksjon: bare i debug-bygg (`debuggable`) mot lokale adresser (`GroupHub.scheme`); debug-manifestene
  tillater ukryptert trafikk (`app/src/debug`, `companion/src/debug`), release-manifestene gjør ikke det.
- **Invitasjonslenker:** `curveon://join` er registrert i både Curveon og companion (Android spør hvilken som skal brukes når begge er
  installert). `https`-formen trenger domene og App Links.
- **SOS:** appen sier at den ikke er en nødtjeneste (teksten under knappen). Ingen automatisk krasjvarsling.

## Profilbilde-stiler: ML Kit-utklipp (2026-10-22)

`play-services-mlkit-subject-segmentation` (Googles ML Kit, gratis, Google APIs-vilkår) klipper rytteren ut av
profilbildet for stilene Klistremerke / Neon / Fart. Modellen ligger i Google Play-tjenester og hentes av dem
(manifest-meta-data `com.google.mlkit.vision.DEPENDENCIES = subject_segment`, ved installasjon), ikke av appen:
ingen egen nedlasting etter installasjon, ingen nye native-biblioteker i APK-en (16 KB-kravet er uberørt), og
bildet forlater ikke enheten. Ingen nye tillatelser. Uten Play-tjenester (eller uten nett første gang) vises
bare «Vanlig». Datasikkerhetsskjemaet: ML Kit på enheten kan sende anonym bruks-/ytelsesstatistikk til Google;
nevn det under «Data delt»/«samlet» hvis Google krever det for ML Kit.

## Største risikoer hos Google

1. **`READ_CALL_LOG`** og **`SEND_SMS`**, se over. Største risiko.
2. **Erklæringer i Play Console:** notification listener, mikrofon (`RECORD_AUDIO`), foreground services
   (location i hovedappen; connectedDevice og microphone i companion). Noen krever kort demovideo.
3. Reell unntakssøknad for anropslogg trenger tekst og trolig demovideo av tavla som viser anropslogg og ringing.

## Må gjøres av deg

- Utviklerkonto hos Google Play (25 USD, identitetsverifisering).
- Ny privatkonto: lukket testing med minst 12 testere i 14 dager før produksjon.
- Personvernerklæring på offentlig URL: utkast i `privacy/` (norsk og engelsk), mangler navn og e-post og må hostes (GitHub Pages er enkleste vei; å skru det på er utad, så jeg spør først).
- Butikkoppføring: 512px-ikon, feature graphic 1024x500, skjermbilder, beskrivelse (jeg kan lage tekst og ta skjermbilder fra emulatoren).
- Data safety-skjema: utkast i [google-play-data-safety.md](google-play-data-safety.md). Avklar Android-backup (`allowBackup="true"` med tomme regler i hovedappen) før innsending.

## Åpne spørsmål

1. Begge apper samtidig. Én app først hjelper ikke (se over).
2. Søke om unntak for `READ_CALL_LOG` som følgeapp til tavla, og beholde `CALL_PHONE`? (Anbefalt.)
3. **Første Play-versjon med eller uten kjøregrupper?** Anbefalt uten (companion uten `INTERNET`, se «Kjøregrupper og Play»).
   Ikke avgjort 2026-10-18; tas opp i tråden som implementerer groups.
4. Før innsending: Android-backup i hovedappen (`allowBackup`), navn og e-post i personvernerklæringen, hosting av den,
   og lenke til den i Innstillinger → Hjelp. Se `google-play-data-safety.md`.

## Andre ting å vurdere

- **Play App Signing** signerer på nytt med en annen nøkkel. Den som har installert APK fra GitHub må avinstallere før Play-versjonen.
- Release-APK-en kan ikke installeres over ride-bygget på din X7 (annen nøkkel) uten å miste nedlastede kart. Behold ride-bygget på X7.
- Hovedappens APK er stor (ca. 99 MB): ikke minifisert, alle ABI-er. R8 + resource shrinking kan krympe den, men må testes på enhet
  (MapLibre, Room, refleksjon). Kan også fjerne ca. 20 `Log.d/v/i`-kall.
- **Lyd og stemme (se `docs/audio.md`):** bruker Androids innebygde talemotor (`<queries>` for `TTS_SERVICE`),
  ingen egne biblioteker eller nedlastinger. sherpa-onnx er strøket, så ingen GPL. Eneste nye tillatelse er
  `ACCESS_NETWORK_STATE` i companion (vanlig, gis automatisk).
- **Send tur til nettbrettet (2026-10-22):** hovedappen har `<queries>` for én pakke (`com.curveon.companion`) for å
  vise valget bare når Companion er installert. Det er innenfor Plays regler for pakkesynlighet (én navngitt pakke,
  ikke `QUERY_ALL_PACKAGES`). Companion har en eksportert `BroadcastReceiver` (`RouteForTablet$Receiver`) uten
  tillatelse, fordi Curveon fra GitHub og Companion kan være signert med ulike nøkler. En annen app kan derfor legge
  en tur i køen til nettbrettet. Det er ingen sikkerhetsrisiko for data, fordi nettbrettet alltid spør før turen kjøres,
  men en `signature`-tillatelse bør inn når begge appene signeres likt (Play App Signing).
- **Helningsvinkel fjernet (2026-10-22):** gyro og akselerometer brukes ikke lenger (`LeanSensor` er slettet).
- Lisens og tredjepartsnotiser finnes (MIT, `THIRD_PARTY_LICENSES.md`). Sjekk at OSM/Overture-kreditering vises i appen.
