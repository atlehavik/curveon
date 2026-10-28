# Play Console: Data safety (utkast)

Utkast 2026-10-18, samsvarer med personvernerklæringen i `privacy/`. Skjemaet er på engelsk; svarene under er det
jeg foreslår å krysse av. Jeg har ikke sett selve skjemaet, så kategorinavn kan avvike litt. Skjønnsmessige valg er
merket **(skjønn)**. Hovedappen = `com.curveon.app`, companion = `com.curveon.companion`.

Grunnlag: kartlegging av koden 2026-10-18 (all nettrafikk: `PlaceSearch.kt`, `MapDownloads.kt`, `RoutingData.kt`,
online-kartstilene; ingen analyse, reklame, Firebase eller Play Services-bibliotek; companion har ikke `INTERNET`).

## Overordnet

| Spørsmål | Hovedappen | Companion |
|---|---|---|
| Samler appen inn eller deler data? | Ja (søk og posisjon, se under) | **Nei** |
| Er all data kryptert under overføring? | Ja (HTTPS) | Ikke aktuelt |
| Kan brukere be om sletting? | Ikke aktuelt: ingen kontoer, utvikler har ingen data (avinstallering fjerner alt) | Ikke aktuelt |
| Følger «Families»-policyen? | Nei, ikke rettet mot barn | Nei |

For companion: alt den leser går over Bluetooth til brukerens egen skjerm. Den bruker internett bare på vegne av et nettbrett uten nett: reléet for grupper (kryptert, som i hovedappen) og værvarsel fra MET Norway (midten av en rute på ca. 10 km, når rytteren planlegger). Det siste er **Location > Approximate location**, samlet for App functionality, ikke delt (MET er tjenesteleverandør), valgfritt (Vær av i Curveon). Før companion legges på Play må svaret derfor ikke lenger være «no data collected or shared».

## Hovedappen: data som sendes fra enheten

| Kategori / type | Samlet? | Delt? | Formål | Valgfri? |
|---|---|---|---|---|
| **Location > Precise location** (posisjon sendt til Photon ved stedssøk) | Ja | Nei **(skjønn)** | App functionality | Ja (brukeren velger å søke på nett) |
| **Location > Approximate location** (midten av en rute på ca. 10 km sendt til MET Norway for værvarsel når rytteren planlegger; av i Innstillinger → Navigasjon → Vær) | Ja | Nei **(skjønn: MET er tjenesteleverandør for funksjonen)** | App functionality | Ja |
| **App activity > In-app search history** (søketekst til Photon og Kartverket) | Ja | Nei **(skjønn)** | App functionality | Ja |
| **Photos and videos > Photos** (bilder rytteren legger ved en feilmelding/et forslag) | Ja | Ja **(skjønn)** | App functionality (support) | Ja |
| **App activity > Other user-generated content** (tittel og tekst i feilmelding/forslag) | Ja | Ja **(skjønn)** | App functionality (support) | Ja |
| **App info and performance > Crash logs** (starten av siste krasjrapport, bare hvis avkrysset) | Ja | Ja **(skjønn)** | Analytics / App functionality | Ja |
| **App info and performance > Other app performance data** (appversjon, enhetsmodell, Android-versjon i rapporten) | Ja | Ja **(skjønn)** | App functionality | Ja |

**Feilmeldinger og forslag** (Innstillinger → Hjelp, `Feedback.kt`, reléets `POST /feedback`): bare når rytteren trykker
Send. Det blir en *offentlig* GitHub-sak, så vi krysser «delt» (GitHub er ikke bare vår underleverandør når innholdet
publiseres). Play unntar riktignok overføring brukeren selv setter i gang og forventer, men offentlig publisering er
tryggest å deklarere. Bildene velges med systemets bildevelger (ingen lagringstillatelse) og kodes om til JPEG uten EXIF.
Krasjrapporter sendes **bare** her, og bare med rytterens avkrysning; ellers gjelder linja om krasjrapporter under.

