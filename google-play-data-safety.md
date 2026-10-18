# Play Console: Data safety (utkast)

Utkast 2026-10-18, samsvarer med personvernerklæringen i `privacy/`. Skjemaet er på engelsk; svarene under er det
jeg foreslår å krysse av. Jeg har ikke sett selve skjemaet, så kategorinavn kan avvike litt. Skjønnsmessige valg er
merket **(skjønn)**. Hovedappen = `com.curveon.tablet`, companion = `com.curveon.companion`.

Grunnlag: kartlegging av koden 2026-10-18 (all nettrafikk: `PlaceSearch.kt`, `MapDownloads.kt`, `RoutingData.kt`,
online-kartstilene; ingen analyse, reklame, Firebase eller Play Services-bibliotek; companion har ikke `INTERNET`).

## Overordnet

| Spørsmål | Hovedappen | Companion |
|---|---|---|
| Samler appen inn eller deler data? | Ja (søk og posisjon, se under) | **Nei** |
| Er all data kryptert under overføring? | Ja (HTTPS) | Ikke aktuelt |
| Kan brukere be om sletting? | Ikke aktuelt: ingen kontoer, utvikler har ingen data (avinstallering fjerner alt) | Ikke aktuelt |
| Følger «Families»-policyen? | Nei, ikke rettet mot barn | Nei |

For companion svares «no data collected or shared»: den har ikke internett, og alt den leser går over Bluetooth til brukerens egen skjerm.

## Hovedappen: data som sendes fra enheten

| Kategori / type | Samlet? | Delt? | Formål | Valgfri? |
|---|---|---|---|---|
| **Location > Precise location** (posisjon sendt til Photon ved stedssøk) | Ja | Nei **(skjønn)** | App functionality | Ja (brukeren velger å søke på nett) |
| **App activity > In-app search history** (søketekst til Photon og Kartverket) | Ja | Nei **(skjønn)** | App functionality | Ja |

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
- Krasjrapporter: lagres på enheten (`crashes/`), ikke sendt noe sted. (Ikke «Crash logs» i skjemaet.)

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
