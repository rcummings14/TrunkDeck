#!/usr/bin/env bash
# Assembles pkg/TrunkDeck (the folder that becomes the release files) from stage/, the built jar, lib/ and the sources.
# Needs stage/ (in the source repository). Run "bash build.sh" first. Then: python3 make_zip.py (writes dist/)
set -euo pipefail
cd "$(dirname "$0")"
P=pkg/TrunkDeck
[ -f build/trunkdeck.jar ] || { echo "build/trunkdeck.jar missing - run build.sh first"; exit 1; }
rm -rf pkg && mkdir -p "$P/app/lib" "$P/source/third-party"
cp build/trunkdeck.jar lib/flatlaf-3.5.4.jar "$P/app/"
for j in lib/*.jar; do [ "$(basename "$j")" = flatlaf-3.5.4.jar ] || cp "$j" "$P/app/lib/"; done
cp -r stage/icons "$P/app/icons"
cp -r stage/native "$P/app/native"
cp stage/talkgroups_template.csv "$P/app/" && cp stage/talkgroups_template.csv "$P/"
cp stage/README.md stage/START-HERE.txt stage/NOTICE.txt "$P/"
cp -r stage/licenses "$P/licenses"
cp -r stage/linux "$P/linux" && cp -r stage/windows "$P/windows"
cp -r src test lib build.sh assemble_pkg.sh make_zip.py "$P/source/"
cp stage/third-party/* "$P/source/third-party/"
# never ship user data (radio-name files, settings) even if one was left in the tree
find "$P" \( -name "*.radios" -o -name "settings.properties" -o -name "rr-app-key.txt" -o -name "rr-login.properties" \) -delete
# never ship recordings of a real radio system (the user's I/Q captures live outside the tree, see LiveTests.recordings());
# the test suite only generates synthetic signals at run time, so no I/Q or audio data file belongs in source/
rm -rf "$P/source/test/data" "$P/source/real-tests"
find "$P/source" -type f \( -iname "*.wav" -o -iname "*.bin" -o -iname "*.iq" -o -iname "*.cu8" -o -iname "*.cs8" -o -iname "*.cs16" \
    -o -iname "*.cf32" -o -iname "*.raw" -o -iname "*.sdriq" -o -iname "*.mp3" \) -delete
left=$(find "$P" -type f \( -iname "*.wav" -o -iname "*.bin" -o -iname "*.iq" -o -iname "*.cu8" -o -iname "*.cs8" -o -iname "*.cs16" \
    -o -iname "*.cf32" -o -iname "*.raw" -o -iname "*.sdriq" -o -iname "*.radios" -o -name "settings.properties" -o -name "rr-app-key.txt" -o -name "rr-login.properties" \))
[ -z "$left" ] || { echo "user data left in $P:"; echo "$left"; exit 1; }
# A Windows checkout may have CRLF. Linux runs these files as scripts, so the package is LF
# even when this tree is not. .bat files are left alone.
find "$P" -type f \( -name '*.sh' -o -name '*.desktop' -o -name '*.rules' -o -path "$P/linux/*" \) -print0 |
  while IFS= read -r -d '' f; do tr -d '\r' < "$f" > "$f.lf" && mv "$f.lf" "$f"; done
chmod +x "$P/linux/"*.sh "$P/linux/udev/setup-usb.sh"
if grep -R -a -l $'\r' --include='*.sh' "$P" >/dev/null 2>&1; then
  echo "Linux package contains a shell script with CR:" >&2
  grep -R -a -l $'\r' --include='*.sh' "$P" >&2
  exit 1
fi
echo "Assembled $P"
