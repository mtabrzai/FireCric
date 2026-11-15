#!/usr/bin/env python3
"""Extract the stadium backdrop (bg_3d.png) from the Boundary design visual.

The design frame `design/boundary-face-the-delivery.svg` embeds the stadium
render as a base64 PNG (the background pattern). This script extracts it,
resizes to a power-of-two 1024x512 (GLES2-safe with GL_REPEAT) and writes
`res/drawable/bg_3d.png`.

Usage (from the boundary/ project root, Pillow required):
    python tools/gen_bg3d.py [path-to-svg]
"""

import base64
import io
import os
import re
import sys

from PIL import Image

DEFAULT_SVG = os.path.join("..", "design", "boundary-face-the-delivery.svg")
OUT = os.path.join("res", "drawable", "bg_3d.png")


def main():
    svg_path = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_SVG
    with open(svg_path, "r", encoding="utf-8") as f:
        svg = f.read()
    m = re.search(r"data:image/png;base64,([A-Za-z0-9+/=]+)", svg)
    if not m:
        raise SystemExit("no embedded PNG found in " + svg_path)
    img = Image.open(io.BytesIO(base64.b64decode(m.group(1))))
    img = img.convert("RGB").resize((1024, 512), Image.LANCZOS)
    # palette + dither keeps the file small with no visible banding on gradients
    img = img.quantize(colors=256, method=Image.MEDIANCUT, dither=Image.FLOYDSTEINBERG)
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    img.save(OUT, optimize=True)
    print("wrote", OUT, img.size)


if __name__ == "__main__":
    main()
