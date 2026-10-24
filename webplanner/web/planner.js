// Curveon's web planner: plan a trip on a big screen, in a session with Curveon on the rider's phone or tablet
// (webplanner/README.md). Scanning the page's code connects a device: the page then shows that device's planned trips
// to open and work on, and sends new and changed trips back. The route comes from the planner service (the app's own
// routing); everything between page and device goes end-to-end encrypted through the relay (webplan.js).
import { b64, channel, decodePolyline, linkUri, newLink, parseLink, random, unb64 } from "./webplan.js";

// ---------- Texts (English and Norwegian, as in the app) ----------
const TEXTS = {
  en: {
    title: "Curveon planner", search_hint: "Search for a place, or click the map", lang: "Norsk",
    theme_dark: "Dark map", theme_light: "Light map",
    hint_empty: "Click the map or search to choose where you start, then where you are going. Click a point to change or remove it, drag it to move it. Click the line to put a via point or stop there, or drag it to take another road. Right-click tells you about the road there; the middle mouse button moves the map, also while drawing.",
    opt_style: "Roads", opt_surface: "Surface", opt_ferries: "Ferries", opt_motorways: "Motorways",
    style_fastest: "Fastest", style_curvy: "Curvy", style_super_curvy: "Super curvy",
    surface_tarmac_only: "Tarmac only", surface_mixed: "Mixed", surface_gravel: "Gravel", surface_offroad: "Offroad",
    ferries_ok: "Ferries OK", no_ferries: "No ferries", motorways_ok: "Motorways OK", no_motorways: "No motorways",
    no_motorways_e_roads: "No motorways or E-roads",
    start: "Start", via: "Via point", map_point: "Map point", remove: "Remove", up: "Earlier", down: "Later",
    km: "km", h: "h", min: "min", curvy: "curvy", up_m: "m climb", ferries_n: (n) => n === 1 ? "1 ferry" : `${n} ferries`,
    routing: "Finding the road…", queued: "Busy right now, your route is next in line…",
    err_busy: "The planner is busy. Try again in a moment.", err_no_route: "No road found between these places. Move a point onto a road.",
    err_too_long: "That is too long for one trip (over 1500 km). Split it into days.", err_slow_down: "That was a lot of routes at once. Wait a few minutes.",
    err_outside: "Routes can be worked out in Europe so far. Move the points there.",
    adv_open: "Adventure: draw an area, get fun roads on the way", adv_title: "Adventure",
    adv_draw: "Hold the mouse button down and draw round the area you want to ride in on the way there.",
    adv_draw_touch: "Draw round the area you want to ride in on the way there, with your finger.",
    adv_stops: "The stops on the way give way to the adventure's roads (Undo brings them back).",
    adv_time: "Longest time", adv_no_limit: "No limit", adv_hm: (h, m) => `${h}:${String(m).padStart(2, "0")} h`,
    adv_time_explain: "The longest the trip may take, or no limit and the area decides. It can be shorter if the area has too few good roads.",
    adv_searching: "Looking for an adventure… it can take up to a minute.",
    adv_found: "Here is an adventure. Change it like any trip, or ask for another.",
    adv_again: "Another suggestion", adv_redraw: "Draw again", adv_close: "Close",
    adv_too_small: "Draw a bigger area round where you want to ride.",
    err_no_adventure: "No adventure found in that area. Draw it bigger, or give more time.",
    err_adventure_too_short: (m) => `The quickest way takes ${m} min. Give more time for an adventure.`,
    err_too_slow: "That route took too long to work out. Split it with a stop, or pick Curvy instead of Super curvy.",
    err_failed: "The route could not be worked out. Try again.", err_offline: "No connection to the planner. Check your internet connection.",
    and: " and ", name_hint: "Name of the trip",
    open_gpx: "Open GPX", send_as: "Send as", as_trip: "A trip", as_track: "A GPX track", as_both: "Both",
    send_as_explain: "A trip has its stops, to change in Curveon; a GPX track is ridden just as it is.",
    gpx_loaded: (n) => `Opened “${n}”. Send it as a GPX track just as it is, as a trip you can change, or both.`,
    gpx_bad: "No track found in that file.", track_suffix: " (track)", send: "Send to Curveon", update_on: (d) => `Update on ${d}`, send_new: "Save as a new trip",
    sending: "Sending…", sent_ok: "Sent. It shows under Planned in Curveon the next time the app is online.",
    updated_ok: (d) => `Sent. The trip on ${d} is updated the next time the app is online.`,
    send_failed: "The trip could not be sent. Check your internet connection and try again.",
    pending_title: "Waiting for Curveon", waiting: "Not fetched yet", forget: "Remove from this list",
    connect: "Show the QR code", connect_more: "Connect another device", connect_title: "Connect your phone or tablet",
    connect_why: "Then what you plan here goes straight to Curveon, and you can open the trips already there. Click the button, then in Curveon tap Planned → From the web and scan the code.",
    menu_start: "Start here", menu_end: "Destination here", menu_new_end: "New destination here", menu_stop: "Stop here",
    menu_via: "Via point here", make_stop: "Make it a stop", make_via: "Make it a via point", drag_to_move: "Drag the point to move it.", make_end: "Make it the destination", vias_n: (n) => `${n} via points`, show_all: "Show them all",
    clear_all: "Clear all points", confirm_clear: "Remove all the points on the map?", undo: "↶ Undo", undo_title: "Undo the last change (Ctrl+Z)",
    copy_coords: "Copy coordinates", copied: "Copied ✓", zoom_here: "Zoom in here", on_route: (k, n) => `km ${k} of ${n} on the route`,
    road_motorway: "Motorway", road_main: "Main road", road_secondary: "Secondary road", road_minor: "Minor road", road_local: "Local road",
    road_service: "Access road", road_track: "Track (gravel or dirt)", road_path: "Path, not for motorcycles", road_ramp: "Slip road",
    road_tunnel: "tunnel", road_bridge: "bridge", road_oneway: "one-way", road_paved: "tarmac", road_unpaved: "gravel", connected_to: (d) => `Connected to ${d}`,
    on_device: (d) => `On ${d}`, no_trips: "No planned trips there yet.", loose: "Not in a group",
    edit: "Open to work on it", delete_on: (d) => `Delete from ${d}`,
    confirm_delete: (n, d) => `Delete “${n}” from ${d}? It is gone from Planned there the next time the app is online.`,
    view_only: "A line without stops (a ride kept as a trip, or a loop). Click the line to put a via point or stop on it: it then becomes a trip to change, and goes to Curveon as a new one.",
    editing_from: (d) => `Opened from ${d}: Update changes the trip there.`,
    new_trip: "New trip",
    pair_title: "Connect Curveon to this page", pair_step1: "Open Curveon on your phone or tablet.",
    pair_step2: "Go to Planned and tap From the web.", pair_step3: "Point the camera at this code.",
    pair_more: "Once per phone or tablet. Then this page shows the trips on it, and what you plan here goes there.",
    pair_done: "Close",
    save_draft: "Save as draft", save_draft_again: "Save the draft", draft_saved: "Saved among the drafts on this page.",
    drafts_title: "Drafts on this page", draft_points: (n) => `${n} points`, delete_draft: "Delete the draft",
    confirm_delete_draft: (n) => `Delete the draft “${n}”?`,
    unsaved_title: "Keep what you are working on?", unsaved_text: "The trip on the map is not saved or sent since you last changed it.",
    unsaved_save: "Save as draft", unsaved_discard: "Throw it away", unsaved_cancel: "Cancel",
    attribution: "Routes: Curveon (BRouter) · Search: Photon, Kartverket · Places: OpenStreetMap, Overture Maps",
    loop_open: "Roundtrip: draw where you want to ride", loop_title: "Roundtrip",
    loop_draw: "Hold the mouse button down and draw round the area you want to ride in. The trip goes round it and back to the start.",
    loop_draw_touch: "Draw round the area you want to ride in, with your finger. The trip goes round it and back to the start.",
    loop_len: "Length", loop_max_any: "Whole area", loop_max_km: (k) => `Max ${k} km`, roundtrip_draw_km: (k) => `About ${k} km round`, loop_len_explain: "The longest the roundtrip may be, or no limit and the area decides.",
    loop_searching: "Looking for a roundtrip… it can take up to a minute.",
    loop_found: "Here is a roundtrip. Change it like any trip, or ask for another.",
    err_no_roundtrip: "No roundtrip found in that area. Draw it bigger, or round more roads.",
    day_n: (n) => `Day ${n}`, night_here: "Stay the night here", night_not: "Not staying the night here", night: "night",
    as_days: "Each day as a trip", day_suffix: (n) => ` – day ${n}`,
    profile_title: "Height along the route", profile_at: (km, m) => `km ${km} · ${m} m above sea`,
    fuel_gap: (km, from) => `⛽ ${km} km without fuel, from km ${from}`, fuel_gap_title: "Show it on the map",
    compare: "Compare Fastest, Curvy and Super curvy", compare_title: "Road styles", compare_wait: "Working it out…",
    compare_pick: "Use this one", compare_close: "Close",
    places: "Places", places_title: "Places on the map", places_zoom: "Zoom in to see them.",
    layer_fuel: "Fuel", layer_viewpoints: "Viewpoints", layer_passes: "Mountain passes", layer_camping: "Camping",
    layer_gravel: "Gravel roads (from zoom 11)", layer_food: "Food and coffee", layer_ferries: "Ferries", layer_motorcycle: "Motorcycle shops and workshops",
    save_gpx: "Save as GPX", share: "Copy link", link_copied: "Link copied. Whoever opens it sees this trip and can change it.",
    opened_link: "Opened from a link. Change it, save it as a draft, or send it to Curveon.",
    sec_trip: "Trip", sec_options: "Roads", sec_route: "Route", sec_save: "Save and send", name_label: "Name",
    draw_cancel: "Cancel",
    tip_search: "Search for a place, an address or a business; or click the map",
    tip_undo: "Undo the last change (Ctrl+Z)", tip_clear: "Take every point off the map and start the trip again",
    tip_save_draft: "Keep what is on the map in this browser, to go on with later (Drafts, below)",
    tip_adv: "Draw an area on the map: Curveon finds the most fun roads through it on the way to the destination, within the time you give",
    tip_loop: "Draw an area on the map: Curveon finds a loop through it from the start and back, as long as you allow",
    tip_adv_again: "Another suggestion in the same area", tip_adv_redraw: "Draw the area again", tip_adv_close: "Stop, and keep the trip as it is now",
    tip_style: "What kind of roads the route looks for", tip_surface: "Whether the route may take gravel and tracks",
    tip_ferries: "Whether the route may take ferries", tip_motorways: "Whether the route may take motorways and E-roads",
    tip_compare: "Work out the route as Fastest, Curvy and Super curvy, see them side by side, and pick one",
    tip_profile: "Heights along the route: point at it to see where on the map",
    tip_name: "The trip's name in Curveon", tip_send: "Send the trip to Curveon on your connected phone or tablet",
    tip_send_new: "Keep the trip on the device as it was, and save this as a new trip",
    tip_save_gpx: "Download the trip as a GPX file, for other apps and GPS units",
    tip_share: "Copy a link to this trip: open it on another computer or send it to a friend",
    tip_new: "Start a new, empty trip", tip_open_gpx: "Open a GPX file from this computer (or drop it on the map)",
    tip_pair: "Show the code to scan in Curveon (Planned → From the web)", tip_unpair: "Make this browser forget your phone and tablet",
    tip_lang: "Norsk / English", tip_fold: "Fold the panel away, or out again",
    opt_reach: "Detour for gravel", reach_any: "Up to double length", reach_km: (k) => `Up to ${k} km`,
    tip_reach: "How far off the usual route to find gravel?", gravel_amount: "gravel", track_amount: "tracks",
    disconnect: "Disconnect",
    confirm_disconnect: (d) => `Disconnect ${d}? This browser forgets the connection, so nobody using it can see or change the trips there. To connect again, scan the code again.`,
    disconnected: "Disconnected. This browser can no longer reach your phone or tablet. To remove it there too: Planned → From the web → Disconnect.",
    seconds: (n) => `${n} s`,
  },
  nb: {
    title: "Curveon-planlegger", search_hint: "Søk etter et sted, eller klikk i kartet", lang: "English",
    theme_dark: "Mørkt kart", theme_light: "Lyst kart",
    hint_empty: "Klikk i kartet eller søk for å velge hvor du starter, og så hvor du skal. Klikk på et punkt for å endre eller fjerne det, dra i det for å flytte det. Klikk på linjen for å legge et hjelpepunkt eller stopp der, eller dra i den for å ta en annen vei. Høyreklikk forteller om veien der; midtre museknapp flytter kartet, også mens du tegner.",
    opt_style: "Veier", opt_surface: "Underlag", opt_ferries: "Ferjer", opt_motorways: "Motorvei",
    style_fastest: "Raskest", style_curvy: "Svingete", style_super_curvy: "Supersvingete",
    surface_tarmac_only: "Kun asfalt", surface_mixed: "Blandet", surface_gravel: "Grus", surface_offroad: "Offroad",
    ferries_ok: "Ferjer OK", no_ferries: "Ingen ferjer", motorways_ok: "Motorvei OK", no_motorways: "Ingen motorvei",
    no_motorways_e_roads: "Ingen motorvei eller E-vei",
    start: "Start", via: "Hjelpepunkt", map_point: "Kartpunkt", remove: "Fjern", up: "Tidligere", down: "Senere",
    km: "km", h: "t", min: "min", curvy: "svingete", up_m: "m stigning", ferries_n: (n) => n === 1 ? "1 ferje" : `${n} ferjer`,
    routing: "Finner veien …", queued: "Travelt akkurat nå, ruten din står først i køen …",
    err_busy: "Planleggeren har mye å gjøre. Prøv igjen om litt.", err_no_route: "Fant ingen vei mellom disse stedene. Flytt et punkt inn på en vei.",
    err_too_long: "Det er for langt for én tur (over 1500 km). Del den opp i dager.", err_slow_down: "Det ble mange ruter på kort tid. Vent noen minutter.",
    err_outside: "Ruter kan foreløpig bare regnes ut i Europa. Flytt punktene dit.",
    adv_open: "Eventyr: tegn et område, få morsomme veier dit", adv_title: "Eventyr",
    adv_draw: "Hold museknappen nede og tegn rundt området du vil kjøre i på veien dit.",
    adv_draw_touch: "Tegn rundt området du vil kjøre i på veien dit, med fingeren.",
    adv_stops: "Stoppene underveis viker for eventyrets veier (Angre henter dem tilbake).",
    adv_time: "Lengste tid", adv_no_limit: "Ingen grense", adv_hm: (h, m) => `${h}:${String(m).padStart(2, "0")} t`,
    adv_time_explain: "Lengste tid turen kan ta, eller ingen grense, så bestemmer området. Den kan bli kortere hvis området ikke har gode veier nok.",
    adv_searching: "Leter etter et eventyr … det kan ta opptil et minutt.",
    adv_found: "Her er et eventyr. Endre det som en vanlig tur, eller be om et annet.",
    adv_again: "Et annet forslag", adv_redraw: "Tegn på nytt", adv_close: "Lukk",
    adv_too_small: "Tegn et større område rundt der du vil kjøre.",
    err_no_adventure: "Fant ikke noe eventyr i det området. Prøv å tegne det større, eller gi mer tid.",
    err_adventure_too_short: (m) => `Raskeste vei tar ${m} min. Be om mer tid for å få et eventyr.`,
    err_too_slow: "Den ruten tok for lang tid å regne ut. Del den opp med et stopp, eller velg Svingete i stedet for Supersvingete.",
    err_failed: "Ruten kunne ikke regnes ut. Prøv igjen.", err_offline: "Får ikke kontakt med planleggeren. Sjekk internettforbindelsen.",
    and: " og ", name_hint: "Navn på turen",
    open_gpx: "Åpne GPX", send_as: "Send som", as_trip: "En tur", as_track: "Et GPX-spor", as_both: "Begge",
    send_as_explain: "En tur har stoppene sine, så du kan endre den i Curveon; et GPX-spor kjøres akkurat som det er.",
    gpx_loaded: (n) => `Åpnet «${n}». Send den som GPX-spor akkurat som den er, som en tur du kan endre, eller begge deler.`,
    gpx_bad: "Fant ikke noe spor i den filen.", track_suffix: " (spor)", send: "Send til Curveon", update_on: (d) => `Oppdater på ${d}`, send_new: "Lagre som ny tur",
    sending: "Sender …", sent_ok: "Sendt. Den dukker opp under Planlagte i Curveon neste gang appen er på nett.",
    updated_ok: (d) => `Sendt. Turen på ${d} oppdateres neste gang appen er på nett.`,
    send_failed: "Turen ble ikke sendt. Sjekk internettforbindelsen og prøv igjen.",
    pending_title: "Venter på Curveon", waiting: "Ikke hentet ennå", forget: "Fjern fra listen",
    connect: "Vis QR-koden", connect_more: "Koble til en enhet til", connect_title: "Koble til telefonen eller nettbrettet",
    connect_why: "Da havner det du planlegger her rett i Curveon, og du kan åpne turene som allerede ligger der. Trykk på knappen, og i Curveon trykker du Planlagte → Fra nettet og skanner koden.",
    menu_start: "Start her", menu_end: "Mål her", menu_new_end: "Nytt mål her", menu_stop: "Stopp her",
    menu_via: "Hjelpepunkt her", make_stop: "Gjør til stopp", make_via: "Gjør til hjelpepunkt", drag_to_move: "Dra i punktet for å flytte det.", make_end: "Gjør til mål", vias_n: (n) => `${n} hjelpepunkter`, show_all: "Vis alle",
    clear_all: "Fjern alle punktene", confirm_clear: "Fjerne alle punktene på kartet?", undo: "↶ Angre", undo_title: "Angre siste endring (Ctrl+Z)",
    copy_coords: "Kopier koordinater", copied: "Kopiert ✓", zoom_here: "Zoom inn hit", on_route: (k, n) => `km ${k} av ${n} på ruten`,
    road_motorway: "Motorvei", road_main: "Hovedvei", road_secondary: "Sekundærvei", road_minor: "Mindre vei", road_local: "Lokalvei",
    road_service: "Adkomstvei", road_track: "Traktorvei eller grusvei", road_path: "Sti, ikke for motorsykkel", road_ramp: "Rampe",
    road_tunnel: "tunnel", road_bridge: "bru", road_oneway: "enveiskjørt", road_paved: "asfalt", road_unpaved: "grus", connected_to: (d) => `Koblet til ${d}`,
    on_device: (d) => `På ${d}`, no_trips: "Ingen planlagte turer der ennå.", loose: "Uten gruppe",
    edit: "Åpne for å jobbe med den", delete_on: (d) => `Slett fra ${d}`,
    confirm_delete: (n, d) => `Slette «${n}» fra ${d}? Den forsvinner fra Planlagte der neste gang appen er på nett.`,
    view_only: "En linje uten stopp (en kjørt tur lagret som tur, eller en rundtur). Klikk på linjen for å legge et hjelpepunkt eller stopp på den: da blir den en tur du kan endre, og den går til Curveon som en ny tur.",
    editing_from: (d) => `Åpnet fra ${d}: Oppdater endrer turen der.`,
    new_trip: "Ny tur",
    pair_title: "Koble Curveon til denne siden", pair_step1: "Åpne Curveon på telefonen eller nettbrettet.",
    pair_step2: "Gå til Planlagte og trykk Fra nettet.", pair_step3: "Rett kameraet mot denne koden.",
    pair_more: "Én gang per telefon eller nettbrett. Da viser denne siden turene der, og det du planlegger her havner der.",
    pair_done: "Lukk",
    save_draft: "Lagre som utkast", save_draft_again: "Lagre utkastet", draft_saved: "Lagret blant utkastene på denne siden.",
    drafts_title: "Utkast på denne siden", draft_points: (n) => `${n} punkter`, delete_draft: "Slett utkastet",
    confirm_delete_draft: (n) => `Slette utkastet «${n}»?`,
    unsaved_title: "Ta vare på det du holder på med?", unsaved_text: "Turen på kartet er ikke lagret eller sendt siden du sist endret den.",
    unsaved_save: "Lagre som utkast", unsaved_discard: "Forkast den", unsaved_cancel: "Avbryt",
    attribution: "Ruter: Curveon (BRouter) · Søk: Photon, Kartverket · Steder: OpenStreetMap, Overture Maps",
    loop_open: "Rundtur: tegn hvor du vil kjøre", loop_title: "Rundtur",
    loop_draw: "Hold museknappen nede og tegn rundt området du vil kjøre i. Turen går rundt det og tilbake til start.",
    loop_draw_touch: "Tegn rundt området du vil kjøre i med fingeren. Turen går rundt det og tilbake til start.",
    loop_len: "Lengde", loop_max_any: "Hele området", loop_max_km: (k) => `Maks ${k} km`, roundtrip_draw_km: (k) => `Omtrent ${k} km rundt`, loop_len_explain: "Hvor lang rundturen høyst kan være, eller ingen grense, så avgjør området.",
    loop_searching: "Leter etter en rundtur … det kan ta opptil et minutt.",
    loop_found: "Her er en rundtur. Endre den som en hvilken som helst tur, eller be om en annen.",
    err_no_roundtrip: "Fant ingen rundtur i det området. Tegn det større, eller rundt flere veier.",
    day_n: (n) => `Dag ${n}`, night_here: "Overnatt her", night_not: "Ikke overnatt her", night: "natt",
    as_days: "Hver dag som egen tur", day_suffix: (n) => ` – dag ${n}`,
    profile_title: "Høyden langs ruten", profile_at: (km, m) => `km ${km} · ${m} moh.`,
    fuel_gap: (km, from) => `⛽ ${km} km uten bensin, fra km ${from}`, fuel_gap_title: "Vis det på kartet",
    compare: "Sammenlign Raskest, Svingete og Supersvingete", compare_title: "Veivalg", compare_wait: "Regner ut …",
    compare_pick: "Bruk denne", compare_close: "Lukk",
    places: "Steder", places_title: "Steder på kartet", places_zoom: "Zoom inn for å se dem.",
    layer_fuel: "Bensin", layer_viewpoints: "Utsiktspunkt", layer_passes: "Fjelloverganger", layer_camping: "Camping",
    layer_gravel: "Grusveier (fra zoom 11)", layer_food: "Mat og kaffe", layer_ferries: "Ferjer", layer_motorcycle: "MC-butikker og verksteder",
    save_gpx: "Lagre som GPX", share: "Kopier lenke", link_copied: "Lenken er kopiert. Den som åpner den, ser turen og kan endre den.",
    opened_link: "Åpnet fra en lenke. Endre den, lagre den som utkast, eller send den til Curveon.",
    sec_trip: "Turen", sec_options: "Veivalg", sec_route: "Ruten", sec_save: "Lagre og send", name_label: "Navn",
    draw_cancel: "Avbryt",
    tip_search: "Søk etter et sted, en adresse eller en bedrift, eller klikk i kartet",
    tip_undo: "Angre siste endring (Ctrl+Z)", tip_clear: "Fjern alle punktene fra kartet og begynn turen på nytt",
    tip_save_draft: "Ta vare på det som er på kartet i denne nettleseren, for å jobbe videre senere (Utkast, lenger ned)",
    tip_adv: "Tegn et område i kartet: Curveon finner de morsomste veiene gjennom det på veien til målet, innenfor tiden du gir",
    tip_loop: "Tegn et område i kartet: Curveon finner en runde gjennom det fra start og tilbake, så lang du tillater",
    tip_adv_again: "Et nytt forslag i samme område", tip_adv_redraw: "Tegn området på nytt", tip_adv_close: "Stopp, og behold turen som den er nå",
    tip_style: "Hva slags veier ruten skal finne", tip_surface: "Om ruten kan gå på grus og traktorvei",
    tip_ferries: "Om ruten kan ta ferje", tip_motorways: "Om ruten kan gå på motorvei og E-vei",
    tip_compare: "Regn ut ruten som Raskest, Svingete og Supersvingete, se dem side om side, og velg én",
    tip_profile: "Høyden langs ruten: pek i den for å se hvor på kartet",
    tip_name: "Navnet på turen i Curveon", tip_send: "Send turen til Curveon på telefonen eller nettbrettet du har koblet til",
    tip_send_new: "Behold turen på enheten som den var, og lagre dette som en ny tur",
    tip_save_gpx: "Last ned turen som GPX-fil, til andre apper og GPS-er",
    tip_share: "Kopier en lenke til turen: åpne den på en annen PC eller send den til en venn",
    tip_new: "Begynn på en ny, tom tur", tip_open_gpx: "Åpne en GPX-fil fra denne PC-en (eller slipp den på kartet)",
    tip_pair: "Vis koden du skanner i Curveon (Planlagte → Fra nettet)", tip_unpair: "Få denne nettleseren til å glemme telefonen og nettbrettet ditt",
    tip_lang: "Norsk / English", tip_fold: "Fell panelet sammen, eller ut igjen",
    opt_reach: "Omvei for grus", reach_any: "Inntil dobbel lengde", reach_km: (k) => `Inntil ${k} km`,
    tip_reach: "Hvor langt fra vanlig rute for å finne grus?", gravel_amount: "grus", track_amount: "traktorvei",
    disconnect: "Koble fra",
    confirm_disconnect: (d) => `Koble fra ${d}? Denne nettleseren glemmer koblingen, så ingen som bruker den kan se eller endre turene der. Skann koden på nytt for å koble til igjen.`,
    disconnected: "Koblet fra. Denne nettleseren kommer ikke lenger til telefonen eller nettbrettet ditt. For å fjerne koblingen der også: Planlagte → Fra nettet → Koble fra.",
    seconds: (n) => `${n} s`,
  },
};

