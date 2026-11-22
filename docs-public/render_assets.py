"""Render the Lantern icon and social preview PNGs (geometry from the B2 icon, 108-unit canvas).

Usage: python docs-public/render_assets.py   (needs Pillow)
"""
import os

from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
AMBER = (0xF5, 0xB5, 0x44, 255)
PALE = (0xFF, 0xF1, 0xD0, 255)
BLACK = (0, 0, 0, 255)
SS = 4  # supersampling factor


def bezier(p0, p1, p2, p3, n=24):
    pts = []
    for i in range(n + 1):
        t = i / n
        u = 1 - t
        pts.append((
            u**3 * p0[0] + 3 * u * u * t * p1[0] + 3 * u * t * t * p2[0] + t**3 * p3[0],
            u**3 * p0[1] + 3 * u * u * t * p1[1] + 3 * u * t * t * p2[1] + t**3 * p3[1],
        ))
    return pts


def flame_points():
    # M54 46 c4 6 6 10 6 14 a6 6 0 0 1 -12 0 c0-4 2-8 6-14 z
    pts = bezier((54, 46), (58, 52), (60, 56), (60, 60))
    # Arc: semicircle centre (54,60) r6 from (60,60) to (48,60) through the bottom (sweep=1).
    import math
    for i in range(1, 25):
        a = math.pi * i / 24
        pts.append((54 + 6 * math.cos(a), 60 + 6 * math.sin(a)))
    pts += bezier((48, 60), (48, 56), (50, 52), (54, 46))
    return pts


def render_icon(size):
    scale = size * SS / 108
    big = size * SS
    img = Image.new("RGBA", (big, big), BLACK)
    d = ImageDraw.Draw(img)

    def rr(x, y, w, h, r, fill):
        d.rounded_rectangle([x * scale, y * scale, (x + w) * scale - 1, (y + h) * scale - 1],
                            radius=r * scale, fill=fill)

    rr(38, 32, 32, 48, 12, AMBER)
    rr(44, 38, 20, 36, 7, BLACK)  # glass cut-out
    rr(47, 24, 14, 5, 2.5, AMBER)  # cap
    d.polygon([(x * scale, y * scale) for x, y in flame_points()], fill=PALE)
    return img.resize((size, size), Image.LANCZOS)


def font(names, size):
    for n in names:
        try:
            return ImageFont.truetype(n, size)
        except OSError:
            continue
    return ImageFont.load_default()


def render_social():
    w, h = 1280, 640
    img = Image.new("RGBA", (w, h), BLACK)
    icon = render_icon(400)
    img.paste(icon, (140, 120))
    d = ImageDraw.Draw(img)
    title = font(["C:/Windows/Fonts/segoeuib.ttf", "segoeuib.ttf"], 120)
    sub = font(["C:/Windows/Fonts/segoeui.ttf", "segoeui.ttf"], 40)
    d.text((600, 215), "Lantern", font=title, fill=AMBER)
    d.text((606, 365), "An unofficial reader for AO3", font=sub, fill=PALE)
    return img


if __name__ == "__main__":
    render_icon(512).convert("RGB").save(os.path.join(HERE, "icon-512.png"), optimize=True)
    render_social().convert("RGB").save(os.path.join(HERE, "social-preview.png"), optimize=True)
