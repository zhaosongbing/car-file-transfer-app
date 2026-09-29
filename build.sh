#!/usr/bin/env bash
# Manual APK build: no Gradle required (aapt2 + javac + d8 + zipalign + apksigner)
set -e

PROJ="$(cd "$(dirname "$0")" && pwd)"
cd "$PROJ"

JDK="$(cd "$PROJ/../tools/jdk-17.0.13+11" && pwd -W)"
export JAVA_HOME="$JDK"
JAVA="$JDK/bin/java.exe"
JAVAC="$JDK/bin/javac.exe"
KEYTOOL="$JDK/bin/keytool.exe"

# Locate the Android SDK. The default install under AppData can be wiped by
# disk-cleanup tools, so honour ANDROID_HOME first and fall back through the
# usual locations rather than assuming one fixed path.
if [ -n "$ANDROID_HOME" ] && [ -d "$ANDROID_HOME" ]; then
  SDK="$(cd "$ANDROID_HOME" && pwd -W)"
elif [ -d ~/AppData/Local/Android/Sdk ]; then
  SDK="$(cd ~/AppData/Local/Android/Sdk && pwd -W)"
elif [ -d "$PROJ/../.workbuddy/binaries/android-sdk" ]; then
  SDK="$(cd "$PROJ/../.workbuddy/binaries/android-sdk" && pwd -W)"
else
  echo "!! Android SDK not found - set ANDROID_HOME to your SDK directory"
  exit 1
fi

# Use whatever build-tools / platform levels are installed instead of pinning a
# single version, so a missing level never breaks the whole build.
BT_VER="$(ls "$SDK/build-tools" 2>/dev/null | sort -V | tail -1)"
PLAT_VER="$(ls "$SDK/platforms" 2>/dev/null | sort -V | tail -1)"
if [ -z "$BT_VER" ] || [ -z "$PLAT_VER" ]; then
  echo "!! SDK at $SDK has no build-tools / platforms installed"
  exit 1
fi
echo "SDK=$SDK (build-tools $BT_VER, platform $PLAT_VER)"

BT="$SDK/build-tools/$BT_VER"
PLAT="$SDK/platforms/$PLAT_VER"
AAPT2="$BT/aapt2.exe"
AAPT="$BT/aapt.exe"
ZIPALIGN="$BT/zipalign.exe"
D8JAR="$BT/lib/d8.jar"
SIGNJAR="$BT/lib/apksigner.jar"
ANDROID_JAR="$PLAT/android.jar"

SRC="$PROJ/app/src/main"
OUT="$PROJ/build"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"
# Targeted cleanup of generated artifacts only (avoids a whole-dir bulk delete).
rm -f "$OUT"/res.zip "$OUT"/base-unsigned.apk "$OUT"/base-aligned.apk \
  "$OUT"/classes.jar "$OUT"/classes.dex "$OUT"/sources.txt 2>/dev/null || true

echo "== java =="; "$JAVA" -version 2>&1 | head -2

echo "== 1. aapt2 compile res =="
"$AAPT2" compile --dir "$SRC/res" -o "$OUT/res.zip"

echo "== 2. aapt2 link =="
"$AAPT2" link -o "$OUT/base-unsigned.apk" \
  -I "$ANDROID_JAR" \
  -R "$OUT/res.zip" \
  --manifest "$SRC/AndroidManifest.xml" \
  --auto-add-overlay \
  -A "$SRC/assets" \
  --min-sdk-version 26 \
  --target-sdk-version 36 \
  --version-code 15 \
  --version-name 9.6 \
  --java "$OUT/gen"

echo "== 3. javac =="
OUTM="$(cygpath -m "$OUT")"
ANDROID_JARM="$(cygpath -m "$ANDROID_JAR")"
find "$SRC/java" -name "*.java" | while read -r f; do cygpath -m "$f"; done > "$OUT/sources.txt"
cygpath -m "$OUT/gen/com/zsb/carfiletransfer/R.java" >> "$OUT/sources.txt"
"$JAVAC" -encoding UTF-8 -nowarn -d "$OUTM/classes" \
  -cp "$ANDROID_JARM;$OUTM/gen" --release 11 \
  @"$OUTM/sources.txt"

