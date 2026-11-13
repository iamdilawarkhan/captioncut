#!/bin/bash
# CaptionCut manual APK build (plain Java + WebView, no Gradle, no deps)
# Same proven toolchain as PromptPix / CineCommentary.
set -e
export JAVA_HOME=~/tools/jdk17
export PATH=~/tools/jdk17/bin:~/android-sdk/build-tools/34.0.0:$PATH
BASE=~/workspace/captioncut-app
M=$BASE/build/manual
APP=$BASE/android/app/src/main
AAPT2=~/android-sdk/build-tools/34.0.0/aapt2
ANDROID_JAR=~/android-sdk/platforms/android-34/android.jar
BT=~/android-sdk/build-tools/34.0.0
KS=$BASE/keystore/captioncut.keystore

# Signing password comes ONLY from the environment — never from a file.
# Export CAPTIONCUT_KS_PASS before running this script; it fails otherwise.
: "${CAPTIONCUT_KS_PASS:?CAPTIONCUT_KS_PASS is not set — export the keystore password as an env var (never commit it to any file).}"

rm -rf $M && mkdir -p $M/compiled_res $M/gen $M/classes $M/dex $M/merged_manifest

echo "== aapt2 compile res =="
$AAPT2 compile --dir $APP/res -o $M/compiled_res/app.zip

echo "== manifest package =="
sed 's|<manifest xmlns:android=|<manifest package="com.captioncut.app" xmlns:android=|' \
  $APP/AndroidManifest.xml > $M/merged_manifest/AndroidManifest.xml

echo "== aapt2 link =="
$AAPT2 link -o $M/base.apk \
  -I $ANDROID_JAR \
  --manifest $M/merged_manifest/AndroidManifest.xml \
  -A $APP/assets \
  --java $M/gen \
  --min-sdk-version 26 --target-sdk-version 34 \
  --version-code 15 --version-name 1.14 \
  $M/compiled_res/app.zip
echo "LINK DONE"; ls -la $M/base.apk

echo "== javac =="
find $APP/java $M/gen -name "*.java" > $M/sources.txt
wc -l $M/sources.txt
javac -encoding UTF-8 -source 8 -target 8 -nowarn -cp $ANDROID_JAR -d $M/classes @$M/sources.txt 2>&1 | grep -v "bootstrap\|deprecat" | head -30 || true
find $M/classes -name "*.class" | wc -l

echo "== d8 =="
(cd $M/classes && jar cf $M/app-classes.jar .)
$BT/d8 --min-api 26 --lib $ANDROID_JAR --output $M/dex $M/app-classes.jar
ls -la $M/dex/

echo "== keystore (persistent, kept for in-place updates) =="
if [ ! -f $KS ]; then
  mkdir -p $BASE/keystore
  keytool -genkeypair -keystore $KS -storepass "$CAPTIONCUT_KS_PASS" \
    -keypass "$CAPTIONCUT_KS_PASS" -keyalg RSA -keysize 2048 -validity 10950 \
    -alias captioncut -dname "CN=CaptionCut,O=CaptionCut,C=PK" 2>&1 | tail -1
fi

echo "== package =="
cp $M/base.apk $M/unaligned.apk
(cd $M/dex && zip -q -0 $M/unaligned.apk classes.dex)
$BT/zipalign -f 4 $M/unaligned.apk $M/aligned.apk
$BT/apksigner sign --ks $KS --ks-pass "pass:$CAPTIONCUT_KS_PASS" \
  --key-pass "pass:$CAPTIONCUT_KS_PASS" --out $BASE/deliver/CaptionCut-v1.14-debug.apk $M/aligned.apk

echo "== verify =="
$BT/apksigner verify --print-certs $BASE/deliver/CaptionCut-v1.14-debug.apk 2>&1 | head -3
$BT/aapt dump badging $BASE/deliver/CaptionCut-v1.14-debug.apk 2>/dev/null | head -5
ls -la $BASE/deliver/
