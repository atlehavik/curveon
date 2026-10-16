# Google Play: status og plan

Notater fra samtalen 2026-10-11/12. Ikke committet.

## Status

- Opplastingsnøkkel: `~/.curveon-signing/upload-key.jks`, passord i `~/.curveon-signing/signing.env`
  (alias `upload`). **Ta backup av hele mappen.** Mistes nøkkelen kan Play nullstille den, men det er tungvint.
- Begge appene har signert release-bygg:
  `source ~/.curveon-signing/signing.env && ./gradlew :app:bundleRelease :companion:bundleRelease`
  - `app/build/outputs/bundle/release/app-release.aab` (tablet, `com.curveon.tablet`, ca. 33 MB)
  - `companion/build/outputs/bundle/release/companion-release.aab` (telefon, `com.curveon.companion`, ca. 6 MB)
- Kartdata ligger ikke i appen (lastes ned i Settings), så størrelsen er ikke et problem.
- `companion/build.gradle.kts` fikk signeringskonfig; `.gitignore` dekker `*.jks`, `*.keystore`, `signing.env`.
- Release-bygg skjuler utviklerverktøy via `BuildConfig.DEV` (demo ride, demo data, fake OBD, companionens test-kort).
- GitHub-release v1.0 med signerte APK-er (sideloading): https://github.com/atlehavik/curveon/releases/tag/v1.0
- Versjon er 1.0 / versionCode 1 i begge moduler. Øk `versionCode` for hver opplasting til Play.

## Største risikoer hos Google

1. **Telefon-tillatelser i `:phone`** (`READ_CALL_LOG`, `CALL_PHONE`) er begrensede. Godtas normalt bare
   for standard ringeapp. Companion blir sannsynligvis avvist med dem. Største risiko.
   Alternativ: fjern anropsloggen og bruk `ACTION_DIAL` eller en Telecom-flyt uten `CALL_PHONE` (mindre funksjon).
   Brukt i `phone/.../PhoneBook.kt` (`placeCall`, call log) og `PhoneLinkDialog.kt`, `companion/.../MainActivity.kt`.
2. **`QUERY_ALL_PACKAGES`** krever begrunnelse (står selv i manifestet at den trenger vurdering for Play).
   Forslag: bytt til `<queries>` for medie- og meldingsapper. Brukes for å vise appnavn ("Spotify", "WhatsApp").
3. **Erklæringer i Play Console:** notification listener, mikrofon (`RECORD_AUDIO`), foreground services
   (location i tablet; connectedDevice og microphone i companion). Noen krever kort demovideo.
4. Tablet-appen har bare posisjon og Bluetooth, og er enklest. Den har også en notification listener ("This device").

## Må gjøres av deg

- Utviklerkonto hos Google Play (25 USD, identitetsverifisering).
- Ny privatkonto: lukket testing med minst 12 testere i 14 dager før produksjon.
- Personvernerklæring på offentlig URL (jeg kan skrive norsk og engelsk, legge på GitHub Pages).
- Butikkoppføring: 512px-ikon, feature graphic 1024x500, skjermbilder, beskrivelse (jeg kan lage tekst og ta skjermbilder fra emulatoren).
- Data safety-skjema (jeg kan lage utkast).

## Åpne spørsmål / forslag

1. Begge apper, eller bare tablet først? Anbefaling: tablet først.
2. Fjerne `READ_CALL_LOG` og `CALL_PHONE`, og bytte `QUERY_ALL_PACKAGES` til `queries`, så companion har en realistisk sjanse?

## Andre ting å vurdere

- **Selvoppdatering fra GitHub** (`AppUpdates`, `REQUEST_INSTALL_PACKAGES` i manifestet) er ikke lov på Play:
  fjern tillatelsen og skjul «Se etter oppdatering» i Play-bygget (f.eks. egen flavor).

- **Play App Signing** signerer på nytt med en annen nøkkel. Den som har installert APK fra GitHub må avinstallere før Play-versjonen.
- Release-APK-en kan ikke installeres over ride-bygget på din X7 (annen nøkkel) uten å miste nedlastede kart. Behold ride-bygget på egen tablet.
- Tablet-APK er stor (ca. 99 MB): ikke minifisert, alle ABI-er. R8 + resource shrinking kan krympe den, men må testes på enhet
  (MapLibre, Room, refleksjon). Kan også fjerne ca. 20 `Log.d/v/i`-kall.
- **Lyd og stemme (planlagt, se `docs/audio.md`):** stemmemodeller lastes ned som data (lov). sherpa-onnx-bibliotekene
  må være 16 KB-justert (sjekk med `zipalign -c -P 16`). eSpeak NG gjør APK-ene GPL-3 (lov på Play). Ingen nye tillatelser.
- Lisens og tredjepartsnotiser finnes (MIT, `THIRD_PARTY_LICENSES.md`). Sjekk at OSM/Overture-kreditering vises i appen.
