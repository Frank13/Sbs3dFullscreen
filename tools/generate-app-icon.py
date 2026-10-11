# Draws the app icon (pale rounded tile + SBS monitor glyph) and writes both
# src/commonMain/composeResources/drawable/icon.png (256x256, the window/taskbar icon of the running app)
# and icons/icon.ico (all Windows sizes, used by the installers and pinned shortcuts), plus
# icons/icon-square.png (512x512, same tile with square corners) for the Store/Start tiles: those get
# their own corner mask, and transparent corners showed up there as black wedges.
# Geometry is in 256-unit coordinates; GLYPH_SCALE enlarges the monitor around the tile center so it
# fills the tile like other apps' taskbar icons instead of floating in a wide pale margin.
# Every size is supersampled then downscaled, so small frames stay smooth.
# Requires Pillow (pip install pillow). Re-run ./tools/generate-msix-assets.ps1 afterwards.
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
PNG_PATH = ROOT / "src/commonMain/composeResources/drawable/icon.png"
ICO_PATH = ROOT / "icons/icon.ico"
SQUARE_PNG_PATH = ROOT / "icons/icon-square.png"

SS = 8  # supersampling factor
GLYPH_SCALE = 1.2
GLYPH_CENTER = (127.5, 131.0)  # center of the monitor glyph's bounding box, before scaling

TILE = (232, 241, 242, 255)
TILE_RADIUS = 56
FRAME = (16, 32, 42, 255)
LEFT_PANEL = (61, 214, 198, 255)
RIGHT_PANEL = (255, 138, 91, 255)
MOUNTAIN = (255, 255, 255, 255)

# Right-eye mountains are shifted 5 units less than the panel offset (84) - a small disparity.
MOUNTAINS = [(53.5, 137.5), (74, 100), (84, 115), (99.5, 88), (121, 137.5)]
RIGHT_MOUNTAIN_SHIFT = 79


def g(x, y):
    """Glyph coordinates (256 units) -> supersampled canvas pixels, scaled around the tile center."""
    cx, cy = GLYPH_CENTER
    return ((128 + (x - cx) * GLYPH_SCALE) * SS, (128 + (y - cy) * GLYPH_SCALE) * SS)


def g_rect(x0, y0, x1, y1):
    return [*g(x0, y0), *g(x1, y1)]


def r(units):
    return units * GLYPH_SCALE * SS


def render_master(rounded=True):
    size = 256 * SS
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([0, 0, size - 1, size - 1], radius=TILE_RADIUS * SS if rounded else 0, fill=TILE)

    # Stand neck (trapezoid) and base, drawn first so the screen frame overlaps the neck's top.
    d.polygon([g(111, 165), g(144.5, 165), g(152.5, 207), g(103, 207)], fill=FRAME)
    d.rounded_rectangle(g_rect(86, 205, 169.5, 215.5), radius=r(5), fill=FRAME)

    # Screen frame and its two eye panels.
    d.rounded_rectangle(g_rect(34.5, 46.5, 221.5, 170.5), radius=r(9), fill=FRAME)
    d.rounded_rectangle(g_rect(46.5, 58.5, 125, 157.5), radius=r(4), fill=LEFT_PANEL)
    d.rounded_rectangle(g_rect(130.5, 58.5, 209, 157.5), radius=r(4), fill=RIGHT_PANEL)

    for shift in (0, RIGHT_MOUNTAIN_SHIFT):
        d.polygon([g(x + shift, y) for x, y in MOUNTAINS], fill=MOUNTAIN)
    return img


def main():
    master = render_master()
    PNG_PATH.parent.mkdir(parents=True, exist_ok=True)
    master.resize((256, 256), Image.LANCZOS).save(PNG_PATH)

    sizes = [16, 20, 24, 32, 40, 48, 64, 128, 256]
    frames = [master.resize((s, s), Image.LANCZOS) for s in sizes]
    ICO_PATH.parent.mkdir(parents=True, exist_ok=True)
    frames[-1].save(ICO_PATH, format="ICO", sizes=[(s, s) for s in sizes], append_images=frames[:-1])

    render_master(rounded=False).resize((512, 512), Image.LANCZOS).save(SQUARE_PNG_PATH)
    print(f"Wrote {PNG_PATH}\nWrote {ICO_PATH}\nWrote {SQUARE_PNG_PATH}")


if __name__ == "__main__":
    main()
