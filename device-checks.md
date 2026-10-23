# Device checks

Things to try or look at on the real devices (Hugerock X7 tablet, Pixel 9a) that couldn't be
tested in the emulator. Newest first; tick them off or delete them when done.

Commands assume the device is connected over USB. With both connected, pick one with
`-s <serial>` (see `adb devices`). `adb` lives in `~/Android/Sdk/platform-tools/`.

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
  ti minutter, eller med en gang hvis vennen åpner Venner): «X delte en rute», med «Den er din egen kopi …» og Kjør nå / Lagre / Ignorer.
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

- [ ] **Pixel:** Mens du navigerer til et sted: trykk på ankomsttiden (folder seg ut som før), så på den nye **Rute**-knappen. Med en
  gruppetur på: **Del** sender ruten til gruppen, og neste gang står det «Delt». Uten gruppe: teksten sier at du må starte en
  gruppetur, og Del åpner Gruppetur.

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
- [ ] **Grønn prikk** på Venner-knappen mens en gruppe eller en venn får posisjonen din, og ingen prikk når ingen gjør det.
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

- [ ] Pixel (Curveon + Companion, Start sharing) og X7 koblet: planlegg en tur på Pixel, trykk nettbrett-ikonet i
      forhåndsvisningen. Pixel sier «Ligger nå på nettbrettet»; X7 viser «Tur fra telefonen» med Kjør / Senere, og
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
      skimtes. Se også at andre vinduer som spretter opp (Kartpunkt, «Forkast tur?») ikke er kuttet nederst.
- [ ] Etter et par sekunder dukker Vanlig / Klistremerke / Neon / Fart opp (første gang kan Play-tjenester
      bruke litt tid på å hente modellen; uten nett vises bare bildet). Prøv et bilde av deg med hjelm/uten,
      og se at utklippet følger hodet. Lagre og se at runden i profilen og på kartet ser bra ut.
- [ ] Samme på X7 (har den Google Play-tjenester? Hvis stilene aldri kommer, si fra).

## 2026-10-21 — Tilt helt ned mens kartet følger deg (1.2.2)

- [ ] 3D mens kartet følger deg (gjerne under navigering/demotur): hold inne 3D-knappen og trykk «−»
      til det ikke går lenger. Blikket skal gå like lavt ned mot veien som når du har dratt i kartet,
      og pilen din skal fortsatt sitte lavt på skjermen, uten å hoppe når du trykker.

## 2026-10-21 — Stiplet grus forblir stiplet når du zoomer inn (1.2.2)

- [ ] Nattkart og dagkart, Veidekke = Stiplet: zoom inn på en grusvei helt til zoom 18: den er stiplet
      hele veien, uten hvit/grå vei i mellomrommene. Se også der grusveien møter en annen vei: krysset
      skal ikke se ut som det har et hull. Stedsnavn ligger fortsatt øverst.

## 2026-10-21 — Settings tabs scroll to the open page; via point or stop when changing place; grey or dark grey roads (1.2.2)

- [ ] X7, day map: Settings → Display → Roads → Grey, then Dark grey: small roads, the tarmac roads
      drawn by the gravel layer and the plain gravel dashes change shade together; place names stay on
      top and readable. Dark grey looks as before.

- [ ] Plan a trip with a route point, tap it → Change place → Pick on map → tap: the bar offers
      Route point and Stop. Route point keeps it a white dot, Stop makes it a numbered stop. Same
      for "Add stop" before a point. Pixel portrait and X7.

- [ ] Pixel, portrait: Settings, tap the tabs from left to right up to Help: the open tab's name
      shows in full each time, also Help at the far right (in Norwegian too).
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
- [ ] «Si igjen» på SMS-kortet starter opptak på nytt, X avbryter uten å sende.
- [ ] Ring fra dialogen ringer det valgte nummeret (prøv en kontakt med to numre).

## 2026-10-21 — Les opp meldinger

- [ ] Med Pixel koblet til X7: få en melding, trykk høyttaleren ved avsendernavnet. Meldingen skal leses opp i hjelmen («Navn skriver: …»), musikken dempes litt mens den leses. Uten norsk stemme lastet ned på telefonen kommer bare en lyd.

## 2026-10-21 — SOS-knappen utløses ikke lenger av å skli

- [ ] Group ride-fanen: legg tommelen på SOS-knappen og dra opp/ned (scroll) eller ut av knappen. Fremdriftsbaren skal nullstilles og **ingen** nedtelling starte. Holdes den i ro i tre sekunder skal nedtellingen på 10 starte som før.

## 2026-10-20 — Friends and groups live in Curveon, the phone is the way to the internet (not released, in 1.2.2)

Built and unit tested (the tunnel client and server against each other, and the new messages), tried on the X7 over USB: the friend code
shows at once with no phone near, and nothing crashes. **Not tried, because it needs the Pixel and the relay on the net:** everything below.
The companion no longer keeps friends or groups of its own; it shows how the tablet's active group is doing, asks the tablet for a friend
code and hands over what it scans. The tablet uses its own internet if it has some, else the phone's through Bluetooth.

- [ ] **X7 with the Pixel linked, X7 has no internet** (wifi off, no hotspot): Friends → My friends → Show my code: the code comes up within a second or two (made on the X7, nothing sent to the phone).
- [ ] **Same, start a group** (uses `relay.curveon.no`). The group should say "connected" through the phone. Check `adb -s <X7> logcat -s Curveon GroupHub`, and that the phone's data use is small.
- [ ] **Turn wifi on at the X7 while a group is running:** the group moves to the X7's own internet by itself (a short "offline" at most). Turn wifi off again: back through the phone.
- [ ] **Pixel companion → Friends → Show my code / Share link / Scan:** the code is the X7's (a friend who scans it shows up in the X7's friend list, not on the phone). With the X7 not linked or switched off you get "Open Curveon on your tablet or this phone first".
- [ ] **Companion's Group ride card** shows the X7's active group and "connected" / "offline" like the X7 does. It clears when the X7 disconnects.
- [ ] **Old companion on the Pixel with the new X7 build:** the X7 ignores what the old companion says about groups, so nothing doubles up. (Update the companion to get the tunnel.)
- [ ] **My devices (Pixel's Curveon app + X7).** Friends → Me → My devices: "Show code for my other device" on one, "Scan code from my other device" on the other; both then say "Linked". Tried on the X7 and a phone emulator against the relay on this PC (link by intent, not by camera): both said linked and a group started on the X7 showed up on the emulator. **Not tried:** scanning the code with the real camera, the profile (name, colour, picture) following, a friend added on one showing on the other, leaving a group on one removing it on the other, Unlink, and the Pixel itself. Do the camera scan, then add a friend on the X7 and open Curveon on the Pixel: the friend should be there within seconds (a friend is not shown until their hello has been heard).

## 2026-10-21 — Rides end after 5 minutes, "Continue the ride?" (not released, in 1.2.2)

Only the logic is tested (unit tests); the question card hasn't been seen with real riding.

- [ ] Ride a few km (Rides on, in Settings), stop for 5+ minutes: the ride is finished in the ride log (and its GPX copy made if auto-save is on), the red dot is gone.
- [ ] Ride on within two hours: a card "Continue the ride?" with Continue / New ride appears at the top of the map after a few seconds of riding. Check it doesn't cover the arrival box, speed card or road card on the X7 and the Pixel, while navigating too.
- [ ] Continue: the ride log shows one ride (no new one), the distance grows, and with auto-save the GPX group has one track for the whole ride, not two.
- [ ] New ride (or ignore it for 30 s): the card goes away, and the earlier ride is left as it was.
- [ ] A stop you end with the stop button, or longer than two hours, does not ask.

## 2026-10-20 — Gravel and rough ground on the map (not released, in 1.2.2)

Installed on the X7, but no gravel showed near the desk, so the new look hasn't been seen yet.

- [ ] Ride or pan to a gravel road (zoom 11 or closer) in Settings → Display → Road surfaces = Dashed: the road itself should look dashed, same colour and no thicker than the neighbouring roads, brown for rough ground. Check the gaps don't show a pale line over forest.
- [ ] Same with Coloured: orange gravel, purple rough ground, as the route. Also on the night map.
- [ ] With Dashed or Coloured on, place names (towns, villages) lie above the gravel dashes, the route and GPX tracks, as they do with Off. Dashes should look thinner than the main road and the thin country road, e.g. at Helland.
- [ ] Day map is high contrast without the old switch; small roads stay dark grey.

## 2026-10-19 — Group rides (not released, no version bump yet)

Tried 2026-10-19 on the X7 (as its own phone, over a relay on this PC with simulated riders): joining by link,
positions both ways, the map markers, the group dialog, the invite QR, SOS in both directions and "I'm coming".
Not tried, because it needs the Pixel: the real chain X7 → Bluetooth → companion → relay.

Set up a test group without a second rider (the relay and the simulated riders live in `~/develop/curveon-relay`):
`npm start` there; `node test/rider.ts create --riders 3 --lat <lat> --lon <lon> --sos-after 120` prints an invite
link (`curveon://join#...`) and keeps three riders circling the point. `adb -s <phone> reverse tcp:8787 tcp:8787`, then
open the link on the phone: `adb -s <phone> shell "am start -a android.intent.action.VIEW -d '<link>'"`.

- [ ] **Pixel + X7 together.** Install `:companion:installDebug` on the Pixel and keep the X7 on the build with groups. Everything about groups (start a group, name and picture, invite, data saver) is done in Curveon on the X7; the companion only shows the group's status, scans a QR code and opens invite links. On the X7: start a group. On the Pixel, scan its QR code (companion: Group ride → Scan a QR code) or open the link: the X7 should then show the group's riders, and the X7's position should show up for the others.
- [ ] **After restarting Curveon** (force-stop, start) with a group active: the group button and the riders are back within about 10 s. Seen once on the X7 (as own phone) that they were *not* back after a restart although the group was stored (`shared_prefs/groups.xml` held it): if it happens again, pull `adb logcat -s GroupHub` and say when.
- [ ] **SOS sent from the dialog:** the red "SOS sent" card sits at the top of the dialog (it was behind the dialog before this fix) with "Received by n of m" counting up, and "Cancel SOS" works.
- [ ] **Overlap (CLAUDE.md):** the group button is in the main menu (rail) between Phone and Settings, so the map button column is as before. Check on the Pixel (portrait: six buttons along the bottom; landscape: six down the side with the map toggle still reachable) and the X7 landscape. The dialog opens in the panel area like the Phone one.
- [ ] **Curveon alone on a phone (no companion installed):** a whole group ride must work: Group → Settings → relay address, start a group, invite with the QR or Share link, your name and picture, scan or open someone else's invite, SOS. Nothing may say you need the companion app.
- [ ] **Your picture, from the camera:** Group → Your name in the group → Take a picture: the camera opens (the app asks for the camera permission first, as it holds it for scanning), then the picture comes back in a cutting window: drag and zoom with two fingers until your face is in the circle, Use this picture, and the big round preview shows exactly what the others will see. Save. Also try Choose from pictures and Remove. Seen on the X7: choosing a picture, cutting it (dragging) and the preview; not seen: the camera path and two-finger zoom.
- [ ] **Companion: scanning a QR code** (Group ride → Scan a QR code): same as in Curveon; joining from the phone's camera is for a tablet whose camera is out of reach.
- [ ] **Scanning a QR code in Curveon** (Group → Scan a QR code): the camera permission is asked only now. Hold the camera in front of an invite's QR (from the other device's Group → Invite riders) and then in front of the "My other device" one, at arm's length and closer: it should read both within a second or two and join. Seen so far: the camera opens without errors on the X7 (rear camera 0) and a QR drawn and read in unit tests, also turned and small; not seen: a code held in front of the real camera. Say if a dense code is hard to read and I will raise the picture size.
- [ ] **Your own picture:** Group → Your name in the group → Choose a picture of you (the system photo picker, no permission asked). The marker on the map and the row in the group list show it round; the others see it after a few seconds. Try a portrait photo taken sideways (it must be turned right) and remove it again.
- [ ] **Only one device sends your position:** with Curveon on both the tablet and the phone in the same group (as one rider, see below), drive with one: the other shows "Your position is shared from your other device" with "Use this device"; pressing it on the other moves the sending there (and the first one then shows the note). A device left behind with the app open takes over by itself after three minutes if the other has gone quiet.
- [ ] **One rider on two devices:** in a group, Group → "My other device" on the tablet shows a QR; open it with the phone camera (or `adb shell am start -a android.intent.action.VIEW -d '<link>'`) on the phone running Curveon: the others must see one rider, not two, and a position sent from either device reaches them. The code stops working after 15 minutes.
- [ ] **SOS from someone else** is a small red dialog (not full screen): "Drive there" starts a route to them, "Ignore" closes it, and it says how many are on the way. The alarm stops when you do either.
- [ ] **Tunnel / no coverage:** drive into a tunnel or switch the phone to flight mode: the group says "No contact with the group", the position is sent once when coverage is back, and an SOS made without coverage goes out when it returns.
- [ ] **Battery:** one hour of riding with a group active, phone screen off: compare with the same ride without a group (the phone uses the radio every 5 s; data saver 15 s).
- [ ] **Invite links with both apps on one phone:** `curveon://join` is handled by both Curveon and the companion, so Android asks which to use. Pick the companion when it is paired with a tablet, otherwise Curveon.
- [ ] **Samsung/Xiaomi-type phones** (not the Pixel) may kill the companion in the background: if a group goes quiet on someone's phone, note the model.
- [ ] **Not built yet, so do not look for it:** sharing a route to the group from the route screens, and a notification for SOS when Curveon is not open on the phone.

