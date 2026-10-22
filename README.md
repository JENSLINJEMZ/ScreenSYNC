# led-hue-cycle

Drive a cheap Bluetooth "dream colour" LED strip from your own code — and turn
your **Android phone or Linux PC screen into an Ambilight** that lights each LED
with the colour of the screen region it faces. Built for BLE controllers that
advertise as **`LEDDMX-*`** and ship with the **"LED LAMP"** Android app
(Shenzhen Zhongji) — sold under many brands. This project reverse-engineers that
app's protocol so you can drive the strips yourself.

What's inside:

| Path | What it is |
|---|---|
| **[Android app](android/)** | native phone app — *Layout screen with per-light tiles*, **phone-screen sync** (MediaProjection), hue cycle, runtime colour order, SignalRGB-style UI |
| **[`sync_gui.py`](sync_gui.py)** | **LED Studio** — desktop GUI (GTK4) that wraps your monitor, lets you place LED strips around its edges (start corner, direction, sides, LED count), shows a live per-LED preview, and syncs **each LED to the screen region it faces** |
| **[`screen_sync.py`](screen_sync.py)** | headless **PC screen sync** for Linux — whole-screen / vertical-band colour streaming from the terminal |
| **[`led_cycle.py`](led_cycle.py)** | the original hue-cycle effect from the terminal |
| **`tools/`** | BLE scanner, frame sender, HCI-snoop parser, phone verifier — the protocol reverse-engineering kit |

## Hardware compatibility

Tested and confirmed on:

