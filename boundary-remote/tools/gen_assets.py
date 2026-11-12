import os

from PIL import Image, ImageDraw

ROOT = "res"

BG_TOP = (9, 28, 25)
BG_BOTTOM = (6, 15, 14)
LIME = (215, 255, 103)
BAT = (215, 168, 110)


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
    w = max(6, int(size * 0.15))
    d.line([(size * 0.28, size * 0.84), (size * 0.64, size * 0.32)], fill=BAT + (255,), width=w)
    d.line([(size * 0.64, size * 0.32), (size * 0.76, size * 0.22)], fill=(60, 60, 60, 255),
           width=max(5, int(size * 0.1)))
    r = size * 0.09
    d.ellipse([size * 0.74 - r, size * 0.66 - r, size * 0.74 + r, size * 0.66 + r], fill=LIME + (255,))
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
print("boundary remote icons generated")