// ---------- Small helpers ----------
const $ = (id) => document.getElementById(id);
const el = (tag, cls, text) => { const e = document.createElement(tag); if (cls) e.className = cls; if (text !== undefined) e.textContent = text; return e; };
const store = {
  get(key, fallback) { try { const v = localStorage.getItem("curveon." + key); return v === null ? fallback : JSON.parse(v); } catch { return fallback; } },
  set(key, value) { try { localStorage.setItem("curveon." + key, JSON.stringify(value)); } catch { /* private window: works until the tab closes */ } },
};
const params = new URLSearchParams(location.search);
// The relay is the host the page comes from (relay.curveon.no/plan); ?relay=localhost:8787 when trying it on a PC.
const RELAY = params.get("relay") || location.host;
const WS_SCHEME = params.get("relay") || location.protocol === "http:" ? "ws" : "wss";
const BASE = location.pathname.replace(/[^/]*$/, ""); // "/plan/"
const API = BASE + "api/route";

let lang = store.get("lang", /^(nb|nn|no)\b/i.test(navigator.language) ? "nb" : "en");
const t = (key, ...a) => { const v = TEXTS[lang][key] ?? TEXTS.en[key] ?? key; return typeof v === "function" ? v(...a) : v; };

function applyTexts() {
  document.documentElement.lang = lang === "nb" ? "nb" : "en";
  document.title = t("title");
  document.querySelectorAll("[data-t]").forEach((e) => { e.textContent = t(e.dataset.t); });
  document.querySelectorAll("[data-tp]").forEach((e) => { e.placeholder = t(e.dataset.tp); });
  document.querySelectorAll("[data-tip]").forEach((e) => { e.title = t(e.dataset.tip); });
  for (const o of $("reach").options) o.textContent = o.value ? t("reach_km", Number(o.value)) : t("reach_any");
  $("lang").textContent = t("lang");
  $("send-as").title = t("send_as_explain");
  $("theme").textContent = theme === "dark" ? "☀" : "☾";
  $("theme").title = t(theme === "dark" ? "theme_light" : "theme_dark");
  $("theme").ariaLabel = $("theme").title;
  $("fold").textContent = $("panel").classList.contains("folded") ? "▴" : "▾";
  if (typeof iconSelects !== "undefined") drawSelects();
}

// ---------- State ----------
// points: [{ name, lat, lon, via }] start first, destination last.
// editing: what the map holds, if it was sent or opened from a device: { tid, ref: { r, id } | null, dev }.
// viewOnly: a device's line without stops, shown but not changed.
let points = [];
let result = null; // the planner's answer for these points and options (or the device's saved line)
let editing = null;
let viewOnly = false;
/** An adventure being drawn or looked for, while its box is open: { area: [[lat, lon]], ends: [start, destination], variant, drawing, stroke }. */
let adv = null;
let advCtl = null;
/** The via points in a row shown one by one in the list, not as one line. */
let viasOpen = false;
let options = Object.assign({ style: "CURVY", surface: "PAVED", ferries: false, motorways: false, trunk: false }, store.get("options", {}));
/** The connected devices, by their id on the channel: { dev, ids: [trip ids, newest first], trips: Map id → trip, at }. */
const devices = new Map();
/** Trips the page asked a device to delete ("rider:id"), hidden until its list says they are gone. */
const deleting = new Set();
/** The other road styles' lines while they are compared: [{ style, line }]. */
let alts = [];
/** The stretch without fuel shown on the map ([[lat, lon]]), when the rider asked to see it. */
let gapLine = null;
/** The places in view for the map's layers: [[kind, lat, lon, name]]. */
let poiList = [];
/** The gravel roads in (and round) view, for the map's dashes, and the box they cover. */
let gravelWays = [], gravelHave = null, gravelCtl = null;

// ---------- Map ----------
const emptyLine = () => ({ type: "FeatureCollection", features: [] });
// Light by default, dark when the rider picks it (both in the app's normal contrast); the page follows the map.
let theme = ["light", "dark"].includes(params.get("theme")) ? params.get("theme") : store.get("theme", "light");
document.documentElement.dataset.theme = theme;
const MAP = location.origin + BASE + "map/";
const protocol = new pmtiles.Protocol();
maplibregl.addProtocol("pmtiles", protocol.tile);
// The Norway map, also asked directly whether it covers the view (else the online map shows, as in the app).
const norway = new pmtiles.PMTiles(MAP + "norway.pmtiles");
protocol.add(norway);
let norwayMaxZoom = 15;
norway.getHeader().then((h) => { norwayMaxZoom = h.maxZoom; }).catch(() => {});

async function loadStyle() {
  const style = await (await fetch(`${BASE}map/styles/${theme}.json`)).json();
  style.sources.protomaps.url = "pmtiles://" + MAP + "norway.pmtiles";
  style.glyphs = MAP + "fonts/{fontstack}/{range}.pbf";
  style.sprite = MAP + "sprites/" + theme;
  // Beyond the Norway map there is only background: the sea's colour, so the edge of the map doesn't show.
  const water = style.layers.find((l) => l.id === "water")?.paint?.["fill-color"];
  const bg = style.layers.find((l) => l.id === "background");
  if (bg && typeof water === "string") bg.paint = { ...bg.paint, "background-color": water };
  return style;
}

const map = new maplibregl.Map({
  container: "map", style: withRoute(await loadStyle()), center: [8.5, 61.2], zoom: 5.2, minZoom: 3.5, maxZoom: 18,
  attributionControl: { compact: true, customAttribution: t("attribution") },
  // Where the map is stays in the address (#zoom/lat/lon): a view can be shared, and comes back on reload.
  hash: true,
  // No turning the map (there is no compass to set it straight again): the right button adds points instead.
  dragRotate: false, pitchWithRotate: false, touchPitch: false,
});
map.touchZoomRotate.disableRotation();
if (params.has("debug")) window.curveonMap = map; // for tools/webplanner_test.mjs
map.addControl(new maplibregl.NavigationControl({ showCompass: false }), "top-right");
map.addControl(new maplibregl.GeolocateControl({ positionOptions: { enableHighAccuracy: false }, trackUserLocation: false }), "top-right");

/**
 * The route's line in [style], under the place names (which stay on top of every line, as in the app). Part of the
 * style itself, so a change between light and dark keeps it.
 */
function withRoute(style) {
  style.sources.route = { type: "geojson", data: routeData() };
  style.sources["cv-alt"] = { type: "geojson", data: altData() };
  style.sources["cv-gap"] = { type: "geojson", data: gapData() };
  style.sources["cv-surface"] = { type: "geojson", data: surfData() };
  style.sources["cv-gravel"] = { type: "geojson", data: gravelData() };
  // Gravel and soft roads dashed over the map's own roads, as the app's map has them: under the road names.
  const labels = style.layers.findIndex((l) => l.type === "symbol" && !l.id.startsWith("online-"));
  const roadColour = theme === "dark" ? "#ffffff" : "#707070";
  // A little bolder than the app's on a phone: a big screen shows far more road at once.
  const dashWidth = ["interpolate", ["exponential", 1.6], ["zoom"], 11, 1.4, 12.5, 2.2, 15, 3, 18, 11];
  const ferry = { "line-color": theme === "dark" ? "#6fa3cf" : "#4a7fae", "line-width": ["interpolate", ["linear"], ["zoom"], 8, 1, 12, 1.6, 16, 2.5], "line-dasharray": [3, 2.5] };
  style.layers.splice(labels < 0 ? style.layers.length : labels, 0,
    // Where the ferries go: dashed across the water, from the Norway map's roads and, beyond it, the online map's
    // (an "online-" layer, shown and hidden with the rest of that map).
    { id: "cv-ferry", type: "line", source: "protomaps", "source-layer": "roads", filter: ["==", ["get", "kind"], "ferry"], paint: ferry },
    ...(style.sources.online ? [{ id: "online-ferry", type: "line", source: "online", "source-layer": "transportation", filter: ["==", ["get", "class"], "ferry"], paint: ferry }] : []),
    { id: "cv-gravel-road", type: "line", source: "cv-gravel", minzoom: 11, maxzoom: 12.2, filter: ["==", ["get", "k"], "road"],
      layout: { "line-cap": "round", "line-join": "round" },
      paint: { "line-color": roadColour, "line-width": ["interpolate", ["exponential", 1.6], ["zoom"], 11, 1, 14.5, 1.2] } },
    { id: "cv-gravel-soft", type: "line", source: "cv-gravel", minzoom: 11, filter: ["==", ["get", "k"], "soft"],
      paint: { "line-color": theme === "dark" ? "#a07850" : "#7a5230", "line-width": dashWidth, "line-dasharray": [2, 1.5] } },
    { id: "cv-gravel-gravel", type: "line", source: "cv-gravel", minzoom: 11, filter: ["==", ["get", "k"], "gravel"],
      paint: { "line-color": roadColour, "line-width": dashWidth, "line-dasharray": [1.8, 1.7] } });
  style.sources["cv-hover"] = { type: "geojson", data: emptyLine() };
  style.sources["cv-pois"] = { type: "geojson", data: poiData() };
  const at = style.layers.findIndex((l) => l.id.startsWith("places_"));
  // The places' names in the map's own font.
  const font = style.layers.find((l) => l.id.startsWith("places_") && Array.isArray(l.layout?.["text-font"]))?.layout["text-font"] || ["Noto Sans Regular"];
  style.layers.splice(at < 0 ? style.layers.length : at, 0,
    // An adventure's area, under the route (and the place names).
    { id: "area-fill", type: "fill", source: "area", paint: { "fill-color": "#ff9100", "fill-opacity": 0.1 } },
    { id: "area-line", type: "line", source: "area", layout: { "line-join": "round" },
      paint: { "line-color": "#ff9100", "line-width": 2.5, "line-opacity": 0.8, "line-dasharray": [2, 1.5] } },
    // The other road styles, while they are compared: thin, under the route.
    { id: "cv-alt", type: "line", source: "cv-alt", layout: { "line-join": "round", "line-cap": "round" },
      paint: { "line-color": theme === "dark" ? "#9aa3b2" : "#6b7280", "line-width": 3, "line-opacity": 0.75, "line-dasharray": [1.5, 1] } },
    // As in the app: blue, gravel orange and tracks purple on top of it, with a light (or, at night, dark) edge.
    { id: "route-casing", type: "line", source: "route", layout: { "line-join": "round", "line-cap": "round" },
      paint: { "line-color": theme === "dark" ? "#00363d" : "#ffffff", "line-width": ["interpolate", ["linear"], ["zoom"], 5, 6, 12, 11, 16, 16] } },
    { id: "route", type: "line", source: "route", layout: { "line-join": "round", "line-cap": "round" },
      paint: { "line-color": theme === "dark" ? "#00e5ff" : "#0091ea", "line-width": ["interpolate", ["linear"], ["zoom"], 5, 3.5, 12, 7, 16, 11] } },
    { id: "cv-surface", type: "line", source: "cv-surface", layout: { "line-join": "round", "line-cap": "butt" },
      paint: { "line-color": ["match", ["get", "k"], 2, "#d500f9", "#ff9100"], "line-width": ["interpolate", ["linear"], ["zoom"], 5, 3.5, 12, 7, 16, 11] } },
    // The longest stretch without fuel, when the rider asks to see it.
    { id: "cv-gap", type: "line", source: "cv-gap", layout: { "line-join": "round", "line-cap": "round" },
      paint: { "line-color": "#d50000", "line-width": ["interpolate", ["linear"], ["zoom"], 5, 3, 12, 6, 16, 9], "line-dasharray": [1, 1] } },
    { id: "route-hit", type: "line", source: "route", paint: { "line-color": "#000", "line-opacity": 0, "line-width": 22 } },
    // Where the pointer is on the height profile.
    { id: "cv-hover", type: "circle", source: "cv-hover", paint: { "circle-radius": 7, "circle-color": "#ffffff", "circle-stroke-color": "#004a77", "circle-stroke-width": 3 } },
    // Places on the map (fuel, viewpoints, …), their names from close up.
    { id: "cv-pois", type: "symbol", source: "cv-pois",
      layout: { "icon-image": ["concat", "poi-", ["get", "k"]], "icon-size": 1, "icon-allow-overlap": false, "icon-padding": 1,
        "text-field": ["step", ["zoom"], "", 12, ["get", "name"]], "text-font": font, "text-size": 11.5, "text-offset": [0, 1.25],
        "text-anchor": "top", "text-optional": true, "text-max-width": 9 },
      paint: { "text-color": theme === "dark" ? "#e6e8eb" : "#2b2f36", "text-halo-color": theme === "dark" ? "#101216" : "#ffffff", "text-halo-width": 1.4 } },
    // While dragging: the dashed guess from the place before, through the pointer, to the place after (as in the app).
    { id: "drag-line", type: "line", source: "drag", layout: { "line-join": "round", "line-cap": "round" },
      paint: { "line-color": theme === "dark" ? "#00e5ff" : "#0091ea", "line-width": 4, "line-opacity": 0.9, "line-dasharray": [1.2, 1.2] } });
  style.sources.drag = { type: "geojson", data: emptyLine() };
  style.sources.area = { type: "geojson", data: areaData() };
  return style;
}
map.on("load", showOnline);

