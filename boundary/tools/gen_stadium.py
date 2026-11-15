#!/usr/bin/env python3
"""Generate the procedural night-stadium backdrop for Boundary v3.1.

Outputs res/drawable-nodpi/bg_stadium.png (1024x512, POT). This is a fully
procedural floodlit stadium bowl -- sky gradient, four floodlight towers with
glow halos, multi-tier seating arcs with crowd shimmer, and a dark turf strip.
No Figma screenshot crops are baked into the game.
"""
import os
import math
import random

from PIL import Image, ImageDraw, ImageFilter

W, H = 1024, 512
OUT = os.path.join(os.path.dirname(__file__), "..", "res", "drawable-nodpi", "bg_stadium.png")

def lerp(a, b, t):
    return a + (b - a) * t

def main():
    random.seed(31)
    img = Image.new("RGB", (W, H))
    px = img.load()

    # --- sky: deep green-black up top into a slightly navy glow at the horizon
    top = (6, 15, 14)        # #060F0E
    horizon = (38, 69, 105)  # floodlit haze
    horizon_y = int(H * 0.52)
    for y in range(H):
        if y < horizon_y:
            t = y / max(1, horizon_y)
            t = t ** 1.6
            c = tuple(int(lerp(top[i], horizon[i], t)) for i in range(3))
        else:
            c = horizon
        for x in range(W):
            px[x, y] = c

    draw = ImageDraw.Draw(img, "RGBA")

    # --- two curved seating tiers behind the bowler's end (upper backdrop)
    def tier(y_top, y_bot, col, alpha):
        # concentric elliptical arcs, upper half only
        steps = 64
        pts = []
        for i in range(steps + 1):
            t = i / steps
            x = t * W
            y = y_bot - (y_bot - y_top) * math.sin(math.pi * t) ** 0.35
            pts.append((x, y))
        draw.polygon(pts + [(W, y_bot), (0, y_bot)], fill=col + (alpha,))

    tier(int(H * 0.20), int(H * 0.42), (24, 46, 42), 235)   # upper tier
    tier(int(H * 0.30), int(H * 0.50), (30, 58, 52), 255)   # lower tier

    # crowd shimmer: tiny dots across the tiers
    for _ in range(5200):
        x = random.uniform(0, W)
        yy = random.uniform(H * 0.21, H * 0.49)
        v = random.randint(70, 200)
        col = (v, v, v * 92 // 100) if random.random() < 0.5 else (v, v, int(v * 0.7))
        draw.point((x, yy), fill=col + (random.randint(30, 110),))

    # roofline over the top tier
    steps = 64
    roof = []
    for i in range(steps + 1):
        t = i / steps
        roof.append((t * W, H * 0.20 - (H * 0.075) * math.sin(math.pi * t) ** 0.5))
    draw.line(roof, fill=(16, 32, 30, 255), width=5)

    # --- four floodlight towers with glow halos
    for fx, fy in [(0.10, 0.115), (0.36, 0.065), (0.64, 0.065), (0.90, 0.115)]:
        cx, cy = fx * W, fy * H
        # mast
        draw.line([(cx, cy), (cx, H * 0.34)], fill=(14, 28, 26, 255), width=4)
        # lamp bank
        draw.rounded_rectangle([cx - 26, cy - 12, cx + 26, cy + 12], 4, fill=(226, 238, 214, 255))
        for r in range(5):
            for c in range(9):
                lx = cx - 22 + c * 5.5
                ly = cy - 8 + r * 4.5
                draw.point((lx, ly), fill=(255, 252, 224, 255))
        # halo
        halo = Image.new("L", (W, H), 0)
        hd = ImageDraw.Draw(halo)
        hd.ellipse([cx - 60, cy - 46, cx + 60, cy + 46], fill=90)
        halo = halo.filter(ImageFilter.GaussianBlur(26))
        glow = Image.new("RGB", (W, H), (168, 204, 130))
        img.paste(glow, (0, 0), halo.point(lambda v: min(95, v)))

    draw = ImageDraw.Draw(img, "RGBA")

    # --- outfield turf filling the lower half, with mowed radial stripes
    for y in range(horizon_y, H):
        t = (y - horizon_y) / max(1, H - horizon_y)
        base = (int(lerp(20, 26, t)), int(lerp(64, 88, t)), int(lerp(30, 42, t)))
        for x in range(W):
            # stripes fan out from the centre
            ang = math.atan2(y - horizon_y * 0.92, (x - W / 2) / 1.6)
            stripe = 0.5 + 0.5 * math.sin(ang * 14)
            k = 0.85 + 0.3 * stripe
            px[x, y] = tuple(min(255, int(base[i] * k)) for i in range(3))

    # faint boundary rope arc
    rope = []
    for i in range(steps + 1):
        t = i / steps
        rope.append((t * W, H * 0.60 + (H * 0.03) * math.sin(math.pi * t)))
    draw.line(rope, fill=(214, 226, 208, 130), width=3)

    # subtle vignette so HUD bars read well
    vin = Image.new("L", (W, H), 0)
    vd = ImageDraw.Draw(vin)
    vd.rectangle([0, 0, W, H], fill=46)
    vd.ellipse([-W * 0.15, -H * 0.35, W * 1.15, H * 1.25], fill=0)
    vin = vin.filter(ImageFilter.GaussianBlur(60))
    dark = Image.new("RGB", (W, H), (2, 6, 6))
    img.paste(dark, (0, 0), vin)

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    img.save(OUT, "PNG")
    print("wrote", OUT, os.path.getsize(OUT), "bytes")

if __name__ == "__main__":
    main()
