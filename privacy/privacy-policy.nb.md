# Personvernerklæring for Curveon

Gjelder fra [dato]. Erklæringen gjelder appene **Curveon** (`com.curveon.app`) og **Curveon Companion**
(`com.curveon.companion`).

**Kort fortalt:** Curveon har ingen kontoer, ingen reklame og ingen analyse. Det appene leser på telefonen din,
blir værende på dine egne enheter. Det som forlater dem, er forespørsler til nettbaserte kart- og søketjenester,
og, hvis du velger å kjøre sammen med andre, krypterte meldinger via en reléserver vi driver. Reléet
kan ikke lese innholdet.

Behandlingsansvarlig og kontakt: [navn], [e-postadresse].

## Hva appene bruker, og hvorfor

| Hva | Hvorfor | Hvor det går |
|---|---|---|
| **Posisjon** (GPS) | For å vise deg på kartet, navigere og lagre turer | Blir på enheten. En tekstlogg over de siste turene (posisjon, fart) ligger i appens egen mappe i 14 dager, og slettes så. Når du deler posisjon i en gruppe, se «Kjøregrupper og venner» under |
| **Kontakter** | For å vise favoritter, navn og bilder i telefonvisningen, og for å ringe | Leses på telefonen og vises på Curveon-skjermen din. Sendes ikke til andre |
| **Anropslogg** | For å vise siste og ubesvarte anrop, så du kan ringe tilbake uten å ta på telefonen | Leses når telefonen kobler til og når du åpner telefonvisningen, maks de 200 siste anropene, bare i minnet. Endres aldri og sendes aldri til noen server |
| **Varsler** (tilgang til varsler) | For å vise meldinger, anrop og musikken som spilles, og la deg svare | Leses på telefonen og sendes videre til skjermen din. Lagres ikke |
| **Mikrofon** | Stemmesvar på meldinger | Håndteres av telefonens egen talegjenkjenning, som foretrekker å gjøre det uten nett. Bruker telefonens taletjeneste nettet, er det den tjenesten (vanligvis Googles) som sender lyden, på egne vilkår. Curveon lagrer ingen opptak |
| **Bluetooth** | Koblingen mellom telefon og skjerm, og en valgfri OBD2-adapter for motordata | Blir mellom enhetene dine |
| **Installerte apper** (apper med startikon og musikkapper) | For å vise navnet på appen som sendte en melding eller spiller musikk | Brukes bare på enheten |
| **Kamera** | For å skanne QR-koden til en venn eller en gruppeinvitasjon | Bildet brukes bare til å lese koden og lagres ikke |

## Hva som sendes over internett

Bare når du bruker disse funksjonene, og bare over kryptert forbindelse (HTTPS):

- **Stedssøk.** Det du skriver og omtrentlig posisjon (så de nærmeste treffene kommer først) går til Photon
  (photon.komoot.io, Komoot) og, for norske stedsnavn og adresser, det du skriver til Kartverket (ws.geonorge.no).
  Tjenestene ser IP-adressen din, og Curveon rår ikke over hva de lagrer.
- **Nettkart.** Kartflisene kommer fra OpenFreeMap (tiles.openfreemap.org). De ser hvilket område du ser på og
  IP-adressen din.
- **Nedlasting av kart og veidata** (Innstillinger): kartfiler fra Protomaps (build.protomaps.com) og rutedata fra
  BRouter (brouter.de); søkedata og fartsgrenser fra Curveons datalager på GitHub (github.com). Planlegger du en
  tur på nett uten nedlastet land, hentes bare rutedataene turen trenger. Tjenestene ser IP-adressen din og hvilke
  områder du ber om.
- **Nye versjoner** (bare utgaven fra GitHub): Curveon spør GitHub (api.github.com) om det finnes en ny versjon,
  og laster den ned derfra. GitHub ser IP-adressen din.

- **Feilmeldinger og forslag** (Innstillinger → Hjelp, bare når du trykker Send). Det du skriver, bildene du velger,
  appversjon, enhetsmodell og Android-versjon, og, hvis du lar det stå avkrysset, starten av siste krasjrapport, går
  via reléet vårt (relay.curveon.no) til en **offentlig** sak på GitHub (github.com/atlehavik/curveon), der alle kan
  lese den. Reléet sender det videre og lagrer ingenting; bildene kodes om først, så et bildes posisjon forsvinner.
  GitHub (Microsoft) behandler saken etter sine egne personvernvilkår. Appen husker saksnummer og lenke så du kan
  følge saken, og spør GitHub hvordan den står når du åpner Hjelp. Vil du ha en sak fjernet, spør oss (kontakt under).

