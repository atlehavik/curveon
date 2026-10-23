# Device checks

Ting å prøve eller se på de ekte enhetene (Hugerock X7-nettbrettet, Pixel 9a) som ikke lot seg
teste i emulatoren. Nyeste først; kryss dem av eller slett dem når de er gjort.

Kommandoene forutsetter at enheten er koblet til over USB. Er begge koblet til, velg en med
`-s <serial>` (se `adb devices`). `adb` ligger i `~/Android/Sdk/platform-tools/`.

## 2026-10-24 — Livligere SOS og nudge, melding over fullt kart, større profilbilde (1.2.4)

- [ ] SOS fra kameraten (eller DEV-demo): det røde kortet skal pulsere og blinke i takt med sirenen, og det skal ikke komme noe utenfor skjermen på Pixelen.
- [ ] Nytt ikon (V4, sirkel med brun ramme): Curveon på Pixelen og X7, og Companion på Pixelen, i app-skuffen og på hjemmeskjermen. Prøv også temaikon (Android 13+: Innstillinger → Bakgrunn og stil → Temaikoner): motorsykkelen skal vises i én farge, lysene som hull.
- [ ] Pixel, Curveon som «Denne enheten»: Innstillinger → Telefon viser «mangler tillatelse» (SMS/mikrofon). Trykk Tillat og godta i Android: advarselen skal forsvinne med en gang, uten å gå ut av innstillingene. Skru en tillatelse av og på i Android-innstillingene og kom tilbake: advarselen følger med.
- [ ] Hovedmenyen: trykk på hver knapp (telefon, venner, innstillinger, GPX). Den åpne knappen skal fylles med sin egen farge (grønn, oransje, hvit, fiolett), og ikonet blir mørkt.
- [ ] Hovedmenyen: GPX-knappen er fiolett og de to siste knappene (GPX og motorsykkelen) har ulik farge. Sjekk også at «GPX og turer»-dialogen ser fin ut.
- [ ] Nudge fra en venn: ravgul puls og avatar som «ringer». Roligere enn SOS.
- [ ] Ha fullt kart oppe og få en SMS: banner øverst i ca. 10 sekunder med navn og tekst, og knappene les opp / svar / lukk. Prøv også under navigering (ligger det over turkortet?) og med musikkspilleren på.
- [ ] Sett profilbilde på nytt (Venner → Gruppetur → profil): nytt bilde er 192 px. Trykk på rytteren på kartet: bildet er større. Eldre bilder er fortsatt 96 px til de velges på nytt.

## 2026-10-23 — SOS-lyd, tilt-piler, turdialog, veikontrast, markør (1.2.4)

- [ ] Markør: Settings → Navigation viser nå 0 s som utgangspunkt (lagret verdi uendret, 0 = dagens +1,5 s). Sjekk at − og + flytter markøren slik du venter.
- [ ] Veikontrast (Settings → Display → Roads): Normal / Høykontrast. Dag: Normal = grå, Høykontrast = mørkegrå. Natt: Normal = litt mykere enn hvit, Høykontrast = hvit. Sjekk også grusstripene og at stedsnavn ligger over veiene.
- [ ] Turdialogen etter å ha kjørt videre kort tid etter en lagret tur: skal si «Forrige tur ble lagret» med «Fortsett den turen» / «Start ny tur».

- [ ] Demo-SOS (DEV) eller en SOS fra kameraten: det skal pipe S-O-S (tre korte, tre lange, tre korte) på alarmvolumet til du svarer eller avbryter, og ikke spille telefonens alarmmelodi.
- [ ] Bytt til 3D-kartet: to piler (opp/ned) dukker opp ved 3D-knappen i noen sekunder og forsvinner igjen; hold inne 3D-knappen for å få dem fram. Pil opp = mer fugleperspektiv.

## 2026-10-23 — Fra kodegjennomgangen: signerte grupper, trygg oppdatering, fortsett navigering (1.2.4)

- [ ] Pixelen (release, oppdateres fra GitHub): etter oppdateringen er alle lagrede ruter, GPX-spor og turer der som før.
- [ ] Gruppetur med X7/Pixel og en kamerat på en **eldre** Curveon (1.2.3): begge ser hverandre på kartet, SOS og
  «Jeg kommer» går fram begge veier, og admin kan fjerne en tredje rytter uten at noen av dere mister gruppa.
- [ ] Naviger et stykke på Pixelen, sveip Curveon bort fra «Nylige» (eller la den ligge i bakgrunnen til Android
  lukker den), åpne igjen: «Fortsette navigeringen?» med målet. «Fortsett» planlegger turen fra der du er,
  «Nei takk» glemmer den. Lukk Curveon med «Lukk Curveon» i varselet: da spør den ikke.

## 2026-10-23 — Ingen vending midt i veien, markør på veien, SMS fra fullkart, blå deling, venneforespørsel i gruppe (1.2.4)

- [ ] Venneforespørsel fra en gruppe, med to ekte enheter (X7 og Pixelen er samme rytter, så bruk en annen telefon, f.eks. kameratens,
  i samme gruppe): på X7, Venner → Gruppetur → gruppa → «Legg til som venn» ved kameraten (ikonet med + til høyre, eller trykk
  rytteren på kartet). Hos kameraten kommer kortet «<navn> vil være din venn». «Legg til som venn» gir high five på begge, og kameraten står
  i vennelista. Prøv også «Nei takk»: X7 får ingen beskjed, og en ny forespørsel fra samme rytter i samme gruppe vises ikke.
  Knappen skal ikke vises ved en som allerede er venn.

- [ ] X7 («Denne enheten», uten SIM): Telefon-knappen viser «Denne enheten kan ikke ringe eller sende SMS» med
  hvorfor og hva du kan gjøre. Meldinger og musikk virker som før.
- [ ] Telefon med SIM som aldri er koblet til en Companion (kameratens: det holder å oppdatere til 1.2.4):
  Innstillinger → Telefon står på «Denne enheten» uten at noe er valgt, telefonpanelet viser «Tillat», og
  etterpå kommer kontaktene. Ingenting om Companion.

- [ ] Kjør uten rute (Pixel, gjerne der GPS-en driver litt): markøren ligger på veien og peker langs den,
  også gjennom svinger. I kryss og når du svinger av, slipper den veien og følger GPS-en. Står du stille,
  blir den liggende på veien. Den følger veiene slik kartet tegner dem, også i utlandet og offline.
- [ ] Fullkart → Telefon → en kontakt → SMS: dashbordet kommer fram med SMS-kortet, og etter Send eller
  Avbryt går det tilbake til fullkart. Gjelder også Svar på en melding.
- [ ] Del posisjon i en gruppe: prikken på Venner-knappen er blå (ikke grønn). Prøv Pixel i sollys.

- [ ] Del en tur med en venn mens dere står på hver deres side av et kryss (som ved Circle K Automat Bømlo).
  Hos vennen skal ruten koble seg på turen i den retningen den går, uten å kjøre til et punkt på veien og snu der.
- [ ] Plan en tur med et stopp ved en vei du skal tilbake langs: ruten kjører videre til et kryss eller en
  rundkjøring og snur der, ikke på selve veien. (Et stopp i en blindvei, en innkjørsel eller på en
  stasjonsplass snur fortsatt der.)

## 2026-10-23 — Pause i gruppa, autolagret profil, nudge-sperre (1.2.3)

Pixel og X7 begge med 1.2.3 (Companion også 1.2.3 hvis X7 går via Companion).
- [ ] Gruppetur: slå av «Kjør med gruppa» på Pixelen. Pixelen forsvinner fra kartet på X7 med en gang, Pixelen ser ikke
      X7 lenger, gruppa sier «Pause» og forklarer, og SOS-knappen er borte. SOS fra X7 når ikke Pixelen. Slå på igjen:
      begge ser hverandre innen et halvt minutt.
- [ ] Trykk «Vil ha kontakt» mange ganger fort: X7 får ett varsel, bjellen er grå i et halvt minutt.
- [ ] Venner → Meg: endre navn, farge, ikon. «Lagret» vises, og X7 ser det nye uten noen lagre-knapp. Med bilde er
      ikonene dempet; «Fjern» bildet, så kan et ikon velges.
- [ ] Skann QR-kode på Pixelen (kamerahull), stående og liggende: «Avbryt» er helt på skjermen.
- [ ] En venn som slår på deling: dukker opp på kartet uten at noen må slå av og på igjen (kan ta noen minutter
      hvis Venner ikke er åpen; åpne Venner for å få det med en gang).

## 2026-10-23 — Trykk på en venn på kartet, og «Vil ha kontakt»

Pixel og X7 som venner (ExSeven), begge med ny app.
- [ ] Trykk på ExSeven på kartet på Pixelen: kortet viser fart eller «Stoppet i …», sist sett, avstand og batteri.
- [ ] «Vil ha kontakt» på kortet: Pixelen sier «Sendt», X7 får varsel med lyd og vibrasjon. «OK, jeg ser det» på
      X7: Pixelen sier «ExSeven har sett det» (grønt), innen få sekunder.
- [ ] Samme fra vennelisten (bjellen), også når X7 ikke deler posisjon: varselet kommer når X7 ser etter
      (åpne Venner på X7 for å få det med en gang).
- [ ] I en gruppetur: bjellen i medlemslisten virker likt.
- [ ] Står Pixel og X7 på samme sted (f.eks. hjemme), blir ExSeven og en annen rytter én runding med «2» og navnene
      under; trykk gir lista «Her sammen», og et navn der gir kortet. Én alene står som før.

## 2026-10-22 — Ny relay (tåler nye funksjoner)

Relayen på relay.curveon.no er oppdatert, og både Pixelen og X7 har den nye appen (X7 fikk den 22:28; før det
kastet X7 ruter fra venner stille, fordi den var bygget før den funksjonen kom).
- [ ] Venner: Pixel og X7 ser hverandre på kartet når «Ser alltid hvor jeg er» er på (begge veier).
- [ ] Send en rute til en venn fra Pixelen og fra X7: den kommer fram begge veier.
- [ ] Del en tur med en kjøregruppe, endre den mange ganger (stopp, stil), og la en tredje enhet koble til etterpå:
      den får turen slik den er nå.
- [ ] Ingen nye krasj: `adb -s <enhet> pull /sdcard/Android/data/com.curveon.app/files/crashes`

## 2026-10-22 — Eventyrruter som ikke kjører samme vei tilbake (1.2.2)

- [ ] Lag den samme eventyrturen som før (Bømlo → østover rundt Sunnhordland, ca. 8 t) noen ganger
      (ny variant hver gang): turen skal ikke lenger gå opp en vei og ned igjen samme vei, eller ut
      til en blindvei og snu. Si fra hvis noe ser rart ut (gjerne med skjermbilde).
- [ ] Det tar ikke merkbart lenger tid før forslaget kommer enn før.

## 2026-10-22 — Del en rute med venner (1.2.2)

Trenger to telefoner med Curveon som er venner (emulatoren har ingen venner).

- [ ] **Pixel → en venn:** Planlegg en tur til et sted, Del → **Del med venner**, kryss av en venn, Send. Hos vennen (innen
  ti minutter, eller med en gang hvis vennen åpner Venner): «X delte en rute», med «Den er din egen kopi …» og Kjør / Lagre i delt / Ignorer.
- [ ] **Under kjøring:** ankomsttiden → Rute → Del → Del med venner gjør det samme for turen du kjører.
- [ ] **X7 via Companion:** det samme fra nettbrettet (Companion på Pixel er oppdatert med dette; en eldre Companion gjør ingenting).
- [ ] Ser vennen deg fortsatt på kartet etter at du har sendt den noen turer?
- [ ] **Samme rute:** Mottakeren får ett kort med din linje (ikke Raskest/Svingete/Supersvingete), og KJØR følger akkurat de veiene
  du valgte. Står mottakeren et stykke unna (f.eks. midt på turen), kobler den seg på nærmeste del av linjen, ikke starten.
- [ ] **Gruppe, ombestemme seg:** Del med gruppen fra planleggeren, bytt så til en annen stil (eller legg til et stopp): de andre i
  gruppen får den nye linjen innen kort tid og kjører den.

## 2026-10-22 — Rediger rute under kjøring, sprø profilbilde-effekter (1.2.2)

- [ ] **Pixel:** Under navigering til et sted: ankomsttiden → Rute → **Rediger rute**. Ruteplanleggeren åpner fra der du er, med
  stoppene du har igjen. Velg en annen stil eller legg til et stopp, trykk KJØR: navigeringen fortsetter på den nye ruten.
  Lukk med X i stedet: du kjører videre som før, uten spørsmål.
- [ ] **Pixel:** Venner → Meg → velg et bilde av deg selv og prøv **Pop art, Glitch, Varmekamera og Syretripp** (emulatoren finner
  ingen person, så de er ikke sett i bruk). Ser de bra ut i den lille runde avataren hos en venn?

## 2026-10-22 — Rute-knappen i ankomstlinjen (1.2.2)

- [ ] **Pixel:** Mens du navigerer til et sted: trykk på ankomsttiden (folder seg ut som før), så på den nye **Rute**-knappen, så **Del**.
  Med en gruppetur på: «Del med gruppen» sender ruten til gruppen, og neste gang står det «Delt med gruppen». Uten gruppe:
  «Start en gruppetur først» åpner Gruppetur. «Del med venner» står der i begge tilfeller.

## 2026-10-22 — Ni nye språk (1.2.2)

- [ ] **Pixel:** Innstillinger → Skjerm → Språk: velg f.eks. Deutsch og Polski. Se over hovedskjermen mens du navigerer og med musikk på:
  tekst som er kuttet eller går over to linjer der den ikke burde (tysk og finsk er lengst). Si fra om ord som høres rare ut.
- [ ] **Pixel:** Med talte veibeskrivelser på og et nytt språk valgt: snakker stemmen det språket? Mangler telefonen stemmen,
  sier Android det i Innstillinger → Tekst til tale.
- [ ] **Pixel:** Systemets Innstillinger → Apper → Curveon → Språk viser alle elleve språkene.

## 2026-10-22 — Profilbilde: «jobber med det» og krasj ved utklipping (1.2.2)

Emulatoren finner ingen person i bildet (ML Kit mangler GPU der), så effektene kan bare prøves på ekte enhet.

- [ ] **Pixel og X7:** Venner → Meg → Velg fra bilder (eller ta et bilde) av deg selv: en cyan ring går rundt bildet med
  «Finner deg i bildet …», så kommer stilene. Velg Neon: ringen og «Lager effekten …» vises til effekten er klar, og «Bruk dette bildet»
  er grå så lenge.
- [ ] **Ingen krasj:** gjør det to-tre ganger etter hverandre med ulike bilder. Curveon skal ikke lukke seg (før lukket ML Kit seg med en
  krasj). Ved krasj: `adb pull /sdcard/Android/data/com.curveon.app/files/crashes`.

## 2026-10-22 — Venn via lenke, og high-five (1.2.2)

Prøvd i emulatoren: «Send lenke» åpner delingsmenyen med `https://relay.curveon.no/join#…`, nettsiden (fra et lokalt relé) viser
«Åpne i Curveon», og knappen åpner Curveon; high-fiven og at koden forsvinner er prøvd med demo-data. Det nye reléet er ute på
relay.curveon.no (`assetlinks.json` godkjent av Googles sjekk).

- [ ] **Lenke mellom Pixel og X7:** på Pixel, Mine venner → Send lenke → send til deg selv (f.eks. Messenger/SMS), åpne den på X7
  (eller send den til en venn). Begge får high-five, og vennen står i lista på begge.
- [ ] **Lenken rett inn i appen:** trykk på lenken i en chat på Pixel. Den skal åpne Curveon direkte (App Links; Pixel-en sier
  `relay.curveon.no: verified` etter installasjonen 2026-10-22).
- [ ] **Lenke åpnet dagen etter:** send en lenke, start Curveon på nytt, åpne lenken på den andre enheten etterpå: vennskapet kommer
  i stand (innen ~10 min, eller med en gang du åpner Venner).
- [ ] **QR-kode:** vis koden, skann den med den andre enheten: koden forsvinner og begge får high-five; «Vis koden min» igjen gir en ny kode.

## 2026-10-22 — Flere grupper, og venner som alltid ser hvor du er (1.2.2)

Lista med grupper, bytte av gruppe og vennelista er prøvd i emulatoren (også med demo-venner på kartet). Selve delingen mellom
to ekte enheter er ikke prøvd. Det nye reléet (siste posisjon i minnet) er ute på relay.curveon.no.

- [ ] **To grupper på X7:** Venner → Gruppetur viser begge som kort. Slår du på bryteren på den ene, slås den andre av, og
  kartet viser rytterne i den du slo på. Trykk på et kort: detaljene, «Gruppene dine» tilbake.
- [ ] **Venn ser deg:** på Pixel, Mine venner → slå på «La venner se hvor jeg er» og «Ser alltid hvor jeg er» for X7-en (som venn).
  Kjør eller gå litt med Pixel-en med Curveon åpen. Innen et par minutter skal X7 vise vennen med «Deler posisjonen sin · nå/… min siden»
  og en markør på kartet, også uten noen gruppe.
- [ ] **Slå av igjen** på Pixel: markøren og «Deler posisjonen sin» forsvinner fra X7 innen et par minutter.
- [ ] **Blå prikk** på Venner-knappen også mens bare en venn (uten gruppe) får posisjonen din, og ingen prikk når ingen gjør det.
- [ ] **Batteri:** en times kjøring med deling til én venn på. Batteribruken skal være omtrent som uten (én åpen forbindelse, en posisjon i minuttet).

## 2026-10-22 — Grupper og venner bruker alltid relay.curveon.no (1.2.2)

