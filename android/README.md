# LED Cycle — Android app

The working solution for this setup. Because the Windows Bluetooth stack cannot
talk to these controllers (see the top-level README), the phone drives the
strip. This is a tiny native app that runs the hue cycle on the phone itself —
smooth, continuous, auto-reconnecting.

## What it does

A **foreground service** (`LedService`) connects to the `LEDDMX-*` lights you
pick (one or several), sends power-on, then walks the hue wheel continuously
(whole strip = one solid colour) at ~5 Hz. All selected strips receive the same
hue, so they stay in sync. A dropped BLE link reconnects automatically and
resumes at the correct colour. No addresses are hardcoded — you choose lights by
scanning in-app.

Because the loop lives in a foreground service with a **partial wake lock**, it
**keeps running with the screen off and the app closed** (verified: hue kept
advancing and both strips kept acknowledging writes while the phone was locked).
It shows an ongoing notification with the current hue and a **Stop** action;
tapping the notification reopens the controls. Intended to run on charge.

`MainActivity` is just the UI: it starts the service, binds to it to drive the
sliders and mirror the live colour, and leaves it running when you exit.
`LayoutActivity` (the **Layout** button) is the per-light view below.

## On-screen controls

- **Scan** button — discovers nearby `LEDDMX-*` controllers and lists them with
  name, signal, and address. Tick the ones you want to drive; the selection is
  saved and reappears next launch. No addresses are hardcoded.
- **Start / Stop** buttons — the app opens idle; Start launches the foreground
  service for the ticked lights, Stop ends it (the strip holds its last colour).
  Button state follows the actual service, so it stays correct even if you stop
  from the notification.
- **Speed** slider — full-cycle duration, 0.25–60 min, adjustable live. The hue
  is a *phase accumulator* (advanced by `dt/cycle` each tick), so changing speed
  shifts the pace without any colour jump.
- **Start colour** slider — hue offset 0–359°. On launch the cycle begins at
  this colour; dragging it shifts the colour live.
- Both settings persist across restarts (SharedPreferences).
- Status line shows current hue/rgb and per-strip state (`022:on  F4D:on`).
- **Layout** button — opens the Layout screen: one live-sync tile per light
  (the screen mirrors exactly what is being sent to that strip, ~6×/s) and
  individual controls for each light — **Cycle ✓/✗** (follow the shared cycle or
  break out), **Colour** (pin that light to its own static colour with hue +
  level sliders), **On/Off** (show black). Edits apply live while the cycle
  runs and are saved to `layout` in SharedPreferences (`mac,follow,on,rrggbb;…`).
- **Order** button (third in the second button row) — the colour-slot order the
  strip expects: the `7B FF 07 c1 c2 c3 00 FF BF` frame's three colour bytes
  are mapped per the chosen order. Cycles through all six orders live and
  persists in `colorOrder` in SharedPreferences. Default `rgb`; the original
  reverse-engineered build sent `rbg` (the mapping differs between strip
  batches — green showing as blue means the order needs changing).
- **Screen** button — SignalRGB-style screen sync: after the system
  "recording or casting" dialog, a `MediaProjection` mirror (320px-wide
  `ImageReader` virtual display, ~6 fps) is averaged per light and pushed at
  the usual tick. The foreground service is re-published with the
  `mediaProjection` type before the projection token is taken — required on
  Android 10+ or the system kills the projection after one frame. One strip
  mirrors the whole screen; several strips split it into vertical bands. Press
  **Screen** again to fall back to the cycle.

## Configuration (code)

Fixed setup is at the top of
[src/com/ledcycle/LedService.java](src/com/ledcycle/LedService.java):

| Constant      | Meaning                                       |
|---------------|-----------------------------------------------|
| `NAME_PREFIX` | advertised-name prefix the scan filters on (default `LEDDMX`) |
| `STEP_MS`     | update interval (default 200 ms = 5 Hz)       |
| `BRIGHTNESS`  | HSV value 0..1 (default 1.0)                   |

Lights are chosen at runtime via **Scan** (not hardcoded); speed and starting
colour are runtime sliders. The
colour frame is `7B FF 07 RR BB GG 00 FF BF` on characteristic `ffe1` — i.e.
**colour order R, B, G** (green and blue swapped), verified on hardware: blue
reads blue and red reads red. To drive one strip instead of several, tick just
that one in the scan list.

**Writes are flow-controlled**: one GATT write is in flight at a time
(`WRITE_TYPE_DEFAULT`, with response); the next colour is sent only after the
previous one is acknowledged (`onCharacteristicWrite`), with a 1.2 s watchdog.
This is what makes the strips actually track the phone — firing unacknowledged
`NO_RESPONSE` writes at 5 Hz into two connections dropped most of them and the
strips lagged/stuck. The status shows `on` only while a strip is acknowledging
(`?` if acks stop, `…` before connect).

Note: these RGBIC strips reproduce saturated **orange** poorly (looks
muddy/dim) — that is the LED hardware, not the data; blue/red/green/purple are
faithful.

## Build & install

Needs JDK 17 and the Android SDK (build-tools 36.0.0, platform android-36) —
no Gradle. From this directory:

```
bash build.sh
adb install -r build/ledcycle.apk
adb shell pm grant com.ledcycle android.permission.BLUETOOTH_CONNECT
adb shell pm grant com.ledcycle android.permission.POST_NOTIFICATIONS
adb shell am start -n com.ledcycle/.MainActivity
```

`build.sh` runs javac → d8 → aapt2 link → adds `classes.dex` → zipalign →
apksigner (using the standard `~/.android/debug.keystore`).

Then open **LED Cycle**, press **Scan**, tick your lights, and press **Start**
(grant the Bluetooth/notification prompts on first run). The foreground service
keeps the cycle running with the screen off / app closed; stop it with **Stop**
or the notification's **Stop** action.

`POST_NOTIFICATIONS` is only for the ongoing-service notification — if it is
denied the cycle still runs, just without a visible notification. When tapping
the icon normally (not via adb), Android prompts for it on first launch.

## Notes / next steps

- Runs as a foreground service (type `connectedDevice`) with a partial wake
  lock, so it survives screen-off and backgrounding. Best run on charge.
- Drives both controllers over two concurrent GATT connections. If one strip is
  out of range it keeps retrying that one while the other runs.
- Not started automatically on boot. To auto-start after reboot, add a
  `BOOT_COMPLETED` receiver that calls `startForegroundService` — not done yet.
