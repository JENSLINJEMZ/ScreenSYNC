#!/usr/bin/env python3
"""LED Studio — SignalRGB-style desktop GUI for LEDDMX strips (Linux / Hyprland).

A window that mirrors the monitor as a canvas: place LED strips around its
edges, drag their start/length on sliders and the screen samples exactly the
zone under each segment — then custom-pattern BLE frames drive each segment
independently. Also does whole-average screen sync, hue cycle, live preview,
brightness/rate/colour-order, strip scanning, layout persistence.

Run:  .venv/bin/python sync_gui.py            (layout: led_layout.json)
Debug: LEDSYNC_DEBUG=1 …                      (per-frame colour log)
"""
import asyncio
import json
import os
import shutil
import subprocess
import sys
import threading
import time

import numpy as np

import gi
gi.require_version("Gtk", "4.0")
from gi.repository import Gtk, GLib

from bleak import BleakClient, BleakScanner
from screen_sync import (CHAR_UUID, POWER_ON_FRAME, ENGINES,
                         build_color_frame, bluetooth_scan_warmup, parse_ppm)

HERE = os.path.dirname(os.path.abspath(__file__))
CONFIG_PATH = os.path.join(HERE, "led_layout.json")
NAME_PREFIXES = ("LED DMX", "LEDDMX", "LED-DMX")

MODES = ["GD", "FD", "FW", "FS", "PU", "FL", "HO"]
DISPLAY_CHOICES = ["zones (edge sync)", "average", "cycle", "off"]
ORDER_CHOICES = ["rgb", "rbg", "grb", "gbr", "brg", "bgr"]
EDGE_FRAC = 0.07   # band thickness sampled at each screen edge
TEST_SIDE_COLORS = {"top": (255, 0, 0), "right": (0, 255, 0),
                    "bottom": (0, 0, 255), "left": (255, 255, 0)}

DEFAULT_CONFIG = {
    "monitor": "", "order": "rgb", "rate": 5.0, "brightness": 100.0,
    "display_mode": "GD",
    "strips": [{
        "mac": "", "name": "", "leds": 40,
        "start_corner": "top-left", "direction": "clockwise",
        "sides": ["top", "right", "bottom", "left"],
        "reverse": False, "display": "zones (edge sync)",
    }],
}

# ---------------------------------------------------------------------------
# Protocol helpers
# ---------------------------------------------------------------------------

def channels(rgb, order):
    r, g, b = rgb
    ch = {"r": r, "g": g, "b": b}
    return ch[order[0]], ch[order[1]], ch[order[2]]


def list_color_frame(pos, rgb, size, order):
    """Set colour `pos` (1-based) in a custom-pattern list of `size` colours."""
    c1, c2, c3 = channels(rgb, order)
    return bytes([0x7B, pos, 0x0E, 0xFD, c1, c2, c3, size, 0xBF])


def mode_frame(mode):
    """Custom-pattern display mode (GD..HO ordinal), 7B FF 13 <idx> …."""
    return bytes([0x7B, 0xFF, 0x13, MODES.index(mode), 0xFF, 0xFF, 0xFF, 0xFF, 0xBF])


def supports_write_without_response(client):
    try:
        char = client.services.get_characteristic(CHAR_UUID)
        return char is not None and "write-without-response" in char.properties
    except Exception:
        return False


# ---------------------------------------------------------------------------
# Shared state + sync engine (runs asyncio in a thread; GTK talks via idle_add)
# ---------------------------------------------------------------------------

