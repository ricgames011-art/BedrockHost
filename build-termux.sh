#!/data/data/com.termux/files/usr/bin/bash
set -e
if ! command -v java >/dev/null || ! command -v gradle >/dev/null; then
  echo "Falta Java/Gradle. Instala JDK 17 y Gradle 8.9+."; exit 1
fi
if [ -z "${ANDROID_HOME:-}" ] || [ ! -d "$ANDROID_HOME/platforms/android-35" ]; then
  echo "ANDROID_HOME no apunta a un SDK con platforms/android-35."
  echo "En Termux suele ser más fiable compilar con GitHub Actions; consulta README.md."
  exit 1
fi
gradle --no-daemon assembleDebug
echo "APK: app/build/outputs/apk/debug/app-debug.apk"
