#!/usr/bin/env python3
"""Mock Chromecast for CPlay's Play-on-TV tests (test-only, no real device).

  :18009  CASTV2 over TLS (self-signed cert made with openssl on first start)
  :18010  HTTP  /debug/state  /debug/reset

Speaks enough of the protocol for a sender: CONNECT, PING/PONG, LAUNCH of the
Default Media Receiver (RECEIVER_STATUS with a transportId), LOAD (fetches the
media URL with Range requests like a real device), PLAY/PAUSE/SEEK/STOP,
GET_STATUS and SET_VOLUME.
"""
import json, os, socket, ssl, struct, subprocess, threading, time, urllib.request
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler

ROOT = os.path.dirname(os.path.abspath(__file__))
CERT = os.path.join(ROOT, "mockcast.pem")
STATE = {"actions": [], "fetches": [], "media": None, "player": "IDLE", "offset": 0.0, "started_at": None, "volume": 0.5}
LOCK = threading.Lock()


def varint(n):
    out = b""
    while n >= 0x80:
        out += bytes([(n & 0x7F) | 0x80]); n >>= 7
    return out + bytes([n])


def encode(src, dst, ns, payload):
    def s(field, v):
        b = v.encode()
        return varint(field << 3 | 2) + varint(len(b)) + b
    body = varint(1 << 3) + varint(0) + s(2, src) + s(3, dst) + s(4, ns) + varint(5 << 3) + varint(0) + s(6, json.dumps(payload))
    return struct.pack(">I", len(body)) + body


def decode(body):
    pos, out = 0, {}
    while pos < len(body):
        key, shift = 0, 0
        while True:
            b = body[pos]; pos += 1
            key |= (b & 0x7F) << shift; shift += 7
            if not b & 0x80: break
        field, wire = key >> 3, key & 7
        if wire == 0:
            while body[pos] & 0x80: pos += 1
            pos += 1
        else:
            n, shift = 0, 0
            while True:
                b = body[pos]; pos += 1
                n |= (b & 0x7F) << shift; shift += 7
                if not b & 0x80: break
            out[field] = body[pos:pos + n].decode(); pos += n
    return out.get(2, ""), out.get(3, ""), out.get(4, ""), out.get(6, "")


def position():
    if STATE["player"] == "PLAYING" and STATE["started_at"]:
        return STATE["offset"] + time.time() - STATE["started_at"]
    return STATE["offset"]


def fetch(url, rng):
    try:
        with urllib.request.urlopen(urllib.request.Request(url, headers={"Range": rng}), timeout=15) as r:
            body = r.read(65536)
            rec = {"range": rng, "status": r.status, "bytes": len(body), "type": r.headers.get("Content-Type")}
    except Exception as e:
        rec = {"range": rng, "error": str(e)}
    with LOCK:
        STATE["fetches"].append(rec)


def media_status(rid=0):
    with LOCK:
        st = {"mediaSessionId": 1, "playerState": STATE["player"], "currentTime": position(), "volume": {"level": STATE["volume"]}}
        if STATE["media"]:
            st["media"] = {"contentId": STATE["media"]["contentId"], "duration": 35.0}
        if STATE["player"] == "IDLE" and STATE["media"]:
            st["idleReason"] = "CANCELLED"
    return {"type": "MEDIA_STATUS", "requestId": rid, "status": [st]}


def client(conn):
    buf = b""
    def send(src, ns, payload):
        conn.sendall(encode(src, "sender-0", ns, payload))
    try:
        while True:
            data = conn.recv(65536)
            if not data: break
            buf += data
            while len(buf) >= 4:
                n = struct.unpack(">I", buf[:4])[0]
                if len(buf) < 4 + n: break
                src, dst, ns, payload = decode(buf[4:4 + n]); buf = buf[4 + n:]
                p = json.loads(payload or "{}")
                t = p.get("type")
                with LOCK:
                    STATE["actions"].append(f"{ns.split('.')[-1]}:{t}")
                rid = p.get("requestId", 0)
                if t == "PING":
                    send(dst, ns, {"type": "PONG"})
                elif t == "LAUNCH":
                    send("receiver-0", "urn:x-cast:com.google.cast.receiver", {"type": "RECEIVER_STATUS", "requestId": rid, "status": {
                        "applications": [{"appId": p.get("appId"), "displayName": "Default Media Receiver", "sessionId": "s1", "transportId": "web-5"}],
                        "volume": {"level": STATE["volume"], "muted": False}}})
                elif t == "LOAD":
                    url = p["media"]["contentId"]
                    with LOCK:
                        STATE.update({"media": p["media"], "player": "PLAYING", "offset": float(p.get("currentTime", 0)), "started_at": time.time()})
                    threading.Thread(target=fetch, args=(url, "bytes=0-65535"), daemon=True).start()
                    threading.Thread(target=fetch, args=(url, "bytes=100000-"), daemon=True).start()
                    send("web-5", ns, media_status(rid))
                elif t in ("PLAY", "PAUSE", "SEEK", "STOP"):
                    with LOCK:
                        if t == "PLAY": STATE.update({"player": "PLAYING", "started_at": time.time()})
                        if t == "PAUSE": STATE.update({"offset": position(), "player": "PAUSED", "started_at": None})
                        if t == "SEEK":
                            STATE["offset"] = float(p.get("currentTime", 0))
                            if STATE["player"] == "PLAYING": STATE["started_at"] = time.time()
                        if t == "STOP": STATE.update({"player": "IDLE", "started_at": None})
                    send("web-5", ns, media_status(rid))
                elif t == "GET_STATUS" and ns.endswith("media"):
                    send("web-5", ns, media_status(rid))
                elif t == "SET_VOLUME":
                    with LOCK:
                        STATE["volume"] = p.get("volume", {}).get("level", STATE["volume"])
    except (ConnectionError, ssl.SSLError, OSError):
        pass
    finally:
        conn.close()


def cast_server():
    if not os.path.exists(CERT):
        subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "3650", "-subj", "/CN=mockcast",
                        "-keyout", CERT, "-out", CERT], check=True, capture_output=True)
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.load_cert_chain(CERT)
    srv = socket.socket(); srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("0.0.0.0", 18009)); srv.listen(5)
    while True:
        raw, _ = srv.accept()
        try:
            conn = ctx.wrap_socket(raw, server_side=True)
        except ssl.SSLError:
            raw.close(); continue
        threading.Thread(target=client, args=(conn,), daemon=True).start()


class Debug(BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def do_GET(self):
        if self.path == "/debug/reset":
            with LOCK:
                STATE.update({"actions": [], "fetches": [], "media": None, "player": "IDLE", "offset": 0.0, "started_at": None})
        with LOCK:
            b = json.dumps(dict(STATE, position=position())).encode()
        self.send_response(200); self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(b))); self.end_headers(); self.wfile.write(b)


if __name__ == "__main__":
    threading.Thread(target=cast_server, daemon=True).start()
    print("mock chromecast up: cast 18009 (TLS), debug 18010", flush=True)
    ThreadingHTTPServer(("0.0.0.0", 18010), Debug).serve_forever()
