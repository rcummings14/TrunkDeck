# TrunkDeck 1.6.0 — P25 Phase 1 and Phase 2 trunking monitor (Windows & Linux)

TrunkDeck is a scanner-style dashboard for **P25 trunked radio systems** (Phase 1 control channel; Phase 1 and Phase 2 voice). Type in a control channel, load your
talkgroups from a CSV file, plug in an Airspy R2 or one or two RTL-SDR dongles, press **START MONITORING**, and watch (and hear) the system
live: talkgroup, name, frequency, radio ID, call timer, encryption and emergency flags, a control-channel decode meter, an
activity graph, and a table of **every call that is on the air right now**.

TrunkDeck decodes everything itself with the **TrunkDeck engine**, its own P25 decoder written in Java from the TIA-102 standard.
It talks to your Airspy R2 and RTL-SDR dongles directly. No other radio program is needed.

**New in 1.6.0 – set up from RadioReference**
* **Systems → Set up from RadioReference** (also *Setup & Help → Tools*, and offered on a new install) builds a system
  from the RadioReference database in five steps: **Sign in** with your own RadioReference **Premium** account →
  **System** (state → county → trunked system, a ZIP code, or a RadioReference system ID / P25 system ID) → **Site**
  (the obvious one is ticked when there is only one; tick several and TrunkDeck tries their control channels in turn) →
  **Talkgroups** (grouped by category with a tick box per category; everything that isn't encrypted is ticked;
  encrypted ones are marked and unticked, and encrypted calls are still skipped) → **Finish**. You get the control
  channels (primary first, then alternates), NAC, system ID / WACN, and every chosen talkgroup with its alpha tag,
  description, tag, category and mode.
* **Systems → Refresh from RadioReference** (systems set up this way) downloads the system again and merges it: new
  talkgroups are offered, renamed ones are updated (untick *Update talkgroup names* to keep your own names), your
  favourites, mutes, recording, priorities and stream assignments are kept, and nothing is deleted. A summary lists
  what changed. On the system you are monitoring the talkgroup changes apply at once; if the control channels changed,
  **Restart to apply** appears in the top bar for you to press when it suits you – feeds and uploads are never
  restarted or interrupted by a refresh.
* TrunkDeck only talks to RadioReference while the wizard is open (no background checks). Place lists (countries,
  states, counties) are kept on your computer for 30 days; system data is fetched fresh each time.
* **Remember me** is off by default. When ticked, the password is saved in the settings folder, scrambled and readable
  only by your user account – this keeps it out of plain sight but is not strong protection against someone who can
  read your files. *Engine → RadioReference app key → Forget saved RadioReference password* removes it.
* It needs a **RadioReference app key** for TrunkDeck (see *Set up from RadioReference* below). A build without one
  shows "RadioReference setup isn't available in this build" and everything else works as before.