/** The online map where the Norway map has no tiles in view (its middle or a corner), hidden where it does. */
async function showOnline() {
  const ids = map.getStyle().layers.map((l) => l.id).filter((id) => id.startsWith("online-"));
  if (!ids.length) return;
  const z = Math.max(0, Math.min(Math.floor(map.getZoom()), norwayMaxZoom));
  const n = 2 ** z;
  const c = map.getContainer();
  const spots = [[0.5, 0.5], [0.1, 0.1], [0.9, 0.1], [0.1, 0.9], [0.9, 0.9]].map(([fx, fy]) => map.unproject([c.clientWidth * fx, c.clientHeight * fy]));
  let covered = true;
  for (const ll of spots) {
    const lat = Math.max(-85, Math.min(85, ll.lat)) * Math.PI / 180;
    const x = Math.min(n - 1, Math.max(0, Math.floor((ll.lng + 180) / 360 * n)));
    const y = Math.min(n - 1, Math.max(0, Math.floor((1 - Math.log(Math.tan(lat) + 1 / Math.cos(lat)) / Math.PI) / 2 * n)));
    if (!(await norway.getZxy(z, x, y).catch(() => null))) { covered = false; break; }
  }
  const v = covered ? "none" : "visible";
  for (const id of ids) if (map.getLayer(id) && map.getLayoutProperty(id, "visibility") !== v) map.setLayoutProperty(id, "visibility", v);
}
let onlineTimer;
map.on("moveend", () => { clearTimeout(onlineTimer); onlineTimer = setTimeout(showOnline, 150); });


const lineFeature = (line) => ({ type: "Feature", geometry: { type: "LineString", coordinates: line.map(([la, lo]) => [lo, la]) } });
function routeData() { return result ? lineFeature(result.line) : emptyLine(); }
function altData() { return { type: "FeatureCollection", features: alts.map((a) => lineFeature(a.line)) }; }
function gapData() { return gapLine ? lineFeature(gapLine) : emptyLine(); }
/** The route's gravel (k 1) and track (k 2) stretches, each its part of the line. */
function surfData() {
  const r = result;
  if (!r?.surf?.length || r.line.length < 2) return emptyLine();
  const along = alongOf(r);
  const at = (m) => { let a = 0, b = along.length - 1; while (b - a > 1) { const mid = (a + b) >> 1; if (along[mid] <= m) a = mid; else b = mid; } return a; };
  return { type: "FeatureCollection", features: r.surf.map(([from, to, k]) => {
    const line = r.line.slice(at(from), Math.min(r.line.length, at(to) + 2));
    return line.length < 2 ? null : { ...lineFeature(line), properties: { k } };
  }).filter(Boolean) };
}

function drawRoute() {
  if (!result) gapLine = null;
  map.getSource("route")?.setData(routeData());
  map.getSource("cv-gap")?.setData(gapData());
  map.getSource("cv-alt")?.setData(altData());
  map.getSource("cv-surface")?.setData(surfData());
}

/** The dashed guess while dragging, through [ll] between the places [prev] and [next] (either may be missing). */
function drawDrag(prev, ll, next) {
  const c = [prev && [prev.lon, prev.lat], ll && [ll.lng, ll.lat], next && [next.lon, next.lat]].filter(Boolean);
  map.getSource("drag")?.setData(c.length >= 2 ? { type: "Feature", geometry: { type: "LineString", coordinates: c } } : emptyLine());
}

function fitTo(coords) {
  if (!coords.length) return;
  const b = coords.reduce((bb, [la, lo]) => bb.extend([lo, la]), new maplibregl.LngLatBounds([coords[0][1], coords[0][0]], [coords[0][1], coords[0][0]]));
  const narrow = innerWidth <= 640;
  map.fitBounds(b, { padding: narrow ? { top: 40, bottom: innerHeight * 0.55, left: 30, right: 30 } : { top: 60, bottom: 60, left: 470, right: 60 }, maxZoom: 12 });
}

// ---------- Markers ----------
let markers = [];
// The last point is the destination, unless the rider made it a stop or via point (still working towards the end).
function kindOf(i) { return i === 0 ? "start" : points[i].via ? "via" : i === points.length - 1 && !points[i].stop ? "end" : "stop"; }
function stopNumber(i) { let n = 0; for (let k = 1; k <= i; k++) if (kindOf(k) === "stop") n++; return n; }
const hasEnd = () => points.length >= 2 && kindOf(points.length - 1) === "end";
const pinText = (kind, i) => kind === "start" ? "A" : kind === "end" ? "B" : kind === "stop" ? String(stopNumber(i)) : "";

function drawMarkers() {
  markers.forEach((m) => m.remove());
  markers = points.map((p, i) => {
    const kind = kindOf(i);
    const e = el("div", "pin " + kind + (isNight(i) ? " night" : ""), pinText(kind, i));
    e.title = p.name || t(kind === "via" ? "via" : "map_point");
    const m = new maplibregl.Marker({ element: e, draggable: true }).setLngLat([p.lon, p.lat]).addTo(map);
    m.on("dragstart", () => { draggingLine = true; closeMenu(); });
    m.on("drag", () => drawDrag(points[i - 1], m.getLngLat(), points[i + 1]));
    m.on("dragend", async () => {
      const ll = m.getLngLat();
      drawDrag();
      const moved = (points[i] = { ...points[i], lat: ll.lat, lon: ll.lng });
      setTimeout(() => { draggingLine = false; }, 50);
      changed();
      if (!moved.via) { moved.name = await placeName(ll.lat, ll.lng); drawList(); drawSummary(); }
    });
    // A click (or right-click) asks what to do with the point. (A drag ends in a click too: not that one.)
    e.addEventListener("click", (ev) => { ev.stopPropagation(); if (!draggingLine) pointMenu(p); });
    e.addEventListener("contextmenu", (ev) => { ev.preventDefault(); ev.stopPropagation(); pointMenu(p); });
    return m;
  });
}

// ---------- The list of places ----------
// ---------- Undo ----------
// Every change to the points (added, moved, removed, made a stop or via point, all cleared) can be taken back, one at a
// time. A name that arrives later is not a change of its own: it goes into the state it belongs to.
const undoStack = [];
const placesKey = () => JSON.stringify(points.map((p) => [p.lat, p.lon, !!p.via, !!p.stop, !!p.night]));
let lastKey = placesKey(), lastState = JSON.stringify(points);
function remember() {
  const key = placesKey();
  if (key !== lastKey) { undoStack.push(lastState); if (undoStack.length > 100) undoStack.shift(); lastKey = key; }
  lastState = JSON.stringify(points);
}
/** A trip opened from a device starts afresh: undo does not go back past it. */
function forgetUndo() { undoStack.length = 0; lastKey = placesKey(); lastState = JSON.stringify(points); }
function undo() {
  if (!undoStack.length) return;
  closeMenu();
  if (viewOnly) { viewOnly = false; editing = null; }
  lastState = undoStack.pop();
  points = JSON.parse(lastState);
  lastKey = placesKey();
  changed();
}
document.addEventListener("keydown", (e) => {
  if ((e.ctrlKey || e.metaKey) && !e.shiftKey && e.key.toLowerCase() === "z" && !/^(INPUT|SELECT|TEXTAREA)$/.test(document.activeElement?.tagName)) {
    e.preventDefault();
    undo();
  }
});

/** A stop where the rider stays the night: the next day starts there. */
const isNight = (i) => !!points[i]?.night && kindOf(i) === "stop" && i < points.length - 1;
const dur = (s) => { const h = Math.floor(s / 3600), m = Math.round((s % 3600) / 60); return h ? `${h} ${t("h")} ${m} ${t("min")}` : `${m} ${t("min")}`; };
const kmText = (m) => `${Math.round(m / 1000)} ${t("km")}`;

/**
 * Each leg's numbers (points[i] to points[i + 1]) and where each point is on the line ([cuts], indexes into it). From the
 * legs the planner sent; for a line from elsewhere (a device's trip, a GPX file) from where the points lie along it.
 */
const legMemo = new WeakMap();
function legStats() {
  const r = result;
  if (!r || viewOnly || points.length < 2 || r.line.length < 2) return null;
  if (r.legs && r.cuts && r.legs.length === points.length - 1) return { legs: r.legs, cuts: r.cuts };
  const key = placesKey();
  const memo = legMemo.get(r);
  if (memo?.key === key) return memo.st;
  const line = r.line;
  const d = (a, b) => (a[0] - b[0]) ** 2 + ((a[1] - b[1]) * Math.cos((a[0] * Math.PI) / 180)) ** 2;
  const cuts = [0];
  let from = 0;
  for (let i = 1; i < points.length - 1; i++) {
    let best = from, bd = Infinity;
    for (let k = from; k < line.length; k++) { const v = d(line[k], [points[i].lat, points[i].lon]); if (v < bd) { bd = v; best = k; } }
    cuts.push((from = best));
  }
  cuts.push(line.length - 1);
  const along = alongOf(r);
  const legs = cuts.slice(1).map((b, n) => {
    const a = cuts[n], m = along[b] - along[a];
    let up = 0;
    if (r.ele) for (let k = a + 1; k <= b; k++) up += Math.max(0, r.ele[k] - r.ele[k - 1]);
    return { m, s: r.m ? (r.s * m) / r.m : 0, up: Math.round(up) };
  });
  const st = { legs, cuts };
  legMemo.set(r, { key, st });
  return st;
}

/** Metres along [r]'s line at each of its points, scaled so that the end says what the summary says. */
const alongMemo = new WeakMap();
function alongOf(r) {
  if (alongMemo.has(r)) return alongMemo.get(r);
  const line = r.line, out = [0];
  for (let k = 1; k < line.length; k++) {
    const [a, b] = [line[k - 1], line[k]];
    out.push(out[k - 1] + Math.hypot(a[0] - b[0], (a[1] - b[1]) * Math.cos((a[0] * Math.PI) / 180)) * 111195);
  }
  const f = r.m && out[out.length - 1] ? r.m / out[out.length - 1] : 1;
  const scaled = out.map((v) => v * f);
  alongMemo.set(r, scaled);
  return scaled;
}

/** The days of the trip: from the start, or a night's stop, to the next night's stop or the end ([from, to] point indexes). */
function days() {
  const out = [];
  let from = 0;
  for (let i = 1; i < points.length; i++) if (isNight(i) || i === points.length - 1) { out.push([from, i]); from = i; }
  return out;
}
const hasNights = () => points.some((_, i) => isNight(i));
/** The numbers from point [a] to point [b], summed over the legs between. */
function between(st, a, b) {
  const legs = st.legs.slice(a, b);
  return { m: legs.reduce((x, l) => x + l.m, 0), s: legs.reduce((x, l) => x + l.s, 0), up: legs.reduce((x, l) => x + (l.up || 0), 0) };
}

function drawList() {
  remember();
  const ol = $("stops");
  ol.replaceChildren();
  // Under each place, how far and how long from the place before it; with nights, a line for each day.
  const st = legStats();
  const dayList = hasNights() ? days() : [];
  const dayHead = (n) => {
    const [a, b] = dayList[n];
    const li = el("li", "day");
    li.append(el("b", "", t("day_n", n + 1)));
    if (st) { const x = between(st, a, b); li.append(el("small", "", `${kmText(x.m)} · ${dur(x.s)}`)); }
    ol.append(li);
  };
  if (dayList.length) dayHead(0);
  let prevMajor = 0;
  // Three via points or more in a row (an adventure has up to 16) are one line, opened with a click.
  const runOf = (i) => { let n = 0; while (i + n < points.length && kindOf(i + n) === "via") n++; return n; };
  points.forEach((p, i) => {
    const kind = kindOf(i);
    if (kind === "via" && !viasOpen) {
      const first = kindOf(i - 1) !== "via";
      let from = i; while (kindOf(from - 1) === "via") from--;
      const n = runOf(from);
      if (n >= 3) {
        if (first) {
          const li = el("li", "via group");
          const name = el("span", "name", t("vias_n", n));
          name.title = t("show_all");
          name.onclick = () => { viasOpen = true; drawList(); };
          li.append(el("span", "dot", ""), name);
          ol.append(li);
        }
        return;
      }
    }
    const li = el("li", kind + (isNight(i) ? " night" : ""));
    const name = el("span", "name", kind === "via" ? t("via") : (p.name || t("map_point")) + (isNight(i) ? " ☾" : ""));
    name.title = name.textContent;
    name.onclick = () => map.easeTo({ center: [p.lon, p.lat], zoom: Math.max(map.getZoom(), 11) });
    li.append(el("span", "dot", pinText(kind, i)), name);
    if (kind !== "via" && i > 0 && st) {
      const x = between(st, prevMajor, i);
      const leg = el("small", "leg", `${kmText(x.m)} · ${dur(x.s)}`);
      leg.title = `${points[prevMajor].name || t("map_point")} → ${p.name || t("map_point")}`;
      li.append(leg);
    }
    if (kind !== "via") prevMajor = i;
    const btn = (label, title, fn) => { const b = el("button", "", label); b.type = "button"; b.title = title; b.ariaLabel = title; b.onclick = fn; li.append(b); };
    if (i > 0) btn("↑", t("up"), () => { [points[i - 1], points[i]] = [points[i], points[i - 1]]; changed(); });
    if (i < points.length - 1) btn("↓", t("down"), () => { [points[i + 1], points[i]] = [points[i], points[i + 1]]; changed(); });
    btn("✕", t("remove"), () => { points.splice(i, 1); changed(); });
    ol.append(li);
    if (isNight(i)) dayHead(dayList.findIndex(([a]) => a === i));
  });
  $("clear").hidden = points.length === 0;
  $("undo").hidden = undoStack.length === 0;
  drawAdventure();
  drawDrafts();
  $("undo").title = t("undo_title");
  $("hint").textContent = viewOnly ? t("view_only") : editing?.ref ? t("editing_from", editing.dev) : t("hint_empty");
  $("hint").hidden = !viewOnly && !editing?.ref && points.length >= 2;
}

function addPlace(p) {
  if (viewOnly) { viewOnly = false; result = null; editing = null; }
  points.push({ name: p.name, lat: p.lat, lon: p.lon, via: false });
  changed();
}

function removePoint(p) {
  const i = points.indexOf(p);
  if (i >= 0) { points.splice(i, 1); changed(); }
}

/**
 * A clicked place [p] ({ lat, lon, naming: Promise of its name }) put in as [kind]. Without a destination yet it goes
 * last (the rider works forward); with one, a stop or via point goes on [leg], or else on the leg it bends least.
 */
async function addAt(p, kind, leg) {
  if (viewOnly) { viewOnly = false; result = null; editing = null; points = []; }
  const pt = { name: t("map_point"), lat: p.lat, lon: p.lon, via: kind === "via", ...(kind === "stop" ? { stop: true } : {}) };
  if (kind === "start" || kind === "end" || !hasEnd()) points.push(pt);
  else points.splice((leg ?? bestLeg(pt)) + 1, 0, pt);
  changed();
  if (kind === "via") return;
  pt.name = await p.naming;
  if (points.includes(pt)) { drawList(); drawMarkers(); drawSummary(); }
}

/** The leg (points[i] to points[i + 1]) that [p] lengthens least, as the crow flies. */
function bestLeg(p) {
  const d = (a, b) => Math.hypot(a.lat - b.lat, (a.lon - b.lon) * Math.cos((a.lat * Math.PI) / 180));
  let best = 0, bd = Infinity;
  for (let i = 0; i < points.length - 1; i++) {
    const v = d(points[i], p) + d(p, points[i + 1]) - d(points[i], points[i + 1]);
    if (v < bd) { bd = v; best = i; }
  }
  return best;
}

// ---------- The little menu on the map ----------
let menu = null;
function closeMenu() { menu?.remove(); menu = null; }

/**
 * A menu at [ll] with a [title], [items] ([label, action, class]; an action that returns "keep" leaves it open) and
 * lines of [notes] under them (above them when [infoFirst]); returns the title, to name the place once known.
 */
function showMenu(ll, title, items, notes, infoFirst = false) {
  closeMenu();
  const box = el("div", "menu");
  const head = el("div", "menu-title", title);
  box.append(head);
  const lines = [notes].flat().filter(Boolean).map((n) => el("small", "", n));
  if (infoFirst) box.append(...lines);
  for (const [label, fn, cls, icon] of items) {
    const b = el("button", cls || "", label);
    b.type = "button";
    // The point's own mark, as on the map: green A, white via ring, orange stop with its number, red B.
    if (icon) b.prepend(el("span", "dot " + icon[0], icon[1] ?? ""));
    b.onclick = (ev) => { ev.stopPropagation(); if (fn(b) !== "keep") closeMenu(); };
    box.append(b);
  }
  if (!infoFirst) box.append(...lines);
  // On a phone the sheet covers the bottom of the map: the menu goes above the point unless that is near the top.
  const y = map.project(ll).y;
  const anchor = innerWidth <= 640 ? (y < 220 ? "top" : "bottom") : undefined;
  menu = new maplibregl.Popup({ closeButton: false, closeOnClick: false, className: "curveon-menu", offset: 16, maxWidth: "280px", anchor })
    .setLngLat(ll).setDOMContent(box).addTo(map);
  return head;
}
document.addEventListener("keydown", (e) => { if (e.key === "Escape") closeMenu(); });

/**
 * What a click on the map can make of the place: first the start; then, step by step forward, a via point, stop or the
 * destination; with a destination, a stop or via point on the way, or a new destination. On the route: a stop or via
 * point on that part of it.
 */
