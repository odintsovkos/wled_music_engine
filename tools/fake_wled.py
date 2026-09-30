"""Эмулятор WLED для полевой проверки.

- UDP 11988: Audio Sync v2 (режим Audio Reactive);
- UDP 4048: DDP (режим RGB Engine) — сборка кадров по push-флагу, кадры/с, неполные кадры;
- HTTP 80: GET /json/info (с live/lm/lip), GET/POST /json/state, GET /json/eff.

Параметры: python tools/fake_wled.py [лог] [--leds N] [--matrix WxH] [--timeout СЕК]
"""
import argparse
import json
import socket
import struct
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

parser = argparse.ArgumentParser()
parser.add_argument("log", nargs="?", default="fake_wled.log")
parser.add_argument("--leds", type=int, default=60)
parser.add_argument("--matrix", default=None, help="например 16x16; задаёт leds = W*H")
parser.add_argument("--timeout", type=float, default=2.5, help="realtime-таймаут, с")
args = parser.parse_args()

LOG = args.log
MATRIX = tuple(int(v) for v in args.matrix.lower().split("x")) if args.matrix else None
LEDS = MATRIX[0] * MATRIX[1] if MATRIX else args.leds
EFFECTS = ["Solid", "Blink", "Breathe", "Wipe", "Rainbow", "Gravcenter", "GEQ"]

lock = threading.Lock()
stats = {"ok": 0, "bad": 0, "peaks": 0, "last": None, "last_t": 0.0, "src": None, "max_smth": 0.0}
ddp = {"frames": 0, "partial": 0, "bad": 0, "last_t": 0.0, "src": None, "leds": 0,
       "seq": None, "got": 0, "expect": 0, "luma": 0}
state = {
    "on": True, "bri": 128, "transition": 7, "ps": -1, "pl": -1, "lor": 0, "mainseg": 0,
    "seg": [{"id": 0, "start": 0, "stop": LEDS, "fx": 0, "sx": 128, "ix": 128, "pal": 0,
             "col": [[255, 160, 0], [0, 0, 0], [0, 0, 0]], "on": True, "bri": 255}],
}


def log(msg):
    line = time.strftime("%H:%M:%S ") + msg
    print(line, flush=True)
    with open(LOG, "a", encoding="utf-8") as f:
        f.write(line + "\n")


def live():
    """Realtime активен, пока DDP-кадры приходят чаще таймаута и нет live override."""
    with lock:
        return state["lor"] == 0 and ddp["last_t"] > 0 and time.time() - ddp["last_t"] < args.timeout


def audio_sync():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.bind(("0.0.0.0", 11988))
    while True:
        data, addr = s.recvfrom(256)
        if len(data) == 44 and data[:6] == b"00002\x00":
            raw, smth = struct.unpack_from("<ff", data, 8)
            peak = data[16]
            fft = list(data[18:34])
            mag, major = struct.unpack_from("<ff", data, 36)
            stats["ok"] += 1
            stats["peaks"] += 1 if peak else 0
            stats["last"] = (raw, smth, peak, fft, mag, major)
            stats["max_smth"] = max(stats["max_smth"], smth)
            stats["src"] = addr[0]
        else:
            stats["bad"] += 1
        stats["last_t"] = time.time()


