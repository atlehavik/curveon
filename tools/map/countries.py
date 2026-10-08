#!/usr/bin/env python3
"""
Step 1 of build-countries.sh: turns Natural Earth 1:50m countries (and, for a few huge countries,
their states/provinces) into a plain tab-separated list the Kotlin tool can read without a JSON
library:

    id  name  iso2  continent  parent  geometry-json

Overseas parts far from the main land (French Guiana for France, Hawaii for the USA, …) are
dropped, so a country download doesn't pull in another ocean.
"""
import json
import math
import re
import sys

SKIP_CONTINENTS = {"Antarctica", "Seven seas (open ocean)"}
# Parts further than this from the main part's bounding box are left out.
OVERSEAS_KM = 2000


def slug(s):
    s = s.lower().replace("å", "a").replace("ø", "o").replace("æ", "ae")
    return re.sub(r"[^a-z0-9]+", "-", s).strip("-")


def parts(geom):
    return [geom["coordinates"]] if geom["type"] == "Polygon" else geom["coordinates"]


def bbox(poly):
    pts = poly[0]
    return (min(p[0] for p in pts), min(p[1] for p in pts), max(p[0] for p in pts), max(p[1] for p in pts))


def area(poly):
    pts = poly[0]
    lat = sum(p[1] for p in pts) / len(pts)
    a = sum(pts[i][0] * pts[i + 1][1] - pts[i + 1][0] * pts[i][1] for i in range(len(pts) - 1))
    return abs(a) / 2 * math.cos(math.radians(lat))


def bbox_gap_km(a, b):
    dx = max(0, max(a[0], b[0]) - min(a[2], b[2]))
    dy = max(0, max(a[1], b[1]) - min(a[3], b[3]))
    lat = (max(a[1], b[1]) + min(a[3], b[3])) / 2
    return math.hypot(dx * 111 * math.cos(math.radians(lat)), dy * 111)


def mainland(geom):
    ps = parts(geom)
    main = bbox(max(ps, key=area))
    kept = [p for p in ps if bbox_gap_km(bbox(p), main) <= OVERSEAS_KM]
    return {"type": "MultiPolygon", "coordinates": kept}


def main(countries_path, states_path, split, out_path):
    split = set(split.split(","))
    rows = []
    for f in json.load(open(countries_path))["features"]:
        p = f["properties"]
        if p["CONTINENT"] in SKIP_CONTINENTS:
            continue
        iso = p["ISO_A2_EH"] if p["ISO_A2_EH"] != "-99" else ""
        cid = slug(p["NAME_EN"])
        rows.append((cid, p["NAME_EN"], iso, p["CONTINENT"], "", mainland(f["geometry"]), p["ADMIN"]))
    by_name = {r[6]: r for r in rows}
    # Russia has 85 subjects: group them by federal district instead.
    groups = {}
    for f in json.load(open(states_path))["features"]:
        p = f["properties"]
        if p["admin"] not in split:
            continue
        parent = by_name[p["admin"]]
        name = p["region"] if p["admin"] == "Russia" else p["name_en"] or p["name"]
        key = (parent[0], name)
        if key in groups and groups[key][1] != p["iso_3166_2"] and p["admin"] != "Russia":
            name, key = p["name"], (parent[0], p["name"])  # e.g. District of Columbia vs Washington state
        g = groups.setdefault(key, [parent, p["iso_3166_2"], name, []])
        g[3].extend(parts(f["geometry"]))
    for (pid, _), (parent, _, name, polys) in groups.items():
        rows.append((pid + "-" + slug(name), name, parent[2], parent[3], pid, {"type": "MultiPolygon", "coordinates": polys}))
    with open(out_path, "w") as out:
        for r in rows:
            out.write("\t".join(r[:5]) + "\t" + json.dumps(r[5], separators=(",", ":")) + "\n")
    print(f"{len(rows)} areas -> {out_path}")


if __name__ == "__main__":
    main(*sys.argv[1:])