## 2026-10-19 — Route colour, pruned via points, time to the next stop (1.2.2)

- [ ] Navigate a route with gravel and offroad in it: asphalt keeps the usual blue, gravel is solid orange, offroad solid purple, no see-through dashes on the route. Night and day map, and at the rider arrow (orange at night) check it is still easy to see.
- [ ] Roundabout in the turn card (demo ride past one): three arrows turning against the clock, the exit number clear of the arrowheads. In a left-hand country (e.g. Britain, if its map is on the device) they turn with the clock.
- [ ] 3D map while riding: the arrow sits a bit lower than before without touching the cards at the bottom (also on the phone in portrait and landscape); the default tilt is steeper (55° instead of 50°; if you have set a tilt before, yours is kept).
- [ ] Add several stops while riding (also by tapping in the map, and a fuel station), with the road style Fastest: every stop is on the route (the line goes to each one, also a short detour), the numbers follow the order you ride them, and "stop reached" comes only when you are at the stop. Say if any stop is still passed at a distance: pull `navtrace` and send the `E,…addStop` lines.
- [ ] Convert a GPX track to a route (GPX list → make a route): the line is cyan, not green. Same when riding a track or a logged ride.
- [ ] After converting, wait a minute or two without touching anything, then open the route menu: fewer stops than before? Pull the log and look for the pruned count: `adb logcat -d -s Curveon | grep Pruned` ("N -> M via points"). Edit the trip (drag, add a stop) while it works: nothing should jump or be lost.
- [ ] Arrival box (demo ride with a stop and a fuel suggestion): folded = arrival time, and orange km only when there is a stop on the way (nothing extra otherwise); folded out = km and time to the destination, a thin line, then each stop (name, then km and time in orange, same columns), a small red cross on each stop asks "Delete <stop>?" (not on a GPX track's stops), more stops below with lines between; tap the fuel chip: a dialog asks "Go to <station>?", Yes adds it as a stop. Check phone portrait and landscape, and the tablet, with the speed card wide (over 100 km/h).

## 2026-10-18 — Fuel stop on a demo ride, trip bar at speed, stale road number (1.2.2)

- [ ] Demo ride on the Pixel (portrait), pass 100 km/h: the arrival box top left keeps its × visible; folded it shows only the arrival time and ×; fold it out with a fuel chip showing: line 2 distance + time left, then next stop, fuel and Add stop each on its own line, left-aligned, nothing overlapping (the road card moves down with it).
- [ ] Tap the fuel chip: a numbered orange stop appears on the station, no "A" dot where you are; the route menu lists it (without a ×, a track's stops can't be taken out one by one). Say if the route to the station looks wrong.
- [ ] End the demo ride, ride/stand elsewhere (not near the E18): the E18 card is gone or shows the road you are on.

## 2026-10-18 — Add stop that doesn't redraw the route (1.2.2)

- [ ] While navigating, add a stop. If the route doesn't change (or nothing happens), note which stop and where it was, then pull the trace: `adb -s <device> pull /sdcard/Android/data/com.curveon.app/files/navtrace` and look for the `E,…,addStop '<name>' <lat> <lon>: …` lines (started / done / cancelled / failed / no route / no position). Known from the Pixel trace of 2026-10-18: one route change 1.5 km into the ride kept exactly the old line (1235 vs 1236 points), the next one, 40 s later, had a small detour.

## 2026-10-18 — Arrival box top left, road card under it (1.2.2)

- [ ] Navigating with a road card showing: the arrival box is on top, the road card right under it. Tap the arrival box open: the road card moves down and never touches it. With no road info, the arrival box is top left alone.
- [ ] Road card Large / Extra large: still under the box, never overlapping, also folded out.
- [ ] Gravel heat button (not navigating): the road card sits under it.
- [ ] Right side: only the speed card and the map buttons.

## 2026-10-18 — Place names on top, thinner road edge (1.2.2)

- [ ] Online map AND downloaded map, normal and high contrast, several zoom levels (10–16): no road, gravel dash or tarmac line runs over a place name. Online labels were under the overlay lines before.
- [ ] High contrast, zoomed out: no road runs through a place name; the white halo hides what is under it.
- [ ] Normal day map in sun: is the thin grey edge (#808080, 1 px) around white roads enough, or too weak now? Say which way to go.

## 2026-10-18 — Road names beside roads, high contrast while navigating, music player stays put (1.2.2)

- [ ] Road names sit beside the road at zoom 15+, all from the same zoom; readable and not too far from the road (also on wide roads)?
- [ ] High contrast: place names (white halo) readable when zoomed out a bit.
- [ ] Day map, normal map setting: starting navigation turns on high contrast, stopping puts it back.
- [ ] Portrait, full-screen map, music icon, while navigating: tap the icon. The player opens on the same height, beside the turn card, not above it.
- [ ] The road number in the turn card (38 dp) is readable at a glance.

## 2026-10-18 — Road card size, one-sided speed signs, wider roundabout ring (1.2.2)

- [ ] Settings → Display → Road info size: Large and Extra large make the road number, priority and give way signs bigger. Check it doesn't touch the speed card or the trip bar (Pixel landscape, portrait).
- [ ] High contrast, zoomed far out and in: small roads dark grey at every level, none white.
- [ ] A speed sign stands on the right of the road only, where the limit changes.
- [ ] Roundabout icon: the number sits clear of the arrows; is the size right?

## 2026-10-18 — Speed signs on the map, stop alert, roundabout icon, road info without a map (1.2.2)

- [ ] Pixel/X7, navigating a route with speed limit changes (a road with NVDB limits): a speed sign stands on each side of the road where the limit changes (not where it stays), within ~1 km ahead; zoom 13+.
- [ ] A trip with a stop on the way: reaching it (30 m) shows a "Reached <name>" banner for 8 s and plays the arrival sound (or says the name with spoken navigation); tap closes it. Check it doesn't fire again, and not for the destination.
- [ ] Navigating: the arrival box shows time and km left folded, and h:mm left when tapped. A roundabout shows two big arrows with the exit number inside; readable?
- [ ] Hold a finger on a road in a country whose map is not downloaded (e.g. abroad): the road card says to download the map for <country>.
- [ ] High contrast: the dark grey (#707070) at several zoom levels; the same grey everywhere; main roads yellow with black edge.

## 2026-10-18 — Road card place, small roads on the day map, night arrow (1.2.2)

- [ ] X7 in bright daylight: Settings → Display → High contrast on (map on Day or Auto in daytime). Dark grey roads with black edges and yellow main roads: easy enough to see? Say if the grey should be darker/lighter or the edge wider. Paths and tracks go mid-grey in this mode.
- [ ] X7 in bright daylight (day map): the small white roads now have a dark edge; are they easy enough to see? Say if the edge should be darker or wider, or if the grey paths/tracks need the same.
- [ ] Night map while navigating: the arrow has a white edge and a light halo; clear enough on the dark map?
- [ ] Road card (road number / priority sign): under the speed card in portrait and in the dashboard; centred at the top only on the full-screen map in landscape. Check it doesn't meet the trip bar on the Pixel in landscape.

## 2026-10-18 — Calmer navigation cards, call card on the dashboard (1.2.2)

- [ ] Pixel (Curveon on the phone itself): ring it and tap Answer on Curveon's call card. It must answer (if not: `adb logcat -s PhoneLink` and look for "Call action"). Never tried with a real call.
- [ ] Answer a call with the dashboard showing (not full-screen map): a call card with Hang up and Speaker sits at the top of the card column, no green icon over the map. Full-screen map: the green icon is still there.
- [ ] Full-screen map, music playing: a small cover icon bottom right; tap opens the player, tap its cover (or wait 10 s) folds it. Check the map buttons and the turn card don't collide, open and folded.
- [ ] Navigating: the arrival box shows only the time; tap shows km and time left. The turn card is narrow, tap shows the words; a roundabout shows the exit number in its corner.
- [ ] Settings → Display → Speed limit sign: Large and Extra large grow over the speed card's top and bottom without the card growing; nothing gets cut off at the screen edge (also with a speed camera or altitude line under it).

## 2026-10-18 — Faster map downloads, next country starts while the last one finishes (1.2.2)

- [ ] Pixel: Settings → Data → add a mid-sized country (e.g. Denmark) on wifi: it should download clearly faster than before (the PC test went from 46 s to 14 s for 96 MB). Note the time and the speed on a phone hotspot too.
- [ ] Pixel: queue two or three countries. When the first map is done its row shows its own bar for routing/search while the second map already downloads; both can be cancelled separately. Check the app doesn't run out of memory (`adb logcat -s AndroidRuntime`) and that the map of country 1 opens while country 2 downloads.
- [ ] Pixel: cut the connection halfway (airplane mode on) while a country downloads: the error shows, partial files are cleaned, and the next try works.

## 2026-10-18 — Play preparation: app names without QUERY_ALL_PACKAGES

- [x] Pixel (installed) and X7: a message in an app that isn't a music app (WhatsApp, Messenger, SMS) still shows with the app's name ("WhatsApp"), not `com.whatsapp`. Also a call/notification from a launcher app you rarely use.
- [x] Settings → Navigation/Music: the list of music and podcast apps is the same as before (Spotify, NRK Radio, YouTube Music, ...), and starting one that isn't running still works.
- [x] If an app name shows as a package name: `adb shell dumpsys package <package> | grep -i launcher` and tell me which app it was.

## 2026-10-18 — Adventure finds more routes; white Hjelpepunkt button (1.2.2)

- [ ] Pixel: plan an adventure with an area and a long time (e.g. the Hallingdal → Gudbrandsdalen oval, 7 h), then shuffle a few times: each shuffle should give a different route near the asked time (before, the first try could come out at half the time, and later tries added nothing).
- [ ] Pixel: plan an adventure: Tur 1 shows at once with its time and km, and a bold yellow "Første forslag. Leter etter en bedre tur …" under it while it keeps looking; the first trip should be near the asked time (before it could be far short). Better trips appear as Tur 2, 3 beside it.
- [ ] Pixel: draw an adventure area: the time button says "Ingen grense" (grey) from the start. Plan it: trips come with their own times (a long area can give 8-10 h); pick a time on the button and it limits the trips as before; pick "Ingen grense" again to go back. Check how often a no-limit trip strays outside the drawn area (it did for 2 of 4 variants in the PC test).
- [ ] Pixel: Juster ruten → Endre i kartet: the padlock is locked every time you enter, also after unlocking it on an earlier edit.
- [ ] While riding, tap the map for a new stop: the Hjelpepunkt button in the "Stopp på veien, eller nytt mål?" bar is white with black text and stands out from the map.

## 2026-10-17 — Fuel gauge setup: tank twice, engine off (1.2.2)

- [ ] Settings → Engine → Fuel gauge setup, X7 or Pixel with the OBD2 adapter on the bike, engine **off**, ignition on,
  parked the same way every time. Tank size in step 2. At the pump: Before filling (reads ~40 s: two readings
  15 s apart), fill to the first click, park the same way, enter the pump litres, After filling. Do it at two
  refuellings. Step 4 should then offer a reading that rises or falls with the litres; pick it and check that
  step 5 and the dashboard Fuel show litres that match the bike's own gauge.
- [ ] Step 6 (fine-tune): with the reading chosen, register a level you know (e.g. after pouring in 2 L from a can, or Full): it is read ~40 s and listed in step 3; the litres on the dashboard follow the table. Remove it again with its cross: the chosen reading stays.
- [ ] If step 4 offers nothing: it says whether the adapter heard the bus at all. Pull `files/navtrace/obd-*.txt` and send it.
- [ ] Remove a refuelling with its cross (both levels go); the chosen reading goes too when only one refuelling is left.
- [ ] Leave the Before reading for over 30 min, then After: it says the reading is too old and must be removed.
- [ ] Old levels registered with the earlier version are listed one by one; they don't count as refuellings, so register two again.

## 2026-10-17 — Progress when making a GPX track adjustable from the ride sheet (1.2.2)

- [ ] GPX and trips → GPX: ⋮ ... ride menu → Kjør GPX herfra, then tap the adjust (EditRoad) icon in the ride sheet header: the sheet shows "Konverterer …" in cyan above the options until the route appears.

## 2026-10-17 — Speed cameras only when zoomed in (1.2.2)

- [ ] Settings → Display → Map layers: Speed cameras on. Zoomed out over a city (e.g. Bergen) no red dots; zoom in to where houses appear and they show.

## 2026-10-17 — Select buttons stay put in Trips and Planned (1.2.1)

- [ ] Pixel, upright: GPX and trips → Trips (and Planned): scroll down, the select button stays in its row above the list; select two, the Alle / merge / delete buttons stay too.
- [ ] Pixel, sideways: the same buttons sit in the title bar, with "2 valgt" under the title.

## 2026-10-17 — Gravel colours, slimmer dashes, DEMO tab, fuel gauge setup

- [ ] Settings → Display → Road surfaces: pick Yellow, then Green. On the light map and on the dark map
  the gravel dashes are yellow (and the soft roads orange-red) / green (soft roads amber), and the
  little samples in Settings match what the map shows (they follow the map theme).
- [ ] Plan or ride a route over gravel roads: the dashes on top of the blue route are slim, the blue
  line is easy to follow between and around them; GPX tracks too.
- [ ] Settings → Navigation → Demo ride on, start a navigation: a clear amber DEMO tag sits right under the rider arrow
  and follows it. Check it covers nothing (phone upright too). It goes when
  the demo ride is off or navigation stops.

## 2026-10-17 — Waypoints hidden in the route planner

- [ ] Plan a TET route (e.g. N-01) on the Pixel and the X7: the chip row in the planner shows only
  "Juster ruten" (and the start chip), no row of white waypoint chips. A stop you added with
  "Legg til stopp" still shows as a numbered chip; route line and the map are unchanged.

## 2026-10-17 — Snappier app (tabs, map, routes, GPX)

- [ ] On a phone held upright: swipe between the Settings pages. The lit tab changes as soon as the
  next page is more than half across, not after it stops. Tapping a far tab still glides straight
  to it without the lit tab flickering past the ones in between.
- [ ] Plan or ride a long route (e.g. TET N-01) on the X7: the route line, A/B pins and the speed card
  should look as before, and the screen should not hitch when the route appears or when a GPX
  group is switched on or off. Measured on the X7 with demo ride: the garbage collector ran
  about half as much, and the main thread had far less to do while a route was shown.
- [ ] The X7 is on the `ride` build now (`./gradlew :app:installRide`): not debuggable, so it starts in
  0.8 s instead of 2.8 s and routes a 1558 km GPX track in 31 s instead of 43 s. It installs over the
  debug build and keeps the data; use it for the handlebars, `installDebug` only when debugging.

## 2026-10-17 — GPX ride button: ride or convert

- [ ] In the GPX list, tap the cyan ride button: a menu with "Kjør GPX herfra" (as before) and
  "Konverter til justerbar rute". Both show a progress message while they work; converting
  TET N-01 takes ~15 s. The route should now follow the track everywhere (no red stripes;
  measured 0 % of the track off the route, was 50 %), with a few hundred white dots.

## 2026-10-17 — Better GPX → route, route points, cancel a tap

- [ ] Make TET N-01 Øyermoen–Blåhø into a route ("Juster som en rute"). It now adds extra points
  where the track takes a side road the router would skip (takes a bit longer to appear). Check
  the route follows the track's small roads, and that it doesn't need manual fixing.
- [ ] While adjusting a route, tap a road with "+ Stopp": the bar now has Hjelpepunkt (white dot),
  På veien and Nytt mål, and a ✕ to drop a mistaken tap. Check it fits the Pixel in portrait.

## 2026-10-17 — GPX track or ride as a route

- [ ] Open a GPX track (GPX → track → follow/preview) and tap the new "Change it like a route"
  button next to the reverse arrow. It should become an ordinary trip from the track's start to
  its end with stops along it ("Adjust route" in the sheet), keep to the track's roads and stay
  on the map while it recalculates. Drag the line, add a stop, then save it as a GPX track.
  Check a loop track (start = end) and a long one (200+ km). Same from Rides → Ride again.

## 2026-10-17 — Recording dot, stop button and merging rides

- [ ] Ride (or demo ride) for a minute, then stand still: after about 5 seconds a red dot should
  appear on the GPX button in the rail, and in the GPX dialog on the Rides tab. It goes when you
  ride off, and when the ride ends. Check that it overlaps nothing on the X7 and on the phone,
  in both orientations, while navigating and not, with the music player on and off.
- [ ] GPX → Rides, while recording: "Stop" in the recording card ends the ride at once and it shows
  in the list. Riding on does not start a new one until you have stood still once.
- [ ] Rides → select two or more (long-press, or the select button): a merge button appears. After
  confirming, one ride remains with the combined distance and time, running from where the first
  began to where the last ended; open it on the map ("Ride again") and check the track is whole.
  GPX copies already saved by auto-save stay (as with deleting).

## 2026-10-17 — Country roads in the palette colour

- [ ] Settings → Display → Road surfaces: pick Orange, Yellow or Green. The small country roads
  (the thin ones that string gravel together) should show as a solid line in the gravel colour
  with a dark edge, not dashed, from zoom 11 (10 with Map detail "Detailed") until about zoom
  15. In a housing estate the streets should stay plain white as before. With "Quiet" nothing
      changes. Is the line too thick or too loud between the dashes? Say so and the widths change.

## 2026-10-17 — Map detail (gravel and small roads further out)

- [ ] Settings → Display → Map detail, switch to "Detailed" (the default is "Fast"): gravel and dirt dashes and the small
  tarmac roads between them show from zoom 10, with the gravel heat gone by then. Pan around a
  gravel-rich area (Telemark, Hardanger) on the X7 and watch for lag or stutter; if it is slow,
  switch back to "Fast" (zoom 11, as before) and tell me, and the zoom can change.
  Roads leading to gravel (the thin ones up a driveway) keep showing from 11 either way.

## 2026-10-17 — Map stays put, adventure time is a ceiling

- [ ] Planning a trip, editing a route, cancelling a preview: pan/zoom the map somewhere else. It
  must not jump back to you after the preview/edit/pick ends; only starting navigation or the
  "back to me" button moves it. Drawing an adventure area zooms out where the map already is.
- [ ] Riding with places on the map (Settings → Display → On the map: speed cameras, fuel, …): they
  should show while following the rider in 3D too, also at road speed. Before, the size of a 3D
  view's bounds (reaching the horizon) switched every kind off. If still nothing: `adb logcat -s
  Curveon:I | grep "Map places"` says how wide the map counts as, and which layers were on.
- [ ] Long tunnel (a 2 km straight piece in the route, or 20+ km): the rider keeps moving along the
  route with the "estimating" note the whole way, with no time limit; on coming out the real GPS
  takes over, navigation is still running and the 3D view is still there. (The 17 Oct trip showed
  the cause: the tracker got stuck at the end of a 2 km piece of the route and called it off
  route; fixed, with a test.) If it still goes wrong, pull the navtrace
  (`adb pull .../files/navtrace`): look for "route cleared" and rows with `est` = 1.
- [ ] A give way / stop ahead while navigating and while riding freely: its sign and the distance
  count down in the road card (with the road number and priority sign), and no longer under the
  speed. Check the card shows up on its own when the road has no number.
- [ ] Hold the 3D/N button: the map goes to 3D and + / − appear beside it (6 s after the last press
  they go again). + looks more down on the map, − lower along the road; the angle is kept (also
  after restarting the app) until changed. Tapping the button still cycles N → heading → 3D.
- [ ] Zoom in (11 or closer) on a gravel or dirt road with a navigation route or a GPX track on top
  of it: the dashes now show through the line (dark edge, in the colours picked in Settings →
  Display → Road surfaces). Check it reads on both themes, and that turning road surfaces off
  removes them from over the lines too.
- [ ] Navigation sounds on, ride off the route so the "turn around or go on" dialog appears: a
  bell (two strikes) rings, clearly different from the soft off-route tones. Listen to it in the
  helmet through the intercom too: loud enough, not cut off at the start.
- [ ] Adventure over the fjells east of Stord (start Bremnes, to Tveit, ring over Stord/Kvinnherad,
  6 h): the trip should now go over the mountain roads east of the fjord instead of circling
  once on the west side, and planning ("Finding roads…") should be quicker than before.
- [ ] Adventure planning: a first trip should appear after a few seconds, with a small spinner and
  "Looking for a better trip…" (pulsing) under the title while it keeps searching. Each better
  trip found is added as a chip "Trip 2 · 5:50 h · 290 km" next to "Trip 1", and drawn grey on
  the map. Tap the chips to switch back and forth (nothing is forgotten). Change the trip (drag
  it, add a stop, shuffle) or close the sheet while it searches: the search stops. Check the chips
  and the spinner don't overlap anything on the phone, portrait and landscape.
- [ ] Draw an adventure area and ask for a long time: the trip may now be shorter than asked (with
  a note "The trip takes … of the … you gave it") instead of filling the time with dead ends.
  Check that planning takes no longer than before, and that the trip has fewer spurs.

## 2026-10-16 — Stop sharing ends the link at once, Bluetooth tip, give way off a priority road

- [ ] Companion linked to the X7: tap Stop sharing on the Pixel. The X7 says the phone isn't
  answering within a few seconds, and Play on the X7 no longer starts Spotify on the Pixel.
  Start sharing again: the X7 links up again.
- [ ] Phone not answering (Settings → Phone on the X7): the hint also says to turn Bluetooth off
  and on on the tablet and the phone if it keeps failing (English and Norwegian).
- [ ] Planned route turning left from Stavlandsvegen onto KV1030 (Svortland): no give way sign at
  the turn.

## 2026-10-16 — Night rider marker orange, voice heads-up choice, offline voice tip

- [ ] Night map: the rider arrow, its ring and the edge glow are orange; the cyan route stands out
  from it. On a gravel (orange) route the arrow stays cyan. Day map unchanged.
- [ ] Settings → Navigation → Sound with Voice: "Forvarsel" switch. Off: only "Sving til høyre …" at
  the turn, no "Om 300 meter".
- [ ] Without an offline voice for the language (e.g. switch Curveon to English if no English voice is
  downloaded), choosing Voice shows an amber tip with where to download it; on Curveon's own device
  also a "Last ned stemme" button that opens the download. Gone once it is downloaded and Voice
  is chosen again.
- [ ] Settings page at phone size (portrait and landscape): the new rows and the tip fit.

## 2026-10-16 — Spoken navigation (Android text-to-speech), tick earlier, new icon

Update Curveon on both devices and the companion on the Pixel.

- [ ] Settings → Navigation → Sound → Voice: says "Om 300 meter, sving til høyre" in the helmet.
  With the X7 linked it comes from the Pixel (`adb -s <pixel> logcat -s Speaker` shows
  "Speaking nb-NO with …-local"); Curveon alone on the Pixel says it too.
- [ ] Offline: on the Pixel, Settings → System → Languages → Text-to-speech → Speech Services by
  Google → Install voice data → Norsk (Bokmål). With it the log says `-local`, and it talks
  without coverage. Without it (and offline) you get the sounds instead.
- [ ] Demo ride with Voice: heads-up "Om … meter, sving … inn på <vei>, deretter …", at the turn
  "Sving til høyre", "Du er fremme". Words clear through the intercom? Too long, too early, too late?
- [ ] Sounds: does the tick now come before you start turning (it is 2 s earlier)?
- [ ] New launcher icon (cyan tyre) on both devices and on the companion.

## 2026-10-16 — Navigation sounds and wrong-turn sounds

Curveon must be updated on both devices, and the companion on the Pixel (older companions ignore the sounds).

- [ ] Settings → Navigation → Sound: choosing "Sounds" plays the heads-up in the right ear, turning
  "Wrong-turn sounds" on plays the low off-route sound. In the helmet (via the phone when the X7 is
  linked, straight from the phone when Curveon runs on it alone).
- [ ] Riding (or demo ride): two soft notes ~12 s before a turn and a tick ~3 s before it, in the ear
  of the turn. Too early, too late, too loud, too quiet? Music dips briefly rather than pausing.
- [ ] Ride off the route: one low double sound, a light one when the new route is ready or you are
  back on it, and a "question" sound when Curveon asks whether to turn back.
- [ ] Intercom: is the start of the sound cut off when nothing else is playing? (There is 0.3 s of
  silence first for the headset to wake up.)
- [ ] During a phone call: no sounds.
- [ ] Listen on the PC first if you like: `~/develop/Curveon-data/cue-sounds/*.wav`.

## 2026-10-16 — Map drawn with OpenGL instead of Vulkan (Pixel crashes)

- [ ] Pixel: use the map as when it crashed today (pan, zoom, plan, navigate a while). No more
  crashes; `crashes/` on the device stays without new `-native` reports.
- [ ] Both: the map looks and moves as before (labels, route line, surface dashes, heat map).

## 2026-10-16 — Crash reports kept on the device

- [ ] After a crash, open Curveon again, then
  `adb pull /sdcard/Android/data/com.curveon.app/files/crashes`: a `.txt` per crash with
  the time, version and stack (a native one also has a `.pb`). Tell Claude, who can read them.

## 2026-10-16 — Planning on the map: help bubble, padlock on by default

- [ ] Point by point (or Pick on map while planning): a help bubble over the bar explains tapping,
  the padlock, the dotted-line button and undo/done. OK closes it until the next time;
  "Don't show again" for good.
- [ ] The padlock starts closed (the line can't be dragged by accident) and remembers what you chose.
  The bar now says "Tap to add a point; the line stays put" instead of "Route locked".
- [ ] The flame (gravel heat map) button is gone until Settings → Display → Gravel heat map is set
  to "While planning" or "Also on the map" (default Off).

## 2026-10-16 — Gravel colours and the gravel heat map

- [ ] Settings → Display → Road surfaces: pick Orange / Yellow / Green; the dashes on the map
  change colour (and get a bit wider). With "Only while planning" on they stay quiet until a
  trip is being planned (preview open, picking places, drawing a roundtrip area).
- [ ] The flame button (gravel heat map) sits top left on the map, on the Pixel in landscape too
  (it used to overlap there). Zoom out to ~50–200 km across: warm where there is much gravel;
  it fades out as the dashes take over around zoom 11–12.
- [ ] Settings → Display → Gravel heat map: "While planning" shows the button only while
  planning; "Also on the map" shows it on the ordinary map too, but not while navigating or
  riding (gone from 10 km/h, back once stopped).
- [ ] Zooming in from ~zoom 10: as soon as the gravel dashes appear (zoom 11), the small tarmac
  roads leading to them are drawn too (white with a grey edge), so gravel roads no longer seem
  to start nowhere. Try a spot like the KV 10122 one.
- [ ] Access/private roads ("Adkomst- eller privatvei", e.g. by Jerstad towards Heimrehølen) that
  lead to a gravel road now show with it (until ~zoom 15.5, where the map draws them itself).
  Driveways that lead to no gravel stay out.
- [ ] First time per 5°×5° routing file the note "Counting gravel here (once)…" shows; on the X7
  that took ~50 s per file. Afterwards it is instant (cache in `files/gravel-heat/`).
- [ ] Abroad (a country downloaded under Maps & data): the heat map works there too. Where no
  routing data is on the device it says so instead.
- [ ] While a file is being counted, the dashes still load when zooming in (they have their own
  reader now).

## 2026-10-16 — Give way signs without a route, and standing on the map

Riding without a route, Curveon looks ~150 m along the road you are on (a short route straight
ahead, every 2 s from 10 km/h) and, if the next junction is one where you give way, shows its
sign and the distance in the road card (top middle, by the road number and priority sign; shown
even with Road info off). Standing still at the junction keeps it. The sign also stands on the map,
on the right of the road ~14 m before the junction: the one just ahead when riding freely, and
those on the next kilometre of the route when navigating. Checked in the emulator with GPS fixes
along Kv1164 onto Fv541 (Bømlo): 90 m, 70 m, 40 m, then gone on Fv541; the sign on its pole by
the junction.

- [ ] Real ride without a route: out of a side road onto a bigger one, the sign shows in time
  (~100 m before) and goes once you are through.
- [ ] Riding along a main road past side roads: nothing shows.
- [ ] Where the road forks or bends hard just ahead, a wrong sign may show (the look-ahead goes
  straight on): note where.
- [ ] Heading-up and 3D map: the sign on the map stands upright beside the road.

## 2026-10-16 — Give way and stop signs while navigating

Navigating in Norway, a junction where you give way (or must stop) shows its sign under the speed,
with the distance, from 200 m before it until you are there. Two sources, both in the new
norway.sqlite from NVDB: the signs themselves (202 Vikeplikt, 204 Stopp, ~67 000), those facing
traffic on the road you ride and those for a side road you come out of; and turning onto a
priority road (forkjørsveg) out of another road, since such side roads often have no sign. Riding
straight across a priority road is left out (can't be told from a bridge over it), as are private
roads and junctions under the right-hand rule. Checked in the emulator (demo ride, Bømlo): Kv1164
onto Fv541 shows the triangle with the same distance as the turn card, gone once on Fv541.

- [ ] Real ride with a route: out of a side road onto a fylkesveg, and into a roundabout: the
  triangle shows ~200 m before and goes away at the junction.
- [ ] Out of a side road onto a priority road (the yellow diamond shows once on it): the triangle
  before the turn, with the same distance as the turn card.
- [ ] Riding along a priority road past side roads: no sign (those are for the side roads).
- [ ] Turning off a main road into a side road: no sign (its sign faces the other way).
- [ ] Any sign shown where there is none, or missing where there is one: note the place (or send
  the nav trace) so the direction rules can be tuned.

## 2026-10-15 — Point by point: start where the finger was held, or from my position

A finger held on the map → Point by point now asks first: **Start here** (the trip starts at that
place; nothing is routed from where you are, e.g. planning a trip abroad from home) or **From my
position** (as before). Checked on the X7: Start here put A on the held place, the next tap
planned from there ("From Søvoll").

- [ ] Pixel: hold a finger far away (another country), Point by point → Start here, tap a few
  points: no routing from where you are, A stays on the first place.

## 2026-10-15 — Open GPX files from other apps; tidy waypoint cards; track name on a held road

- Curveon is offered for GPX files opened or shared from other apps. Checked on the X7: a GPX in
  Downloads opened with Curveon (Android calls it `application/octet-stream`) was imported and
  GPX & rides opened with it at the top. Curveon may now also be offered for other binary or XML
  files; those just say there is no track in them.

- Waypoint cards on a phone upright: the text goes above the facts instead of beside them (it got
  almost no width there and grew very tall), and empty lines in the text are dropped.

- A group's colour choices: "each track its own colour" is always the many-coloured ring (checked
  on the X7 on an all-blue group).

- A finger held on a GPX track: the card names the track (with its colour) under the road.

- [ ] Pixel: open a .gpx from Files, Gmail and Drive → "Open with Curveon" → imported, and GPX &
  rides shows it. Share a .gpx to Curveon too.

- [ ] Pixel upright: tap Reinheimen Lodge (TET N-13 Lom-Grotli): the card is only as tall as its
  text, with the text above the facts.

- [ ] Hold a finger on a road under a shown GPX track: the card names the track.

## 2026-10-15 — Halo round the rider arrow: how sure the GPS is

A see-through disc under the arrow, as wide as the GPS's accuracy: normally a small ring just round
the arrow; wider where the fix is poor, and growing in tunnels while the position is guessed (up
to 300 m). Cyan on the night map, the route's blue on the day map. Looked at on the X7, standing
still, in both. Installed on the X7 and the Pixel.

- [ ] Riding, day and night: the ring is easy to see but doesn't hide the road under the arrow.
- [ ] 3D: the halo lies flat on the road (an oval), centred on the arrow.
- [ ] Through a tunnel: the halo grows while dead reckoning, and shrinks once GPS is back.
- [ ] Poor GPS (deep valley, town): the halo is visibly bigger.

## 2026-10-15 — Curveon sleeps out of sight; the trip being planned is kept

Out of sight with no trip being navigated (another app, screen off), Curveon now sleeps: no GPS,
no "Curveon" notification, no phone link or OBD, no lookups. Checked on the X7: 0 CPU ticks in
30 s hidden (2791 on screen), no GPS request, service gone; all back on return. The trip being
planned is kept on disk and comes back even if Android ends Curveon (checked on the X7 with a
force-stop: same stops, start and road style). Installed on the X7 and the Pixel.

- [ ] Pixel: leave Curveon (no trip) for a few hours, then Settings → Battery → Curveon: next to
  nothing in the background, and no "Curveon" notification while it is hidden.
- [ ] Navigating with Google Maps (or another app) in front: Curveon keeps navigating, notification
  shows; once the trip ends (arrived or stopped) the notification goes away.
- [ ] Plan a trip, go to e-mail, come back: the plan is there. Same after a long time away.
- [ ] Back on screen: GPS fix, phone link (music, messages) and OBD come back within seconds.
- [ ] Downloading a map and leaving Curveon: the download finishes.

## 2026-10-15 — Route preview: zoom-to-trip button and gravel detour menu

- [ ] Plan a trip on the Pixel (upright) while on a numbered road: the zoom-to-trip button sits
  under the road number card, not behind it.
- [ ] Pick Grus: the chip beside it reads "Omvei inntil 5 km" / "Omvei inntil dobbel lengde"; its menu starts with
  "Hvor langt fra vanlig rute for å finne grus?" and lists "Inntil 1 km" … "Inntil dobbel lengde".

## 2026-10-15 — Settings: choices as drop-down buttons on the right

- [ ] Settings → Display and Navigation, on the X7 and on the Pixel (upright and sideways): every
  choice (language, km/h/mph, screen, map, road surfaces, rider arrow, off route, carry on…) is
  a button on the right, in line with the switches, with title and hint on the left.
- [ ] Each button is as wide as its longest choice: picking another doesn't change its width, and
  no text is cut off (try Norsk and English).
- [ ] Tapping opens the list, the chosen one is cyan with a tick; picking one closes the list.

## 2026-10-15 — Curveon on GitHub in Settings → Help

- [ ] Settings → Help → Curveon on GitHub opens the GitHub page in the browser.

## 2026-10-14 — Updates from GitHub, and What's new

- [ ] First start of the new build on the X7 and Pixel: "New in Curveon 1.1" opens once, and not
  again on the next start.
- [ ] Settings → Help → Look for updates: "This is the newest version" (1.1 isn't on GitHub yet).
- [ ] Settings → Help → What's new lists 1.1 and 1.0.
- [ ] When 1.2 is out (1.0 has no updater, so 1.1 → 1.2 is the first real update): on a device with
  the GitHub 1.1 APK, the startup check shows a toast and a dot on Settings, Settings opens on
  Help, Download and install works, and What's new shows 1.2 after the restart.

## 2026-10-14 — Very curvy no longer takes the Hurtigruten

- [ ] From Bømlo, destination Florø, Supersvingete: the route rides up the coast (about 390 km,
  a few short fjord ferries), not to Bergen and the Hurtigruten from there.

## 2026-10-14 — Lock the route while editing on the map

- [ ] Juster ruten → Endre i kartet: a lock button (open padlock) sits beside Undo. Press it: it
  turns amber, the hint says "Ruta er låst: trykk for nytt punkt".
- [ ] Locked: pan with a finger that starts on the route line or on a point: the map moves, the
  route doesn't bend or move. Taps still add a point; a tap on a point still opens its menu.
- [ ] Two fingers (zoom) starting on the line still zoom. Unlock: dragging the line works as before.

## 2026-10-14 — Music on the full-screen map can be switched off

- [ ] Settings → Display → "Musikk på kartet" off: the player no longer shows in the corner of the
  full-screen map (and the map buttons/turn card use the room); on again: it is back.

## 2026-10-14 — Road number sign when holding a finger on a road

- [ ] Hold a finger on a numbered road (e.g. an E-, riks- or fylkesveg): the road info in the
  dialog shows the number sign (green/white as in the turn card) next to the speed limit, and the
  street name beside it instead of "Fv63 Storgata".
- [ ] On a forkjørsveg the yellow priority diamond shows too; on an unnumbered road just the name as before.

## 2026-10-14 — "Point by point" from a long-press on the map

- [ ] With no trip planned and not riding, hold a finger on a road: the dialog has a third button,
  "Punkt for punkt" (next to "Kjør hit" and "Lagre som favoritt").
- [ ] Press it: the map goes full-screen with the point-by-point bar, and the trip from here to that
  point is found. Tap further along: each tap rides the trip on to there; Undo takes the last back.
- [ ] ✓ ends on the ordinary route preview with all the points as stops, where the route can be
  adjusted and started as usual.

## 2026-10-14 — "Ride again" on a loop from home

"Ride again" on today's 32 km loop (on the Pixel) offered only the last ~4 km: the loop passes home
again after ~18 km, and the trip joined the track there. It now joins where the track first passes
you. Installed on the X7 and the Pixel:

- [ ] At home, Rides → today's Svortland loop → "Ride again": the preview should be the whole loop
  (~32 km), not a few km. Try "Ride the other way" too.

## 2026-10-15 — Rider arrow: +1.5 s ahead of the GPS by default

Testing on the road, +1.5 s put the arrow where the bike is (+1.75 was close; +0.25 left it behind at junctions).
That is now the standard, and the setting stays (Settings → Navigation → "Rider arrow", no longer
"test"). A value already picked by hand is kept; only devices still on the old standard move.

- [ ] At 40 and at 80 km/h, passing a junction: the arrow passes it with you.

## 2026-10-14 — Road number signs and priority roads

Installed on the X7 and tried on the demo ride with a Vestland-only data file
(`speedlimits/zz-test-vestland.sqlite`, deleted from the X7 on 2026-10-16): "Fv4998" and
the priority road sign in a card of their own (top middle in landscape, under the speed in
portrait); in the turn card "270 m [Fv5012]". Settings → Display → Road info hides and shows them.

- [ ] Real ride: the number by the speed changes as you turn onto another road, and the priority
  sign shows on forkjørsveg and goes away off it (within ~2 s).
- [ ] An E-road and a riksveg show green signs (E39, Rv…), fylkesveg white, kommunal veg grey.
- [ ] Abroad (or without the new data file): the number comes from the map instead, in the
  country's colours; no priority sign.

## 2026-10-14 — Battery test: Google Maps against Curveon (Pixel)

Installed on the Pixel (Companion and Curveon). Tried on the X7 (faked unplugged): Google Maps'
navigation and Curveon's are each recognised, start and end.

- [ ] Pixel unplugged, same screen brightness both ways (auto-brightness off): Companion → Battery
  test → Start. Ride A→B navigating with Google Maps, B→A with Curveon, then Stop. Companion shows mAh and mAh/h per app.
- [ ] Afterwards, for a closer look: `adb pull /sdcard/Android/data/com.curveon.companion/files/batterytest/`
- [ ] Close the other app while one navigates (Curveon via "Close Curveon" in its notification).

## 2026-10-14 — Less battery

Installed on the X7 and measured there (CPU per thread, same spot of the same demo ride, old vs new):
standing still the app went from ~23 % of a core to ~5 %; riding, the main thread from ~67 % to
~47 % (the map's own drawing, ~75 %, is unchanged). Still to see on a real ride:

- [ ] Ride with the map following: it should glide exactly as before, also pulling away from a stop
  (the rider loop now sleeps while the bike stands still and nothing changes).
- [ ] The road name after the next turn, the online map where no downloaded map reaches and the
  gravel roads still appear while riding (they are now looked for once a second instead of on
  every frame).
- [ ] Battery over an hour's ride, compared with before:
  `adb shell dumpsys batterystats --charged com.curveon.app`.

## 2026-10-14 — Pins A/B, save dialog, Planned looks like Rides

Installed on the X7 and the Pixel. Scrolling and the Planned page were checked on the X7 sized like the Pixel held sideways; the map pins and the save dialog were not seen.

- [ ] Plan a trip: start = green A (only when planned from elsewhere), stops = orange 1, 2…, destination = red B. A planned trip ridden the other way: A at its new start, B at the end.
- [ ] Bookmark a route: "Save route" shows Planned trips and GPX, both unticked; pick a group for each; save to either or both.
- [ ] Rides: open a ride: one "Save…" button (same dialog).
- [ ] Planned: a thumbnail of the line and the km; tap for time, climb and curves; Ride / Ride the other way / Save to GPX.
- [ ] Sideways on the Pixel: GPX, Rides and Planned scroll as one list (the switches and chips scroll away).

## 2026-10-13 — One frame for GPX / Rides / Planned and Phone; planned trips in groups

Installed on the X7 (landscape checked there, and upright via `wm size 1080x2400`); the Pixel was not reachable.

- [ ] Pixel upright: open the route icon (GPX): one frame with three tabs (GPX, Rides, Planned); swipe sideways between them (the tab lights up). Same in Settings and Phone (also swipeable now).
- [ ] Planned: your saved routes are here. ⋮ → Move to group → new group ("Trips for the weekend"); the group opens and closes; ⋮ on a group renames, ungroups or deletes it.
- [ ] Planned: the arrow on a trip turns amber = ride the other way round; the cyan button then plans it reversed (as a line, without its stops).
- [ ] Rides: open a ride: "Ride the other way" and "Copy to planned" (pick or create a group); the copy appears under Planned.
- [ ] The orange destination pin stays while an adventure is drawn and worked out.

## 2026-10-13 — Adventure routing (new)

Installed on the Pixel and the X7.

- [ ] Search a destination, then in the route preview tap **Adventure** (next to "From here"). Set the
  time and surface in the bar, draw the area to ride in with one finger. Expect an "Adventure to …"
  route taking about the asked time, inside the area (wait ~10–40 s for long ones).
- [ ] In the adventure preview: time chip (changes the time), **Draw again**, shuffle (another route),
  start chip (plan from elsewhere), surface chips, GO. Check `adb logcat -s CurveonPlan` for the
  candidates it tried.
- [ ] Too little time (less than the quickest way): expect the message with the quickest way's minutes.
- [ ] Roundtrip (draw area from "Where to?") works as before.

## 2026-10-13 — Motorway choice with icons (route preview)

Installed on the Pixel only.

- [ ] Tap the motorway chip: the list shows the blue motorway sign, the same crossed out, and the
  green E sign crossed out.
- [ ] "No motorways or E-roads": the chip shows the green E sign crossed out; "No motorways" the
  blue motorway sign crossed out; "Motorways OK" the plain blue sign.

## 2026-10-13 — Ride log: save-rides switch and grouping by time

Installed on the Pixel only.

- [ ] Rides dialog: "Save new rides" off, ride a short stretch: it must not appear in the list, and
  nothing lands in the GPX group. The GPX auto-save row is greyed out.
- [ ] Switch it off in the middle of a ride: that ride still finishes and is saved.
- [ ] "This week" is open, "Last week" and the months are closed; headers show rides and km.
- [ ] Select all + delete also takes the rides in closed groups.

## 2026-10-13 — Ringing call big, answered call a small icon you can move

Needs the new Curveon Companion on the Pixel too (it says whether a call has been answered; with an
old one an answered call looks like it is still ringing). Installed on the X7 only; never tried with
a real call.

- [ ] Ring the Pixel: a big card with the caller's photo, name, red Decline and green Answer, over
  everything (full map, dashboard, route preview, an open screen, portrait).
- [ ] Answer: the card becomes a green phone icon, on every view. Drag it anywhere; it stays put for
  the next call. Tap it: name, Hang up and Speaker for a few seconds. The music card stays.
- [ ] If the Answer button is still there after answering, the Pixel runs the old companion: install
  `./gradlew :companion:installDebug` on it.

## 2026-10-13 — Calls as a slim card and a floating icon

Needs the new Curveon Companion on the Pixel (it now says whether a call has been answered) and the
new Curveon on the X7. Both were installed; the X7 and the Pixel were never connected to each other
while testing, so none of this has been seen with a real call.

- [ ] Ring the Pixel with the X7 on the full map: a slim card (small picture, name, red Decline,
  green Answer) in the bottom right, not the big photo. In the dashboard view the same slim card
  sits on top of the right-hand column, with music or messages below it.
- [ ] Answer: on the full map the card becomes a green phone icon. Drag it: it settles on the left edge
  or just left of the map's buttons, and sits there for the next call. Tap it: name, Hang up and
  Speaker open for a few seconds. It stays visible with Settings, GPX or Phone open.
- [ ] Call placed from the X7's Phone screen: same icon once it connects.
- [ ] Android below 12 on the phone (if any): an answered call may still show as ringing; say so.

## 2026-10-12 — Corners, panels over the map, swipe, new menu icons

- [ ] X7 riding (not the demo ride): the trip bar sits top left, the speed top right, the next turn
  bottom left, music bottom right (full map), and the map's buttons keep clear of all four. A
  call on the full map takes the bottom right, left of the buttons.
- [ ] Pixel sideways and upright (Curveon on the phone): the same, with nothing over the +/− and
  3D buttons; the map's (i) licence button sits halfway up the left edge and opens its text.
- [ ] Rail: the open screen's button is blue; pressing it again closes the screen, pressing another
  one swaps it in; Back closes it; the keyboard in "Where to?" doesn't hide the field.
- [ ] Upright (X7 or Pixel): Settings and GPX & rides swipe between their pages, and the tab row
  follows. Sliders and horizontal lists inside the pages still scroll without changing page.
- [ ] Welcome tour (Settings → Help): Where to?, Plan a trip and Roundtrip light up the motorcycle
  button and say what to press first.

## 2026-10-11 — New app icons (motorcycle on the curve)

- [ ] Pixel: Curveon and Curveon Companion (the cyan phone with the new art on its screen) show
  the new icon in the app drawer; the round mask keeps both mirrors. With Themed icons on
  (wallpaper settings), both show a clean one-colour version.
- [ ] X7: install Curveon there too (it wasn't reachable) and check the icon on its launcher.

## 2026-10-11 — Day road colours on the night map

- [ ] Night map: roads have the day map's colours (white streets, yellow main roads and motorways)
  with the same outlines. Too bright to ride by at night? Then say so; the old night colours are
  in git (`app/src/main/assets/map/styles/dark.json` before this commit).

## 2026-10-11 — Speed limits without a map

- [ ] Pixel (no maps installed), online and with a GPS fix: within a couple of minutes
  `adb -s <pixel> shell ls /sdcard/Android/data/com.curveon.app/files/speedlimits/` shows
  `norway.sqlite` (the country it is in); the limit sign then shows on roads, e.g. in the demo ride
  or "Hold a finger on a road".

## 2026-10-11 — New layout of the cards over the map

- [ ] While navigating, full map: the next-turn card sits bottom right beside the music player, both
  the same height and not overlapping; the time-left bar is top, beside the speed and as high.
- [ ] Dashboard: the same top bar (arrival, km · time, X); tap it and a second line folds out below
  with next stop (tap: route menu), fuel and add stop. The turn card is bottom right of the map.
- [ ] 3D / heading up: the arrow stays clear of the turn card (it sits a bit higher than before).
- [ ] Phone (portrait and landscape): bar and speed fit side by side; player goes above the turn card.

## 2026-10-11 — No U-turn on E39 at Heiane (Stord)

- [ ] Plan a trip to McDonald's Stord from the south-west (say from Føyno): the route should take the
  exit to the right off E39, the roundabout and the bridge, with no U-turn on E39. Worth a
  look at other motorway exits too: they should look as before.

## 2026-10-11 — Welcome tour texts and drawings

- [ ] Settings → Help → Start the tour (the Pixel needs the newest install first): the phone step
  says a tablet that can't call needs Curveon Companion on the phone; Settings says offline
  maps, phone link and more; the map step only mentions holding a place; after music and
  messages comes an engine-data drawing; "Change just one part" ends with a dashed line being
  pulled out, as when editing on the map.

## 2026-10-11 — Full map or dashboard per orientation

- [ ] On the phone: in portrait pick the full map, in landscape the dashboard. Rotate back and
  forth: each orientation returns to what it last had. Restart Curveon: same again.

## 2026-10-11 — Map view remembered per mode

- [ ] Fresh (or after clearing settings): the map without a trip is north up; start a trip and it
  goes to 3D. Stop the trip: back to north up.
- [ ] Press the compass button during navigation (say to heading up), stop and start another trip:
  heading up again. The choice made while browsing the map is kept separately.

## 2026-10-11 — Demo mode

- [ ] Settings → Display → Demo ride: switch it on, plan a trip and press Go: the ride starts by
  itself as a simulation, from the trip's own start point if you set one (not from where the
  tablet is). Switch it off: back to real GPS. Switched on during a trip: that trip is
  simulated from here.

## 2026-10-11 — Places on the map

- [ ] Settings → Display → On the map: switch on Fuel, Speed cameras, Viewpoints, …; zoom in on a town
  you have a map of: the places show with icons and names (fuel green, cameras red, ferries grey),
  tapping one opens its card. Riding, they should keep appearing ahead without the map stuttering.
- [ ] Switch them off again: the map is clean as before.

## 2026-10-11 — Welcome tour

- [ ] Start Curveon on the X7: the welcome tour opens by itself (once). Walk through it: the lit
  ring should sit neatly round each rail button, the map buttons, the speed and the cards, and
  every card should fit on screen (also the long "Planning a trip" one).
- [ ] First start (clear the app's data to see it again): the tour opens with Language · Språk, and
  carries on in the language you tap. Six small animations (tablet–phone–intercom, just a phone, plan, edit on the map, mark one part
  with the three-dots button, draw a roundtrip) loop without text.
- [ ] Settings → Help → Start the tour: it starts again; the symbol list below reads well.
- [ ] On the Pixel held upright: the tour turns the screen to landscape while it is open, and back
  to upright when it closes.

## 2026-10-11 — Official speed limits; road info when holding a finger on a road

**Official speed limits.** Where a country publishes them freely, the speed sign now shows the
official limit of the road you're on and falls back to OpenStreetMap elsewhere: Norway (Statens
vegvesen NVDB, public roads), Finland (Digiroad), the Netherlands (NDW/RWS WKD), Flanders (AWV).
Built monthly by the curveon-data repo (`speedlimits` release, ~7 MB for Norway); the tablet
fetches the file for each country it has a map of, with the map, or by itself ~20 s after
starting when online. Credits under Settings → Maps & data → Speed limits. Around Bømlo/Stord/
Fitjar OSM has no limit on 47 % of kommunal veg and differs on ~5 % of main roads; the official
file gave the right limit at all 1 497 test points.

**Road info.** Holding a finger on a road shows, in the card that opens, its speed limit sign,
name/number, kind (main road, country road, track…) and surface, and whether the limit is
official or from OpenStreetMap. Information only.

- [ ] X7/Pixel online: after a start, Settings → Maps & data → Speed limits says "Norge: Statens vegvesen, NVDB …".
- [ ] Riding on a kommunal veg that showed no sign before: does it show one now, and is it right?
- [ ] Hold a finger on a few roads (main road, gravel road, track): is the info right?

## 2026-10-11 — Gravel reach when drawing a loop

With Gravel or Offroad in the drawing bar, the "Gravel within N km / anywhere" chip is there too.
The loop's shape is found with gravel's full pull; the reach applies when it is ridden as a trip
through its points (each stretch between two points keeps that close to the ordinary road), and
can be changed on the sheet afterwards. Checked in the emulator.

- [ ] Draw a gravel loop inland with "within 3 km": less gravel than "anywhere", but some?

## 2026-10-11 — Brighter roads on the dark map

Roads on the night map were barely lighter than the land (#333–#474 on #1f1f1f). They are now
clear greys, brightest for the biggest roads (small #4b–#5f, main #6f, motorway #8a), tunnels a
step darker, edges darker than the land; background, water and buildings unchanged. Checked in
the emulator.

- [ ] Riding at night: are the roads easy to tell from the land now, without glare? The blue route still the brightest?

## 2026-10-11 — No motorways or E-roads

The motorway chip is a list: Motorways OK / No motorways / **No motorways or E-roads**. The routing
data has road classes, not numbers, so "E-roads" means trunk roads: in Norway the E-roads and the
biggest national roads (Rv 7, Rv 13 where tagged trunk); still ridden where there is no other way.
Haugesund–Bergen fastest: 61 km on trunk → 6 km (139 → 165 km). Checked in the emulator.

- [ ] A trip you'd normally get E39/E134 on: does it keep off them, and is the detour sensible?

## 2026-10-11 — Rides keep their times in GPX (for lining up with video)

A ride saved to the GPX collection now keeps when each point was ridden, and exported GPX files
have `<time>` (UTC, tenths of a second) on every track point and in the metadata, so video tools
can line the track up with a film. Rides are recorded to a tenth of a second from now on (older
rides have whole seconds; saving one of those to GPX again gives it times too). Times in imported
GPX files are kept and written back out; a track exported reversed gets none. Unit tested; installed on the X7.

- [ ] Save a ride to GPX, export it, open in a video/GPX tool (e.g. GoPro Quik, Telemetry Overlay): the times match the ride.

## 2026-10-10 — Altitude (m a.s.l.) under the speed, optional

Settings → Display → **Altitude** shows the height above sea level under the speed (off by
default). It comes from the GPS receiver's own NMEA (GGA, height above sea level); Android's
altitude is above the ellipsoid, ~40 m higher in Norway, and is only used (or Android 14's sea-level
height) when the receiver sends no NMEA. Recorded rides now get sea-level heights too. Checked in
the emulator; installed on the X7 (the Pixel wasn't reachable).

- [ ] Compare with a known height (a summit sign, or the sea: ~0–5 m at the quay) on the X7 and the Pixel.

## 2026-10-10 — Gravel within reach of the route; the route line coloured by surface

With Gravel or Offroad picked, a chip next to it says how far off the ordinary route gravel may take
the trip: within 1/3/5/10/20 km, or anywhere. The ordinary route is planned first, then the gravel
one with less and less pull towards gravel until it stays that close; failing that, the ordinary
route. Gravel itself pulls harder now (before, Gravel barely changed a route): Kongsberg–Notodden
curvy gets 17 km gravel (33 → 43 km, 6 km off); within 3 km it stays on the ordinary road. On the
west coast few gravel roads are tagged in OpenStreetMap, so there it changes less. The previewed
route is drawn orange where it is gravel and purple on tracks. Checked in the emulator; installed on the X7.

- [ ] Gravel near home: does it find any? Does "within N km" behave?
- [ ] Are the orange/purple stretches readable on the X7 in sunlight?

## 2026-10-10 — Max length and surface when drawing a loop; draw again

The drawing bar has **Whole area / Max N km** (50–300) and the surface chip; with a max, the loop is
planned round the area shrunk towards the start, so it stays about that long (tests: max 50 → 48
km, max 80 → 55 km from Bømlo in a big area). The loop's sheet has the same max chip (plans the loop
again, on the road style picked there) and **Draw again** (same start, max and surface). Austevoll
in a drawn area: it is reachable (ferry Sandvikvåg–Hufthamar) but only that way in and out, so a
loop over it rides the ferry and Stord roads twice and loses to loops that don't. Checked in the
emulator; installed on the X7 (the Pixel wasn't reachable).

- [ ] Draw with Max 80 km on the X7: about 80 km or less?
- [ ] Change the max on the sheet, and Draw again.

## 2026-10-10 — Close Curveon from the notification

The "GPS and navigation are running" notification has **Close Curveon**: it closes the screen,
GPS, the phone link and OBD and ends the app. (The 10-minute "still running" warning is gone:
Curveon now sleeps out of sight instead, see 2026-10-15.)

- [ ] "Close Curveon" from the notification: Curveon is gone; opening it again starts normally.

## 2026-10-10 — Roundtrip is drawn straight away; loops ride less road twice

"Roundtrip" in Where to? now goes straight to drawing on the map (the direction/distance dialog is
gone): the bar shows "About N km round" while drawing, and "From …" when a start elsewhere was set.
The loop is planned curvy with the last route options; the sheet then offers every style, ferries
and motorways as for any trip. Loops now steer clear of road they already rode: where the best loop
still rides a stretch twice, those legs are routed again with that road made expensive (allowed
when it's the only way, e.g. off an island). On test loops round Bremnes/Haugesund the share ridden
twice went from 12% to 5% on average; drawn areas on Bømlo/Stord from ~30% to 7–17%. Checked in the
emulator.

- [ ] Roundtrip from home: does it still ride stretches twice? Where (screenshot)?
- [ ] How long a drawn loop takes on the X7 now.

## 2026-10-10 — Route options say what they do

The option chips under a route (and in Roundtrip) were hard to read: a struck-through
"~~Motorways~~" didn't say clearly whether motorways were on or off. They now say it in words:
"Motorways OK" (grey) or "No motorways" (amber, icon crossed out in red); the same for ferries;
the surface chip says "Tarmac only" (then Mixed, Gravel, Offroad). Checked in the emulator.

- [ ] Ask Øystein whether the chips are clear now.

## 2026-10-10 — Favourites: hold a place on the map, keep it as Home or by name

Holding a finger on the map (no trip) now asks: **Go here** (the trip preview as before) or **Save
as favourite** – Home with one tap (a new Home replaces the old), or a name (the place's, to
change). Riding or planning, the same hold also offers it next to stop / destination / start.
Favourites come first in Where to? (Home leads) for the destination, a stop or the start; hold
one to delete it. Checked in the emulator; installed on the X7 and the Pixel.

- [ ] Save Home on the X7, then Where to? → Home plans the way home.
- [ ] A named favourite as a stop while riding (+ Stop → the favourite).
- [ ] Hold a favourite in Where to? → delete.

## 2026-10-10 — Music pill no longer covers the trip bar; no crash turning the phone

In the full-screen map with music playing, the music pill sat on top of the trip bar (arrival,
km and time left) where both don't fit side by side (the phone, upright): it now moves up above
it. Turning the phone while navigating crashed the app (the map read road names before it had
drawn after the turn); it now waits. Checked in the emulator at Pixel size; installed on the X7 and the Pixel.

- [ ] Phone upright, navigating, full map, music playing: the pill sits above the trip bar.
- [ ] Turn the phone while navigating: no crash, the road name after the next turn still shows.
- [ ] The blue route line is thicker when riding (about twice as wide zoomed in, as before zoomed out): easy to follow, not hiding the road names?

## 2026-10-10 — Roundtrip in "Where to?": draw the area, start elsewhere, edit the loop

The Roundtrip button on the rail is gone: Roundtrip is now a row in "Where to?", shown only when
no trip is planned or being ridden. Its dialog has **Draw area**: the map goes flat and zooms out,
one finger draws the area to ride in (two fingers still move the map), and on letting go a loop
inside it is worked out (Shuffle gives another). "From my position" in "Where to?" (or the start
chip on the loop's sheet) starts the loop somewhere else. The pen icon on a loop's sheet turns it
into an ordinary trip with via points about every 8 km, to drag and change like any other.
Update: a found loop now *is* such a trip straight away – the same sheet as "Where to?" (start,

+ Stop, the via points, Fastest/Curvy/Super curvy, edit on map), titled "Roundtrip from …", with
  Shuffle for another loop; changing the start plans a new loop from there.
  Checked in the emulator (Haugesund, Leirvik); installed on the X7 and the Pixel.
- [ ] Draw an area with a finger on the X7: the stroke follows the finger, two fingers pan/zoom.
- [ ] How long a drawn loop takes on the X7 (`adb logcat -s Curveon CurveonPlan` → "Routed Roundtrip in … s"; 1–15 s on the PC).
- [ ] Draw round Bømlo/Stord from home: does the loop use the area and stay off dead ends?
- [ ] A loop's sheet looks like a planned trip; drag a point on the map, the rest of the loop stays.
- [ ] Shuffle on a loop (also after changing road style) gives another loop.

## 2026-10-10 — The map stays put while planning; a button shows the whole trip

Planning a trip no longer zooms the map out by itself each time the route changes (picking places,
stops, detours, route options): it stays where you put it. The new button top right (four arrows,
under the speed) fits the whole trip, like zoom extents. Only when a trip first appears and isn't
already in view is it fitted once; your own position is only included when the trip starts near
you. Checked in the emulator; installed on the X7.

- [ ] Plan a trip far from where you are, pick places on the map: the map doesn't jump.
- [ ] The four-arrow button shows the whole trip above the sheet.
- [ ] The X on a planned trip asks first: Keep / Save / Discard.

## 2026-10-10 — Rider glides on the map, quicker off the line after a wrong turn, new 3D, ride trace

The arrow and the map now move every frame (carried forward between the once-a-second GPS fixes,
plus ~0.25 s for the receiver's delay) instead of easing to each fix a second late. Taking another
road lets go of the route line after ~7 m when heading away from it (before: only once the
navigator called it off route, 40 m+ for 3 fixes). 3D: tilt 50° (was 60°), rider ~3/4 down the
map, zoom 17.2 at 20 km/h → 15.6 at 90, in a bit when a turn is under 300 m away. Installed on the
X7 (the Pixel was offline: `ANDROID_SERIAL=<pixel> ./gradlew :app:installDebug`).

Every app start writes a trace to `Android/data/com.curveon.app/files/navtrace/trace-*.csv`
(raw GPS fixes, route matching, where the arrow was drawn, routes, OBD values; kept 14 days).

- [ ] Ride a route, including a junction where you deliberately go the wrong way, in heading-up
  and in 3D. Does the arrow glide, keep up with the bike at junctions, leave the line at once?
- [ ] Then pull the traces and tell me roughly when/where (time of day, which junction):
  `adb -s <X7> pull /sdcard/Android/data/com.curveon.app/files/navtrace/ ~/develop/Curveon-data/navtrace/`
  (Pixel: same path with its own serial).

## 2026-10-10 — Speed on the map, engine panel (OBD2)

Speed, limit, clock (and speed camera / GPS lines) are always on the map now, top right. Settings →
Engine: engine panel on/off, the OBD2 adapter (paired Bluetooth devices, or Demo), and which values
(fuel, RPM, coolant, battery). Tablet: panel above the music; phone upright: music bottom left,
panel bottom right, map up to the top. Checked in the emulator with Demo. Installed on both devices.

- [x] Adapter connects on the Ténéré 700 (2020): RPM, coolant and battery come through.
- [ ] Fuel: the T7 doesn't answer PID 012F. On the first connection after each app start the
  tablet now notes what the ECU supports plus 20 s of raw bus traffic in
  `files/navtrace/obd-*.txt` (the readings show up ~30 s later than usual that one time).
  Connect once with a full tank and once with it low (ignition on, engine idling is fine),
  then pull the navtrace folder as above, so we can look for the fuel gauge on the bus.
- [ ] Riding with a turn banner: it sits top left and doesn't cover the speed (phone upright too).

## 2026-10-10 — Stops down a side street are ridden to, and one map editor

Two changes, installed on the X7 (the Pixel was offline: `./gradlew :app:installDebug` with it as
`ANDROID_SERIAL` once Wireless debugging is back on).

- Stops: a stop just off the way (e.g. a side street in Haugesund) is now ridden to; before, the
  route rode past it ~200 m away (BRouter cut the out-and-back to it). Trips with stops are now
  routed leg by leg. (The first try, 7e75557, turned plain A→B trips into there and back; fixed.)
  `:routing:test` StopsVisitedTest covers both.

- The edit-on-map button (and a long press on the route) opens the same editor as "+ Stop" → Pick
  on map: tap to add a point, drag the line to bend the route, tap/hold a point for its menu
  (remove, move, change place, add stop before), the dotted-line button marks a part of the route.

- [ ] X7: Bømlo → Haugesund without stops is one way (~67 km), not there and back.

- [ ] X7: add a stop in a side street near the route: the blue line goes into the street to it.

- [ ] Edit button on the route preview → drag the line → a detour; marking button → two pins.

- [ ] Tap a stop on the map → menu → Change place opens the search and comes back to the trip.

## 2026-10-10 — Two fingers on the map zoom, never drag the route

While editing or building a trip, a pinch whose first finger lands on the route line or a point no
longer starts a detour: as soon as the second finger touches, the drag is dropped and the map
zooms/turns. Checked in the emulator with a scripted two-finger pinch starting on the line (route
unchanged, map zoomed). Installed on both devices.

- [ ] Pixel/X7: pinch-zoom with one finger starting on the blue line: no dashed detour, no new point.

## 2026-10-10 — "+ Stop" on the map asks: on the way, or the new end?

Picking stops on the map from "+ Stop" on a trip that already has an end: the first tap asks "A
stop on the way, or the new end?". On the way: that point and the next ones become numbered stops
where they fit along the route; New end: the trip rides on to them (the old end stays a stop).
Asked once per round of picking. Checked in the emulator. Installed on both devices.

- [ ] X7: install, then "+ Stop" → Pick on map → tap a point beside the route → On the way.

## 2026-10-10 — Hold a point on the map to remove it

While editing a trip on the map or building it point by point, holding a finger still on a point
(white dot, numbered stop, start or destination) opens its menu with "Remove point" (a stop also
keeps moving earlier/later). Removing the destination makes the point before it the destination.
Dragging a point works as before. Checked in the emulator. Installed on both devices.

- [ ] Pixel: a long press on a white dot gives a buzz and the menu; Remove reroutes without it.
- [ ] A quick drag of a point still moves it (no menu popping up mid-drag).

## 2026-10-10 — Save a route to Where to?, GPX, or both

The route's save dialog has two ticks: Where to? (saved routes, as before) and GPX track, with
the GPX group to put it in (an existing one, no group, or a new one, as when saving a ride). The
choice is remembered for next time. The GPX copy has the route's line and its stops as waypoints.
Checked in the emulator (saved to both, the track shows up in its GPX group). Installed on both.

- [ ] Pixel portrait: the dialog fits, the group name doesn't push the tick off the row.
- [ ] Export the saved GPX track from the GPX tab and open it elsewhere: line and stops are there.

## 2026-10-10 — Build a trip point by point on the map

"Pick on map" (for a destination or a stop) has a new button (a zigzag line) next to the search
icon. Switched on, each tap on the map rides the trip on to there right away (the old end becomes
a point on the way); the bar shows the trip's time and length, Undo takes the last point back and
the tick finishes into the route preview. It stays on until switched off. Points can be dragged
while building. Checked in the emulator (X7 landscape, Pixel size portrait). Installed on both.

- [ ] Pixel: plan A → B → C → D by tapping; the map stays where you tap (no zooming out between points).
- [ ] A tap right on the route line also adds the next point (e.g. riding back the same way).
- [ ] Point by point is now always how a destination/stop is picked on the map (no toggle). Picking
  the start, a stop while riding, or "Change place"/"Add stop before" still picks one place.

## 2026-10-10 — Mark the part of the route to change

While editing a trip on the map, the new button (a line with dots) left of Undo marks a part of
the route: drag along the line, and its two ends get pinned as small white points, so dragging the
line between them only changes that part. Done is a round tick on a phone. Checked in the
emulator (X7 landscape, Pixel size portrait). Installed on both devices.

- [ ] Pixel portrait: editing shows the tick (Done) at the right of the bar.
- [ ] Long trip (e.g. Bømlo → Oslo): mark a part at Haukeli, drag the line out to a side road;
  the route outside the marked part stays as it was, and Undo takes the marking away in one go.
- [ ] Marking with a finger that drifts off the line still slides the orange part along the route.

## 2026-10-10 — The route preview scrolls instead of losing GO

When the route preview runs out of height (the Pixel in landscape with a gravel warning), the part
between the title and the options scrolls; the options and GO always stay at the bottom. Checked in
the emulator at Pixel size, landscape. Installed on both devices.

- [ ] Pixel landscape: the trip with the gravel warning shows Tarmac/Ferries/Motorways and GO;
  swipe the cards up to read the whole warning.

## 2026-10-10 — The clock sits by the speed

The clock moved from the rail to the right end of the speed card (and of the speed box on the
full-screen map), so the rail's buttons get the room. Checked in the emulator on the X7 (both
layouts) and phone upright. Installed on both devices.

- [ ] Pixel upright: the six buttons along the bottom have room now.
- [ ] Riding with a speed limit sign showing: speed, sign and clock all fit on the X7 and Pixel.

## 2026-10-10 — GO drops its word when squeezed

The route preview's GO button shows arrow and "GO"/"KJØR" when it has room, and just a bigger arrow
when it's squeezed (it spilled out of the button on the Pixel in landscape). Installed on both devices.

- [ ] Pixel landscape (dashboard): plan a trip; the button shows just the arrow, inside its pill.
- [ ] X7 and Pixel upright: the button still says KJØR/GO.

## 2026-10-10 — Routing online without downloading the country

Planning a trip somewhere with no routing data (e.g. Curveon on the Pixel, no countries downloaded)
now fetches just the routing tiles the trip needs from brouter.de when online (5x5 degrees, about
5–65 MB each), shown as "Fetching routing data (1 of 2)…" in the preview. They stay for next time,
so that area then works offline too. No map or search is downloaded. Tried in the phone emulator
(Oslo → Hamar: two tiles, ~93 MB, then the three routes in ~8 s).

- [ ] Pixel, online, no countries: plan a trip; it fetches routing data, then shows the routes and GO works.

## 2026-10-10 — Lock the screen landscape or portrait

Settings → Display → Screen: Turns / Landscape / Portrait. Locked, the screen stays exactly the way
it was held when chosen (not even flipping upside down), whatever Android's auto-rotate says.
Tried in the emulator.

- [ ] X7: Settings → Display → Screen → Landscape while it sits on the mount; then ride over bumps
  (or tilt it upright by hand): the screen doesn't turn.

## 2026-10-09 — Portrait: speed on top, dialogs that fit

Upright, the speed card now sits above the map. The dialogs stack on a phone instead of squeezing:
Settings, GPX and the map download get their page tabs along the top (only the open one labelled),
the phone's tabs get a row of their own, Roundtrip puts the compass above the options, and the
route preview gives GO a full-width row. Checked at Pixel 9a size in the emulator (search, route
preview, place menu, save, navigation, every rail dialog); the X7 in landscape looks as before.

- [ ] Pixel upright: go through Settings, GPX, Roundtrip, Phone and a route preview; anything cut off?

## 2026-10-09 — Portrait

Curveon now turns with the device (Android's auto-rotate): held upright, the map is on top, speed and
music/messages below it, and the rail is a row along the bottom; while planning a trip the map
gets the cards' room too. Landscape is as before. Tried in the emulator (dashboard, full map,
search, route preview).

- [ ] Pixel upright: the 411 dp wide screen still fits the route preview's three options and GO.

## 2026-10-09 — Curveon on a phone: camera hole, rail, hints

On the Pixel (held sideways) the rail moves clear of the camera hole, the rail buttons are a bit
smaller so the map toggle fits, the "no offline map" note only shows when there is no map at all
(no connection either), and the music card says how to use the phone itself. Checked on the Pixel 9a with the
hole on the left; the X7 looks as before.

- [ ] Pixel: turn it the other way round (hole on the right): the cards on the right move clear of it.
- [ ] Curveon on a phone without downloaded countries, wifi and mobile data off: the map shows
  "No map here without a connection…". With a connection there is no note at all.

## 2026-10-09 — "This device" as its own phone

Settings → Phone has a new choice, **This device**: Curveon reads messages, calls and music from the
apps on the same device, with no companion and no Bluetooth (for riders who have only a phone).
The companion app still works as before and is still installed on its own. Its code moved into a
shared `:phone` module. Tried in the emulator (music apps found, a test message
showed on the dashboard).

- [ ] Pixel (companion reinstalled 2026-10-09, music already reaches the X7): a message, a call and
  a spoken reply still come through. After this update the companion's notification access had
  to be turned on again (the listener moved to `com.curveon.phone`; done with
  `adb shell cmd notification allow_listener com.curveon.companion/com.curveon.phone.CurveonNotificationListener`).
- [ ] X7: Settings → Phone → *Use* on the Pixel again after trying "This device" (switching back works).
- [ ] Optional, with Curveon on the Pixel: *This device* → allow access; a WhatsApp message shows on
  Curveon's message card, and the music card controls Spotify.

## 2026-10-09 — Choose the start before the destination

"Where to?" now has a "From my position" row at the top. Tap it to pick the start first (search, a
recent place or the map), then search for the destination. The route is then worked out from
that start straight away, not from where you are first. Tried in the emulator (from Lillehammer to Hamar).

- [ ] Plan a trip that starts far away (another part of the country): the preview comes up quickly
  and reads "From …".
- [ ] Choosing the start with "Pick on map" brings you back to "Where to?" with that start.

## 2026-10-09 — Offroad as a fourth surface choice

The surface chip now cycles Tarmac → Mixed → Gravel → Offroad. Offroad goes looking for tracks
(rough grades too) and dirt, grass and earth, before gravel; tarmac only where there's nothing else.
It still keeps to roads and tracks a motorcycle is allowed on (no paths or trails). The route line
is purple. Hamar–Lillehammer in the tests: Gravel 19 km unpaved and no tracks, Offroad 33 km and
9 km of tracks.

- [ ] Plan a trip with Offroad near home: the tracks it picks are ones the X7 bike can actually
  ride (if grade 4–5 tracks turn out too rough, say so and they'll cost more).
- [ ] The purple route line is easy to see on both the day and the night map.

## 2026-10-09 — Warning when a tarmac route forces gravel

With Tarmac chosen, a route that still has gravel (100 m or more) shows a framed warning above GO:
how much gravel and where the first of it starts. The folded bar shows "⚠ 400 m gravel". The router
only takes gravel on Tarmac when there's no tarmac road short of a huge detour. Tried on the X7
(Stautland on Bømlo: 400 m gravel at the end; with Mixed the warning goes away).

- [ ] Plan a trip to a cabin or farm on a gravel road: the warning is clear enough before pressing GO.
- [ ] A normal tarmac trip shows no warning (no false alarms from short untagged bits).

## 2026-10-09 — No home/recents buttons while typing

Dialogs no longer bring back the back/home/recents bar (it also squeezed the map). The bar that
comes with the keyboard can't be hidden by an app, so the X7 was switched to gesture navigation
(`adb shell cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.gestural`):
with the keyboard up there is only a ▼ (hide keyboard) bottom left and a thin home line that needs a
swipe up, not a tap. Undo: Settings → System → Gestures → System navigation → 3-button navigation.
Tried on the X7 (Where to?, Settings).

- [ ] Type an address on the bike: ▼ hides the keyboard, and you no longer land on the home screen by accident.
- [ ] Elsewhere (Google Maps etc.) back is now a swipe in from the left or right edge: OK with gloves?
- [ ] A delete confirmation (long-press a saved route in Where to?) also shows no button bar.

## 2026-10-09 — Gravel and soft roads on the map

From zoom 11 in, roads the routing data knows as unpaved are dashed over the map: grey = gravel or
compacted, brown = softer, rough or unknown ground. Read from the routing segments, so it works
wherever routing data is installed. Tried in the emulator (forest roads east of Elverum).

- [ ] On the X7, zoom to a forest-road area (or ride somewhere with gravel): the dashes appear
  within about a second after the map stops moving, and panning stays smooth.
- [ ] Spot-check a road you know is gravel and one you know is tarmac: right colour?
- [ ] Dark map: the grey and brown still show.

## 2026-10-09 — Online map and online search

Outside the downloaded countries the map now comes from OpenFreeMap (free, no key) when the tablet is
online. Search shows offline results first, then mixes in Photon and (in Norway) Kartverket's place
names/addresses. Tried in the emulator (Arvika in Sweden; "Lyklingv" from Bømlo).

- [ ] On the phone hotspot, pan the X7's map into Sweden: roads and towns appear in the same style.
  Pan back into Norway: `adb logcat -s CurveonMap` should say "Online map off".
- [ ] Turn the hotspot off and pan to where you looked before: the map should still be there (cache).
- [ ] With the hotspot on, search for a farm or bay name near home: an "online" result appears a
  moment after the offline ones. With it off, search behaves as before.
- [ ] Riding with the online map shown: does the X7 keep up (smooth scrolling, no lag)?

## 2026-10-08 — Tracks with Tarmac

Before, Tarmac forbade every `highway=track` (and grade4/5 were forbidden even with Gravel), so a
destination on a farm/forest road stopped on the nearest tarmac. Now tracks are a last resort.

- [ ] With Tarmac: Where to? → long-press the map on a gravel/forest track → Go here: the route should
  end on the track. A trip between towns should still use no tracks.
- [ ] Follow a GPX track that runs over gravel roads with Tarmac selected: it should stay on the GPX line.

## 2026-10-08 — Route start at home

In OSM your road (way 520497619) is joined to the roads only through the cycle path (the map is
wrong). Cycle paths/footpaths are now used only at the very start/end of a route when there is no
road way out (as little as possible, no length limit), never in the middle. From your house that is
18 m of path stub onto Stavlandsvegen. Tried in the emulator with the GPS at your house.

- [ ] At home on the X7: plan a trip → the line starts at the house, "Bear left · Fv4998 Stavlandsvegen"
  after ~70 m. Ride it: no "Recalculating" on the way down to the road.
- [ ] Ride home with a trip to the house: it should end at the house.
- Still worth fixing in OSM (join the service road to Stavlandsvegen), so other apps get it right too.

## 2026-10-08 — Editing a trip on the map

Tried in the emulator: long-press on the route line → edit mode, dragging the line (dashed guess,
then the new route), dragging the new point again, Undo, tapping a point → menu, Add stop before,
saving a changed saved route under its name (updated, not copied).

- [ ] On the X7 with gloves: is it easy to grab the line (22 dp either side) and the stop handles
  (30 dp) without panning the map by accident? Too fiddly → say so and they can be made bigger.
- [ ] Ride a saved A→B route without changing it: it should still follow its saved line (and rejoin it
  after a wrong turn) like before.

## 2026-10-08 — Pick on map

Tried in the emulator: destination, + Stop and the start chip each open the map from "Pick on
map", the dialog shrinks into the bar, tap drops a pin, confirm works and the dashboard comes back.

- [ ] On the X7 with gloves: is a tap on the map reliable enough to drop the pin (not taken as a
  small pan)? And is the shrink animation smooth on the X7, or does it stutter?

## 2026-10-08 — Phone status, music app favorites

Tried on the X7 + Pixel: Settings → Phone shows Pixel 9a, battery, "connected since" and the
detected music apps; starring Spotify (not running) and swiping the music card to it started it
on the phone (Spotify refuses Curveon's media browser, the play key fallback worked after ~8 s).

- [x] Spotify should now start in ~4 s instead of ~8 s (a refused browser goes straight to the
  play key): close Spotify on the phone, swipe to it on the X7.
- YouTube Music can't be started from the tablet when it isn't running (it only lets Android
  Auto/the system start it; tried play keys and "play from search"). Once it has played on the
  phone it can be switched to like the others.
- [x] Star NRK Radio / Podme too, close them on the phone (swipe away from
  recents), then swipe to each on the X7. Which start, and how? `adb -s 58301JEBF17776 logcat -s CurveonMedia`
  → "browser connected … play" / "started by play key" / "didn't start" (the tablet then says so).
- [x] Forget in Settings → Phone: the phone moves to "Paired phones"; tapping it there links it again.

## 2026-10-08 — Stops, planner, saving, wrong turns

Tried in the emulator with fake GPS (Voss → Bergen, then riding off towards Granvin): the
question card, Turn back (banner says "Make a U-turn" until you have turned), New route from here
with Keep/Skip stops, Rejoin further ahead on a saved route, the automatic choice after 20 s,
quiet rejoins in Voss centre, + Stop / route menu / save name while riding. The decisions were also
simulated over real roads on the desktop (`WrongTurnSimulationTest`). Still to try on the bike:

- [x] Ride a planned trip and take a wrong turn on purpose where there is a quick way round
  (a block, a parallel road): it should re-route quietly, no card. Then one onto a road that
  leads away: the card should come, and Turn back / New route from here should do what they say.
- [x] Ignore the card and keep riding: after ~20 s it should pick "New route from here" itself.
  Stop at the roadside instead: it should wait for you.
- [x] After "New route from here", leave the route again within 5 minutes: no new card, just a
  new route (follow mode, e.g. riding behind someone).
- [x] Is 40 m / 3 min / 10 % (Settings → Navigation) a good default on real roads? Too many or
  too few cards? Each decision is logged:
  `adb -s <x7> logcat -s CurveonNav` → "Off route at … m, heading …: rejoin/ask/replan".
- [x] Re-routing takes a few seconds on the X7 (several router calls). If "Recalculating…" stays
  up much longer than ~5 s on long trips, tell me.
- [x] Planner at home: long-press the map → Start here, add stops, save; later in Where to?
  the saved route should follow exactly the same line.

## 2026-10-08 — Offline search in every country

Tried in the emulator: Norway's search built from the map (~2 min, "basic search"), then replaced by
the full one from the cloud ("fuller search available" → Get missing). On the X7 the old Norway
index carried over as the full one. Still to try on the X7:

- [x] Download a new country (e.g. Sweden, once https://github.com/atlehavik/curveon-data/releases/tag/search
  has `sweden.sqlite.gz`): search finds a town, a street with house number and a fuel station there.
- [x] Search while in Norway still finds Norwegian addresses first; with two countries installed,
  results from both show up.
- [x] Optional: in airplane mode, build search from the map for a small country (Get missing with
  no connection falls back to it) and see how long it takes on the X7.

## 2026-10-08 — Download maps by country

Tried in the emulator (Faroe Islands: download, list, delete) and on the X7 (Faroe Islands next to
Norway on the map, then deleted again). Still to try on the X7:

- [x] Settings → Maps & data → Download countries → Sweden (or another country you'll ride):
  progress, then the map shows across the border and routes work there.
- [x] Pick two or three countries in a row: they queue ("Waiting") and download one after another;
  Cancel on a waiting one takes it out.
- [x] Border areas (e.g. Svinesund): roads look normal where the two maps overlap (no doubled labels).

## 2026-10-08 — Bike stays on the road while navigating

Only unit-tested (the tracker); needs a real ride with a route on the X7:

- [x] On the route, the bike sits on the route line and points along the road, also where GPS
  drifts (trees, steep valleys) instead of floating beside it.
- [x] Take a different road on purpose: the bike stays on the line for ~3 s, then jumps to your
  real position as the reroute starts. Shouldn't feel too sticky or too eager.
- [x] Tight hairpins: the arrow turns with the road without lagging badly.

## 2026-10-08 — Rides: select/delete, auto-save to GPX; map compass, 3D, "where am I" glow

Selecting, deleting, auto-save switch, 3D, recenter and edge glow tested in the emulator (no map
tiles there, so 3D was only seen on a blank map). On the X7:

- [x] After a real ride with "Auto-save new rides to GPX" on: the ride shows up as a track in the
  chosen GPX group once it ends (20 min standing still, or the app closing does *not* count).
- [x] 3D while riding: road ahead readable, rider not too low; tilt/zoom feel right in twisty roads.
- [x] Pan the map away while riding: the cyan glow on the edge points the right way and follows as
  you move; the ◎ button brings you back and disappears.
- [x] A tap on the small dashboard map no longer goes full screen (only the rail button does).

## 2026-10-07 — Language: English / Norsk

Tested in the emulator (switching both ways, Settings stays open). On the X7:

- [x] Settings → Display → Språk/Language: pick Norsk; the whole app turns Norwegian at once.
- [x] Ride with navigation in Norwegian: turn banner ("Sving til høyre · deretter …") fits, and
  numbers use decimal comma (12,5 km).
- [x] Android Settings → Apps → Curveon → Language lists English and Norsk too.
- [x] Reset if needed: `adb -s 241181d1 shell cmd locale set-app-locales com.curveon.app --locales en`

## 2026-10-07 — GPX groups: database upgrade on the X7

The GPX groups build was installed on the X7 by mistake while it was connected. It upgrades the
database the first time the app opens (your 20 TET tracks become the group "TET S"). This upgrade
was tested in the emulator on a copy of the X7's data. A backup of the X7 database taken before the
upgrade is in `~/develop/Curveon-data/db-backup-2026-10-07/x7/`.

- [x] Open Curveon on the X7, then GPX: one group "TET S" (20 tracks) and your tracks shown as before.
- [x] Rides: all 7 rides still there; "Save to GPX" on a ride puts it in the group you pick.
- [x] Map: a track set to inverted (← in the GPX list) shows its arrows the other way.
- [x] Waypoint details: install the newest build (`./gradlew :app:installDebug` with only the X7
  connected), then GPX → Import → `Download/S.gpx` again. Your group and track settings are kept;
  the waypoints now get address, phone, links, etc. Do the same for any other TET file you use.
- [x] Map: zoomed out, Sweden's waypoints are coloured dots; zoomed in (about 30 km across the
  screen) they turn into icons. Tap one (e.g. a campsite) and check the details look right.

## 2026-10-07 — incoming call card disappears after a moment

On the dashboard the call card showed briefly, then the music card took its place. The cause wasn't
found yet. The tablet now logs which of two things happened.

- [ ] Ride/sit with the X7 connected to the phone, have someone call you, let it ring ~20 s.

- [ ] Then, with the X7 on USB:

  ```
  adb logcat -d -s PhoneLink
  ```

  - `Phone removed call …` → the phone itself removed the call notification (phone-side fix needed).
  - `Link dropped during a call` → the Bluetooth link to the tablet dropped while it rang.
  - Neither line → the card wasn't removed; describe what you saw instead.

## 2026-10-07 — choosing the music app (Spotify vs. podcast)

- [x] With both Spotify and the podcast app opened on the phone, the music card on the tablet shows
  small dots at the top. Flip the card sideways (drag): it should pause the first app and play the other.
- [x] Stop all music, then tap the empty music card: it should offer the app icons to pick from.
- [x] "Phone not connected": turn sharing on in Curveon Companion, tap the music card on the tablet —
  it should connect within a few seconds instead of waiting for the next automatic try.
