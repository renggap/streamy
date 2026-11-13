#!/usr/bin/env python3
"""Generate Google Play listing assets from the Streamy launcher icon design.

- icon-512.png: 512x512, 32-bit, no alpha (Play requirement).
- feature-graphic-1024x500.png: 1024x500, no alpha.

Design source: app/src/main/res/drawable/ic_launcher_{background,foreground}.xml
(dark #14161A square, ring #E6E8EC stroke 5, on-air red dot #FF3B30, 108 viewport).
Everything is drawn at 4x and downscaled for antialiasing.
"""
import os
import pathlib
import tempfile
import urllib.request

from PIL import Image, ImageDraw, ImageFont

BG = (20, 22, 26)        # #14161A
RING = (230, 232, 236)   # #E6E8EC
RED = (255, 59, 48)      # #FF3B30
TAG = (168, 175, 184)    # soft gray for tagline on dark
S = 4  # supersample factor

# Brand font (variable wght 300-700), fetched on first run into the system temp dir.
FONT_URL = "https://github.com/google/fonts/raw/main/ofl/spacegrotesk/SpaceGrotesk%5Bwght%5D.ttf"
FONT = f"{tempfile.gettempdir()}/SpaceGrotesk.ttf"

REPO = pathlib.Path(__file__).resolve().parents[2]
OUT = str(REPO / "web" / "assets" / "play")


def ensure_font():
    if not os.path.exists(FONT):
        urllib.request.urlretrieve(FONT_URL, FONT)
    return FONT


def ring(draw, cx, cy, r, w, color, bg=BG):
    """Stroke a circle of radius r with pen width w (fill between two ellipses)."""
    draw.ellipse([cx - r - w / 2, cy - r - w / 2, cx + r + w / 2, cy + r + w / 2], fill=color)
    draw.ellipse([cx - r + w / 2, cy - r + w / 2, cx + r - w / 2, cy + r - w / 2], fill=bg)


def font(px, weight):
    f = ImageFont.truetype(ensure_font(), px)
    f.set_variation_by_axes([weight])
    return f


def make_icon():
    size = 512 * S
    img = Image.new("RGB", (size, size), BG)
    d = ImageDraw.Draw(img)
    s = size / 108  # vector viewport scale
    c = size / 2
    ring(d, c, c, 24 * s, 5 * s, RING)          # ring from ic_launcher_foreground
    d.ellipse([c - 16 * s, c - 16 * s, c + 16 * s, c + 16 * s], fill=RED)  # on-air dot
    img.resize((512, 512), Image.LANCZOS).save(f"{OUT}/icon-512.png")
    print("icon-512.png")


def make_feature():
    w, h = 1024 * S, 500 * S
    img = Image.new("RGB", (w, h), BG)
    d = ImageDraw.Draw(img)

    # Tally mark on the left, same proportions as the icon (ring:dot = 24:16).
    cx, cy = 185 * S, h // 2
    r, sw, dot = 78 * S, 16 * S, 52 * S
    ring(d, cx, cy, r, sw, RING)
    d.ellipse([cx - dot, cy - dot, cx + dot, cy + dot], fill=RED)

    # Wordmark + tagline, left-aligned after the mark, vertically centered as a group.
    x = 310 * S
    max_w = 1024 * S - x - 60 * S  # keep 60px right margin (Play safe area)
    name_f = font(112 * S, 700)
    tag_f = font(40 * S, 400)
    name = "Streamy"
    tag = "Siaran radio internet dari ponsel Anda"
    # Shrink the tagline until it fits beside the mark — PIL doesn't wrap text.
    while d.textlength(tag, font=tag_f) > max_w and tag_f.size > 16 * S:
        tag_f = font(tag_f.size - 2 * S, 400)
    name_box = d.textbbox((0, 0), name, font=name_f)
    tag_box = d.textbbox((0, 0), tag, font=tag_f)
    gap = 22 * S
    total_h = (name_box[3] - name_box[1]) + gap + (tag_box[3] - tag_box[1])
    y = (h - total_h) // 2 - name_box[1]
    d.text((x, y), name, font=name_f, fill=RING)
    d.text((x, y + (name_box[3] - name_box[1]) + gap), tag, font=tag_f, fill=TAG)

    img.resize((1024, 500), Image.LANCZOS).save(f"{OUT}/feature-graphic-1024x500.png")
    print("feature-graphic-1024x500.png")


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    make_icon()
    make_feature()
