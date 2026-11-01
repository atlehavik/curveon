# :routing

Offline motorcycle routing on [BRouter](https://github.com/abrensch/brouter) 1.7.10 (MIT,
`libs/brouter-1.7.10-ro.jar`).

- `src/main/resources/brouter/curveon-moto.brf` – motorcycle profile with parameters
- `src/main/java/btools/router/CurveonPath.java` – BRouter's path with Curveon's road rules, which a
  profile can't express as it sees no angles: turning back on yourself on a road is forbidden in the
  search itself, as a turn restriction is. Named by the profile's `---model` line
  `road_style` (fastest / curvy / very curvy), `surface_pref` (paved / mixed / gravel),
  `avoid_ferries`, `avoid_motorways`, `avoid_toll`.
- `BRouterEngine` – A→B (with vias) routing, maneuvers, ETA, curviness score.
- `RoundtripPlanner` – GoRide-style loop from a direction and distance.

Routing data: BRouter `.rd5` segments, ~200 MB for Norway, from https://brouter.de/brouter/segments4/.
`./gradlew :routing:test` runs real routes when the segments are in `../Curveon-data/brouter/segments4`
(override with `-Dcurveon.segments=...`).
