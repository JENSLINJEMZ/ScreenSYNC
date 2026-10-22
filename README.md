# led-hue-cycle

Control a cheap Bluetooth "dream colour" LED strip from your own code — a smooth,
continuous whole-strip hue cycle that morphs through the full spectrum (red →
orange → yellow → green → cyan → blue → violet → magenta → red …), no fading to
black, no jumps, no chasing segments. Drives **multiple strips in sync**, runs in
the **background with the screen off**, and reconnects automatically.

Built for BLE controllers that advertise as **`LEDDMX-*`** and ship with the
**"LED LAMP"** Android app (Shenzhen Zhongji) — sold under many brands. This
project reverse-engineers that app's protocol so you can drive the strips
yourself.

|  |  |
|---|---|
| **Recommended (works today)** | [Android app](android/) — native, background foreground-service, **SignalRGB-style screen sync**, live speed + start-colour controls, Layout screen with per-light sync, two strips at once |
| **Also included** | Python (`bleak`) scripts for **PC screen sync on Linux** (Wayland/X11) + protocol reverse-engineering tools |

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

### ⚠️ Colour order varies between strips

These RGBIC strips don't all wire their colour channels the same way. The One94Store
units above use byte order **R, B, G** (green and blue swapped), which is the
default here. If your colours come out wrong — a classic sign is **blue showing
as green** and **orange showing as pink** — switch the order:

- **Android:** press the **Order** button (main screen) until colours are
  correct — it cycles `RGB → RBG → GBR → GRB → BRG → BGR` live and remembers
  the choice. The app defaults to **RGB**; the older verified build sent
  **RBG**, so if blue shows as green, tap until it doesn't.
- **Python:** pass `--color-order` (`rgb`, `rbg`, `grb`, `gbr`, `brg`, `bgr`) to
  `led_cycle.py`. Calibrate with `python led_cycle.py --test red` and adjust
  until red shows red, then confirm with `--test green`.

Note: saturated **orange** looks muddy/dim on these strips regardless of settings —
that's a limitation of the RGBIC LEDs (weak green mixing), not a bug.

## Quick start — Android app (recommended)

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

### Layout screen — live screen sync

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

## Protocol reference