def ddp_receiver():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.bind(("0.0.0.0", 4048))
    frame = bytearray(LEDS * 3)
    while True:
        data, addr = s.recvfrom(1500)
        if len(data) < 10 or data[0] & 0xC0 != 0x40:
            ddp["bad"] += 1
            continue
        push = bool(data[0] & 0x01)
        seq = data[1] & 0x0F
        offset, length = struct.unpack_from(">IH", data, 4)
        if 10 + length != len(data):
            ddp["bad"] += 1
            continue
        with lock:
            if seq != ddp["seq"]:
                if ddp["got"] and ddp["seq"] is not None:
                    ddp["partial"] += 1  # кадр без push-пакета
                ddp["seq"], ddp["got"] = seq, 0
            end = min(len(frame), offset + length)
            if offset < len(frame):
                frame[offset:end] = data[10:10 + end - offset]
            ddp["got"] += length
            ddp["expect"] = max(ddp["expect"], offset + length)
            ddp["last_t"] = time.time()
            ddp["src"] = addr[0]
            if push:
                if ddp["got"] < ddp["expect"]:
                    ddp["partial"] += 1
                else:
                    ddp["frames"] += 1
                ddp["leds"] = ddp["expect"] // 3
                n = max(1, len(frame) // 3)
                ddp["luma"] = sum(frame[i] * 54 + frame[i + 1] * 183 + frame[i + 2] * 19
                                  for i in range(0, len(frame) - 2, 3)) // 256 // n
                ddp["seq"], ddp["got"], ddp["expect"] = None, 0, 0


class Api(BaseHTTPRequestHandler):
    def send_json(self, obj, code=200):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/json/info":
            receiving = time.time() - stats["last_t"] < 2.5
            is_live = live()
            leds = {"count": LEDS}
            if MATRIX:
                leds["matrix"] = {"w": MATRIX[0], "h": MATRIX[1]}
            self.send_json({
                "ver": "0.16.0-fake", "name": "Fake WLED (PC)", "leds": leds,
                "live": is_live, "lm": "DDP" if is_live else "", "lip": ddp["src"] if is_live else "",
                "u": {"AudioReactive": ["on"],
                      "Audio Source": ["UDP sound sync", " - receiving" if receiving else " - idle"]},
            })
        elif self.path == "/json/state":
            with lock:
                self.send_json(state)
        elif self.path == "/json/eff":
            self.send_json(EFFECTS)
        else:
            self.send_json({"error": "not found"}, 404)
        log(f"HTTP GET {self.path} from {self.client_address[0]}")

    def do_POST(self):
        if self.path != "/json/state":
            self.send_json({"error": "not found"}, 404)
            return
        try:
            body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))) or b"{}")
        except ValueError:
            self.send_json({"error": 9}, 400)
            return
        with lock:
            if body.get("live") is False:
                ddp["last_t"] = 0.0  # немедленный выход из realtime
            for key in ("on", "bri", "lor", "ps", "transition"):
                if key in body:
                    state[key] = body[key]
            for seg in body.get("seg", []):
                target = next((s for s in state["seg"] if s["id"] == seg.get("id", 0)), None)
                if target:
                    target.update({k: v for k, v in seg.items() if k != "id"})
            reply = state if body.get("v") else {"success": True}
        self.send_json(reply)
        log(f"HTTP POST /json/state {json.dumps(body)} from {self.client_address[0]}")

    def log_message(self, *a):
        pass


def http():
    try:
        ThreadingHTTPServer(("0.0.0.0", 80), Api).serve_forever()
    except OSError as e:
        log(f"HTTP server failed: {e}")


threading.Thread(target=audio_sync, daemon=True).start()
threading.Thread(target=ddp_receiver, daemon=True).start()
threading.Thread(target=http, daemon=True).start()
log(f"fake WLED started: {LEDS} LED" + (f", matrix {MATRIX[0]}x{MATRIX[1]}" if MATRIX else ""))
prev = 0
prev_frames = 0
while True:
    time.sleep(2)
    n = stats["ok"]
    rate = (n - prev) / 2
    prev = n
    last = stats["last"]
    if last:
        raw, smth, peak, fft, mag, major = last
        log(f"sync pkts={n} rate={rate:.1f}/s bad={stats['bad']} peaks={stats['peaks']} src={stats['src']} "
            f"smth={smth:.0f} max_smth={stats['max_smth']:.0f} major={major:.0f}Hz mag={mag:.0f} fft={fft}")
    frames = ddp["frames"]
    if frames or ddp["bad"] or ddp["partial"]:
        log(f"ddp frames={frames} rate={(frames - prev_frames) / 2:.1f}/s leds={ddp['leds']} "
            f"partial={ddp['partial']} bad={ddp['bad']} luma={ddp['luma']} live={live()} src={ddp['src']}")
    prev_frames = frames
    if not last and not frames:
        log(f"idle: sync bad={stats['bad']} ddp bad={ddp['bad']}")
