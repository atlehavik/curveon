#!/usr/bin/env python3
"""
Matches every area of the map catalog (app/src/main/assets/map/countries.json) to the Geofabrik
OpenStreetMap extract(s) its search index is built from, and writes areas.json for the public
curveon-data repo, whose GitHub Action builds one index per area.

    python3 geofabrik_areas.py <countries.json> <areas.json>

Matching: countries by ISO code, US states / Canadian provinces / Russian federal districts by
name, and anything else (Vatican, Jersey, small islands, …) by the smallest extract whose outline
contains the area, as long as it isn't huge. Areas without a match get their search built on the
tablet from the map instead.
"""
import json
import re
import sys
import urllib.request

INDEX = "https://download.geofabrik.de/index-v1.json"
RUSSIA = {
    "Central": ["central-fed-district"], "Far Eastern": ["far-eastern-fed-district"],
    "Northwestern": ["northwestern-fed-district"], "Siberian": ["siberian-fed-district"],
    "Urals": ["ural-fed-district"],
    # Natural Earth's old Volga district also holds the south and the North Caucasus.
    "Volga": ["volga-fed-district", "south-fed-district", "north-caucasus-fed-district"],
}
# A fallback extract this many times bigger (by bounding box) than the area is too wasteful.
MAX_FALLBACK_RATIO = 400


def slug(s):
    return re.sub(r"[^a-z0-9]+", "-", s.lower()).strip("-")


def bbox(geom):
    pts = []

    def walk(c):
        if isinstance(c[0], (int, float)):
            pts.append(c)
        else:
            for x in c:
                walk(x)
    walk(geom["coordinates"])
    return (min(p[0] for p in pts), min(p[1] for p in pts), max(p[0] for p in pts), max(p[1] for p in pts))


def area(b):
    return max(b[2] - b[0], 0.01) * max(b[3] - b[1], 0.01)


def main(catalog_path, out_path):
    catalog = json.load(open(catalog_path))["areas"]
    feats = json.load(urllib.request.urlopen(INDEX))["features"]
    by_id = {f["properties"]["id"]: f for f in feats}
    by_iso = {}
    for f in feats:
        for iso in f["properties"].get("iso3166-1:alpha2", []):
            by_iso.setdefault(iso, []).append(f)
    boxes = {f["properties"]["id"]: bbox(f["geometry"]) for f in feats if f.get("geometry")}

    out, unmatched = {}, []
    for a in catalog:
        ids = []
        if a["parent"] == "united-states-of-america":
            ids = ["us/" + slug(a["name"])]
        elif a["parent"] == "canada":
            ids = [f["properties"]["id"] for f in feats
                   if f["properties"].get("parent") == "canada" and f["properties"]["name"] == a["name"]]
        elif a["parent"] == "russia":
            ids = RUSSIA.get(a["name"], [])
        elif a["parent"] == "" and a["iso"] in by_iso:
            # Prefer the extract made for this country alone, then the smallest shared one.
            cands = sorted(by_iso[a["iso"]], key=lambda f: (len(f["properties"]["iso3166-1:alpha2"]),
                                                             area(boxes.get(f["properties"]["id"], (0, 0, 360, 180)))))
            ids = [cands[0]["properties"]["id"]]
        ids = [i for i in ids if i in by_id]
        if not ids and a["parent"] == "":
            b = a["bbox"]
            cx, cy = (b[0] + b[2]) / 2, (b[1] + b[3]) / 2
            inside = [(area(fb), fid) for fid, fb in boxes.items()
                      if fb[0] <= b[0] and fb[1] <= b[1] and fb[2] >= b[2] and fb[3] >= b[3]
                      and area(fb) <= MAX_FALLBACK_RATIO * area(b) and fb[0] <= cx <= fb[2] and fb[1] <= cy <= fb[3]]
            if inside:
                ids = [min(inside)[1]]
        if ids:
            out[a["id"]] = [by_id[i]["properties"]["urls"]["pbf"] for i in ids]
        elif a["parent"] == "" and not any(c["parent"] == a["id"] for c in catalog):
            unmatched.append(a["id"])
    json.dump(out, open(out_path, "w"), indent=1, sort_keys=True)
    print(f"{len(out)} areas matched -> {out_path}")
    print("built on the tablet instead:", ", ".join(unmatched))


if __name__ == "__main__":
    main(*sys.argv[1:])
