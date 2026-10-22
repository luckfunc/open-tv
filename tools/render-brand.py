#!/usr/bin/env python3
"""Renders the TV banner PNGs from assets/brand/banner-art.svg plus the Inter wordmark.

    python3 tools/render-brand.py          # needs rsvg-convert and Pillow

The square launcher icon is a vector adaptive icon (res/drawable/ic_launcher_*.xml) on Android 8.0
and later; assets/brand/icon.svg is its source of truth for the README. Android 5.x to 7.x
cannot read adaptive icons, so this also renders mipmap-*/ic_launcher.png from that SVG.
"""
import os
import subprocess
import tempfile

from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ART = os.path.join(ROOT, "assets/brand/banner-art.svg")
ICON = os.path.join(ROOT, "assets/brand/icon.svg")
FONT = os.path.join(ROOT, "app/src/main/res/font/inter.ttf")
RES = os.path.join(ROOT, "app/src/main/res")


def banner(scale):
    w, h = 320 * scale, 180 * scale
    with tempfile.NamedTemporaryFile(suffix=".png") as tmp:
        subprocess.run(["rsvg-convert", "-w", str(w), "-h", str(h), ART, "-o", tmp.name], check=True)
        img = Image.open(tmp.name).convert("RGBA")
    font = ImageFont.truetype(FONT, 40 * scale)
    font.set_variation_by_axes([32, 750])  # optical size (display), weight
    draw = ImageDraw.Draw(img)
    text = "OpenTV"
    left, top, right, bottom = draw.textbbox((0, 0), text, font=font)
    x = 123 * scale
    y = (h - (bottom - top)) / 2 - top + 2 * scale
    draw.text((x, y), text, font=font, fill=(20, 20, 20, 255))
    return img


def legacy_icon(px):
    """The visible 72 of the 108 adaptive canvas, as a rounded square px wide."""
    full = px * 108 // 72
    with tempfile.NamedTemporaryFile(suffix=".png") as tmp:
        subprocess.run(["rsvg-convert", "-w", str(full), "-h", str(full), ICON, "-o", tmp.name], check=True)
        img = Image.open(tmp.name).convert("RGBA")
    inset = (full - px) // 2
    img = img.crop((inset, inset, inset + px, inset + px))
    # Draw the mask at 4x and scale down for a smooth edge.
    mask = Image.new("L", (px * 4, px * 4), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, px * 4 - 1, px * 4 - 1), radius=px * 4 * 0.22, fill=255)
    img.putalpha(mask.resize((px, px), Image.LANCZOS))
    return img


if __name__ == "__main__":
    legacy_icon(512).save(os.path.join(ROOT, "assets/brand/icon.png"), optimize=True)
    banner(3).crop((0, 78, 960, 462)).convert("RGB").save(
        os.path.join(ROOT, "assets/brand/wordmark.png"), optimize=True)

    for density, px in (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)):
        out = os.path.join(RES, f"mipmap-{density}", "ic_launcher.png")
        os.makedirs(os.path.dirname(out), exist_ok=True)
        legacy_icon(px).save(out, optimize=True)
        print(out)
    for density, scale in (("xhdpi", 2), ("xxhdpi", 3)):
        out = os.path.join(RES, f"drawable-{density}", "opentv_banner.png")
        os.makedirs(os.path.dirname(out), exist_ok=True)
        banner(scale).convert("RGB").save(out, optimize=True)
        print(out)