Serveren er oppe (`https://relay.curveon.no/health` svarer `ok`), og innstillingen «Reléadresse» under Avansert er borte.

- [ ] **X7 og Pixel:** Venner → Meg: under Innstillinger står bare Datasparing, ingen «Avansert».
- [ ] **Start en gruppe på X7** og bli med fra Pixel (QR eller lenke): begge sier «tilkoblet», og posisjonene vises hos den andre.
- [ ] **Venner og grupper fra testing mot PC-en** (localhost:8787): de flyttes selv til relay.curveon.no ved oppstart. En venn på begge enhetene skal komme tilbake som tilkoblet når begge har den nye versjonen.
- [ ] **Invitasjonslenken** åpnet i nettleseren på en telefon uten Curveon: siden på `https://relay.curveon.no/join` vises.

## 2026-10-22 — Send tur fra telefonen til nettbrettet, og «si fra når noe mangler» (1.2.2)

Telefonsiden er prøvd i emulatoren (knappene, «venter på nettbrettet», «Companion deler ikke», «Åpne Companion»);
selve overføringen over Bluetooth og mottaket på nettbrettet kan ikke prøves der.

- [ ] Pixel (Curveon + Companion, Start sharing) og X7 koblet: planlegg en tur på Pixel, trykk Del-ikonet i
      forhåndsvisningen → «Send til nettbrettet». Pixel sier «Ligger nå på nettbrettet»; X7 viser «Tur fra telefonen» med Kjør / Senere, og
      turen ligger under GPX og turer → Planlagte. «Kjør» åpner den i forhåndsvisningen med samme linje.
- [ ] Det samme fra en planlagt tur (⋮ → Send til nettbrettet), en kjørt tur (nettbrett-knappen ved Lagre) og et
      GPX-spor (⋮ → Send til nettbrettet).
- [ ] Med X7 av: send en tur fra Pixel («Nettbrettet er ikke koblet til ennå…»), slå på X7: turen kommer når den
      kobler seg til telefonen. `adb -s <x7> logcat -s PhoneLink` om den ikke kommer.
- [ ] Uten Companion på Pixel finnes ingen «Send til nettbrettet» noe sted.
- [ ] Databasen er oppgradert (helningsvinkel fjernet): GPX og turer → Turer viser alle de gamle turene på
      X7 og Pixel, og nye turer lagres.
- [ ] Varsel med anrop: avslutt anropet på telefonen og trykk Svar på X7 med en gang: «Fikk ikke gjort det på
      telefonen…» kommer i stedet for ingenting.
- [ ] Innstillinger → Motor med Bluetooth av på X7: «Bluetooth er slått av på enheten» med «Slå på»-knapp.

## 2026-10-22 — Profilbilde: knapper synlige og stiler (1.2.2)

- [ ] Pixel, portrett og landskap: Venner → Meg → velg eller ta et bilde. «Avbryt» og «Bruk dette bildet»
      er helt synlige nederst (feilen var at vinduet havnet under kamerahullet), og ingen stripe av kartet
      skimtes. Se også at andre vinduer som spretter opp (Kartpunkt, «Forkaste turen?») ikke er kuttet nederst.
- [ ] Etter et par sekunder dukker Vanlig / Klistremerke / Neon / Fart opp (første gang kan Play-tjenester
      bruke litt tid på å hente modellen; uten nett vises bare bildet). Prøv et bilde av deg med hjelm/uten,
      og se at utklippet følger hodet. Lagre og se at runden i profilen og på kartet ser bra ut.
- [ ] Samme på X7 (har den Google Play-tjenester? Hvis stilene aldri kommer, si fra).

## 2026-10-21 — Tilt helt ned mens kartet følger deg (1.2.2)

- [ ] 3D mens kartet følger deg (gjerne under navigering/demotur): bytt til 3D og trykk pil ned ved 3D-knappen
      til det ikke går lenger. Blikket skal gå like lavt ned mot veien som når du har dratt i kartet,
      og pilen din skal fortsatt sitte lavt på skjermen, uten å hoppe når du trykker.

## 2026-10-21 — Stiplet grus forblir stiplet når du zoomer inn (1.2.2)

- [ ] Nattkart og dagkart, Veidekke = Stiplet: zoom inn på en grusvei helt til zoom 18: den er stiplet
      hele veien, uten hvit/grå vei i mellomrommene. Se også der grusveien møter en annen vei: krysset
      skal ikke se ut som det har et hull. Stedsnavn ligger fortsatt øverst.

## 2026-10-21 — Innstillingsfaner ruller til åpen side; hjelpepunkt eller stopp når du bytter sted; grå eller mørk grå veier (1.2.2)

- [ ] X7, dagkart: Innstillinger → Skjerm → Veier → Grå, så Mørk grå: små veier, asfaltveiene som
      grusveilaget tegner og de vanlige grusstrekene skifter nyanse sammen; stedsnavn ligger fortsatt
      øverst og er lette å lese. Mørk grå ser ut som før.

- [ ] Planlegg en tur med et hjelpepunkt, trykk på det → Bytt sted → Velg i kartet → trykk:
      linjen tilbyr Hjelpepunkt og Stopp. Hjelpepunkt beholder det som en hvit prikk, Stopp gjør
      det til et nummerert stopp. Det samme for «Nytt stopp før» på et punkt. Pixel stående og
      X7.

- [ ] Pixel, stående: Innstillinger, trykk på fanene fra venstre mot høyre til Hjelp: navnet på
      åpen fane vises helt hver gang, også Hjelp helt til høyre (på norsk også).
## 2026-10-21 — Telefonbatteri, kikk på fartskortet, demo-triks

- [ ] X7 med Pixel koblet til: slå på Innstillinger → Skjerm → Telefonbatteri. Et lite telefonsymbol ved farten viser Pixel-batteriet (grønt når den lader). På Pixel med «Denne enheten» skal bryteren ikke finnes.
- [ ] Trykk på fartskortet: klokke og batterier vises i 5 s også når de er skrudd av.
- [ ] Demotur: trykk DEMO under pila → «Mist GPS-signalet» gir spøkelsespil og tunnel-gjetting; «Kjør feil i neste kryss» kjører av ruten i neste kryss, og navigasjonen svarer som på tur.
- [ ] Det tomme veikortet (liten mørk pille øverst til venstre) fra forrige versjon skal være borte.

## 2026-10-21 — Pixel fryste ved retur fra en annen app

Tre «svarer ikke» 17:49–17:55: kartets tegnetråd og skjermen ventet på hverandre da vinduet endret størrelse. Kartet tegnes nå i en TextureView på Android 14+.

- [ ] Pixel: naviger, bytt til Claude Code (eller en annen app) og tilbake flere ganger. Ingen frys, ingen «svarer ikke», kartet tegnes med en gang.
- [ ] Merker du at Pixel blir varmere eller batteriet går fortere under navigering enn før?

## 2026-10-21 — Veinavn på veikortet, zoom, kart-knappen

- [ ] Trykk på veikortet (Fv-skiltet øverst til venstre) på en vei med navn: navnet står ved siden av skiltene i 6 s, et trykk et annet sted tar det bort. Er det navnet på veien du kjører på (ikke en sidevei)? Har veien ikke navn i kartet, skjer ingenting.
- [ ] Zoomen når du begynner å kjøre: ett trinn lenger ut enn før. Passer det, i by og på landevei?
- [ ] Med en skjerm åpen fra menyen (f.eks. Innstillinger): kart/dashbord-knappen lukker den og bytter visning.

## 2026-10-21 — Spøkelsespil uten GPS, mindre manøverkort, neste stopp

- [ ] I en tunnel (eller med GPS av en stund): pila blir blek med en oransje satellitt ved siden av, og fartskortet holder samme høyde. Når signalet er tilbake, er pila vanlig igjen. Står du stille med signal, skal spøkelset **ikke** komme.
- [ ] Under navigering: manøverkortet nede til venstre er lite når neste sving er over 1,5 km unna, og vokser når du nærmer deg. Er 1,5 km riktig grense i fart?
- [ ] Med et stopp: under ankomsttiden står klokkeslettet for stoppet i mindre oransje skrift.
- [ ] X ved tiden spør «Avslutte hele ruten?» før ruten avsluttes.

## 2026-10-21 — Si stedet i «Hvor skal du?»

- [ ] Med Pixel koblet til X7: åpne «Hvor skal du?», trykk mikrofonen til høyre i feltet og si et sted i hjelmen (f.eks. «Geiranger»). Feltet fylles mens du snakker, og søket viser stedet. Trykk mikrofonen igjen midt i for å stoppe.

## 2026-10-21 — Ring eller SMS fra Telefon

Companion og Curveon («Denne enheten») ber nå om lov til å sende SMS: godta den på Pixel, i companion og i Telefon-innstillingene.

- [ ] Telefon → Favoritter/Kontakter/Nylige: trykk en person. Dialogen viser navnet, numrene (mobil valgt), Ring, SMS og Avbryt.
- [ ] SMS: si en kort melding i hjelmen, se teksten på kortet «SMS til …», trykk Send. Kommer SMS-en fram, og ligger den i Meldinger-appen på Pixel som sendt?
- [ ] «Si det igjen» (runde pil-knappen) på SMS-kortet starter opptak på nytt, X avbryter uten å sende.
- [ ] Ring fra dialogen ringer det valgte nummeret (prøv en kontakt med to numre).

## 2026-10-21 — Les opp meldinger

- [ ] Med Pixel koblet til X7: få en melding, trykk høyttaleren ved avsendernavnet. Meldingen skal leses opp i hjelmen («Navn skriver: …»), musikken dempes litt mens den leses. Uten norsk stemme lastet ned på telefonen kommer bare en lyd.

## 2026-10-21 — SOS-knappen utløses ikke lenger av å skli

- [ ] Venner → Gruppetur: legg tommelen på SOS-knappen og dra opp/ned (scroll) eller ut av knappen. Fremdriftsbaren skal nullstilles og **ingen** nedtelling starte. Holdes den i ro i tre sekunder skal nedtellingen på 10 starte som før.

## 2026-10-20 — Venner og grupper bor i Curveon, telefonen er veien til nettet (ikke utgitt, i 1.2.2)

Bygget og enhetstestet (tunnelklienten og -serveren mot hverandre, og de nye meldingene), prøvd på X7 over USB: vennekoden vises med en gang
uten telefon i nærheten, og ingenting krasjer. **Ikke prøvd, fordi det trenger Pixelen og relayen på nett:** alt under. Companion har ikke
lenger egne venner eller grupper; den viser hvordan nettbrettets aktive gruppe har det, ber nettbrettet om en vennekode og leverer det den
skanner. Nettbrettet bruker sitt eget internett hvis det har noe, ellers telefonens via Bluetooth.

- [ ] **X7 med Pixel koblet til, X7 uten internett** (wifi av, ingen hotspot): Venner → Mine venner → Vis koden min: koden kommer opp i løpet av et sekund eller to (laget på X7, ingenting sendt til telefonen).
- [ ] **Det samme, start en gruppe** (bruker `relay.curveon.no`). Gruppen skal si «tilkoblet» via telefonen. Sjekk `adb -s <X7> logcat -s Curveon GroupHub`, og at telefonens databruk er liten.
- [ ] **Slå på wifi på X7 mens en gruppe kjører:** gruppen går over til X7s eget internett av seg selv (høyst et kort «frakoblet»). Slå wifi av igjen: tilbake via telefonen.
- [ ] **Pixel-companion → Venner → Vis koden min / Del lenke / Skann:** koden er X7s (en venn som skanner den dukker opp i X7s venneliste, ikke på telefonen). Er X7 ikke koblet til eller er slått av, får du «Open Curveon on your tablet or this phone first».
- [ ] **Companionens Gruppetur-kort** viser X7s aktive gruppe og «tilkoblet» / «frakoblet» som X7 gjør. Det tømmes når X7 kobler fra.
- [ ] **Gammel companion på Pixelen med nytt X7-bygg:** X7 overser det den gamle companionen sier om grupper, så ingenting dobles. (Oppdater companionen for å få tunnelen.)
- [ ] **Mine enheter (Curveon-appen på Pixelen + X7).** Venner → Meg → Mine enheter: «Vis kode til min andre enhet» på den ene, «Skann kode fra min andre enhet» på den andre; begge skal så si «Koblet». Prøvd på X7 og en telefonemulator mot relayen på denne PC-en (kobling via intent, ikke kamera): begge sa koblet, og en gruppe startet på X7 dukket opp på emulatoren. **Ikke prøvd:** skanning av koden med ekte kamera, at profilen (navn, farge, bilde) følger med, at en venn lagt til på den ene vises på den andre, at å forlate en gruppe på den ene fjerner den på den andre, Koble fra, og selve Pixelen. Gjør kameraskanningen, legg så til en venn på X7 og åpne Curveon på Pixelen: vennen skal være der i løpet av sekunder (en venn vises ikke før hilsenen deres er hørt).

## 2026-10-21 — Turer slutter etter 5 minutter, «Fortsett turen?» (ikke utgitt, i 1.2.2)

Bare logikken er testet (enhetstester); spørsmålskortet er ikke sett under ekte kjøring.

- [ ] Kjør noen km («Lagre nye turer» på, i GPX og turer → Turer), stopp i over 5 minutter: turen avsluttes i turloggen (og GPX-kopien lages hvis autolagring er på), den røde prikken er borte.
- [ ] Kjør videre innen to timer: et kort «Fortsett turen?» med Fortsett / Ny tur dukker opp øverst på kartet etter noen sekunders kjøring. Sjekk at det ikke dekker ankomstboksen, fartskortet eller veikortet på X7 og Pixel, også under navigering.
- [ ] Fortsett: turloggen viser én tur (ingen ny), distansen vokser, og med autolagring har GPX-gruppen ett spor for hele turen, ikke to.
- [ ] Ny tur (eller ignorer kortet i 30 s): kortet forsvinner, og den forrige turen blir som den var.
- [ ] Et stopp du avslutter med stoppknappen, eller et som varer over to timer, spør ikke.

## 2026-10-20 — Grus og ulendt terreng på kartet (ikke utgitt, i 1.2.2)

Installert på X7, men det vistes ingen grus i nærheten av skrivebordet, så det nye utseendet er ikke sett ennå.

- [ ] Kjør eller panorer til en grusvei (zoom 11 eller nærmere) med Innstillinger → Skjerm → Veidekke = Stiplet: selve veien skal se stiplet ut, samme farge og ikke tykkere enn nabovegene, brun for ulendt terreng. Sjekk at mellomrommene ikke viser en blek linje over skogen.
- [ ] Det samme med Farget: oransje grus, lilla ulendt terreng, som ruten. Også på nattkartet.
- [ ] Med Stiplet eller Farget på ligger stedsnavn (byer, tettsteder) over grusstrekene, ruten og GPX-sporene, slik de gjør med Av. Strekene skal se tynnere ut enn hovedveien og den tynne landeveien, f.eks. ved Helland.

## 2026-10-19 — Gruppeturer (ikke utgitt, ingen versjonsøkning ennå)

Prøvd 2026-10-19 på X7 (som sin egen telefon, over en relay på denne PC-en med simulerte ryttere): å bli med
via lenke, posisjoner begge veier, kartmarkørene, gruppedialogen, invitasjons-QR-en, SOS begge veier og «Jeg
kommer». Ikke prøvd, fordi det trenger Pixelen: den ekte kjeden X7 → Bluetooth → companion → relay.

Sett opp en testgruppe uten en ekstra rytter (relayen og de simulerte rytterne ligger i `~/develop/curveon-relay`):
`npm start` der; `node test/rider.ts create --riders 3 --lat <lat> --lon <lon> --sos-after 120` skriver ut en
invitasjonslenke (`curveon://join#...`) og holder tre ryttere i sirkel rundt punktet. `adb -s <phone> reverse tcp:8787
tcp:8787`, åpne så lenken på telefonen: `adb -s <phone> shell "am start -a android.intent.action.VIEW -d '<link>'"`.