class SyncEngine(threading.Thread):

    def __init__(self, settings, on_status, on_live):
        super().__init__(daemon=True)
        self.settings = settings          # dict, mutated live by the GUI
        self.on_status = on_status        # cb(str)          from engine thread
        self.on_live = on_live            # cb(dict mac->zone rgb list)
        self.stop_evt = threading.Event()
        self.lock = threading.Lock()
        self.latest = {"frame": 0, "strips": []}
        self._last_slots = {}      # mac -> previously uploaded slot colours
        self._last_mode = {}       # mac -> display mode currently armed

    def run(self):
        print("[engine] thread started", file=sys.stderr, flush=True)
        try:
            asyncio.run(self._main())
        except Exception as exc:
            self.on_status(f"engine stopped: {exc}")

    def stop(self):
        self.stop_evt.set()

    # -- async core ---------------------------------------------------------

    async def _main(self):
        loop = asyncio.get_running_loop()
        self.loop = loop
        await loop.run_in_executor(None, bluetooth_scan_warmup, None, 6)
        self.on_status("capturing…")
        tasks = [loop.create_task(self._producer())]
        for idx in range(8):                       # slot per possible strip
            tasks.append(loop.create_task(self._strip_loop(idx)))
        try:
            while not self.stop_evt.is_set():
                await asyncio.sleep(0.2)
        finally:
            for t in tasks:
                t.cancel()
            await asyncio.gather(*tasks, return_exceptions=True)

    async def _grab(self):
        s = dict(self.settings)
        monitor = s.get("monitor") or ""
        cmd = None
        for name, eng in ENGINES.items():
            if shutil.which(eng.cmd[0]):
                cmd = list(eng.cmd)
                break
        if cmd is None:
            raise RuntimeError("no capture engine (install grim)")
        if monitor:
            cmd.insert(-1, "-o"); cmd.insert(-1, monitor)
        proc = await asyncio.create_subprocess_exec(
            *cmd, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.DEVNULL)
        data, _ = await proc.communicate()
        if proc.returncode != 0 or not data:
            raise RuntimeError(f"capture failed (exit {proc.returncode})")
        return parse_ppm(data)

    def _state_snapshot(self):
        with self.lock:
            return dict(self.latest)

    async def _producer(self):
        log = os.environ.get("LEDSYNC_DEBUG")
        slow = 0
        while not self.stop_evt.is_set():
            s = dict(self.settings)
            t0 = time.perf_counter()
            try:
                w, h, mv = await self._grab()
                arr = np.frombuffer(mv, np.uint8).reshape(h, w, 3).astype(np.float32)
                strips_out = []
                for strip in s.get("strips", []):
                    mode = strip.get("display", DISPLAY_CHOICES[0])
                    if mode == "off":
                        strips_out.append({"mac": strip.get("mac", ""), "rgb": []})
                    elif mode == "zones":
                        if s.get("test"):
                            rgb_list = [apply_brightness(TEST_SIDE_COLORS[side], float(s.get("brightness", 100)))
                                        for side, _x, _y in perimeter_leds(strip)]
                        else:
                            rgb_list = perimeter_colors(arr, w, h, strip, float(s.get("brightness", 100)))
                        strips_out.append({"mac": strip.get("mac", ""), "rgb": rgb_list})
                    else:                              # average | cycle | off
                        rgb = whole_average(arr, float(s.get("brightness", 100)))
                        if mode == "cycle":
                            rgb = hue_color(time.time())
                        strips_out.append({"mac": strip.get("mac", ""), "rgb": [rgb]})
                with self.lock:
                    self.latest = {"frame": self.latest["frame"] + 1, "strips": strips_out}
                self.on_live({st["mac"]: st["rgb"] for st in strips_out})
                if log:
                    print("frame: " + " | ".join(f"{st['mac'][-5:]}:{st['rgb'][:3]}"
                                                 for st in strips_out), file=sys.stderr)
                slow = 0
            except Exception as exc:
                slow += 1
                self.on_status(f"capture: {exc}")
                await asyncio.sleep(0.5 if slow < 2 else 3.0)
            rate = max(0.2, float(s.get("rate") or 2.0))
            await asyncio.sleep(max(0.0, 1.0 / rate - (time.perf_counter() - t0)))

    async def _strip_loop(self, idx):
        backoff = 2.0
        while not self.stop_evt.is_set():
            strip = (dict(self.settings).get("strips") or [{}])[idx:idx + 1]
            if not strip:
                await asyncio.sleep(0.5); continue
            strip = strip[0]
            mac = strip.get("mac", "")
            if not mac:
                await asyncio.sleep(0.5); continue
            try:
                async with BleakClient(mac, timeout=25) as client:
                    self.on_status(f"{mac[-5:]}: connected")
                    self._last_slots.pop(mac, None)      # force full refresh
                    self._last_mode.pop(mac, None)
                    use_resp = not supports_write_without_response(client)
                    backoff = 2.0
                    await client.write_gatt_char(CHAR_UUID, POWER_ON_FRAME, response=use_resp)
                    await asyncio.sleep(0.3)          # let service cache settle
                    last = -1
                    while not self.stop_evt.is_set():
                        cur = (dict(self.settings).get("strips") or [{}])[idx:idx + 1]
                        if cur and cur[0].get("mac") != mac:
                            break                                   # config changed → reconnect
                        snap = self._state_snapshot()
                        if snap["frame"] != last:
                            last = snap["frame"]
                            rgb_list = next((st["rgb"] for st in snap["strips"]
                                             if st["mac"] == mac), None)
                            if rgb_list:
                                await self._push(mac, client, use_resp, strip, rgb_list)
                        await asyncio.sleep(0.03)
            except Exception as exc:
                if self.stop_evt.is_set():
                    break
                stale = "not found" in str(exc)
                self.on_status(f"{mac[-5:]}: {type(exc).__name__}: {exc}")
                if stale:
                    await asyncio.get_running_loop().run_in_executor(
                        None, bluetooth_scan_warmup, mac, 6)
                    await asyncio.sleep(1.5)
                else:
                    await asyncio.sleep(backoff)
                    backoff = min(backoff * 2, 30.0)

    async def _push(self, mac, client, use_resp, strip, rgb_list):
        s = dict(self.settings)
        order = s.get("order") or "rgb"
        strip_mode = strip.get("display", DISPLAY_CHOICES[0])
        w = lambda b: client.write_gatt_char(CHAR_UUID, b, response=use_resp)
        if strip_mode == "zones":
            n = max(1, len(rgb_list))
            prev = self._last_slots.get(mac)
            changed = []
            for pos, rgb in enumerate(rgb_list, start=1):
                # only re-upload slots whose colour moved enough to matter
                if not prev or pos > len(prev):
                    changed.append((pos, rgb))
                else:
                    pr, pg, pb = prev[pos - 1]
                    if abs(rgb[0] - pr) + abs(rgb[1] - pg) + abs(rgb[2] - pb) > 3:
                        changed.append((pos, rgb))
            for pos, rgb in changed:
                await w(list_color_frame(pos, rgb, n, order))
            self._last_slots[mac] = list(rgb_list)
            # (re)arm the display mode only when actually different
            cur_mode = s.get("display_mode") or "GD"
            if self._last_mode.get(mac) != cur_mode:
                await w(mode_frame(cur_mode))
                self._last_mode[mac] = cur_mode
        else:
            r, g, b = rgb_list[0]
            await w(build_color_frame(r, g, b, order))


