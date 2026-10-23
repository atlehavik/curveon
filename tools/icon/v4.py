#!/usr/bin/env python3
"""
Makes every Curveon icon from the two source files in this folder:
  curveon-icon-v4.png          the emblem on a full square (no rounded corners): Google Play, Apple touch icon
  curveon-icon-v4-round.png    the emblem in a circle (transparent corners)
  curveon-icon-v4-rounded.png  the same in a rounded square (transparent corners)

Writes the launcher icons of :app and :companion, the website's icons, the sharing picture and the Google Play icon.
Run from the repository root:  python3 -I tools/icon/v4.py
"""
import colorsys
import os
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
FRAME = (72, 64, 62)            # the colour of the frame round the emblem (#48403E)
NIGHT = (11, 14, 20)            # the website's and the sharing picture's background

round_ = Image.open(os.path.join(HERE, "curveon-icon-v4-round.png")).convert("RGBA")
rounded = Image.open(os.path.join(HERE, "curveon-icon-v4-rounded.png")).convert("RGBA")
square = Image.open(os.path.join(HERE, "curveon-icon-v4.png")).convert("RGBA")


def out(path, im):
    p = os.path.join(ROOT, path)
    os.makedirs(os.path.dirname(p), exist_ok=True)
    im.save(p, optimize=True)


def sized(im, n):
    return im.resize((n, n), Image.LANCZOS)


def corner_gradient(n):
    """The square's own background (the frame colour fading at the edges) without the emblem: its four corners, blended."""
    w = square.width
    def mean(x, y):
        patch = square.convert("RGB").crop((x, y, x + 24, y + 24)).resize((1, 1), Image.BOX)
        return patch.getpixel((0, 0))
    tl, tr, bl, br = mean(0, 0), mean(w - 24, 0), mean(0, w - 24), mean(w - 24, w - 24)
    out_ = Image.new("RGB", (n, n))
    px = out_.load()
    for y in range(n):
        for x in range(n):
            fx, fy = x / (n - 1), y / (n - 1)
            px[x, y] = tuple(round((tl[i] * (1 - fx) + tr[i] * fx) * (1 - fy) + (bl[i] * (1 - fx) + br[i] * fx) * fy) for i in range(3))
    return out_.convert("RGBA")


def mono_mask(n):
    """The bike alone as a white shape: orange and cyan are solid, the headlights are holes. n px square."""
    src = round_.convert("RGB")
    w = src.width
    m = Image.new("L", src.size, 0)
    px, mp = src.load(), m.load()
    cx = cy = w / 2
    limit = (0.43 * w) ** 2
    for y in range(w):
        for x in range(w):
            if (x - cx) ** 2 + (y - cy) ** 2 > limit:
                continue
            r, g, b = px[x, y]
            h, s, v = colorsys.rgb_to_hsv(r / 255, g / 255, b / 255)
            if s > 0.45 and v > 0.4:
                mp[x, y] = 255
    return m.resize((n, n), Image.LANCZOS)


def white(mask):
    im = Image.new("RGBA", mask.size, (255, 255, 255, 0))
    im.putalpha(mask)
    return im


def centred(inner, canvas=432):
    c = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
    o = (canvas - inner.width) // 2
    c.alpha_composite(inner, (o, o))
    return c


# --- :app launcher. Adaptive: the square's own background behind the emblem (70 dp of the 108 dp the system masks).
EMBLEM = 432 * 70 // 108
out("app/src/main/res/drawable-nodpi/ic_launcher_bg.png", corner_gradient(432))
out("app/src/main/res/drawable-nodpi/ic_launcher_emblem.png", centred(sized(round_, EMBLEM)))
out("app/src/main/res/drawable-nodpi/ic_launcher_mono.png", centred(white(mono_mask(EMBLEM))))
for dpi, n in (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)):
    out(f"app/src/main/res/mipmap-{dpi}/ic_launcher.png", sized(rounded, n))
    out(f"app/src/main/res/mipmap-{dpi}/ic_launcher_round.png", sized(round_, n))


# --- :companion launcher: the same emblem inside a phone's frame.
def phone(fill, emblem):
    c = Image.new("RGBA", (432, 432), (0, 0, 0, 0))
    d = ImageDraw.Draw(c)
    d.rounded_rectangle((145, 115, 288, 315), radius=30, fill=(0, 229, 255, 255))
    d.rounded_rectangle((161, 131, 272, 299), radius=18, fill=fill)
    c.alpha_composite(emblem, (216 - emblem.width // 2, 215 - emblem.height // 2))
    return c


out("companion/src/main/res/drawable-nodpi/ic_launcher_bg.png", corner_gradient(432))
out("companion/src/main/res/drawable-nodpi/ic_launcher_phone.png", phone(FRAME + (255,), sized(round_, 108)))
out("companion/src/main/res/drawable-nodpi/ic_launcher_phone_mono.png", phone((0, 0, 0, 0), white(mono_mask(96))))

# --- the website
out("website/icon-512.png", sized(rounded, 512))
out("website/icon-192.png", sized(rounded, 192))
out("website/favicon-32.png", sized(rounded, 32))
out("website/apple-touch-icon.png", sized(square, 180))
share = Image.new("RGBA", (1280, 640), NIGHT + (255,))
share.alpha_composite(sized(round_, 480), (400, 80))
out("docs/social-preview.png", share)
out("website/social-preview.png", share)

# --- Google Play: 512 px, a full square (Play rounds it)
out("tools/icon/play-store-icon-512.png", sized(square, 512))
print("done")