- [ ] **Etter omstart av Curveon** (tving stopp, start) med en aktiv gruppe: rytterne er tilbake på kartet (og den blå prikken på Venner-knappen) i løpet av ca. 10 s. Sett én gang på X7 (som egen telefon) at de *ikke* var tilbake etter omstart selv om gruppen var lagret (`shared_prefs/groups.xml` hadde den): skjer det igjen, hent `adb logcat -s GroupHub` og si når.
- [ ] **SOS sendt fra dialogen:** det røde kortet «SOS sendt til gruppa» ligger øverst i dialogen (det lå bak dialogen før denne rettelsen) med «Mottatt av n av m» som teller opp, og «Avbryt SOS» virker.
- [ ] **Overlapp (CLAUDE.md):** Venner-knappen ligger i hovedmenyen (skinnen) mellom Telefon og Innstillinger, så kartknappkolonnen er som før. Sjekk på Pixel (stående: seks knapper langs bunnen; liggende: seks nedover siden med kartvekslingen fortsatt innen rekkevidde) og X7 liggende. Dialogen åpnes i panelområdet som Telefon-dialogen.
- [ ] **Curveon alene på en telefon (ingen companion installert):** en hel gruppetur må fungere: Venner → Gruppetur → Start en gruppe, Inviter ryttere (QR eller Del lenke), navn og bilde (Venner → Meg), Skann QR-kode eller åpne en annens invitasjon, SOS. Ingenting skal si at du trenger companion-appen.
- [ ] **Bildet ditt, fra kameraet:** Venner → Meg → Ta et bilde: kameraet åpnes (appen ber først om kameratillatelse, siden den også trenger den til skanning), så kommer bildet tilbake i et beskjæringsvindu: dra og zoom med to fingrer til ansiktet ditt er i sirkelen, Bruk dette bildet, og den store runde forhåndsvisningen viser nøyaktig det de andre får se. Det lagres av seg selv («Lagret»). Prøv også Velg fra bilder og Fjern. Sett på X7: valg av bilde, beskjæring (dra) og forhåndsvisningen; ikke sett: kamerabanen og tofingerzoom.
- [ ] **Skanne en QR-kode i Curveon** (Venner → Gruppetur → Skann QR-kode): kameratillatelsen spørres først nå. Hold kameraet foran QR-en til en invitasjon (fra den andre enhetens gruppe → Inviter ryttere), og med Venner → Meg → Skann kode fra min andre enhet foran QR-en til «Vis kode til min andre enhet» på den andre enheten, i armlengde og nærmere: begge skal leses i løpet av et sekund eller to og bli med. Sett så langt: kameraet åpnes uten feil på X7 (bakkamera 0), og en QR tegnet og lest i enhetstester, også snudd og liten; ikke sett: en kode holdt foran det ekte kameraet. Si fra hvis en tett kode er vanskelig å lese, så øker jeg bildestørrelsen.
- [ ] **Ditt eget bilde:** Venner → Meg → Velg fra bilder (systemets bildevelger, ingen tillatelse spørres). Markøren på kartet og raden i gruppelisten viser det rundt; de andre ser det etter noen sekunder. Prøv et portrettbilde tatt sidelengs (det må snus riktig) og fjern det igjen.
- [ ] **Bare én enhet sender posisjonen din:** med Curveon på både nettbrettet og telefonen i samme gruppe (som én rytter, se under), kjør med den ene: den andre viser «Posisjonen din deles fra den andre enheten din» med «Bruk denne enheten»; trykker du på den på den andre, flyttes sendingen dit (og den første viser så merknaden). En enhet som er blitt igjen med appen åpen overtar av seg selv etter tre minutter hvis den andre har blitt stille.
- [ ] **Én rytter på to enheter:** i en gruppe, Venner → Meg → Vis kode til min andre enhet på nettbrettet; på telefonen med Curveon, Venner → Meg → Skann kode fra min andre enhet (eller `adb shell am start -a android.intent.action.VIEW -d '<link>'`): de andre skal se én rytter, ikke to, og en posisjon sendt fra begge enhetene når dem. Koden slutter å virke etter 15 minutter.
- [ ] **SOS fra noen andre** er en liten rød dialog (ikke fullskjerm): «Kjør dit» starter en rute til dem, «Ignorer» lukker den, og den sier hvor mange som er på vei. Alarmen stopper når du gjør ett av delene.
- [ ] **Tunnel / ingen dekning:** kjør inn i en tunnel eller sett telefonen i flymodus: gruppen sier «Ingen kontakt med gruppa», posisjonen sendes én gang når dekningen er tilbake, og en SOS laget uten dekning går ut når den kommer tilbake.
- [ ] **Batteri:** én times kjøring med en aktiv gruppe, telefonskjermen av: sammenlign med samme tur uten gruppe (telefonen bruker radioen hvert 5. s; databesparelse 15 s).
- [ ] **Invitasjonslenker med begge appene på én telefon:** `curveon://join` håndteres av både Curveon og companion, så Android spør hvilken som skal brukes. Velg companion når den er paret med et nettbrett, ellers Curveon.
- [ ] **Samsung-/Xiaomi-telefoner** (ikke Pixel) kan drepe companion i bakgrunnen: blir en gruppe stille på noens telefon, noter modellen.
- [ ] **Ikke bygget ennå, så ikke let etter det:** et varsel for SOS når Curveon ikke er åpen på telefonen.

## 2026-10-19 — Rutefarge, beskårne hjelpepunkter, tid til neste stopp (1.2.2)

- [ ] Naviger en rute med grus og terreng i: asfalt beholder den vanlige blå, grus er helt oransje, terreng helt lilla, ingen gjennomsiktige streker på ruten. Natt- og dagkart, og ved rytterpila (oransje om natten) sjekk at den fortsatt er lett å se.
- [ ] Rundkjøring i svingkortet (demotur forbi en): tre piler som snur mot klokka, utkjørselsnummeret klar av pilspissene. I et land med venstrekjøring (f.eks. Storbritannia, hvis kartet ligger på enheten) snur de med klokka.
- [ ] 3D-kart under kjøring: pila sitter litt lavere enn før uten å røre kortene nederst (også på telefon stående og liggende); standard vipp er brattere (55° i stedet for 50°; har du satt en vipp før, beholdes din).
- [ ] Legg til flere stopp under kjøring (også ved å trykke i kartet, og en bensinstasjon), med veistilen Raskest: hvert stopp ligger på ruten (linja går til hvert, også en kort omvei), numrene følger rekkefølgen du kjører dem i, og «stopp nådd» kommer først når du er ved stoppet. Si fra hvis et stopp fortsatt passeres på avstand: hent `navtrace` og send `E,…addStop`-linjene.
- [ ] Gjør et GPX-spor om til en rute (GPX-listen → cyan kjør-knapp → Konverter til justerbar rute): linja er cyan, ikke grønn. Det samme når du kjører et spor eller en loggført tur.
- [ ] Etter konvertering, vent et minutt eller to uten å røre noe, åpne så rutemenyen: færre stopp enn før? Hent loggen og se etter antallet beskårne: `adb logcat -d -s Curveon | grep Pruned` («N -> M via points»). Rediger turen (dra, legg til stopp) mens det jobber: ingenting skal hoppe eller gå tapt.
- [ ] Ankomstboks (demotur med et stopp og et drivstofforslag): brettet sammen = ankomsttid, og under den ankomsttiden til neste stopp, mindre i oransje, bare når det er et stopp på veien (ellers ingenting ekstra); utbrettet = km og tid til målet, en tynn linje, så hvert stopp (navn, så km og tid i oransje, samme kolonner), et lite rødt kryss på hvert stopp spør «Slette <stopp>?» (ikke på stoppene til et GPX-spor), flere stopp nedenfor med linjer imellom, så drivstoffbrikken, Legg til stopp og Rute; trykk på drivstoffbrikken: en dialog spør «Kjøre til <stasjon>?», Ja legger den til som stopp. Sjekk telefon stående og liggende, og nettbrettet, med fartskortet bredt (over 100 km/t).

## 2026-10-18 — Drivstoffstopp på en demotur, turlinje i fart, gammelt veinummer (1.2.2)

- [ ] Trykk på drivstoffbrikken og svar Ja: et nummerert oransje stopp dukker opp på stasjonen, ingen «A»-prikk der du er; rutemenyen lister det (uten ×, stoppene til et spor kan ikke tas ut ett og ett). Si fra hvis ruten til stasjonen ser feil ut.
- [ ] Avslutt demoturen, kjør/stå et annet sted (ikke nær E18): E18-kortet er borte eller viser veien du er på.

## 2026-10-18 — Nytt stopp som ikke tegner ruten på nytt (1.2.2)

- [ ] Legg til et stopp under navigering. Endrer ikke ruten seg (eller skjer ingenting), noter hvilket stopp og hvor det var, og hent så sporet: `adb -s <device> pull /sdcard/Android/data/com.curveon.app/files/navtrace` og se etter linjene `E,…,addStop '<name>' <lat> <lon>: …` (started / done / cancelled / failed / no route / no position). Kjent fra Pixel-sporet 2026-10-18: én ruteendring 1,5 km ut i turen beholdt nøyaktig den gamle linja (1235 mot 1236 punkter), den neste, 40 s senere, fikk en liten omvei.

## 2026-10-18 — Ankomstboks øverst til venstre, veikort under den (1.2.2)

- [ ] Navigering med veikort synlig: ankomstboksen er øverst, veikortet rett under. Trykk ankomstboksen åpen: veikortet flytter seg ned og rører den aldri. Uten veiinfo står ankomstboksen alene øverst til venstre.
- [ ] Veikort Stor / Ekstra stor: fortsatt under boksen, aldri overlapp, også utbrettet.
- [ ] Grus-varmekartknappen (ikke under navigering): veikortet ligger under den.
- [ ] Høyre side: bare fartskortet og kartknappene.

## 2026-10-18 — Stedsnavn øverst, tynnere veikant (1.2.2)

- [ ] Nettkart OG nedlastet kart, dag- og nattkart, flere zoomnivåer (10–16), også utzoomet: ingen vei, grusstrek eller asfaltlinje går over et stedsnavn. Navnene på nettkartet lå under overleggslinjene før.

## 2026-10-18 — Veinavn ved veiene, høy kontrast under navigering, musikkspilleren blir stående (1.2.2)

- [ ] Veinavn står ved veien fra zoom 15, alle fra samme zoom; lesbare og ikke for langt fra veien (også på brede veier)?
- [ ] Dagkart: stedsnavn (mørk tekst på lys glorie) er lesbare når du har zoomet litt ut.
- [ ] Stående, fullskjermkart, musikkikon, under navigering: trykk på ikonet. Spilleren åpnes i samme høyde, ved siden av svingkortet, ikke over det.
- [ ] Veinummeret i svingkortet (38 dp) er lett å lese med et blikk.

## 2026-10-18 — Veikortets størrelse, ensidige fartsskilt, bredere rundkjøringsring (1.2.2)

- [ ] Et fartsskilt står bare på høyre side av veien, der grensen endres.

## 2026-10-18 — Fartsskilt på kartet, stoppvarsel, rundkjøringsikon, veiinfo uten kart (1.2.2)

- [ ] En tur med et stopp på veien: når du når det (30 m) vises et banner «Fremme ved <navn>» i 8 s og ankomstlyden spilles (eller navnet sies med talenavigasjon); trykk lukker det. Sjekk at det ikke utløses igjen, og ikke for målet.
- [ ] Hold en finger på en vei i et land der kartet ikke er lastet ned (f.eks. utlandet): veikortet ber deg laste ned kartet over <land>.

## 2026-10-18 — Veikortets plass, små veier på dagkartet, nattpil (1.2.2)

- [ ] X7 i skarpt dagslys, dagkart (Innstillinger → Skjerm → Kart: Dag, eller Auto på dagtid): grå små veier (Innstillinger → Skjerm → Veier: Grå eller Mørk grå) og gule hovedveier med mørk kant: lett nok å se? Si fra om grått bør være mørkere/lysere eller kanten bredere. Stier og traktorveier er en lysere grå.
- [ ] Nattkart under navigering: pila har hvit kant og lys glorie; tydelig nok på det mørke kartet?

## 2026-10-18 — Roligere navigasjonskort, samtalekort på dashbordet (1.2.2)

- [ ] Pixel (Curveon på selve telefonen): ring den og trykk Svar på Curveons samtalekort. Den skal svare (hvis ikke: `adb logcat -s PhoneLink` og se etter «Call action»). Aldri prøvd med en ekte samtale.
- [ ] Svar på en samtale med dashbordet synlig (ikke fullskjermkart): et samtalekort med Legg på og Høyttaler ligger øverst i kortkolonnen, ikke noe grønt ikon over kartet. Fullskjermkart: det grønne ikonet er fortsatt der.
- [ ] Fullskjermkart, musikk spiller: et lite coverikon nede til høyre; trykk åpner spilleren, trykk på coveret (eller vent 10 s) bretter den sammen. Sjekk at kartknappene og svingkortet ikke kolliderer, åpen og sammenbrettet.
- [ ] Under navigering: svingkortet er smalt, trykk viser ordene.
- [ ] Innstillinger → Skjerm → Fartsgrenseskilt: Stor og Ekstra stor vokser over fartskortets topp og bunn uten at kortet vokser; ingenting kuttes ved skjermkanten (også med fotoboks eller høydelinje under).

## 2026-10-18 — Raskere kartnedlastinger, neste land starter mens det siste ferdigstilles (1.2.2)

- [ ] Pixel: Innstillinger → Kart og data → legg til et mellomstort land (f.eks. Danmark) på wifi: det skal lastes ned tydelig raskere enn før (PC-testen gikk fra 46 s til 14 s for 96 MB). Noter tiden og farten på telefonhotspot også.
- [ ] Pixel: legg to eller tre land i kø. Når det første kartet er ferdig, viser raden sin egen linje for ruting/søk mens det andre kartet allerede lastes ned; begge kan avbrytes hver for seg. Sjekk at appen ikke går tom for minne (`adb logcat -s AndroidRuntime`) og at kartet over land 1 åpnes mens land 2 lastes ned.
- [ ] Pixel: kutt forbindelsen halvveis (flymodus på) mens et land lastes ned: feilen vises, delvise filer ryddes bort, og neste forsøk virker.

## 2026-10-18 — Forberedelse til Play: appnavn uten QUERY_ALL_PACKAGES

- [x] Pixel (installert) og X7: en melding i en app som ikke er en musikkapp (WhatsApp, Messenger, SMS) vises fortsatt med appens navn («WhatsApp»), ikke `com.whatsapp`. Også en samtale/et varsel fra en launcher-app du sjelden bruker.
- [x] Innstillinger → Navigasjon/Musikk: lista over musikk- og podkastapper er den samme som før (Spotify, NRK Radio, YouTube Music, ...), og å starte en som ikke kjører virker fortsatt.
- [x] Hvis et appnavn vises som et pakkenavn: `adb shell dumpsys package <package> | grep -i launcher` og si hvilken app det var.

## 2026-10-18 — Eventyr finner flere ruter; hvit Hjelpepunkt-knapp (1.2.2)

- [ ] Pixel: planlegg et eventyr med et område og lang tid (f.eks. ovalen Hallingdal → Gudbrandsdalen, 7 t), og stokk så noen ganger: hver stokking skal gi en annen rute nær ønsket tid (før kunne første forsøk bli halv tid, og senere forsøk ga ingenting).
- [ ] Pixel: planlegg et eventyr: Tur 1 vises med en gang med tid og km, og et fet gult «Første forslag. Leter etter en bedre tur …» under mens den fortsetter å lete; første tur skal ligge nær ønsket tid (før kunne den bli langt under). Bedre turer dukker opp som Tur 2, 3 ved siden av.
- [ ] Pixel: tegn et eventyrområde: tidsknappen sier «Ingen grense» (grå) fra starten. Planlegg: turene kommer med egne tider (et langt område kan gi 8–10 t); velg en tid på knappen, så begrenser den turene som før; velg «Ingen grense» igjen for å gå tilbake. Sjekk hvor ofte en tur uten grense havner utenfor det tegnede området (det skjedde for 2 av 4 varianter i PC-testen).
- [ ] Pixel: Juster ruten → Endre i kartet: hengelåsen er låst hver gang du går inn, også etter at du låste den opp ved en tidligere redigering.
- [ ] Når du planlegger (ikke kjører), trykk på kartet for et nytt punkt: linja «Stopp på veien, eller nytt mål?» har Hjelpepunkt (hvit med svart tekst, skiller seg fra kartet), På veien og Nytt mål, og en ✕ for å droppe et feiltrykk. Sjekk at den passer på Pixel stående. (Under kjøring legges et trykket sted rett og slett til som stopp.)

## 2026-10-17 — Oppsett av drivstoffmåler: tank to ganger, motor av (1.2.2)

- [ ] Innstillinger → Motor → Oppsett av drivstoffmåler, X7 eller Pixel med OBD2-adapteren på motorsykkelen, motor
  **av**, tenning på, parkert på samme måte hver gang. Tankstørrelse i trinn 2. Ved pumpa: Før tanking (leser ~40 s: to
  målinger med 15 s mellom), fyll til første klikk, parker på samme måte, skriv inn pumpeliterne, Etter tanking. Gjør
  det ved to tankinger. Trinn 4 skal da tilby en avlesning som stiger eller faller med literne; velg den og sjekk at
  trinn 5 og dashbordets Bensin viser liter som stemmer med motorsykkelens egen måler.
- [ ] Trinn 6 (finjustering): med avlesningen valgt, registrer et nivå du kjenner (f.eks. etter å ha helt i 2 L fra en kanne, eller Full): det leses ~40 s og listes i trinn 3; literne på dashbordet følger tabellen. Fjern det igjen med krysset: den valgte avlesningen blir stående.
- [ ] Hvis trinn 4 ikke tilbyr noe: det sier om adapteren hørte bussen i det hele tatt. Hent `files/navtrace/obd-*.txt` og send den.
- [ ] Fjern en tanking med krysset (begge nivåene går); den valgte avlesningen går også når bare én tanking er igjen.
- [ ] La Før-avlesningen stå i over 30 min, så Etter: det sier at avlesningen er for gammel og må fjernes.
- [ ] Gamle nivåer registrert med den tidligere versjonen listes ett og ett; de teller ikke som tankinger, så registrer to på nytt.

## 2026-10-17 — Framdrift når et GPX-spor gjøres justerbart fra kjørearket (1.2.2)

- [ ] GPX og turer → GPX: cyan kjør-knapp → Kjør GPX herfra, så trykk på justeringsikonet (EditRoad) «Juster som en rute» i toppen av kjørearket: arket viser «Lager rute av <navn>…» i cyan over valgene til ruten er klar.

## 2026-10-17 — Fotobokser bare når du har zoomet inn (1.2.2)

- [ ] Innstillinger → Skjerm → På kartet: Fotobokser på. Utzoomet over en by (f.eks. Bergen) ingen røde prikker; zoom inn til husene dukker opp, så vises de.

## 2026-10-17 — Velg-knappene blir stående i Turer og Planlagte (1.2.1)

- [ ] Pixel, stående: GPX og turer → Turer (og Planlagte): rull ned, velg-knappen blir stående i raden over lista; velg to, og knappene Alle / slå sammen / slett blir også stående.
- [ ] Pixel, liggende: de samme knappene sitter i tittellinja, med «2 valgt» under tittelen.