Reverse-engineered from the LED LAMP app; cross-checked against
[user154lt/LEDDMX-00](https://github.com/user154lt/LEDDMX-00). All frames are
9 bytes, framed `7B … BF`.

- **Service:** `0000ffe0-0000-1000-8000-00805f9b34fb`
- **Write characteristic:** `0000ffe1-0000-1000-8000-00805f9b34fb`
  (properties: WRITE / WRITE-NO-RESPONSE / NOTIFY / READ)

| Command       | Frame                                        |
|---------------|----------------------------------------------|
| Static colour | `7B FF 07 C1 C2 C3 00 FF BF`                 |
| Power on/off  | `7B FF 04 03/02 FF FF FF FF BF`              |
| Brightness    | `7B FF 01 (pct*32/100) pct 00 FF FF BF`      |
| Pattern       | `7B FF 03 idx FF FF FF FF BF` (idx 0–210)    |

`C1 C2 C3` are the three colour bytes. Their mapping to physical red/green/blue
is the **colour order** described above — on the tested hardware it is
`R, B, G`, so a colour `(r,g,b)` is sent as `7B FF 07 r b g 00 FF BF`.

## Python scripts

Pure-`bleak` implementation and the tools used to reverse-engineer the protocol:

| File | Purpose |
|------|---------|
| [`led_cycle.py`](led_cycle.py) | the hue-cycle effect (CLI: `--mac`, `--cycle-minutes`, `--brightness`, `--color-order`, `--test`) |
| [`screen_sync.py`](screen_sync.py) | **SignalRGB-style screen sync for Linux** — mirror the PC screen onto the strips |
| [`tools/scan.py`](tools/scan.py) | find `LEDDMX-*` devices, dump their GATT table |
| [`tools/send_test.py`](tools/send_test.py) | write one frame (colour or raw hex) for testing |
| [`tools/parse_btsnoop.py`](tools/parse_btsnoop.py) | extract ATT writes from an Android HCI snoop log / bugreport zip |
| [`tools/phone_verify.*`](tools/) | drive nRF Connect's automated test over ADB to verify frames |

```bash
pip install -r requirements.txt
python tools/scan.py                                   # find devices + GATT
python led_cycle.py --test red                         # colour-order calibration
python led_cycle.py --cycle-minutes 30 --color-order rbg
```

The controller accepts **one connection at a time** — close the phone app (and
the Android app of this project) before connecting from a computer.

### Screen sync for Linux — `screen_sync.py`

The same engine the Android app's **Screen** button uses, ported to the PC:
grab the screen N times per second, average it per strip (whole screen with
one strip, vertical bands left→right with several), and stream colour frames
over BLE. Works out of the box on Wayland compositors based on
**wlroots** — Hyprland, Sway, river, … (via `grim`) — and on X11 (via
ImageMagick `import`).

```bash
pip install -r requirements.txt          # bleak (numpy is optional & speeds up averaging)

python3 screen_sync.py                                   # auto-find strips, whole screen
python3 screen_sync.py --mac 41:42:2C:C2:1E:13           # one strip
python3 screen_sync.py --mac A1 --mac B2                 # strips as vertical bands
python3 screen_sync.py --rate 10 --monitor DP-1          # main monitor only, 10 fps

python3 screen_sync.py --capture-test 5                  # no-BLE sanity check:
                                                         # prints grabbed colours + timing
```

Options that matter:

- `--monitor NAME` — capture one output (`hyprctl monitors` shows the names);
  default averages every monitor into one colour.
- `--rate` — captures per second (default 5).
- `--gamma 1` (default) mirrors the screen exactly; `--gamma 2.2` is a
  perceptual variant that dims dark desktops (nicer at night).
- `--color-order` — same meanings as `led_cycle.py`; default `rgb`.
- `LEDSYNC_DEBUG=1` — log the per-strip colour every frame.

Stop with **Ctrl+C**; the strip keeps its last colour. If the first connection
fails with *"Device … not found"*, the adapter was cold — rerun (the script
warms the adapter up with a short `bluetoothctl scan` automatically).

### ⚠️ Windows is not supported for the Python path

These controllers reject GATT **characteristic discovery** in a way the Windows
Bluetooth stack (WinRT, used by `bleak` and essentially every Windows BLE app)
refuses to tolerate (`WinError -2147024838`), even though Android handles it
fine. The Python scripts therefore need a **non-Windows BLE transport**:

- **Linux / Raspberry Pi (BlueZ):** run `led_cycle.py` directly — works.
- **USB BLE dongle + [bumble](https://github.com/google/bumble):** a pure-Python
  BLE stack that owns the dongle and writes by ATT handle, bypassing the broken
  discovery. Works on Windows without touching the built-in radio.

On Windows with only the built-in adapter, use the **Android app** instead —
that's why it's the recommended path.

## Repository layout

```
android/           native Android app (the working, recommended solution)
led_cycle.py       Python hue-cycle effect (Linux/BlueZ or USB dongle)
screen_sync.py     Python PC screen sync (Linux/BlueZ, wlroots Wayland or X11)
tools/             BLE scanner, frame sender, HCI-snoop parser, phone verifier
requirements.txt   Python deps (bleak)
```

## Credits

- Protocol confirmed against [user154lt/LEDDMX-00](https://github.com/user154lt/LEDDMX-00).
- Frame verification used [Nordic's nRF Connect for Mobile](https://www.nordicsemi.com/Products/Development-tools/nRF-Connect-for-mobile).

## License

[Apache-2.0](LICENSE).

## Disclaimer

Not affiliated with One94Store, Shenzhen Zhongji, or the LED LAMP app. This is an
independent, interoperability project for hardware you own. Use at your own risk.
