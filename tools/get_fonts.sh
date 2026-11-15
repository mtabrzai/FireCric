#!/bin/bash
# Downloads the Inter typeface (SIL Open Font License) into both apps'
# assets/fonts/. Run once after cloning, before tools/build-apk.sh.
# Fonts are binary assets: the repo generates/downloads them, it does not
# commit them (same policy as gen_assets.py / gen_stadium.py).
set -euo pipefail

URL=https://github.com/rsms/inter/releases/download/v4.1/Inter-4.1.zip
SHA256=9883fdd4a49d4fb66bd8177ba6625ef9a64aa45899767dde3d36aa425756b11e
FONTS="Inter-Regular Inter-Medium Inter-SemiBold Inter-Bold"

cd "$(dirname "$0")/.."
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

echo "== downloading Inter 4.1 (SIL OFL) =="
curl -sSL -o "$TMP/inter.zip" "$URL"
echo "$SHA256  $TMP/inter.zip" | sha256sum -c -

for APP in boundary boundary-remote; do
    mkdir -p "$APP/assets/fonts"
    for F in $FONTS; do
        unzip -p "$TMP/inter.zip" "extras/ttf/$F.ttf" > "$APP/assets/fonts/$F.ttf"
    done
    unzip -p "$TMP/inter.zip" "LICENSE.txt" > "$APP/assets/fonts/OFL.txt"
    echo "$APP/assets/fonts: $FONTS + OFL.txt"
done
