"""Send one test frame to the LED controller and exit.

LEDDMX-00/-03 static colour frame (7B FF 07 RR GG BB 00 FF BF on char ffe1,
per https://github.com/user154lt/LEDDMX-00):
    python tools/send_test.py --rgb 255,0,0

Arbitrary frame replay (e.g. a payload lifted from an HCI snoop log):
    python tools/send_test.py --raw "7b ff 04 03 ff ff ff ff bf"
    python tools/send_test.py --raw 7bff0403ffffffffbf --char 0000ffe1-0000-1000-8000-00805f9b34fb
"""

import argparse
import asyncio
import sys

from bleak import BleakClient, BleakScanner

NAME_PREFIXES = ("LED DMX", "LEDDMX", "LED-DMX")
DEFAULT_CHAR = "0000ffe1-0000-1000-8000-00805f9b34fb"


def family_frame(r: int, g: int, b: int) -> bytes:
    return bytes([0x7B, 0xFF, 0x07, r, g, b, 0x00, 0xFF, 0xBF])


async def find_device(timeout: float) -> str | None:
    print(f"Scanning for LED DMX device ({timeout:.0f}s) ...")
    devices = await BleakScanner.discover(timeout=timeout)
    for d in devices:
        if d.name and d.name.upper().startswith(tuple(p.upper() for p in NAME_PREFIXES)):
            print(f"Found {d.name} at {d.address}")
            return d.address
    return None


async def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--address", "--mac", dest="address", help="device address (default: auto-scan)")
    ap.add_argument("--char", default=DEFAULT_CHAR, help=f"characteristic UUID (default {DEFAULT_CHAR})")
    ap.add_argument("--rgb", help="R,G,B 0-255 -> sends the known family frame")
    ap.add_argument("--raw", help="hex bytes to write verbatim (spaces allowed)")
    ap.add_argument("--response", action="store_true", help="use write-with-response")
    args = ap.parse_args()

    if bool(args.rgb) == bool(args.raw):
        ap.error("give exactly one of --rgb or --raw")

    if args.raw:
        payload = bytes.fromhex(args.raw.replace(" ", ""))
    else:
        r, g, b = (int(x) for x in args.rgb.split(","))
        payload = family_frame(r, g, b)

    address = args.address or await find_device(10.0)
    if not address:
        print("Device not found. Close the phone app and try again.")
        return 1

    print(f"Connecting to {address} ...")
    async with BleakClient(address, timeout=30) as client:
        print(f"Writing to {args.char}: {payload.hex(' ')}  "
              f"({'with' if args.response else 'without'} response)")
        await client.write_gatt_char(args.char, payload, response=args.response)
        await asyncio.sleep(1.0)  # give the controller a moment before we drop the link
    print("Done. What colour is the strip showing?")
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
