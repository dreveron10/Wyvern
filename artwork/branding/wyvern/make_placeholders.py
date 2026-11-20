"""Generate Wyvern's placeholder icon and splash screen.

These replace the OpenRocket logo until Wyvern has real artwork.
Run from the repository root:

    python3 artwork/branding/wyvern/make_placeholders.py

Needs Pillow and the DejaVu fonts. Writes into swing/src/main/resources/pix/.
The splash keeps OpenRocket's copyright and GPL notice, which the license requires.
The version line is drawn by Splash.java at x=617, y=150 (right edge of the
baseline), so that area is left empty here.
"""
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[3]
PIX = ROOT / "swing" / "src" / "main" / "resources" / "pix"
FONT_BOLD = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
FONT_REG = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"

BG = (16, 19, 26, 255)
BADGE = (28, 34, 46, 255)
ACCENT = (242, 163, 58, 255)   # amber
TEXT = (235, 238, 243, 255)
QUIET = (154, 164, 178, 255)


def draw_icon(size):
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    radius = max(2, size // 5)
    d.rounded_rectangle([0, 0, size - 1, size - 1], radius=radius, fill=BG)
    font = ImageFont.truetype(FONT_BOLD, int(size * 0.68))
    d.text((size / 2, size / 2 + size * 0.02), "W", font=font, fill=ACCENT, anchor="mm")
    return img


def draw_splash(scale):
    w, h = 662 * scale, 336 * scale
    s = lambda v: int(v * scale)
    img = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([0, 0, w - 1, h - 1], radius=s(24), fill=BG)

    # Badge on the left
    d.ellipse([s(40), s(68), s(240), s(268)], fill=BADGE, outline=ACCENT, width=s(4))
    d.text((s(140), s(172)), "W", font=ImageFont.truetype(FONT_BOLD, s(120)), fill=ACCENT, anchor="mm")

    right = s(617)
    d.text((right, s(118)), "Wyvern", font=ImageFont.truetype(FONT_BOLD, s(60)), fill=TEXT, anchor="rs")
    # y=150: version line drawn at runtime by Splash.java
    d.text((right, s(178)), "Rocket flight simulator", font=ImageFont.truetype(FONT_REG, s(15)),
           fill=QUIET, anchor="rs")

    small = ImageFont.truetype(FONT_REG, s(11))
    lines = [
        "Based on OpenRocket. Copyright 2007-2026 Sampo Niskanen and others.",
        "This program comes with ABSOLUTELY NO WARRANTY. Wyvern is free software,",
        "you can redistribute it according to the GNU GPL version 3.",
        "See Help → License for details.",
    ]
    for i, line in enumerate(lines):
        d.text((right, s(252 + 18 * i)), line, font=small, fill=QUIET, anchor="rs")
    return img


def main():
    for size in (16, 48, 64, 128, 256):
        draw_icon(size).save(PIX / "icon" / f"icon-{size:03d}.png")
    draw_splash(1).save(PIX / "splashscreen.png")
    draw_splash(2).save(PIX / "splashscreen@2x.png")


if __name__ == "__main__":
    main()
