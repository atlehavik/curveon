# Offline destination search

Every downloaded country gets an offline search index (`search/<area id>.sqlite` on the tablet):

- **Full index**, built monthly in the cloud by the public repo
  [atlehavik/curveon-data](https://github.com/atlehavik/curveon-data) from Geofabrik's
  OpenStreetMap extracts with `build_search_index.py`: places, POIs (fuel, cafés, viewpoints,
  campsites, ferries, passes, …), streets with house numbers, and speed cameras. Businesses
  (shops, workshops, dealers, lodging, …) are added from Overture Maps' open places dataset,
  downloaded for the area's bounding box and kept where OSM doesn't already have them. The tablet
  downloads it from the `search` release together with the map.
- **Basic index**, built on the tablet from the map's own tiles (`pmtiles/…/TileNames.kt`) when
  the cloud has none for that area (tiny islands, Vatican, Kosovo, …) or can't be reached: places
  and streets, fewer POIs, no house numbers or cameras. Replaced by the full one when it appears.

After changing `build_search_index.py` or the country catalog:

```
tools/search/update-data-repo.sh            # sync builder + areas.json to the data repo
tools/search/update-data-repo.sh norway     # … and rebuild some areas now ("all" for everything)
```

Build one by hand:

```
python3 -m venv venv && ./venv/bin/pip install osmium overturemaps
curl -LO https://download.geofabrik.de/europe/norway-latest.osm.pbf
PATH=./venv/bin:$PATH ./venv/bin/python build_search_index.py norway-latest.osm.pbf norway.sqlite norway-places.parquet
```

When online, search also asks Photon (OSM, worldwide) and, in Norway, Kartverket's place name and
address registers, and mixes those results in (`PlaceSearch.search`). None of them need a key.
