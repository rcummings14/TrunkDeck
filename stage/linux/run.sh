#!/usr/bin/env bash
# Starts TrunkDeck with the Java runtime the installer put in runtime/ (falls back to a system Java 22+ JDK).
HERE="$(cd "$(dirname "$(readlink -f "${BASH_SOURCE[0]}")")" && pwd)"
APPHOME="$HERE"
[ -d "$APPHOME/app" ] || APPHOME="$(cd "$HERE/.." && pwd)"   # running from the unzipped package
JAVA="$APPHOME/runtime/bin/java"
if [ ! -x "$JAVA" ]; then
  JAVA="$(command -v java || true)"
  [ -n "$JAVA" ] || { echo "Java not found. Run install.sh first (it downloads everything)."; exit 1; }
fi
exec "$JAVA" --enable-native-access=ALL-UNNAMED -XX:-OmitStackTraceInFastThrow -Dtrunkdeck.home="$APPHOME" -Dsun.java2d.opengl=false -Xmx768m -jar "$APPHOME/app/trunkdeck.jar" "$@"
