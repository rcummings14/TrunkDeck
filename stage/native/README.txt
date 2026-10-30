Native USB libraries used by TrunkDeck's engine.
  windows-x64/rtlsdr.dll   - RTL-SDR Blog rtl-sdr v1.3.6, official Windows x64 release build (libusb statically linked)
  linux-x64/librtlsdr.so   - built from the same v1.3.6 source (needs libusb-1.0, package "libusb1" on Fedora)
On Linux an installed system librtlsdr (sudo dnf install rtl-sdr) is preferred over this copy.
License: GNU GPL v2 or later. Source: ../../source/third-party/rtl-sdr-blog-1.3.6.tar.gz
and https://github.com/rtlsdrblog/rtl-sdr-blog

  windows-x64/libusb-1.0.dll - libusb 1.0.27, official release (MinGW64 build), used by TrunkDeck's own Airspy R2 driver.
  On Linux the system libusb-1.0 is used (Fedora: sudo dnf install libusb1).
  License: GNU LGPL v2.1 or later (../licenses/libusb-LGPL-2.1.txt). Source: ../../source/third-party/libusb-1.0.27.tar.bz2
