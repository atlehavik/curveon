# Foreslåtte features

Ideer som ikke er bygget ennå. Hver feature har hva rytteren får, hvordan den passer inn i appen
(minimal UI, offline først, batteri, Google Play) og hva som er uavklart. Øverst står det som er
nærmest å bli bygget. En feature som blir bygget, flyttes ut herfra og inn i README.md.

---

## 1. Kjør etter godt vær

**Kort sagt:** Curveon velger retning og vei etter været. En rundtur går dit det er oppholdsvær når
du er der, og ruten viser hvor og når du kan få regn.

**Status (2026-10-28, gren `weather`):** første versjon er bygd. Når rytteren tegner en rundtur, vises været rundt
startpunktet på kartet (sol, sky, regn, vind og temperatur på 25 steder ut til ca. 65 km for en tur på 150 km, for
omtrent når rytteren er der), og brikken *Godt vær – velg for meg* i tegnelinjen legger turen i den tørreste av åtte
retninger med lengden og underlaget som er valgt. (Et eget kort «Godt vær i dag» i «Hvor skal du?» ble prøvd og fjernet:
det tok for mye plass.) I forhåndsvisningen: værlinjen (regn eller kraftige vindkast med sted og klokkeslett, ellers
«Tørt hele veien»), «Tørt»/«Regn» på alternativene når de skiller seg, forslag om senere start og «Tørt via <sted>
[Bruk]» (prøver 30 og 60 km til siden for der regnet begynner, høyst +20 min eller +25 %). Henting via Companion når
nettbrettet ikke har nett (`weather_get` / `weather_data`), og en bryter i Innstillinger → Navigasjon → Vær.
Værkart-knappen (sol) i knappekolonnen til høyre på kartet i fullskjerm, når rytteren ikke navigerer og står
stille (den skjules fra 10 km/t, som grusknappen) og vær kan hentes: av/på huskes, og den viser ett symbol per værrute
(ca. 10 km) i den delen av kartet som vises, for nå og timen etter; det hentes bare mens knappen vises og er på.
Værsymbolene står på nærmeste vei innen 6 km (fra rutedataene, `SurfaceReader.nearestRoad`, beregnet utenfor
hovedtråden og husket per punkt), så aldri i sjøen; uten vei så nær utelates de. (Først ble kartets vannlag spurt med
`queryRenderedFeatures`; det ga hele vannflaten som geometri på hovedtråden og frøs kartet i over et sekund, ANR på X7.)
Rutenettet er festet til faste punkter (0,1° × 0,2° eller en dobling, cirka seks langs skjermens korte side, maks 120 med
en rad rundt), så panorering gir de samme punktene; kartet tegnes bare på nytt når symbolene faktisk endrer seg.
Målt på X7, zoomet ut og panorert: 1 % tregeste bilder 1150 ms før, 48 ms etter (40 ms med værkartet av).
**Ikke med ennå:** varsel underveis, Nowcast og MetAlerts-farevarsler; «Godt vær» for eventyr.
Kode: `data/weather/` (WeatherService, WeatherScore, WeatherController), `:phone` MetWeather og WeatherTunnel.

### Hva rytteren får

- **Rundtur og eventyr:** Et valg «Godt vær» i arket for rundtur og eventyr, ved siden av
  retning og lengde. Når det er på, prøver planleggeren flere retninger. Den velger den der
  regn, vind og kulde er minst *på tidspunktet du er der*, ikke der været er fint akkurat nå.
  Arket sier kort hvorfor, for eksempel «Vestover: oppholdsvær hele turen» eller «Regn i
  Hardanger fra kl. 15, turen går nord».
- **Vanlig rute A→B:** Forhåndsvisningen viser et lite værmerke på ruten der det regner når du
  kommer fram, med klokkeslett, for eksempel «Regn ved Odda ca. 14:40». Er en annen veistil
  (rask, svingete) mye tørrere, står det på den brikken. Ruten byttes aldri uten at du ber om det.
- **Underveis:** Endrer prognosen seg kraftig mens du kjører (nytt regn på strekningen foran),
  kommer det et rolig kort på samme måte som andre varsler. Ingen lyd og ingen ny knapp. Det
  samme gjelder et farevarsel fra MET på strekningen foran, for eksempel «Farevarsel: sterk
  vind over Hardangervidda fra kl. 16».
- **Ingen data:** Er du offline og prognosen er for gammel, er valget gråtonet og sier hvorfor:
  «Trenger nett for værvarsel». Det samme gjelder utenfor området værdataene dekker.

### Hvor dataene kommer fra: met.no

Alle værdata kommer fra **MET Norway** (Meteorologisk institutt) på `api.met.no`. Det er det
samme varselet som Yr viser: Yr har ikke noe eget API, så vi henter direkte fra MET. API-et er
åpent, uten nøkkel og uten konto, og det koster ingenting å bruke.

- **Locationforecast 2.0** (`api.met.no/weatherapi/locationforecast/2.0/compact`): timesvarsel i
  ni dager (time for time de første dagene, så hver sjette time). Det dekker hele verden, med
  høy oppløsning i Norden og grovere ellers. Det er grunnlaget for rundtur, A→B og varselet
  underveis.
- **Nowcast 2.0** (`api.met.no/weatherapi/nowcast/2.0/complete`): radarbasert nedbør hvert femte
  minutt de neste to timene, bare i Norden. Brukes underveis for «regn foran deg om 20 min», der
  Locationforecast er for grovt. Kommer etter Locationforecast.
