#!/usr/bin/env python3
"""
Writes app/src/main/assets/map/styles/online-{light,dark}.json: the layers drawn from OpenFreeMap's
free planet tiles (OpenMapTiles schema, no key) where no downloaded map covers the view, in the
colours of the offline Protomaps styles so the two look like one map. OfflineMaps.styleJson puts
them under the offline layers; CurveonMapView shows them only outside downloaded countries.

    python3 tools/map/gen-online-style.py
"""
import json
import os

SOURCE = "online"
TILES = "https://tiles.openfreemap.org/planet"
NAME = ["coalesce", ["get", "name:latin"], ["get", "name"]]

FLAVORS = {
    "light": dict(
        land="#e2dfda", water="#80deea", wood="#a0d9a0", grass="#99d2bb", park="#9cd3b4", ice="#ffffff",
        farmland="#d8efd2", sand="#e2e0d7", residential="#e6e6e6", building="#cccccc",
        casing="#a3a3a3", casing_major="#858585", casing_highway="#6b6b6b", highway="#ffffff", major="#ffffff", minor="#ffffff", other="#7a7a7a",
        tunnel="#d5d5d5", rail="#a7b1b3", boundary="#adadad",
        road_text="#91888b", road_halo="#ffffff", place_text="#5c5c5c", place_halo="#e0e0e0",
        water_text="#728dd4", water_halo="#80deea", country_text="#a3a3a3",
    ),
    "dark": dict(
        land="#1f1f1f", water="#31353f", wood="#202121", grass="#222323", park="#192a24", ice="#2b2b2b",
        farmland="#1f2420", sand="#212123", residential="#1c1c1c", building="#111111",
        casing="#1f1f1f", casing_major="#1f1f1f", casing_highway="#1f1f1f", highway="#474747", major="#3d3d3d", minor="#333333", other="#333333",
        tunnel="#292929", rail="#000000", boundary="#5b6374",
        road_text="#525252", road_halo="#1f1f1f", place_text="#7a7a7a", place_halo="#212121",
        water_text="#717784", water_halo="#31353f", country_text="#5c5c5c",
    ),
}

HIGHWAY = ["motorway", "trunk"]
MAJOR = ["primary", "secondary", "tertiary"]
MINOR = ["minor", "service"]
OTHER = ["track", "path"]

# Same widths as the offline style's roads_highway / roads_major / roads_minor / roads_other.
W_HIGHWAY = ["interpolate", ["exponential", 1.6], ["zoom"], 3, 0, 6, 1.1, 12, 1.6, 15, 5, 18, 15]
W_MAJOR = ["interpolate", ["exponential", 1.6], ["zoom"], 6, 0, 12, 1.6, 15, 3, 18, 13]
W_MINOR = ["interpolate", ["exponential", 1.6], ["zoom"], 11, 0, 12.5, 0.5, 15, 2, 18, 11]
W_OTHER = ["interpolate", ["exponential", 1.6], ["zoom"], 14, 0.5, 20, 12]


def road_filter(classes):
    return ["all", ["==", ["geometry-type"], "LineString"], ["in", ["get", "class"], ["literal", classes]]]