- **[One94Store Smart LED Strip Light 5M with App & Remote Control](https://www.amazon.in/One94Store-Lights-Remote-Gaming-Decoration/dp/B0FTQ5Q7BZ)**
  (Amazon.in, ASIN `B0FTQ5Q7BZ`) — RGBIC "dream colour" strip, 2× 5 m chained to 10 m.

It should work with any strip whose controller:

- advertises over BLE with a name starting **`LEDDMX-`** (e.g. `LEDDMX-03-XXXX`), and
- is controlled by the **"LED LAMP"** app (package `com.ledlamp`). This app is
  rebadged under many store brands, so a lot of no-name Amazon/AliExpress
  "dream colour" / "music sync" strips use it.

Check yours with a BLE scanner (e.g. nRF Connect for Mobile) — if you see a
`LEDDMX-*` device exposing GATT service `0xFFE0`, this project should drive it.

The controller accepts **one BLE connection at a time** — stop the phone app
before connecting from a computer (and the other way around).

### ⚠️ Colour order varies between strips

These RGBIC strips don't all wire their colour channels the same way. If your
colours come out wrong — a classic sign is **blue showing as green** and
**orange showing as pink** — switch the order:

- **Android:** press the **Order** button (main screen) until colours are
  correct — it cycles `RGB → RBG → GBR → GRB → BRG → BGR` live and remembers
  the choice.
- **LED Studio:** use the **Colour order** dropdown (same options).
- **Python:** pass `--color-order` (`rgb`, `rbg`, `grb`, `gbr`, `brg`, `bgr`)
  to `led_cycle.py` / `screen_sync.py`.

Note: saturated **orange** looks muddy/dim on these strips regardless of
settings — that's a limitation of the RGBIC LEDs (weak green mixing), not a bug.

## LED Studio — Sync GUI for Linux (Hyprland / wlroots / X11)

`sync_gui.py` is a desktop app that shows your monitor as a canvas and lets you
build your strip layout directly on it:

```bash
pip install -r requirements.txt          # bleak (PyGObject/GTK4 comes from the system)
.venv/bin/python sync_gui.py             # open LED Studio

LEDSYNC_AUTOSTART=1 .venv/bin/python sync_gui.py    # start sync on launch
LEDSYNC_DEBUG=1 .venv/bin/python -u sync_gui.py     # engine log in the terminal
```

**▶ Sync** starts capture → BLE streaming; **■ Stop** ends it; closing the
window saves everything to `led_layout.json` next to the script.

### Building your layout

The canvas shows your monitor with **one LED dot per physical LED**, wrapped
around the screen in the same order as your strip. Each LED dot shows a **live
colour tile** of exactly what is being streamed to that LED.

- **1st LED** — which corner the strip's first LED sits at (`top-left` …),
- **clockwise / counter-cw** — the direction the strip runs around the screen,
- **Side checkboxes** — which sides the strip covers (e.g. top + right + bottom
  + left for a U-wrap),
- **flip direction** — run the whole layout from the other end,
- **LEDs** — how many LEDs are on the strip (e.g. 40 = 10 per side).

Match the dot order to what you physically see; the sync engine then samples a
thin screen band **under every LED position** (corners sample the corner area)
and streams each LED its own colour using the strip's custom-pattern colour
list — real per-LED Ambilight with a single BLE strip.

### Controls

- **Test pattern** checkbox — shows red top / green right / blue bottom /
  yellow left on the strip (and on the preview dots) so you can verify
  placement/direction without needing specific screen content.
- **Zone mode** (`GD`/`FD`/`FW`/`FS`/`PU`/`FL`/`HO`) — the undisplayed-name
  firmware modes for the custom colour list; each LED's colour comes from its
  list slot either way, but the firmware may animate the list — pick the one
  that holds it static.
- **Per strip display** — `zones (edge sync)`, `average` (whole strip follows
  the average screen colour), `cycle` (slow hue cycling), `off`.
- **Monitor** — which output to capture (`hyprctl monitors` names); capture is
  per-monitor so the strip only mirrors that screen.
- **Brightness / Update rate (fps) / Colour order** — global.
- **Scan strips** — scan for LEDDMX controllers and assign their MAC.

### Latency

The pipeline refreshes at `Update rate` (default 5 fps; capture is ~1/3 of the
window budget each frame) and only uploads colour slots whose value actually
changed, so typical screen→LED delay is ~0.25–0.3 s. Push the slider to 7–8
until the strip starts dropping writes (flicker), then back off.

### Requirements

Wayland compositors based on **wlroots** (Hyprland, Sway, river, …) via
`grim`; X11 sessions via ImageMagick `import` (fallback engine). GTK4 +
PyGObject for the window (Arch: `python-gobject` is pulled in by GTK apps;
`grim` may need a `pacman -S grim`).

### ⚠️ Windows is not supported for the Python path

These controllers reject GATT **characteristic discovery** in a way the Windows
Bluetooth stack (WinRT, used by `bleak` and essentially every Windows BLE app)
refuses to tolerate (`WinError -2147024838`), even though Android handles it
fine. On Windows with only the built-in adapter, use the **Android app**
instead. A **USB BLE dongle + [bumble](https://github.com/google/bumble)**
bypassing the broken discovery also works.

## Quick start — Android app

The phone drives the strip directly. See [android/README.md](android/README.md)
for the full build; in short (needs JDK 17 + Android SDK, no Gradle):

```bash
cd android
bash build.sh
adb install -r build/ledcycle.apk
adb shell pm grant com.ledcycle android.permission.BLUETOOTH_CONNECT
adb shell pm grant com.ledcycle android.permission.POST_NOTIFICATIONS
adb shell am start -n com.ledcycle/.MainActivity
```

Open **LED Cycle**, press **Scan**, tick the `LEDDMX-*` lights it finds, and
press **Start** — no addresses to edit, no rebuild. It runs as a foreground
service and keeps cycling with the screen off; **Stop** (or the notification)
ends it. Your selection is remembered for next time.

### Layout screen — live tiles

Press **Layout** for a view where every light is a tile that mirrors exactly
what the app sends to that strip, updating ~6×/s (live screen sync). Each light
is controlled independently:

- **Cycle ✓ / ✗** — follow the shared hue cycle, or break out of it,
- **Colour** — pin the light to its own static colour (hue + level sliders),
- **On / Off** — show black without touching the others.

Changes apply live while the cycle runs and are remembered across restarts.

### Screen sync (SignalRGB-style)

Press **Screen** on the main screen and confirm the system "recording or
casting" dialog — the app then captures the phone screen at ~6 fps and mirrors
its colours onto the strips over BLE:

- **One strip = whole-screen average colour.**
- **Multiple strips = vertical bands**, so a strip wall shows the screen like
  SignalRGB's ambient modes do.
- Works with anything on screen — videos, games, browsers.
- Press **Screen** again to switch it back to the hue cycle.

## Headless PC sync — `screen_sync.py`

The same engine as LED Studio in terminal form: grab the screen several times
per second, average per strip (whole screen with one, vertical bands
left→right with several), stream over BLE:

```bash
python3 screen_sync.py                                   # auto-find strips, whole screen
python3 screen_sync.py --mac 41:42:2C:C2:1E:13           # one strip
python3 screen_sync.py --mac A1 --mac B2                 # strips as vertical bands
python3 screen_sync.py --rate 10 --monitor DP-1          # main monitor only, 10 fps

python3 screen_sync.py --capture-test 5                  # no-BLE sanity check:
                                                         # prints grabbed colours + timing
```

Useful options: `--monitor` (one output), `--rate`, `--gamma 2.2` (perceptual
dimming for dark desktops), `--color-order`, and `LEDSYNC_DEBUG=1` for a
per-frame colour log. Stop with **Ctrl+C**; the strip keeps its last colour.
If the first connection fails with *"Device … not found"*, the adapter was
cold — rerun (the script warms the adapter with a short `bluetoothctl scan`
automatically, and re-scans on its own when the BlueZ record expires).

## Protocol reference

Reverse-engineered from the LED LAMP app; cross-checked against
[user154lt/LEDDMX-00](https://github.com/user154lt/LEDDMX-00). All frames are
9 bytes, framed `7B … BF`.

- **Service:** `0000ffe0-0000-1000-8000-00805f9b34fb`
- **Write characteristic:** `0000ffe1-0000-1000-8000-00805f9b34fb`
  (properties: WRITE / WRITE-NO-RESPONSE / NOTIFY / READ)

| Command             | Frame                                              |
|---------------------|----------------------------------------------------|
| Static colour       | `7B FF 07 C1 C2 C3 00 FF BF`                       |
| Power on/off        | `7B FF 04 03/02 FF FF FF FF BF`                    |
| Brightness          | `7B FF 01 (pct*32/100) pct 00 FF FF BF`            |
| Pattern             | `7B FF 03 idx FF FF FF FF BF` (idx 0–210)          |
| Colour temperature  | `7B FF 09 (pct*32/100) pct FF FF FF FF BF`         |
| Mic + EQ            | `7B FF 0B eq 00 FF FF FF BF` (eq 0 = mic off)      |
| Direction           | `7B FF 0D 00/01 FF FF FF FF BF` (custom fwd/rev)   |
| Display mode        | `7B FF 13 mode FF FF FF FF BF` (custom pattern)    |

`C1 C2 C3` are the three colour bytes. Their mapping to physical red/green/blue
is the **colour order** described above — on the tested hardware the app
defaults to `RGB` (the older verified build sent `RBG`).

### Custom pattern colour list (per-LED colours)

The firmware also holds a **colour list** whose entries it distributes across
the physical LEDs — the frames used by this project for edge sync:

- **Set colour** `7B pos 0E FD C1 C2 C3 size BF` — `pos` = 1-based slot,
  `size` = total list length.
- **Display mode** `7B FF 13 <mode> FF FF FF FF BF` — `mode` ordinal is one of
  `GD FD FW FS AC PU FL HO` (`AC` = off). The vendor app does not document
  what each abbreviation does; set the list then the mode.
- **Direction** `7B FF 0D 00/01 FF FF FF FF BF` (forward / reverse).

Unknowns: exact cap of the colour list (≥ 40 worked on the tested strip) and
the static-vs-animated behaviour of each mode — empirically at least one mode
spreads the list statically across the LEDs.

## Python scripts

Pure-`bleak` implementation and the tools used to reverse-engineer the
protocol:

| File | Purpose |
|------|---------|
| [`led_cycle.py`](led_cycle.py) | the hue-cycle effect (CLI: `--mac`, `--cycle-minutes`, `--brightness`, `--color-order`, `--test`) |
| [`screen_sync.py`](screen_sync.py) | **SignalRGB-style screen sync for Linux** — mirror the PC screen onto the strips |
| [`sync_gui.py`](sync_gui.py) | **LED Studio** — per-LED Ambilight layout editor + sync GUI |
| [`tools/scan.py`](tools/scan.py) | find `LEDDMX-*` devices, dump their GATT table |
| [`tools/send_test.py`](tools/send_test.py) | write one frame (colour or raw hex) for testing |
| [`tools/parse_btsnoop.py`](tools/parse_btsnoop.py) | extract ATT writes from an Android HCI snoop log / bugreport zip |
| [`tools/phone_verify.*`](tools/) | drive nRF Connect's automated test over ADB to verify frames |

```bash
pip install -r requirements.txt
python tools/scan.py                                   # find devices + GATT
python led_cycle.py --test red                         # colour-order calibration
python led_cycle.py --cycle-minutes 30 --color-order rgb
```

## Repository layout

```
android/           native Android app (phone: layout screen, screen sync, cycle)
sync_gui.py        LED Studio — GTK4 per-LED Ambilight GUI for the PC
screen_sync.py     headless PC screen sync (Linux/BlueZ, wlroots Wayland or X11)
led_cycle.py       Python hue-cycle effect (Linux/BlueZ or USB dongle)
tools/             BLE scanner, frame sender, HCI-snoop parser, phone verifier
led_layout.json    LED Studio strip layout (auto-generated)
requirements.txt   Python deps (bleak; numpy optional for capture math)
```

## Credits

- Protocol confirmed against [user154lt/LEDDMX-00](https://github.com/user154lt/LEDDMX-00).
- Frame verification used [Nordic's nRF Connect for Mobile](https://www.nordicsemi.com/Products/Development-tools/nRF-Connect-for-mobile).

## License

[Apache-2.0](LICENSE).

## Disclaimer

Not affiliated with One94Store, Shenzhen Zhongji, or the LED LAMP app. This is
an independent, interoperability project for hardware you own. Use at your own
risk.
