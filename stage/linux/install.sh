#!/usr/bin/env bash
# TrunkDeck installer for Linux (Fedora, Ubuntu, Debian, Mint, Arch, Raspberry Pi OS 64-bit)
# Installs for the current user only: ~/.local/share/trunkdeck  (no root needed, except the optional SDR USB rules)
set -euo pipefail

APP_NAME="TrunkDeck"
DEST="${TRUNKDECK_DEST:-$HOME/.local/share/trunkdeck}"
BIN_DIR="$HOME/.local/bin"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PKG="$(cd "$HERE/.." && pwd)"

c_ok=$'\e[32m'; c_warn=$'\e[33m'; c_err=$'\e[31m'; c_hi=$'\e[36m'; c_off=$'\e[0m'
say()  { printf '%s\n' "${c_hi}==>${c_off} $*"; }
ok()   { printf '%s\n' "${c_ok}  ok${c_off} $*"; }
warn() { printf '%s\n' "${c_warn}  !${c_off}  $*"; }
die()  { printf '%s\n' "${c_err}ERROR:${c_off} $*" >&2; exit 1; }

echo
echo "  ${c_hi}TRUNK${c_off}DECK  -  P25 trunking monitor  -  Linux installer"
echo "  Installs to: $DEST"
echo

# ---- Java runtime: Eclipse Temurin JDK 25 (a full JDK: TrunkDeck builds the JMBE voice codec from source on
#      first start, which needs the Java compiler; the engine needs Java 22+ for USB access) ----
JDK_VER="25.0.5+8"
case "$(uname -m)" in
  x86_64|amd64)  JDK_FILE="OpenJDK25U-jdk_x64_linux_hotspot_25.0.5_8.tar.gz";     JDK_SHA="68982529869257d0ee6b824ec5aa270ff8b118bb438670da0b97ebdab178d7fb" ;;
  aarch64|arm64) JDK_FILE="OpenJDK25U-jdk_aarch64_linux_hotspot_25.0.5_8.tar.gz"; JDK_SHA="bb5b2ded252f21c6090cf780b0a408ad0d83bbb557e702955643557f13231880" ;;
  *) die "Unsupported CPU '$(uname -m)'. TrunkDeck needs a 64-bit x86 or ARM computer." ;;
esac
JDK_URL="https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.5%2B8/${JDK_FILE}"

command -v tar >/dev/null 2>&1 || die "'tar' is missing. Fedora: sudo dnf install tar   Ubuntu/Debian: sudo apt install tar"
if command -v curl >/dev/null 2>&1; then DL="curl"; elif command -v wget >/dev/null 2>&1; then DL="wget"; else DL=""; fi

# ---- copy TrunkDeck (settings, talkgroups, recordings and the JMBE codec live outside this folder and are kept) ----
say "Copying $APP_NAME"
mkdir -p "$DEST" "$BIN_DIR"
rm -rf "$DEST/app"
cp -r "$PKG/app" "$DEST/app"
cp "$HERE/uninstall.sh" "$DEST/uninstall.sh"
cp "$HERE/run.sh" "$DEST/run.sh"
rm -rf "$DEST/udev"; cp -r "$HERE/udev" "$DEST/udev"
cp "$PKG/README.md" "$DEST/README.md" 2>/dev/null || true
rm -rf "$DEST/licenses"; cp -r "$PKG/licenses" "$DEST/licenses" 2>/dev/null || true
chmod +x "$DEST/uninstall.sh" "$DEST/run.sh" "$DEST/udev/setup-usb.sh" 2>/dev/null || true
ok "app files"

# ---- upgrade from 1.1.x: remove the old SDRTrunk program folder (only the program TrunkDeck downloaded) ----
if [ -d "$DEST/engine" ]; then
  pkill -f "$DEST/engine/" 2>/dev/null || true
  # keep any JMBE codec jar that might sit inside the old program folder
  find "$DEST/engine" -name 'jmbe-*.jar' ! -name 'jmbe-api*' -type f 2>/dev/null | while read -r j; do
    mkdir -p "$HOME/.trunkdeck/jmbe"; [ -f "$HOME/.trunkdeck/jmbe/$(basename "$j")" ] || cp "$j" "$HOME/.trunkdeck/jmbe/"
  done
  rm -rf "$DEST/engine"
  ok "removed the old SDRTrunk program folder ($DEST/engine) - TrunkDeck decodes everything itself now"
  echo "     Your settings, talkgroups, recordings and the JMBE voice codec are kept."