## 2026-10-17 — Grusfarger, smalere streker, DEMO-merke, oppsett av drivstoffmåler

- [ ] Innstillinger → Navigasjon → Demotur på, start en navigasjon: et tydelig rav-farget DEMO-merke sitter rett under
  rytterpila og følger den. Sjekk at det ikke dekker noe (telefon stående også). Det forsvinner når demoturen er av
  eller navigasjonen stopper.

## 2026-10-17 — Veipunkter skjult i ruteplanleggeren

- [ ] Planlegg en TET-rute (f.eks. N-01) på Pixel og X7: brikkeraden i planleggeren viser bare
  «Juster ruten» (og startbrikken), ingen rad med hvite veipunktbrikker. Et stopp du la til med
  «Legg til stopp» vises fortsatt som en nummerert brikke; rutelinja og kartet er uendret.

## 2026-10-17 — Kjappere app (faner, kart, ruter, GPX)

- [ ] På en telefon holdt stående: sveip mellom innstillingssidene. Den lysende fanen skifter så
  snart neste side er mer enn halvveis inne, ikke etter at den stopper. Å trykke på en fjern fane
  glir fortsatt rett dit uten at den lysende fanen flimrer forbi de imellom.
- [ ] Planlegg eller kjør en lang rute (f.eks. TET N-01) på X7: rutelinja, A/B-nålene og fartskortet
  skal se ut som før, og skjermen skal ikke henge seg opp når ruten dukker opp eller en GPX-gruppe
  slås på eller av. Målt på X7 med demotur: søppeltømmeren kjørte omtrent halvparten så mye, og
  hovedtråden hadde langt mindre å gjøre mens en rute var vist.
- [ ] X7 kjører nå `ride`-bygget (`./gradlew :app:installRide`): ikke debuggbart, så den starter på 0,8
  s i stedet for 2,8 s og ruter et GPX-spor på 1558 km på 31 s i stedet for 43 s. Det installeres over
  debug-bygget og beholder dataene; bruk det på styret, `installDebug` bare når du feilsøker.

## 2026-10-17 — GPX-kjørknapp: kjør eller konverter

- [ ] I GPX-listen, trykk på den cyan kjør-knappen: en meny med «Kjør GPX herfra» (som før) og «Konverter til justerbar rute». Begge viser en
  framdriftsmelding mens de jobber; å konvertere TET N-01 tar ~15 s. Ruten skal nå følge sporet overalt (ingen røde striper; målt 0 % av sporet
  utenfor ruten, var 50 %), med hvite rutepunkter langs den (færre etter et minutt eller to, se sjekken fra 2026-10-19).

## 2026-10-17 — Bedre GPX → rute, rutepunkter, avbryte et trykk

- [ ] Gjør TET N-01 Øyermoen–Blåhø om til en rute («Juster som en rute»). Den legger nå til
  ekstra punkter der sporet tar en sidevei ruteren ville hoppet over (det tar litt lengre tid
  før den dukker opp). Sjekk at ruten følger sporets små veier, og at den ikke trenger manuell
  retting.

## 2026-10-17 — GPX-spor eller tur som rute

- [ ] Åpne et GPX-spor (GPX → cyan kjør-knapp → Kjør GPX herfra) og trykk på «Juster som en rute»-knappen
  ved siden av snupila. Det skal bli en vanlig tur fra sporets start til slutten med stopp langs det
  («Juster ruten» i arket), holde seg til sporets veier og bli stående på kartet mens det regnes om. Dra
  i linja, legg til et stopp, og lagre det så som GPX-spor. Sjekk et rundt spor (start = slutt) og et
  langt ett (200+ km). Det samme fra Turer → Kjør igjen.

## 2026-10-17 — Opptaksprikk, stoppknapp og sammenslåing av turer

- [ ] Kjør (eller demotur) i et minutt, stå så i ro: etter ca. 5 sekunder skal en rød prikk
  dukke opp på GPX-knappen i skinnen, og i GPX-dialogen i Turer-fanen. Den går når du kjører av
  gårde, og når turen slutter. Sjekk at den ikke overlapper noe på X7 og telefon, i begge
  retninger, under navigering og ikke, med musikkspilleren på og av.
- [ ] GPX → Turer, under opptak: «Stopp» i opptakskortet avslutter turen med en gang og den vises i
  lista. Å kjøre videre starter ikke en ny før du har stått i ro én gang.
- [ ] Turer → velg to eller flere (langt trykk, eller velg-knappen): en slå sammen-knapp dukker
  opp. Etter bekreftelse er én tur igjen med samlet distanse og tid, fra der den første begynte
  til der den siste endte; åpne den på kartet («Kjør igjen») og sjekk at sporet er helt.
  GPX-kopier som allerede er lagret av autolagring blir stående (som ved sletting).

## 2026-10-17 — Kartdetaljer (grus og små veier lenger ut)

- [ ] Innstillinger → Skjerm → Kartdetaljer, bytt til «Detaljert» (standard er «Rask»): grus- og jordstreker og de små
  asfaltveiene mellom dem vises fra zoom 10, og grusvarmekartet er da borte. Panorer rundt i et grusrikt område
  (Telemark, Hardanger) på X7 og se etter hakking; er det tregt, bytt tilbake til «Rask» (zoom 11, som før) og si fra, så
  kan zoomen endres. Veier som leder til grus (de tynne opp en oppkjørsel) vises fra 11 uansett.

## 2026-10-17 — Kartet blir stående, eventyrtid er et tak

- [ ] Planlegge en tur, redigere en rute, avbryte en forhåndsvisning: panorer/zoom kartet et
  annet sted. Det må ikke hoppe tilbake til deg når forhåndsvisningen/redigeringen/valget
  slutter; bare å starte navigering eller «tilbake til meg»-knappen flytter det. Å tegne et
  eventyrområde zoomer ut der kartet allerede er.
- [ ] Kjøring med steder på kartet (Innstillinger → Skjerm → På kartet: fotobokser, drivstoff, …):
  de skal vises mens du følger rytteren i 3D også, også i veifart. Før slo størrelsen på
  3D-visningens grenser (som når til horisonten) av alle slag. Hvis fortsatt ingenting: `adb logcat
  -s Curveon:I | grep "Map places"` sier hvor bredt kartet regnes, og hvilke lag som var på.
- [ ] Lang tunnel (et 2 km rett stykke i ruten, eller 20+ km): rytteren fortsetter langs ruten som
  en blek pil med den oransje satellitten hele veien, uten tidsgrense; når du kommer ut tar den
  ekte GPS-en over, navigasjonen kjører fortsatt og 3D-visningen er der fortsatt. (Turen 17. okt
  viste årsaken: sporeren satt fast i enden av et 2 km stykke av ruten og kalte det utenfor ruten;
  rettet, med en test.) Går det fortsatt galt, hent navtrace (`adb pull .../files/navtrace`): se
  etter «route cleared» og `F`-rader med `estimated` = 1.
- [ ] Et vikeplikt-/stoppskilt foran under navigering og under fri kjøring: skiltet og avstanden
  teller ned i veikortet (med veinummeret og forkjørsskiltet), og ikke lenger under farten. Sjekk
  at kortet dukker opp av seg selv når veien ikke har noe nummer.
- [ ] Hold 3D/N-knappen: kartet går til 3D og + / − dukker opp ved siden av (6 s etter siste trykk
  forsvinner de igjen). + ser mer ned på kartet, − lavere langs veien; vinkelen beholdes (også
  etter omstart av appen) til den endres. Å trykke på knappen veksler fortsatt N → retning → 3D.
- [ ] Zoom inn (11 eller nærmere) på en grus- eller jordvei med et GPX-spor eller en
  ruteforhåndsvisning oppå: strekene skinner gjennom linja, i utseendet valgt i Innstillinger →
  Skjerm → Veidekke (Stiplet / Farget). Under navigering er selve ruten oransje/lilla der i stedet,
  uten streker over. Sjekk at det leses på begge temaene, og at Veidekke Av også fjerner dem fra
  over linjene.
- [ ] Navigeringslyder på, kjør av ruten så «snu eller kjør videre»-dialogen dukker opp: en
  bjelle (to slag) ringer, tydelig annerledes enn de myke tonene utenfor ruten. Hør den i
  hjelmen gjennom intercomen også: høy nok, ikke avkuttet i starten.
- [ ] Eventyr over fjellene øst for Stord (start Bremnes, til Tveit, ring over Stord/Kvinnherad, 6
  t): turen skal nå gå over fjellveiene øst for fjorden i stedet for å sirkle én gang på vestsiden,
  og planleggingen («Finding roads…») skal gå raskere enn før.
- [ ] Eventyrplanlegging, mens «Første forslag. Leter etter en bedre tur …» (liten spinner,
  pulserende) fortsatt vises: hver bedre tur er en brikke «Tur 2 · 5:50 t · 290 km» ved siden av
  «Tur 1», tegnet grå på kartet. Trykk på brikkene for å bytte fram og tilbake (ingenting glemmes).
  Endre turen (dra, legg til stopp, stokk) eller lukk arket mens det søker: søket stopper. Sjekk at
  brikkene og spinneren ikke overlapper noe på telefonen, stående og liggende.

## 2026-10-16 — Stopp deling avslutter koblingen med en gang, Bluetooth-tips, vikeplikt utenfor en forkjørsvei

- [ ] Companion koblet til X7: trykk Stopp deling på Pixelen. X7 sier at telefonen ikke
  svarer i løpet av noen sekunder, og Play på X7 starter ikke lenger Spotify på Pixelen.
  Start deling igjen: X7 kobler seg til igjen.
- [ ] Telefonen svarer ikke (Innstillinger → Telefon på X7): hintet sier også at du skal slå
  Bluetooth av og på på nettbrettet og telefonen hvis det fortsetter å feile (engelsk og norsk).
- [ ] Planlagt rute som svinger til venstre fra Stavlandsvegen inn på KV1030 (Svortland): ikke
  noe vikepliktskilt i svingen.

## 2026-10-16 — Oransje nattmarkør, valg av forvarsel i stemmen, tips om frakoblet stemme

- [ ] Nattkart: rytterpila og kantgløden (kartet flyttet bort fra deg) er oransje, også på en
  grusrute; den cyan ruten skiller seg ut fra den. Dagkartet uendret (cyan).
- [ ] Innstillinger → Navigasjon → Lyd med Stemme: bryteren «Forvarsel». Av: bare «Sving til høyre
  …» i svingen, ikke «Om 300 meter».
- [ ] Uten en frakoblet stemme for språket (f.eks. bytt Curveon til engelsk hvis ingen engelsk stemme
  er lastet ned), viser valget Stemme et rav-farget tips om hvor den lastes ned; på Curveons egen
  enhet også en «Last ned stemme»-knapp som åpner nedlastingen. Borte når den er lastet ned og Stemme
  velges igjen.
- [ ] Innstillingssiden i telefonstørrelse (stående og liggende): de nye radene og tipset passer.

## 2026-10-16 — Talenavigasjon (Androids tekst-til-tale), tikk tidligere, nytt ikon

Oppdater Curveon på begge enhetene og companion på Pixelen.

- [ ] Innstillinger → Navigasjon → Lyd → Stemme: sier «Om 300 meter, sving til høyre» i hjelmen.
  Med X7 koblet kommer det fra Pixelen (`adb -s <pixel> logcat -s Speaker` viser «Speaking nb-NO
  with …-local»); Curveon alene på Pixelen sier det også.
- [ ] Frakoblet: på Pixelen, Innstillinger → System → Språk → Tekst-til-tale → Speech Services
  by Google → Installer taledata → Norsk (Bokmål). Med den sier loggen `-local`, og den snakker
  uten dekning. Uten den (og frakoblet) får du lydene i stedet.
- [ ] Demotur med Stemme: forvarsel «Om … meter, sving … inn på <vei>, deretter …», i svingen «Sving
  til høyre», «Du er fremme». Tydelige ord gjennom intercomen? For lang, for tidlig, for sent?
- [ ] Lyder: kommer tikket nå før du begynner å svinge (det er 2 s tidligere)?
- [ ] Startikon (motorsykkelen sett forfra) på begge enhetene og på companion.

## 2026-10-16 — Navigeringslyder og lyder ved feilkjøring

Curveon må oppdateres på begge enhetene, og companion på Pixelen (eldre companion overser lydene).

- [ ] Innstillinger → Navigasjon → Lyd: å velge «Lyder» spiller forvarselet i høyre øre, å slå på
  «Lyd ved feilkjøring» spiller den lave lyden for utenfor ruten. I hjelmen (via telefonen når X7
  er koblet til, rett fra telefonen når Curveon kjører alene på den).
- [ ] Kjøring (eller demotur): to myke toner ~12 s før en sving og et tikk ~3 s før, i øret til
  svingen. For tidlig, for sent, for høyt, for lavt? Musikken dempes kort i stedet for å pause.
- [ ] Kjør av ruten: én lav dobbeltlyd, og en lys en når den nye ruten er klar eller du er tilbake
  på den.
- [ ] Intercom: blir starten av lyden kuttet når ingenting annet spiller? (Det er 0,3 s stillhet
  først så headsettet våkner.)
- [ ] Under en telefonsamtale: ingen lyder.
- [ ] Lytt på PC-en først hvis du vil: `~/develop/Curveon-data/cue-sounds/*.wav`.

## 2026-10-16 — Kartet tegnet med OpenGL i stedet for Vulkan (Pixel-krasj)

- [ ] Pixel: bruk kartet som da det krasjet i dag (panorer, zoom, planlegg, naviger en stund).
  Ikke flere krasj; `crashes/` på enheten får ingen nye `-native`-rapporter.
- [ ] Begge: kartet ser ut og beveger seg som før (stedsnavn, rutelinje, dekkestreker, varmekart).

## 2026-10-16 — Krasjrapporter lagres på enheten

- [ ] Etter et krasj, åpne Curveon igjen, så `adb pull
  /sdcard/Android/data/com.curveon.app/files/crashes`: en `.txt` per krasj med tid, versjon og
  stakk (en native har også en `.pb`). Si fra til Claude, som kan lese dem.

## 2026-10-16 — Planlegge i kartet: hjelpeboble, hengelås på som standard

- [ ] Punkt for punkt (eller Velg i kartet under planlegging): en hjelpeboble over linja forklarer
  trykking, hengelåsen, knappen med stiplet linje og angre/ferdig. OK lukker den til neste gang;
  «Ikke vis igjen» for godt.
- [ ] Hengelåsen starter lukket (rav; linja kan ikke dras ved et uhell), og linja sier «Trykk for nytt
  punkt; streken står fast». Åpnet sier den «Trykk for nytt punkt, dra for å bøye ruta».
- [ ] Flamme-knappen (grusvarmekart) er borte til bryteren Innstillinger → Skjerm → Varmekart grus
  er på (standard av).

## 2026-10-16 — Grusfarger og grusvarmekartet

- [ ] Med Innstillinger → Skjerm → Varmekart grus på sitter flammeknappen øverst til venstre på kartet, også på Pixel
  liggende (den overlappet der før). Zoom ut til ~50–200 km bredt: varmt der det er mye grus; det tones ut når strekene tar
  over rundt zoom 11–12.
- [ ] Innstillinger → Skjerm → Varmekart grus på: knappen vises under planlegging og på det
  vanlige kartet, men ikke under navigering eller kjøring (borte fra 10 km/t, tilbake når du står
  stille). Av: ingen knapp.
- [ ] Zoomer du inn fra ~zoom 10: så snart grusstrekene dukker opp (zoom 11), tegnes de små
  asfaltveiene som leder til dem også (i fargen for små veier fra Innstillinger → Skjerm →
  Veier), så grusveier ikke lenger ser ut til å begynne i intet. Prøv et sted som KV 10122.
- [ ] Adkomst-/privatveier («Adkomst- eller privatvei», f.eks. ved Jerstad mot Heimrehølen) som
  leder til en grusvei vises nå sammen med den (til ~zoom 15,5, der kartet tegner dem selv).
  Oppkjørsler som ikke leder til grus, holdes utenfor.
- [ ] Første gang per 5°×5° rutingfil vises merknaden «Counting gravel here (once)…»; på X7 tok
  det ~50 s per fil. Etterpå går det øyeblikkelig (hurtigbuffer i `files/gravel-heat/`).
- [ ] I utlandet (et land lastet ned under Kart og data): varmekartet virker der også. Der
  det ikke ligger rutingdata på enheten, sier det det i stedet.
- [ ] Mens en fil telles, laster strekene fortsatt når du zoomer inn (de har sin egen leser
  nå).

## 2026-10-16 — Vikepliktskilt uten rute, og stående på kartet

Kjører du uten rute, ser Curveon ~150 m langs veien du er på (en kort rute rett fram, hvert 2. s
fra 10 km/t) og viser, hvis neste kryss er et der du har vikeplikt, skiltet og avstanden i
veikortet (øverst til venstre, ved veinummeret og forkjørsskiltet; vises selv med Veiinformasjon
av). Å stå stille i krysset beholder det. Skiltet står også på kartet, på høyre side av veien ~14 m
før krysset: det like foran under fri kjøring, og de på neste kilometer av ruten under navigering.
Sjekket i emulatoren med GPS-posisjoner langs Kv1164 inn på Fv541 (Bømlo): 90 m, 70 m, 40 m, så
borte på Fv541; skiltet på stolpen sin ved krysset.

- [ ] Ekte tur uten rute: ut av en sidevei og på en større, skiltet vises i god tid (~100 m
  før) og forsvinner når du er gjennom.
- [ ] Kjøring langs en hovedvei forbi sideveier: ingenting vises.
- [ ] Der veien deler seg eller svinger skarpt like foran, kan et feil skilt vises (framskuet
  går rett fram): noter hvor.
