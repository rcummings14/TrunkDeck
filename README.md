# TrunkDeck

**A scanner-style P25 trunking monitor for Windows and Linux.** TrunkDeck follows a P25 trunked radio system's control
channel, decodes the voice calls (Phase 1 and Phase 2) with its own built-in engine, plays them, records them, and can
stream them to Broadcastify, OpenMHz or your own Rdio Scanner server - all from one window, no other radio software needed.

![TrunkDeck main window](docs/screenshots/monitor.png)

### [⬇ Download TrunkDeck (Windows & Linux)](https://github.com/rcummings14/TrunkDeck/releases/latest) &nbsp;·&nbsp; [▶ Watch the 7-minute tour](https://www.youtube.com/watch?v=iAd6VPXa0ZE)

> **Provided as-is.** TrunkDeck is a hobby project shared in the hope that it is useful. There is **no support and no
> warranty** of any kind, and issues or pull requests **may not be answered**. See [LICENSE](LICENSE).

If TrunkDeck is useful to you, you can support it here: [https://paypal.me/TrunkDeck](https://paypal.me/TrunkDeck)

## Features

* **Native P25 engine** (written in Java from the TIA-102 standards): Phase 1 control channel (C4FM, and LSM/CQPSK for
  simulcast with automatic selection), Phase 1 (IMBE) and Phase 2 TDMA (AMBE+2, both slots) voice, encryption and
  emergency flags, priority, talkgroup mute / lockout, radio-ID names (DSD+ `.radios` or CSV import).
* **P25 conventional** (untested): save a system of fixed channels (from RadioReference when it lists them, or by typing the
  name and the frequencies yourself, with an optional NAC). Press Start the same way as a trunked system. TrunkDeck
  listens on those channels and decodes the P25 voice. Encrypted calls are skipped. P25 conventional has not been tested
  on a real radio system yet. If something doesn't work, please report it on
  [GitHub Issues](https://github.com/rcummings14/TrunkDeck/issues).
* **Receivers:** one **Airspy R2** or **Airspy Mini** (about 8.8 MHz at 10 MS/s - often a whole site at once, many calls decoded in parallel;
  the Mini also offers 6 and 3 MS/s, the R2 also 2.5 MS/s) and/or one or more **RTL-SDR** dongles (a control dongle plus roaming voice dongles). Own drivers - no SDR# / SDRTrunk.
  New in 1.7.0 and **untested**: **SDRplay**, **HackRF One**, **LimeSDR** and **Ettus USRP** through SoapySDR - see
  [Supported receivers](#supported-receivers).
* **Big scanner display** with colour themes (Full color, SDS-style, amber, ... or your own colours, including the bars
  and the display border), glow, scan lines, backlight on calls, SCANNING ticker.
* **Control-channel signal meter** (1.7.2): the scanner display shows a signal meter on the control-channel row so you
  can compare sites and antennas.
* **Display only** mode: the window shrinks to just the scanner display (resizable, draggable, always-on-top option);
  double-click to get the full window back. Everything keeps running. A row of large buttons sits inside the display,
  along its bottom, and only on this window: **Mute all** (silence what you hear; decoding, the log, recordings and streams keep going),
  **Mute talkgroup** (mute the talkgroup on the display, or the latest call if the display is idle), **Hold** (stay on
  the talkgroup that is on the air until you tap again; not remembered after a restart), and a **Muted list** of
  talkgroups muted from that button. Tap one to unmute it. Talkgroups muted from the main window are not in that list.
* **Home network page:** from another computer on the same home network, a password-protected page that looks like the
  Monitor (scanner display, signal meter, active calls, talkgroups). Listen, start and stop, and mute. It does not
  have the Display only buttons. Turn it on from Setup & Help → Home network. For the home network only.
* **System tray:** close or minimize to the tray (or to Display only) - your choice.
* **Streaming / uploads** (optional): **Broadcastify Calls**, **Broadcastify Feeds** / any Icecast server (MP3),
  **OpenMHz**, **Rdio Scanner**. Live talkgroup assignment, status and retry on the Streams page.
* **Set up from RadioReference** (1.6.0): sign in with your own RadioReference Premium account, pick state → county →
  system, the site(s) and talkgroups (encrypted ones marked and unticked), and TrunkDeck creates the system with its
  control channels and talkgroup names. *Refresh* later merges new and renamed talkgroups and keeps your own settings.
  Needs a RadioReference app key (not in this repository - see [below](#radioreference-app-key)).
* **Shared talkgroup lists** (1.7.2): sites of the same radio system share one talkgroup list (matching WACN and
  System ID, or imported from the same RadioReference system). Names, favorites, mutes, record, priority, colours, and stream
  assignments stay in sync. Radio ID names stay per site.
* **Now-playing tags** on Broadcastify Feeds / Icecast (1.5.1): the talkgroup (optionally the radio) of the call playing
  is sent as the stream title, in step with the audio - your choice of format, an optional text between calls, and a
  *Send test tag* button. A separate short request: the audio connection is never touched.
* Recordings of every call, played back in the app (a live call pauses playback), call log, Active-calls table, control-channel decode meter, activity graph, per-call
  diagnostics, talkgroup CSV import (RadioReference format), Simple and Advanced views.
* **Talkgroup colours:** the Talkgroups page has a **Color** column. Pick a colour, or Default. The list itself keeps its
  usual colours (the cell shows the colour's name and a small sample). While that talkgroup is on the air, its name and
  number are drawn in that colour on the Monitor, Display only, and the home-network page. Default keeps the theme's
  text colour. Sites of the same system share the colour.
* **Recordings folder:** on the Recordings page, *Choose folder...* picks where new recordings are saved. The change is used
  right away, without restarting. *Use default* goes back to the usual folder in your home directory (`TrunkDeck/recordings`,
  which on Windows is under your user profile). Recordings already saved stay where they are and can still be played from
  the Recordings page and the call log. If that folder is missing or TrunkDeck can't save files there, a message explains
  it and new recordings go to the usual folder. The home-network page cannot change this folder.
* **Auto-start:** on the Systems page, *Auto-start when TrunkDeck opens* starts that system after a short countdown the next
  time TrunkDeck opens. Only one system can be set. You can press Cancel on the Monitor page. The home-network page can
  show the countdown but cannot change this checkbox.

| Display only | Streams | Colours |
|---|---|---|
| ![Display only](docs/screenshots/display-only.png) | ![Streams page](docs/screenshots/streams.png) | ![Display colours](docs/screenshots/colors.png) |

More: [Now-playing tags](docs/screenshots/now-playing-tags.png) · [Simple view](docs/screenshots/simple-view.png) · [Display only right-click menu](docs/screenshots/display-only-menu.png) · [Systems page](docs/screenshots/systems.png) · [Talkgroups page](docs/screenshots/talkgroups.png) · [Engine page](docs/screenshots/engine.png)

*All screenshots show a simulated signal and a fictional example system (made-up names, frequencies and IDs).*

## Install

Download the file for your system from the [Releases](../../releases) page.

### Windows 10/11 (64-bit)

1. Unzip **`TrunkDeck-1.7.4-windows.zip`**, open `TrunkDeck\windows` and double-click **`Install.bat`**
   (SmartScreen: *More info → Run anyway*). It downloads the Eclipse Temurin Java runtime into TrunkDeck's own folder
   (checksum-verified) and adds Start Menu and Desktop shortcuts. Installs for your user only, no admin rights needed.
2. **USB driver (once per receiver):** run [Zadig](https://zadig.akeo.ie), *Options → List All Devices*, pick the
   RTL-SDR (*Bulk-In, Interface (Interface 0)*, USB ID `0BDA 2838`) or the Airspy (`1D50 60A1`), choose **WinUSB**,
   *Replace Driver*, then replug the receiver.
3. Start **TrunkDeck** from the Start Menu.

Uninstall: *Settings → Apps → TrunkDeck*.

### Linux (Fedora, Ubuntu, Debian, Mint, Arch, Raspberry Pi OS 64-bit)

```bash
tar xzf TrunkDeck-1.7.4-linux.tar.gz
cd TrunkDeck/linux
./install.sh
```

The installer downloads the Java runtime (checksum-verified), adds an app-menu entry, a desktop shortcut and a
`trunkdeck` command, and offers to set up USB access (a udev rule for RTL-SDR and Airspy, and blocking the DVB-T driver
that grabs RTL-SDR dongles). Replug the receivers afterwards. The Airspy needs `libusb1` (`sudo dnf install libusb1`,
Debian/Ubuntu: `libusb-1.0-0`). GNOME needs the *AppIndicator* extension for the tray icon.

Uninstall: `~/.local/share/trunkdeck/uninstall.sh`.

### Supported receivers

| Receiver | Status | What you install besides TrunkDeck |
|---|---|---|
| **RTL-SDR** (RTL2832U) | Tested | Windows: WinUSB driver via Zadig. Linux: the installer's USB setup. (librtlsdr is bundled.) |
| **Airspy R2** | Tested | Windows: WinUSB driver via Zadig. Linux: USB setup + `libusb1`. (TrunkDeck's own driver. 10 or 2.5 MS/s.) |
| **Airspy Mini** | Tested | Same driver and same USB ID as the R2 (`1D50 60A1`). Windows: WinUSB via Zadig. Linux: USB setup + `libusb1`. Sample rates 10, 6 and 3 MS/s. |
| **SDRplay** RSP1A / RSP1B / RSP2 / RSPdx / RSPduo | **UNTESTED** (1.7.0) | The **SDRplay API 3.x** from [sdrplay.com](https://www.sdrplay.com) (its licence doesn't allow bundling) + SoapySDR with the SDRplay module |
| **HackRF One** | **UNTESTED** (1.7.0) | SoapySDR + HackRF module (Windows: also WinUSB via Zadig) |
| **LimeSDR** (USB / Mini) | **UNTESTED** (1.7.0) | SoapySDR + LimeSuite module |
| **Ettus USRP** (B2xx, N2xx, X3xx...) | **UNTESTED** (1.7.0) | SoapySDR + UHD module, then `uhd_images_downloader` once |

**UNTESTED** = new in 1.7.0 and not yet tried with real hardware (only with simulated receivers in `test/p25monitor/SoapyTests.java`).
The Engine page marks these receivers *(untested)* ([screenshot](docs/screenshots/engine-untested-receivers.png)). If one doesn't work, please open an
[issue](https://github.com/rcummings14/TrunkDeck/issues) with *Copy diagnostics* from the Engine page, the receiver model,
your OS and driver versions.

SoapySDR in short (details: *Supported receivers* in [stage/README.md](stage/README.md)) - none of it is bundled:
* **Windows:** [PothosSDR](https://downloads.myriadrf.org/builds/PothosSDR/) (`PothosSDR-2021.07.25-vc16-x64.exe`)
  contains SoapySDR and the HackRF, LimeSDR, USRP and SDRplay modules; SDRplay also needs the SDRplay API.
* **Debian/Ubuntu:** `sudo apt install libsoapysdr0.8 soapysdr-module-hackrf soapysdr-module-lms7 soapysdr-module-uhd`
* **Fedora:** `sudo dnf install SoapySDR soapy-uhd uhd` (HackRF / LimeSDR modules: radioconda or build from source).
* **SDRplay on Linux:** SDRplay API, then build [SoapySDRPlay3](https://github.com/pothosware/SoapySDRPlay3).

### First start

1. **Systems** page: **Set up from RadioReference** (needs a RadioReference Premium account and a build with an app key),
   or add your system by hand - its control channel(s) in MHz (RadioReference lists them; use the ones marked
   *c*). The preloaded *Example County* presets are **fictional** placeholders; delete them if you like.
2. **Talkgroups** page: import your talkgroup CSV (RadioReference export works as-is; `talkgroups_template.csv` is a template).
3. **Engine** page → *Voice codec*: click **Build from source…** once to build the JMBE voice codec on your PC (see the
   legal note below). Without it, calls are followed but silent.
4. Plug in the receiver(s) and press **START MONITORING**.

The full manual (every page, streaming setup, Airspy notes, troubleshooting) is [stage/README.md](stage/README.md); it is
also included in the download.

## Build from source

You need a **JDK 22 or newer** (`javac` and `jar` on the PATH) and `bash` (on Windows: Git Bash or WSL).

```bash
bash build.sh            # compiles src/ into build/trunkdeck.jar
bash build.sh test       # ... and runs the automated tests (some are skipped without test recordings / a display)
bash assemble_pkg.sh     # assembles pkg/TrunkDeck (app, installers, licences, source)
python3 make_zip.py      # writes dist/TrunkDeck-<version>-windows.zip, -linux.tar.gz and SHA256SUMS.txt
```

### RadioReference app key

RadioReference's web service needs an **app key**, issued to the app's developer. It is **not** in this repository and
must never be committed (`rr-app-key.txt` is in `.gitignore`). Without one, everything works except *Set up from
RadioReference*, which then says it isn't available in this build. TrunkDeck looks for the key in this order:

1. the environment variable `TRUNKDECK_RR_APP_KEY`;
2. a file `rr-app-key.txt` (just the key) next to the app, or in the settings folder (*Engine → RadioReference app key*
   writes it there);
3. a key built into the packaged app: `RR_APP_KEY=your-key bash build.sh` writes it (obscured) into `build/trunkdeck.jar`
   only - never into `src/`. Anyone can extract a key from a binary, so only do this for builds you distribute
   under RadioReference's terms for that key.

Each user signs in with their **own** RadioReference Premium account; RadioReference data is for personal use and must
not be mirrored or republished. TrunkDeck only contacts RadioReference while the wizard is open. Tests use a mock
server (`test/p25monitor/rr/MockRr.java`, fictional data); no key is needed to build or test.

Run without installing (Linux, with a system Java 22+): `bash pkg/TrunkDeck/linux/run.sh` after `assemble_pkg.sh`.
The bundled native libraries (`stage/native`) are prebuilt `librtlsdr` and `libusb`;
their source archives are in `stage/third-party`.

Optional test inputs (never committed, see `.gitignore`): public P25 sample recordings in `samples/`, your own
recordings via `-Dtrunkdeck.testdata=DIR`, your own DSD+ file via `-DradiosFile=FILE`, a JMBE jar via `-Djmbe=FILE`.
Tests that need them are skipped when they are missing.

## Legal

* **Only monitor and stream unencrypted traffic, and follow your local laws.** Receiving public-safety radio is legal in
  much of the US, but some states and countries restrict scanners (for example in vehicles) or the use or rebroadcast of
  what you hear. You are responsible for how you use this software. TrunkDeck does not decrypt encrypted calls.
* **Voice codec / patents:** TrunkDeck does **not** include a voice codec. On request it downloads the source of
  [JMBE](https://github.com/DSheirer/jmbe) (GPL-3.0) and compiles it on your computer, or uses a JMBE jar you choose.
  The IMBE and AMBE+2 vocoders it implements are subject to patents held by Digital Voice Systems, Inc.; check whether
  you need a licence where you live.
* Broadcastify, OpenMHz, Rdio Scanner, RadioReference, Airspy, RTL-SDR, SDRplay, HackRF, LimeSDR, Ettus and USRP are names of their respective owners;
  TrunkDeck is not affiliated with or endorsed by any of them.

## License

TrunkDeck is released under the [MIT License](LICENSE) - Copyright (c) 2026 TrunkDeck contributors.
Bundled third-party components keep their own licences (FlatLaf, librtlsdr, libusb, java-lame, JTransforms, ...);
SoapySDR, its modules and the SDRplay API are not bundled (you install them yourself):
see [NOTICE.txt](NOTICE.txt) and [stage/licenses/THIRD-PARTY.txt](stage/licenses/THIRD-PARTY.txt).
