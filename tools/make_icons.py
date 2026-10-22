"""Draws NodeNote's app icons: three linked cards on the app's dark background.

    python tools/make_icons.py

Writes the home-screen icons for the browser app (shared/src/wasmJsMain/resources/icons/) and
the desktop installers' icon (shared/icons/). Needs Pillow.
"""
import os
from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
WEB = os.path.join(ROOT, "shared", "src", "wasmJsMain", "resources", "icons")
DESKTOP = os.path.join(ROOT, "shared", "icons")

BACKGROUND = (21, 23, 27)      # canvas background, #15171B
LINE = (154, 163, 175)         # text secondary, #9AA3AF
CARDS = [(91, 156, 255), (229, 178, 91), (83, 194, 124)]  # accent blue, amber, green


def draw(size: int, rounded: bool, glyph: float) -> Image.Image:
    """[glyph] is how much of the icon the drawing fills (maskable icons keep to the middle)."""
    s = 1024
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    if rounded:
        d.rounded_rectangle([0, 0, s - 1, s - 1], radius=int(s * 0.22), fill=BACKGROUND)
    else:
        d.rectangle([0, 0, s, s], fill=BACKGROUND)

    def at(x: float, y: float):  # layout in a unit square, scaled into the glyph area
        margin = (1 - glyph) / 2
        return (s * (margin + x * glyph), s * (margin + y * glyph))

    a, b, c = at(0.22, 0.28), at(0.80, 0.42), at(0.38, 0.80)
    width = int(s * 0.035 * glyph / 0.7)
    for p, q in ((a, b), (a, c), (b, c)):
        d.line([p, q], fill=LINE, width=width)
    w, h = s * 0.30 * glyph, s * 0.19 * glyph
    for (cx, cy), color in zip((a, b, c), CARDS):
        d.rounded_rectangle([cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2], radius=int(h * 0.28),
                            fill=BACKGROUND, outline=color, width=int(width * 1.2))
        r = h * 0.16
        d.ellipse([cx - w / 2 + h * 0.5 - r, cy - r, cx - w / 2 + h * 0.5 + r, cy + r], fill=color)
    return img.resize((size, size), Image.LANCZOS)


os.makedirs(WEB, exist_ok=True)
os.makedirs(DESKTOP, exist_ok=True)
draw(192, rounded=True, glyph=0.78).save(os.path.join(WEB, "icon-192.png"))
draw(512, rounded=True, glyph=0.78).save(os.path.join(WEB, "icon-512.png"))
draw(512, rounded=False, glyph=0.62).save(os.path.join(WEB, "icon-maskable-512.png"))
# iOS rounds the corners itself and wants no transparency.
draw(180, rounded=False, glyph=0.72).convert("RGB").save(os.path.join(WEB, "apple-touch-icon.png"))
big = draw(256, rounded=True, glyph=0.78)
big.save(os.path.join(DESKTOP, "nodenote.ico"), sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
draw(512, rounded=True, glyph=0.78).save(os.path.join(DESKTOP, "nodenote.png"))
print("icons written")