# ---------------------------------------------------------------------------
# Screen sampling: strips laid as a path around the monitor perimeter
# ---------------------------------------------------------------------------

CORNERS = ["top-left", "top-right", "bottom-right", "bottom-left"]
SIDE_BY_PAIR = {("top-left", "top-right"): "top",
                ("top-right", "bottom-right"): "right",
                ("bottom-right", "bottom-left"): "bottom",
                ("bottom-left", "top-left"): "left"}


def _mean_rgb(arr, x0, x1, y0, y1):
    x0, x1 = max(0, int(x0)), max(1, int(x1))
    y0, y1 = max(0, int(y0)), max(1, int(y1))
    sub = arr[y0:y1: max(1, (y1 - y0) // 8),
              x0:x1: max(1, (x1 - x0) // 8)]
    return sub.reshape(-1, 3).mean(axis=0)


CORNER_XY = {"top-left": (0.0, 0.0), "top-right": (1.0, 0.0),
             "bottom-right": (1.0, 1.0), "bottom-left": (0.0, 1.0)}


def strip_path(strip, weights=None):
    """Walking order around the monitor: [(side, from_corner, to_corner, f_end)].

    Start corner + direction decide which corner walks to which; `sides`
    must include every side the physical strip crosses. Segments are evenly
    spaced along the walk using `weights` (side → relative length).
    """
    di = 1 if strip.get("direction", "clockwise") == "clockwise" else -1
    ci = CORNERS.index(strip.get("start_corner", "top-left"))
    chosen = list(strip.get("sides") or ["top", "right", "bottom", "left"])
    total = sum((weights or {}).get(s, 1.0) for s in chosen) or 1.0

    segs, acc = [], 0.0
    for _ in range(4):
        a, b = CORNERS[ci % 4], CORNERS[(ci + di) % 4]
        side = SIDE_BY_PAIR.get((a, b)) or SIDE_BY_PAIR.get((b, a))
        if side and side in chosen:
            acc += (weights or {}).get(side, 1.0) / total
            segs.append((side, a, b, acc))
        ci += di
    return segs


def path_led_count(strip):
    return max(1, int(strip.get("leds") or 40))


def perimeter_leds(strip, weights=None):
    """[(side, x, y)] normalized position for every LED, in physical order."""
    n = path_led_count(strip)
    out, prev = [], 0
    for side, a, b, f_end in strip_path(strip, weights):
        bnd = round(n * f_end)
        k = max(1, bnd - prev)
        (x0, y0), (x1, y1) = CORNER_XY[a], CORNER_XY[b]
        for i in range(k):
            t = (i + 0.5) / k
            out.append((side, x0 + (x1 - x0) * t, y0 + (y1 - y0) * t))
        prev = bnd
    return out[:n]


def led_sample_rect(w, h, x, y, thick=EDGE_FRAC):
    """Screen band inward from the LED's edge position (corners get squares)."""
    def span(v):
        if v < 0.03:     return 0.0, thick      # hugging left/top edge
        if v > 0.97:     return 1 - thick, 1.0  # hugging right/bottom edge
        return v - 0.03, v + 0.03               # mid-edge: local strip
    x0, x1 = span(x)
    y0, y1 = span(y)
    return (w * x0, max(1, w * x1), h * y0, max(1, h * y1))


def perimeter_colors(arr, w, h, strip, brightness):
    """Average the screen area under each LED of a perimeter-wrapped strip."""
    out = []
    for side, x, y in perimeter_leds(strip):
        x0, x1, y0, y1 = led_sample_rect(w, h, x, y)
        out.append(apply_brightness(_mean_rgb(arr, x0, x1, y0, y1), brightness))
    if strip.get("reverse"):
        out.reverse()
    return out


def whole_average(arr, brightness):
    return apply_brightness(arr.reshape(-1, 3).mean(axis=0), brightness)


def apply_brightness(rgb, brightness):
    f = brightness / 100.0
    return tuple(int(max(0, min(255, round(v * f)))) for v in rgb)


def hue_color(t):
    import colorsys
    h = (t / 120.0) % 1.0                                  # 2-minute full cycle
    r, g, b = colorsys.hsv_to_rgb(h, 1.0, 1.0)
    return (round(r * 255), round(g * 255), round(b * 255))


def monitor_names():
    try:
        out = subprocess.run(["hyprctl", "-j", "monitors"], capture_output=True,
                             text=True, timeout=5)
        return [m["name"] for m in json.loads(out.stdout or "[]")]
    except Exception:
        return []


# ---------------------------------------------------------------------------
# GTK UI
# ---------------------------------------------------------------------------

CSS = """
window { background: #0b0e14; color: #e9ecf5; }
.hdrbar { background: #131722; border-bottom: 1px solid #23283b; }
.card  { background: rgba(255,255,255,0.05); border-radius: 12px; padding: 12px; }
.card2 { background: rgba(255,255,255,0.03); border-radius: 8px;  padding: 8px; }
h1 { font-size: 22px; font-weight: bold; }
.dim { color: #8b93ac; font-size: 12px; }
.pill { border-radius: 18px; padding: 6px 16px; font-weight: bold; }
.pill-start { background: #7c4dff; color: #ffffff; }
.pill-stop  { background: #3a4159; color: #e9ecf5; }
.stripcard { background: rgba(255,255,255,0.05); border-radius: 10px; padding: 10px; border: 1px solid transparent; }
.stripcard.sel { border: 1px solid #7c4dff; background: rgba(124,77,255,0.12); }
label.title { font-weight: 700; }
"""


class StudioWindow(Gtk.ApplicationWindow):
    def __init__(self, app):
        super().__init__(application=app)
        self.add_css_class("window")
        self.set_default_size(1180, 760)
        self.load_config()
        monitors = monitor_names()

        self.monitor_options = monitors or ["DP-1"]
        self.selected = 0
        self.live = {}
        self.scan_macs = []
        self.engine = None

        self._build_ui()

    # -- config -------------------------------------------------------------
    def load_config(self):
        cfg = dict(DEFAULT_CONFIG)
        try:
            with open(CONFIG_PATH) as f:
                cfg.update(json.load(f))
        except Exception:
            pass
        cfg.setdefault("strips", [dict(DEFAULT_CONFIG["strips"][0])])
        for st in cfg["strips"]:
            # migrate pre-path layouts by resetting strip geometry
            if "edge" in st:
                st = {
                    "mac": st.get("mac", ""), "name": st.get("name", ""),
                    "leds": st.get("leds", st.get("zones", 40)),
                    "start_corner": "top-left", "direction": "clockwise",
                    "sides": ["top", "right", "bottom", "left"],
                    "reverse": bool(st.get("reverse")),
                    "display": st.get("display", DISPLAY_CHOICES[0]),
                }
            st.setdefault("leds", 40)
            st.setdefault("start_corner", "top-left")
            st.setdefault("direction", "clockwise")
            st.setdefault("sides", ["top", "right", "bottom", "left"])
            st.setdefault("reverse", False)
            st.setdefault("mac", "")
            st.setdefault("name", "")
            st.setdefault("display", DISPLAY_CHOICES[0])
        self.cfg = cfg

    def save_config(self):
        try:
            with open(CONFIG_PATH, "w") as f:
                json.dump(self.cfg, f, indent=2)
        except Exception:
            pass

    # -- construction -------------------------------------------------------
    def _build_ui(self):
        self._apply_css()

        outer = Gtk.Overlay()
        self.set_child(outer)
        # header
        hdr = Gtk.HeaderBar()
        hdr.add_css_class("hdrbar")
        self.set_titlebar(hdr)
        t = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        title = Gtk.Label(label="LED Studio"); title.add_css_class("h1")
        sub = Gtk.Label(label="SignalRGB-style screen sync")
        sub.add_css_class("dim")
        t.append(title); t.append(sub)
        hdr.set_title_widget(t)

        self.btn_start = Gtk.Button(label="▶  Sync")
        self.btn_start.add_css_class("pill"); self.btn_start.add_css_class("pill-start")
        self.btn_start.connect("clicked", self.on_start_stop)
        hdr.pack_end(self.btn_start)

        main = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=10)
        main.set_margin_top(8); main.set_margin_bottom(8)
        main.set_margin_start(8); main.set_margin_end(8)
        outer.set_child(main)

        # ---- left: monitor canvas
        self.canvas = Gtk.DrawingArea()
        self.canvas.set_hexpand(True); self.canvas.set_vexpand(True)
        self.canvas.set_draw_func(self.draw_canvas)
        click = Gtk.GestureClick()
        click.connect("released", self.on_canvas_click)
        self.canvas.add_controller(click)
        canvas_card = Gtk.Frame()
        canvas_card.set_child(self.canvas)
        canvas_card.add_css_class("card")
        canvas_card.set_hexpand(True)
        main.append(canvas_card)

        # ---- right: control panel
        panel = Gtk.ScrolledWindow()
        panel.set_policy(Gtk.PolicyType.NEVER, Gtk.PolicyType.AUTOMATIC)
        panel.set_size_request(360, -1)
        panel.set_hexpand(False)
        main.append(panel)

        p = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=10)
        panel.set_child(p)

        # global card
        g = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8)
        g.add_css_class("card")
        p.append(g)
        g.append(self._lbl("Screen sync", big=True))
        self.combo_mon = self._combo(self.monitor_options)
        mon_row = Gtk.Box(spacing=6)
        mon_row.append(self._lbl("Monitor", dim=True))
        mon_row.append(self.combo_mon)
        g.append(mon_row)
        mon = self.cfg.get("monitor") or self.monitor_options[0]
        # choose best default
        if mon not in self.monitor_options:
            mon = self.monitor_options[0]
        self.combo_mon.set_active(self.monitor_options.index(mon))
        self.combo_mon.connect("changed", self.on_any_change)

        self.combo_disp = self._combo(MODES)
        disp_row = Gtk.Box(spacing=6)
        disp_row.append(self._lbl("Zone mode", dim=True))
        disp_row.append(self.combo_disp)
        disp_row.append(self._lbl("ⓘ what the colour-list does — try each", dim=True))
        g.append(disp_row)
        self.combo_disp.set_active(MODES.index(self.cfg.get("display_mode", "GD")))
        self.combo_disp.connect("changed", self.on_any_change)

        self.scale_bright = self._scale(0, 100, self.cfg.get("brightness", 100.0))
        g.append(self._labeled("Brightness", self.scale_bright))
        self.scale_bright.connect("value-changed", self.on_any_change)
        self.scale_rate = self._scale(0.2, 8, self.cfg.get("rate", 2.0), digits=1)
        g.append(self._labeled("Update rate (fps)", self.scale_rate))
        self.scale_rate.connect("value-changed", self.on_any_change)
        self.combo_order = self._combo(ORDER_CHOICES)
        order_row = Gtk.Box(spacing=6)
        order_row.append(self._lbl("Colour order", dim=True))
        order_row.append(self.combo_order)
        g.append(order_row)
        self.combo_order.set_active(ORDER_CHOICES.index(self.cfg.get("order", "rgb")))
        self.combo_order.connect("changed", self.on_any_change)

        self.chk_test = Gtk.CheckButton(label="Test pattern (red top / green right / blue bottom / yellow left)")
        self.chk_test.set_active(bool(self.cfg.get("test")))
        self.chk_test.connect("toggled", self.on_test_toggle)
        g.append(self.chk_test)

        scan_row = Gtk.Button(label="🔍  Scan strips")
        scan_row.connect("clicked", self.on_scan)
        scan_row.add_css_class("pill")
        g.append(scan_row)

        # strips
        self.strips_box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8)
        p.append(self.strips_box)
        self.rebuild_strips()

        add_row = Gtk.Button(label="＋  Add strip")
        add_row.connect("clicked", self.on_add_strip)
        add_row.add_css_class("pill")
        p.append(add_row)

        # status
        self.status = Gtk.Label(label="Idle")
        self.status.add_css_class("dim")
        st_card = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=4)
        st_card.append(self.status)
        st_card.add_css_class("card2")
        p.append(st_card)

        self.connect("close-request", self.on_close)

    # -- helpers ------------------------------------------------------------
    def _lbl(self, txt, dim=False, big=False):
        l = Gtk.Label(label=txt, xalign=0)
        l.set_xalign(0.0)
        if dim: l.add_css_class("dim")
        if big: l.add_css_class("title")
        return l

    def _combo(self, items):
        c = Gtk.ComboBoxText()
        for i in items:
            c.append_text(i)
        c.set_hexpand(True)
        return c

    def _scale(self, lo, hi, val, digits=0):
        step = max(1e-3, (hi - lo) / 100.0)
        page = max(1e-2, (hi - lo) / 10.0)
        adj = Gtk.Adjustment.new(val, lo, hi, step, page, 0.0)
        s = Gtk.Scale(orientation=Gtk.Orientation.HORIZONTAL)
        s.set_digits(digits)
        s.set_adjustment(adj)
        s.set_hexpand(True)
        return s

    def _labeled(self, text, widget):
        v = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=2)
        v.append(self._lbl(text, dim=True)); v.append(widget)
        return v

    def _apply_css(self):
        pr = Gtk.CssProvider()
        pr.load_from_string(CSS)
        disp = self.get_display()
        Gtk.StyleContext.add_provider_for_display(disp, pr, Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION)

    def rebuild_strips(self):
        while True:
            kid = self.strips_box.get_first_child()
            if kid is None:
                break
            self.strips_box.remove(kid)
        for idx, strip in enumerate(self.cfg["strips"]):
            card = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=6)
            card.add_css_class("stripcard")
            if idx == self.selected:
                card.add_css_class("sel")
            head = Gtk.Box(spacing=6)
            nm = strip.get("mac") or "unassigned"
            head.append(self._lbl(f"Light {idx + 1} — {nm}", big=True))
            card.append(head)

            mac_row = Gtk.Box(spacing=6)
            mac_row.append(self._lbl("MAC", dim=True))
            cc = Gtk.ComboBoxText()
            cc.append_text(strip.get("mac") or "(pick)")
            for m in self.scan_macs:
                if m not in [strip.get("mac")] and m != "(pick)":
                    cc.append_text(m)
            cc.append_text("(pick from scan…)")
            cc.set_active(0)
            cc._strip_idx = idx
            cc.connect("changed", self.on_mac_changed)
            mac_row.append(cc)
            card.append(mac_row)

            r1 = Gtk.Box(spacing=6)
            corner = self._combo(CORNERS)
            corner.set_active(CORNERS.index(strip.get("start_corner", "top-left")))
            corner._strip_idx = idx; corner._field = "start_corner"
            corner.connect("changed", self.on_strip_changed)
            r1.append(self._lbl("1st LED", dim=True)); r1.append(corner)
            dr = self._combo(["clockwise", "counter-cw"])
            dr.set_active(0 if strip.get("direction", "clockwise") == "clockwise" else 1)
            dr._strip_idx = idx; dr._field = "direction"
            dr.connect("changed", self.on_strip_changed)
            r1.append(dr)
            card.append(r1)

            sides_row = Gtk.Box(spacing=4)
            sides_row.append(self._lbl("Side", dim=True))
            for side in ("top", "right", "bottom", "left"):
                cb = Gtk.CheckButton(label=side)
                cb.set_active(side in (strip.get("sides") or []))
                cb._strip_idx = idx; cb._side = side
                cb.connect("toggled", self.on_side_toggle)
                sides_row.append(cb)
            card.append(sides_row)
            rev = Gtk.CheckButton(label="flip direction (LED 1 at the other end)")
            rev.set_active(bool(strip.get("reverse")))
            rev._strip_idx = idx
            rev.connect("toggled", self.on_strip_changed)
            card.append(rev)

            r2 = Gtk.Box(spacing=6)
            r2.append(self._lbl("LEDs", dim=True))
            zn = Gtk.SpinButton()
            adj = Gtk.Adjustment.new(int(strip.get("leds", 40)), 2, 200, 1, 10, 0)
            zn.set_adjustment(adj)
            zn._strip_idx = idx; zn._field = "leds"
            zn.connect("value-changed", self.on_strip_changed)
            zn.set_hexpand(True)
            r2.append(zn)
            disp = self._combo(DISPLAY_CHOICES)
            disp.set_active(DISPLAY_CHOICES.index(strip.get("display", DISPLAY_CHOICES[0])))
            disp._strip_idx = idx; disp._field = "disp"
            disp.connect("changed", self.on_strip_changed)
            r2.append(disp)
            card.append(r2)

            but_del = Gtk.Button(label="Remove")
            but_del._strip_idx = idx
            but_del.connect("clicked", self.on_remove_strip)
            card.append(but_del)

            card._strip_idx = idx
            self.strips_box.append(card)

    # -- callbacks ----------------------------------------------------------
    def sync_setting(self):
        self.cfg["monitor"] = self.combo_mon.get_active_text() or ""
        self.cfg["display_mode"] = MODES[self.combo_disp.get_active()] if self.combo_disp.get_active() != -1 else "GD"
        self.cfg["brightness"] = float(self.scale_bright.get_value()) if self.scale_bright else None
        self.cfg["rate"] = float(self.scale_rate.get_value()) if self.scale_rate else None
        self.cfg["order"] = ORDER_CHOICES[self.combo_order.get_active()] if self.combo_order.get_active() != -1 else "rgb"
        for k in ("brightness", "rate"):
            if self.cfg.get(k) is None:
                del self.cfg[k]
        self.save_config()
        self.restart_engine()

    def on_any_change(self, w=None):
        self.sync_setting()

    def on_test_toggle(self, w):
        self.cfg["test"] = bool(w.get_active())
        self.save_config()

    def on_mac_changed(self, combo):
        idx = combo._strip_idx if hasattr(combo, "_strip_idx") else 0
        txt = combo.get_active_text()
        if not txt or txt.startswith("(pick"):
            return
        self.cfg["strips"][idx]["mac"] = txt
        self.cfg["strips"][idx]["name"] = txt
        self.save_config()
        self.restart_engine()

    def on_side_toggle(self, w):
        idx = w._strip_idx
        strip = self.cfg["strips"][idx]
        current = list(strip.get("sides") or [])
        sides = [s for s in ("top", "right", "bottom", "left")
                 if (s != w._side and s in current) or
                    (s == w._side and w.get_active())]
        strip["sides"] = sides or ["top"]
        self.selected = idx
        self.save_config()
        self.restart_engine()

    def on_strip_changed(self, w):
        idx = w._strip_idx
        field = getattr(w, "_field", None)
        strip = self.cfg["strips"][idx]
        if isinstance(w, Gtk.CheckButton):
            strip["reverse"] = w.get_active()
        elif field == "start_corner":
            strip["start_corner"] = w.get_active_text() or "top-left"
        elif field == "direction":
            strip["direction"] = w.get_active_text() or "clockwise"
        elif field == "leds":
            strip["leds"] = int(w.get_value())
        elif field == "disp":
            strip["display"] = w.get_active_text() or DISPLAY_CHOICES[0]
        self.selected = idx
        self.save_config()
        self.restart_engine()

    def on_add_strip(self, b):
        new = dict(DEFAULT_CONFIG["strips"][0])
        new.update({"mac": "", "name": "", "leds": 10,
                    "display": DISPLAY_CHOICES[0]})
        self.cfg["strips"].append(new)
        self.selected = len(self.cfg["strips"]) - 1
        self.save_config()
        self.rebuild_strips()
        self.restart_engine()

    def on_remove_strip(self, b):
        idx = b._strip_idx
        if len(self.cfg["strips"]) > idx >= 0:
            self.cfg["strips"].pop(idx)
            self.selected = max(0, self.selected - 1) if self.selected >= idx else self.selected
            self.save_config()
            self.rebuild_strips()
            self.restart_engine()

    def on_scan(self, b):
        b.set_sensitive(False)

        def work():
            found = asyncio.run(BleakScanner.discover(timeout=8.0))
            macs = [d.address for d in found
                    if d.name and d.name.upper().startswith(NAME_PREFIXES)]
            names = {d.address: d.name for d in found
                     if d.name and d.name.upper().startswith(NAME_PREFIXES)}
            GLib.idle_add(self.on_scan_done, macs, names)
        threading.Thread(target=work, daemon=True).start()

    def on_scan_done(self, macs, names):
        self.scan_macs = [f"{a}  {names[a]}" for a in macs]
        self.status.set_text(f"found {len(macs)} strip(s)" if macs else "no LEDDMX strips found")
        self.rebuild_strips()
        return GLib.SOURCE_REMOVE

    # -- start/stop ---------------------------------------------------------
    def on_start_stop(self, b):
        if self.engine and self.engine.is_alive():
            self.engine.stop()
            self.status.set_text("sync engine stopped")
            self.btn_start.set_label("▶  Sync")
        else:
            self.btn_start.set_label("■  Stop")
            self.status.set_text("starting…")
            self.restart_engine(fresh=True)

    def restart_engine(self, fresh=False):
        if self.engine and self.engine.is_alive() and not fresh:
            return
        if self.engine and self.engine.is_alive():
            self.engine.stop(); self.engine.join(3)
        self.engine = SyncEngine(self.cfg, self.cb_status, self.cb_live)
        self.engine.start()

    def cb_status(self, msg):
        GLib.idle_add(self.status.set_text, str(msg))
        print(f"[engine] {msg}", file=sys.stderr, flush=True)
        return False

    def cb_live(self, macs_zones):
        GLib.idle_add(self._apply_live, macs_zones)
        return False

    def _apply_live(self, macs_zones):
        self.live = macs_zones
        self.canvas.queue_draw()
        return GLib.SOURCE_REMOVE

    # -- canvas -------------------------------------------------------------
    def hit_led(self, x, y, strip, m_w, m_h, mx, my):
        """distance from canvas point to nearest LED dot of `strip`."""
        weights = {"top": m_w, "bottom": m_w, "left": m_h, "right": m_h}
        best = 1e18
        for side, fx, fy in perimeter_leds(strip, weights):
            px = mx + fx * m_w
            py = my + fy * m_h
            if side == "top": py = my - 7
            elif side == "bottom": py = my + m_h + 7
            if side == "left": px = mx - 7
            elif side == "right": px = mx + m_w + 7
            d = (px - x) ** 2 + (py - y) ** 2
            best = min(best, d)
        return best

    def draw_canvas(self, da, ctx, w, h):
        ctx.set_source_rgb(0.055, 0.066, 0.087); ctx.paint()
        chrome = 30
        m_w, m_h = w - 150, h - 24 - chrome          # keep monitor aspect-ish
        m_w = min(m_w, m_h * 16 / 9.0)
        mx, my = (w - m_w) / 2, 12
        # monitor bezel
        ctx.set_source_rgb(0.13, 0.15, 0.22)
        ctx.rectangle(mx - 6, my - 6, m_w + 12, m_h + 12)
        ctx.fill()
        ctx.set_source_rgb(0.09, 0.1, 0.14)
        ctx.rectangle(mx, my, m_w, m_h)
        ctx.fill()
        ctx.set_source_rgb(0.25, 0.28, 0.38)
        ctx.select_font_face("sans-serif", 0, 0)
        ctx.set_font_size(15)
        ctx.move_to(mx + 8, my + m_h - 10)
        ctx.show_text(f"{self.cfg.get('monitor') or 'monitor'}  —  edge-sync layout")

        for idx, strip in enumerate(self.cfg.get("strips", [])):
            weights = {"top": m_w, "bottom": m_w, "left": m_h, "right": m_h}
            leds = perimeter_leds(strip, weights)
            rgb_list = self.live.get(strip.get("mac", "")) or []
            if self.cfg.get("test"):
                rgb_list = [TEST_SIDE_COLORS[side] for side, _fx, _fy in leds]
            for i, (side, fx, fy) in enumerate(leds):
                px = mx + fx * m_w
                py = my + fy * m_h
                # push dots onto the bezel line
                if side == "top": py = my - 7
                elif side == "bottom": py = my + m_h + 7
                if side == "left": px = mx - 7
                elif side == "right": px = mx + m_w + 7
                r, g, b = [v / 255.0 for v in
                           (rgb_list[i] if i < len(rgb_list) else (30, 33, 46))]
                ctx.set_source_rgb(r, g, b)
                ctx.arc(px, py, 6, 0, 6.3)
                ctx.fill()
                if idx == self.selected:
                    ctx.set_source_rgb(0.486, 0.302, 1.0)
                    ctx.set_line_width(1)
                    ctx.arc(px, py, 6.8, 0, 6.3)
                    ctx.stroke()
        ctx.set_source_rgb(0.25, 0.28, 0.38)
        ctx.set_font_size(12)
        ctx.move_to(mx, my + m_h + 26)
        ctx.show_text("dots = LEDs in physical order (LED 1 highlighted when selected)")

    def on_canvas_click(self, gest, state, x, y):
        w, h = self.get_allocated_width(), self.get_allocated_height()
        chrome = 30
        m_w = min(w - 150, h - 24 - chrome * 1)
        m_w = min(m_w, (h - 24 - chrome) * 16 / 9.0)
        m_h = (h - 24 - chrome)
        mx, my = (w - m_w) / 2, 12
        best, closest = 1e18, 0
        for idx, strip in enumerate(self.cfg.get("strips", [])):
            d = self.hit_led(x, y, strip, m_w, m_h, mx, my)
            if d < best:
                best, closest = d, idx
        self.selected = closest
        self.rebuild_strips()
        return True

    def on_close(self, *a):
        if self.engine and self.engine.is_alive():
            self.engine.stop()
        self.save_config()
        return False


class StudioApp(Gtk.Application):
    def __init__(self):
        super().__init__(application_id="org.ledcycle.Studio")

    def do_activate(self):
        win = StudioWindow(self)
        win.present()
        if os.environ.get("LEDSYNC_AUTOSTART"):
            win.on_start_stop(win.btn_start)


def main():
    app = StudioApp()
    return app.run(sys.argv)


if __name__ == "__main__":
    sys.exit(main())