function placeMenu(ll, onRoute, known) {
  // A line shown only (a device's ride or loop): a via point or stop on it first makes it a trip with points along it.
  const shown = viewOnly && onRoute;
  const p = { lat: ll.lat, lon: ll.lng };
  p.naming = known ? Promise.resolve(known) : placeName(p.lat, p.lon);
  const n = viewOnly && !shown ? 0 : points.length;
  const leg = onRoute && !shown && n >= 2 ? legAt(ll) : undefined;
  const onLine = (kind) => () => { if (shown) lineToTrip(); addAt(p, kind, shown ? legAt(ll) : leg); };
  // The number a stop would get where it goes in.
  const stopNo = (at) => String(points.slice(1, at + 1).filter((_, k) => kindOf(k + 1) === "stop").length + 1);
  const at = shown ? 0 : leg ?? (hasEnd() ? bestLeg(p) : points.length - 1);
  const items = onRoute ? [[t("menu_via"), onLine("via"), "", ["via"]], [t("menu_stop"), onLine("stop"), "", ["stop", stopNo(at)]]]
    : n === 0 ? [[t("menu_start"), () => addAt(p, "start"), "", ["start", "A"]]]
    : [[t("menu_via"), () => addAt(p, "via"), "", ["via"]], [t("menu_stop"), () => addAt(p, "stop"), "", ["stop", stopNo(at)]],
      [t(hasEnd() ? "menu_new_end" : "menu_end"), () => addAt(p, "end"), "", ["end", "B"]]];
  const head = showMenu(ll, "…", items);
  p.naming.then((name) => { head.textContent = name; });
}

/** A click on a point: a stop can become a via point and back, and any point can go. Dragging moves it. */
function pointMenu(p) {
  const i = points.indexOf(p);
  if (i < 0) return;
  const kind = kindOf(i);
  const last = i > 0 && i === points.length - 1;
  const redraw = async () => {
    drawList(); drawMarkers(); drawSummary();
    if (!p.via && !p.name) { p.name = await placeName(p.lat, p.lon); drawList(); drawMarkers(); drawSummary(); }
  };
  const items = [];
  const no = String(points.slice(1, i).filter((_, k) => kindOf(k + 1) === "stop").length + 1);
  if (kind === "stop" || kind === "end") items.push([t("make_via"), () => { p.via = true; delete p.stop; delete p.night; redraw(); }, "", ["via"]]);
  if (kind === "via" || kind === "end") items.push([t("make_stop"), () => { p.via = false; if (last) p.stop = true; redraw(); }, "", ["stop", no]]);
  if (last && kind !== "end") items.push([t("make_end"), () => { p.via = false; delete p.stop; redraw(); }, "", ["end", "B"]]);
  // A stop on the way can be where the day ends: the trip shows day by day, and can go to Curveon a day at a time.
  if (kind === "stop" && !last) items.push([t(p.night ? "night_not" : "night_here"), () => { if (p.night) delete p.night; else p.night = true; redraw(); drawSummary(); }, "", ["stop night", "☾"]]);
  items.push([t("remove"), () => removePoint(p), "danger"]);
  showMenu([p.lon, p.lat], kind === "via" ? t("via") : p.name || t("map_point"), items, t("drag_to_move"));
}

// ---------- Search ----------
let searchSeq = 0;
let hits = [];
let picked = -1;

async function search(q) {
  const seq = ++searchSeq;
  if (q.trim().length < 2) return showHits([]);
  const c = map.getCenter();
  const photon = fetch(`https://photon.komoot.io/api/?q=${encodeURIComponent(q)}&limit=8&lat=${c.lat}&lon=${c.lng}`)
    .then((r) => r.json()).then((j) => j.features.map((f) => {
      const p = f.properties;
      const name = p.name || (p.street ? `${p.street}${p.housenumber ? " " + p.housenumber : ""}` : null);
      return name && { name, area: p.city || p.county || p.country || "", lat: f.geometry.coordinates[1], lon: f.geometry.coordinates[0] };
    }).filter(Boolean)).catch(() => []);
  const names = fetch(`https://ws.geonorge.no/stedsnavn/v1/navn?sok=${encodeURIComponent(q.trim() + "*")}&treffPerSide=8&utkoordsys=4258`)
    .then((r) => r.json()).then((j) => (j.navn || []).map((n) => n.representasjonspunkt && {
      name: n.skrivemåte, area: n.kommuner?.[0]?.kommunenavn || "", lat: n.representasjonspunkt.nord, lon: n.representasjonspunkt["øst"],
    }).filter(Boolean)).catch(() => []);
  const [a, b] = await Promise.all([photon, names]);
  if (seq !== searchSeq) return;
  const all = [];
  for (const h of [...a, ...b]) if (!all.some((x) => x.name === h.name && Math.abs(x.lat - h.lat) + Math.abs(x.lon - h.lon) < 0.01)) all.push(h);
  showHits(all.slice(0, 10));
}

function showHits(list) {
  hits = list;
  picked = list.length ? 0 : -1;
  $("hits").replaceChildren(...list.map((h, i) => {
    const li = el("li");
    li.role = "option";
    li.ariaSelected = String(i === picked);
    li.append(el("span", "", h.name), el("small", "", h.area));
    li.onmousedown = (e) => { e.preventDefault(); choose(i); };
    return li;
  }));
}

function choose(i) {
  const h = hits[i];
  if (!h) return;
  $("q").value = "";
  showHits([]);
  addPlace(h);
  map.easeTo({ center: [h.lon, h.lat], zoom: Math.max(map.getZoom(), 9) });
}

let searchTimer;
$("q").addEventListener("input", (e) => { clearTimeout(searchTimer); searchTimer = setTimeout(() => search(e.target.value), 250); });
$("q").addEventListener("keydown", (e) => {
  if (e.key === "ArrowDown" || e.key === "ArrowUp") {
    e.preventDefault();
    picked = Math.max(0, Math.min(hits.length - 1, picked + (e.key === "ArrowDown" ? 1 : -1)));
    [...$("hits").children].forEach((li, i) => { li.ariaSelected = String(i === picked); });
  } else if (e.key === "Enter") { e.preventDefault(); choose(picked); }
  else if (e.key === "Escape") showHits([]);
});
$("q").addEventListener("blur", () => setTimeout(() => showHits([]), 150));

/** A name for a point clicked on the map: the nearest place Photon knows, else "Map point". */
async function placeName(lat, lon) {
  try {
    const j = await (await fetch(`https://photon.komoot.io/reverse?lat=${lat}&lon=${lon}&limit=1`)).json();
    const p = j.features?.[0]?.properties;
    return p?.name || p?.street || p?.city || p?.county || t("map_point");
  } catch {
    return t("map_point");
  }
}

/**
 * The line on the map as a trip: its ends as start and destination and points along it about every 15 km (as an
 * adventure has), so that it is routed along the same roads when changed. A new trip: the device's own stays as it was.
 */
function pointsAlong(line, m) {
  let next = 0, along = 0;
  const count = Math.max(2, Math.min(16, Math.round(m / 15000)));
  const vias = [];
  for (let k = 1; k < line.length - 1; k++) {
    along += Math.hypot(line[k][0] - line[k - 1][0], (line[k][1] - line[k - 1][1]) * Math.cos((line[k][0] * Math.PI) / 180)) * 111195;
    if (next < count && along >= (m * (next + 1)) / (count + 1)) { vias.push({ name: "", lat: line[k][0], lon: line[k][1], via: true }); next++; }
  }
  const [a, b] = [line[0], line[line.length - 1]];
  return [{ name: t("start"), lat: a[0], lon: a[1], via: false }, ...vias, { name: t("map_point"), lat: b[0], lon: b[1], via: false }];
}
function lineToTrip() {
  if (!viewOnly || !result) return;
  points = pointsAlong(result.line, result.m);
  viewOnly = false;
  editing = null;
  viasOpen = false;
  forgetUndo();
  for (const p of [points[0], points[points.length - 1]]) placeName(p.lat, p.lon).then((n) => { p.name = n; drawList(); drawMarkers(); });
}

// ---------- The middle mouse button moves the map ----------
// Always, also while an area is drawn (when the left button draws), as in most map and drawing programs.
map.getCanvasContainer().addEventListener("mousedown", (e) => {
  if (e.button !== 1) return;
  e.preventDefault(); // no autoscroll
  let last = [e.clientX, e.clientY];
  const cursor = map.getCanvas().style.cursor;
  map.getCanvas().style.cursor = "grabbing";
  const move = (ev) => { map.panBy([last[0] - ev.clientX, last[1] - ev.clientY], { animate: false }); last = [ev.clientX, ev.clientY]; };
  const up = (ev) => {
    if (ev.button !== 1) return;
    removeEventListener("mousemove", move);
    removeEventListener("mouseup", up);
    map.getCanvas().style.cursor = cursor;
  };
  addEventListener("mousemove", move);
  addEventListener("mouseup", up);
});
// Linux pastes on a middle click: not on the map.
map.getCanvasContainer().addEventListener("auxclick", (e) => { if (e.button === 1) e.preventDefault(); });

// ---------- Clicking and dragging on the map ----------
let draggingLine = false;
/** A click on the line already asked what to do (on letting go): the click event that may follow is not another. */
let skipClickUntil = 0;
// The map hears clicks on the points and the menu before they do: those are theirs.
const onPinOrMenu = (e) => !!e.originalEvent.target.closest?.(".maplibregl-marker, .maplibregl-popup");
// A click asks what the place should be; a click beside an open menu only closes it.
map.on("click", (e) => {
  if (draggingLine || onPinOrMenu(e) || Date.now() < skipClickUntil) return;
  if (menu) { closeMenu(); return; }
  // A place on the map's layers (a fuel station, a viewpoint): the same menu, at the place and with its name.
  const poi = map.queryRenderedFeatures(e.point, { layers: ["cv-pois"] })[0];
  if (poi) {
    const [lon, lat] = poi.geometry.coordinates;
    placeMenu({ lat, lng: lon }, false, poi.properties.name || t("layer_" + poi.properties.k));
    return;
  }
  placeMenu(e.lngLat, !!result && map.queryRenderedFeatures(e.point, { layers: ["route-hit"] }).length > 0);
});
map.on("mouseenter", "cv-pois", () => { if (!adv?.drawing) map.getCanvas().style.cursor = "pointer"; });
map.on("mouseleave", "cv-pois", () => { if (!adv?.drawing) map.getCanvas().style.cursor = ""; });
// A right-click is the usual context menu: what is there (the road, the place, how far along the route), to copy or zoom.
map.on("contextmenu", (e) => {
  e.preventDefault();
  if (onPinOrMenu(e)) return;
  infoMenu(e.lngLat, e.point);
});

// Road classes in both maps (Norway's kind_detail, the online map's class), the bigger first.
const ROAD_KINDS = [
  [["motorway"], "road_motorway"], [["trunk", "primary"], "road_main"], [["secondary"], "road_secondary"],
  [["tertiary"], "road_minor"], [["unclassified", "residential", "living_street", "minor"], "road_local"],
  [["service"], "road_service"], [["track"], "road_track"], [["path", "footway", "cycleway", "bridleway", "steps", "pedestrian"], "road_path"],
];

/** The biggest road drawn within a few pixels of [pt], described from the map's own data; null if none. */
function roadAt(pt) {
  const box = [[pt.x - 6, pt.y - 6], [pt.x + 6, pt.y + 6]];
  let best = null, bestRank = Infinity;
  for (const f of map.queryRenderedFeatures(box)) {
    if (f.sourceLayer !== "roads" && f.sourceLayer !== "transportation") continue;
    const p = f.properties;
    const cls = String(p.kind_detail || p.class || "").replace(/_link$/, "");
    const rank = ROAD_KINDS.findIndex(([names]) => names.includes(cls));
    if (rank < 0) continue;
    // Of the same class, the one with a name or number tells more.
    const r = rank - (p.name || p.ref ? 0.5 : 0);
    if (r < bestRank) { bestRank = r; best = { p, kind: ROAD_KINDS[rank][1], link: /_link$/.test(p.kind_detail || "") || !!p.ramp }; }
  }
  if (!best) return null;
  const { p } = best;
  const what = [t(best.link ? "road_ramp" : best.kind)];
  if (p.is_tunnel || p.brunnel === "tunnel") what.push(t("road_tunnel"));
  if (p.is_bridge || p.brunnel === "bridge") what.push(t("road_bridge"));
  if (p.oneway === "yes" || p.oneway === 1) what.push(t("road_oneway"));
  if (p.surface === "paved" || p.surface === "unpaved") what.push(t("road_" + p.surface));
  return { name: [p.ref, p.name].filter(Boolean).join(" · "), what: what.join(", ") };
}

/** How far along the route [pt] is, if it is on the line: "km 45 of 134". */
function alongRoute(pt) {
  if (!result?.line?.length) return null;
  const line = result.line;
  const m = (a, b) => Math.hypot(a[0] - b[0], (a[1] - b[1]) * Math.cos((a[0] * Math.PI) / 180));
  let best = -1, bd = Infinity;
  for (let k = 0; k < line.length; k++) {
    const q = map.project([line[k][1], line[k][0]]);
    const d = Math.hypot(q.x - pt.x, q.y - pt.y);
    if (d < bd) { bd = d; best = k; }
  }
  if (bd > 14) return null;
  let upTo = 0, all = 0;
  for (let k = 1; k < line.length; k++) { const d = m(line[k - 1], line[k]); all += d; if (k <= best) upTo += d; }
  // Scaled to the planner's own length, so the end says the same as the summary.
  return t("on_route", Math.round((upTo / all) * result.m / 1000), Math.round(result.m / 1000));
}

/** The right-click menu: the road there and what kind it is, the place, how far along the route, the coordinates. */
function infoMenu(ll, pt) {
  const road = roadAt(pt);
  const coords = `${ll.lat.toFixed(5)}, ${ll.lng.toFixed(5)}`;
  const items = [[t("copy_coords"), (b) => {
    navigator.clipboard?.writeText(coords).then(() => { b.textContent = t("copied"); }, () => {});
    return "keep";
  }]];
  if (map.getZoom() < 14) items.push([t("zoom_here"), () => map.easeTo({ center: ll, zoom: Math.min(16, map.getZoom() + 3) })]);
  const head = showMenu(ll, road?.name || "…", items, [road?.what, alongRoute(pt), coords], true);
  // The place's name: under the road's, or the title when the road has none.
  placeName(ll.lat, ll.lng).then((name) => { if (road?.name) head.after(el("small", "place", name)); else head.textContent = name; });
}

// Drag the line: a through-point goes in where it was grabbed, on the leg it belongs to.
map.on("mouseenter", "route-hit", () => { map.getCanvas().style.cursor = "grab"; });
map.on("mouseleave", "route-hit", () => { map.getCanvas().style.cursor = ""; });
map.on("mousedown", "route-hit", (e) => startLineDrag(e));
map.on("touchstart", "route-hit", (e) => { if (e.points.length === 1) startLineDrag(e); });

function startLineDrag(e) {
  // Only the left button drags the line (the right one asks about the road there).
  if (!result || adv?.drawing || onPinOrMenu(e) || e.originalEvent.button > 0) return;
  if (!viewOnly && points.length < 2) return;
  e.preventDefault();
  closeMenu();
  draggingLine = true;
  const from = e.point, at = e.lngLat;
  let moved = false, ghost = null, leg = 0;
  // A via point follows the pointer only once it moves: a click on the line shows nothing until it is let go.
  const move = (ev) => {
    if (!moved && Math.hypot(ev.point.x - from.x, ev.point.y - from.y) <= 4) return;
    if (!moved) {
      moved = true;
      if (viewOnly) lineToTrip();
      leg = legAt(at);
      ghost = new maplibregl.Marker({ element: el("div", "pin via") }).setLngLat(ev.lngLat).addTo(map);
    }
    ghost.setLngLat(ev.lngLat);
    drawDrag(points[leg], ev.lngLat, points[leg + 1]);
  };
  const up = () => {
    map.off("mousemove", move); map.off("touchmove", move);
    if (!moved) {
      // A click on the line: a via point or a stop there? Asked here, not by the click that follows, which some
      // browsers (Chrome, Edge) never send after a press on the line.
      draggingLine = false;
      skipClickUntil = Date.now() + 400;
      placeMenu(at, true);
      return;
    }
    const ll = ghost.getLngLat();
    ghost.remove();
    drawDrag();
    points.splice(leg + 1, 0, { name: "", lat: ll.lat, lon: ll.lng, via: true });
    changed();
    setTimeout(() => { draggingLine = false; }, 50);
  };
  map.on("mousemove", move); map.on("touchmove", move);
  map.once("mouseup", up); map.once("touchend", up);
}

/** Which leg (from points[i] to points[i + 1]) the line is at [ll]: where the nearest line point falls between the places. */
function legAt(ll) {
  const line = result.line;
  const d = (a, b) => (a[0] - b[0]) ** 2 + ((a[1] - b[1]) * Math.cos((a[0] * Math.PI) / 180)) ** 2;
  const nearest = (q, from) => { let best = from, bd = Infinity; for (let k = from; k < line.length; k++) { const v = d(line[k], q); if (v < bd) { bd = v; best = k; } } return best; };
  const at = nearest([ll.lat, ll.lng], 0);
  let from = 0;
  for (let i = 1; i < points.length - 1; i++) {
    from = nearest([points[i].lat, points[i].lon], from);
    if (from > at) return i - 1;
  }
  return points.length - 2;
}

// ---------- Routing ----------
// Leg by leg, as the planner service routes: the page keeps every leg it got (from one point to the next, with the
// options, and the way the leg before arrived), and asks only for the legs it does not have. Dragging one point of a long
// trip is then two short searches, and going back to a road style already seen costs nothing. A newer request from this
// page makes the service give up the one before (TAB).
let routeCtl = null;
let routeTimer;
const TAB = b64(random(9));
const legCache = new Map(); // legKey → leg, oldest first
const legPairs = new Set(); // the same without the heading: a leg the page has some version of
const ll6 = (p) => `${p.lat.toFixed(6)},${p.lon.toFixed(6)}`;
const pairKey = (a, b, o) => `${ll6(a)};${ll6(b)};${JSON.stringify(o)}`;
const legKey = (a, b, o, heading) => `${pairKey(a, b, o)};${heading ?? "-"}`;
function keepLeg(a, b, o, heading, leg) {
  const k = legKey(a, b, o, heading);
  legCache.delete(k);
  legCache.set(k, leg);
  legPairs.add(pairKey(a, b, o));
  // A few hundred legs: a long evening's planning, a few MB at most.
  while (legCache.size > 400) legCache.delete(legCache.keys().next().value);
}

/** The places or options changed: the road is worked out again (a trip opened from a device keeps its line until then). */
function changed() {
  advCtl?.abort();
  closeCompare();
  drawList();
  drawMarkers();
  result = null;
  drawRoute();
  drawSummary();
  clearTimeout(routeTimer);
  routeTimer = setTimeout(route, 200);
}

/**
 * While the planner works for more than a moment: a chip on the map with what it does and the seconds so far, and an
 * adventure's or roundtrip's area breathing. Returns the function that ends it.
 */
