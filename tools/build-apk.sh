#!/bin/bash
# Usage: build-apk.sh <project_dir> <output_apk> <min_sdk> <target_sdk>
set -euo pipefail

ROOT=$(cd "$1" && pwd)
OUT=$2
MINSDK=$3
TGTSDK=$4
VCODE=${5:-1}
VNAME=${6:-1.0}

SDK=/workspace/tools/sdk
BT=$SDK/build-tools/34.0.0
PLAT=$SDK/platforms/android-$MINSDK/android.jar
JDK=/workspace/tools/jdk17
export PATH=$JDK/bin:$PATH
export JAVA_HOME=$JDK

cd $ROOT
rm -rf build
mkdir -p build/flat build/gen build/classes build/dex

echo "== aapt2 compile =="
$BT/aapt2 compile --dir res -o build/flat

echo "== aapt2 link =="
$BT/aapt2 link -o build/unsigned.apk \
    -I $PLAT \
    --manifest AndroidManifest.xml \
    --java build/gen \
    --min-sdk-version $MINSDK --target-sdk-version $TGTSDK \
    --version-code $VCODE --version-name $VNAME \
    build/flat/*

echo "== javac =="
$JDK/bin/javac --release 8 -cp $PLAT -d build/classes $(find src build/gen -name '*.java')

echo "== d8 =="
(cd build/classes && $BT/d8 --release --min-api $MINSDK --lib $PLAT --output ../dex $(find . -name '*.class'))

echo "== add dex =="
(cd build/dex && zip -q ../unsigned.apk classes.dex)

echo "== zipalign =="
$BT/zipalign -f 4 build/unsigned.apk build/aligned.apk

echo "== keystore =="
if [ ! -f build/keystore.jks ]; then
    $JDK/bin/keytool -genkeypair -keystore build/keystore.jks -alias app \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -storepass apppass123 -keypass apppass123 \
        -dname "CN=FireCalc, OU=Dev, O=Example, C=US" >/dev/null
fi

echo "== sign =="
$BT/apksigner sign --ks build/keystore.jks --ks-key-alias app \
    --ks-pass pass:apppass123 --key-pass pass:apppass123 \
    --out $OUT build/aligned.apk

echo "== verify =="
$BT/apksigner verify $OUT
$BT/aapt dump badging $OUT | grep -E "package:|sdkVersion|targetSdkVersion|launchable|leanback|uses-permission"
ls -la $OUT
