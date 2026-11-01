#!/usr/bin/env python3
"""
Builds app/src/main/assets/land-mask.dat: which parts of the world are land, for leaving the weather symbols off the
sea in every country, offline, with or without the country's map and routing data (see LandMask.kt).

Source: Natural Earth 1:10m (public domain, no credit needed), downloaded by hand:
  https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson/ne_10m_{land,minor_islands,lakes}.geojson

  python3 -I tools/build_land_mask.py <folder with the three .geojson files> app/src/main/assets/land-mask.dat

Not named .gz: Android's AssetManager would hand it over unpacked, and only on a device.

Format, gzipped: one bit per cell of 0.05 x 0.05 degrees, row by row from 90 south and each row from 180 west, lowest
bit first in each byte. A cell is land when at least LAND_SHARE of it is land and not a lake.
"""
import gzip
import json
import os
import sys

import numpy as np
from PIL import Image, ImageDraw

CELLS_PER_DEGREE = 20
SUPER = 4  # samples per cell along each side
LAND_SHARE = 0.1
WIDTH = 360 * CELLS_PER_DEGREE * SUPER
HEIGHT = 180 * CELLS_PER_DEGREE * SUPER


def pixels(ring):
    return [((lon + 180) / 360 * WIDTH, (lat + 90) / 180 * HEIGHT) for lon, lat, *_ in ring]


def polygons(path):
    with open(path) as f:
        for feature in json.load(f)["features"]:
            geometry = feature["geometry"]
            if geometry["type"] == "Polygon":
                yield geometry["coordinates"]
            elif geometry["type"] == "MultiPolygon":
                yield from geometry["coordinates"]


def area(rings):
    x, y = np.array(pixels(rings[0])).T
    return abs(np.dot(x, np.roll(y, 1)) - np.dot(y, np.roll(x, 1))) / 2


def main(source, out):
    Image.MAX_IMAGE_PIXELS = None
    image = Image.new("L", (WIDTH, HEIGHT), 0)
    draw = ImageDraw.Draw(image)
    land = list(polygons(os.path.join(source, "ne_10m_land.geojson"))) + \
        list(polygons(os.path.join(source, "ne_10m_minor_islands.geojson")))
    # The biggest first, so an island in a hole of a bigger polygon is drawn after the hole.
    for rings in sorted(land, key=area, reverse=True):
        draw.polygon(pixels(rings[0]), fill=1)
        for hole in rings[1:]:
            draw.polygon(pixels(hole), fill=0)
    # The other way round for lakes: islands in them stay land by their hole.
    for rings in sorted(polygons(os.path.join(source, "ne_10m_lakes.geojson")), key=area, reverse=True):
        draw.polygon(pixels(rings[0]), fill=0)
        for hole in rings[1:]:
            draw.polygon(pixels(hole), fill=1)

    samples = np.asarray(image, dtype=np.uint8)
    rows, cols = HEIGHT // SUPER, WIDTH // SUPER
    share = samples.reshape(rows, SUPER, cols, SUPER).mean(axis=(1, 3))
    mask = share >= LAND_SHARE
    packed = np.packbits(mask, axis=1, bitorder="little")
    with open(out, "wb") as f:
        with gzip.GzipFile(fileobj=f, mode="wb", compresslevel=9, mtime=0) as z:
            z.write(packed.tobytes())
    print(f"{out}: {os.path.getsize(out)} bytes gzipped, {packed.size} raw, {mask.mean():.1%} land")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
