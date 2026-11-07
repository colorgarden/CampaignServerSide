"""Draws the mod icon and writes assets/icon.png plus a multi-size assets/icon.ico.

The same artwork also exists as assets/icon.svg for editing; this script redraws that layout with
Pillow so the build needs neither cairo nor inkscape on the machine. Everything is drawn at 4x and
downsampled, which is what keeps the edges clean without a vector rasteriser.
"""
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
OUT_PNG = ROOT / "assets" / "icon.png"
OUT_ICO = ROOT / "assets" / "icon.ico"

SS = 4          # supersampling factor
SIZE = 256      # final pixel size
W = SIZE * SS
ICO_SIZES = [(16, 16), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)]

# palette shared with the SVG
BG_TOP, BG_BOTTOM = (22, 33, 46), (11, 16, 22)
EDGE = (36, 52, 71)
RING_BACK = (29, 43, 58)
RING_FRONT = (142, 202, 230)
DISC = (16, 26, 36)
DISC_EDGE = (45, 66, 87)
FACET = (27, 43, 58)
FACET_EDGE = (51, 80, 107)
CORE_LIGHT, CORE_DARK = (255, 217, 122), (240, 169, 60)
PLATE = (138, 106, 36)
BADGE_BG = (18, 48, 31)
BADGE = (71, 198, 106)
RAIL = (51, 80, 107)


def s(v):
    """Scales a viewBox coordinate to the supersampled canvas."""
    return int(round(v * SS))


def vertical_gradient(size, top, bottom):
    img = Image.new("RGB", size, top)
    draw = ImageDraw.Draw(img)
    for y in range(size[1]):
        t = y / max(1, size[1] - 1)
        colour = tuple(int(round(a + (b - a) * t)) for a, b in zip(top, bottom))
        draw.line([(0, y), (size[0], y)], fill=colour)
    return img


def main():
    canvas = Image.new("RGBA", (W, W), (0, 0, 0, 0))

    # slab background with a vertical gradient, clipped to the rounded rect
    slab = Image.new("L", (W, W), 0)
    ImageDraw.Draw(slab).rounded_rectangle([s(6), s(6), s(250), s(250)], radius=s(46), fill=255)
    canvas.paste(vertical_gradient((W, W), BG_TOP, BG_BOTTOM).convert("RGBA"), (0, 0), slab)

    d = ImageDraw.Draw(canvas)
    d.rounded_rectangle([s(6), s(6), s(250), s(250)], radius=s(46), outline=EDGE, width=s(4))

    # orbit ring: dark base plus a bright arc
    ring_box = [s(128 - 72), s(118 - 72), s(128 + 72), s(118 + 72)]
    d.ellipse(ring_box, outline=RING_BACK, width=s(22))
    d.arc(ring_box, start=-150, end=110, fill=RING_FRONT, width=s(10))

    # sector disc and its hexagonal facet
    disc_box = [s(128 - 48), s(118 - 48), s(128 + 48), s(118 + 48)]
    d.ellipse(disc_box, fill=DISC, outline=DISC_EDGE, width=s(4))

    hexagon = [(s(128), s(80)), (s(164), s(99)), (s(164), s(137)),
               (s(128), s(156)), (s(92), s(137)), (s(92), s(99))]
    d.polygon(hexagon, fill=FACET)
    d.line(hexagon + [hexagon[0]], fill=FACET_EDGE, width=s(4), joint="curve")

    # the core: a bright box on a dark inset
    core_mask = Image.new("L", (W, W), 0)
    ImageDraw.Draw(core_mask).rounded_rectangle([s(106), s(96), s(150), s(140)], radius=s(8), fill=255)
    canvas.paste(vertical_gradient((W, W), CORE_LIGHT, CORE_DARK).convert("RGBA"), (0, 0), core_mask)

    inset = Image.new("RGBA", (W, W), (0, 0, 0, 0))
    ImageDraw.Draw(inset).rounded_rectangle([s(114), s(104), s(142), s(132)], radius=s(5),
                                            fill=(27, 18, 6, 90))
    canvas.alpha_composite(inset)

    d = ImageDraw.Draw(canvas)
    d.rounded_rectangle([s(118), s(108), s(138), s(128)], radius=s(4), fill=CORE_LIGHT)
    d.rounded_rectangle([s(100), s(90), s(156), s(98)], radius=s(4), fill=PLATE + (140,))

    # the vote tick
    d.ellipse([s(188 - 34), s(182 - 34), s(188 + 34), s(182 + 34)], fill=BADGE_BG, outline=BADGE, width=s(6))
    d.line([(s(172), s(182)), (s(183), s(194)), (s(205), s(168))], fill=BADGE, width=s(10), joint="curve")

    # server rails
    for y in (150, 168, 186):
        d.rounded_rectangle([s(34), s(y), s(44), s(y + 10)], radius=s(2), fill=RAIL)

    icon = canvas.resize((SIZE, SIZE), Image.LANCZOS)
    icon.save(OUT_PNG)
    icon.save(OUT_ICO, format="ICO", sizes=ICO_SIZES)

    print("png: {} ({} bytes, {}x{})".format(OUT_PNG, OUT_PNG.stat().st_size, *icon.size))
    print("ico: {} ({} bytes, sizes {})".format(OUT_ICO, OUT_ICO.stat().st_size,
                                                [w for w, _ in ICO_SIZES]))


if __name__ == "__main__":
    main()
