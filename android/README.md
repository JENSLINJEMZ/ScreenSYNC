# LED Cycle — Android app

The working solution for this setup. Because the Windows Bluetooth stack cannot
talk to these controllers (see the top-level README), the phone drives the
strip. This is a tiny native app that runs the hue cycle on the phone itself —
smooth, continuous, auto-reconnecting.

## What it does

A **foreground service** (`LedService`) connects to **both** strips at once
(`LEDDMX-03-9022` and `LEDDMX-03-BF4D`), sends power-on, then walks the hue
wheel continuously (whole strip = one solid colour) at ~5 Hz. Both strips
receive the same hue, so they stay in sync. A dropped BLE link reconnects
automatically and resumes at the correct colour.

Because the loop lives in a foreground service with a **partial wake lock**, it
**keeps running with the screen off and the app closed** (verified: hue kept
advancing and both strips kept acknowledging writes while the phone was locked).
It shows an ongoing notification with the current hue and a **Stop** action;
tapping the notification reopens the controls. Intended to run on charge.

`MainActivity` is just the UI: it starts the service, binds to it to drive the
sliders and mirror the live colour, and leaves it running when you exit.

## On-screen controls

- **Start Cycle / Stop Cycle** buttons — the app opens idle; Start launches the
  foreground service, Stop ends it (the strip holds its last colour). Button
  state follows the actual service, so it stays correct even if you stop from
  the notification.
- **Speed** slider — full-cycle duration, 0.25–60 min, adjustable live. The hue
  is a *phase accumulator* (advanced by `dt/cycle` each tick), so changing speed
  shifts the pace without any colour jump.
- **Start colour** slider — hue offset 0–359°. On launch the cycle begins at
  this colour; dragging it shifts the colour live.
- Both settings persist across restarts (SharedPreferences).
- Status line shows current hue/rgb and per-strip state (`022:on  F4D:on`).

## Configuration (code)

Fixed setup is at the top of
[src/com/ledcycle/LedService.java](src/com/ledcycle/LedService.java):

| Constant     | Meaning                                        |
|--------------|------------------------------------------------|
| `MACS`       | array of controller addresses to drive together |
| `STEP_MS`    | update interval (default 200 ms = 5 Hz)        |
| `BRIGHTNESS` | HSV value 0..1 (default 1.0)                   |

Speed and starting colour are runtime controls (sliders), not constants. The
colour frame is `7B FF 07 RR BB GG 00 FF BF` on characteristic `ffe1` — i.e.
**colour order R, B, G** (green and blue swapped), verified on hardware: blue
reads blue and red reads red. To drive only one strip, leave a single entry in
`MACS`.

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

After the first install with the permissions granted, tap the **LED Cycle**
icon and press **Start Cycle**. The foreground service keeps the cycle running
with the screen off / app closed; stop it with **Stop Cycle** or the
notification's **Stop** action. It drives both strips defined in `MACS`.

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
