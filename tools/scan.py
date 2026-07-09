"""Find the LED DMX controller over BLE and dump its GATT table.

Usage:
    python tools/scan.py                 # scan, auto-pick first "LED DMX*" device, dump GATT
    python tools/scan.py --address AA:BB:CC:DD:EE:FF   # skip scan, connect directly
    python tools/scan.py --scan-only     # just list nearby devices
"""

import argparse
import asyncio
import sys

from bleak import BleakClient, BleakScanner

NAME_PREFIXES = ("LED DMX", "LEDDMX", "LED-DMX")


def looks_like_target(name: str | None) -> bool:
    return bool(name) and name.upper().startswith(tuple(p.upper() for p in NAME_PREFIXES))


async def scan(timeout: float) -> str | None:
    """List nearby BLE devices; return address of first LED DMX match."""
    print(f"Scanning for {timeout:.0f}s ...")
    devices = await BleakScanner.discover(timeout=timeout, return_adv=True)
    if not devices:
        print("No BLE devices found. Is Bluetooth on?")
        return None

    target = None
    rows = sorted(devices.values(), key=lambda pair: pair[1].rssi or -127, reverse=True)
    print(f"\n{'RSSI':>5}  {'Address':<17}  Name")
    for device, adv in rows:
        mark = ""
        if looks_like_target(device.name):
            mark = "  <-- LED DMX controller"
            if target is None:
                target = device.address
        print(f"{adv.rssi:>5}  {device.address:<17}  {device.name or '(no name)'}{mark}")
    return target


async def dump_gatt(address: str) -> None:
    print(f"\nConnecting to {address} ...")
    async with BleakClient(address, timeout=30) as client:
        print("Connected. GATT table:\n")
        for service in client.services:
            print(f"Service {service.uuid}  ({service.description})")
            for char in service.characteristics:
                props = ",".join(char.properties)
                print(f"  Char  handle=0x{char.handle:04x}  {char.uuid}  [{props}]")
                for desc in char.descriptors:
                    print(f"    Desc handle=0x{desc.handle:04x}  {desc.uuid}")
        print("\nNote the handle numbers: the HCI snoop log identifies writes by handle, not UUID.")


async def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--address", help="connect to this address directly (skip scan)")
    ap.add_argument("--timeout", type=float, default=10.0, help="scan duration in seconds")
    ap.add_argument("--scan-only", action="store_true", help="list devices only, don't connect")
    args = ap.parse_args()

    address = args.address
    if not address:
        address = await scan(args.timeout)
        if not address:
            print("\nNo LED DMX device found. Make sure the phone app is CLOSED "
                  "(the controller only accepts one connection).")
            return 1
    if args.scan_only:
        return 0

    await dump_gatt(address)
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
