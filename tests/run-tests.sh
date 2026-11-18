#!/bin/bash
# Run the JVM unit tests for the pure-Kotlin units (VaultCrypto).
# No framework: each test file has a main() that prints its pass/fail
# counts and exits non-zero on any failure.
set -e
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
FILES_ROOT="${NOVA_FILES_ROOT:-$ROOT/../nova-files}"
[ -d "$FILES_ROOT" ] || FILES_ROOT="$HOME/workspace/nova-files"
export JAVA_HOME="${JAVA_HOME:-$FILES_ROOT/tools/jdk-17.0.21+9}"
export PATH="$JAVA_HOME/bin:$PATH"
KOTLINC="$FILES_ROOT/tools/kotlinc/bin/kotlinc"
[ -x "$KOTLINC" ] || { echo "kotlinc not found at $KOTLINC"; exit 1; }

OUT="$ROOT/build/test-classes"
rm -rf "$OUT"
mkdir -p "$OUT"

SRC="$ROOT/app/src/main/java"
"$KOTLINC" -jvm-target 17 -d "$OUT" \
    "$SRC/com/nova/files/data/VaultCrypto.kt" \
    "$ROOT/tests/VaultCryptoTest.kt"

# Compile-check the Android-side crypto files too (they need android.jar
# for the Keystore / Base64 APIs but are never executed here). FileRepo
# and the UI need the full Android/Compose classpath, so they are checked
# by the real APK build (tools/build-apk.sh) during the release audit.
ANDROID_JAR="$HOME/android-sdk/platforms/android-35/android.jar"
"$KOTLINC" -jvm-target 17 -classpath "$ANDROID_JAR" -d "$OUT" \
    "$SRC/com/nova/files/data/VaultCrypto.kt" \
    "$SRC/com/nova/files/data/VaultSession.kt" \
    "$SRC/com/nova/files/data/VaultKeystore.kt"

# kotlin-stdlib for the test run (the sources use only the stdlib).
STDLIB="$FILES_ROOT/tools/kotlinc/lib/kotlin-stdlib.jar"
[ -f "$STDLIB" ] || { echo "kotlin-stdlib.jar not found at $STDLIB"; exit 1; }

FAIL=0
java -cp "$OUT:$STDLIB" com.nova.files.data.VaultCryptoTestKt || FAIL=1
if [ "$FAIL" -eq 0 ]; then
    echo "ALL JVM TESTS PASSED"
else
    echo "SOME JVM TESTS FAILED"
    exit 1
fi