- [ ] Retning-opp- og 3D-kart: skiltet på kartet står oppreist ved veien.

## 2026-10-16 — Vikeplikt- og stoppskilt under navigering

Navigerer du i Norge, viser et kryss der du har vikeplikt (eller må stoppe) skiltet i veikortet,
med avstanden, fra 200 m før til du er der. To kilder, begge i den nye norway.sqlite fra NVDB:
selve skiltene (202 Vikeplikt, 204 Stopp, ~67 000), de som vender mot trafikken på veien du kjører
og de for en sidevei du kommer ut av; og å svinge inn på en forkjørsvei fra en annen vei, siden
slike sideveier ofte ikke har skilt. Å kjøre rett over en forkjørsvei er utelatt (kan ikke skilles
fra en bro over den), likeså private veier og kryss under høyreregelen. Sjekket i emulatoren
(demotur, Bømlo): Kv1164 inn på Fv541 viser trekanten med samme avstand som svingkortet, borte når
du er på Fv541.

- [ ] Ekte tur med rute: ut av en sidevei og inn på en fylkesvei, og inn i en rundkjøring:
  trekanten vises ~200 m før og forsvinner i krysset.
- [ ] Ut av en sidevei og inn på en forkjørsvei (den gule diamanten vises når du er på den):
  trekanten før svingen, med samme avstand som svingkortet.
- [ ] Kjøring langs en forkjørsvei forbi sideveier: ikke noe skilt (de er for sideveiene).
- [ ] Å svinge av en hovedvei inn på en sidevei: ikke noe skilt (skiltet vender andre veien).
- [ ] Et skilt vist der det ikke er noe, eller manglende der det er ett: noter stedet (eller
  send navigasjonssporet) så retningsreglene kan justeres.

## 2026-10-15 — Punkt for punkt: start der fingeren ble holdt, eller fra min posisjon

En finger holdt på kartet → Punkt for punkt spør nå først: **Start her** (turen starter på det
stedet; ingenting rutes fra der du er, f.eks. når du planlegger en tur i utlandet hjemmefra)
eller **Fra min posisjon** (som før). Sjekket på X7: Start her satte A på det holdte stedet,
neste trykk planla derfra («Fra Søvoll»).

- [ ] Pixel: hold en finger langt unna (et annet land), Punkt for punkt → Start her, trykk
  noen punkter: ingen ruting fra der du er, A blir stående på det første stedet.

## 2026-10-15 — Åpne GPX-filer fra andre apper; ryddigere veipunktkort; sporets navn på en holdt vei

- Curveon tilbys for GPX-filer åpnet eller delt fra andre apper. Sjekket på X7: en GPX i
  Nedlastinger åpnet med Curveon (Android kaller den `application/octet-stream`) ble importert
  og GPX og turer åpnet med den øverst. Curveon kan nå også tilbys for andre binær- eller
  XML-filer; de sier bare at det ikke er noe spor i dem.

- Veipunktkort på en telefon stående: teksten ligger over faktaene i stedet for ved siden av (den
  fikk nesten ingen bredde der og ble veldig høy), og tomme linjer i teksten droppes.

- En gruppes fargevalg: «hvert spor sin egen farge» er alltid den mangefargede ringen (sjekket på
  X7 med en helt blå gruppe).

- En finger holdt på et GPX-spor: kortet navngir sporet (med fargen) under veien.

- [ ] Pixel: åpne en .gpx fra Filer, Gmail og Drive → «Åpne med Curveon» → importert, og GPX og
  turer viser den. Del en .gpx til Curveon også.

- [ ] Pixel stående: trykk Reinheimen Lodge (TET N-13 Lom-Grotli): kortet er bare så høyt som
  teksten, med teksten over faktaene.

- [ ] Hold en finger på en vei under et vist GPX-spor: kortet navngir sporet.

## 2026-10-15 — Glorie rundt rytterpila: hvor sikker GPS-en er

En gjennomsiktig skive under pila, så bred som GPS-ens nøyaktighet: normalt en liten ring rett
rundt pila; bredere der posisjonen er dårlig, og voksende i tunneler mens posisjonen gjettes
(opptil 300 m). Cyan på nattkartet, rutas blå på dagkartet. Sett på X7, stillestående, i begge.
Installert på X7 og Pixel.

- [ ] Kjøring, dag og natt: ringen er lett å se, men skjuler ikke veien under pila.
- [ ] 3D: glorien ligger flatt på veien (en oval), midtstilt på pila.
- [ ] Dårlig GPS (dyp dal, by): glorien er synlig større.

## 2026-10-15 — Curveon sover når den er ute av syne; turen som planlegges, huskes

Ute av syne uten en tur som navigeres (en annen app, skjermen av), sover Curveon nå: ingen GPS,
ikke noe «Curveon»-varsel, ingen telefonlink eller OBD, ingen oppslag. Sjekket på X7: 0 CPU-tikk
på 30 s skjult (2791 på skjermen), ingen GPS-forespørsel, tjenesten borte; alt tilbake ved
retur. Turen som planlegges, lagres på disk og kommer tilbake selv om Android avslutter Curveon
(sjekket på X7 med tvungen stopp: samme stopp, start og veistil). Installert på X7 og Pixel.

- [ ] Pixel: forlat Curveon (ingen tur) i noen timer, så Innstillinger → Batteri → Curveon:
  nesten ingenting i bakgrunnen, og ikke noe «Curveon»-varsel mens den er skjult.
- [ ] Navigering med Google Maps (eller en annen app) foran: Curveon fortsetter å navigere,
  varselet vises; når turen slutter (framme eller stoppet) forsvinner varselet.
- [ ] Planlegg en tur, gå til e-post, kom tilbake: planen er der. Det samme etter lang tid borte.
- [ ] Tilbake på skjermen: GPS-posisjon, telefonlink (musikk, meldinger) og OBD kommer tilbake i løpet av sekunder.
- [ ] Laste ned et kart og forlate Curveon: nedlastingen blir ferdig.

## 2026-10-15 — Ruteforhåndsvisning: zoom-til-tur-knapp og grusomveismeny

- [ ] Planlegg en tur på Pixel (stående) mens du er på en nummerert vei: zoom-til-tur-knappen
  sitter under veinummerkortet, ikke bak det.
- [ ] Velg Grus: brikken ved siden av sier «Omvei inntil 5 km» / «Omvei inntil dobbel lengde»; menyen starter med
  «Hvor langt fra vanlig rute for å finne grus?» og lister «Inntil 1 km» … «Inntil dobbel lengde».

## 2026-10-15 — Innstillinger: valg som nedtrekksknapper til høyre

- [ ] Innstillinger → Skjerm og Navigasjon, på X7 og på Pixel (stående og liggende): hvert valg
  (språk, km/t/mph, skjerm, kart, veier, kartdetaljer, veidekke, utenfor ruten, fortsett…) er en
  knapp til høyre, på linje med bryterne, med tittel og hint til venstre.
- [ ] Hver knapp er så bred som sitt lengste valg: å velge et annet endrer ikke bredden, og ingen
  tekst kuttes (prøv Norsk og English).
- [ ] Trykk åpner lista, det valgte er cyan med hake; å velge ett lukker lista.

## 2026-10-15 — Curveon på GitHub i Innstillinger → Hjelp

- [ ] Innstillinger → Hjelp → Curveon på GitHub åpner GitHub-siden i nettleseren.

## 2026-10-14 — Oppdateringer fra GitHub, og Nyheter

- [ ] Første start av en ny versjon på X7 og Pixel (f.eks. 1.2.4): «Nytt i Curveon 1.2.4» åpnes
  én gang, og ikke igjen ved neste start.
- [ ] Innstillinger → Hjelp → Se etter oppdatering på en enhet med nyeste GitHub-versjon: «Dette
  er nyeste versjon».
- [ ] Innstillinger → Hjelp → Nyheter lister hver versjon, nyeste først.
- [ ] Når en nyere versjon ligger på GitHub: på Pixel (utgivelsesbygg) viser oppstartssjekken
  toasten «Curveon … er ute: Innstillinger → Hjelp» og en prikk på Innstillinger, Innstillinger
  åpnes på Hjelp, Last ned og installer virker, og Nyheter viser den nye versjonen etter omstart.

## 2026-10-14 — Svært svingete tar ikke lenger Hurtigruten

- [ ] Fra Bømlo, mål Florø, Supersvingete: ruten går oppover kysten (ca. 390 km, noen korte
  fjordferger), ikke til Bergen og Hurtigruten derfra.

## 2026-10-14 — Lås ruten mens du redigerer i kartet

- [ ] Juster ruten → Endre i kartet: hengelåsen ved Angre er lukket (rav) når du kommer inn,
  hintet sier «Trykk for nytt punkt; streken står fast». Trykk på den: den åpnes, hintet sier
  «Trykk for nytt punkt, dra for å bøye ruta».
- [ ] Låst: panorer med en finger som starter på rutelinja eller på et punkt: kartet flytter
  seg, ruten bøyer eller flytter seg ikke. Trykk legger fortsatt til et punkt; et trykk på et
  punkt åpner fortsatt menyen.
- [ ] To fingrer (zoom) som starter på linja zoomer fortsatt. Lås opp: å dra i linja virker som før.

## 2026-10-14 — Musikk på fullskjermkartet kan slås av

- [ ] Innstillinger → Skjerm → «Musikk på kartet» av: spilleren vises ikke lenger i hjørnet av
  fullskjermkartet (og kartknappene/svingkortet bruker plassen); på igjen: den er tilbake.

## 2026-10-14 — Veinummerskilt når du holder en finger på en vei

- [ ] Hold en finger på en nummerert vei (f.eks. en E-, riks- eller fylkesvei): veiinfoen i
  dialogen viser nummerskiltet (grønt/hvitt som i svingkortet) ved siden av fartsgrensen, og
  gatenavnet ved siden av i stedet for «Fv63 Storgata».
- [ ] På en forkjørsvei vises den gule forkjørsdiamanten også; på en vei uten nummer bare navnet som før.

## 2026-10-14 — «Punkt for punkt» fra et langt trykk på kartet

- [ ] Uten planlagt tur og uten å kjøre, hold en finger på en vei: dialogen har en tredje knapp,
  «Punkt for punkt» (ved siden av «Kjør hit» og «Lagre som favoritt»).
- [ ] Trykk på den → «Fra min posisjon»: kartet går i fullskjerm med punkt-for-punkt-linja, og turen herfra til det
  punktet blir funnet. Trykk lenger fram: hvert trykk kjører turen videre dit; Angre tar det siste tilbake.
- [ ] ✓ ender på den vanlige ruteforhåndsvisningen med alle punktene som stopp, der ruten kan
  justeres og startes som vanlig.

## 2026-10-14 — «Kjør igjen» på en runde fra hjemmet

«Kjør igjen» på dagens runde på 32 km (på Pixelen) tilbød bare de siste ~4 km: runden passerer
hjemmet igjen etter ~18 km, og turen koblet seg på sporet der. Nå kobler den seg på der sporet
først passerer deg. Installert på X7 og Pixel:

- [ ] Hjemme, Turer → dagens Svortland-runde → «Kjør igjen»: forhåndsvisningen skal være hele
  runden (~32 km), ikke noen få km. Prøv «Kjør motsatt vei» også.

## 2026-10-15 — Rytterpila: +1,5 s foran GPS-en som standard

Test på veien: +1,5 s satte pila der motorsykkelen er (+1,75 var nær; +0,25 lot den henge etter i kryss). Det er
nå standard, og innstillingen blir stående (Innstillinger → Navigasjon → «Markør», − / +, ikke lenger «test»). En
verdi som allerede er valgt for hånd beholdes; bare enheter som fortsatt står på den gamle standarden flyttes.

- [ ] Ved 40 og ved 80 km/t, forbi et kryss: pila passerer det sammen med deg.

## 2026-10-14 — Veinummerskilt og forkjørsveier

Installert på X7 og prøvd på demoturen med en datafil bare for Vestland
(`speedlimits/zz-test-vestland.sqlite`, slettet fra X7 2026-10-16): «Fv4998» og forkjørsskiltet i
et eget kort (øverst på midten liggende, under farten stående); i svingkortet «270 m [Fv5012]».
Innstillinger → Skjerm → Veiinformasjon skjuler og viser dem.

- [ ] Ekte tur: nummeret i veikortet (øverst til venstre) endres når du svinger inn på en annen vei, og
  forkjørsskiltet vises på forkjørsvei og forsvinner utenfor den (innen ~2 s).
- [ ] En E-vei og en riksvei viser grønne skilt (E39, Rv…), fylkesvei hvitt, kommunal vei grått.
- [ ] I utlandet (eller uten den nye datafilen): nummeret kommer fra kartet i stedet, i
  landets farger; ikke noe forkjørsskilt.

## 2026-10-14 — Batteritest: Google Maps mot Curveon (Pixel)

Installert på Pixel (Companion og Curveon). Prøvd på X7 (late som frakoblet): Google Maps'
navigasjon og Curveons gjenkjennes hver for seg, start og slutt.

- [ ] Pixel frakoblet, samme skjermlysstyrke begge veier (autolysstyrke av): Companion → Batteritest → Start. Kjør A→B
  med navigering i Google Maps, B→A med Curveon, så Stopp. Companion viser mAh og mAh/t per app.
- [ ] Etterpå, for en nærmere titt: `adb pull /sdcard/Android/data/com.curveon.companion/files/batterytest/`
- [ ] Lukk den andre appen mens en navigerer (Curveon via «Lukk Curveon» i varselet).

## 2026-10-14 — Mindre batteribruk

Installert på X7 og målt der (CPU per tråd, samme sted på samme demotur, gammel mot ny): i ro gikk
appen fra ~23 % av en kjerne til ~5 %; under kjøring hovedtråden fra ~67 % til ~47 % (kartets egen
tegning, ~75 %, er uendret). Gjenstår å se på en ekte tur:

- [ ] Kjør med kartet som følger: det skal gli nøyaktig som før, også når du kjører fra et stopp
  (rytter-løkka sover nå mens motorsykkelen står stille og ingenting endres).
- [ ] Veinavnet etter neste sving, nettkartet der intet nedlastet kart når, og grusveiene vises
  fortsatt under kjøring (de letes nå etter én gang i sekundet i stedet for ved hver ramme).
- [ ] Batteri over en times kjøring, sammenlignet med før: `adb shell dumpsys batterystats
  --charged com.curveon.app`.

## 2026-10-14 — Nåler A/B, lagringsdialog, Planlagte ser ut som Turer

Installert på X7 og Pixel. Rulling og Planlagte-siden ble sjekket på X7 i Pixel-størrelse liggende; kartnålene og lagringsdialogen ble ikke sett.

- [ ] Planlegg en tur: start = grønn A (bare når planlagt fra et annet sted), stopp = oransje 1, 2…, mål = rød B. En planlagt tur kjørt andre veien: A ved den nye starten, B ved slutten.
- [ ] Bokmerk en rute: «Lagre ruten» viser Hvor skal du?, Planlagte turer og GPX-spor, alle uten hake; velg en gruppe for Planlagte turer og GPX-spor; lagre til hvilken som helst av dem.
- [ ] Turer: åpne en tur: én «Lagre…»-knapp (samme dialog).
- [ ] Planlagte: et miniatyrbilde av linja og km; trykk for tid, stigning og svinger; Kjør / Kjør motsatt vei / Lagre i GPX-spor.
- [ ] Liggende på Pixel: GPX, Turer og Planlagte ruller som én liste (bryterne og brikkene ruller bort).

## 2026-10-13 — Én ramme for GPX / Turer / Planlagte og Telefon; planlagte turer i grupper

Installert på X7 (liggende sjekket der, og stående via `wm size 1080x2400`); Pixelen var ikke innen rekkevidde.

- [ ] Pixel stående: åpne ruteikonet (GPX og turer): én ramme med fire faner (GPX, Turer, Planlagte, Delte ruter); sveip sidelengs mellom dem (fanen lyser). Det samme i Innstillinger og Telefon (kan også sveipes nå).
- [ ] Planlagte: de lagrede rutene dine ligger her. Trykk på en tur, ⋮ → Flytt til gruppe → ny gruppe («Turer til helga»); gruppen åpnes og lukkes; ⋮ på en gruppe gir nytt navn, løser opp eller sletter den.
- [ ] Den røde B-målnålen blir stående mens et eventyr tegnes og regnes ut.

## 2026-10-13 — Eventyrruting (ny)

Installert på Pixel og X7.

- [ ] Søk et mål, trykk så **Eventyr** i ruteforhåndsvisningen (ved siden av «Fra min posisjon»). Tiden starter
  på «Ingen grense»; sett den og underlaget i linja, tegn området du vil kjøre i med én finger. Forvent en
  «Eventyr til …»-rute inni området, ikke lengre enn tiden du ba om (vent ~10–40 s på lange).
- [ ] I eventyrforhåndsvisningen: tidsbrikke (endrer tiden), **Tegn på nytt**, stokk (en annen rute),
  startbrikke (planlegg fra et annet sted), underlagsbrikker, KJØR. Sjekk `adb logcat -s CurveonPlan`
  for kandidatene den prøvde.
- [ ] For lite tid (mindre enn den raskeste veien): forvent meldingen med minuttene for den raskeste veien.
- [ ] Rundtur (tegn område fra «Hvor skal du?») virker som før.

## 2026-10-13 — Valg av motorvei med ikoner (ruteforhåndsvisning)

Installert bare på Pixel.

- [ ] Trykk på motorveibrikken: lista viser det blå motorveiskiltet, det samme overstrøket, og
  det grønne E-skiltet overstrøket.
