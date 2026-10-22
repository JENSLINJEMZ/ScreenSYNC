#!/usr/bin/env python3
"""SignalRGB-style screen sync on Linux: mirror the PC display onto LEDDMX strips.

The same engine as the Android app's "Screen" option: grab the screen several
times per second, average it per strip (the whole screen with one strip, or
vertical bands left→right with several), and stream 9-byte colour frames over
BLE at the usual rate, one colour per strip.

Works like the phone's Layout too: whatever colour the PC shows is what the
strip shows.

Usage:
    python3 screen_sync.py                      # auto-scan, 1 strip, whole screen
    python3 screen_sync.py --mac AA:BB:...      # 1 strip (whole screen)
    python3 screen_sync.py --mac AA --mac CC    # 2 strips = vertical bands
    python3 screen_sync.py --rate 15 --color-order rbg

Requirements: Linux with BlueZ (not Windows), and a capture engine:
    grim    — wlroots compositors (Hyprland, Sway, river, …)
    import  — ImageMagick, X11 sessions (or XWayland)
The engine is auto-detected; force one with --engine grim|import.
Stop with Ctrl+C; the strip keeps its last colour.
"""

import argparse
import asyncio
import os
import re
import subprocess
import sys
import time

import bleak
from bleak import BleakClient, BleakScanner, BleakError

NAME_PREFIXES = ("LED DMX", "LEDDMX", "LED-DMX")
SERVICE_UUID = "0000ffe0-0000-1000-8000-00805f9b34fb"
CHAR_UUID = "0000ffe1-0000-1000-8000-00805f9b34fb"
POWER_ON_FRAME = bytes([0x7B, 0xFF, 0x04, 0x03, 0xFF, 0xFF, 0xFF, 0xFF, 0xBF])

try:                       # numpy makes the averaging ~50x faster if present
    import numpy as np
except ImportError:
    np = None

# ---------------------------------------------------------------------------
# Protocol — identical to led_cycle.py (service docs there apply verbatim)
# ---------------------------------------------------------------------------

def build_color_frame(r: int, g: int, b: int, order: str) -> bytes:
    channels = {"r": r, "g": g, "b": b}
    c1, c2, c3 = (channels[ch] for ch in order)
    return bytes([0x7B, 0xFF, 0x07, c1, c2, c3, 0x00, 0xFF, 0xBF])


def supports_write_without_response(client: BleakClient) -> bool:
    char = client.services.get_characteristic(CHAR_UUID)
    return char is not None and "write-without-response" in char.properties

# ---------------------------------------------------------------------------
# Screen capture engine (one full frame in, parser per frame out)
# ---------------------------------------------------------------------------

_PPM_HEADER = re.compile(rb"^P6\s*(?:#[^\n]*\n\s*)*(\d+)\s+(\d+)\s+(\d+)\s")


def parse_ppm(data: bytes):
    """Parse a P6 (binary RGB) PPM image as emitted by grim/import."""
    m = _PPM_HEADER.match(data)
    if not m:
        raise ValueError("capture is not a raw P6 PPM frame")
    w, h, _max = (int(m.group(i)) for i in (1, 2, 3))
    # the regex already consumed the single whitespace byte after maxval
    off = m.end()
    rgb = data[off:off + w * h * 3]
    if len(rgb) < w * h * 3:
        raise ValueError("capture frame truncated")
    return w, h, memoryview(rgb)


class Engine:
    """Runs one capture process per frame (grim/import cost ~20-60 ms)."""

    def __init__(self, name: str, cmd: list):
        self.name = name
        self.cmd = cmd

    async def grab(self):
        proc = await asyncio.create_subprocess_exec(*self.cmd,
                                                    stdout=asyncio.subprocess.PIPE,
                                                    stderr=asyncio.subprocess.DEVNULL)
        data, _ = await proc.communicate()
        if proc.returncode != 0 or not data:
            raise RuntimeError(f"{self.name} capture failed (exit {proc.returncode})")
        return parse_ppm(data)


# Output goes to stdout: grim natively, ImageMagick via the ppm:- spec.
ENGINES = {
    "grim":   Engine("grim", ["grim", "-t", "ppm", "-"]),
    "import": Engine("import", ["import", "-window", "root", "ppm:-"]),
}


def detect_engine(preferred: str | None, monitor: str | None = None) -> Engine:
    if preferred:
        eng = ENGINES[preferred]
        for cmd in eng.cmd:
            if _which(cmd):
                return _with_monitor(eng, monitor)
        raise SystemExit(f"engine {preferred} requested but {eng.cmd[0]} not installed")
    for eng in ENGINES.values():
        if _which(eng.cmd[0]):
            return _with_monitor(eng, monitor)
    raise SystemExit("no capture engine: install grim (Wayland/Hyprland/Sway) "
                     "or ImageMagick import (X11)")


def _with_monitor(eng: Engine, monitor: str | None) -> Engine:
    if monitor and eng.name != "grim":
        raise SystemExit("--monitor is a grim-only option (Wayland)")
    if monitor:
        return Engine(eng.name, ["grim", "-t", "ppm", "-o", monitor, "-"])
    return eng