function busy(label, delay = 700) {
  const t0 = Date.now();
  let timer = null, pulse = null;
  const show = () => {
    const sec = Math.floor((Date.now() - t0) / 1000);
    $("busy-text").textContent = label + (sec >= 2 ? " " + t("seconds", sec) : "");
    $("busy").hidden = false;
  };
  const start = setTimeout(() => {
    show();
    timer = setInterval(show, 1000);
    if (adv?.area) {
      let k = 0;
      pulse = setInterval(() => { if (map.getLayer("area-fill")) map.setPaintProperty("area-fill", "fill-opacity", 0.08 + 0.14 * (0.5 + 0.5 * Math.sin(k++ / 3))); }, 120);
    }
  }, delay);
  return () => {
    clearTimeout(start); clearInterval(timer); clearInterval(pulse);
    if (map.getLayer("area-fill")) map.setPaintProperty("area-fill", "fill-opacity", 0.1);
    $("busy").hidden = true;
  };
}

function setStatus(text, error = false) {
  $("status").textContent = text || "";
  $("status").classList.toggle("error", error);
}

/** Thrown with the service's error code, to show in the rider's language. */
class Refused extends Error { constructor(code, minutes) { super(code); this.code = code; this.minutes = minutes; } }

/**
 * The legs between [pts] with options [o], from the cache where it has them and from the service where not ([onLeg]
 * hears of each as it comes). [tab] names the search, so the service drops this page's search before.
 */
async function legsFor(pts, o, signal, tab, onLeg = () => {}) {
  const legs = [];
  let i = 0;
  while (i < pts.length - 1) {
    const heading = i === 0 ? null : legs[i - 1].arrive;
    const have = legCache.get(legKey(pts[i], pts[i + 1], o, heading));
    if (have) { legs[i] = have; onLeg(legs); i++; continue; }
    // Ask for the legs from here up to one the page already has some version of (it most likely still fits).
    let j = i + 1;
    while (j < pts.length - 1 && !legPairs.has(pairKey(pts[j], pts[j + 1], o))) j++;
    const r = await fetch(API, {
      method: "POST", signal, headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ points: pts.slice(i, j + 1).map((p) => [p.lat, p.lon]), options: o, heading, legs: true, tab }),
    });
    const res = await r.json().catch(() => ({ error: "failed" }));
    if (!r.ok) throw new Refused(res.error || "failed", res.minutes);
    res.legs.forEach((leg, n) => {
      keepLeg(pts[i + n], pts[i + n + 1], o, n === 0 ? heading : res.legs[n - 1].arrive, leg);
      legs[i + n] = leg;
    });
    onLeg(legs);
    i = j;
  }
  return legs;
}

/** One trip from its legs: the line and heights end to end, where each point is on it (cuts), and the numbers. */
function joinLegs(legs) {
  const line = [], ele = [], cuts = [0], fuel = [], surf = [];
  let m = 0, deg = 0;
  legs.forEach((l, n) => {
    for (const [a, b, k] of l.surf || []) surf.push([m + a, m + b, k]);
    for (const f of l.fuel || []) if (!fuel.length || m + f[0] > fuel[fuel.length - 1][0] + 50) fuel.push([m + f[0], f[1], f[2], f[3]]);
    for (let k = n === 0 ? 0 : 1; k < l.line.length; k++) { line.push(l.line[k]); ele.push(l.ele?.[k] ?? 0); }
    cuts.push(line.length - 1);
    m += l.m;
    deg += l.dpk * l.m;
  });
  const sum = (key) => legs.reduce((a, l) => a + (l[key] || 0), 0);
  return {
    line, ele: legs.every((l) => l.ele) ? ele : null, cuts, m, s: sum("s"), up: sum("up"),
    curvy: m ? Math.min(100, Math.floor(deg / m / 3)) : 0, ferries: sum("ferries"), unpaved: sum("unpaved"),
    // Fuel only when the service knows the stations (else nothing is said about it).
    fuel: legs.every((l) => l.fuel) ? fuel : null,
    surf,
    legs: legs.map((l) => ({ m: l.m, s: l.s, up: l.up, dpk: l.dpk, ferries: l.ferries, unpaved: l.unpaved, arrive: l.arrive })),
  };
}

/**
 * The legs of a [r] made from legs (an adventure, a roundtrip, a draft) back in the cache for these points and options,
 * so that changing it later asks only for what changed.
 */
function adoptLegs(r, pts, o) {
  if (!r?.legs || !r.cuts || r.legs.length !== pts.length - 1 || r.cuts.length !== pts.length) return;
  let along = 0;
  r.legs.forEach((l, n) => {
    const [a, b] = [r.cuts[n], r.cuts[n + 1]];
    const fuel = r.fuel?.filter((f) => f[0] >= along && f[0] < along + l.m).map((f) => [f[0] - along, f[1], f[2], f[3]]);
    const surf = (r.surf || []).filter(([x, y]) => y > along && x < along + l.m).map(([x, y, k]) => [Math.max(0, x - along), Math.min(l.m, y - along), k]);
    keepLeg(pts[n], pts[n + 1], o, n === 0 ? null : r.legs[n - 1].arrive,
      { ...l, line: r.line.slice(a, b + 1), ele: r.ele ? r.ele.slice(a, b + 1) : null, surf, ...(r.fuel ? { fuel } : {}) });
    along += l.m;
  });
}

async function route() {
  routeCtl?.abort();
  if (points.length < 2) { setStatus(""); return; }
  const ctl = (routeCtl = new AbortController());
  const pts = points.slice(), o = routeOptions();
  setStatus(t("routing"));
  const slow = setTimeout(() => { if (routeCtl === ctl) setStatus(t("queued")); }, 6000);
  const done = busy(t("routing"), 1200);
  try {
    // The legs there already show while the rest are worked out.
    const legs = await legsFor(pts, o, ctl.signal, TAB, (got) => {
      if (routeCtl !== ctl) return;
      const known = got.filter(Boolean);
      map.getSource("route")?.setData({ type: "Feature", geometry: { type: "MultiLineString", coordinates: known.map((l) => l.line.map(([la, lo]) => [lo, la])) } });
    });
    if (routeCtl !== ctl) return;
    result = joinLegs(legs);
    setStatus("");
    drawRoute();
    drawList();
    drawSummary();
    // A road style picked while comparing: the comparison stays, from the legs already here.
    if (compareKeep) { compareKeep = false; openCompare(); }
  } catch (e) {
    if (routeCtl !== ctl || e.name === "AbortError" || e.code === "superseded") return;
    drawRoute();
    setStatus(e instanceof Refused ? t("err_" + e.code, e.minutes) : t("err_offline"), true);
  } finally {
    clearTimeout(slow);
    done();
  }
}

// ---------- Adventure and roundtrip ----------
// As in the app. An adventure: from the start to the destination, the most fun roads inside an area the rider draws,
// within a time. A roundtrip: from the start round an area the rider draws and back, at most so long. Either answer
// becomes an ordinary trip (via points along it), to change and send like any other.
const touchy = matchMedia("(pointer: coarse)").matches;

function areaData() {
  const a = adv?.drawing ? adv.stroke : adv?.area;
  if (!a || a.length < 2) return emptyLine();
  const ring = a.map(([la, lo]) => [lo, la]);
  return adv.drawing ? { type: "Feature", geometry: { type: "LineString", coordinates: ring } }
    : { type: "Feature", geometry: { type: "Polygon", coordinates: [[...ring, ring[0]]] } };
}
const drawArea = () => map.getSource("area")?.setData(areaData());

/** [loop]: a roundtrip from the start; else an adventure from the start to the destination. */
function openAdventure(loop = false) {
  closeMenu();
  closeCompare();
  adv = { loop, area: null, ends: loop ? [points[0]] : [points[0], points[points.length - 1]], variant: 0, drawing: false, stroke: [] };
  fillTimes();
  startDrawing();
}

function startDrawing() {
  advCtl?.abort();
  Object.assign(adv, { drawing: true, stroke: [], area: null });
  map.dragPan.disable();
  map.getCanvas().style.cursor = "crosshair";
  drawArea();
  // On the map itself, where the rider looks next: what to do now, and a way out.
  $("draw-text").textContent = t(adv.loop ? (touchy ? "loop_draw_touch" : "loop_draw") : touchy ? "adv_draw_touch" : "adv_draw");
  $("draw").hidden = false;
  requestAnimationFrame(() => $("adv-box").scrollIntoView({ block: "nearest", behavior: "smooth" }));
  // As the app: drawn from no closer than zoom 9, so the area is a day's ride round, not a few streets.
  if (map.getZoom() > 9) map.easeTo({ zoom: 9, center: adv.loop && points[0] ? [points[0].lon, points[0].lat] : map.getCenter(), duration: 500 });
  drawAdventure(adv.loop ? t(touchy ? "loop_draw_touch" : "loop_draw")
    : t(touchy ? "adv_draw_touch" : "adv_draw") + (points.length > 2 ? " " + t("adv_stops") : ""));
}

function stopDrawing() {
  $("draw").hidden = true;
  map.dragPan.enable();
  map.getCanvas().style.cursor = "";
  if (adv) adv.drawing = false;
}

function closeAdventure() {
  advCtl?.abort();
  stopDrawing();
  adv = null;
  drawArea();
  drawAdventure();
}

/** The box in the panel: what to do now, the longest time (or length), and what to do next. */
function drawAdventure(text, error = false) {
  $("adv").hidden = !!adv || viewOnly || !hasEnd();
  // With only a start, a roundtrip from it.
  $("loop").hidden = !!adv || viewOnly || points.length !== 1;
  $("adv-box").hidden = !adv;
  if (!adv) return;
  $("adv-head").textContent = adv.loop ? "↻ " + t("loop_title") : "✦ " + t("adv_title");
  $("adv-time-label").textContent = t(adv.loop ? "loop_len" : "adv_time");
  if (text !== undefined) { $("adv-hint").textContent = text; $("adv-hint").classList.toggle("error", error); }
  $("adv-again").hidden = !adv.area || !!advCtl;
  $("adv-redraw").hidden = adv.drawing || !!advCtl;
}

/** Times for an adventure, lengths for a roundtrip (kept apart: an hour is not 60 km). */
function fillTimes() {
  const sel = $("adv-time");
  const loop = !!adv?.loop;
  const keep = store.get(loop ? "loopKm" : "advMinutes", "");
  // The app's choices: a loop round the whole area, or at most so long; an adventure within so many hours.
  const choices = loop ? [50, 80, 100, 150, 200, 300] : [60, 90, 120, 150, 180, 240, 300, 360, 480];
  sel.replaceChildren(el("option", "", t(loop ? "loop_max_any" : "adv_no_limit")), ...choices.map((v) => {
    const o = el("option", "", loop ? t("loop_max_km", v) : t("adv_hm", Math.floor(v / 60), v % 60));
    o.value = String(v);
    return o;
  }));
  sel.firstChild.value = "";
  sel.value = String(keep);
  sel.title = t(loop ? "loop_len_explain" : "adv_time_explain");
}

// Drawing the area: mouse button or one finger held down, round the area.
function strokeAt(e) {
  const s = adv.stroke, ll = e.lngLat, last = s[s.length - 1];
  if (last) { const a = map.project([last[1], last[0]]); if (Math.hypot(a.x - e.point.x, a.y - e.point.y) < 4) return; }
  s.push([ll.lat, ll.lng]);
  drawArea();
  // How far round it is so far, as the app tells while the finger draws.
  let m = 0;
  for (let k = 1; k < s.length; k++) m += Math.hypot(s[k][0] - s[k - 1][0], (s[k][1] - s[k - 1][1]) * Math.cos((s[k][0] * Math.PI) / 180)) * 111195;
  if (s.length > 2) m += Math.hypot(s[0][0] - s[s.length - 1][0], (s[0][1] - s[s.length - 1][1]) * Math.cos((s[0][0] * Math.PI) / 180)) * 111195;
  $("draw-text").textContent = t("roundtrip_draw_km", Math.round(m / 1000));
}
let stroking = false;
function strokeStart(e) {
  if (!adv?.drawing || (e.originalEvent.button ?? 0) !== 0) return;
  if (e.points && e.points.length !== 1) return;
  e.preventDefault();
  stroking = true;
  adv.stroke = [];
  strokeAt(e);
}
function strokeMove(e) { if (stroking && adv?.drawing) strokeAt(e); }
function strokeEnd() {
  if (!stroking || !adv?.drawing) return;
  stroking = false;
  // The click that ends the stroke is not a click on the map.
  draggingLine = true;
  setTimeout(() => { draggingLine = false; }, 50);
  // Round enough to be an area: some 8 km round at the least, as in the app.
  const s = adv.stroke;
  let m = 0;
  for (let k = 1; k <= s.length; k++) {
    const a = s[k - 1], b = s[k % s.length];
    m += Math.hypot(a[0] - b[0], (a[1] - b[1]) * Math.cos((a[0] * Math.PI) / 180)) * 111195;
  }
  if (s.length < 8 || m < 8000) { adv.stroke = []; drawArea(); drawAdventure(t("adv_too_small"), true); return; }
  // Thinned for the request: a point every few pixels is plenty.
  adv.area = s.filter((_, k) => k % Math.ceil(s.length / 400) === 0);
  stopDrawing();
  drawArea();
  findAdventure();
}
map.on("mousedown", strokeStart);
map.on("touchstart", strokeStart);
map.on("mousemove", strokeMove);
map.on("touchmove", strokeMove);
map.on("mouseup", strokeEnd);
map.on("touchend", strokeEnd);

async function findAdventure() {
  routeCtl?.abort();
  clearTimeout(routeTimer);
  advCtl?.abort();
  const ctl = (advCtl = new AbortController());
  const limit = $("adv-time").value ? Number($("adv-time").value) : null;
  const o = routeOptions();
  const loop = adv.loop;
  drawAdventure(t(loop ? "loop_searching" : "adv_searching"));
  setStatus("");
  $("adv-busy").hidden = false;
  const done = busy(t(loop ? "loop_title" : "adv_title") + ": " + t("compare_wait"), 0);
  try {
    const [start, end] = adv.ends;
    const ask = loop ? { start: [start.lat, start.lon], area: adv.area, km: limit, variant: adv.variant, options: o, tab: TAB }
      : { points: adv.ends.map((p) => [p.lat, p.lon]), area: adv.area, minutes: limit, variant: adv.variant, options: o, tab: TAB };
    const r = await fetch(BASE + (loop ? "api/roundtrip" : "api/adventure"), {
      method: "POST", signal: ctl.signal, headers: { "Content-Type": "application/json" }, body: JSON.stringify(ask),
    });
    const j = await r.json().catch(() => ({ error: "failed" }));
    if (advCtl !== ctl) return;
    advCtl = null;
    if (!r.ok) { if (j.error !== "superseded") drawAdventure(t("err_" + (j.error || "failed"), j.minutes), true); return; }
    // A roundtrip ends where it starts.
    const last = loop ? { ...start, via: false } : { ...end };
    points = [{ ...start }, ...j.vias.map(([lat, lon]) => ({ name: "", lat, lon, via: true })), last];
    delete points[points.length - 1].stop;
    delete points[points.length - 1].night;
    j.legs.forEach((leg, n) => keepLeg(points[n], points[n + 1], o, n === 0 ? null : j.legs[n - 1].arrive, leg));
    result = joinLegs(j.legs);
    viasOpen = false;
    drawList(); drawMarkers(); drawRoute(); drawSummary();
    drawAdventure(t(loop ? "loop_found" : "adv_found"));
  } catch (e) {
    if (e.name === "AbortError" || advCtl !== ctl) return;
    advCtl = null;
    drawAdventure(t("err_offline"), true);
  } finally {
    // Only this search's: a newer one has its own.
    if (advCtl === null || advCtl === ctl) { $("adv-busy").hidden = true; done(); }
    else done();
  }
}

$("adv").addEventListener("click", () => openAdventure(false));
$("loop").addEventListener("click", () => openAdventure(true));
$("adv-again").addEventListener("click", () => { adv.variant++; findAdventure(); });
$("adv-redraw").addEventListener("click", startDrawing);
$("adv-close").addEventListener("click", closeAdventure);
$("draw-cancel").addEventListener("click", closeAdventure);
document.addEventListener("keydown", (e) => { if (e.key === "Escape" && adv?.drawing) closeAdventure(); });
$("adv-time").addEventListener("change", () => {
  store.set(adv?.loop ? "loopKm" : "advMinutes", $("adv-time").value);
  if (adv?.area) findAdventure();
});

function routeOptions() {
  return { style: options.style, surface: options.surface, ferries: options.ferries, motorways: options.motorways, trunk: options.trunk, toll: false,
    // How far off the ordinary route for gravel: only with Gravel or Offroad, as in the app.
    ...(gravelSurface() && options.gravelReachKm ? { gravelReachKm: options.gravelReachKm } : {}) };
}
const gravelSurface = () => options.surface === "GRAVEL" || options.surface === "OFFROAD";

function drawSummary() {
  const s = $("summary");
  $("send-row").hidden = !result || viewOnly;
  $("route-block").hidden = !result;
  $("send-new").hidden = !result || viewOnly || !editing?.ref;
  $("send-as-row").hidden = !result || viewOnly || !!editing?.ref;
  // A trip with nights can go a day at a time.
  const daysOk = hasNights() && !!result;
  $("send-as").querySelector('[value="days"]').hidden = !daysOk;
  if (!daysOk && $("send-as").value === "days") $("send-as").value = "trip";
  $("share").hidden = !result || viewOnly || points.length < 2;
  drawFuelGap();
  drawProfile();
  $("compare").hidden = !result || viewOnly || points.length < 2 || compareOpen || !!adv;
  drawSaveBlock();
  if (!result) { s.hidden = true; return; }
  const h = Math.floor(result.s / 3600), m = Math.round((result.s % 3600) / 60);
  const part = (v, unit) => { const e = el("span", "", v); e.append(el("small", "", unit)); return e; };
  s.replaceChildren(
    part(Math.round(result.m / 1000), t("km")),
    ...(h ? [part(h, t("h")), part(m, t("min"))] : [part(m, t("min"))]),
    // Not known for a GPX file opened here.
    ...(result.curvy != null ? [part(`${result.curvy}/100`, t("curvy"))] : []),
    part(result.up, t("up_m")),
    ...(result.ferries ? [el("span", "", t("ferries_n", result.ferries))] : []),
    // How much of it is gravel and tracks (as the app tells), in the colours the route has on the map.
    ...surfaceParts(),
  );
  s.hidden = false;
  if (!$("name").value) $("name").placeholder = defaultName();
  $("send").textContent = editing?.ref ? t("update_on", devNames(editing)) : t("send");
}