- [ ] «Ingen motorvei eller E-vei»: brikken viser det grønne E-skiltet overstrøket; «Ingen
  motorvei» det blå motorveiskiltet overstrøket; «Motorvei OK» det vanlige blå skiltet.

## 2026-10-13 — Turlogg: bryter for å lagre turer og gruppering etter tid

Installert bare på Pixel.

- [ ] Turer-dialogen: «Lagre nye turer» av, kjør en kort strekning: den skal ikke vises i lista, og
  ingenting havner i GPX-gruppen. GPX-autolagringsraden er grånet.
- [ ] Slå den av midt i en tur: den turen blir likevel ferdig og lagret.
- [ ] Den nyeste gruppen («Denne uka» når det er turer denne uka) er åpen, de eldre («Forrige uke», månedene)
  er lukket; overskriftene viser turer og km.
- [ ] Velg alle + slett tar også turene i lukkede grupper.

## 2026-10-13 — Ringende samtale stor, besvart samtale et lite ikon du kan flytte

Trenger den nye Curveon Companion på Pixelen også (den sier om en samtale er besvart; med en gammel
ser en besvart samtale ut som om den fortsatt ringer). Installert bare på X7; aldri prøvd med en
ekte samtale.

- [ ] Ring Pixelen: et stort kort med den som ringer sitt bilde, navn, rød Avvis og grønn Svar,
  over alt (fullt kart, dashbord, ruteforhåndsvisning, en åpen skjerm, stående).
- [ ] Svar på fullt kart: kortet blir et grønt telefonikon. Dra det hvor som helst; det blir stående
  til neste samtale, også med Innstillinger, GPX eller Telefon åpen. Trykk på det: navn, Legg på og
  Høyttaler i noen sekunder. Musikkortet blir stående. (På dashbordet er en besvart samtale et kort,
  se 2026-10-18.)
- [ ] Hvis Svar-knappen fortsatt er der etter å ha svart, kjører Pixelen den gamle companionen:
  installer `./gradlew :companion:installDebug` på den.

## 2026-10-13 — Samtaler som et smalt kort og et flytende ikon

Trenger den nye Curveon Companion på Pixelen (den sier nå om en samtale er besvart) og den nye
Curveon på X7. Begge ble installert; X7 og Pixel var aldri koblet til hverandre under testingen, så
ingenting av dette er sett med en ekte samtale.

- [ ] Samtale startet fra X7s Telefon-skjerm: samme ikon når den kobler til.
- [ ] Android under 12 på telefonen (hvis noen): en besvart samtale kan fortsatt vises som ringende; si fra.

## 2026-10-12 — Hjørner, paneler over kartet, sveip, nye menyikoner

- [ ] X7 under kjøring (ikke demoturen): ankomstboksen sitter øverst til venstre, farten øverst til
  høyre, neste sving nede til venstre, musikken nede til høyre (fullt kart), og kartets knapper holder
  seg unna alle fire.
- [ ] Pixel liggende og stående (Curveon på telefonen): det samme, uten noe over +/−- og
  3D-knappene; kartets (i)-lisensknapp sitter halvveis opp på venstre kant og åpner teksten sin.
- [ ] Skinnen: knappen til den åpne skjermen er blå; å trykke på den igjen lukker skjermen, å
  trykke på en annen bytter den inn; Tilbake lukker den; tastaturet i «Hvor skal du?» skjuler ikke
  feltet.
- [ ] Stående (X7 eller Pixel): i Innstillinger og GPX og turer ruller glidere og vannrette
  lister inni sidene uten å bytte side.
- [ ] Velkomstomvisning (Innstillinger → Hjelp): Hvor skal du?, Planlegg en tur og Rundtur lyser
  opp motorsykkelknappen og sier hva du skal trykke på først.

## 2026-10-11 — Nye appikoner (motorsykkel på svingen)

- [ ] Pixel: Curveon (motorsykkelen sett forfra) og Curveon Companion (den cyan telefonen med samme
  motiv på skjermen) viser ikonene sine i appskuffen; den runde masken beholder begge speilene. Med
  Temaikoner på (bakgrunnsinnstillinger) viser begge en ren énfarget versjon.
- [ ] X7: sjekk ikonet på startskjermen.

## 2026-10-11 — Dagens veifarger på nattkartet

- [ ] Nattkart: veiene har dagkartets farger (hvite gater, gule hovedveier og motorveier) med
  samme omriss. For lyst å kjøre etter om natten? Si det da; de gamle nattfargene ligger i git
  (`app/src/main/assets/map/styles/dark.json` før denne commiten).

## 2026-10-11 — Fartsgrenser uten kart

- [ ] Pixel (ingen kart installert), på nett og med GPS-posisjon: i løpet av et par minutter viser
  `adb -s <pixel> shell ls /sdcard/Android/data/com.curveon.app/files/speedlimits/` `norway.sqlite`
  (landet den er i); fartsgrenseskiltet vises så på veiene, f.eks. på demoturen eller med «Hold en
  finger på en vei».

## 2026-10-11 — Ingen U-sving på E39 ved Heiane (Stord)

- [ ] Planlegg en tur til McDonald's Stord fra sørvest (si fra Føyno): ruten skal ta avkjøringen til
  høyre fra E39, rundkjøringen og brua, uten U-sving på E39. Verdt å se på andre motorveiavkjøringer
  også: de skal se ut som før.

## 2026-10-11 — Tekster og tegninger i velkomstomvisningen

- [ ] Innstillinger → Hjelp → Start omvisningen: telefontrinnet sier at et nettbrett som ikke
  kan ringe trenger Curveon Companion på telefonen; Innstillinger sier frakoblede kart,
  telefonlink og mer; karttrinnet nevner bare å holde på et sted; etter musikk og meldinger
  kommer en motordata-tegning; «Endre bare en del» ender med en stiplet linje som dras ut,
  som ved redigering i kartet.

## 2026-10-11 — Fullt kart eller dashbord per retning

- [ ] På telefonen: velg fullt kart stående og dashbord liggende. Roter fram og tilbake: hver
  retning går tilbake til det den sist hadde. Start Curveon på nytt: det samme igjen.

## 2026-10-11 — Kartvisning huskes per modus

- [ ] Nytt (eller etter å ha tømt innstillingene): kartet uten tur er nord opp; start en tur, så
  går det til 3D. Stopp turen: tilbake til nord opp.
- [ ] Trykk på kompassknappen under navigering (si til retning opp), stopp og start en annen tur:
  retning opp igjen. Valget gjort mens du blar i kartet huskes separat.

## 2026-10-11 — Demomodus

- [ ] Innstillinger → Navigasjon → Demotur: slå den på, planlegg en tur og trykk Kjør: turen
  starter av seg selv som en simulering, fra turens eget startpunkt hvis du har satt ett (ikke fra
  der nettbrettet er). Slå den av: tilbake til ekte GPS. Slått på under en tur: den turen simuleres
  herfra.

## 2026-10-11 — Steder på kartet

- [ ] Innstillinger → Skjerm → På kartet: slå på Bensin, Fotobokser, Utsiktspunkt, …; zoom inn på en
  by du har kart over: stedene vises med ikoner og navn (bensin grønn, kameraer røde, ferjer grå), et
  trykk på ett åpner kortet. Under kjøring skal de fortsette å dukke opp foran deg uten at kartet
  hakker.
- [ ] Slå dem av igjen: kartet er rent som før.

## 2026-10-11 — Velkomstomvisning

- [ ] Start Curveon på X7: velkomstomvisningen åpnes av seg selv (én gang). Gå gjennom den: den
  lyse ringen skal sitte pent rundt hver skinneknapp, kartknappene, farten og kortene, og hvert
  kort skal passe på skjermen (også det lange «Planlegg en tur»).
- [ ] Første start (tøm appens data for å se den igjen): omvisningen åpnes med Language · Språk, og fortsetter på språket du
  trykker på. Åtte små animasjoner (musikk og meldinger, motordata, nettbrett–telefon–intercom, bare en telefon, planlegg,
  rediger i kartet, marker én del med tre-prikkers-knappen, tegn en rundtur) går i løkke uten tekst.
- [ ] Innstillinger → Hjelp → Start omvisningen: den starter igjen; symbollista under leses godt.
- [ ] Pixel holdt stående: språk og velkomst kommer stående; «Vis meg rundt» snur skjermen til liggende (den sier
  at telefonen kan snus tilbake etterpå), og den går tilbake til stående når omvisningen lukkes.

## 2026-10-11 — Offisielle fartsgrenser; veiinfo når du holder en finger på en vei

**Offisielle fartsgrenser.** Der et land publiserer dem fritt, viser fartsskiltet nå den
offisielle grensen for veien du er på, og faller tilbake på OpenStreetMap andre steder: Norge
(Statens vegvesen NVDB, offentlige veier), Finland (Digiroad), Nederland (NDW/RWS WKD), Flandern
(AWV). Bygges hver måned av repoet curveon-data (`speedlimits`-utgivelsen, ~7 MB for Norge);
nettbrettet henter filen for hvert land det har kart over, sammen med kartet, eller av seg selv
~20 s etter oppstart når det er på nett. Krediteringer under Innstillinger → Kart og data →
Fartsgrenser. Rundt Bømlo/Stord/Fitjar mangler OSM grense på 47 % av kommunal vei og avviker på
~5 % av hovedveiene; den offisielle filen ga riktig grense i alle 1 497 testpunkter.

**Veiinfo.** Å holde en finger på en vei viser, i kortet som åpnes, fartsgrenseskiltet,
navn/nummer, type (hovedvei, landevei, traktorvei…) og dekke, og om grensen er offisiell eller
fra OpenStreetMap. Bare informasjon.

- [ ] X7/Pixel på nett: etter en oppstart sier Innstillinger → Kart og data → Fartsgrenser «Norge: Statens vegvesen, NVDB …».
- [ ] Kjøring på en kommunal vei som ikke viste skilt før: viser den ett nå, og er det riktig?
- [ ] Hold en finger på noen veier (hovedvei, grusvei, traktorvei): er infoen riktig?

## 2026-10-11 — Grusrekkevidde når du tegner en runde

Med Grus eller Offroad i tegnelinja er brikken «Grus innen N km / hvor som helst» der også.
Rundens form finnes med grusens fulle trekk; rekkevidden gjelder når den kjøres som en tur
gjennom punktene sine (hver strekning mellom to punkter holder seg så nær den vanlige veien), og
kan endres på arket etterpå. Sjekket i emulatoren.

- [ ] Tegn en grusrunde inne i landet med «Omvei inntil 3 km»: mindre grus enn «Omvei inntil dobbel lengde», men noe?

## 2026-10-11 — Ingen motorvei eller E-vei

Motorveibrikken er en liste: Motorvei OK / Ingen motorvei / **Ingen motorvei eller E-vei**.
Rutingdataene har veiklasser, ikke nummer, så «E-veier» betyr stamveier: i Norge E-veiene og de
største riksveiene (Rv 7, Rv 13 der de er merket som stamvei); kjøres likevel der det ikke finnes
noen annen vei. Haugesund–Bergen raskest: 61 km på stamvei → 6 km (139 → 165 km). Sjekket i
emulatoren.

- [ ] En tur du vanligvis ville fått E39/E134 på: holder den seg unna dem, og er omveien fornuftig?

## 2026-10-11 — Turer beholder tidene sine i GPX (for å passe sammen med video)

En tur lagret i GPX-samlingen beholder nå når hvert punkt ble kjørt, og eksporterte GPX-filer har `<time>`
(UTC, tideler av et sekund) på hvert sporpunkt og i metadataene, så videoverktøy kan legge sporet over en film.
Turer registreres nå med en tidels sekund (eldre turer har hele sekunder; å lagre en av dem i GPX på nytt gir
den også tider). Tider i importerte GPX-filer beholdes og skrives ut igjen; et spor eksportert baklengs får
ingen. Enhetstestet; installert på X7.

- [ ] Lagre en tur i GPX, eksporter den, åpne i et video-/GPX-verktøy (f.eks. GoPro Quik, Telemetry Overlay): tidene stemmer med turen.

## 2026-10-10 — Høyde (moh.) under farten, valgfritt

Innstillinger → Skjerm → **Høyde** viser høyden over havet under farten (av som standard). Den
kommer fra GPS-mottakerens egen NMEA (GGA, høyde over havet); Androids høyde er over ellipsoiden,
~40 m høyere i Norge, og brukes bare (eller Android 14s høyde over havet) når mottakeren ikke sender
NMEA. Registrerte turer får nå også høyder over havet. Sjekket i emulatoren; installert på X7
(Pixelen var ikke innen rekkevidde).

- [ ] Sammenlign med en kjent høyde (et toppskilt, eller havet: ~0–5 m ved kaia) på X7 og Pixel.

## 2026-10-10 — Grus innen rekkevidde av ruten; rutelinja farget etter underlag

Med Grus eller Offroad valgt sier en brikke ved siden av hvor langt fra den vanlige ruten grus kan ta turen:
innen 1/3/5/10/20 km, eller hvor som helst. Den vanlige ruten planlegges først, så grusruten med stadig
mindre trekk mot grus til den holder seg så nær; ellers den vanlige ruten. Selve grusen trekker hardere nå
(før endret Grus knapt en rute): Kongsberg–Notodden svingete får 17 km grus (33 → 43 km, 6 km utenfor);
innen 3 km holder den seg på den vanlige veien. På vestkysten er få grusveier merket i OpenStreetMap, så der
endres det mindre. Den forhåndsviste ruten tegnes oransje der den er grus og lilla på traktorveier. Sjekket
i emulatoren; installert på X7.

- [ ] Grus nær hjemmet: finner den noe? Oppfører «Omvei inntil N km» seg?
- [ ] Er de oransje/lilla strekningene lesbare på X7 i sollys?

## 2026-10-10 — Maks lengde og underlag når du tegner en runde; tegn på nytt

Tegnelinja har **Hele området / Maks N km** (50–300) og underlagsbrikken; med en maks planlegges
runden rundt området krympet mot starten, så den blir omtrent så lang (tester: maks 50 → 48 km, maks
80 → 55 km fra Bømlo i et stort område). Rundens ark har den samme maks-brikken (planlegger runden
på nytt, med veistilen valgt der) og **Tegn på nytt** (samme start, maks og underlag). Austevoll i
et tegnet område: den kan nås (ferje Sandvikvåg–Hufthamar) men bare den veien inn og ut, så en runde
over den kjører ferja og Stord-veiene to ganger og taper mot runder som ikke gjør det. Sjekket i
emulatoren; installert på X7 (Pixelen var ikke innen rekkevidde).

- [ ] Tegn med Maks 80 km på X7: omtrent 80 km eller mindre?
- [ ] Endre maks på arket, og Tegn på nytt.

## 2026-10-10 — Lukk Curveon fra varselet

Varselet «GPS og navigasjon kjører» har **Lukk Curveon**: det lukker skjermen, GPS,
telefonlinken og OBD og avslutter appen. (Advarselen «kjører fortsatt» etter 10 minutter er
borte: Curveon sover nå ute av syne i stedet, se 2026-10-15.)

- [ ] «Lukk Curveon» fra varselet: Curveon er borte; å åpne den igjen starter normalt.

## 2026-10-10 — Rundtur tegnes med en gang; runder kjører mindre vei to ganger

«Rundtur» i Hvor skal du? går nå rett til tegning på kartet (retnings-/avstandsdialogen er borte):
linja viser «Omtrent N km rundt» mens du tegner, og «Fra …» når en start et annet sted er satt.
Runden planlegges svingete med de siste rutevalgene; arket tilbyr så alle stiler, ferjer og
motorveier som for enhver tur. Runder holder seg nå unna vei de allerede har kjørt: der den beste
runden fortsatt kjører en strekning to ganger, rutes de etappene på nytt med den veien gjort dyr
(tillatt når det er eneste vei, f.eks. av en øy). På testrunder rundt Bremnes/Haugesund gikk
andelen kjørt to ganger fra 12 % til 5 % i snitt; tegnede områder på Bømlo/Stord fra ~30 % til 7–17
%. Sjekket i emulatoren.

- [ ] Rundtur fra hjemmet: kjører den fortsatt strekninger to ganger? Hvor (skjermbilde)?
- [ ] Hvor lang tid en tegnet runde tar på X7 nå (`adb logcat -s Curveon CurveonPlan` → «Routed Roundtrip in … s»; 1–15 s på PC-en).

## 2026-10-10 — Rutevalgene sier hva de gjør

Valgbrikkene under en rute (og i Rundtur) var vanskelige å lese: et gjennomstreket
«~~Motorveier~~» sa ikke tydelig om motorveier var på eller av. De sier det nå med ord:
«Motorvei OK» (grå) eller «Ingen motorvei» (rav, ikonet overstrøket i rødt); det samme for
ferjer; underlagsbrikken sier «Kun asfalt» (så Blandet, Grus, Offroad). Sjekket i emulatoren.

- [ ] Spør Øystein om brikkene er tydelige nå.

## 2026-10-10 — Favoritter: hold på et sted på kartet, behold det som Hjem eller med navn

Å holde en finger på kartet (ingen tur) spør nå: **Kjør hit** (turforhåndsvisningen som før) eller
**Lagre som favoritt** – Hjem med ett trykk (et nytt Hjem erstatter det gamle), eller et navn
(stedets, som kan endres). Under kjøring eller planlegging tilbyr det samme trykket den også ved
siden av stopp / mål / start. Favoritter kommer først i Hvor skal du? (Hjem øverst) for mål, et
stopp eller starten; hold på en for å slette den. Sjekket i emulatoren; installert på X7 og Pixel.