fi

# ---- Java runtime ----
if [ -x "$DEST/runtime/bin/java" ] && [ -x "$DEST/runtime/bin/javac" ] && [ "$(cat "$DEST/runtime/.version" 2>/dev/null)" = "$JDK_VER" ]; then
  ok "Java runtime (Temurin JDK $JDK_VER) already installed"
else
  say "Getting the Java runtime (Eclipse Temurin JDK $JDK_VER, about 140 MB)"
  TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
  LOCAL=""
  for cand in "$HERE/$JDK_FILE" "$PKG/$JDK_FILE" "$HOME/Downloads/$JDK_FILE"; do [ -f "$cand" ] && LOCAL="$cand" && break; done
  if [ -n "$LOCAL" ]; then
    ok "using local file $LOCAL"
    cp "$LOCAL" "$TMP/jdk.tar.gz"
  else
    [ -n "$DL" ] || die "Need curl or wget to download. Or download $JDK_URL yourself and put it next to install.sh"
    echo "     $JDK_URL"
    if [ "$DL" = curl ]; then curl -fL --retry 3 --progress-bar -o "$TMP/jdk.tar.gz" "$JDK_URL"; else wget -q --show-progress -O "$TMP/jdk.tar.gz" "$JDK_URL"; fi
  fi
  if command -v sha256sum >/dev/null 2>&1; then GOT="$(sha256sum "$TMP/jdk.tar.gz" | cut -d' ' -f1)"
  elif command -v shasum >/dev/null 2>&1; then GOT="$(shasum -a 256 "$TMP/jdk.tar.gz" | cut -d' ' -f1)"
  else die "Neither sha256sum nor shasum found - cannot verify the download."; fi
  [ "$GOT" = "$JDK_SHA" ] || die "Download checksum mismatch (got $GOT). Delete the file and try again."
  ok "checksum verified"
  mkdir -p "$TMP/x"
  tar -xzf "$TMP/jdk.tar.gz" -C "$TMP/x"
  JDIR="$(find "$TMP/x" -mindepth 1 -maxdepth 1 -type d | head -n1)"
  [ -x "$JDIR/bin/java" ] || die "The Java download does not look right (no bin/java)."
  rm -rf "$DEST/runtime"
  mv "$JDIR" "$DEST/runtime"
  echo "$JDK_VER" > "$DEST/runtime/.version"
  ok "Java runtime installed in $DEST/runtime"
fi

# ---- launcher, icon, menu entry, desktop shortcut ----
say "Creating launcher, app-menu entry and desktop shortcut"
cat > "$BIN_DIR/trunkdeck" <<LAUNCH
#!/usr/bin/env bash
exec "$DEST/run.sh" "\$@"
LAUNCH
chmod +x "$BIN_DIR/trunkdeck"

ICON_DIR="$HOME/.local/share/icons/hicolor"
for s in 16 24 32 48 64 128 256 512; do
  mkdir -p "$ICON_DIR/${s}x${s}/apps"
  cp "$PKG/app/icons/trunkdeck-$s.png" "$ICON_DIR/${s}x${s}/apps/trunkdeck.png"
done
mkdir -p "$ICON_DIR/scalable/apps" && cp "$PKG/app/icons/trunkdeck.svg" "$ICON_DIR/scalable/apps/trunkdeck.svg"   # 1.4.5: vector logo

