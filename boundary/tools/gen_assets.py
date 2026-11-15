import os

from PIL import Image, ImageDraw, ImageFont

ROOT = "res"

BG_TOP = (9, 28, 25)
BG_BOTTOM = (6, 15, 14)
LIME = (215, 255, 103)
BALL = (241, 93, 77)
SEAM = (255, 243, 210)


def make_icon(size):
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    for y in range(size):
        t = y / max(1, size - 1)
        c = tuple(int(BG_TOP[i] + (BG_BOTTOM[i] - BG_TOP[i]) * t) for i in range(3)) + (255,)
        d.line([(0, y), (size, y)], fill=c)
    mask = Image.new("L", (size, size), 0)
    md = ImageDraw.Draw(mask)
    m = size * 0.06
    md.rounded_rectangle([m, m, size - m, size - m], radius=size * 0.22, fill=255)
    img.putalpha(mask)
    d = ImageDraw.Draw(img)
    cx, cy = size * 0.5, size * 0.5
    r = size * 0.3
    for i in range(24):
        rr = r * (1 - i / 30.0)
        c = tuple(int(BALL[i] * (1 - i / 26.0) + 120 * (i / 26.0)) for i in range(3))
        d.ellipse([cx - rr, cy - rr, cx + rr, cy + rr], fill=c + (255,))
    d.arc([cx - r, cy - r, cx + r, cy + r], start=290, end=250, fill=SEAM + (255,),
          width=max(3, int(size * 0.05)))
    d.line([(cx - r * 0.75, cy - r * 0.75), (cx + r * 0.75, cy + r * 0.75)], fill=SEAM + (255,),
           width=max(2, int(size * 0.03)))
    return img


sizes = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}
for folder, s in sizes.items():
    d = os.path.join(ROOT, folder)
    os.makedirs(d, exist_ok=True)
    make_icon(s).save(os.path.join(d, "ic_launcher.png"))

W, H = 320, 180
img = Image.new("RGB", (W, H), BG_TOP)
d = ImageDraw.Draw(img)
for y in range(H):
    t = y / H
    c = tuple(int(BG_TOP[i] + (BG_BOTTOM[i] - BG_TOP[i]) * t) for i in range(3))
    d.line([(0, y), (W, y)], fill=c)
d.rectangle([0, 0, W, 34], fill=(19, 45, 37))
mini = make_icon(72)
img.paste(mini, (16, 54), mini)
try:
    font = ImageFont.load_default(size=34)
    small = ImageFont.load_default(size=13)
except TypeError:
    font = ImageFont.load_default()
    small = font
d.text((104, 62), "BOUNDARY", fill=(246, 248, 238), font=font)
d.text((104, 104), "CRICKET. IN YOUR HANDS.", fill=LIME, font=small)
os.makedirs(os.path.join(ROOT, "drawable-xhdpi"), exist_ok=True)
img.save(os.path.join(ROOT, "drawable-xhdpi", "banner.png"))
print("boundary assets generated")