- [ ] Lagre Hjem på X7, så planlegger Hvor skal du? → Hjem veien hjem.
- [ ] En navngitt favoritt som stopp under kjøring (trykk på ankomstboksen → Legg til stopp → favoritten).
- [ ] Hold på en favoritt i Hvor skal du? → slett.

## 2026-10-10 — Musikkpillen dekker ikke lenger turlinja; ingen krasj når du snur telefonen

På fullskjermkartet med musikk spillende lå musikkpillen oppå turlinja (ankomst, km og tid igjen) der begge
ikke får plass side om side (telefonen, stående): den flytter seg nå opp over den. Å snu telefonen under
navigering krasjet appen (kartet leste veinavn før det hadde tegnet etter snuinga); den venter nå. Sjekket i
emulatoren i Pixel-størrelse; installert på X7 og Pixel.

- [ ] Snu telefonen under navigering: ingen krasj, veinavnet etter neste sving vises fortsatt.
- [ ] Den blå rutelinja er tykkere under kjøring (omtrent dobbelt så bred innzoomet, som før utzoomet): lett å følge, uten å skjule veinavnene?

## 2026-10-10 — Rundtur i «Hvor skal du?»: tegn området, start et annet sted, rediger runden

Rundtur-knappen i skinnen er borte: Rundtur er nå en rad i «Hvor skal du?», vist bare når ingen
tur er planlagt eller kjøres. Dialogen har **Tegn område**: kartet blir flatt og zoomer ut, én
finger tegner området du vil kjøre i (to fingrer flytter fortsatt kartet), og når du slipper
regnes en runde inni det ut (Stokk gir en annen). «Fra min posisjon» i «Hvor skal du?» (eller
startbrikken på rundens ark) starter runden et annet sted. Blyantikonet på rundens ark gjør den om
til en vanlig tur med via-punkter omtrent hver 8. km, til å dra i og endre som alle andre.
Oppdatering: en funnet runde *er* nå en slik tur med en gang – samme ark som «Hvor skal du?»
(start,

+ Stopp, via-punktene, Raskest/Svingete/Supersvingete, rediger i kartet), med tittelen «Rundtur
  fra …», og Stokk for en annen runde; å endre starten planlegger en ny runde derfra. Sjekket i
  emulatoren (Haugesund, Leirvik); installert på X7 og Pixel.
- [ ] Tegn et område med en finger på X7: streken følger fingeren, to fingrer panorerer/zoomer.
- [ ] Tegn rundt Bømlo/Stord fra hjemmet: bruker runden området og holder den seg unna blindveier?
- [ ] Arket til en runde ser ut som en planlagt tur; dra et punkt på kartet, resten av runden blir stående.
- [ ] Stokk på en runde (også etter å ha endret veistil) gir en annen runde.

## 2026-10-10 — Kartet blir stående under planlegging; en knapp viser hele turen

Å planlegge en tur zoomer ikke lenger kartet ut av seg selv hver gang ruten endres (valg av
steder, stopp, omveier, rutevalg): det blir der du la det. Den nye knappen øverst til høyre (fire
piler, under farten) passer hele turen i bildet, som zoom til alt. Bare når en tur først dukker
opp og ikke allerede er i bildet, tilpasses den én gang; din egen posisjon tas bare med når turen
starter nær deg. Sjekket i emulatoren; installert på X7.

- [ ] Planlegg en tur langt unna der du er, velg steder på kartet: kartet hopper ikke.
- [ ] Firepilknappen viser hele turen over arket.
- [ ] X-en på en planlagt tur spør først: Behold / Lagre / Forkast.

## 2026-10-10 — Rytteren glir på kartet, raskere av linja etter en feil sving, nytt 3D, turspor

Pila og kartet beveger seg nå hver ramme (ført fram mellom GPS-posisjonene som kommer én gang i
sekundet, pluss ~0,25 s for mottakerens forsinkelse) i stedet for å glatte seg til hver posisjon
et sekund for sent. Å ta en annen vei slipper rutelinja etter ~7 m når du kjører bort fra den
(før: først når navigatoren kalte det utenfor ruten, 40 m+ for 3 posisjoner). 3D: vipp 50° (var
60°), rytteren ~3/4 nede på kartet, zoom 17,2 ved 20 km/t → 15,6 ved 90, litt inn når en sving er
under 300 m unna. Installert på X7 (Pixelen var frakoblet: `ANDROID_SERIAL=<pixel> ./gradlew
:app:installDebug`).

Hver appstart skriver et spor til `Android/data/com.curveon.app/files/navtrace/trace-*.csv`
(rå GPS-posisjoner, rutematching, hvor pila ble tegnet, ruter, OBD-verdier; beholdes i 14
dager).

- [ ] Kjør en rute, med et kryss der du med vilje tar feil vei, i retning opp og i 3D. Glir
  pila, holder den følge med motorsykkelen i kryss, forlater den linja med en gang?
- [ ] Hent så sporene og fortell omtrent når/hvor (tid på dagen, hvilket kryss): `adb -s <X7> pull
  /sdcard/Android/data/com.curveon.app/files/navtrace/ ~/develop/Curveon-data/navtrace/` (Pixel: samme sti
  med eget serienummer).

## 2026-10-10 — Fart på kartet, motorpanel (OBD2)

Fart, grense, klokke (og fotoboks-/GPS-linjer) ligger alltid på kartet nå, øverst til høyre.
Innstillinger → Motor: motorpanel av/på, OBD2-adapteren (parede Bluetooth-enheter, eller Demo), og
hvilke verdier (bensin, turtall, kjølevæske, batteri). Nettbrett: panelet over musikken; telefon
stående: musikk nede til venstre, panel nede til høyre, kartet helt opp. Sjekket i emulatoren med
Demo. Installert på begge enhetene.

- [x] Adapteren kobler til på Ténéré 700 (2020): turtall, kjølevæske og batteri kommer gjennom.

## 2026-10-10 — Stopp nedi en sidegate kjøres til, og én kartredigerer

To endringer, installert på X7 (Pixelen var frakoblet: `./gradlew :app:installDebug` med den som
`ANDROID_SERIAL` når Trådløs feilsøking er på igjen).

- Stopp: et stopp like utenfor veien (f.eks. en sidegate i Haugesund) kjøres nå til; før kjørte
  ruten forbi det ~200 m unna (BRouter kuttet turen inn og ut til det). Turer med stopp rutes nå
  etappe for etappe. (Det første forsøket, 7e75557, gjorde vanlige A→B-turer om til dit og
  tilbake; rettet.) `:routing:test` StopsVisitedTest dekker begge.

- Rediger-i-kartet-knappen (og et langt trykk på ruten) åpner den samme redigereren som «+ Stopp»
  → Velg i kartet: trykk for å legge til et punkt, dra linja for å bøye ruten, trykk/hold et punkt
  for menyen (fjern, flytt, bytt sted, nytt stopp før), knappen med stiplet linje markerer en del
  av ruten.

- [ ] X7: Bømlo → Haugesund uten stopp er én vei (~67 km), ikke dit og tilbake.

- [ ] X7: legg til et stopp i en sidegate nær ruten: den blå linja går inn i gata til det.

- [ ] Ruteforhåndsvisning → Juster ruten → Endre i kartet → åpne hengelåsen → dra linja → en
  omvei; knappen med stiplet linje (Marker delen som skal endres) → to nåler.

- [ ] Trykk på et stopp på kartet → meny → Bytt sted åpner søket og kommer tilbake til turen.

## 2026-10-10 — «+ Stopp» på kartet spør: på veien, eller nytt mål?

Når du velger stopp på kartet fra «+ Stopp» på en tur som allerede har et mål, spør det første
trykket «Stopp på veien, eller nytt mål?». På veien: det punktet og de neste blir nummererte
stopp der de passer langs ruten; Nytt mål: turen kjører videre til dem (det gamle målet blir et
stopp). Spørres én gang per runde med valg. Sjekket i emulatoren. Installert på begge enhetene.

- [ ] X7: ruteforhåndsvisning → Juster ruten → Legg til stopp → Velg i kartet → trykk et punkt ved
  siden av ruten: linja spør «Stopp på veien, eller nytt mål?» med Hjelpepunkt / På veien / Nytt mål →
  På veien.

## 2026-10-10 — Hold på et punkt på kartet for å fjerne det

Når du redigerer en tur på kartet eller bygger den punkt for punkt, åpner det å holde en finger
i ro på et punkt (hvit prikk, nummerert stopp, start eller mål) menyen med «Fjern punktet» (et
stopp kan også flyttes tidligere/senere). Å fjerne målet gjør punktet før det til mål. Å dra et
punkt virker som før. Sjekket i emulatoren. Installert på begge enhetene.

- [ ] Pixel: et langt trykk på en hvit prikk gir en summing og menyen; Fjern ruter om uten det.
- [ ] Med hengelåsen åpen flytter et raskt drag av et punkt det fortsatt (ingen meny som dukker opp midt i draget).

## 2026-10-10 — Lagre en rute i Hvor skal du?, GPX, eller begge

Rutens lagringsdialog har to hake-bokser: Hvor skal du? (lagrede ruter, som før) og GPX-spor, med
GPX-gruppen den skal i (en eksisterende, ingen gruppe, eller en ny, som når du lagrer en tur).
Valget huskes til neste gang. GPX-kopien har rutas linje og stoppene som veipunkter. Sjekket i
emulatoren (lagret i begge, sporet dukker opp i GPX-gruppen sin). Installert på begge.

- [ ] Pixel stående: dialogen passer, gruppenavnet skyver ikke haken ut av raden.
- [ ] Eksporter det lagrede GPX-sporet fra GPX-fanen og åpne det et annet sted: linja og stoppene er der.

## 2026-10-10 — Bygg en tur punkt for punkt på kartet

«Velg i kartet» (for et mål eller et stopp) har en ny knapp (en sikksakklinje) ved siden av
søkeikonet. Slått på kjører hvert trykk på kartet turen videre dit med en gang (det gamle målet
blir et punkt underveis); linja viser turens tid og lengde, Angre tar det siste punktet tilbake
og haken avslutter til ruteforhåndsvisningen. Den blir på til den slås av. Punkter kan dras mens
du bygger. Sjekket i emulatoren (X7 liggende, Pixel-størrelse stående). Installert på begge.

- [ ] Pixel: planlegg A → B → C → D ved å trykke; kartet blir der du trykker (ingen utzooming mellom punktene).
- [ ] Et trykk rett på rutelinja legger også til neste punkt (f.eks. når du kjører tilbake samme vei).
- [ ] Punkt for punkt er nå alltid måten et mål/stopp velges på kartet (ingen bryter). Å velge
  starten, et stopp under kjøring, eller «Bytt sted»/«Nytt stopp før» velger fortsatt ett sted.

## 2026-10-10 — Marker delen av ruten som skal endres

Når du redigerer en tur på kartet, markerer den nye knappen (en linje med prikker) til venstre for
Angre en del av ruten: dra langs linja, så festes de to endene som små hvite punkter, og å dra
linja mellom dem endrer bare den delen. Ferdig er en rund hake på en telefon. Sjekket i emulatoren
(X7 liggende, Pixel-størrelse stående). Installert på begge enhetene.

- [ ] Pixel stående: redigering viser haken (Ferdig) til høyre i linja.
- [ ] Lang tur (f.eks. Bømlo → Oslo): marker en del ved Haukeli, åpne hengelåsen, dra linja ut til en sidevei;
  ruten utenfor den markerte delen blir som den var, og Angre tar markeringen bort i ett grep.
- [ ] Markering med en finger som driver av linja glir fortsatt den oransje delen langs ruten.

## 2026-10-10 — Ruteforhåndsvisningen ruller i stedet for å miste KJØR

Når ruteforhåndsvisningen går tom for høyde (Pixel liggende med en grusadvarsel), ruller delen
mellom tittelen og valgene; valgene og KJØR blir alltid stående nederst. Sjekket i emulatoren i
Pixel-størrelse, liggende. Installert på begge enhetene.

- [ ] Pixel liggende: turen med grusadvarselen viser brikkene (Kun asfalt, Ferjer OK, Motorvei
  OK) og KJØR; sveip kortene opp for å lese hele advarselen.

## 2026-10-10 — Klokka sitter ved farten

Klokka flyttet fra skinnen til høyre ende av fartskortet (og av fartsboksen på
fullskjermkartet), så skinneknappene får plassen. Sjekket i emulatoren på X7 (begge oppsett)
og telefon stående. Installert på begge enhetene.

- [ ] Pixel stående: de seks knappene langs bunnen har plass nå.
- [ ] Kjøring med fartsgrenseskilt synlig: fart, skilt og klokke passer alle på X7 og Pixel.

## 2026-10-10 — KJØR mister ordet når det er trangt

KJØR-knappen i ruteforhåndsvisningen viser pil og «GO»/«KJØR» når den har plass, og bare en større pil
når det er trangt (den rant ut av knappen på Pixel liggende). Installert på begge enhetene.

- [ ] Pixel liggende (dashbord): planlegg en tur; knappen viser bare pila, inni pillen sin.
- [ ] X7 og Pixel stående: knappen sier fortsatt KJØR/GO.

## 2026-10-10 — Ruting på nett uten å laste ned landet

Å planlegge en tur et sted uten rutingdata (f.eks. Curveon på Pixelen, ingen land lastet ned)
henter nå bare rutingflisene turen trenger fra brouter.de når du er på nett (5x5 grader, omtrent
5–65 MB hver), vist som «Henter rutedata (1 av 2) …» i forhåndsvisningen. De blir liggende til
neste gang, så det området virker frakoblet også. Ikke noe kart eller søk lastes ned. Prøvd i
telefonemulatoren (Oslo → Hamar: to fliser, ~93 MB, så de tre rutene på ~8 s).

- [ ] Pixel, på nett, ingen land: planlegg en tur; den henter rutedata, viser så rutene og KJØR virker.

## 2026-10-10 — Lås skjermen liggende eller stående

Innstillinger → Skjerm → Skjermretning: Snur seg / Liggende / Stående. Låst blir skjermen akkurat
slik den ble holdt da du valgte (ikke engang opp-ned), uansett hva Androids autorotering sier.
Prøvd i emulatoren.

- [ ] X7: Innstillinger → Skjerm → Skjermretning → Liggende mens den sitter i holderen; kjør så
  over støt (eller vipp den stående for hånd): skjermen snur seg ikke.

## 2026-10-09 — Stående: fart øverst, dialoger som passer

Stående sitter fartskortet nå over kartet. Dialogene stables på en telefon i stedet for å presses
sammen: Innstillinger, GPX og kartnedlastingen får sidefanene sine langs toppen (bare den åpne er
merket), telefonens faner får en egen rad, Rundtur setter kompasset over valgene, og
ruteforhåndsvisningen gir KJØR en rad i full bredde. Sjekket i Pixel 9a-størrelse i emulatoren
(søk, ruteforhåndsvisning, stedsmeny, lagring, navigering, hver skinnedialog); X7 liggende ser ut
som før.

- [ ] Pixel stående: gå gjennom Innstillinger, GPX og turer, Telefon, Venner, Hvor skal du? →
  Rundtur og en ruteforhåndsvisning; er noe kuttet?

## 2026-10-09 — Stående

Curveon snur seg nå med enheten (Androids autorotering): holdt stående er kartet øverst, fart og
musikk/meldinger under det, og skinnen er en rad langs bunnen; under planlegging av en tur får kartet
også kortenes plass. Liggende er som før. Prøvd i emulatoren (dashbord, fullt kart, søk,
ruteforhåndsvisning).

- [ ] Pixel stående: den 411 dp brede skjermen har fortsatt plass til ruteforhåndsvisningens tre valg og KJØR.

## 2026-10-09 — Curveon på en telefon: kamerahull, skinne, hint

På Pixelen (holdt liggende) flytter skinnen seg unna kamerahullet, skinneknappene er litt mindre så
kartvekslingen får plass, merknaden «ingen frakoblet kart» vises bare når det ikke finnes noe kart i det hele
tatt (heller ingen forbindelse), og musikkortet sier hvordan telefonen selv brukes. Sjekket på Pixel 9a med
hullet til venstre; X7 ser ut som før.

- [ ] Pixel: snu den andre veien (hullet til høyre): kortene til høyre flytter seg unna det.
- [ ] Curveon på en telefon uten nedlastede land, wifi og mobildata av: kartet viser «Ingen
  kart her uten nett …». Med forbindelse er det ingen merknad i det hele tatt.

## 2026-10-09 — «Denne enheten» som sin egen telefon

Innstillinger → Telefon har et nytt valg, **Denne enheten**: Curveon leser meldinger, samtaler og
musikk fra appene på samme enhet, uten companion og uten Bluetooth (for ryttere som bare har en
telefon). Companion-appen virker som før og er fortsatt installert for seg. Koden flyttet til en
delt `:phone`-modul. Prøvd i emulatoren (musikkapper funnet, en testmelding vistes på dashbordet).

- [ ] Pixel (companion installert på nytt 2026-10-09, musikk når allerede X7): en melding, en samtale og et talt
  svar kommer fortsatt gjennom. Etter denne oppdateringen måtte companionens varseltilgang slås på igjen (lytteren
  flyttet til `com.curveon.phone`; gjort med `adb shell cmd notification allow_listener
  com.curveon.companion/com.curveon.phone.CurveonNotificationListener`).
- [ ] X7: Innstillinger → Telefon → *Bruk* på Pixelen igjen etter å ha prøvd «Denne enheten» (å bytte tilbake virker).
- [ ] Valgfritt, med Curveon på Pixelen: *Denne enheten* → gi tilgang; en WhatsApp-melding vises på
  Curveons meldingskort, og musikkortet styrer Spotify.

