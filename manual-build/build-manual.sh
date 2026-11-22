#!/usr/bin/env bash
# CyberDrill — manual build (no Gradle daemon).
#
# Why this exists: this workspace's sandbox blocks raw localhost TCP, which
# the Gradle daemon protocol requires, so `./gradlew assembleDebug` cannot
# run inside the VM. This pipeline mirrors the owner's proven manual builds:
#   resolve_deps.py (HTTPS) -> aapt2 -> kotlinc (+ Compose plugin) -> d8
#   -> zipalign -> apksigner (debug key).
# On any normal machine, prefer the Gradle build (see README.md).
#
# Produces: manual-build/out/app-debug.apk
set -uo pipefail

export JAVA_HOME="${JAVA_HOME:-$HOME/workspace/tools/jdk17}"
export PATH="$JAVA_HOME/bin:$PATH"
SDK="${ANDROID_HOME:-$HOME/workspace/android-sdk}"
BT="$SDK/build-tools/34.0.0"
AJAR="$SDK/platforms/android-35/android.jar"
KOTLINC="$HOME/workspace/tools/kotlinc/bin/kotlinc"
PROJECT="$(cd "$(dirname "$0")/.." && pwd)"
MB="$PROJECT/manual-build"
OUT="$MB/out"
GEN="$OUT/gen"
PKG="com.cyberdrill.app"
VERSION_CODE=1
VERSION_NAME="1.0.0"
MIN_SDK=26
TARGET_SDK=35

rm -rf "$OUT"
mkdir -p "$GEN" "$OUT/dex"