Med nedlastede kart kan du kjøre helt uten nett.

## Kjøregrupper og venner

Dette er valgfritt og er av til du selv legger til en venn eller starter en gruppe.

- **Hva du deler:** posisjon (bare mens du deler, og bare med den aktive gruppen), kallenavn, farge og
  profilbilde, ruter og delte turer, og SOS-varsel hvis du utløser det. Gruppens medlemmer ser dette.
- **Ende-til-ende-kryptering:** alt dette krypteres på enheten din med en nøkkel bare gruppens medlemmer har, før
  det sendes. Reléet kan ikke lese posisjoner, navn, bilder eller ruter.
- **Reléet** (relay.curveon.no) drives av oss på en server hos netcup i Tyskland. Det videresender de
  krypterte meldingene. Det ser IP-adressen din når du kobler til (brukt til å begrense misbruk, ikke lagret i
  logg), gruppe- og deltaker-ID-er som ikke kan knyttes til deg som person, og størrelsen på og tidspunktet for
  meldingene.
- **Hva reléet lagrer** (kryptert): siste profil per deltaker, de siste rutene, endringer i delte turer, vennenes
  invitasjoner og et aktivt SOS-varsel (til det avbrytes, maks 12 timer). Posisjoner lagres aldri. Alt slettes
  automatisk etter 30 dager uten bruk.
- **Venner** er private kanaler mellom to personer. Venneinvitasjoner går som kode eller QR-kode du selv gir videre.
- **Forlate og slette:** du kan forlate en gruppe eller fjerne en venn når som helst. Det som ligger igjen på
  reléet, er krypterte data som utløper av seg selv. Kontakt oss hvis du vil ha dem fjernet tidligere.

## Hva som lagres på enheten din

Lagrede ruter, steder, spor, turer, innstillinger, venner og delte ruter ligger i appen. Slett dem i appen, ved å
tømme appens data eller ved å avinstallere. Android-sikkerhetskopi kan kopiere appdata til Google-kontoen din hvis
du har slått det på. Det styres av Google og kontoinnstillingene dine.

## Deling, salg og barn

Vi selger eller deler ikke dataene dine, bruker dem ikke til reklame eller profilering, og bygger ingen profiler.
Appene er ikke rettet mot barn.

## Dine rettigheter og valg

Du bestemmer hver tillatelse i Android-innstillingene, og appene sier hva som slutter å virke uten den (for
eksempel anropsloggen: fanen Nylige i telefonvisningen blir tom). Vi har ingen personopplysninger om deg
utenom det krypterte som ligger midlertidig på reléet, så det er lite å be om innsyn i. Du har rett til innsyn,
retting og sletting etter personvernforordningen (GDPR), og kan klage til Datatilsynet. Ta kontakt på
[e-postadresse].

## Nettplanleggeren

Nettplanleggeren (relay.curveon.no/plan, også via curveon.no/plan) har ingen konto og ingen informasjonskapsler. Punktene du planlegger gjennom sendes til tjeneren vår for å finne veien; den svarer og lagrer ingenting, og ingenting om deg logges. Det du søker etter sendes til Photon (Komoot) og Kartverket, og et punkt du klikker på i kartet sendes til Photon for å få et navn. En tur du sender til Curveon krypteres i nettleseren med en nøkkel som bare nettleseren og appene som har skannet koden har; reléet tar vare på den, uleselig, i opptil 30 dager så appen kan hente den. Nøkkelen og listen over turer du har sendt lagres i nettleserens lokale lagring, og du kan slette dem med nettleserens nettstedsdata. Mens en telefon eller et nettbrett er koblet til siden, legger Curveon de planlagte turene (de 60 nyeste) kryptert på reléet på samme måte, så siden kan vise dem; avslutter du økten under Planlagte → Fra nettet, stopper det.

## Nettsiden

Siden curveon.no og invitasjonssiden på relay.curveon.no bruker ingen sporing eller informasjonskapsler. Tjeneren
ser IP-adressen din når du åpner dem, og den lagres ikke i logg.

## Endringer

Endres dette, erstatter den nye versjonen denne siden, og datoen øverst endres.
