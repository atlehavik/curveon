# Personvernerklæring for Curveon

Gjelder fra 2026-10-18. Erklæringen gjelder appene **Curveon** (`com.curveon.tablet`) og **Curveon Companion**
(`com.curveon.companion`).

**Kort fortalt:** Curveon har ingen kontoer, ingen reklame, ingen analyse og ingen egen server. Det appene
leser på telefonen din blir værende på dine egne enheter. Det eneste som forlater dem, er forespørslene du selv gjør
til nettbaserte kart- og søketjenester, listet under.

Utvikler og kontakt: [navn], [e-postadresse].

## Hva appene bruker, og hvorfor

| Hva | Hvorfor | Hvor det går |
|---|---|---|
| **Posisjon** (GPS) | For å vise deg på kartet, navigere og lagre turer | Blir på enheten. En tekstlogg over de siste turene (posisjon, fart) ligger i appens egen mappe i 14 dager, og slettes så |
| **Kontakter** | For å vise favoritter, navn og bilder i telefonvisningen, og for å ringe | Leses på telefonen og vises på Curveon-skjermen din. Sendes ikke til andre |
| **Anropslogg** | For å vise siste og ubesvarte anrop på skjermen, så du kan ringe tilbake uten å ta på telefonen | Leses når telefonen kobler til og når du åpner telefonvisningen, maks de 200 siste anropene, bare i minnet. Endres aldri og sendes aldri til noen server |
| **Varsler** (tilgang til varsler) | For å vise meldinger, anrop og musikken som spilles, og la deg svare | Leses på telefonen og sendes videre til skjermen din. Lagres ikke |
| **Mikrofon** | Stemmesvar på meldinger | Håndteres av telefonens egen talegjenkjenning, som foretrekker å gjøre det uten nett. Bruker telefonens taletjeneste nettet, er det den tjenesten (vanligvis Googles) som sender lyden, på egne vilkår. Curveon lagrer ingen opptak |
| **Bluetooth** | Koblingen mellom telefon og skjerm, og en valgfri OBD2-adapter for motordata | Blir mellom enhetene dine |
| **Installerte apper** (apper med startikon og musikkapper) | For å vise navnet på appen som sendte en melding eller spiller musikk | Brukes bare på enheten |

Curveon Companion har ikke tillatelse til internett og kan ikke sende noe ut av telefonen: den snakker bare
med Curveon-skjermen din over Bluetooth.

## Hva som sendes over internett (bare Curveon)

Bare når du bruker disse funksjonene, og bare over HTTPS:

- **Stedssøk.** Det du skriver og omtrentlig posisjon (så de nærmeste treffene kommer først) går til Photon
  (photon.komoot.io, Komoot), og for norske stedsnavn og adresser går det du skriver til Kartverket (ws.geonorge.no).
  Tjenestene ser IP-adressen din, og Curveon styrer ikke hva de lagrer.
- **Nettkart.** Kartfliser hentes fra OpenFreeMap (tiles.openfreemap.org). De ser hvilket område du ser på, og IP-adressen din.
- **Nedlasting av kart og veidata** (Innstillinger): kartfiler fra Protomaps (build.protomaps.com) og
  rutedata fra BRouter (brouter.de). De ser IP-adressen din og hvilke områder du ber om.

Curveon har ingen andre servere og sender ingenting til utvikleren. Med nedlastede kart kan du kjøre helt uten nett.

## Hva som lagres på enheten din

Lagrede ruter, steder, spor, turer og innstillinger ligger i appen. Slett dem i appen, eller ved å tømme appens
data eller avinstallere den; vi lagrer ingenting andre steder.
Android-sikkerhetskopi kan kopiere appdata til Google-kontoen din hvis du har slått på enhetssikkerhetskopi; det
håndteres av Google etter innstillingene på Google-kontoen din.

## Deling, salg, barn

Vi selger eller deler ikke dataene dine, bruker dem ikke til reklame eller profilering, og bygger ikke profiler.
Appene er ikke rettet mot barn.

## Dine valg

Du bestemmer hver tillatelse i Androids innstillinger, og appene sier fra om hva som slutter å virke uten den (for
eksempel anropsloggen: fanen Recents i telefonvisningen blir tom). Siden utvikleren ikke har noen personopplysninger,
er det ingenting å be om innsyn i eller sletting av; avinstallering fjerner alt på enheten.

## Endringer og kontakt

Hvis dette endres, erstatter den nye versjonen denne siden og datoen øverst. Spørsmål: [e-postadresse].
