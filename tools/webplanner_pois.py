#!/usr/bin/env python3
"""
Takes the places the web planner shows (fuel, viewpoints, mountain passes, campsites, food, ferries, motorcycle shops)
out of the app's offline search indexes, one TSV per area (kind, lat, lon, name), for the planner service (POIS=dir).

    python3 -I webplanner_pois.py OUT --catalog countries.json       # every European area, from the `search` release
    python3 -I webplanner_pois.py OUT --sqlite norway-search.sqlite  # one index at hand (trying it on a PC)

The indexes are the ones the app downloads (github.com/atlehavik/curveon-data, release `search`, built monthly from
OpenStreetMap and Overture Maps). Each is downloaded, read and deleted again: only the TSVs stay, a few tens of MB for
all of Europe. An area whose index has not changed since last time (OUT/state.json) is not downloaded again.
"""
import gzip
import json
import os
import shutil
import sqlite3
import sys
import urllib.request

KINDS = ("fuel", "viewpoint", "pass", "camp_site", "cafe", "restaurant", "fast_food", "ferry", "motorcycle_shop", "workshop")
RELEASE = "https://api.github.com/repos/atlehavik/curveon-data/releases/tags/search"
# Where the planner routes (tools/deploy-webplanner.sh fetches BRouter segments for this box).
EUROPE = (-25.0, 34.0, 45.0, 72.0)


def european_areas(catalog):
    areas = json.load(open(catalog))["areas"]
    out = []
    for a in areas:
        w, s, e, n = a["bbox"]
        cx, cy = (w + e) / 2, (s + n) / 2
        if EUROPE[0] < cx < EUROPE[2] and EUROPE[1] < cy < EUROPE[3] and e - w < 60:
            out.append(a["id"])
    return out


def extract(db_path, tsv_path):
    con = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True)
    n = 0
    with open(tsv_path + ".part", "w", encoding="utf-8") as f:
        q = f"SELECT kind, lat, lon, name FROM entries WHERE kind IN ({','.join('?' * len(KINDS))})"
        for kind, lat, lon, name in con.execute(q, KINDS):
            name = " ".join(str(name or "").split())[:80]
            f.write(f"{kind}\t{lat:.6f}\t{lon:.6f}\t{name}\n")
            n += 1
    con.close()
    os.replace(tsv_path + ".part", tsv_path)
    return n


def main():
    if len(sys.argv) < 4 or sys.argv[2] not in ("--catalog", "--sqlite"):
        sys.exit(__doc__)
    out, how, src = sys.argv[1], sys.argv[2], sys.argv[3]
    os.makedirs(out, exist_ok=True)
    if how == "--sqlite":
        name = os.path.basename(src).split(".")[0]
        print(f"{name}: {extract(src, os.path.join(out, name + '.tsv'))} places")
        return
    state_path = os.path.join(out, "state.json")
    state = json.load(open(state_path)) if os.path.exists(state_path) else {}
    release = json.load(urllib.request.urlopen(urllib.request.Request(RELEASE, headers={"Accept": "application/vnd.github+json"}), timeout=60))
    assets = {a["name"]: a for a in release["assets"]}
    tmp = os.path.join(out, ".tmp")
    os.makedirs(tmp, exist_ok=True)
    total = 0
    for area in european_areas(src):
        asset = assets.get(area + ".sqlite.gz")
        tsv = os.path.join(out, area + ".tsv")
        if not asset:
            continue
        if state.get(area) == asset["updated_at"] and os.path.exists(tsv):
            continue
        gz, db = os.path.join(tmp, area + ".sqlite.gz"), os.path.join(tmp, area + ".sqlite")
        try:
            with urllib.request.urlopen(asset["browser_download_url"], timeout=120) as r, open(gz, "wb") as f:
                shutil.copyfileobj(r, f, 1 << 20)
            with gzip.open(gz, "rb") as r, open(db, "wb") as f:
                shutil.copyfileobj(r, f, 1 << 20)
            n = extract(db, tsv)
            total += n
            state[area] = asset["updated_at"]
            json.dump(state, open(state_path, "w"))
            print(f"{area}: {n} places", flush=True)
        except Exception as e:  # one area failing keeps the others (and its old TSV, if any)
            print(f"{area}: failed ({e})", flush=True)
        finally:
            for p in (gz, db):
                if os.path.exists(p):
                    os.remove(p)
    print(f"places: {len([f for f in os.listdir(out) if f.endswith('.tsv')])} areas, {total} places updated")


if __name__ == "__main__":
    main()