def layers(c):
    out = [
        {"id": "land", "type": "background", "paint": {"background-color": c["land"]}},
        {"id": "landcover", "type": "fill", "source-layer": "landcover",
         "paint": {"fill-color": ["match", ["get", "class"], "wood", c["wood"], "grass", c["grass"], "ice", c["ice"],
                                  "farmland", c["farmland"], "sand", c["sand"], c["grass"]],
                   "fill-opacity": ["interpolate", ["linear"], ["zoom"], 5, 0.6, 11, 1]}},
        {"id": "landuse", "type": "fill", "source-layer": "landuse",
         "filter": ["in", ["get", "class"], ["literal", ["residential", "suburb", "neighbourhood", "commercial", "industrial", "retail"]]],
         "paint": {"fill-color": c["residential"], "fill-opacity": ["interpolate", ["linear"], ["zoom"], 8, 0, 12, 1]}},
        {"id": "park", "type": "fill", "source-layer": "park",
         "paint": {"fill-color": c["park"], "fill-opacity": ["interpolate", ["linear"], ["zoom"], 6, 0, 11, 1]}},
        {"id": "water", "type": "fill", "source-layer": "water", "paint": {"fill-color": c["water"]}},
        {"id": "waterway", "type": "line", "source-layer": "waterway", "minzoom": 9,
         "filter": ["in", ["get", "class"], ["literal", ["river", "canal"]]],
         "paint": {"line-color": c["water"], "line-width": ["interpolate", ["exponential", 1.6], ["zoom"], 9, 0, 9.5, 1, 18, 12]}},
        {"id": "building", "type": "fill", "source-layer": "building", "minzoom": 13, "paint": {"fill-color": c["building"]}},
        {"id": "boundary", "type": "line", "source-layer": "boundary",
         "filter": ["all", ["==", ["get", "admin_level"], 2], ["!=", ["get", "maritime"], 1]],
         "paint": {"line-color": c["boundary"], "line-width": ["interpolate", ["linear"], ["zoom"], 3, 0.6, 10, 1.5],
                   "line-dasharray": [3, 2]}},
        {"id": "roads_other", "type": "line", "source-layer": "transportation", "minzoom": 12, "filter": road_filter(OTHER),
         "paint": {"line-color": c["other"], "line-width": W_OTHER, "line-dasharray": [2, 1.5]}},
        {"id": "roads_rail", "type": "line", "source-layer": "transportation", "minzoom": 10,
         "filter": road_filter(["rail"]), "paint": {"line-color": c["rail"], "line-width": 1}},
    ]
    # Casings first, then the road fills, so crossings of the same class join up.
    for name, classes, width, minzoom in (("minor", MINOR, W_MINOR, 12), ("major", MAJOR, W_MAJOR, 9), ("highway", HIGHWAY, W_HIGHWAY, 7)):
        out.append({"id": f"roads_{name}_casing", "type": "line", "source-layer": "transportation", "minzoom": minzoom,
                    "filter": road_filter(classes), "layout": {"line-cap": "round", "line-join": "round"},
                    "paint": {"line-color": c["casing" if name == "minor" else f"casing_{name}"], "line-gap-width": width, "line-width": 1}})
    for name, classes, width in (("minor", MINOR, W_MINOR), ("major", MAJOR, W_MAJOR), ("highway", HIGHWAY, W_HIGHWAY)):
        out.append({"id": f"roads_{name}", "type": "line", "source-layer": "transportation",
                    "filter": road_filter(classes), "layout": {"line-cap": "round", "line-join": "round"},
                    "paint": {"line-color": ["case", ["==", ["get", "brunnel"], "tunnel"], c["tunnel"], c[name]], "line-width": width}})
    out += [
        {"id": "water_label", "type": "symbol", "source-layer": "water_name",
         "layout": {"text-field": NAME, "text-font": ["Noto Sans Italic"], "text-size": 12, "text-max-width": 7},
         "paint": {"text-color": c["water_text"], "text-halo-color": c["water_halo"], "text-halo-width": 1}},
        {"id": "roads_labels", "type": "symbol", "source-layer": "transportation_name", "minzoom": 12,
         "layout": {"symbol-placement": "line", "text-field": NAME, "text-font": ["Noto Sans Regular"],
                    "text-size": ["interpolate", ["linear"], ["zoom"], 12, 10, 16, 13]},
         "paint": {"text-color": c["road_text"], "text-halo-color": c["road_halo"], "text-halo-width": 1}},
        {"id": "places", "type": "symbol", "source-layer": "place",
         "filter": ["in", ["get", "class"], ["literal", ["city", "town", "village", "hamlet", "suburb", "island"]]],
         "layout": {"text-field": NAME, "symbol-sort-key": ["get", "rank"], "text-max-width": 8,
                    "text-font": ["case", ["==", ["get", "class"], "city"], ["literal", ["Noto Sans Medium"]], ["literal", ["Noto Sans Regular"]]],
                    "text-size": ["match", ["get", "class"], "city", 16, "town", 14, "village", 12, 11]},
         "paint": {"text-color": c["place_text"], "text-halo-color": c["place_halo"], "text-halo-width": 1}},
        {"id": "places_country", "type": "symbol", "source-layer": "place", "maxzoom": 7,
         "filter": ["==", ["get", "class"], "country"],
         "layout": {"text-field": NAME, "text-font": ["Noto Sans Medium"], "text-size": 13, "text-max-width": 6},
         "paint": {"text-color": c["country_text"], "text-halo-color": c["place_halo"], "text-halo-width": 1}},
    ]
    for layer in out:
        layer["id"] = "online-" + layer["id"]
        if layer["type"] != "background":
            layer["source"] = SOURCE
    return out


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    styles = os.path.join(here, "..", "..", "app", "src", "main", "assets", "map", "styles")
    for flavor, colours in FLAVORS.items():
        style = {"source": {"type": "vector", "url": TILES, "attribution": "© OpenStreetMap contributors, OpenFreeMap"},
                 "layers": layers(colours)}
        with open(os.path.join(styles, f"online-{flavor}.json"), "w") as f:
            json.dump(style, f, separators=(",", ":"))
    print("Wrote online-light.json, online-dark.json")


if __name__ == "__main__":
    main()
