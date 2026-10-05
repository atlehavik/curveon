# :routing

Offline motorcycle routing on [BRouter](https://github.com/abrensch/brouter) 1.7.10 (MIT,
`libs/brouter-1.7.10-ro.jar`).

- `src/main/resources/brouter/vibenav-moto.brf` – motorcycle profile with parameters
  `road_style` (fastest / curvy / very curvy), `surface_pref` (paved / mixed / gravel),
  `avoid_ferries`, `avoid_motorways`, `avoid_toll`.
- `BRouterEngine` – A→B (with vias) routing, maneuvers, ETA, curviness score.
- `RoundtripPlanner` – GoRide-style loop from a direction and distance.

Routing data: BRouter `.rd5` segments, ~200 MB for Norway, from https://brouter.de/brouter/segments4/.
`./gradlew :routing:test` runs real routes when the segments are in `../VibeNav-data/brouter/segments4`
(override with `-Dvibenav.segments=...`).