DESKTOP_FILE="$HOME/.local/share/applications/trunkdeck.desktop"
mkdir -p "$(dirname "$DESKTOP_FILE")"
cat > "$DESKTOP_FILE" <<DESK
[Desktop Entry]
Type=Application
Name=TrunkDeck
GenericName=P25 Trunking Monitor
Comment=Monitor and listen to P25 trunked radio systems
Exec="$DEST/run.sh"
Icon=trunkdeck
Terminal=false
Categories=AudioVideo;Audio;HamRadio;
Keywords=scanner;p25;radio;sdr;trunking;rtl-sdr;airspy;
StartupWMClass=p25monitor-Main
DESK
chmod +x "$DESKTOP_FILE"
command -v update-desktop-database >/dev/null 2>&1 && update-desktop-database -q "$HOME/.local/share/applications" || true
command -v gtk-update-icon-cache >/dev/null 2>&1 && gtk-update-icon-cache -q -t "$ICON_DIR" 2>/dev/null || true
ok "app menu: TrunkDeck"

DESKTOP_DIR="$(xdg-user-dir DESKTOP 2>/dev/null || echo "$HOME/Desktop")"
if [ -d "$DESKTOP_DIR" ]; then
  cp "$DESKTOP_FILE" "$DESKTOP_DIR/trunkdeck.desktop"
  chmod +x "$DESKTOP_DIR/trunkdeck.desktop"
  command -v gio >/dev/null 2>&1 && gio set "$DESKTOP_DIR/trunkdeck.desktop" metadata::trusted true 2>/dev/null || true
  ok "desktop shortcut: $DESKTOP_DIR/trunkdeck.desktop"
fi

# ---- SDR USB access (Airspy + RTL-SDR) ----
echo
say "USB permissions for Airspy and RTL-SDR receivers"
RULES_OK=0; [ -f /etc/udev/rules.d/52-trunkdeck-sdr.rules ] && RULES_OK=1
BL_OK=0; [ -f /etc/modprobe.d/blacklist-trunkdeck-rtl.conf ] && BL_OK=1
if [ $RULES_OK = 1 ] && [ $BL_OK = 1 ]; then
  ok "already set up"
else
  echo "  Your dongles need a udev rule (lets your user open them) and the TV-tuner driver must be"
  echo "  blocked so RTL-SDR dongles are free for TrunkDeck. This needs your password (sudo) once."
  ANSWER="n"
  if [ -t 0 ]; then read -r -p "  Set this up now? [Y/n] " ANSWER || true; ANSWER="${ANSWER:-y}"; fi
  if [[ "$ANSWER" =~ ^[Yy] ]]; then
    sudo install -m 0644 "$HERE/udev/52-trunkdeck-sdr.rules" /etc/udev/rules.d/52-trunkdeck-sdr.rules
    sudo install -m 0644 "$HERE/udev/blacklist-trunkdeck-rtl.conf" /etc/modprobe.d/blacklist-trunkdeck-rtl.conf
    sudo udevadm control --reload-rules && sudo udevadm trigger || true
    sudo modprobe -r dvb_usb_rtl28xxu 2>/dev/null || true
    ok "done - unplug and re-plug your SDRs"
  else
    warn "skipped. Later run:  sudo $DEST/udev/setup-usb.sh"
  fi
fi

# libusb-1.0: used by the Airspy driver (and by the bundled librtlsdr)
if ldconfig -p 2>/dev/null | grep -q "libusb-1.0.so.0" || ls /usr/lib64/libusb-1.0.so.0 /usr/lib/*/libusb-1.0.so.0 >/dev/null 2>&1; then
  ok "libusb-1.0 found"
else
  warn "libusb-1.0 not found - install it for the Airspy / RTL-SDR:  sudo dnf install libusb1   (Debian/Ubuntu: sudo apt install libusb-1.0-0)"
fi

echo
echo "${c_ok}Installed!${c_off}  Start TrunkDeck from your app menu, the desktop icon, or by typing:  trunkdeck"
echo "First start: pick your system, plug in your Airspy or dongle(s) and press START MONITORING."
echo "The first time, TrunkDeck builds the JMBE voice codec from its source code on your computer (about a minute)."
case ":$PATH:" in *":$BIN_DIR:"*) ;; *) warn "$BIN_DIR is not in your PATH yet - log out and back in, or use the menu icon." ;; esac
[ -f "$HOME/.trunkdeck/settings.properties" ] && echo "Upgrading from 1.1.x? Your systems, talkgroups, streams and recordings carry over; see README.md > Updating."
echo "Uninstall any time:  $DEST/uninstall.sh"