## 2026-10-09 — Velg starten før målet

«Hvor skal du?» har nå en rad «Fra min posisjon» øverst. Trykk på den for å velge starten først (søk, et
nylig sted eller kartet), søk så etter målet. Ruten regnes da ut fra den starten med en gang, ikke først
fra der du er. Prøvd i emulatoren (fra Lillehammer til Hamar).

- [ ] Planlegg en tur som starter langt unna (en annen del av landet): forhåndsvisningen kommer
  raskt og viser «Fra …».
- [ ] Å velge starten med «Velg i kartet» tar deg tilbake til «Hvor skal du?» med den starten.

## 2026-10-09 — Offroad som et fjerde underlagsvalg

Underlagsbrikken går nå gjennom Asfalt → Blandet → Grus → Offroad. Offroad leter etter traktorveier
(også ulendte grader) og jord, gress og leire, før grus; asfalt bare der det ikke finnes noe annet.
Den holder seg fortsatt til veier og traktorveier en motorsykkel har lov til å kjøre på (ingen
stier). Rutelinja er lilla. Hamar–Lillehammer i testene: Grus 19 km uasfaltert og ingen
traktorveier, Offroad 33 km og 9 km traktorveier.

- [ ] Planlegg en tur med Offroad nær hjemmet: traktorveiene den velger er noen
  X7-motorsykkelen faktisk kan kjøre (hvis grad 4–5-veier viser seg å være for ulendte, si fra
  så koster de mer).

## 2026-10-09 — Advarsel når en asfaltrute tvinger inn grus

Med Asfalt valgt viser en rute som likevel har grus (100 m eller mer) en innrammet advarsel over
KJØR: hvor mye grus og hvor den første begynner. Den sammenbrettede linja viser «⚠ 400 m grus».
Ruteren tar bare grus på Asfalt når det ikke finnes noen asfaltvei uten en enorm omvei. Prøvd på X7
(Stautland på Bømlo: 400 m grus på slutten; med Blandet forsvinner advarselen).

- [ ] Planlegg en tur til en hytte eller gård på en grusvei: er advarselen tydelig nok før du trykker KJØR?
- [ ] En vanlig asfalttur viser ingen advarsel (ingen falske alarmer fra korte umerkede biter).

## 2026-10-09 — Ingen hjem-/nylig-knapper mens du skriver

Dialoger henter ikke lenger fram tilbake-/hjem-/nylig-linja (den presset også sammen kartet). Linja
som følger med tastaturet kan ikke skjules av en app, så X7 ble byttet til gestnavigasjon (`adb
shell cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.gestural`): med
tastaturet oppe er det bare en ▼ (skjul tastaturet) nede til venstre og en tynn hjemlinje som krever
et sveip opp, ikke et trykk. Angre: Innstillinger → System → Bevegelser → Systemnavigasjon →
Navigasjon med 3 knapper. Prøvd på X7 (Hvor skal du?, Innstillinger).

- [ ] Skriv en adresse på motorsykkelen: ▼ skjuler tastaturet, og du havner ikke lenger på startskjermen ved et uhell.
- [ ] Andre steder (Google Maps osv.) er tilbake nå et sveip inn fra venstre eller høyre kant: går det med hansker?
- [ ] En slettebekreftelse (langt trykk på en lagret rute i Hvor skal du?) viser heller ingen knapperad.

## 2026-10-09 — Grus og bløte veier på kartet

Fra zoom 11 og inn stiples veier rutingdataene kjenner som uasfalterte over kartet: grå = grus
eller komprimert, brun = bløtere, ulendt eller ukjent grunn. Leses fra rutingsegmentene, så det
virker overalt der rutingdata er installert. Prøvd i emulatoren (skogsveier øst for Elverum).

- [ ] På X7, Innstillinger → Skjerm → Veidekke = Stiplet, zoom til et skogsveiområde (eller kjør
  et sted med grus): strekene dukker opp i løpet av et sekund etter at kartet slutter å bevege
  seg, og panorering forblir jevn.
- [ ] Stikkprøve på en vei du vet er grus og en du vet er asfalt: er bare grusveien stiplet?

## 2026-10-09 — Nettkart og nettsøk

Utenfor de nedlastede landene kommer kartet nå fra OpenFreeMap (gratis, ingen nøkkel) når nettbrettet
er på nett. Søket viser frakoblede treff først, og blander så inn Photon og (i Norge) Kartverkets
stedsnavn/adresser. Prøvd i emulatoren (Arvika i Sverige; «Lyklingv» fra Bømlo).

- [ ] På telefonens hotspot, panorer X7s kart inn i Sverige: veier og byer dukker opp i samme stil.
  Panorer tilbake til Norge: `adb logcat -s CurveonMap` skal si «Online map off».
- [ ] Slå av hotspoten og panorer dit du så før: kartet skal fortsatt være der (hurtigbuffer).
- [ ] Med hotspoten på, søk etter et gårds- eller bukt-navn nær hjemmet: et «nett»-treff dukker
  opp et øyeblikk etter de frakoblede. Av oppfører søket seg som før.
- [ ] Kjøring med nettkartet vist: henger X7 med (jevn rulling, ingen lagg)?

## 2026-10-08 — Traktorveier med Asfalt

Før forbød Asfalt alle `highway=track` (og grad 4/5 var forbudt selv med Grus), så et mål på en
gårds-/skogsvei stoppet på nærmeste asfalt. Nå er traktorveier siste utvei.

- [ ] Med Asfalt: Hvor skal du? → langt trykk på kartet på en grus-/skogsvei → Kjør hit: ruten skal
  ende på veien. En tur mellom byer skal fortsatt ikke bruke traktorveier.
- [ ] Følg et GPX-spor som går over grusveier med Asfalt valgt: det skal holde seg på GPX-linja.

## 2026-10-08 — Rutestart hjemme

I OSM henger veien din (way 520497619) sammen med veiene bare gjennom sykkelstien (kartet er feil).
Sykkelstier/gangstier brukes nå bare helt i start/slutt av en rute når det ikke finnes noen vei ut
(så lite som mulig, ingen lengdegrense), aldri midt i. Fra huset ditt er det 18 m stistump ut på
Stavlandsvegen. Prøvd i emulatoren med GPS-en ved huset ditt.

- [ ] Hjemme på X7: planlegg en tur → linja starter ved huset, «Hold til venstre · Fv4998
  Stavlandsvegen» etter ~70 m. Kjør den: ingen «Beregner på nytt» på veien ned til veien.
- [ ] Kjør hjem med en tur til huset: den skal ende ved huset.
- Verdt å rette i OSM fortsatt (koble servicevegen til Stavlandsvegen), så andre apper får det riktig også.

## 2026-10-08 — Redigere en tur på kartet

Prøvd i emulatoren: langt trykk på rutelinja → redigeringsmodus, dra linja (stiplet gjetning, så
den nye ruten), dra det nye punktet igjen, Angre, trykk på et punkt → meny, Nytt stopp før, lagre
en endret lagret rute under sitt navn (oppdatert, ikke kopiert).

- [ ] På X7 med hansker: er det lett å gripe linja (22 dp på hver side) og stopp-håndtakene (30
  dp) uten å panorere kartet ved et uhell? For fiklete → si fra, så kan de gjøres større.
- [ ] Kjør en lagret A→B-rute uten å endre den: den skal fortsatt følge den lagrede linja (og koble
  seg på igjen etter en feil sving) som før.

## 2026-10-08 — Velg i kartet

Prøvd i emulatoren: mål, + Stopp og startbrikken åpner hver kartet fra «Velg i kartet», dialogen
krymper inn i linja, trykk setter en nål, bekreft virker og dashbordet kommer tilbake.

- [ ] På X7 med hansker: er et trykk på kartet pålitelig nok til å sette nålen (ikke tatt som en
  liten panorering)? Og er krympeanimasjonen jevn på X7, eller hakker den?

## 2026-10-08 — Telefonstatus, favoritter blant musikkapper

Prøvd på X7 + Pixel: Innstillinger → Telefon viser Pixel 9a, batteri, «tilkoblet siden» og de
oppdagede musikkappene; å stjernemerke Spotify (som ikke kjørte) og sveipe musikkortet til den
startet den på telefonen (Spotify avviser Curveons mediabrowser, avspillingstast-reserven virket
etter ~8 s).

- [x] Spotify skal nå starte på ~4 s i stedet for ~8 s (en avvist browser går rett til
  avspillingstasten): lukk Spotify på telefonen, sveip til den på X7.
- YouTube Music kan ikke startes fra nettbrettet når den ikke kjører (den lar bare Android
  Auto/systemet starte den; prøvde avspillingstaster og «spill fra søk»). Når den har spilt på
  telefonen, kan den byttes til som de andre.
- [x] Stjernemerk NRK Radio / Podme også, lukk dem på telefonen (sveip bort fra siste apper), sveip så til
  hver på X7. Hvilke starter, og hvordan? `adb -s 58301JEBF17776 logcat -s CurveonMedia` → «browser connected
  … play» / «started by play key» / «didn't start» (nettbrettet sier det da).
- [x] Glem i Innstillinger → Telefon: telefonen flyttes til «Parede telefoner»; å trykke på den der kobler den til igjen.

## 2026-10-08 — Stopp, planlegger, lagring, feil sving

Prøvd i emulatoren med falsk GPS (Voss → Bergen, så ut mot Granvin): spørsmålskortet, Snu (banneret
sier «Ta en U-sving» til du har snudd), Ny rute herfra med Behold/Hopp over stopp, Koble på lenger
fram på en lagret rute, det automatiske valget etter 20 s, stille påkoblinger i Voss sentrum, +
Stopp / rutemeny / lagringsnavn under kjøring. Beslutningene ble også simulert over ekte veier på
skrivebordet (`WrongTurnSimulationTest`). Gjenstår å prøve på motorsykkelen:

- [x] Kjør en planlagt tur og ta en feil sving med vilje der det finnes en rask vei rundt (et
  kvartal, en parallellvei): den skal rute om stille, uten kort. Så en inn på en vei som leder
  bort: kortet skal komme, og Snu / Ny rute herfra skal gjøre det de sier.
- [x] Ignorer kortet og kjør videre: etter ~20 s skal det velge «Ny rute herfra» selv. Stopp
  ved veikanten i stedet: det skal vente på deg.
- [x] Etter «Ny rute herfra», forlat ruten igjen innen 5 minutter: ikke noe nytt kort, bare en
  ny rute (følgemodus, f.eks. når du kjører bak noen).
- [x] Er 40 m / 3 min / 10 % (Innstillinger → Navigasjon) en god standard på ekte veier? For
  mange eller for få kort? Hver beslutning logges: `adb -s <x7> logcat -s CurveonNav` → «Off
  route at … m, heading …: rejoin/ask/replan».
- [x] Ny ruting tar noen sekunder på X7 (flere ruterkall). Hvis «Beregner på nytt …» blir
  stående mye lenger enn ~5 s på lange turer, si fra.
- [x] Planlegger hjemme: langt trykk på kartet → Start her, legg til stopp, lagre; senere i
  Hvor skal du? skal den lagrede ruten følge nøyaktig samme linje.

## 2026-10-08 — Frakoblet søk i alle land

Prøvd i emulatoren: Norges søk bygget fra kartet (~2 min, «enkelt søk»), så erstattet av det fulle
fra skyen («fyldigere søk tilgjengelig» → Hent manglende). På X7 ble den gamle Norge-indeksen
overført som den fulle. Gjenstår å prøve på X7:

- [x] Last ned et nytt land (f.eks. Sverige, når
  https://github.com/atlehavik/curveon-data/releases/tag/search har `sweden.sqlite.gz`): søket finner en by,
  en gate med husnummer og en bensinstasjon der.
- [x] Søk i Norge finner fortsatt norske adresser først; med to land installert dukker treff fra
  begge opp.
- [x] Valgfritt: i flymodus, bygg søk fra kartet for et lite land (Hent manglende uten
  forbindelse faller tilbake på det) og se hvor lang tid det tar på X7.

## 2026-10-08 — Last ned kart per land

Prøvd i emulatoren (Færøyene: last ned, liste, slett) og på X7 (Færøyene ved siden av Norge på
kartet, så slettet igjen). Gjenstår å prøve på X7:

- [x] Innstillinger → Kart og data → Last ned land → Sverige (eller et annet land du skal
  kjøre i): framdrift, så vises kartet over grensen og ruter virker der.
- [x] Velg to eller tre land etter hverandre: de står i kø («Venter») og lastes ned ett etter ett;
  Avbryt på et ventende tar det ut.
- [x] Grenseområder (f.eks. Svinesund): veiene ser normale ut der de to kartene overlapper (ingen doble stedsnavn).

## 2026-10-08 — Motorsykkelen holder seg på veien under navigering

Bare enhetstestet (sporeren); trenger en ekte tur med rute på X7:

- [x] På ruten sitter motorsykkelen på rutelinja og peker langs veien, også der GPS-en driver
  (trær, bratte daler) i stedet for å sveve ved siden av.
- [x] Ta en annen vei med vilje: motorsykkelen blir på linja i ~3 s, hopper så til den ekte
  posisjonen din når ny ruting starter. Skal ikke føles for klistrete eller for ivrig.
- [x] Trange hårnålssvinger: pila snur med veien uten å henge stygt etter.

## 2026-10-08 — Turer: velg/slett, autolagring i GPX; kartkompass, 3D, «hvor er jeg»-glød

Valg, sletting, autolagringsbryter, 3D, resentrering og kantglød er testet i emulatoren (ingen
kartfliser der, så 3D ble bare sett på et tomt kart). På X7:

- [x] Etter en ekte tur med «Autolagre nye turer i GPX» på: turen dukker opp som et spor i den
  valgte GPX-gruppen når den slutter (20 min i ro, eller at appen lukkes, teller *ikke*).
- [x] 3D under kjøring: veien foran er lesbar, rytteren ikke for lavt; vipp/zoom føles riktig i svingete veier.
- [x] Panorer kartet bort under kjøring: den cyan gløden på kanten peker riktig vei og følger med
  når du beveger deg; ◎-knappen tar deg tilbake og forsvinner.
- [x] Et trykk på det lille dashbordkartet går ikke lenger i fullskjerm (bare skinneknappen gjør det).

## 2026-10-07 — Språk: English / Norsk

Testet i emulatoren (bytte begge veier, Innstillinger blir åpen). På X7:

- [x] Innstillinger → Skjerm → Språk/Language: velg Norsk; hele appen blir norsk med en gang.
- [x] Kjør med navigasjon på norsk: svingbanneret («Sving til høyre · deretter …») passer, og
  tall bruker desimalkomma (12,5 km).
- [x] Android Innstillinger → Apper → Curveon → Språk lister English og Norsk også.
- [x] Nullstill om nødvendig: `adb -s 241181d1 shell cmd locale set-app-locales com.curveon.app --locales en`

## 2026-10-07 — GPX-grupper: databaseoppgradering på X7

GPX-gruppebygget ble installert på X7 ved en feiltakelse mens den var tilkoblet. Det oppgraderer
databasen første gang appen åpnes (de 20 TET-sporene dine blir gruppen «TET S»). Denne
oppgraderingen ble testet i emulatoren på en kopi av X7s data. En sikkerhetskopi av X7s database
tatt før oppgraderingen ligger i `~/develop/Curveon-data/db-backup-2026-10-07/x7/`.

- [x] Åpne Curveon på X7, så GPX: én gruppe «TET S» (20 spor) og sporene dine vist som før.
- [x] Turer: alle 7 turene er der fortsatt; «Lagre i GPX-spor» på en tur legger den i gruppen du velger.
- [x] Kart: et spor satt til invertert (← i GPX-listen) viser pilene sine den andre veien.
- [x] Veipunktdetaljer: installer det nyeste bygget (`./gradlew :app:installDebug` med bare X7
  tilkoblet), så GPX → Importer → `Download/S.gpx` igjen. Gruppen og sporinnstillingene dine
  beholdes; veipunktene får nå adresse, telefon, lenker osv. Gjør det samme for andre TET-filer
  du bruker.
- [x] Kart: utzoomet er Sveriges veipunkter fargede prikker; innzoomet (omtrent 30 km over
  skjermen) blir de til ikoner. Trykk på ett (f.eks. en campingplass) og sjekk at detaljene
  ser riktige ut.

## 2026-10-07 — innkommende samtalekort forsvinner etter et øyeblikk

På dashbordet vistes samtalekortet kort, så tok musikkortet plassen. Årsaken er ikke funnet ennå.
Nettbrettet logger nå hvilken av to ting som skjedde.

- [ ] Kjør/sitt med X7 koblet til telefonen, la noen ringe deg, og la det ringe ~20 s.

- [ ] Så, med X7 på USB:

  ```
  adb logcat -d -s PhoneLink
  ```

  - `Phone removed call …` → telefonen selv fjernet samtalevarselet (rettelse trengs på telefonsiden).
  - `Link dropped during a call` → Bluetooth-linken til nettbrettet falt ut mens det ringte.
  - Ingen av linjene → kortet ble ikke fjernet; beskriv heller hva du så.

## 2026-10-07 — valg av musikkapp (Spotify mot podkast)

- [x] Med både Spotify og podkastappen åpnet på telefonen viser musikkortet på nettbrettet små prikker
  øverst. Bla kortet sidelengs (dra): det skal pause den første appen og spille den andre.
- [x] Stopp all musikk, trykk så på det tomme musikkortet: det skal tilby appikonene å velge mellom.
- [x] «Telefonen er ikke tilkoblet»: slå på deling i Curveon Companion, trykk på musikkortet på
  nettbrettet — det skal koble til i løpet av noen sekunder i stedet for å vente på neste automatiske
  forsøk.
