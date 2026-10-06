# Offline destination search index

```
python3 -m venv venv && ./venv/bin/pip install osmium
curl -LO https://download.geofabrik.de/europe/norway-latest.osm.pbf
./venv/bin/python build_search_index.py norway-latest.osm.pbf norway-search.sqlite   # ~13 min, ~150 MB
```

Copy `norway-search.sqlite` to `Android/data/com.curveon.tablet/files/search/` on the tablet.
It holds ~114k places, ~45k POIs (fuel, cafés, viewpoints, campsites, ferries, passes, …) and
~114k streets with 2.6M house numbers. Without it the app only searches online (Photon) when connected.
