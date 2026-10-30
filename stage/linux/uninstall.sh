#!/usr/bin/env bash
# Removes TrunkDeck (program, Java runtime, menu entry, desktop shortcut). Your settings and recordings are kept unless you say otherwise.
set -u
DEST="${TRUNKDECK_DEST:-$HOME/.local/share/trunkdeck}"
echo "This removes TrunkDeck from $DEST, its menu entry and desktop shortcut."
read -r -p "Continue? [y/N] " a; [[ "${a:-n}" =~ ^[Yy] ]] || exit 0
pkill -f "trunkdeck.jar" 2>/dev/null || true
rm -f "$HOME/.local/bin/trunkdeck" "$HOME/.local/share/applications/trunkdeck.desktop"
DESKTOP_DIR="$(xdg-user-dir DESKTOP 2>/dev/null || echo "$HOME/Desktop")"
rm -f "$DESKTOP_DIR/trunkdeck.desktop"
for s in 16 24 32 48 64 128 256 512; do rm -f "$HOME/.local/share/icons/hicolor/${s}x${s}/apps/trunkdeck.png"; done
rm -f "$HOME/.local/share/icons/hicolor/scalable/apps/trunkdeck.svg"
command -v update-desktop-database >/dev/null 2>&1 && update-desktop-database -q "$HOME/.local/share/applications" || true
read -r -p "Also delete your TrunkDeck systems, talkgroups, stream settings and voice codec (~/.trunkdeck)? [y/N] " b
[[ "${b:-n}" =~ ^[Yy] ]] && rm -rf "$HOME/.trunkdeck"
read -r -p "Also delete your recordings (~/TrunkDeck/recordings)? [y/N] " c
[[ "${c:-n}" =~ ^[Yy] ]] && rm -rf "$HOME/TrunkDeck/recordings" && rmdir "$HOME/TrunkDeck" 2>/dev/null
if [ -f /etc/udev/rules.d/52-trunkdeck-sdr.rules ] || [ -f /etc/modprobe.d/blacklist-trunkdeck-rtl.conf ]; then
  read -r -p "Remove the SDR USB rules too (needs sudo; keep them if other SDR apps use them)? [y/N] " d
  if [[ "${d:-n}" =~ ^[Yy] ]]; then
    sudo rm -f /etc/udev/rules.d/52-trunkdeck-sdr.rules /etc/modprobe.d/blacklist-trunkdeck-rtl.conf && sudo udevadm control --reload-rules
  fi
fi
rm -rf "$DEST"
echo "TrunkDeck removed."
