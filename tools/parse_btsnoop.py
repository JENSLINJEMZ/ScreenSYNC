"""Extract ATT writes from an Android Bluetooth HCI snoop log.

Accepts either the raw btsnoop_hci.log or a whole adb bugreport .zip
(it finds the log inside automatically).

Usage:
    python tools/parse_btsnoop.py bugreport.zip
    python tools/parse_btsnoop.py btsnoop_hci.log
    python tools/parse_btsnoop.py bugreport.zip --all   # include notifications/reads too

Output: one line per ATT write the phone sent, then a summary of unique
payloads per characteristic handle with the byte positions that differ
highlighted (those are your RR/GG/BB bytes).
"""

import argparse
import io
import struct
import sys
import zipfile
from collections import OrderedDict

ATT_CID = 0x0004
OPCODES = {
    0x12: "WriteReq",
    0x52: "WriteCmd",
    0x16: "PrepWrite",
    0x1B: "Notify",
    0x1D: "Indicate",
}
WRITE_OPCODES = (0x12, 0x52, 0x16)


def load_btsnoop_bytes(path: str) -> bytes:
    if path.lower().endswith(".zip"):
        with zipfile.ZipFile(path) as zf:
            candidates = [n for n in zf.namelist() if "btsnoop" in n.lower() and n.lower().endswith(".log")]
            if not candidates:
                sys.exit("No btsnoop*.log found inside the zip. Was HCI snoop logging enabled?")
            # Prefer the plain current log over .last/backup files
            candidates.sort(key=lambda n: ("last" in n.lower(), n))
            print(f"Using {candidates[0]} from zip")
            return zf.read(candidates[0])
    with open(path, "rb") as f:
        return f.read()


def parse_records(blob: bytes):
    """Yield (timestamp_us, is_sent, hci_packet_bytes) from a btsnoop file."""
    if blob[:8] != b"btsnoop\x00":
        sys.exit("Not a btsnoop file (bad magic).")
    version, datalink = struct.unpack(">II", blob[8:16])
    if datalink not in (1001, 1002):
        sys.exit(f"Unsupported btsnoop datalink type {datalink}.")
    pos = 16
    while pos + 24 <= len(blob):
        orig_len, incl_len, flags, _drops, ts = struct.unpack(">IIIIq", blob[pos:pos + 24])
        pos += 24
        data = blob[pos:pos + incl_len]
        pos += incl_len
        is_sent = (flags & 0x01) == 0
        yield ts, is_sent, data, datalink


def extract_att(path: str, show_all: bool):
    rows = []  # (rel_ms, direction, conn_handle, opcode, att_handle, value)
    t0 = None
    for ts, is_sent, pkt, datalink in parse_records(load_btsnoop_bytes(path)):
        if datalink == 1002:  # H4: first byte is packet type
            if not pkt or pkt[0] != 0x02:  # ACL data only
                continue
            pkt = pkt[1:]
        if len(pkt) < 8:
            continue
        handle_flags, _acl_len = struct.unpack("<HH", pkt[:4])
        pb = (handle_flags >> 12) & 0b11
        if pb == 0b01:  # continuation fragment, no L2CAP header
            continue
        conn_handle = handle_flags & 0x0FFF
        l2cap_len, cid = struct.unpack("<HH", pkt[4:8])
        if cid != ATT_CID:
            continue
        att = pkt[8:8 + l2cap_len]
        if not att:
            continue
        opcode = att[0]
        if opcode not in OPCODES:
            continue
        if not show_all and not (is_sent and opcode in WRITE_OPCODES):
            continue
        if len(att) < 3:
            continue
        att_handle = struct.unpack("<H", att[1:3])[0]
        value = att[3:]
        if t0 is None:
            t0 = ts
        rows.append(((ts - t0) / 1000.0, "TX" if is_sent else "RX", conn_handle, opcode, att_handle, value))
    return rows


def print_rows(rows):
    print(f"\n{'time_ms':>10}  dir  conn  op        att_handle  value")
    for rel_ms, direction, conn, opcode, handle, value in rows:
        print(f"{rel_ms:>10.1f}  {direction}   {conn:#06x}  {OPCODES[opcode]:<9} 0x{handle:04x}      {value.hex(' ')}")


def print_summary(rows):
    by_handle: OrderedDict[int, OrderedDict[bytes, int]] = OrderedDict()
    for _, direction, _, opcode, handle, value in rows:
        if direction != "TX" or opcode not in WRITE_OPCODES:
            continue
        by_handle.setdefault(handle, OrderedDict())
        by_handle[handle][bytes(value)] = by_handle[handle].get(bytes(value), 0) + 1

    print("\n=== Summary: unique write payloads per handle ===")
    for handle, payloads in by_handle.items():
        print(f"\natt handle 0x{handle:04x} ({sum(payloads.values())} writes, {len(payloads)} unique):")
        for value, count in payloads.items():
            print(f"  x{count:<4} {value.hex(' ')}")
        same_len = [v for v in payloads if len(v) == len(next(iter(payloads)))]
        if len(same_len) >= 2:
            varying = [i for i in range(len(same_len[0])) if len({v[i] for v in same_len}) > 1]
            if varying:
                print(f"  -> byte positions that vary: {varying}  (likely colour bytes)")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("path", help="btsnoop_hci.log or adb bugreport .zip")
    ap.add_argument("--all", action="store_true", help="also show received notifications/indications")
    args = ap.parse_args()

    rows = extract_att(args.path, args.all)
    if not rows:
        sys.exit("No ATT writes found in the log. Did you change colours in the app while logging?")
    print_rows(rows)
    print_summary(rows)


if __name__ == "__main__":
    main()