/** "12 km gravel", "800 m tracks": from the route's stretches, when it has any. */
function surfaceParts() {
  const sum = [0, 0, 0];
  for (const [a, b, k] of result?.surf || []) sum[k] += b - a;
  const short = (m) => m < 1000 ? [`${Math.round(m / 100) * 100}`, "m"] : [`${m < 10000 ? (m / 1000).toFixed(1) : Math.round(m / 1000)}`, t("km")];
  return [[1, "gravel_amount", "#ff9100"], [2, "track_amount", "#d500f9"]].filter(([k]) => sum[k] >= 100).map(([k, key, colour]) => {
    const [v, unit] = short(sum[k]);
    const e = el("span", "surf", v);
    e.prepend(Object.assign(el("i", "swatch"), { style: `background:${colour}` }));
    e.append(el("small", "", `${unit} ${t(key)}`));
    return e;
  });
}

const defaultName = () => points.length >= 2 ? `${points[0].name || t("map_point")} – ${points[points.length - 1].name || t("map_point")}` : t("name_hint");

// ---------- Lists with icons ----------
// The road style, surface, ferries and motorways are picked from lists with a sign for each choice (a browser's own
// list shows text only). The page's <select> stays the one that holds the value; this shows it and sets it.
const stroke = (d, w = 2.2) => `<path d="${d}" fill="none" stroke="currentColor" stroke-width="${w}" stroke-linecap="round" stroke-linejoin="round"/>`;
const MOTORWAY = '<path fill="#1565c0" d="M4 1h16a3 3 0 0 1 3 3v16a3 3 0 0 1-3 3H4a3 3 0 0 1-3-3V4a3 3 0 0 1 3-3z"/><path fill="#fff" d="M3.5 5.5h17v2h-17zM4.5 21l5.7-11.5h1L9.6 21zM19.5 21l-5.7-11.5h-1l1.6 11.5z"/>';
const EROAD = '<path fill="#fff" d="M3 3h18a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z"/><path fill="#00843d" d="M4.2 4.3h15.6a1.5 1.5 0 0 1 1.5 1.5v12.4a1.5 1.5 0 0 1-1.5 1.5H4.2a1.5 1.5 0 0 1-1.5-1.5V5.8a1.5 1.5 0 0 1 1.5-1.5z"/><path fill="#fff" d="M8 7h8.2v2h-6v2h5v2h-5v2h6v2H8z"/>';
const BOAT = '<path fill="currentColor" d="M3 14h18l-3.2 5.5H6.2z"/>' + stroke("M7 13V9h10v4M12 9V5", 2);
// A red slash, as the app crosses out what the route keeps off.
const CROSS = '<path d="M3 3l18 18" stroke="#fff" stroke-width="5" stroke-linecap="round"/><path d="M3 3l18 18" stroke="#d32f2f" stroke-width="2.6" stroke-linecap="round"/>';
const DOTS = (pts) => pts.map(([x, y, r = 1.6]) => `<circle cx="${x}" cy="${y}" r="${r}" fill="currentColor"/>`).join("");
const OPTION_ICONS = {
  style: { FASTEST: stroke("M12 21V4M6 10l6-6 6 6"), CURVY: stroke("M10 21.5c0-3.5 6.5-3.5 6.5-7.5S7.5 10 7.5 6.5c0-2 1.5-3.5 3.5-3.5"),
    VERY_CURVY: stroke("M7 22c0-2.6 10-2 10-4.6S7 15.2 7 12.6s10-2 10-4.6S7 5.8 7 3", 2) },
  surface: { PAVED: stroke("M8 3 4 21M16 3l4 18") + stroke("M12 4v3M12 10.5v3M12 17v3", 1.8),
    MIXED: stroke("M8 3 4 21") + stroke("M11 5v3M11 11v3M11 17v3", 1.8) + DOTS([[16, 7], [18.5, 12], [15.5, 15.5], [19.5, 19]]),
    GRAVEL: DOTS([[6, 6], [12, 4.5, 1.8], [18, 7], [8.5, 11.5, 1.9], [15, 11], [5, 17], [11.5, 17.5, 2], [18.5, 16.5], [14.5, 21, 1.4]]),
    OFFROAD: '<path fill="currentColor" d="M2 20l6-10 4 6 3-4 7 8z"/>' + DOTS([[7, 22, 1], [12, 22, 1], [17, 22, 1]]) },
  ferries: { ok: BOAT, no: BOAT + CROSS },
  // The road straight on, and a detour off it and back: higher the further it may go.
  reach: Object.fromEntries([["", 15], ["1", 4], ["3", 6.5], ["5", 9], ["10", 11.5], ["20", 13.5]].map(([v, h]) =>
    [v, stroke("M2 19h20", 1.6) + stroke(`M6 19C6 ${19 - h} 18 ${19 - h} 18 19`, 2.2)])),
  motorways: { ok: MOTORWAY, no: MOTORWAY + CROSS, noe: EROAD + CROSS },
};
const iconSvg = (inner) => `<svg viewBox="0 0 24 24" width="22" height="22" aria-hidden="true">${inner}</svg>`;

function iconSelect(sel) {
  const icons = OPTION_ICONS[sel.id];
  const btn = el("button", "isel");
  btn.type = "button";
  btn.id = sel.id + "-btn";
  btn.ariaHasPopup = "listbox";
  sel.hidden = true;
  sel.after(btn);
  let list = null, at = 0;
  const opts = () => [...sel.options];
  const close = () => { list?.remove(); list = null; btn.ariaExpanded = "false"; document.removeEventListener("pointerdown", outside, true); };
  const outside = (e) => { if (!list?.contains(e.target) && e.target !== btn && !btn.contains(e.target)) close(); };
  const pick = (v) => { close(); if (sel.value !== v) { sel.value = v; sel.dispatchEvent(new Event("change")); } show(); btn.focus(); };
  const mark = () => [...list.children].forEach((li, k) => { li.ariaSelected = String(k === at); if (k === at) li.scrollIntoView({ block: "nearest" }); });
  function show() {
    const o = sel.selectedOptions[0];
    btn.innerHTML = iconSvg(icons[sel.value] || "");
    btn.append(el("span", "", o?.textContent || ""));
    btn.title = `${t("tip_" + sel.id)}: ${o?.textContent || ""}`;
  }
  /** Below the button, or above it when the screen ends first; as wide as the button at least. */
  function place() {
    if (!list) return;
    const r = btn.getBoundingClientRect();
    list.style.minWidth = r.width + "px";
    list.style.left = Math.max(8, Math.min(r.left, innerWidth - list.offsetWidth - 8)) + "px";
    list.style.top = (r.bottom + list.offsetHeight + 8 < innerHeight ? r.bottom + 4 : Math.max(8, r.top - list.offsetHeight - 4)) + "px";
  }
  function open() {
    if (list) return close();
    list = el("ul", "isel-list");
    list.role = "listbox";
    opts().forEach((o, k) => {
      const li = el("li");
      li.role = "option";
      li.dataset.value = o.value;
      li.innerHTML = iconSvg(icons[o.value] || "");
      li.append(el("span", "", o.textContent));
      li.onclick = () => pick(o.value);
      if (o.value === sel.value) { li.classList.add("current"); at = k; }
      list.append(li);
    });
    document.body.append(list);
    place();
    btn.ariaExpanded = "true";
    mark();
    document.addEventListener("pointerdown", outside, true);
  }
  btn.onclick = open;
  btn.onkeydown = (e) => {
    if (!list && ["ArrowDown", "ArrowUp"].includes(e.key)) { e.preventDefault(); open(); return; }
    if (!list) return;
    if (e.key === "Escape") { e.preventDefault(); close(); }
    else if (e.key === "ArrowDown" || e.key === "ArrowUp") { e.preventDefault(); at = Math.max(0, Math.min(sel.options.length - 1, at + (e.key === "ArrowDown" ? 1 : -1))); mark(); }
    else if (e.key === "Enter" || e.key === " ") { e.preventDefault(); pick(sel.options[at].value); }
  };
  // The panel scrolled (also just after opening, when the button was scrolled into view): the list goes with it.
  addEventListener("resize", place);
  $("body").addEventListener("scroll", place);
  show();
  return show;
}
const iconSelects = ["style", "surface", "ferries", "motorways", "reach"].map((id) => iconSelect($(id)));
/** After the value or the language changed: the buttons say it again. */
const drawSelects = () => iconSelects.forEach((show) => show());

// Options, as the app keeps them.
function readOptions() {
  options = {
    style: $("style").value, surface: $("surface").value, ferries: $("ferries").value === "no",
    motorways: $("motorways").value !== "ok", trunk: $("motorways").value === "noe",
    gravelReachKm: $("reach").value ? Number($("reach").value) : null,
  };
  store.set("options", options);
  if (!viewOnly) changed();
}
function showOptions() {
  $("style").value = options.style;
  $("surface").value = options.surface;
  $("ferries").value = options.ferries ? "no" : "ok";
  $("motorways").value = options.trunk ? "noe" : options.motorways ? "no" : "ok";
  $("reach").value = options.gravelReachKm ? String(options.gravelReachKm) : "";
  $("reach-row").hidden = !gravelSurface();
  if (typeof iconSelects !== "undefined") drawSelects();
}
["style", "surface", "ferries", "motorways", "reach"].forEach((id) => $(id).addEventListener("change", () => { readOptions(); showOptions(); drawDrafts(); }));

/** "Save and send" shows when there is something to save or send. */
function drawSaveBlock() {
  $("save-block").hidden = !result && $("save-draft").hidden;
  $("share-row").hidden = !result && $("save-draft").hidden;
  $("save-gpx").hidden = !result;
}

// ---------- Height profile ----------
// The heights along the route under the numbers: the pointer on it shows the place on the map, and how high it is.
function drawProfile() {
  const fig = $("profile");
  const r = result;
  if (!r?.ele || r.ele.length !== r.line.length || r.line.length < 2) { fig.hidden = true; map.getSource("cv-hover")?.setData(emptyLine()); return; }
  fig.hidden = false;
  const c = $("profile-canvas");
  const w = c.clientWidth || 340, hgt = 80, dpr = devicePixelRatio || 1;
  c.width = Math.round(w * dpr); c.height = Math.round(hgt * dpr);
  const g = c.getContext("2d");
  g.scale(dpr, dpr);
  const along = alongOf(r), total = along[along.length - 1] || 1;
  let lo = Infinity, hi = -Infinity;
  for (const e of r.ele) { lo = Math.min(lo, e); hi = Math.max(hi, e); }
  // Some room above and below; a flat trip is not drawn as mountains.
  const span = Math.max(hi - lo, 100);
  const base = lo - span * 0.08, top = base + span * 1.2;
  const y = (e) => hgt - 4 - ((e - base) / (top - base)) * (hgt - 8);
  const css = getComputedStyle(document.documentElement);
  // The highest point in each column of pixels, so a pass shows on a long trip.
  const cols = new Array(Math.ceil(w)).fill(-Infinity);
  for (let k = 0; k < r.ele.length; k++) { const x = Math.min(cols.length - 1, Math.floor((along[k] / total) * (w - 1))); cols[x] = Math.max(cols[x], r.ele[k]); }
  for (let x = 1; x < cols.length; x++) if (cols[x] === -Infinity) cols[x] = cols[x - 1];
  g.beginPath();
  g.moveTo(0, hgt);
  cols.forEach((e, x) => g.lineTo(x, y(e === -Infinity ? lo : e)));
  g.lineTo(w, hgt);
  g.closePath();
  g.fillStyle = "rgba(255, 145, 0, 0.28)";
  g.fill();
  g.beginPath();
  cols.forEach((e, x) => (x ? g.lineTo(x, y(e === -Infinity ? lo : e)) : g.moveTo(x, y(e === -Infinity ? lo : e))));
  g.strokeStyle = css.getPropertyValue("--amber-ink").trim() || "#8a4b00";
  g.lineWidth = 1.5;
  g.stroke();
  // The stops (and nights) as thin lines.
  const st = legStats();
  if (st) {
    g.strokeStyle = css.getPropertyValue("--muted").trim() || "#5f6672";
    for (let i = 1; i < points.length - 1; i++) {
      if (kindOf(i) === "via") continue;
      const x = Math.round((along[st.cuts[i]] / total) * (w - 1)) + 0.5;
      g.setLineDash(isNight(i) ? [] : [3, 3]);
      g.beginPath(); g.moveTo(x, 0); g.lineTo(x, hgt); g.stroke();
    }
    g.setLineDash([]);
  }
  g.fillStyle = css.getPropertyValue("--muted").trim() || "#5f6672";
  g.font = "11px system-ui, sans-serif";
  g.fillText(`${Math.round(hi)} m`, 4, 12);
  g.fillText(`${Math.round(lo)} m`, 4, hgt - 6);
  $("profile-at").textContent = t("profile_title");
  c.ariaLabel = `${t("profile_title")}: ${Math.round(lo)}–${Math.round(hi)} m`;
}

function profileAt(ev) {
  const r = result;
  if (!r?.ele) return;
  const c = $("profile-canvas");
  const box = c.getBoundingClientRect();
  const along = alongOf(r), total = along[along.length - 1];
  const want = Math.max(0, Math.min(1, (ev.clientX - box.left) / box.width)) * total;
  let a = 0, b = along.length - 1;
  while (b - a > 1) { const mid = (a + b) >> 1; if (along[mid] <= want) a = mid; else b = mid; }
  const [la, lo] = r.line[a];
  map.getSource("cv-hover")?.setData({ type: "Feature", geometry: { type: "Point", coordinates: [lo, la] } });
  $("profile-at").textContent = t("profile_at", Math.round(along[a] / 1000), r.ele[a]);
}
$("profile-canvas").addEventListener("pointermove", profileAt);
$("profile-canvas").addEventListener("pointerdown", profileAt);
$("profile-canvas").addEventListener("pointerleave", () => { map.getSource("cv-hover")?.setData(emptyLine()); $("profile-at").textContent = t("profile_title"); });
new ResizeObserver(() => { if (!$("profile").hidden) drawProfile(); }).observe($("panel"));

// ---------- Fuel along the route ----------
// As in the app: the fuel stations by the route, and a word when the longest stretch without one is too long for a
// motorcycle's tank. Only when the planner knows the stations there (it says nothing it does not know).
const LONG_GAP_M = 120_000;
function fuelGap() {
  const r = result;
  if (!r?.fuel || viewOnly) return null;
  const marks = [0, ...r.fuel.map((f) => f[0]), r.m];
  let best = null;
  for (let k = 1; k < marks.length; k++) if (!best || marks[k] - marks[k - 1] > best.to - best.from) best = { from: marks[k - 1], to: marks[k] };
  return best && best.to - best.from > LONG_GAP_M ? best : null;
}
function drawFuelGap() {
  const gap = fuelGap();
  $("fuel-gap").hidden = !gap;
  if (!gap) return;
  $("fuel-gap").textContent = t("fuel_gap", Math.round((gap.to - gap.from) / 1000), Math.round(gap.from / 1000));
  $("fuel-gap").title = t("fuel_gap_title");
}
$("fuel-gap").addEventListener("click", () => {
  const gap = fuelGap();
  if (!gap) return;
  const along = alongOf(result);
  gapLine = result.line.filter((_, k) => along[k] >= gap.from - 1 && along[k] <= gap.to + 1);
  drawRoute();
  fitTo(gapLine);
});

// ---------- Comparing road styles ----------
// Only when the rider asks: the same points as Fastest, Curvy and Super curvy, side by side, the others as thin lines.
// The legs stay in the cache, so picking one is instant.
const STYLES = [["FASTEST", "style_fastest"], ["CURVY", "style_curvy"], ["VERY_CURVY", "style_super_curvy"]];
let compareOpen = false, compareCtl = null, compareKeep = false;

function closeCompare() {
  compareCtl?.abort();
  compareCtl = null;
  compareOpen = false;
  alts = [];
  map.getSource("cv-alt")?.setData(altData());
  $("compare-box").hidden = true;
  $("compare").hidden = !result || viewOnly || points.length < 2 || !!adv;
}

async function openCompare() {
  if (!result || points.length < 2) return;
  compareCtl?.abort();
  const ctl = (compareCtl = new AbortController());
  compareOpen = true;
  alts = [];
  $("compare").hidden = true;
  $("compare-box").hidden = false;
  $("compare-box").scrollIntoView({ block: "nearest", behavior: "smooth" });
  const pts = points.slice(), base = routeOptions();
  const rows = new Map();
  const list = $("compare-list");
  list.replaceChildren(...STYLES.map(([style, label]) => {
    const li = el("li", style === base.style ? "current" : "");
    const b = el("button", "", "");
    b.type = "button";
    b.append(el("b", "", t(label)), el("small", "", style === base.style ? numbers(result) : t("compare_wait")));
    b.title = style === base.style ? "" : t("compare_pick");
    b.disabled = style === base.style;
    b.onclick = () => {
      compareKeep = true;
      options.style = style;
      store.set("options", options);
      showOptions();
      changed();
    };
    li.append(b);
    rows.set(style, li);
    return li;
  }));
  // One at a time: the service has two places for everyone.
  for (const [style] of STYLES) {
    if (style === base.style) continue;
    try {
      const r = joinLegs(await legsFor(pts, { ...base, style }, ctl.signal, TAB + "c"));
      if (compareCtl !== ctl) return;
      rows.get(style).querySelector("small").textContent = numbers(r);
      alts.push({ style, line: r.line });
      map.getSource("cv-alt")?.setData(altData());
    } catch (e) {
      if (compareCtl !== ctl || e.name === "AbortError") return;
      rows.get(style).querySelector("small").textContent = e instanceof Refused ? t("err_" + e.code) : t("err_offline");
    }
  }
}
const numbers = (r) => `${kmText(r.m)} · ${dur(r.s)} · ${r.curvy}/100 ${t("curvy")}`;
$("compare").addEventListener("click", openCompare);
$("compare-close").addEventListener("click", closeCompare);