def _which(prog: str) -> str | None:
    for d in os.environ.get("PATH", "").split(os.pathsep):
        p = os.path.join(d, prog)
        if os.path.isfile(p) and os.access(p, os.X_OK):
            return p
    return None


def bluetooth_scan_warmup(mac: str | None = None, timeout_s: float = 10.0):
    """Prepare the adapter + device record: drop stale links, then advertise-scan.

    A cold adapter often misses the strip's adverts, and a killed previous
    run can leave a zombie BlueZ GATT connection which makes service
    discovery fail ("device disconnected") on the next connect.
    """
    try:
        if mac:
            subprocess.run(["bluetoothctl", "disconnect", mac],
                           capture_output=True, timeout=10)
        subprocess.run(["bluetoothctl", "--timeout", str(int(timeout_s)), "scan", "on"],
                       capture_output=True, timeout=timeout_s + 10)
    except (OSError, subprocess.TimeoutExpired):
        pass

# ---------------------------------------------------------------------------
# Per-strip band averaging
# ---------------------------------------------------------------------------

ROWS, COLS = 96, 128        # sampling grid — scales with the phone app

def band_colors(w, h, mv, n, brightness: float, gamma: float):
    """Average each vertical band -> list of n (r, g, b)."""
    if np is not None:
        arr = np.frombuffer(mv, dtype=np.uint8).reshape(h, w, 3).astype(np.float32)
        out = []
        for i in range(n):
            x0, x1 = (w * i) // n, (w * (i + 1)) // n
            sub = arr[:: max(1, h // ROWS), x0:x1: max(1, (x1 - x0) // COLS)]
            r, g, b = sub.reshape(-1, 3).mean(axis=0)
            out.append(_clamp(r, g, b, brightness, gamma))
        return out

    # pure-python fallback grid average
    stepx_w = max(1, w // COLS)
    sums = [[0, 0, 0] for _ in range(n)]
    counts = [0] * n
    stepx = max(1, w // COLS)
    for y in range(0, h, max(1, h // ROWS)):
        base = y * w * 3
        for x in range(0, w, stepx):
            band = min(n - 1, x * n // w)
            p = base + x * 3
            s = sums[band]
            s[0] += mv[p]; s[1] += mv[p + 1]; s[2] += mv[p + 2]
            counts[band] += 1
    return [_clamp(s[0] / c, s[1] / c, s[2] / c, brightness, gamma)
            for s, c in zip(sums, counts) if c > 0]


def _clamp(r, g, b, brightness: float, gamma: float) -> tuple:
    f = brightness / 100.0
    if gamma != 1.0:
        f /= (2.2 / gamma)   # dims midtones up/down with gamma
    rr = (r / 255.0) ** gamma * f * 255.0
    gg = (g / 255.0) ** gamma * f * 255.0
    bb = (b / 255.0) ** gamma * f * 255.0
    return (max(0, min(255, round(rr))),
            max(0, min(255, round(gg))),
            max(0, min(255, round(bb))))

# ---------------------------------------------------------------------------
# Shared frame store + async workers
# ---------------------------------------------------------------------------

class FrameStore:
    """The latest (frame_id, colors) tuple — producers swap, consumers copy."""

    def __init__(self, n: int):
        self.frame_id = 0
        self.colors = [(0, 0, 0)] * n


async def producer(store, args, stop, engine):
    interval = 1.0 / args.rate
    slow = 0
    while not stop.is_set():
        t0 = time.perf_counter()
        try:
            w, h, mv = await engine.grab()
            colors = band_colors(w, h, mv, len(store.colors),
                                 args.brightness, args.gamma)
            store.frame_id += 1
            store.colors = colors
            slow = 0
            if os.environ.get("LEDSYNC_DEBUG"):
                log("screen " + "  ".join(
                    f"#{i + 1}=rgb{c}" for i, c in enumerate(colors)))
        except Exception as exc:
            slow += 1
            print(f"capture error: {exc}", file=sys.stderr)
            await asyncio.sleep(3.0 if slow > 3 else 0.2)
            continue
        await asyncio.sleep(max(0, interval - (time.perf_counter() - t0)))


async def calibrate_test(args, engine, frames: int):
    """--capture-test: grab N frames and print the averaged colours + timing."""
    print(f"engine: {engine.name}  |  capturing {frames} frames …")
    store = FrameStore(1)
    t0 = time.perf_counter()
    for i in range(frames):
        w, h, mv = await engine.grab()
        colors = band_colors(w, h, mv, 1, args.brightness, args.gamma)
        dt = time.perf_counter() - t0
        print(f"  frame {i + 1}: {w}x{h}  avg=rgb{colors[0]}  ({dt * 1000:.0f} ms)")
        t0 = time.perf_counter()
        await asyncio.sleep(1.0 / args.rate)
    return 0


async def feed_strip(idx, mac, store, args, stop):
    """Per-strip consumer: connect, then push the newest band colour each frame."""
    backoff = 2.0
    while not stop.is_set():
        try:
            async with BleakClient(mac, timeout=30) as client:
                print(f"strip[{idx}] {mac}: connected")
                use_resp = not supports_write_without_response(client)
                backoff = 2.0
                await client.write_gatt_char(CHAR_UUID, POWER_ON_FRAME, response=use_resp)
                last = -1
                while not stop.is_set():
                    fid = store.frame_id
                    if fid != last:
                        last = fid
                        r, g, b = store.colors[idx % len(store.colors)]
                        frame = build_color_frame(r, g, b, args.color_order)
                        await client.write_gatt_char(CHAR_UUID, frame, response=use_resp)
                    await asyncio.sleep(0.03)
        except Exception as exc:
            if not stop.is_set():
                stale = "not found" in str(exc)
                print(f"strip[{idx}] {mac}: {type(exc).__name__}: {exc}"
                      + (" — re-scanning" if stale else f" — retrying in {backoff:.0f}s"),
                      file=sys.stderr)
                if stale:
                    # device record dropped from BlueZ — find it again, then settle
                    await asyncio.get_running_loop().run_in_executor(
                        None, bluetooth_scan_warmup, mac, 6.0)
                    await asyncio.sleep(1.5)
                else:
                    await asyncio.sleep(backoff)
                    backoff = min(backoff * 2, 30.0)
    print(f"strip[{idx}] {mac}: stopped")


def log(msg):
    print(msg, file=sys.stderr)


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

async def scan_devices():
    log("scanning for LEDDMX controllers …")
    found = []
    devices = await BleakScanner.discover(timeout=10.0)
    for d in devices:
        if d.name and d.name.upper().startswith(tuple(p.upper() for p in NAME_PREFIXES)):
            log(f"  found {d.name}  {d.address}")
            found.append((d.name, d.address))
    return found


async def run(args, engine) -> int:
    stop = asyncio.Event()
    loop = asyncio.get_running_loop()
    import signal
    done = loop.create_future()
    # Clean Ctrl+C from async land (lets every task wind down normally)
    loop.add_signal_handler(signal.SIGINT, lambda: done.done() or done.set_result(None))

    await asyncio.get_running_loop().run_in_executor(
        None, bluetooth_scan_warmup, (args.mac or [None])[0])

    macs = list(args.mac or [])
    if not args.mac:
        found = await scan_devices()
        if not found:
            print("No LEDDMX controllers found — is the strip powered and "
                  "the Android app disconnected?", file=sys.stderr)
            return 1
        macs = [addr for _name, addr in found]

    n = len(macs)
    if n > 1:
        print(f"syncing {n} strips as vertical bands (left → right)", file=sys.stderr)
    else:
        print(f"syncing whole screen to {macs[0]}", file=sys.stderr)

    store = FrameStore(n)
    producer_task = asyncio.ensure_future(producer(store, args, stop, engine))
    strip_tasks = [asyncio.ensure_future(feed_strip(i, m, store, args, stop))
                   for i, m in enumerate(macs)]

    await done            # until Ctrl+C
    stop.set()
    for t in [producer_task, *strip_tasks]:
        t.cancel()
    await asyncio.gather(producer_task, *strip_tasks, return_exceptions=True)
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--mac", action="append", metavar="AA:BB:CC:DD:EE:FF",
                    help="controller MAC (repeat for multiple strips / bands)")
    ap.add_argument("--rate", type=float, default=5.0, help="captures per second (default 5)")
    ap.add_argument("--brightness", type=float, default=100.0, help="0-100 (default 100)")
    ap.add_argument("--gamma", type=float, default=1.0,
                    help="gamma correction, 1 = mirror the screen exactly, "
                         "2.2 = perceptual (dims dark content)")
    ap.add_argument("--monitor", metavar="NAME",
                    help="grab a single output (grim -o), e.g. DP-2; "
                         "default is the whole desktop")
    ap.add_argument("--color-order", default="rgb",
                    choices=["rgb", "rbg", "grb", "gbr", "brg", "bgr"],
                    help="channel order the strip expects (default rgb — "
                         "matches the corrected setting in the Android app)")
    ap.add_argument("--engine", choices=sorted(ENGINES), help="force a capture engine")
    ap.add_argument("--capture-test", type=int, metavar="N", const=5, nargs="?",
                    help="grab N frames, print colours and timing, exit (no BLE)")
    args = ap.parse_args()

    if not 0 <= args.brightness <= 100:
        ap.error("--brightness must be 0-100")
    if args.rate <= 0 or args.gamma <= 0:
        ap.error("--rate and --gamma must be positive")

    engine = detect_engine(args.engine, args.monitor)

    if args.capture_test is not None:
        return asyncio.run(calibrate_test(args, engine, args.capture_test))

    try:
        return asyncio.run(run(args, engine))
    except KeyboardInterrupt:
        print("\nScreen sync stopped — strips keep their last colour.",
              file=sys.stderr)
        return 0


if __name__ == "__main__":
    sys.exit(main())
