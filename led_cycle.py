"""Smooth whole-strip hue cycle for the "LED DMX" BLE controller.

The entire strip shows one solid colour that continuously walks the hue
wheel (red -> yellow -> green -> cyan -> blue -> magenta -> red ...).
No blackouts, no jumps. Reconnects automatically if BLE drops; because the
hue is derived from wall-clock time, it resumes exactly where it should be.

Usage:
    python led_cycle.py                          # auto-scan, 30-min cycle
    python led_cycle.py --cycle-minutes 5 --brightness 60
    python led_cycle.py --mac AA:BB:CC:DD:EE:FF
    python led_cycle.py --test red               # calibration: send pure red, exit
Stop with Ctrl+C; the strip stays on its last colour.
"""

import argparse
import asyncio
import colorsys
import sys
import time

from bleak import BleakClient, BleakScanner, BleakError

NAME_PREFIXES = ("LED DMX", "LEDDMX", "LED-DMX")

# ---------------------------------------------------------------------------
# Protocol section — LEDDMX-00/-03 ("LED LAMP" app), confirmed against
# https://github.com/user154lt/LEDDMX-00
# ---------------------------------------------------------------------------
SERVICE_UUID = "0000ffe0-0000-1000-8000-00805f9b34fb"
CHAR_UUID = "0000ffe1-0000-1000-8000-00805f9b34fb"

POWER_ON_FRAME = bytes([0x7B, 0xFF, 0x04, 0x03, 0xFF, 0xFF, 0xFF, 0xFF, 0xBF])


def build_color_frame(r: int, g: int, b: int, order: str) -> bytes:
    channels = {"r": r, "g": g, "b": b}
    c1, c2, c3 = (channels[ch] for ch in order)
    return bytes([0x7B, 0xFF, 0x07, c1, c2, c3, 0x00, 0xFF, 0xBF])
# ---------------------------------------------------------------------------

TEST_COLORS = {"red": (255, 0, 0), "green": (0, 255, 0), "blue": (0, 0, 255)}


def hue_to_rgb(hue: float, brightness: float) -> tuple[int, int, int]:
    r, g, b = colorsys.hsv_to_rgb(hue, 1.0, brightness)
    return round(r * 255), round(g * 255), round(b * 255)


async def find_device(name_hint: str | None, timeout: float = 10.0) -> str | None:
    devices = await BleakScanner.discover(timeout=timeout)
    for d in devices:
        if d.name and d.name.upper().startswith(tuple(p.upper() for p in NAME_PREFIXES)):
            print(f"Found {d.name} at {d.address}")
            return d.address
    return None


async def send_loop(client: BleakClient, args, t0: float, use_response: bool) -> None:
    interval = 1.0 / args.rate
    while True:
        hue = ((time.monotonic() - t0) / (args.cycle_minutes * 60.0)) % 1.0
        r, g, b = hue_to_rgb(hue, args.brightness / 100.0)
        frame = build_color_frame(r, g, b, args.color_order)
        await client.write_gatt_char(CHAR_UUID, frame, response=use_response)
        await asyncio.sleep(interval)


def supports_write_without_response(client: BleakClient) -> bool:
    char = client.services.get_characteristic(CHAR_UUID)
    return char is not None and "write-without-response" in char.properties


async def run(args) -> int:
    if args.test:
        r, g, b = TEST_COLORS[args.test]
        address = args.mac or await find_device(None)
        if not address:
            print("Device not found. Close the phone app and try again.")
            return 1
        async with BleakClient(address, timeout=30) as client:
            use_response = not supports_write_without_response(client)
            frame = build_color_frame(r, g, b, args.color_order)
            print(f"Sending pure {args.test}: {frame.hex(' ')}")
            await client.write_gatt_char(CHAR_UUID, POWER_ON_FRAME, response=use_response)
            await client.write_gatt_char(CHAR_UUID, frame, response=use_response)
            await asyncio.sleep(1.0)
        print(f"If the strip is NOT showing {args.test}, adjust --color-order "
              f"(current: {args.color_order}).")
        return 0

    t0 = time.monotonic()
    backoff = 1.0
    while True:
        try:
            address = args.mac or await find_device(None)
            if not address:
                raise BleakError("device not found in scan")
            print(f"Connecting to {address} ...")
            async with BleakClient(address, timeout=30) as client:
                use_response = not supports_write_without_response(client)
                print(f"Connected. Cycling hue over {args.cycle_minutes:g} min at "
                      f"{args.rate:g} Hz (brightness {args.brightness}%). Ctrl+C to stop.")
                await client.write_gatt_char(CHAR_UUID, POWER_ON_FRAME, response=use_response)
                backoff = 1.0
                await send_loop(client, args, t0, use_response)
        except (BleakError, TimeoutError, OSError) as exc:
            print(f"BLE error: {exc} — retrying in {backoff:.0f}s")
            await asyncio.sleep(backoff)
            backoff = min(backoff * 2, 30.0)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--mac", help="controller address (default: auto-scan for 'LED DMX*')")
    ap.add_argument("--cycle-minutes", type=float, default=30.0, help="full hue cycle duration (default 30)")
    ap.add_argument("--brightness", type=float, default=100.0, help="0-100 (default 100)")
    ap.add_argument("--rate", type=float, default=5.0, help="updates per second (default 5)")
    ap.add_argument("--color-order", default="rbg", choices=["rgb", "rbg", "grb", "gbr", "brg", "bgr"],
                    help="channel order the controller expects (default rbg, verified on LEDDMX-03)")
    ap.add_argument("--test", choices=sorted(TEST_COLORS), help="send one pure colour and exit (calibration)")
    args = ap.parse_args()

    if not 0 <= args.brightness <= 100:
        ap.error("--brightness must be 0-100")
    if args.cycle_minutes <= 0 or args.rate <= 0:
        ap.error("--cycle-minutes and --rate must be positive")

    try:
        return asyncio.run(run(args))
    except KeyboardInterrupt:
        print("\nStopped. Strip left on its last colour.")
        return 0


if __name__ == "__main__":
    sys.exit(main())
