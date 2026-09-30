"""Эмулятор WLED для полевой проверки: UDP Audio Sync v2 на 11988 + GET /json/info на 80."""
import json
import socket
import struct
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

LOG = sys.argv[1] if len(sys.argv) > 1 else "fake_wled.log"
stats = {"ok": 0, "bad": 0, "peaks": 0, "last": None, "last_t": 0.0, "src": None, "max_smth": 0.0}


def log(msg):
    line = time.strftime("%H:%M:%S ") + msg
    print(line, flush=True)
    with open(LOG, "a", encoding="utf-8") as f:
        f.write(line + "\n")


def udp():
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


class Info(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path != "/json/info":
            self.send_response(404)
            self.end_headers()
            return
        receiving = time.time() - stats["last_t"] < 2.5
        body = json.dumps({
            "ver": "0.16.0-fake", "name": "Fake WLED (PC)", "leds": {"count": 60},
            "u": {"AudioReactive": ["on"], "Audio Source": ["UDP sound sync", " - receiving" if receiving else " - idle"]},
        }).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)
        log(f"HTTP /json/info from {self.client_address[0]}")

    def log_message(self, *args):
        pass


def http():
    try:
        ThreadingHTTPServer(("0.0.0.0", 80), Info).serve_forever()
    except OSError as e:
        log(f"HTTP server failed: {e}")


threading.Thread(target=udp, daemon=True).start()
threading.Thread(target=http, daemon=True).start()
log("fake WLED started")
prev = 0
while True:
    time.sleep(2)
    n = stats["ok"]
    rate = (n - prev) / 2
    prev = n
    last = stats["last"]
    if last:
        raw, smth, peak, fft, mag, major = last
        log(f"pkts={n} rate={rate:.1f}/s bad={stats['bad']} peaks={stats['peaks']} src={stats['src']} "
            f"smth={smth:.0f} max_smth={stats['max_smth']:.0f} major={major:.0f}Hz mag={mag:.0f} fft={fft}")
    else:
        log(f"pkts=0 bad={stats['bad']}")