**Skjønn: «delt» eller ikke.** Play regner ikke overføring til en tjenesteleverandør som behandler data på våre vegne som
«deling». Photon og Kartverket er geokodingtjenester vi bruker for å svare på søket, så vi svarer *ikke delt*. Er du
usikker, er det trygt å krysse «delt med tredjepart (formål: app functionality)». Det er litt mer å forklare, men
ikke feil.

Ikke avkrysset fordi det ikke forlater enheten eller ikke samles av oss:
- GPS-spor, turer, lagrede ruter og steder (lagres bare i appen; loggen `navtrace` slettes etter 14 dager).
- Kontakter, anropslogg, varsler og mikrofon: leses på telefonen og går over Bluetooth til brukerens egen skjerm.
  **(skjønn)** Vi svarer at dette ikke er «collected»: det går aldri til utvikler eller tredjepart. Skulle Google
  mene at overføring til brukerens eget andre apparat skal deklareres, legg inn Contacts, Calls/call log og Messages
  med formål App functionality, ikke delt.
- Kartfliser og nedlastinger (OpenFreeMap, Protomaps, BRouter): bare IP-adresse og område. IP-adresse som
  nettverksmetadata hos mottakeren er ikke noe appen «samler».
- Krasjrapporter: lagres på enheten (`crashes/`), og sendes bare når rytteren legger dem ved en feilmelding (se over).

## Når kjøregrupper kommer (`groups.md`)

Svarene over gjelder uten grupper. Når gruppene sendes, må skjemaet (og personvernerklæringen) oppdateres for **begge** apper:

- Companion får `INTERNET`: «No data collected» holder ikke lenger automatisk.
- Posisjon, kallenavn, farge, bilde og delte ruter sendes til reléet, ende-til-ende-kryptert med en nøkkel reléet ikke har.
  Svar på «Is data encrypted in transit»: ja. Ende-til-ende-kryptert data utvikleren ikke kan lese er etter Googles
  definisjon ikke «samlet inn», men bare hvis det stemmer i koden **(skjønn, sjekk definisjonen i Console når den tid kommer)**.
- Reléet ser IP-adresse, `groupId`, `riderId`, tidspunkt og størrelser. Mest sannsynlig må **Device or other IDs**
  deklareres (samlet, ikke delt, formål App functionality) **(skjønn)**.
- Funksjonen er valgfri og av som standard; det skal stå i skjemaet («Data collection is optional»).
- SOS-funksjonen må stå som ikke en nødtjeneste (også for Play-vurderingen).

## Det som må avklares før innsending

1. **Android-sikkerhetskopi.** `app/src/main/AndroidManifest.xml` har `allowBackup="true"`, og
   `data_extraction_rules.xml` / `backup_rules.xml` er tomme maler. Da sikkerhetskopierer Android appens data
   (lagrede ruter, turer, innstillinger) til brukerens Google-konto. Det er brukerens egen backup, men dataene
   inneholder posisjoner. Forslag: velg bevisst, enten (a) la det stå og la erklæringen si det (står allerede i
   personvernerklæringen), eller (b) skru av backup / ekskluder turer og ruter. Jeg har ikke endret noe uten å spørre.
   (Companion har `allowBackup="false"`.)
2. **E-post og navn** i personvernerklæringen (`[name]`, `[email address]`). Skjemaet krever en kontakt-e-post.
3. **Hosting** av personvernerklæringen på en offentlig URL. Play trenger lenken i selve oppføringen og i appen
   (forslag: en lenke i Innstillinger → Hjelp).
4. Taletjenesten: stemmesvar bruker Androids `SpeechRecognizer` (`EXTRA_PREFER_OFFLINE` er satt). Er ingen offline
   språkpakke installert, kan telefonens taletjeneste (Google) bruke nettet. Det er telefonens tjeneste, ikke vår, og står
   i personvernerklæringen. Skulle Google likevel kreve det, er dette den kandidaten til «Audio > Voice or sound recordings».
