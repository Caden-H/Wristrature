#!/usr/bin/env python3
"""Builds the README pictures from the raw screenshots.

1. Record the raw screenshots (square, 816 px, grey corners):
       ./gradlew :app:recordRoborazziDebug --tests '*PromoScreenshots*'
2. Run this script from the repository root. It needs Pillow (`pip install pillow`). It turns each screenshot into a round,
   transparent 600 px PNG in docs/screenshots and composes docs/banner.png and the two transparent strips in docs/images.

Optional argument: a path. If given, a 1200x750 JPEG for a portfolio page is written there as well.
"""
import os
import sys

from PIL import Image, ImageDraw, ImageFilter

SHOTS = "docs/screenshots"
NAMES = ["home", "reader-column", "reader-edge", "reader-focus", "speed", "menu", "place", "bookmarks", "details", "storage"]


def round_cut(path):
    im = Image.open(path).convert("RGBA")
    w, h = im.size
    mask = Image.new("L", (w * 4, h * 4), 0)
    ImageDraw.Draw(mask).ellipse([0, 0, w * 4 - 1, h * 4 - 1], fill=255)
    mask = mask.resize((w, h), Image.LANCZOS)
    out = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    out.paste(im, (0, 0), mask)
    return out


def gradient(w, h, top, bottom):
    g = Image.new("RGBA", (w, h))
    d = ImageDraw.Draw(g)
    for y in range(h):
        t = y / h
        d.line([(0, y), (w, y)], fill=tuple(int(top[i] * (1 - t) + bottom[i] * t) for i in range(3)) + (255,))
    return g


def glow(canvas, box, alpha=40, blur=110):
    layer = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    ImageDraw.Draw(layer).ellipse(box, fill=(245, 185, 119, alpha))
    canvas.alpha_composite(layer.filter(ImageFilter.GaussianBlur(blur)))


def place(canvas, img, x, y, d, blur=26, offset=14, alpha=150):
    watch = img.resize((d, d), Image.LANCZOS)
    shadow = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    ImageDraw.Draw(shadow).ellipse([x - 4, y + offset, x + d + 4, y + d + offset + 8], fill=(0, 0, 0, alpha))
    canvas.alpha_composite(shadow.filter(ImageFilter.GaussianBlur(blur)))
    rim = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    ImageDraw.Draw(rim).ellipse([x - 3, y - 3, x + d + 2, y + d + 2], fill=(255, 255, 255, 26))
    canvas.alpha_composite(rim)
    canvas.alpha_composite(watch, (x, y))


def main():
    circles = {n: round_cut(f"{SHOTS}/{n}.png") for n in NAMES}
    for n, img in circles.items():
        img.resize((600, 600), Image.LANCZOS).save(f"{SHOTS}/{n}.png", optimize=True)

    # Banner: four watches in a row, the middle two larger.
    w, h = 1600, 640
    banner = gradient(w, h, (20, 24, 22), (34, 42, 36))
    glow(banner, [w / 2 - 520, h / 2 - 260, w / 2 + 520, h / 2 + 260])
    gap = 36
    big = int((w - 120 - 3 * gap) / 3.6)
    sizes = [int(big * 0.8), big, big, int(big * 0.8)]
    x = (w - sum(sizes) - 3 * gap) // 2
    for d, name in zip(sizes, ["reader-edge", "home", "reader-column", "speed"]):
        place(banner, circles[name], x, (h - d) // 2 - 6, d)
        x += d + gap
    banner.convert("RGB").save("docs/banner.png", optimize=True)

    # Two strips of four watches on a transparent background.
    os.makedirs("docs/images", exist_ok=True)
    strips = {
        "reading": ["reader-column", "reader-edge", "reader-focus", "speed"],
        "app": ["home", "menu", "bookmarks", "details"],
    }
    for file, names in strips.items():
        d, gap = 380, 30
        strip = Image.new("RGBA", (4 * d + 3 * gap, d), (0, 0, 0, 0))
        for i, name in enumerate(names):
            strip.alpha_composite(circles[name].resize((d, d), Image.LANCZOS), (i * (d + gap), 0))
        strip.save(f"docs/images/{file}.png", optimize=True)

    if len(sys.argv) > 1:
        w, h = 1200, 750
        site = gradient(w, h, (23, 29, 25), (36, 46, 38))
        glow(site, [w / 2 - 380, h / 2 - 300, w / 2 + 380, h / 2 + 300], alpha=45)
        place(site, circles["reader-edge"], 70, 215, 380, blur=30, offset=18)
        place(site, circles["speed"], 750, 215, 380, blur=30, offset=18)
        place(site, circles["reader-column"], 340, 150, 520, blur=36, offset=22, alpha=170)
        site.convert("RGB").save(sys.argv[1], quality=90, optimize=True)


if __name__ == "__main__":
    main()