// ---------- Places on the map ----------
// As the app's map layers (Settings → Display): fuel, viewpoints, passes, camping, food, ferries, motorcycle shops, from
// the same data. Each kind shows from close enough that it stays readable; a click on one asks what it should be.
const LAYERS = [
  ["fuel", ["fuel"], 40, "#1565c0"], ["viewpoints", ["viewpoint"], 80, "#2e7d32"],
  ["passes", ["pass"], 250, "#6d4c41"], ["camping", ["camp_site"], 40, "#558b2f"],
  ["food", ["cafe", "restaurant", "fast_food"], 12, "#e65100"], ["ferries", ["ferry"], 120, "#00838f"],
  ["motorcycle", ["motorcycle_shop", "workshop"], 25, "#455a64"],
];
/** Each kind's sign in white, on a 24 × 24 grid: [paths to fill (even-odd), paths to stroke]. */
const SIGNS = {
  fuel: [["M5 4h9v16H5z M7 6h5v4H7z"], ["M14 9h2.5v8a1.5 1.5 0 0 0 3 0V8l-2-2"]],
  viewpoints: [["M12 9a3 3 0 1 0 0.01 0z"], ["M2 12c3-5 7-7 10-7s7 2 10 7c-3 5-7 7-10 7s-7-2-10-7z"]],
  passes: [["M2 19l7-11 4 6 3-4 6 9z"], []],
  camping: [["M12 4l9 15H3z M12 11l-3.5 8h7z"], []],
  food: [["M5 9h11v5a5 5 0 0 1-5 5h-1a5 5 0 0 1-5-5z"], ["M16 11h1.5a2.5 2.5 0 0 1 0 5H16", "M9 3v4", "M12 3v4"]],
  ferries: [["M3 14h18l-3 5H6z", "M7 9h10v4H7z", "M11 5h2v4h-2z"], []],
  motorcycle: [["M8 15l3-5h4l2 3h-3l-2 3z"], ["M6 12.8a3.2 3.2 0 1 0 0.01 0z", "M18 12.8a3.2 3.2 0 1 0 0.01 0z", "M14 10l1-3h3"]],
};
/** A round badge in the kind's colour with its sign, [size] pixels across. */
function badge(key, size) {
  const [, , , colour] = LAYERS.find(([k]) => k === key);
  const c = document.createElement("canvas");
  c.width = c.height = size;
  const g = c.getContext("2d");
  g.beginPath(); g.arc(size / 2, size / 2, size / 2 - size / 16, 0, Math.PI * 2);
  g.fillStyle = colour; g.fill();
  g.lineWidth = size / 16; g.strokeStyle = "#ffffff"; g.stroke();
  const k = (size * 0.6) / 24;
  g.translate(size * 0.2, size * 0.2);
  g.scale(k, k);
  g.fillStyle = "#ffffff"; g.strokeStyle = "#ffffff"; g.lineWidth = 1.8; g.lineCap = "round"; g.lineJoin = "round";
  const [fills, strokes] = SIGNS[key];
  for (const d of fills) g.fill(new Path2D(d), "evenodd");
  for (const d of strokes) g.stroke(new Path2D(d));
  return c;
}
const layerOfKind = Object.fromEntries(LAYERS.flatMap(([key, kinds]) => kinds.map((k) => [k, key])));
let shownLayers = new Set(store.get("layers", []).filter((k) => LAYERS.some(([key]) => key === k)));
let poiCtl = null, poiTimer;

function poiData() {
  return { type: "FeatureCollection", features: poiList.map(([k, la, lo, name]) => ({
    type: "Feature", properties: { k: layerOfKind[k] || k, name }, geometry: { type: "Point", coordinates: [lo, la] },
  })) };
}

// The map's own sprites have no sign for fuel or camping: the badges are drawn here, for each style the map loads.
map.on("styleimagemissing", (e) => {
  const key = e.id.replace(/^poi-/, "");
  if (!e.id.startsWith("poi-") || !SIGNS[key] || map.hasImage(e.id)) return;
  const c = badge(key, 48);
  map.addImage(e.id, c.getContext("2d").getImageData(0, 0, 48, 48), { pixelRatio: 2 });
});

/** The view's width in km, as the app decides when a layer is readable. */
function viewKm() {
  const b = map.getBounds(), lat = map.getCenter().lat;
  return Math.abs(b.getEast() - b.getWest()) * 111.32 * Math.cos((lat * Math.PI) / 180);
}

async function loadPois() {
  poiCtl?.abort();
  const km = viewKm();
  const on = LAYERS.filter(([key]) => shownLayers.has(key));
  const near = on.filter(([, , maxKm]) => km <= maxKm);
  if ($("places-zoom")) $("places-zoom").hidden = !on.length || near.length === on.length;
  if (!near.length) { poiList = []; map.getSource("cv-pois")?.setData(poiData()); return; }
  const b = map.getBounds();
  const padX = (b.getEast() - b.getWest()) * 0.2, padY = (b.getNorth() - b.getSouth()) * 0.2;
  const box = [b.getWest() - padX, b.getSouth() - padY, b.getEast() + padX, b.getNorth() + padY].map((v) => v.toFixed(4)).join(",");
  const ctl = (poiCtl = new AbortController());
  try {
    const r = await fetch(`${BASE}api/pois?b=${box}&k=${near.flatMap(([, kinds]) => kinds).join(",")}`, { signal: ctl.signal });
    if (!r.ok) return;
    const j = await r.json();
    if (poiCtl !== ctl) return;
    poiList = j.pois;
    map.getSource("cv-pois")?.setData(poiData());
  } catch { /* offline or moved on: the places stay as they were */ }
}
map.on("moveend", () => { clearTimeout(poiTimer); if (shownLayers.size) poiTimer = setTimeout(loadPois, 300); });

/** The button on the map that opens the list of kinds. */
class PlacesControl {
  onAdd() {
    const box = el("div", "maplibregl-ctrl maplibregl-ctrl-group places-ctrl");
    const btn = el("button", "places-btn", "");
    btn.type = "button";
    btn.innerHTML = '<svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true"><path fill="currentColor" d="M12 2a7 7 0 0 0-7 7c0 5 7 13 7 13s7-8 7-13a7 7 0 0 0-7-7zm0 9.5A2.5 2.5 0 1 1 12 6.5a2.5 2.5 0 0 1 0 5z"/></svg>';
    const pop = el("div", "places-pop");
    pop.id = "places-pop";
    pop.hidden = true;
    btn.onclick = () => { pop.hidden = !pop.hidden; drawPlaces(); };
    box.append(btn, pop);
    this.box = box;
    return box;
  }
  onRemove() { this.box.remove(); }
}
map.addControl(new PlacesControl(), "top-right");

function drawPlaces() {
  const btn = document.querySelector(".places-btn");
  btn.title = t("places_title"); btn.ariaLabel = t("places_title");
  btn.classList.toggle("on", shownLayers.size > 0);
  const pop = $("places-pop");
  const head = el("b", "", t("places_title"));
  const zoom = el("small", "hint", t("places_zoom"));
  zoom.id = "places-zoom";
  zoom.hidden = true;
  // Gravel roads first: on by default, as in the app.
  const gl = el("label");
  const gbox = el("input");
  gbox.type = "checkbox";
  gbox.checked = gravelOn();
  gbox.onchange = () => { store.set("gravelMap", gbox.checked); showGravel(); };
  const dash = el("span", "dash-sign");
  gl.append(gbox, dash, el("span", "", t("layer_gravel")));
  pop.replaceChildren(head, gl, ...LAYERS.map(([key]) => {
    const label = el("label");
    const box = el("input");
    box.type = "checkbox";
    box.checked = shownLayers.has(key);
    box.onchange = () => {
      if (box.checked) shownLayers.add(key); else shownLayers.delete(key);
      store.set("layers", [...shownLayers]);
      btn.classList.toggle("on", shownLayers.size > 0);
      loadPois();
    };
    const sign = el("img", "badge");
    sign.src = badge(key, 40).toDataURL();
    sign.alt = "";
    label.append(box, sign, el("span", "", t("layer_" + key)));
    return label;
  }), zoom);
  loadPois();
}

// ---------- Gravel roads on the map ----------
// As the app's map: from zoom 11, the gravel roads dashed in the roads' colour, soft ground dashed brown, and the small
// tarmac roads that lead to them, read by the planner service out of the routing data (the map's tiles don't say).
const gravelOn = () => store.get("gravelMap", true);
function gravelData() {
  return { type: "FeatureCollection", features: gravelWays.map(([k, rank, c]) => ({
    type: "Feature", properties: { k, rank }, geometry: { type: "LineString", coordinates: Array.from({ length: c.length / 2 }, (_, i) => [c[2 * i], c[2 * i + 1]]) },
  })) };
}
async function loadGravel() {
  if (!gravelOn() || map.getZoom() < 11) return;
  const b = map.getBounds();
  const [w, s0, e, n] = [b.getWest(), b.getSouth(), b.getEast(), b.getNorth()];
  if (gravelHave && w >= gravelHave[0] && s0 >= gravelHave[1] && e <= gravelHave[2] && n <= gravelHave[3]) return;
  // A margin round the view, so small moves need nothing new; less of one when the view is already large.
  const cells = (bw, bh) => (bw * 32 + 2) * (bh * 32 + 2);
  let m = 0.3;
  while (m > 0 && cells((e - w) * (1 + 2 * m), (n - s0) * (1 + 2 * m)) > 400) m -= 0.1;
  if (cells(e - w, n - s0) > 400 || (e - w) * 32 + 1 > 40) return;
  const box = [w - (e - w) * m, s0 - (n - s0) * m, e + (e - w) * m, n + (n - s0) * m];
  gravelCtl?.abort();
  const ctl = (gravelCtl = new AbortController());
  try {
    const r = await fetch(`${BASE}api/surface?b=${box.map((v) => v.toFixed(4)).join(",")}`, { signal: ctl.signal });
    if (!r.ok || gravelCtl !== ctl) return;
    gravelWays = (await r.json()).ways;
    gravelHave = box;
    map.getSource("cv-gravel")?.setData(gravelData());
  } catch { /* offline or moved on */ }
}
function showGravel() {
  const v = gravelOn() ? "visible" : "none";
  for (const id of ["cv-gravel-road", "cv-gravel-soft", "cv-gravel-gravel"]) if (map.getLayer(id)) map.setLayoutProperty(id, "visibility", v);
  loadGravel();
}
let gravelTimer;
map.on("moveend", () => { clearTimeout(gravelTimer); gravelTimer = setTimeout(loadGravel, 250); });
map.on("styledata", () => { if (!gravelOn()) showGravel(); });