**New in 1.5.1 – "now playing" tags on Broadcastify Feeds / Icecast**
* When a call starts playing on a **Broadcastify Feed** or **Icecast** stream, its talkgroup is sent to the server as the
  stream title ("now playing"), the way SDRTrunk and ProScan tag Broadcastify feeds: an Icecast 2 metadata update
  (`/admin/metadata?mode=updinfo`, the feed's own server, port, mount and source password, `charset=UTF-8`). Listeners
  see it in players that show stream titles (e.g. VLC, Winamp, scanner apps such as ScannerRadio; whether a given
  web player shows it is up to that player).
* It follows the audio: a call is tagged when it actually starts playing on the feed, so with calls waiting in the
  feed's queue the title changes when each one comes on, not when it was received. Only calls sent to that feed are
  tagged (talkgroup assignments, mutes and *Also send unlisted* apply as for the audio); encrypted calls are never sent.
* Streams → your feed → **Now playing**: *Send tags (now playing)* (on), **Tag format**: *Talkgroup name* (default),
  *Talkgroup name + radio name*, *Talkgroup ID + name* or *Custom…* with `{tg}` `{tgname}` `{radio}` `{radioname}`
  `{group}` `{tag}` (empty ones are left out with their separators); **Between calls**: a text sent after 3 s without
  a call (e.g. *Scanning...*; empty, the default = the last call's tag stays); **Send test tag** sends a test title now
  and shows the server's answer. Changes apply when you press *Save stream*, without reconnecting.
* Safe for the audio: each tag is its own short request on its own thread with short timeouts; the audio connection is
  never touched, slowed or reconnected because of a tag, and a slow or failing server only means no title (logged once).
  At most one update every 1.5 s; when calls come faster the newest wins. On by default, existing feeds included.
* Talkgroup name = the alpha tag from your talkgroup list (else its description, else *TG 1234*); radio name = the
  name from *Radio IDs*, else the radio ID once it is known. Nothing else is sent.

**New in 1.5.0 – Display only, close / minimize choices, display border colour**
* **Display only** (header button **Display only**, or **Ctrl+D**): the same window shrinks to just the big scanner
  display (top bar, talkgroup name, TG line, label bar), with its colours, glow, scan lines, dark-while-scanning /
  lit-by-a-call backlight and the SCANNING ticker. Resize it and the display scales with it; drag it by the display.
  Back to the full window: **double-click** the display, **Esc** or **Ctrl+D**. **Right-click** the display:
  *Full window*, *Always on top* (check box), *Mute speaker* (the Monitor page's mute, kept in sync), *Minimize to
  tray* (greyed out without a tray) and *Quit*. Size, position and Always on top are remembered
  (`ui.displayOnly.bounds`, `ui.displayOnly.onTop`); if that place is no longer on a screen it is put back on one.
  Monitoring, the speaker, recordings, feeds and uploads keep running — nothing restarts or reconnects.
* Monitor page → Listening: **Close (X)** and **Minimize** each offer *Quit (normal)* / *Taskbar (normal)*,
  *System tray* and *Display only*. Defaults are as before (X → System tray, Minimize → Taskbar); settings from
  1.4.7–1.4.9 carry over (`ui.trayClose` / `ui.trayMinimize` are still written for older versions). The tray icon's
  menu has **Display only** too. Without a system tray *System tray* is greyed out; Display only still works. In
  Display only, Alt+F4 hides to the tray if X is set to System tray, otherwise it quits.
* **Colors… → Frame → Display border**: the frame around the display (full window and Display only) in your colour,
  or *None*. *Standard* (the default for every preset) is the frame exactly as before.
* Always on top needs a window manager that supports it (Windows, GNOME, KDE do; it is greyed out otherwise).

Older release notes are in the GitHub release history.

## What you need

| | |
|---|---|
| **Receiver(s)** | **Either** one **Airspy R2** (about 8.8 MHz at 10 MS/s, which covers a whole site on many systems - control channel and every voice channel at once), **or** one or two **RTL-SDR** dongles (RTL2832U, e.g. RTL-SDR Blog V3/V4; about 2 MHz each: one for the control channel, one that moves to where the other calls are). Both can be mixed: the Airspy takes the site, RTL dongles roam for anything outside its window. |
| **Antenna** | One that suits your system's band (e.g. UHF 450–470 MHz or 800 MHz). |
| **Computer** | 64-bit Windows 10/11, or 64-bit Linux (Fedora, Ubuntu, Mint, Debian, Arch, Raspberry Pi OS 64-bit). 4 GB RAM or more. In testing, 8 calls at once used under half of one CPU core. |
| **Java** | Nothing to install. The installer downloads the Eclipse Temurin Java 25 JDK into TrunkDeck's own folder (it doesn't touch any Java you already have). |
| **Internet** | During install (about 140 MB on Linux, 200 MB on Windows), and once on first start to fetch the voice codec's source code. |

**Airspy R2:** supported natively since 1.4.0 (TrunkDeck's own driver, no SDRTrunk or Airspy tools needed) - see *Airspy R2*
below. The Airspy Mini has the same USB ID and should work at its own rates but is untested. **HackRF:** not supported.

---

## Pick your system

Download **TrunkDeck-1.5.1-windows.zip** (Windows) or **TrunkDeck-1.5.1-linux.tar.gz** (Linux) from the GitHub
Release and unpack it. Inside the `TrunkDeck` folder you'll find (each download has only its own installer folder):

```
TrunkDeck/
├─ windows/      ← Windows installer (Install.bat)
├─ linux/        ← Linux installer (install.sh)
├─ app/          ← the TrunkDeck program (used by both installers)
├─ source/       ← TrunkDeck source code, tests, build script, third-party source archives
├─ licenses/
├─ talkgroups_template.csv
├─ README.md     ← this file
└─ START-HERE.txt
```

### Windows — install

1. Open the `windows` folder and double-click **`Install.bat`**.
   If Windows SmartScreen pops up, click **More info → Run anyway**. (The script is plain text, so you can open it in Notepad and read it first.)
2. Wait while it downloads the Java runtime (a few minutes; the download is checked against its published SHA-256 checksum). It then creates:
   * a **Start Menu** entry: *TrunkDeck* (plus *Uninstall TrunkDeck*)
   * a **Desktop** icon
   * an entry in **Settings → Apps**, where you can uninstall it like any other app.
   It installs for your user only, into `%LOCALAPPDATA%\Programs\TrunkDeck`. No admin rights needed.
   *Windows on ARM:* there is no ARM build of the Java runtime for Windows, so the x64 one runs under Windows' built-in emulation (slower; untested).
3. **USB driver (one time, for each receiver):** Windows needs the *WinUSB* driver for SDRs.
   1. Download **Zadig** from <https://zadig.akeo.ie> and run it.
   2. Plug in each **RTL-SDR**. Click *Options → List All Devices*, pick **Bulk-In, Interface (Interface 0)** (it might be named *RTL2838UHIDIR*; the USB ID shows **0BDA 2838**), choose **WinUSB**, and click *Replace Driver*. With two dongles, do this for each one. They may show up with the same name, so install it on each entry, and repeat if you plug a dongle into a different USB port.
   3. **Airspy R2:** plug it in; in Zadig pick **AIRSPY** (USB ID **1D50 60A1**), choose **WinUSB**, *Replace Driver* (or
      *Install Driver*). If you already used the Airspy with SDR# on this PC it usually has WinUSB already - check that Zadig shows
      *Driver: WinUSB* for it.
   4. Unplug the receivers and plug them back in.
   *(Don't pick your keyboard, mouse or other USB devices in Zadig.)*
   TrunkDeck ships its own copies of the RTL-SDR library (`app\native\windows-x64\rtlsdr.dll`) and of libusb for the Airspy
   (`app\native\windows-x64\libusb-1.0.dll`), so there's nothing else to install.

To uninstall: use **Settings → Apps → TrunkDeck → Uninstall**, or *Start Menu → TrunkDeck → Uninstall TrunkDeck*. It asks whether to keep your settings and recordings.

### Linux (Fedora and others) — install

1. Open a terminal in the `linux` folder (in Files: right-click → *Open in Terminal*) and run:
   ```bash
   ./install.sh
   ```
   (If it says *permission denied*, run `bash install.sh`.)
2. It downloads the Java runtime (checked against its SHA-256 checksum), then creates:
   * an **app-menu entry** (*TrunkDeck*, under Activities / Show Apps)
   * a **desktop shortcut** (`~/Desktop/trunkdeck.desktop`; GNOME on Fedora only shows desktop icons if you have a desktop-icons extension)
   * a `trunkdeck` command in your terminal
   It installs for your user only, into `~/.local/share/trunkdeck`.
   *No internet on that PC?* Download the Temurin file the installer prints (`OpenJDK25U-jdk_…_linux_hotspot_25.0.5_8.tar.gz`) elsewhere and put it next to `install.sh` or in `~/Downloads`; the installer uses it (and still checks the checksum).
3. **USB permissions (one time):** the installer asks *"Set this up now? [Y/n]"*. Press Enter and type your password.
   This installs a **udev rule** so your user can open **RTL-SDR** dongles (`0bda:2838` / `0bda:2832`) and the **Airspy R2**
   (`1d50:60a1`), and it **blocks the
   DVB-T TV driver** (`dvb_usb_rtl28xxu`) that otherwise grabs RTL-SDR dongles. Afterwards, unplug the dongles and plug them back in.
   Skipped it? Run `sudo ~/.local/share/trunkdeck/udev/setup-usb.sh` later.
   TrunkDeck uses your system's `librtlsdr` if it's installed (`sudo dnf install rtl-sdr`); otherwise its bundled copy (needs `libusb1`, already on almost every system).
   The Airspy driver uses the system libusb too: on Fedora `sudo dnf install libusb1` (Debian/Ubuntu: `libusb-1.0-0`).
   You do **not** need the `airspy` package or `airspy_rx`; if you have them, just make sure no other program has the Airspy open.

To uninstall, run `~/.local/share/trunkdeck/uninstall.sh`. It asks before deleting your settings, recordings or the USB rules.

---

## Airspy R2

One Airspy R2 at **10 MS/s** receives about **8.8 MHz** at once. TrunkDeck splits it into 312.5 kHz sub-bands (a polyphase filter
bank) and runs the control channel and **every voice channel inside the window at the same time** - no roaming, no missed calls
inside the window. If a site's control and voice channels all fit within about 8.8 MHz, one Airspy covers the whole site.

* **Setup:** Windows - Zadig step 3.3 above (WinUSB for *AIRSPY*). Fedora/Linux - the USB setup (udev rule for `1d50:60a1`) and
  `libusb1`. No other Airspy software is needed.
* **Engine → Tuners** lists each Airspy with its serial number: **Role** (*auto*/*control*: it takes the control channel and the
  whole window; *voice*: a second wideband receiver that roams for calls outside the first; *off*), **sample rate** (10 MS/s, or
  2.5 MS/s for slower PCs), **gain** (*Linearity 0-21* - recommended, resists strong nearby signals - or *Sensitivity 0-21*; higher =
  more gain; default Linearity 16), **Bias** (4.5 V on the antenna jack for an LNA - off by default; only turn it on if your
  antenna system needs it), and an optional fixed **centre MHz** (leave empty: automatic).
* **Automatic centre:** TrunkDeck starts centred on the control channels, re-centres as soon as the site's channel plan
  (IDEN_UP) is known to cover its voice band, and later on the frequencies the grants actually use (re-centring keeps running
  calls inside the window and doesn't interrupt them). The chosen centre is shown on the Engine page.
* **With RTL dongles:** plug them in too and leave their role on *auto*/*voice* - they roam for calls outside the Airspy's window.
* **Diagnostics** (Engine page) shows a *Wideband T1* line: sub-bands in use, decoders, CPU, and the **USB counters** (blocks
  received / dropped, milliseconds of samples lost). Drops while the processing ran but was too slow are flagged "THE PC IS NOT
  KEEPING UP"; drops while the processing was stopped or restarting are flagged as such (1.4.3: not a CPU problem). The
  *Processing T1* line shows processing restarts and contained decoder errors with the last error and its stack.
* **CPU:** on the 8-core test PC the whole Airspy chain at 10 MS/s with the control channel and 10 voice channels used about 0.7 of
  one CPU core (2x faster than real time, spread over several cores).

---

## First start (both systems)

1. Open **TrunkDeck**. The fictional **Example County - Site 001** system is selected (see *The example system* below) - add your own system on the **Systems** page first.
2. Plug in your dongle(s) **before** pressing START, then press **START MONITORING**.
3. **The first time only**, TrunkDeck needs the **JMBE voice codec**. On the *Engine* page, under *Voice codec*, click **Build from source…**.
   It shows the patent notice, downloads the JMBE source code from GitHub and compiles it on your PC (about a minute; the
   Java runtime the installer downloaded includes the compiler). Already have a `jmbe-*.jar`? Use **Choose jar…** instead.
   Updating from 1.1.x? Your existing codec is found automatically.
4. When the control channel is decoding, the **CONTROL CHANNEL** meter turns green (**LOCK, nn msg/s**), the status pill says **● LIVE**, and calls start to appear.
5. Set each dongle's **PPM** and **gain** on the *Engine* page (see *PPM calibration tips*). The engine measures the error for you and offers an **Apply** button.

### How two dongles cover a site (and what they can't)

Each RTL-SDR runs at 2.4 MS/s. After leaving room for the filter edges and the dongle's centre spike, about **±1.0 MHz around
its centre** is usable, so one dongle covers about 2 MHz. Every call granted on a frequency inside a dongle's window is decoded by
that dongle, all at once, up to *Max simultaneous calls* (Systems page).

* **Control dongle:** stays on the active control channel. A site uses one control channel at a time; the others are alternates it
  switches to. TrunkDeck places this dongle so the active control channel, and the alternates if they fit, are inside its window, and
  then covers as many of the site's busy voice frequencies as possible. If the control channel goes quiet for 6 seconds, it hunts
  through the listed control channels, retuning only if a missing one is outside its window. It also re-centres every couple of minutes
  if that would cover clearly more traffic, but only when it has no calls.
* **Roaming dongle:** goes where the calls are. It picks its centre from recent traffic (busy frequencies count more, and talkgroup
  priority decides which waiting call it moves for). **It is never retuned while it has a call to finish**: a call on a frequency
  neither dongle covers waits; if the call ends before the roaming dongle is free, it is logged as
  **MISSED … out of tuner range** with its frequency.
* **With one dongle**, it does both jobs: it never leaves the control channel, and decodes the voice calls inside its own window.

**Example with two dongles:** a site with frequencies 851.0125, 851.2625, 851.5125 (control), 851.7625 (control), 853.4125 and
853.6625 MHz: the four 851 MHz frequencies fit in the control dongle's window and the two 853 MHz ones in the roaming dongle's
window, so **all six can be decoded at the same time**.
Sites whose voice frequencies are spread over more than about 4 MHz will have calls that two RTL-SDRs can't reach at the same
time; those show as MISSED. A third dongle helps (set its *Role* to *voice*).

*Max simultaneous calls* (Systems page, default 20, up to 32) limits how many calls are decoded at once. Calls over the limit are logged as **MISSED … call limit reached**.

---

## Using TrunkDeck

**Top bar:** pick the **SYSTEM**, see the status (**STOPPED / STARTING / LIVE**), and use **START / STOP**.
Talkgroup and stream edits take effect **immediately** and are saved automatically (a green **✓ Saved** shows briefly).
Only a few *system* settings (Systems page: control channels, NAC, demodulator) are read
when the radio engine starts; after changing one, an orange **Restart to apply** button appears. It restarts the radio part
in a few seconds - your feeds stay connected (listeners hear silence for that moment) and uploads in progress finish.

| Page | What it does |
|---|---|
| **Monitor** | The scanner display (the call on the speaker: big alpha tag, talkgroup, category/tag, frequency, radio ID, call timer, ENC / EMERGENCY / FAV / MUTED badges; a dimmed SCANNING when no call is playing), control-channel meter, activity graph). Below it: the **Active calls** table, the live call log, **Hot talkgroups**, the **Speaker** choice, and quick buttons for the selected talkgroup (**Favorite, Mute, Add to list, Replay last**). **What to play:** *Everything*, *Only talkgroups in my list*, or *Favorites only*. |
| **Talkgroups** | Your list for the selected system. **Import CSV**, **Export CSV**, **Save CSV template**, search box, category filter, *Favorites* / *Heard* filters. Tick ★ (favorite), **Mute** and **Rec** (record) per talkgroup. Edit names, categories and **Priority** (1 = most important, 100 = normal) in the table. Select rows (Ctrl/Shift-click or Ctrl+A) to favorite, mute, record or assign streams in bulk. |
| **Systems** | Add or edit systems: name, site, **control channel(s) in MHz**, demodulator (Auto / C4FM / LSM for simulcast), optional NAC/WACN/System ID/RFSS/Site, **Max simultaneous calls**, and *Record every call*. **Add preset** brings back the fictional example presets. |
| **Streams** | Broadcastify Calls, Broadcastify Feeds and Icecast. See below. |
| **Recordings** | Recorded calls, one file per call (talkgroups with **Rec** ticked, or every call). Double-click to play: WAV plays inside TrunkDeck, MP3 opens in your system's player. Volume slider and *Open folder* button. |
| **Engine** | Dongles (role, PPM, gain, bias-T), live site info and tuner windows, voice codec, voice-call diagnostics, decoded control-channel messages, and *Test with an I/Q recording*. See *Engine page*. |
| **Setup & Help** | Checklist (dongles, voice codec, system chosen), detected dongles, engine log, and a **60-second demo** that shows the display with made-up calls, so you can try it without a radio. |

### The Active calls table (Monitor page)
One row per call on the air, refreshed four times a second:

| Column | Meaning |
|---|---|
| ▶ / ● | ▶ = the call on the speaker (lit while its audio is actually playing). ● = being recorded. |
| TG, Alias | Talkgroup number and name from your list. |
| Freq, Tuner | Voice frequency and the dongle decoding it (T1 = control dongle, T2 = roaming dongle). |
| State | PLAYING, VOICE (decoding, not on the speaker), HANG (between transmissions), LISTENING (waiting for the next transmission), ENCRYPTED, or WAITING FOR TUNER. |
| Time, Level, Bit errors/frame | Call duration, signal level (dB of full scale), and average corrected bits per voice frame (0–1 is clean). |

Above the table: how many calls are decoding, how many are waiting for a tuner, and the speaker mode.

### Entering a control channel
On **Systems**, type the frequency in MHz, e.g. `851.0125`. Add the alternate control channels separated by commas,
e.g. `851.0125, 851.5125`. You'll find control channels on RadioReference: open the system page, look at the *Sites* table,
and use the frequencies marked **c** / *Ctrl*.

### Set up from RadioReference (1.6.0)
With a **RadioReference Premium** account, TrunkDeck can fill in a system for you: **Systems → Set up from
RadioReference**. Sign in, choose your state and county (or type a ZIP code, or the system ID at the end of the system's
page address on the RadioReference website, e.g. `…/sid/1234`), pick the system, the site(s) you can receive and the talkgroups, and press
**Create system**. Later, **Refresh from RadioReference** updates it without touching your own talkgroup settings.

* Use your own account: RadioReference's terms allow its data for your own use only – please don't share, mirror or
  republish it. TrunkDeck asks RadioReference only while the wizard is open.
* Errors are explained in plain words: wrong username or password, no active Premium subscription, a missing or
  rejected app key, no internet connection, or a message from RadioReference.
* TrunkDeck decodes P25 systems only; other system types (EDACS, Motorola Type II, DMR …) are listed but can't be
  imported.

**The RadioReference app key.** RadioReference gives each *app* (not each user) a key for its web service. The key is
not part of TrunkDeck's source code. TrunkDeck looks for it in this order:
1. the environment variable `TRUNKDECK_RR_APP_KEY`;
2. a file `rr-app-key.txt` (just the key) next to the app (the install folder or its `app` folder), or in the
   TrunkDeck settings folder (Windows `%APPDATA%\TrunkDeck`, Linux `~/.trunkdeck`) – *Engine → RadioReference
   app key → Save key* writes this file for you;
3. a key built into the app when it was packaged (`RR_APP_KEY=... ./build.sh`).
Without a key the wizard just says that RadioReference setup isn't available in this build.

*Adding your key on Windows, the easy way:* start TrunkDeck → click **Advanced** at the top → **Engine** page → card
**RadioReference app key** → paste the key into the box → **Save key**. The line above the box turns green
("Key found"). Then **Systems → Set up from RadioReference**. *Using the file instead:* open Notepad, paste only the
key, and save it as `rr-app-key.txt` in `%APPDATA%\TrunkDeck` (type that into the File Explorer address bar) – or
next to the app in `%LOCALAPPDATA%\Programs\TrunkDeck`. Restart TrunkDeck afterwards. (Hidden file extensions making it
`rr-app-key.txt.txt`, or Notepad's "Unicode"/"UTF-8 with BOM" encodings, are handled.) Never share the key or post it
online.

### Talkgroup CSV files
TrunkDeck reads almost any talkgroup CSV. It detects these columns automatically (upper/lower case and spaces don't matter):

| Column | Also accepted as |
|---|---|
| **Decimal** (required, or Hex) | DEC, TGID, TG, Talkgroup, ID |
| **Hex** | TG Hex |
| **Alpha Tag** | Alpha, Name, Alias, Label |
| **Mode** | `D`, `T` (TDMA), `A`; `DE`/`TE` = encrypted; `De`/`Te` = sometimes encrypted |
| **Description** | Desc, Comment, Notes |
| **Tag** | Service Tag, Service, Type (e.g. *Law Dispatch*) |
| **Category** | Group, Agency, Department, Subcategory |
| *Favorite, Mute, Record, Priority, Streams* | optional (Yes/1/X = on; Streams separated by `;`) |

Comma, semicolon or tab-separated files all work, including files saved from Excel. A file without a header row is read
in RadioReference order (Decimal, Hex, Alpha Tag, Mode, Description, Tag, Category).
`talkgroups_template.csv` shows the format. **Merge** keeps your favorites and mutes when you re-import an updated list.

*Where to get the talkgroups:* RadioReference premium subscribers can download a system's talkgroups as CSV.
Without premium, you can copy the talkgroup table from the RadioReference system page into a spreadsheet and save it as CSV.
(TrunkDeck doesn't ship RadioReference's talkgroup database, because their terms don't allow redistributing it.)

### Radio ID names
*Talkgroups* page -> **Radio IDs...** opens the list of named radios for the selected system: search, **Add / edit**, **Remove**,
**Import** and **Export CSV**. A name is shown as `NAME (ID)`; radios without a name show just their ID.

* **DSD+ `.radios` files** (DSD+ 2.x "radio records"): only `P25` lines whose network ID matches the system's WACN and
  System ID (Systems page; the example presets are `A4321.2D5`) are imported. If a line has a *radio alias* it is used, otherwise
  its *P25 talker alias*. A leading `*` is removed. DMR, NXDN, Fusion and other P25 systems are skipped and counted. If the system
  has no WACN / System ID set, TrunkDeck asks which network in the file to import.
* **CSV:** `radio_id,alias` with or without a header row (also `Radio ID`, `RID`, `Unit`, `Name`, `Alias`, `Alpha Tag`;
  comma, semicolon or tab). TrunkDeck's own export adds a `source` column (`manual` = typed by you, `import`), which is read back.
* **Merge rules:** new radios are added; imported names are updated by a newer import; a name **you typed** is kept unless you
  pick **Overwrite** in the question that appears when the file has a different name for it. Radio IDs must be 1-16777215.
* Stored in `systems/<system>/radios.csv` in your settings folder (`~/.trunkdeck`, Windows `%APPDATA%\TrunkDeck`), so it moves
  with your settings. Each system has its own list.
* **OpenMHz:** off by default. Tick *Send radio names* on an OpenMHz stream to put the names in the upload's `source_list`
  `tag` field (the same field trunk-recorder fills from its unit tags); they then become public on openmhz.com. Broadcastify
  Calls has no field for names in its upload API, so only the radio ID is sent there (unchanged).

### Listening
All current calls are decoded and recorded; the **Speaker** box on the Monitor page chooses what you hear:
* **One call at a time (priority)** (default): the speaker stays on a conversation through its replies (hang time, default 2.5 s),
  then moves to the best call still on the air. A call with a higher priority (lower number in the *Priority* column; favorites
  first) takes over straight away.
* **Mix all calls:** every decoded call is mixed together. (The big display then doesn't follow a single call; use the Active calls table.)
* **Hold one talkgroup:** select a call in the Active calls table (or a talkgroup), then choose *Hold*. Only that talkgroup is played; everything else is still recorded and streamed.
* **Queue all calls (one after another)** (1.4.2): nothing is missed - every followed call is played in the order the calls started,
  one at a time, straight after each other; silences inside a call are skipped, so a backlog catches up. The speaker can then be
  behind live: the Active calls title shows `SPEAKER: QUEUE n WAITING +s`, the big display shows the call being played with a
  `QUEUED +s` badge. The queue holds at most the *Speaker queue* limit (Engine page, default 30 s, 5 s - 10 min) of audio, in
  memory only; beyond it the oldest waiting audio is skipped (engine log: `Speaker queue: skipped ...`). Talkgroup priority still
  decides which calls get a tuner; it does not reorder the queue. Busy sites with many calls at once will skip a lot - the limit is
  what keeps the speaker close to live.

Also:
* **Mute** a talkgroup to skip it (it's still logged). **What to play:** *Only talkgroups in my list* or *Favorites only* skips the rest.
  Muted and skipped talkgroups aren't decoded, recorded or streamed.
* **Record** talkgroups (Rec column), or every call (*Systems → Record every call*, or *Engine → Record every followed call*).
  Each call is its own file, named `date_time_TrunkDeck_TO_<talkgroup>_FROM_<radio>.mp3`, in `~/TrunkDeck/recordings`
  (Windows: `%USERPROFILE%\TrunkDeck\recordings`). Updating from 1.1.x keeps your old recordings folder (see *Updating*).
* Changes to mute, favorites, priority, record, streams and *what to play* take effect **immediately** - no Apply, no restart.
  Muting a talkgroup that is playing silences it at once (its queued audio too); a talkgroup un-muted mid-call is followed
  from that moment. The speaker mode changes immediately too.

### Streaming to Broadcastify, OpenMHz or Rdio Scanner (optional)
On **Streams**:
* **Add Broadcastify Calls** – enter the **API key** and **System ID** from your Broadcastify Calls account (*My Calls* → your node).
  **Test credentials** checks them with Broadcastify's documented test option (no call is uploaded; the answer is shown as received).
  *End of call* (default 0.8 s) is how long a transmission may be silent without an end-of-transmission signal before it is
  uploaded; lower is faster, but a short fade could then split one transmission in two. The *Status* line shows uploaded /
  duplicate / failed counts, the duplicate rate and the average timing; *Recent uploads* lists the last 12, for example
  `TG 1001 4.3 s OK - end detected +180 ms (terminator), upload started +0 ms later, registered +240 ms, done +410 ms after the last voice`.
  *Registered* is the moment that counts for "already received".
* **Add OpenMHz** – first create your system on [openmhz.com](https://openmhz.com) (sign in, *Add System*). The system's admin
  *Config* page shows its **short name** and **API key**: paste both here. Leave *Upload server* at `https://api.openmhz.com` unless that
  Config page shows another one. **Test connection** checks that the server answers and whether your system is listed (a new
  system only appears in OpenMHz's public list once it has calls). OpenMHz has no way to check an API key without uploading, so
  **Upload test call…** sends a 2-second tone on a talkgroup number you choose (listeners will see it). After that, the *Status*
  line shows uploaded / failed / skipped counts and the last error.
* **Add Rdio Scanner** – for your own [Rdio Scanner](https://github.com/chuot/rdio-scanner) server (see *Setting up Rdio
  Scanner* below). Enter the **Server URL** (`http://localhost:3000` on the same PC, or `http://<its address>:3000`), an
  **API key** and the **System ID** from its admin page. **Test connection** checks that Rdio Scanner answers at that address
  (it adds nothing; Rdio Scanner logs one harmless "Incomplete call data" line for it). Rdio Scanner only checks the key when a
  call arrives, so **Upload test call…** sends a 2-second tone on a talkgroup you choose. *Send talkgroup names* (on) sends each
  talkgroup's alpha tag, description, category and tag (Rdio Scanner uses them when it creates the talkgroup); *Send radio
  names* (off) sends the names from *Radio IDs* with the radio IDs (Rdio Scanner 6.6.3 ignores them; the next version stores them).
* **Add Broadcastify Feed** – enter the **Server host, Port, Mount point, Password** and **Feed ID** from your feed's *Technicals* page. Keep the bitrate at 16 kbps (TrunkDeck sends MP3 at 16000 Hz, mono, constant bitrate, like SDRTrunk). Use *Send test tone* to check the speed on your feed's page.
* **Add Icecast** – any Icecast 2 server (host, port, mount, source user, password).
* Feeds and Icecast send **now-playing tags** (the talkgroup of the call playing) as the stream title – see *New in 1.5.1*. Turn them off with *Send tags (now playing)*.

Then choose which talkgroups go to each stream:
* **Assign by category / tag…** – tick categories or tags from your CSV (for example everything tagged *Fire Dispatch*). Encrypted talkgroups are skipped by default.
* **Pick talkgroups…** – click talkgroups one by one.
* On the Talkgroups page, select rows → **Streams…**, or double-click the *Streams* cell.
* *Also send talkgroups that are NOT in my list* – sends unknown talkgroups too.

How calls are sent:
* **Broadcastify Calls:** every transmission is uploaded on its own as soon as it ends (see *New in 1.3.2*), as a 22050 Hz
  16 kb/s MP3 with its talkgroup, radio ID, frequency and real start time. Transmissions shorter than *Minimum call* (default
  1 s) are skipped. Concurrent calls are separate uploads; nothing is mixed.
* **OpenMHz:** every call is uploaded on its own as an MP3 when it ends (calls shorter than the stream's *Minimum call*,
  default 1 second, are skipped). Concurrent calls are separate uploads; nothing is mixed. OpenMHz uploads also
  carry the radio IDs that talked during the call and when each started (shown on OpenMHz), the emergency flag, and the voice
  error count. If OpenMHz can't be reached or answers with a server error, the upload is retried after 5 s, 15 s, 45 s, 2 min
  and 5 min, then given up. A wrong API key or short name is not retried (fix it and press *Save stream*; it applies at once).
  If your OpenMHz system is set to ignore unknown talkgroups, those calls are counted as *skipped*.
  OpenMHz accepts `.m4a` (AAC) or `.mp3` files. TrunkDeck sends MP3, because there is no AAC encoder that can be built into a Java
  program without extra native software. That needs nothing extra on your computer.
* **Rdio Scanner:** every call is uploaded on its own as an MP3 (8 kHz, 16 kb/s, the same audio as OpenMHz) the moment it
  ends, to `/api/call-upload`, with its talkgroup, frequency, voice error count, the radio IDs that talked and when, patches,
  and the system's name. Rdio Scanner converts it to AAC itself when it has ffmpeg. Calls shorter than *Minimum call* are
  skipped. Each Rdio Scanner stream has its own low-priority thread, so it never holds up Broadcastify Calls or OpenMHz. If the
  server can't be reached or answers with a server error, the call is retried after 3 s, 10 s, 30 s, 1 min, 2 min and 5 min
  (about 9 minutes), then given up and logged. A refused API key or a wrong address is not retried; the *Status* line says
  what to fix.
* **Feeds / Icecast:** one continuous MP3 stream per feed (16000 Hz mono, 16 kb/s constant bitrate, as SDRTrunk), connected from START MONITORING until you press STOP (talkgroup edits, stream edits that don't touch its server settings and *Restart to apply* keep it connected), with real-time silence between calls; it reconnects by itself after a drop (1, 2, 5, then 10 s). Each feed carries only its own talkgroups.
  When two of a feed's calls overlap, the second one is **queued and played right after the first** (so it never sounds
  garbled); a queued call that would start more than 30 seconds late is skipped on that feed (it's still recorded and uploaded).
  On the *Engine* page you can change that limit (*skip a queued call more than N s late*), or tick *mix overlapping calls* instead.

Press **Save stream**: it starts streaming at once if monitoring is running (otherwise when you press START MONITORING). Only a change to a stream's own server, mount, user, password, feed ID or MP3 format reconnects that one stream; every other stream stays connected. Keys and passwords are saved **only on this computer**, in your TrunkDeck profile
(`~/.trunkdeck/streams.properties` on Linux with owner-only permissions; `%APPDATA%\TrunkDeck` on Windows). They're masked in the
app and never written to logs or diagnostics. TrunkDeck sends them only to the server you entered.

---

## Engine page

Open **Engine** in the left rail. It has these parts:

* **Tuners (RTL-SDR, 2.4 MS/s):** every RTL-SDR plugged in. For each one you can set:
  * **Role**: *auto* (the first dongle takes the control channel, the others roam for voice), *control*, *voice* or *off*
  * **PPM** correction
  * **Gain**: Auto (AGC) or a fixed value. Start around 30 dB; the page tells you if the dongle is overloaded (see *Gain* below)
  * **Bias-T**: only for an LNA powered through the coax; leave it off otherwise

  Changes are saved straight away. Press STOP/START to apply them. When the engine has measured a better PPM or gain, an
  **Apply** button appears next to the advice line: it enters the suggested values and restarts monitoring for you.
* **Live** shows:
  * lock status and NAC / WACN / System / RFSS / Site
  * each channel's frequency, lock, signal level and **measured frequency error**
  * the band plan the site broadcasts, adjacent sites, stream status and engine messages
* **Voice codec:** see *Voice audio* below. Also *Record every followed call*, and the two feed options (*mix overlapping calls*, *skip a queued call more than N s late*).
* **Voice call (diagnostics):** each dongle's **window** (centre and usable span, calls on it, retunes), every active call, **missed
  calls** with the reason (*out of tuner range*, *call limit reached*, *no Phase 1 / Phase 2 voice decoded*), and figures for the call on the speaker. See below.
* **Decoded control-channel messages:** every control-channel message (TSBK) the engine decodes: group voice grants, grant updates,
  identifier updates (channel tables), network / RFSS status, adjacent sites, affiliations / registrations. You can filter it
  (type `GRANT` or `TG:1234`), pause it, or copy it to the clipboard.
* **Test with an I/Q recording:** decode a recorded file instead of a live radio. Supported formats: SDR# / SDRuno `.wav` I/Q,
  `rtl_sdr` `.cu8` / `.bin`, `.cs8`, `.cs16`, `.cf32`. **Run built-in test signal** runs a simulated P25 site so you can see the
  engine work with no radio at all. It's clearly marked *TEST SIGNAL* in the display.

**Two identical dongles:** both often have serial number `00000001`. TrunkDeck then tells them apart by USB order (#0, #1),
which can swap if you plug them into different ports. To avoid that, give one a unique serial once with
`rtl_eeprom -d 1 -s 00000002` (from the rtl-sdr tools), then replug. TrunkDeck remembers PPM/gain per serial.

### PPM calibration tips
A P25 channel is only 12.5 kHz wide, and a cheap dongle can be 20–60 PPM off (9–27 kHz at 453 MHz).
* The engine **measures** each dongle's frequency error from the P25 signal itself (*Frequency error* on the Engine page),
  and corrects small errors automatically (up to about ±9 kHz).
* Once it's locked, it shows advice such as *"Control dongle: measured +2276 Hz (+5.0 ppm, signals appear high) - set its PPM to -5 (now 0)."*.
  Click **Apply**, or type that number **exactly as shown, including the minus sign**, in the **PPM** box for that dongle and press STOP/START.
  After the first call on the roaming dongle it also suggests a value for that one.
* **Which sign?** If stations appear *above* their real frequency (a **+** offset), the PPM must be **negative**, and the other way round.
  The advice already does this for you, and the number is the *new total*.
* After applying, *Frequency error* should read within about ±300 Hz. A channel that shows *"- (no signal)"* simply isn't transmitting
  right now (for example the alternate control channel). That's not a fault.
* If it **never locks**, try PPM values in steps of 10 (for example −40 … +60), a few seconds each, until the LOCK meter moves.
  Then fine-tune with the advice. TCXO dongles (RTL-SDR Blog V3/V4) usually need 0–3 PPM.
* **Gain:** for a site a few miles away, **15–30 dB** is a good range. TrunkDeck never suggests going below 15 dB, and **Apply** changes
  the gain by at most one step (about 6 dB) at a time. Some ADC clipping near a strong site is normal and barely affects P25. If a
  dongle still shows a lot of clipping at 15 dB and the voice is bad, use a 10–20 dB inline attenuator instead of less gain.
  The best check: *IMBE voice frames* shows **0–1 corrected bits/frame** and *Stream* shows **0 breaks**.
* Dongles drift as they warm up. Let them run 5–10 minutes before calibrating.

### Voice call diagnostics
The **Voice call** box shows the call on the speaker (*NOW HEARING TG … / HANG*) and figures for it, or for the last one:

| Line | What it tells you |
|---|---|
| Frequency offset | How far the dongle is off, and how much the filter is correcting. Above ±3000 Hz, set that dongle's PPM (the advice line suggests a value). |
| Demodulator | Which demodulator is in use (C4FM or LSM/CQPSK) and the bit-error rate each one measures. Below 1 % is good. |
| Signal | Level while the call was being received (dB of the ADC full scale). About −10 to −20 dB is ideal; above −6 dB means overload. |
| Frames | Frame syncs, NID ok/bad, voice frames (and how many were lost), transmissions and end-of-transmission markers. (Phase 1) |
| TDMA frames | Phase 2: the channel's fragments in sync, ISCH (slot information) ok/bad, MAC messages ok/bad; this slot's voice bursts and AMBE+2 frames, transmissions and end-of-transmission (MAC_END_PTT). |
| Scrambling | Phase 2: the descrambling key (WACN / system ID / NAC from the control channel; e.g. A4321 / 2D5 / 2D5) and how many scrambled MAC messages pass their CRC. *OK* means descrambling works; *FAILING* means the key is wrong. |
| IMBE / AMBE+2 voice frames | Frames decoded, average corrected bits per frame (clean is 0–1), % repeated and % muted as noise. IMBE for Phase 1, AMBE+2 for Phase 2. |
| Stream | Sample gaps and carrier breaks during the call. Should be 0. Breaks mean samples are being lost (USB hub, CPU load, or a very weak signal). |
| IMBE / AMBE+2 error histogram | Corrected bits in each voice frame, and errors in its first codeword (c0). Good: almost all frames at 0–2. |
| Talkgroup check | Voice frames from other talkgroups that were rejected (stale grants). Phase 2 checks the talkgroup in the slot's MAC messages. |
| Audio | Jitter-buffer size and underruns. |
| T1 / T2 … | Each dongle's window (centre, span), how many calls it is decoding, retunes. |
| Concurrent calls | Calls decoding now, the peak, the limit, calls decoded this session (and how many were Phase 2 slots). Two Phase 2 slots on one channel count as two calls. |
| Missed calls | Count by reason, and the last few with talkgroup and frequency. |
| Voice channels outside all windows now | Frequencies with calls that no dongle covers right now. |

**Hang time** (next to *Copy diagnostics*) is how long a conversation is kept between transmissions. Raise it if you lose the
replies; lower it if you want the speaker to move on faster.

**If the audio is bad, what to send:** let it run a few calls, click **Copy diagnostics** (no passwords or stream keys are included),
and optionally tick **Record voice-channel I/Q** (the next 5 calls, up to 30 s each, saved as `.wav` I/Q under `iq/` in your
recordings folder; zip one or two). Say what you heard: choppy, clicks, beeps, robot voice, or talkgroups mixed.

### Voice audio (IMBE and AMBE+2 codecs — please read)
P25 Phase 1 voice uses the **IMBE** vocoder and Phase 2 voice the **AMBE+2** (half-rate) vocoder; both are covered by patents
held by DVSI. **TrunkDeck doesn't include a compiled vocoder.** It uses **JMBE**, an open-source codec library (GPLv3) that is created **on your own computer**:

* **Build from source…** (Engine page → *Voice codec*): TrunkDeck shows the patent notice, downloads the JMBE source code from
  <https://github.com/DSheirer/jmbe> and compiles it on your PC, using the Java compiler in the runtime the installer downloaded.
* **Choose jar…**: pick a `jmbe-*.jar` you already have (for example one another P25 program created for you).

JMBE 2.x contains both codecs. The *Voice codec* line on the Engine page says **IMBE (Phase 1) + AMBE+2 (Phase 2)** when both
are there. An old JMBE 1.x jar has only IMBE: Phase 1 is heard, Phase 2 calls are followed but silent. If you built JMBE with
TrunkDeck 1.2.0 you already have 2.x and don't need to do anything.

Without the codec the engine still follows, lists and logs every call; the calls are just silent. Whether you may use the codec
depends on where you live. That's your decision.

#### Setting up Rdio Scanner
1. **Install it.** Download the latest release from <https://github.com/chuot/rdio-scanner/releases>
   (Windows: `rdio-scanner-windows-amd64-….zip`; Fedora: `rdio-scanner-linux-amd64-….zip`) and unzip it into its own
   folder (its settings and database are created there).
   * **Windows 11:** double-click `rdio-scanner.exe` and keep its window open. Allow it through Windows Defender Firewall if
     phones or other PCs will listen. To start it with Windows: open *Command Prompt (Admin)* in that folder and run
     `rdio-scanner.exe -service install` then `rdio-scanner.exe -service start`.
   * **Fedora:** in a terminal in that folder: `chmod +x rdio-scanner` then `./rdio-scanner`. For other devices:
     `sudo firewall-cmd --add-port=3000/tcp --permanent && sudo firewall-cmd --reload`. To start it with the PC:
     `sudo ./rdio-scanner -service install` then `sudo ./rdio-scanner -service start`.
   * Optional: install ffmpeg so Rdio Scanner stores calls as AAC (Fedora: `sudo dnf install ffmpeg` from RPM Fusion).
2. **Create a system and an API key.** Open `http://localhost:3000/admin` (default password `rdio-scanner`; change it).
   Under *Config*: in *Systems* add a system and give it an **ID** (any number, e.g. 1) and a label; keep *auto populate*
   on so your talkgroups appear by themselves. In *API keys* add a key (any long random text), allowed for that system. Save.
3. **Point TrunkDeck at it.** *Streams → Add Rdio Scanner*: Server URL, API key, System ID → **Test connection** →
   **Save stream** → assign talkgroups (*Assign by category / tag…* or *Pick talkgroups…*). Listen at `http://localhost:3000`.

---

## The example system (fictional)

On first launch TrunkDeck adds three presets of a **fictional** system, *Example County - Site 001/002/003 (fictional)*
(WACN A4321, System ID 2D5, NAC 2D5, control channels around 851 MHz), so every page has something to show. They are
not a real system: on the **Systems** page, add your own (control channel(s) in MHz, optional NAC / WACN / System ID -
the RadioReference database lists them) and delete the examples if you like. No talkgroups are pre-loaded; import your
own CSV (see *Talkgroup CSV files*).

## Limitations (please read)
* **Radio names come from you or an import, not from the air.** TrunkDeck 1.4.1 does not decode Motorola talker aliases
  (Phase 1 link control / Phase 2 MAC): the encoding is an undocumented Motorola scheme (known only from third-party reverse
  engineering) and none of the recordings available for testing contain one, so a decoder could not be checked. Import a DSD+ file (DSD+ does decode them) or type names instead.
* **Phase 2 has been confirmed on few live calls so far.** Phase 2 needs a Phase 1 control
  channel (as on most Phase 2 systems); Phase 2 TDMA control channels, Phase 2 data and the encryption-sync field are not decoded.
* Phase 2 audio needs JMBE 2.x (it contains the AMBE+2 codec). With an old JMBE 1.x jar Phase 2 calls are followed but silent.
* **The Airspy R2 driver is new in 1.4.0 and has not been tested with a real Airspy yet** - only with a bit-accurate simulation of
  its USB sample stream. *Copy diagnostics* on the Engine page shows what happened.
  HackRF is not supported.
* **Two RTL-SDRs (without an Airspy) cover about 2 MHz each.** Calls outside both windows while the roaming dongle is busy are missed (and logged with
  their frequency). See *How two dongles cover a site*.
* **Mix all calls** plays everything, but the big display doesn't follow individual calls in that mode (the Active calls table does).
* The LSM/CQPSK (simulcast) demodulator has been tested on public recordings and simulated simulcast, not on a live simulcast site.
* Unit-to-unit (private) calls, data (PDU) and the HDU/TDULC contents are logged but not followed or decoded.
* **Encrypted talkgroups can't be heard.** They're still listed, in purple, so you can see the activity.
* Expect it to decode somewhat worse than mature decoders on weak or fringe signals.
* Monitoring public-safety radio is legal in most of the US, but some states restrict scanners in vehicles or using what you
  hear. Check your local laws.

---

## Troubleshooting

| Problem | Try this |
|---|---|
| Display says **NO TUNER FOUND** / "No RTL-SDR dongles found" / "usb_claim_interface error" | Plug in the dongle(s). Windows: do the Zadig step, and close any other SDR program (only one program can use a dongle). Linux: run the USB setup, then unplug and replug. Press STOP and START. |
| **Airspy** not listed, or "No Airspy found" / "permission" / "in use" next to it | Windows: do the Zadig step for **AIRSPY (1D50 60A1)** and close SDR# / other SDR programs. Linux: run the USB setup (`sudo ~/.local/share/trunkdeck/udev/setup-usb.sh`), unplug and replug, and make sure `airspy_rx` or another program isn't running. "libusb not available": `sudo dnf install libusb1`. |
| Engine → Diagnostics shows **"THE PC IS NOT KEEPING UP"** on the Wideband line (dropped USB samples) | The PC can't process 10 MS/s: close other heavy programs, plug the Airspy straight into a USB 2.0/3.0 port (not a hub), or set the Airspy to **2.5 MS/s** (covers about 2.2 MHz). |
| Engine → Diagnostics shows **"Processing T1: … processing restarts"** or **"… decoder errors contained"**, or dropped blocks "while sample processing was stopped/restarting" (1.4.3) | TrunkDeck caught an internal error and recovered by itself (decoding continues). Please send the *Processing* line and the engine log lines around it (they contain the error and where it happened) so it can be fixed. |
| Airspy: **ADC clipping** in Diagnostics, or calls break up near strong transmitters | Lower the gain (Engine → Tuners: a lower *Linearity* number). If levels are very low, raise it or try a *Sensitivity* preset. |
| "RTL-SDR driver library not available" | Windows: the `app\native` folder is missing; run Install.bat again. Linux: `sudo dnf install rtl-sdr libusb1` (or `sudo apt install rtl-sdr libusb-1.0-0`). |
| **LOOKING FOR CONTROL CHANNEL**, meter never turns green | Most often the dongle's **PPM** is off (see *PPM calibration tips*). Also check the antenna, try gain 30–40 dB, check that both control channels are entered, or set the demodulator to *LSM* on the Systems page for a simulcast site. |
| Calls appear but there's no sound | Build the voice codec (Engine page → *Voice codec*). Check that the talkgroup isn't muted or encrypted, that *What to play* isn't hiding it, the **Speaker** mode (a *Hold* on another talkgroup?), and your system volume. |
| Many **MISSED … out of tuner range** | Those calls are on frequencies neither dongle covers while the roaming dongle is busy. Check that both dongles show up (*Engine → Tuners*). A third dongle with role *voice* helps. |
| **MISSED … call limit reached** | Raise *Max simultaneous calls* on the Systems page. |
| **MISSED … no Phase 1 voice decoded** | The roaming dongle's PPM or gain is probably off (see the advice line). |
| **MISSED … no Phase 2 voice decoded** / **PHASE 2 SYNC BUT NO VOICE** | Same checks as Phase 1 (PPM, gain). If the diagnostics say *Scrambling FAILING*, the control channel's WACN / system ID did not match the voice channel: send *Copy diagnostics*. |
| **Phase 2 calls are listed but silent** | The voice codec line on the Engine page says *IMBE only*: your JMBE jar is an old 1.x version without AMBE+2. Click **Build from source…** to build JMBE 2.x. |
| Choppy audio, clicks, beeps | Set gain 15–30 dB and PPM as advised. Check that *Stream* shows 0 breaks and *IMBE voice frames* 0–1 corrected bits/frame. Avoid unpowered USB hubs. See *If the audio is bad, what to send*. |
| Purple **ENC** calls | Encrypted. Nobody can listen to these without the key. That's normal. |
| Something else | *Setup & Help → Show engine log*, and *Engine → Copy diagnostics*. |

---

## Updating

Your systems, talkgroups, radio names, streams, recordings and voice codec are kept when you update.
* **Windows:** unzip the new `TrunkDeck-<version>-windows.zip`, open `TrunkDeck\windows`, and double-click **`Install.bat`** again. It closes TrunkDeck
  if it's running, replaces the program files, downloads the Java runtime once (if it isn't there yet), and refreshes the shortcuts.
* **Linux:** unpack the new `TrunkDeck-<version>-linux.tar.gz` (`tar xzf ...`), open a terminal in its `linux` folder and run `./install.sh` again.

**Updating from 1.2.0:** nothing else to do. After the update, look at the *Voice codec* line on the Engine page: it should say
*IMBE (Phase 1) + AMBE+2 (Phase 2)*. If it says *IMBE only*, click **Build from source…** once to get JMBE 2.x for Phase 2 audio.

**Updating from 1.1.x (which used SDRTrunk):**
* The installer deletes the SDRTrunk program folder that 1.1.x downloaded into TrunkDeck's install folder (`engine`), after copying
  out any voice-codec jar it finds there. It downloads the Java runtime instead (once).
* On the first start, TrunkDeck switches a saved *SDRTrunk* engine choice to the TrunkDeck engine, copies the JMBE codec that
  SDRTrunk had created into TrunkDeck's own profile folder, keeps using your old recordings folder if 1.1.x recorded into
  `~/SDRTrunk/recordings`, and deletes only the `TrunkDeck_*.xml` playlist files that 1.1.x wrote for SDRTrunk (and SDRTrunk's playlist
  setting pointing at them). What it did is written to `upgrade-1.2.0.log` in your profile folder (`~/.trunkdeck`, Windows `%APPDATA%\TrunkDeck`).
* SDRTrunk's own folder (`~/SDRTrunk`) and settings are **not** deleted, in case you use SDRTrunk on its own. Delete them yourself
  if you don't need them.
* Set your dongles' PPM and gain on the *Engine* page (1.1.x's SDRTrunk tuner settings aren't carried over).

## Licenses & credits
* **TrunkDeck**, including the TrunkDeck engine (`source/src/p25monitor/{p25,dsp,sdr,nativeengine}`): MIT license (`licenses/TrunkDeck-LICENSE.txt`).
  Source code and tests are in `source/`. Written from the published TIA-102 standards. Since 1.4.6 the error-correction and
  coding tables are built from the standards' own definitions (`source/src/p25monitor/p25/CodeTables.java`), and a test proves they
  are identical to the earlier tables. See `NOTICE.txt`.
* **JMBE** voice codec (GPLv3, <https://github.com/DSheirer/jmbe>): only built on your machine on request (see *Voice audio*). It's never included in this zip.
  The IMBE and AMBE+2 vocoders it implements are subject to patents held by Digital Voice Systems, Inc.
* **Eclipse Temurin JDK 25** (GPLv2 with the Classpath Exception, <https://adoptium.net>): downloaded by the installer, not in this zip. Licence files: `runtime/legal`.
* **librtlsdr** (bundled in `app/native/`, RTL-SDR Blog rtl-sdr v1.3.6): GNU GPL v2 or later; **libusb**: LGPL 2.1. Sources in `source/third-party/`.
* **java-lame** (MP3 encoder, `app/lib`): LGPL v3, unmodified separate jar; source in `source/third-party/`.
* **JTransforms**, **JLargeArrays** (BSD 2-Clause), **Apache Commons Math** (Apache 2.0), **SLF4J API** (MIT): libraries the JMBE codec needs, in `app/lib`.
* **FlatLaf** (look and feel): Apache License 2.0.
* Full list and licence texts: `licenses/THIRD-PARTY.txt` and the other files in `licenses/`.