echo "== 4. jar =="
JAR="$JDK/bin/jar.exe"
"$JAR" cf "$OUT/classes.jar" -C "$OUT/classes" .

echo "== 5. d8 =="
"$JAVA" -cp "$D8JAR" com.android.tools.r8.D8 --release \
  --lib "$ANDROID_JAR" --output "$OUT/dex" "$OUT/classes.jar"

echo "== 6. aapt add dex =="
cp "$OUT/dex/classes.dex" "$OUT/classes.dex"
(cd "$OUT" && "$AAPT" add base-unsigned.apk classes.dex)

echo "== 7. zipalign =="
"$ZIPALIGN" -p 4 "$OUT/base-unsigned.apk" "$OUT/base-aligned.apk"
rm -f "$OUT/base-unsigned.apk"

echo "== 8. keystore =="
# The signing key lives OUTSIDE build/ (which is wiped on every run) and is
# reused as-is. A fresh key per build would change the signing certificate and
# Android would reject the install as an update to the previous version.
#
# IMPORTANT: neither the keystore nor its passphrase may ever be committed -
# together they let anyone publish APKs signed with the same certificate and
# hijack the in-app update channel. The repo is public, so the passphrase is
# read from a local, uncommitted keystore/pass.txt, falling back to the
# CARFILE_KS_PASS environment variable. See push_repo.py EXCLUDE_DIRS.
KS="$PROJ/keystore/carfile.keystore"
mkdir -p "$PROJ/keystore"
KS_PASS=""
if [ -f "$PROJ/keystore/pass.txt" ]; then
  KS_PASS=$(tr -d '\r\n\t ' < "$PROJ/keystore/pass.txt")
elif [ -n "$CARFILE_KS_PASS" ]; then
  KS_PASS="$CARFILE_KS_PASS"
else
  echo "!! keystore password not found: put it in $PROJ/keystore/pass.txt or export CARFILE_KS_PASS"
  exit 1
fi
if [ ! -f "$KS" ]; then
  echo "!! no keystore at $KS - generating a new one (updates will NOT install over older builds)"
  "$KEYTOOL" -genkeypair -v -keystore "$KS" -alias carfile \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass "$KS_PASS" -keypass "$KS_PASS" \
    -dname "CN=CarFileTransfer, OU=Dev, O=ZSB, C=CN"
fi
"$KEYTOOL" -list -v -keystore "$KS" -storepass "$KS_PASS" -alias carfile 2>/dev/null \
  | grep -iE "SHA256|SHA1" | head -4

echo "== 9. apksigner =="
"$JAVA" -cp "$SIGNJAR" com.android.apksigner.ApkSignerTool sign \
  --ks "$KS" --ks-key-alias carfile \
  --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
  --out "$OUT/CarFileTransfer.apk" "$OUT/base-aligned.apk"

echo "== 10. verify =="
"$JAVA" -cp "$SIGNJAR" com.android.apksigner.ApkSignerTool verify --verbose "$OUT/CarFileTransfer.apk" | tail -12

echo "== apk contents =="
"$JAVA" -version >/dev/null 2>&1
python - <<'PY'
import zipfile,sys
z=zipfile.ZipFile(r"C:/Users/zhaosongbing/WorkBuddy/2026-09-26-22-21-58/android/build/CarFileTransfer.apk")
names=z.namelist()
must=["classes.dex","AndroidManifest.xml"]
for m in must:
    print(("OK   " if m in names else "MISS ")+m)
print("assets:", [n for n in names if n.startswith("assets/")])
print("size:", sum(z.getinfo(n).file_size for n in names), "bytes")
PY

echo "BUILD DONE: $OUT/CarFileTransfer.apk"