echo "== 0/8 Librerías: extraer AAR de app/libs =="
LIBROOT="$OUT/libs"; mkdir -p "$LIBROOT"
LIB_JARS=(); LIB_RES_ZIPS=(); LIB_PKGS=()
for aar in "$PROJECT"/app/libs/*.aar; do
  [ -e "$aar" ] || continue
  base=$(basename "$aar" .aar)
  dest="$LIBROOT/$base"; mkdir -p "$dest"
  unzip -o -q "$aar" -d "$dest"
  [ -f "$dest/classes.jar" ] && LIB_JARS+=("$dest/classes.jar")
  for inner in "$dest"/libs/*.jar; do [ -e "$inner" ] && LIB_JARS+=("$inner"); done
  if [ -d "$dest/res" ] && [ -n "$(find "$dest/res" -type f | head -1)" ]; then
    "$BT/aapt2" compile --dir "$dest/res" -o "$LIBROOT/$base-res.zip" || exit 1
    LIB_RES_ZIPS+=("$LIBROOT/$base-res.zip")
    pkg=$(grep -o 'package="[^"]*"' "$dest/AndroidManifest.xml" | head -1 | cut -d'"' -f2)
    [ -n "$pkg" ] && LIB_PKGS+=("$pkg")
  fi
done
for jar in "$PROJECT"/app/libs/*.jar; do
  [ -e "$jar" ] && LIB_JARS+=("$jar")
done
# KMP graphs ship root + -android variants of the same artifact; d8 rejects
# duplicate classes. Drop jars whose classes are all provided by others.
mapfile -t LIB_JARS < <(python3 "$MB/dedupe_jars.py" "${LIB_JARS[@]}")
echo "   jars: ${#LIB_JARS[@]} · paquetes con recursos: ${#LIB_RES_ZIPS[@]}"

echo "== 1/8 Manifiesto fusionado + recursos de configuración de Firebase =="
# The 4th argument is the applicationId: merge_manifests.py substitutes the
# "${applicationId}" placeholders that AAR manifests carry (FirebaseInitProvider
# authority, androidx.startup authority, DYNAMIC_RECEIVER permission). Without
# it the APK installs but does not open on a real device.
python3 "$MB/merge_manifests.py" "$PROJECT/app/src/main/AndroidManifest.xml" "$LIBROOT" "$OUT/AndroidManifest.xml" "$PKG" || exit 1
sed -i 's|<manifest |<manifest package="'"$PKG"'" |' "$OUT/AndroidManifest.xml"
mkdir -p "$OUT/firebase-res/values"
python3 "$MB/gen_firebase_res.py" "$PROJECT/app/google-services.json" "$OUT/firebase-res/values/googleservices.xml" || exit 1

echo "== 2/8 Recursos: compilar con aapt2 =="
"$BT/aapt2" compile --dir "$PROJECT/app/src/main/res" -o "$OUT/compiled.zip" || exit 1
"$BT/aapt2" compile --dir "$OUT/firebase-res" -o "$OUT/firebase-compiled.zip" || exit 1

echo "== 3/8 Recursos: enlazar y generar R.java (target API $TARGET_SDK) =="
EXTRA_ARGS=()
if [ ${#LIB_PKGS[@]} -gt 0 ]; then
  EXTRA_PKG=$(IFS=:; echo "${LIB_PKGS[*]}")
  EXTRA_ARGS=(--extra-packages "$EXTRA_PKG")
fi
"$BT/aapt2" link -o "$OUT/app-unsigned.apk" \
  -I "$AJAR" \
  --manifest "$OUT/AndroidManifest.xml" \
  --java "$GEN" \
  --auto-add-overlay "${EXTRA_ARGS[@]}" \
  --min-sdk-version $MIN_SDK --target-sdk-version $TARGET_SDK \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  "$OUT/compiled.zip" "$OUT/firebase-compiled.zip" "${LIB_RES_ZIPS[@]}" || exit 1

echo "== 4/8 Generar BuildConfig.java =="
PKG_PATH=$(echo "$PKG" | tr '.' '/')
mkdir -p "$GEN/$PKG_PATH"
cat > "$GEN/$PKG_PATH/BuildConfig.java" <<EOF
package $PKG;
public final class BuildConfig {
  public static final boolean DEBUG = true;
  public static final String APPLICATION_ID = "$PKG";
  public static final String BUILD_TYPE = "debug";
  public static final int VERSION_CODE = $VERSION_CODE;
  public static final String VERSION_NAME = "$VERSION_NAME";
}
EOF

echo "== 5/8 Compilar Kotlin (kotlinc + plugin de Compose) =="
find "$PROJECT/app/src/main/java" -name '*.kt' > "$OUT/sources.txt"
find "$GEN" -name 'R.java' >> "$OUT/sources.txt"
echo "$GEN/$PKG_PATH/BuildConfig.java" >> "$OUT/sources.txt"
LIB_CP=$(IFS=:; echo "${LIB_JARS[*]}")
# NOTE: the Compose compiler plugin only exists in "embeddable compiler"
# form (its registrar references the shaded org.jetbrains.kotlin.com.intellij
# classes), so it cannot be loaded by the standalone kotlinc frontend. We run
# the embeddable compiler's own CLI (org.jetbrains.kotlin.cli.jvm.K2JVMCompiler)
# from manual-build/tools/ instead — same Kotlin 2.0.20, same result.
java -Xmx2048m -cp "$MB/tools/kotlin-compiler-embeddable-2.0.20.jar:$HOME/workspace/tools/kotlinc/lib/kotlin-stdlib.jar:$HOME/workspace/tools/kotlinc/lib/kotlinx-coroutines-core-jvm.jar:$HOME/workspace/tools/kotlinc/lib/trove4j.jar:$HOME/workspace/tools/kotlinc/lib/annotations-13.0.jar" \
  org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect \
  -Xskip-metadata-version-check \
  -classpath "$AJAR:$LIB_CP" \
  -Xplugin="$MB/tools/kotlin-compose-compiler-plugin-embeddable-2.0.20.jar" \
  -d "$OUT/classes.jar" @"$OUT/sources.txt" || exit 1

echo "== 6/8 Compilar R.java y BuildConfig con javac =="
mkdir -p "$OUT/javac"
find "$GEN" -name '*.java' > "$OUT/java-sources.txt"
javac -encoding UTF-8 -cp "$AJAR" -d "$OUT/javac" @"$OUT/java-sources.txt" || exit 1
(cd "$OUT/javac" && zip -q -r "$OUT/java-classes.jar" .)

echo "== 7/8 Convertir a DEX con d8 =="
# kotlin-stdlib: prefer the resolved copy from app/libs; else kotlinc's own.
STDLIB=""
for j in "${LIB_JARS[@]}"; do
  case "$j" in *kotlin-stdlib-2*) STDLIB="$j" ;; esac
done
DEX_INPUTS=("$OUT/classes.jar" "$OUT/java-classes.jar" "${LIB_JARS[@]}")
if [ -z "$STDLIB" ]; then
  DEX_INPUTS+=("$HOME/workspace/tools/kotlinc/lib/kotlin-stdlib.jar")
  echo "   (stdlib de kotlinc)"
fi
# Standalone R8/D8 8.13 instead of build-tools 34's d8 (8.2): the old d8
# crashes with an NPE on the newest ML Kit GenAI class files.
java -cp "$MB/tools/r8-8.13.25.jar" com.android.tools.r8.D8 \
  --lib "$AJAR" --min-api $MIN_SDK \
  --output "$OUT/dex" "${DEX_INPUTS[@]}" || exit 1
ls "$OUT/dex"

echo "== 8/8 Ensamblar APK (dex + nativas + assets), alinear y firmar =="
cp "$OUT/app-unsigned.apk" "$OUT/app-unaligned.apk"
(cd "$OUT/dex" && zip -q -X "$OUT/app-unaligned.apk" classes*.dex)
# Native libraries and assets shipped inside AARs, if any.
STAGE="$OUT/stage"; mkdir -p "$STAGE"
for d in "$LIBROOT"/*/; do
  if [ -d "$d/jni" ]; then (cd "$d" && find jni -name '*.so' | while read -r so; do
    abi=$(basename "$(dirname "$so")"); mkdir -p "$STAGE/lib/$abi"; cp "$so" "$STAGE/lib/$abi/"; done); fi
  if [ -d "$d/assets" ] && [ -n "$(find "$d/assets" -type f | head -1)" ]; then
    mkdir -p "$STAGE/assets"; cp -r "$d/assets/." "$STAGE/assets/"; fi
done
if [ -d "$STAGE/lib" ] || [ -d "$STAGE/assets" ]; then
  (cd "$STAGE" && zip -q -0 -X -r "$OUT/app-unaligned.apk" lib assets 2>/dev/null || \
   zip -q -0 -X -r "$OUT/app-unaligned.apk" lib || \
   zip -q -0 -X -r "$OUT/app-unaligned.apk" assets)
fi
"$BT/zipalign" -f -p 4 "$OUT/app-unaligned.apk" "$OUT/app-aligned.apk" || exit 1
KS="$MB/debug.keystore"
if [ ! -f "$KS" ]; then
  keytool -genkeypair -v -keystore "$KS" -storepass android \
    -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 \
    -validity 10000 -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android \
  --key-pass pass:android --out "$OUT/app-debug.apk" "$OUT/app-aligned.apk" || exit 1
"$BT/apksigner" verify --print-certs "$OUT/app-debug.apk" | head -2

echo ""
echo "APK LISTO: $OUT/app-debug.apk"
ls -la "$OUT/app-debug.apk"