- **MetAlerts 2.0** (`api.met.no/weatherapi/metalerts/2.0/current.json?lat=…&lon=…`):
  farevarsler fra MET (sterk vind, styrtregn, snø, glatte veier, flom og skred), med område og
  tidsrom. Et gult, oransje eller rødt farevarsel på strekningen er nyttig for en motorsyklist.
  Det vises som et merke på ruten og som et rolig kort underveis, på samme måte som regn.
- **Lisens:** CC BY 4.0 og NLOD. **Kommersiell bruk er lov**, noe som betyr noe fordi Curveon
  skal på Google Play. Kravet er kreditering: «Værdata fra MET Norway» der været vises, og i
  THIRD_PARTY_LICENSES.md. Yr-navnet og Yr-logoen brukes ikke (de er NRK og MET sine, og det må
  ikke se ut som Yr står bak appen).
- **Vilkår fra MET** (api.met.no/doc/TermsOfService): egen `User-Agent` med appnavn, versjon og
  kontakt (for eksempel `Curveon/1.2.6 github.com/atlehavik/curveon`), ellers svarer MET 403.
  Koordinatene sendes med maks 4 desimaler (vi runder uansett til ~0,1°). Appen respekterer
  `Expires` og `If-Modified-Since`, henter ikke før svaret har gått ut og bruker 304-svar. Det
  blir ingen unødvendig trafikk og ingen henting i bakgrunnen. Over cirka 20 forespørsler i
  sekundet totalt for appen trengs en egen avtale med MET. Med cache og rutenett er Curveon
  langt under det.
- **Hvorfor ikke andre kilder:** Open-Meteo er bare uten kostnad for ikke-kommersiell bruk, og
  OpenWeatherMap krever nøkkel og har en grense i gratisplanen. MET har det beste varselet for
  Norge og lov til kommersiell bruk, uten nøkkel.

### Hvordan det virker (teknisk skisse)

1. **Prøvepunkter:** Langs hver kandidatrute tas et punkt hver ~15 km, eller hver ~10 minutter
   kjøring, med beregnet tidspunkt (ETA) for hvert punkt. Punktene rundes av til et rutenett på
   ~0,1° (cirka 5–10 km), slik at nabopunkter og flere kandidater deler samme oppslag.
2. **Hente og lagre:** Varselet for hver rute i rutenettet hentes én gang og lagres på disk med
   utløpstiden fra MET. En rundtur på 300 km gir typisk 20–40 rutenettpunkter, og de fleste blir
   gjenbrukt mellom kandidatene.
3. **Poeng:** For hvert punkt brukes timen nærmest ETA: nedbør (mm/t og sannsynlighet) teller
   mest, deretter vindkast og lav temperatur. Hvor mye som tåles, følger en enkel
   skala («tørt», «litt yr går fint»). Turens poeng er det verste stedet pluss summen, slik at
   én kraftig byge teller mer enn mye lett yr.
4. **Rundtur:** `RoundtripPlanner` prøver allerede tre rotasjoner (0°, −30°, +30°). Med «Godt
   vær» prøves for eksempel åtte retninger, og hver kandidat får både vei- og værpoeng.
   Eventyr (`AdventurePlanner`) får værpoenget som en ekstra kostnad når turer sammenlignes.
5. **A→B:** De tre kandidatene i forhåndsvisningen får hvert sitt værpoeng. Ruten selv endres
   ikke, bare merkene og brikkene.

### Offline først

- Værdata er små filer, så de følger ikke med kartnedlastingen. De hentes når du planlegger
  med nett.
- Planlegger du hjemme med wifi, lagres varselet for områdene turen går gjennom. Det brukes
  offline så lenge det gjelder, og alderen vises («Varsel fra 08:00»).
- Uten nett og uten gyldig lagret varsel fungerer alt annet som før. Bare værvalget er av.

### Batteri og respons

- Det hentes bare når du planlegger, og ellers på det meste én gang i timen mens du kjører med
  nett. Det er ingen polling i bakgrunnen og ingen jobb når appen ikke er i bruk.
- Rundtur med åtte retninger koster flere ruteberegninger. Den første turen vises raskt, og
  bedre turer kommer etter hvert som chips, på samme måte som eventyr gjør i dag.

### Google Play

- Ingen ny tillatelse, siden `INTERNET` finnes fra før.
- Avrundede posisjoner (cirka 5–10 km) sendes til MET. Det er stedsdata som deles med en
  tredjepart for appens funksjon. Det må med i datasikkerhetsskjemaet
  (google-play-data-safety.md) og noteres i google-play.md.
- Krediteringen av MET må vises i appen.

### Tekst og språk

Alle nye tekster går i `strings.xml` på alle språkene appen har (engelsk, norsk og de andre),
korte nok til kortene i cockpiten.

### Uavklart

- Skal «Godt vær» være på som standard for rundturer, eller huskes fra sist?
- Hvor mye vær skal veie mot svingete vei? Kanskje en enkel bryter: «helst tørt» / «må være tørt».
- Skal været også vises på kartet (et lag med nedbør), eller bare på ruten? Minimal UI tilsier
  bare på ruten.
- Utenfor Norden er MET grovere. Holder det, eller trengs en annen kilde?
- Skal farevarsler fra MetAlerts vises også når «Godt vær» er av? De gjelder sikkerhet, ikke
  bare komfort.
- Skal Nowcast med i første versjon, eller komme etter Locationforecast?

### Testes på

Emulatoren (Pixel-størrelse med kamerahull, stående og liggende, og X7) for arket og merkene,
og `tools/ui_matrix.py` for det nye valget i arket. Ekte tur på Pixel og X7 for varselet
underveis.
