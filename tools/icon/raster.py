# Launcher icons from the painted Curveon artwork (a square PNG, e.g. ~/Pictures/curveon-icon.png).
# Usage: python3 tools/icon/raster.py <artwork.png> [export-dir]
# Writes the adaptive icon layers of :app and :companion (bitmaps on the 108 dp canvas), the legacy
# mipmaps, and a round and a rounded-square PNG of the icon in export-dir (default: next to the art).
import os, sys
import numpy as np
from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SRC = sys.argv[1]
OUT = sys.argv[2] if len(sys.argv) > 2 else os.path.dirname(os.path.abspath(SRC))
PX = 4            # px per dp: 108 dp canvas -> 432 px (xxxhdpi)
ART_DP = 68       # a little inside the 72 dp visible area, so a round mask keeps the mirrors
SS = 4            # supersampling for drawn shapes

art = Image.open(SRC).convert("RGB")

def padded(img, size, inner, fade=0.05):
    """img scaled to inner x inner, centred on a size x size canvas. Edge pixels are repeated
    outwards and fade into the edge's own mean colour, so the road leaving the bottom doesn't streak."""
    a = np.asarray(img.resize((inner, inner), Image.LANCZOS)).astype(np.float32)
    p = (size - inner) // 2
    out = np.pad(a, ((p, size - inner - p), (p, size - inner - p), (0, 0)), mode="edge")
    flat = np.pad(np.zeros((inner, inner, 3), np.float32), ((p, size - inner - p), (p, size - inner - p), (0, 0)))
    # per-pixel distance outside the art, and the mean colour of the nearest edge
    yy, xx = np.mgrid[0:size, 0:size]
    dy = np.maximum(p - yy, yy - (p + inner - 1)).clip(0); dx = np.maximum(p - xx, xx - (p + inner - 1)).clip(0)
    edge = np.where((dy >= dx)[..., None], np.where((yy < p)[..., None], a[0].mean(0), a[-1].mean(0)),
                    np.where((xx < p)[..., None], a[:, 0].mean(0), a[:, -1].mean(0)))
    t = (np.maximum(dx, dy) / (fade * size)).clip(0, 1)[..., None]
    return Image.fromarray((out * (1 - t) + edge * t).astype(np.uint8))

def mono(img):
    """White glyph whose alpha is the bright parts (orange bike and road, cyan arrow, white dashes)."""
    a = np.asarray(img).astype(np.float32).max(axis=2)
    alpha = np.clip((a - 70) / 70, 0, 1) * 255
    out = np.zeros(a.shape + (4,), np.uint8); out[..., :3] = 255; out[..., 3] = alpha.astype(np.uint8)
    return Image.fromarray(out)

def mask(size, shape):
    m = Image.new("L", (size * SS, size * SS), 0); d = ImageDraw.Draw(m)
    if shape == "circle": d.ellipse((0, 0, size * SS - 1, size * SS - 1), fill=255)
    else: d.rounded_rectangle((0, 0, size * SS - 1, size * SS - 1), radius=int(size * SS * 0.22), fill=255)
    return m.resize((size, size), Image.LANCZOS)

def save(img, *path):
    p = os.path.join(ROOT, *path); os.makedirs(os.path.dirname(p), exist_ok=True); img.save(p, optimize=True); print(p)

C = 108 * PX
# :app — the whole artwork is the background layer; the foreground is empty
full = padded(art, C, ART_DP * PX)
save(full, "app/src/main/res/drawable-nodpi/ic_launcher_art.png")
save(mono(padded(art, C, ART_DP * PX)), "app/src/main/res/drawable-nodpi/ic_launcher_mono.png")

# Legacy mipmaps and exports: the visible 72 dp of the adaptive icon, as rounded square and circle
vis = full.crop((18 * PX, 18 * PX, 90 * PX, 90 * PX))
for dpi, n in (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)):
    for name, shape in (("ic_launcher", "square"), ("ic_launcher_round", "circle")):
        im = vis.resize((n, n), Image.LANCZOS).convert("RGBA"); im.putalpha(mask(n, shape))
        save(im, f"app/src/main/res/mipmap-{dpi}/{name}.png")
n = art.width; v = round(n * 72 / ART_DP); c = round(n * 108 / ART_DP)
big = {"circle": padded(art, c, n).crop(((c - v) // 2,) * 2 + ((c + v) // 2,) * 2).convert("RGBA"),
       "square": art.convert("RGBA")}
for suffix, shape in (("round", "circle"), ("rounded", "square")):
    im = big[shape]; im.putalpha(mask(im.width, shape))
    p = os.path.join(OUT, f"curveon-icon-{suffix}.png"); im.save(p, optimize=True); print(p)

# :companion — the cyan phone from the old icon, with the artwork on its screen
def phone_layer(screen_img, frame_rgba):
    S = C * SS; k = PX * SS
    frame = Image.new("RGBA", (S, S), (0, 0, 0, 0)); d = ImageDraw.Draw(frame)
    d.rounded_rectangle((36 * k, 29 * k, 72 * k, 79 * k), radius=6 * k, fill=frame_rgba)
    d.rounded_rectangle((40 * k, 33 * k, 68 * k, 75 * k), radius=2 * k, fill=(0, 0, 0, 0))
    frame = frame.resize((C, C), Image.LANCZOS)
    sm = Image.new("L", (S, S), 0)
    ImageDraw.Draw(sm).rounded_rectangle((40 * k, 33 * k, 68 * k, 75 * k), radius=2 * k, fill=255)
    sm = sm.resize((C, C), Image.LANCZOS)
    out = Image.new("RGBA", (C, C), (0, 0, 0, 0))
    out.paste(screen_img, (0, 0), Image.fromarray(np.minimum(np.asarray(sm), np.asarray(screen_img.getchannel("A")))))
    return Image.alpha_composite(out, frame)

# Screen is 28 x 42 dp: the art 34 dp wide at the bottom (sides cropped), sky repeated above it
w, h = 28 * PX, 42 * PX; aw = 34 * PX
a = np.asarray(art.resize((aw, aw), Image.LANCZOS))
x0 = (aw - w) // 2 - PX  # a bit left of centre: keeps the left mirror, the road can run off the right
scr = np.pad(a, ((h - aw, 0), (0, 0), (0, 0)), mode="edge")[:, x0:x0 + w]
screen = Image.new("RGBA", (C, C), (0, 0, 0, 0)); screen.paste(Image.fromarray(scr).convert("RGBA"), (40 * PX, 33 * PX))
save(phone_layer(screen, (0, 229, 255, 255)), "companion/src/main/res/drawable-nodpi/ic_launcher_phone.png")
mscreen = Image.new("RGBA", (C, C), (0, 0, 0, 0)); mscreen.paste(mono(Image.fromarray(scr)), (40 * PX, 33 * PX))
save(phone_layer(mscreen, (255, 255, 255, 255)), "companion/src/main/res/drawable-nodpi/ic_launcher_phone_mono.png")
