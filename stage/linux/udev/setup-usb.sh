#!/usr/bin/env bash
# Run with sudo: installs the Airspy / RTL-SDR USB rules and blocks the DVB-T driver.
set -e
D="$(cd "$(dirname "$0")" && pwd)"
install -m 0644 "$D/52-trunkdeck-sdr.rules" /etc/udev/rules.d/52-trunkdeck-sdr.rules
install -m 0644 "$D/blacklist-trunkdeck-rtl.conf" /etc/modprobe.d/blacklist-trunkdeck-rtl.conf
udevadm control --reload-rules && udevadm trigger
modprobe -r dvb_usb_rtl28xxu 2>/dev/null || true
echo "Done. Unplug and re-plug your SDR dongles."
