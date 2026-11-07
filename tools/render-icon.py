"""Draws the mod icon and writes assets/icon.png plus a multi-size assets/icon.ico.

The artwork also exists as assets/icon.svg for editing; this script redraws the same layout with
Pillow so the build needs neither cairo nor inkscape. Everything is drawn at 8x and downsampled,
which is what keeps the curves clean without a vector rasteriser.
"""
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
OUT_PNG = ROOT / "assets" / "icon.png"
OUT_ICO = ROOT / "assets" / "icon.ico"

SS = 8          # supersampling factor
SIZE = 256      # final pixel size
W = SIZE * SS
ICO_SIZES = [(16, 16), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)]

SLAB_TOP, SLAB_BOTTOM = (27, 44, 61), (11, 20, 29)
SLAB_EDGE = (43, 66, 88)
HEX_TOP, HEX_BOTTOM = (29, 51, 70), (16, 29, 40)
HEX_EDGE = (58, 93, 125)
HEX_INNER = (37, 64, 90)
ARC = (142, 202, 230)
CORE_TOP, CORE_BOTTOM = (255, 233, 176), (231, 154, 43)
CORE_LIP = (185, 129, 42)
CORE_HOLLOW = (58, 38, 9)
CORE_GLYPH = (255, 233, 176)
BADGE_RING = (78, 203, 114)
BADGE_FILL = (14, 51, 32)


def s(v):
    return int(round(v * SS))


def vgradient(size, top, bottom):
    img = Image.new("RGB", size, top)
    draw = ImageDraw.Draw(img)
    for y in range(size[1]):
        t = y / max(1, size[1] - 1)
        draw.line([(0, y), (size[0], y)],
                  fill=tuple(int(round(a + (b - a) * t)) for a, b in zip(top, bottom)))
    return img


def polygon_mask(points, size=(W, W)):
    mask = Image.new("L", size, 0)
    ImageDraw.Draw(mask).polygon(points, fill=255)
    return mask


def rounded_mask(box, radius, size=(W, W)):
    mask = Image.new("L", size, 0)
    ImageDraw.Draw(mask).rounded_rectangle(box, radius=radius, fill=255)
    return mask


def main():
    canvas = Image.new("RGBA", (W, W), (0, 0, 0, 0))

    # slab
    slab_box = [s(10), s(10), s(246), s(246)]
    canvas.paste(vgradient((W, W), SLAB_TOP, SLAB_BOTTOM).convert("RGBA"), (0, 0),
                 rounded_mask(slab_box, s(54)))
    d = ImageDraw.Draw(canvas)
    d.rounded_rectangle(slab_box, radius=s(54), outline=SLAB_EDGE, width=s(3))

    # sector hexagon
    hexagon = [(s(128), s(62)), (s(190), s(98)), (s(190), s(158)),
               (s(128), s(194)), (s(66), s(158)), (s(66), s(98))]
    canvas.paste(vgradient((W, W), HEX_TOP, HEX_BOTTOM).convert("RGBA"), (0, 0),
                 polygon_mask(hexagon))
    d = ImageDraw.Draw(canvas)
    d.line(hexagon + [hexagon[0]], fill=HEX_EDGE, width=s(4), joint="curve")

    inner_hexagon = [(s(128), s(76)), (s(178), s(104)), (s(178), s(152)),
                     (s(128), s(180)), (s(78), s(152)), (s(78), s(104))]
    d.line(inner_hexagon + [inner_hexagon[0]], fill=HEX_INNER, width=s(4), joint="curve")

    # orbit: a dark full circle with two bright arcs, so the emblem reads as a live sector
    d = ImageDraw.Draw(canvas)
    orbit = [s(128 - 100), s(128 - 100), s(128 + 100), s(128 + 100)]
    d.ellipse(orbit, outline=(31, 47, 64), width=s(10))
    d.arc(orbit, start=205, end=320, fill=ARC, width=s(10))
    d.arc(orbit, start=25, end=140, fill=ARC, width=s(10))

    # core: a beveled block, dark inset, bright centre - the storage silhouette
    body = rounded_mask([s(96), s(96), s(160), s(160)], s(11))
    canvas.paste(vgradient((W, W), CORE_TOP, CORE_BOTTOM).convert("RGBA"), (0, 0), body)

    bevel = Image.new("RGBA", (W, W), (0, 0, 0, 0))
    ImageDraw.Draw(bevel).rounded_rectangle([s(100), s(100), s(156), s(107)], radius=s(3),
                                           fill=(255, 245, 210, 70))
    canvas.alpha_composite(bevel)

    hollow = Image.new("RGBA", (W, W), (0, 0, 0, 0))
    ImageDraw.Draw(hollow).rounded_rectangle([s(106), s(106), s(150), s(150)], radius=s(6),
                                             fill=CORE_HOLLOW + (150,))
    canvas.alpha_composite(hollow)

    d = ImageDraw.Draw(canvas)
    d.rounded_rectangle([s(117), s(117), s(139), s(139)], radius=s(4), fill=CORE_GLYPH)

    # vote badge
    badge = [s(190 - 33), s(186 - 33), s(190 + 33), s(186 + 33)]
    d.ellipse(badge, fill=BADGE_FILL, outline=BADGE_RING, width=s(5))
    d.line([(s(175), s(186)), (s(185), s(197)), (s(205), s(173))],
           fill=BADGE_RING, width=s(9), joint="curve")

    icon = canvas.resize((SIZE, SIZE), Image.LANCZOS)
    icon.save(OUT_PNG)
    icon.save(OUT_ICO, format="ICO", sizes=ICO_SIZES)

    print("png: {} ({} bytes, {}x{})".format(OUT_PNG, OUT_PNG.stat().st_size, *icon.size))
    print("ico: {} ({} bytes, sizes {})".format(OUT_ICO, OUT_ICO.stat().st_size,
                                                [w for w, _ in ICO_SIZES]))


if __name__ == "__main__":
    main()
