#!/usr/bin/env python3
"""Erzeugt die Smartys-IPTV-Icons/Banner: grosses goldenes Play-Dreieck + SMARTYS-Schrift.
Design-Ziel: auch in der kleinen Fire-TV-Kachel sofort erkennbar (hoher Kontrast, grosse Formen).
"""
from PIL import Image, ImageDraw, ImageFont
import math

GOLD = (255, 201, 77)        # #FFC94D
GOLD_DARK = (224, 168, 40)
NAVY = (13, 20, 32)          # sehr dunkles Blau
NAVY_BRIGHT = (24, 34, 58)   # helleres Navy für Hintergrund-Verlauf
WHITE = (240, 244, 252)
FONT_BOLD = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"


def draw_play_triangle(draw, cx, cy, size, color):
    """Nach rechts zeigendes, gefuelltes Play-Dreieck, zentriert auf (cx, cy)."""
    h = size
    w = size * 0.86
    x0 = cx - w * 0.35
    x1 = cx + w * 0.65
    y_top = cy - h / 2
    y_bot = cy + h / 2
    draw.polygon([(x0, y_top), (x1, cy), (x0, y_bot)], fill=color)


def make_icon(side=512):
    """Quadratisches Icon: Navy-Verlauf + grosses goldenes Dreieck + 'SMARTYS'."""
    img = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    # Verlauf Navy_BRIGHT -> Navy (oben -> unten)
    grad = Image.new("RGB", (side, side))
    gp = grad.load()
    for y in range(side):
        t = y / side
        r = int(NAVY_BRIGHT[0] + (NAVY[0] - NAVY_BRIGHT[0]) * t)
        g = int(NAVY_BRIGHT[1] + (NAVY[1] - NAVY_BRIGHT[1]) * t)
        b = int(NAVY_BRIGHT[2] + (NAVY[2] - NAVY_BRIGHT[2]) * t)
        for x in range(side):
            gp[x, y] = (r, g, b)
    # Abgerundeter Hintergrund
    mask = Image.new("L", (side, side), 0)
    md = ImageDraw.Draw(mask)
    md.rounded_rectangle([0, 0, side - 1, side - 1], radius=side // 6, fill=255)
    img.paste(grad, (0, 0), mask)

    d = ImageDraw.Draw(img)

    # Grosses Play-Dreieck (zentriert, leicht nach oben)
    tri_size = int(side * 0.42)
    draw_play_triangle(d, side // 2, int(side * 0.44), tri_size, GOLD)

    # "SMARTYS" darunter, gross + weiss
    font_size = int(side * 0.16)
    font = ImageFont.truetype(FONT_BOLD, font_size)
    text = "SMARTYS"
    bbox = d.textbbox((0, 0), text, font=font)
    tw = bbox[2] - bbox[0]
    th = bbox[3] - bbox[1]
    tx = (side - tw) / 2 - bbox[0]
    ty = int(side * 0.68) - bbox[1]
    d.text((tx, ty), text, font=font, fill=WHITE)

    return img


def make_banner(w=320, h=180):
    """Leanback-Banner (Querformat): Navy + grosses Dreieck links + 'SMARTYS' rechts."""
    img = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    grad = Image.new("RGB", (w, h))
    gp = grad.load()
    for y in range(h):
        t = y / h
        r = int(NAVY_BRIGHT[0] + (NAVY[0] - NAVY_BRIGHT[0]) * t)
        g = int(NAVY_BRIGHT[1] + (NAVY[1] - NAVY_BRIGHT[1]) * t)
        b = int(NAVY_BRIGHT[2] + (NAVY[2] - NAVY_BRIGHT[2]) * t)
        for x in range(w):
            gp[x, y] = (r, g, b)
    img.paste(grad, (0, 0))

    d = ImageDraw.Draw(img)

    # Play-Dreieck links (gross, zentriert in linker Haelfte)
    tri_size = int(h * 0.72)
    draw_play_triangle(d, int(w * 0.28), h // 2, tri_size, GOLD)

    # "SMARTYS" rechts vom Dreieck
    font_size = int(h * 0.34)
    font = ImageFont.truetype(FONT_BOLD, font_size)
    text = "SMARTYS"
    bbox = d.textbbox((0, 0), text, font=font)
    tw = bbox[2] - bbox[0]
    th = bbox[3] - bbox[1]
    tx = int(w * 0.46)
    ty = (h - th) / 2 - bbox[1]
    d.text((tx, ty), text, font=font, fill=WHITE)

    return img


if __name__ == "__main__":
    import os
    res = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                       "..", "app", "src", "main", "res")

    # Banner: Fire-TV Leanback braucht 320x180 (xhdpi). Wir legen es in mipmap/ (density-unabhaengig)
    banner = make_banner(320, 180)
    banner.save(os.path.join(res, "mipmap", "banner.png"))
    print("banner.png -> 320x180")

    # Quadratisches Icon (512) fuer die App-Listen/Raster-Ansicht
    icon = make_icon(512)
    icon.save(os.path.join(res, "mipmap", "ic_launcher.png"))
    print("ic_launcher.png -> 512x512")

    # Adaptive-Icon-Layer (432) fuer API26+: bg = Verlauf, fg = Dreieck + Text
    side = 432
    bg = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    grad = Image.new("RGB", (side, side))
    gp = grad.load()
    for y in range(side):
        t = y / side
        r = int(NAVY_BRIGHT[0] + (NAVY[0] - NAVY_BRIGHT[0]) * t)
        g = int(NAVY_BRIGHT[1] + (NAVY[1] - NAVY_BRIGHT[1]) * t)
        b = int(NAVY_BRIGHT[2] + (NAVY[2] - NAVY_BRIGHT[2]) * t)
        for x in range(side):
            gp[x, y] = (r, g, b)
    bg.paste(grad, (0, 0))
    bg.save(os.path.join(res, "mipmap", "adaptive_bg.png"))
    print("adaptive_bg.png -> 432x432")

    # Foreground: transparent, Inhalt im Safe-Zone (mittlere 66%)
    fg = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    d = ImageDraw.Draw(fg)
    safe = int(side * 0.66)
    ox = (side - safe) // 2
    oy = (side - safe) // 2
    # Dreieck im oberen Safe-Bereich
    tri_size = int(safe * 0.46)
    draw_play_triangle(d, side // 2, oy + int(safe * 0.34), tri_size, GOLD)
    # Text unten
    font_size = int(safe * 0.20)
    font = ImageFont.truetype(FONT_BOLD, font_size)
    text = "SMARTYS"
    bbox = d.textbbox((0, 0), text, font=font)
    tw = bbox[2] - bbox[0]
    tx = (side - tw) / 2 - bbox[0]
    ty = oy + int(safe * 0.62) - bbox[1]
    d.text((tx, ty), text, font=font, fill=WHITE)
    fg.save(os.path.join(res, "mipmap", "adaptive_fg.png"))
    print("adaptive_fg.png -> 432x432")

    print("Alle Assets geschrieben.")