// ---------- GPX out, and a link to the trip ----------
const xml = (s) => String(s).replace(/[<>&"']/g, (c) => ({ "<": "&lt;", ">": "&gt;", "&": "&amp;", '"': "&quot;", "'": "&apos;" })[c]);

/** The trip as a GPX file for any other app or GPS: its places as waypoints and a route, and the line with heights. */
function gpxText() {
  const name = $("name").value.trim() || defaultName();
  const pt = (tag, p, inner = "") => `<${tag} lat="${p.lat.toFixed(6)}" lon="${p.lon.toFixed(6)}">${inner}</${tag}>`;
  const named = points.map((p, i) => ({ p, i })).filter(({ i }) => kindOf(i) !== "via");
  const ele = result.ele;
  return `<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="Curveon planner (curveon.no/plan)" xmlns="http://www.topografix.com/GPX/1/1">
<metadata><name>${xml(name)}</name></metadata>
${named.map(({ p }) => pt("wpt", p, `<name>${xml(p.name || t("map_point"))}</name>`)).join("\n")}
${points.length >= 2 ? `<rte><name>${xml(name)}</name>
${points.map((p) => pt("rtept", p, p.via ? "" : `<name>${xml(p.name || t("map_point"))}</name>`)).join("\n")}
</rte>` : ""}
<trk><name>${xml(name)}</name><trkseg>
${result.line.map(([la, lo], k) => `<trkpt lat="${la.toFixed(6)}" lon="${lo.toFixed(6)}">${ele ? `<ele>${ele[k]}</ele>` : ""}</trkpt>`).join("\n")}
</trkseg></trk>
</gpx>
`;
}
$("save-gpx").addEventListener("click", () => {
  if (!result) return;
  const a = el("a");
  a.href = URL.createObjectURL(new Blob([gpxText()], { type: "application/gpx+xml" }));
  a.download = ($("name").value.trim() || defaultName()).replace(/[\\/:*?"<>|]+/g, " ").trim().slice(0, 80) + ".gpx";
  document.body.append(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(a.href), 10000);
});

/** A link with this trip in it (its places, options and name; nothing else), to open on another computer or send a friend. */
function tripLink() {
  const data = { v: 1, n: $("name").value.trim(), o: routeOptions(),
    p: points.map((p) => [+p.lat.toFixed(5), +p.lon.toFixed(5), (p.via ? "v" : "") + (p.stop ? "s" : "") + (p.night ? "n" : ""), p.via ? "" : p.name || ""]) };
  const url = new URL(location.href);
  url.hash = "";
  url.searchParams.delete("debug");
  url.searchParams.set("trip", b64(new TextEncoder().encode(JSON.stringify(data))));
  return url.toString();
}
$("share").addEventListener("click", async () => {
  try { await navigator.clipboard.writeText(tripLink()); setStatus(t("link_copied")); } catch { prompt(t("share"), tripLink()); }
});

/** A trip from a link (tripLink): on the map to change, save or send. */
function openLink() {
  const raw = params.get("trip");
  if (!raw) return;
  const url = new URL(location.href);
  url.searchParams.delete("trip");
  history.replaceState(null, "", url);
  let data;
  try { data = JSON.parse(new TextDecoder().decode(unb64(raw))); } catch { return; }
  const ok = (v) => Number.isFinite(v) && Math.abs(v) <= 180;
  if (data?.v !== 1 || !Array.isArray(data.p) || data.p.length > 27 || !data.p.every((q) => ok(q[0]) && ok(q[1]))) return;
  points = data.p.map(([lat, lon, f = "", name = ""]) => ({ name: String(name).slice(0, 120), lat, lon, via: f.includes("v"),
    ...(f.includes("s") ? { stop: true } : {}), ...(f.includes("n") ? { night: true } : {}) }));
  if (data.o && typeof data.o === "object") { options = Object.assign(options, { gravelReachKm: null }, data.o); showOptions(); }
  $("name").value = String(data.n || "").slice(0, 80);
  forgetUndo();
  changed();
  if (points.length) fitTo(points.map((q) => [q.lat, q.lon]));
  setStatus(t("opened_link"));
}

// ---------- The session with the devices ----------
let link = store.get("link", null) && parseLink(store.get("link"));
const riderId = () => unb64(store.get("rider", null) || (() => { const r = b64(random(16)); store.set("rider", r); return r; })());
let rider = riderId();
const nextSeq = () => { const s = store.get("seq", 0) + 1; store.set("seq", s); return s; };
let ch = null;
let socket = null;
let backoff = 1000;

async function ensureLink() {
  if (!link) { link = newLink(RELAY); store.set("link", linkUri(link)); rider = riderId(); }
  if (!ch) ch = await channel(link, WS_SCHEME);
  return ch;
}

/** Stays connected while the page is open: the devices' trips, and their word that a trip arrived. */
async function connect() {
  if (!link || socket) return;
  const c = await ensureLink();
  const ws = (socket = new WebSocket(c.url));
  ws.onopen = async () => {
    backoff = 1000;
    // A word in the channel for the devices: Curveon answers what it finds there with its trips, and a channel just made
    // (a new code, after Disconnect) is otherwise empty, the device connected but silent until a newer app answers that
    // too. It is a delete of nothing (this page's own trip 0): no device acts on it.
    try { ws.send(await c.del(rider, nextSeq(), { r: b64(rider), id: 0 })); } catch { /* closed meanwhile */ }
  };
  ws.onmessage = async (ev) => {
    const m = await c.open(ev.data);
    if (!m) return;
    if (m.type === "plan_index") gotIndex(m.rider, m.body);
    else if (m.type === "plan_have") gotTrip(m.rider, m.body);
    else if (m.type === "plan_got") gotAnswer(m.body);
  };
  ws.onclose = () => {
    if (socket !== ws) return; // closed on purpose (disconnected)
    socket = null;
    if (document.visibilityState === "visible") setTimeout(connect, (backoff = Math.min(backoff * 2, 60000)));
  };
}
document.addEventListener("visibilitychange", () => { if (document.visibilityState === "visible") connect(); });

function device(r) {
  if (!devices.has(r)) devices.set(r, { dev: "Curveon", ids: [], trips: new Map(), at: 0 });
  return devices.get(r);
}

function gotIndex(r, body) {
  const d = device(r);
  if (body.ts < d.at) return;
  Object.assign(d, { dev: body.dev || d.dev, ids: body.ids || [], at: body.ts });
  for (const id of [...d.trips.keys()]) if (!d.ids.includes(id)) d.trips.delete(id);
  for (const k of [...deleting]) if (k.startsWith(r + ":") && !d.ids.includes(Number(k.split(":")[1]))) deleting.delete(k);
  if (editing?.ref?.r === r) editing.dev = d.dev;
  for (const c of editing?.copies || []) if (c.r === r) c.dev = d.dev;
  // A device that just scanned the code: the session is on, the code can go.
  if (pairingOpenedAt && body.ts >= pairingOpenedAt - 5000) $("pairing").close();
  drawDevices();
  drawSummary();
}

function gotTrip(r, body) {
  if (!body.trip) return; // one the device took off its list
  const d = device(r);
  const prev = d.trips.get(body.trip.id);
  if (prev && prev.ts > body.ts) return;
  d.trips.set(body.trip.id, { ...body.trip, ts: body.ts });
  drawDevices();
}

function gotAnswer(body) {
  const list = store.get("sent", []);
  const s = list.find((x) => x.tid === body.tid);
  if (!s) return;
  s.got = Object.assign(s.got || {}, { [body.dev || "?"]: body.rev });
  store.set("sent", list);
  drawPending();
}

/** Sends one frame, connecting first if need be, and says whether it went. */
async function sendFrame(frame) {
  await connect();
  for (let i = 0; i < 50 && socket?.readyState === WebSocket.CONNECTING; i++) await new Promise((r) => setTimeout(r, 100));
  if (socket?.readyState !== WebSocket.OPEN) return false;
  socket.send(frame);
  return true;
}

// The devices' trips, in their groups.
function drawDevices() {
  const box = $("devices");
  box.replaceChildren();
  for (const [r, d] of devices) {
    if (!d.at) continue; // trips seen before the device's list: wait for it
    const sec = el("section", "device");
    sec.append(el("h2", "", t("on_device", d.dev)));
    const trips = d.ids.map((id) => d.trips.get(id)).filter((x) => x && !deleting.has(r + ":" + x.id));
    if (!trips.length) sec.append(el("p", "hint", t("no_trips")));
    const groups = new Map();
    for (const x of trips) { const g = x.group || ""; if (!groups.has(g)) groups.set(g, []); groups.get(g).push(x); }
    const named = [...groups.keys()].filter(Boolean).sort((a, b) => a.localeCompare(b));
    for (const g of [...named, ...(groups.has("") ? [""] : [])]) {
      if (named.length) sec.append(el("h3", "", g || t("loose")));
      const ul = el("ul", "trips");
      for (const x of groups.get(g)) {
        const li = el("li", editing?.ref?.r === r && editing.ref.id === x.id ? "open" : "");
        const what = el("div", "what");
        what.title = t("edit");
        what.append(el("b", "", x.name), el("small", "", `${Math.round(x.m / 1000)} ${t("km")}`));
        what.onclick = async () => { if (await leaveOk()) openTrip(r, d, x); };
        const del = el("button", "", "✕");
        del.type = "button"; del.title = t("delete_on", d.dev); del.ariaLabel = del.title;
        del.onclick = async () => {
          if (!confirm(t("confirm_delete", x.name, d.dev))) return;
          await ensureLink();
          if (await sendFrame(await ch.del(rider, nextSeq(), { r, id: x.id }))) {
            deleting.add(r + ":" + x.id);
            if (editing?.ref?.r === r && editing.ref.id === x.id) editing = null;
            drawDevices(); drawList(); drawSummary();
          } else setStatus(t("send_failed"), true);
        };
        li.append(what, del);
        ul.append(li);
      }
      sec.append(ul);
    }
    box.append(sec);
  }
  box.hidden = box.childElementCount === 0;
  const names = [...devices.values()].filter((d) => d.at).map((d) => d.dev);
  $("session").textContent = names.length ? t("connected_to", names.join(", ")) : "";
  $("session").hidden = !names.length;
  // Not connected yet: a card that says what connecting gives and how; connected, only a quiet link for one more.
  $("session-row").classList.toggle("unpaired", !names.length);
  $("connect-intro").hidden = !!names.length;
  $("pair").className = names.length ? "link" : "primary";
  $("pair").textContent = names.length ? t("connect_more") : t("connect");
  $("unpair").hidden = !names.length;
  $("unpair").textContent = t("disconnect");
}

/** The devices a trip opened from one device is updated on: that one, and those with the same trip. */
const devNames = (e) => [e.dev, ...(e.copies || []).map((c) => c.dev)].join(t("and"));

/**
 * The same trip as [x] (on device [r]) on the other devices: what the page sent to them all at once, or the rider shared
 * between them. Same name and length, same start and destination.
 */
function sameTrip(r, x) {
  const near = (a, b) => a && b && Math.abs(a.lat - b.lat) + Math.abs(a.lon - b.lon) < 0.003;
  const out = [];
  for (const [r2, d2] of devices) {
    if (r2 === r || !d2.at) continue;
    const y = d2.ids.map((id) => d2.trips.get(id)).find((y) => y && !deleting.has(r2 + ":" + y.id) && y.name === x.name &&
      Math.abs(y.m - x.m) <= x.m * 0.01 + 50 && near(y.start, x.start) && near(y.destination, x.destination));
    if (y) out.push({ r: r2, id: y.id, dev: d2.dev });
  }
  return out;
}

/** A device's trip on the map, with its own line: changed here and sent back with Update. */
function openTrip(r, d, x) {
  const line = decodePolyline(x.poly);
  if (line.length < 2) return;
  routeCtl?.abort();
  clearTimeout(routeTimer);
  result = { line, m: x.m, s: x.s, up: x.up, curvy: x.curvy, ferries: 0 };
  if (x.destination) {
    const start = x.start || { name: t("start"), lat: line[0][0], lon: line[0][1] };
    points = [start, ...(x.stops || []), x.destination].map((p) => ({ name: p.name || "", lat: p.lat, lon: p.lon, via: !!p.via }));
    viewOnly = false;
    editing = { tid: "r" + x.id + "-" + r.slice(0, 8), ref: { r, id: x.id }, dev: d.dev, copies: sameTrip(r, x) };
  } else {
    points = [];
    viewOnly = true;
    editing = null;
  }
  forgetUndo();
  if (x.options) { options = Object.assign(options, { gravelReachKm: null }, x.options); showOptions(); }
  closeAdventure();
  $("name").value = x.name;
  setStatus("");
  drawList(); drawMarkers(); drawRoute(); drawSummary(); drawDevices();
  currentDraft = null;
  markClean();
  fitTo(line);
}

// ---------- Drafts, and not losing what is on the map ----------
// What is on the map counts as unsaved when its points, options or name changed since it was last opened, sent or
// saved as a draft. Drafts stay in this browser (as the trips sent from here do), to open again later.
let currentDraft = null; // the draft's id, when the map holds one
const workKey = () => JSON.stringify([placesKey(), routeOptions(), $("name").value.trim()]);
let cleanKey = workKey();
function markClean() { cleanKey = workKey(); drawDrafts(); }
const unsaved = () => points.length > 0 && !viewOnly && workKey() !== cleanKey;

function saveDraft() {
  const list = store.get("drafts", []);
  const id = currentDraft || b64(random(9));
  const d = { id, name: $("name").value.trim() || defaultName(), at: Date.now(), points, options: routeOptions(), result, editing };
  store.set("drafts", [d, ...list.filter((x) => x.id !== id)].slice(0, 30));
  currentDraft = id;
  markClean();
  setStatus(t("draft_saved"));
}

function openDraft(d) {
  routeCtl?.abort();
  clearTimeout(routeTimer);
  closeMenu();
  closeAdventure();
  points = d.points; result = d.result; editing = d.editing; viewOnly = false;
  options = Object.assign(options, { gravelReachKm: null }, d.options); showOptions();
  adoptLegs(result, points, routeOptions());
  $("name").value = d.name;
  currentDraft = d.id;
  forgetUndo();
  setStatus("");
  drawList(); drawMarkers(); drawRoute(); drawSummary(); drawDevices();
  markClean();
  if (result) fitTo(result.line); else if (points.length) fitTo(points.map((q) => [q.lat, q.lon]));
  if (!result && points.length >= 2) route();
}

/** Before something else takes the map: true to go on (saved or thrown away), false to stay. */
async function leaveOk() {
  if (!unsaved()) return true;
  const dlg = $("unsaved");
  dlg.returnValue = "";
  dlg.showModal();
  await new Promise((r) => dlg.addEventListener("close", r, { once: true }));
  if (dlg.returnValue === "save") { saveDraft(); return true; }
  return dlg.returnValue === "discard";
}
// Closing the tab or reloading with unsaved work: the browser asks first.
addEventListener("beforeunload", (e) => { if (unsaved()) e.preventDefault(); });

function drawDrafts() {
  const list = store.get("drafts", []);
  $("drafts").hidden = list.length === 0;
  $("drafts-list").replaceChildren(...list.map((d) => {
    const li = el("li", d.id === currentDraft ? "open" : "");
    const what = el("div", "what");
    const km = d.result ? `${Math.round(d.result.m / 1000)} ${t("km")}` : t("draft_points", d.points.length);
    what.append(el("b", "", d.name), el("small", "", `${km} · ${new Date(d.at).toLocaleDateString(lang === "nb" ? "nb-NO" : "en-GB", { day: "numeric", month: "short" })}`));
    what.onclick = async () => { if (d.id === currentDraft || await leaveOk()) openDraft(store.get("drafts", []).find((x) => x.id === d.id) || d); };
    const x = el("button", "", "✕");
    x.type = "button"; x.title = t("delete_draft"); x.ariaLabel = t("delete_draft");
    x.onclick = () => {
      if (!confirm(t("confirm_delete_draft", d.name))) return;
      store.set("drafts", store.get("drafts", []).filter((y) => y.id !== d.id));
      if (currentDraft === d.id) { currentDraft = null; cleanKey = ""; }
      drawDrafts();
    };
    li.append(what, x);
    return li;
  }));
  // The save button shows while there is something on the map not saved yet.
  $("save-draft").hidden = !unsaved();
  drawSaveBlock();
  $("save-draft").textContent = t(currentDraft ? "save_draft_again" : "save_draft");
}
$("save-draft").addEventListener("click", saveDraft);
$("name").addEventListener("input", () => drawDrafts());

// ---------- GPX ----------
// A GPX file opened here (the button, or dropped on the map): its line as it is, with points along it to change it as a
// trip. It goes to Curveon as a GPX track, a trip, or both.
function parseGpx(text, fileName) {
  const doc = new DOMParser().parseFromString(text, "application/xml");
  let pts = [...doc.getElementsByTagName("trkpt")];
  if (!pts.length) pts = [...doc.getElementsByTagName("rtept")];
  const raw = pts.map((p) => [Number(p.getAttribute("lat")), Number(p.getAttribute("lon")), Number(p.getElementsByTagName("ele")[0]?.textContent)])
    .filter(([la, lo]) => Number.isFinite(la) && Number.isFinite(lo) && Math.abs(la) <= 90 && Math.abs(lo) <= 180);
  if (raw.length < 2) return null;
  const d = (a, b) => Math.hypot(a[0] - b[0], (a[1] - b[1]) * Math.cos((a[0] * Math.PI) / 180)) * 111195;
  // A point every 10 m or so is plenty, and keeps a long day's file small enough to send.
  const line = [raw[0]];
  let m = 0, up = 0;
  for (const q of raw.slice(1)) {
    const last = line[line.length - 1];
    const step = d(last, q);
    if (step < 10 && q !== raw[raw.length - 1]) continue;
    m += step;
    if (Number.isFinite(q[2]) && Number.isFinite(last[2]) && q[2] > last[2]) up += q[2] - last[2];
    line.push(q);
  }
  const name = doc.querySelector("trk > name, rte > name, metadata > name")?.textContent?.trim() || fileName.replace(/\.(gpx|xml)$/i, "");
  // Heights when the file has them for every point.
  const ele = line.every((q) => Number.isFinite(q[2])) ? line.map((q) => Math.round(q[2])) : null;
  return { name, line: line.map(([la, lo]) => [la, lo]), ele, m, up };
}

async function openGpx(file) {
  if (!file || !(await leaveOk())) return;
  const g = parseGpx(await file.text(), file.name);
  if (!g) { setStatus(t("gpx_bad"), true); return; }
  routeCtl?.abort();
  clearTimeout(routeTimer);
  closeMenu();
  closeAdventure();
  // Points along it, as an adventure has: change it, and it is routed through them.
  points = pointsAlong(g.line, g.m);
  result = { line: g.line, ele: g.ele, m: Math.round(g.m), s: Math.round(g.m / (60 / 3.6)), up: Math.round(g.up), curvy: null, ferries: 0 };
  editing = null; viewOnly = false; currentDraft = null; viasOpen = false;
  $("name").value = g.name;
  $("send-as").value = "track";
  drawList(); drawMarkers(); drawRoute(); drawSummary(); drawDevices();
  fitTo(g.line);
  setStatus(t("gpx_loaded", g.name));
  for (const p of [points[0], points[points.length - 1]]) placeName(p.lat, p.lon).then((n) => { p.name = n; drawList(); drawMarkers(); });
}
$("open-gpx").addEventListener("click", () => $("gpx-file").click());
$("gpx-file").addEventListener("change", (e) => { openGpx(e.target.files[0]); e.target.value = ""; });
// A file dropped anywhere on the page.
addEventListener("dragover", (e) => { if ([...(e.dataTransfer?.items || [])].some((i) => i.kind === "file")) e.preventDefault(); });
addEventListener("drop", (e) => { const f = e.dataTransfer?.files?.[0]; if (f) { e.preventDefault(); openGpx(f); } });
$("send-as").value = store.get("sendAs", "trip");
$("send-as").addEventListener("change", () => { if ($("send-as").value !== "track") store.set("sendAs", $("send-as").value); });

// ---------- Sending ----------
async function send(asNew) {
  if (!result || viewOnly) return;
  const first = !store.get("link", null);
  await ensureLink();
  if (first) await showPairing();
  const name = $("name").value.trim() || defaultName();
  const target = asNew ? null : editing;
  const tid = target?.tid || b64(random(12));
  const stop = (p) => ({ name: p.name || "", lat: +p.lat.toFixed(6), lon: +p.lon.toFixed(6), ...(p.via ? { via: true } : {}) });
  // The trip ends where the last point is, also when the rider had not made it the destination yet.
  const { via: _, ...destination } = stop(points[points.length - 1]);
  const trip = {
    v: 1, name, start: stop(points[0]), destination, stops: points.slice(1, -1).map(stop),
    options: routeOptions(), line: result.line.map(([la, lo]) => `${la.toFixed(5)},${lo.toFixed(5)}`).join(";"),
    m: result.m, s: result.s, up: result.up, curvy: result.curvy ?? 0,
  };
  $("send").disabled = $("send-new").disabled = true;
  setStatus(t("sending"));
  // Every send is newer than the one before: the time is the revision.
  const rev = Date.now();
  // A trip opened from a device is updated there, and in the same trip's copies on the other devices, each on its own.
  const copies = target?.ref ? [{ ...target.ref, dev: target.dev }, ...(target.copies || [])] : [null];
  // A new one goes as the rider picked: a trip to change, a GPX track ridden as it is, both, or a trip for each day.
  const as = target?.ref ? "trip" : $("send-as").value;
  const sends = as === "track" || as === "days" ? [] : copies.map((c) => [
    c && c !== copies[0] ? "r" + c.id + "-" + c.r.slice(0, 8) : tid, c ? { ...trip, ref: { r: c.r, id: c.id } } : trip]);
  if (as === "track" || as === "both") {
    const { start: _s, destination: _d, ...rest } = trip;
    sends.push([as === "both" ? tid + "-t" : tid, { ...rest, name: as === "both" ? name + t("track_suffix") : name, stops: [], track: true }]);
  }
  const st = as === "days" && legStats();
  if (st) days().forEach(([a, b], n) => {
    // Each day from its morning's place to its night's (or the end), with its own part of the line.
    const x = between(st, a, b);
    const dpk = st.legs.slice(a, b).every((l) => l.dpk != null) && x.m ? st.legs.slice(a, b).reduce((v, l) => v + l.dpk * l.m, 0) / x.m : null;
    const { via: _v, ...dest } = stop(points[b]);
    sends.push([tid + "-d" + (n + 1), {
      ...trip, name: name + t("day_suffix", n + 1), start: stop(points[a]), destination: dest, stops: points.slice(a + 1, b).map(stop),
      line: result.line.slice(st.cuts[a], st.cuts[b] + 1).map(([la, lo]) => `${la.toFixed(5)},${lo.toFixed(5)}`).join(";"),
      m: Math.round(x.m), s: Math.round(x.s), up: Math.round(x.up), curvy: dpk != null ? Math.min(100, Math.floor(dpk / 3)) : trip.curvy,
    }]);
  });
  let ok = true;
  for (const [k, body] of sends) ok = (await sendFrame(await ch.plan(rider, nextSeq(), k, rev, body))) && ok;
  $("send").disabled = $("send-new").disabled = false;
  if (!ok) { setStatus(t("send_failed"), true); return; }
  if (!target?.ref) {
    const list = store.get("sent", []).filter((x) => !sends.some(([k]) => k === x.tid));
    for (const [k, body] of sends) list.unshift({ tid: k, rev, name: body.name, km: Math.round(body.m / 1000), at: Date.now(), got: {} });
    store.set("sent", list.slice(0, 30));
    editing = { tid, ref: null, dev: null };
  }
  setStatus(target?.ref ? t("updated_ok", devNames(target)) : t("sent_ok"));
  // Sent: it is no longer a draft (it waits for Curveon here, or is on the device).
  if (currentDraft) { store.set("drafts", store.get("drafts", []).filter((x) => x.id !== currentDraft)); currentDraft = null; }
  markClean();
  drawPending();
  drawSummary();
}
$("send").addEventListener("click", () => send(false));
$("send-new").addEventListener("click", () => send(true));

/** Trips sent from here that no device has fetched yet; the rest show in the devices' lists. */
function drawPending() {
  const list = store.get("sent", []);
  const waiting = list.filter((s) => !Object.values(s.got || {}).some((rev) => rev >= s.rev));
  if (waiting.length !== list.length) store.set("sent", waiting);
  $("pending").hidden = waiting.length === 0;
  $("pending-list").replaceChildren(...waiting.map((s) => {
    const li = el("li");
    const what = el("div", "what");
    what.append(el("b", "", s.name), el("small", "", `${s.km} ${t("km")} · ${t("waiting")}`));
    const x = el("button", "", "✕");
    x.type = "button"; x.title = t("forget"); x.ariaLabel = t("forget");
    x.onclick = async () => {
      store.set("sent", store.get("sent", []).filter((y) => y.tid !== s.tid));
      if (ch) sendFrame(await ch.forget(rider, nextSeq(), s.tid));
      if (editing?.tid === s.tid) editing = null;
      drawPending(); drawSummary();
    };
    li.append(what, x);
    return li;
  }));
}

// ---------- Pairing ----------
let pairingOpenedAt = 0;
async function showPairing() {
  await ensureLink();
  const qr = qrcode(0, "M");
  qr.addData(linkUri(link));
  qr.make();
  $("qr").innerHTML = qr.createSvgTag({ cellSize: 6, margin: 2, scalable: true });
  const d = $("pairing");
  pairingOpenedAt = Date.now();
  d.showModal();
  connect();
  await new Promise((r) => d.addEventListener("close", r, { once: true }));
  pairingOpenedAt = 0;
}
$("pair").addEventListener("click", showPairing);

/**
 * Disconnect: this browser forgets the channel's keys and who the rider is here, so whoever uses it next can neither
 * see nor change the trips on the devices (everything on the channel is sealed with those keys). The devices keep their
 * side until they forget it (Planned → From the web → Disconnect); scanning a new code connects again.
 */
$("unpair").addEventListener("click", () => {
  const names = [...devices.values()].filter((d) => d.at).map((d) => d.dev);
  if (!confirm(t("confirm_disconnect", names.join(t("and"))))) return;
  socket?.close();
  socket = null;
  ch = null;
  link = null;
  for (const k of ["link", "rider", "seq", "sent"]) { try { localStorage.removeItem("curveon." + k); } catch { /* nothing kept */ } }
  devices.clear();
  deleting.clear();
  if (editing?.ref) { editing = null; viewOnly = false; }
  drawDevices(); drawPending(); drawList(); drawSummary();
  setStatus(t("disconnected"));
});

// ---------- The rest of the panel ----------
$("undo").addEventListener("click", undo);
$("clear").addEventListener("click", () => {
  if (points.length > 2 && !confirm(t("confirm_clear"))) return;
  points = [];
  closeMenu();
  closeAdventure();
  changed();
});
$("new").addEventListener("click", async () => {
  if (!(await leaveOk())) return;
  points = []; editing = null; viewOnly = false; $("name").value = ""; currentDraft = null;
  closeMenu();
  closeAdventure();
  changed();
  drawDevices();
});
$("theme").addEventListener("click", async () => {
  theme = theme === "dark" ? "light" : "dark";
  store.set("theme", theme);
  document.documentElement.dataset.theme = theme;
  applyTexts();
  map.setStyle(withRoute(await loadStyle()));
  map.once("idle", showOnline);
  drawProfile();
});
$("lang").addEventListener("click", () => { lang = lang === "nb" ? "en" : "nb"; store.set("lang", lang); applyTexts(); fillTimes(); drawList(); drawSummary(); drawDevices(); drawPending(); drawPlaces(); });
$("fold").addEventListener("click", () => {
  const folded = $("panel").classList.toggle("folded");
  $("fold").ariaExpanded = String(!folded);
  applyTexts();
});

applyTexts();
fillTimes();
showOptions();
drawList();
drawDevices();
drawPending();
openLink();
markClean();
connect();
if (map.loaded()) { drawPlaces(); showGravel(); } else map.once("load", () => { drawPlaces(); showGravel(); });
