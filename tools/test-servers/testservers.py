#!/usr/bin/env python3
"""Local test servers for ChaosPlay network features (scratch, test-only).

  :8765  static HTTP + HLS        (www/)
  :8766  WebDAV, Nextcloud layout (dav/ served at /remote.php/dav/files/test/), Basic auth test:secret
  :18096 mock Jellyfin            (user test / secret, serves www/Bravo Bars.mp4 as items)

Range requests are supported everywhere so seeking works like a real server.
"""
import base64, json, os, re, sys, threading, urllib.parse, email.utils
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler

ROOT = os.path.dirname(os.path.abspath(__file__))
WWW = os.path.join(ROOT, "www")
DAV = os.path.join(ROOT, "dav")
DAV_PREFIX = "/remote.php/dav/files/test/"
LOG = []


def send_file(h, path, head=False):
    if not os.path.isfile(path):
        h.send_error(404); return
    size = os.path.getsize(path)
    ctype = {"mp4": "video/mp4", "m3u8": "application/vnd.apple.mpegurl", "ts": "video/mp2t",
             "srt": "application/x-subrip"}.get(path.rsplit(".", 1)[-1], "application/octet-stream")
    rng = h.headers.get("Range")
    start, end = 0, size - 1
    if rng:
        m = re.match(r"bytes=(\d*)-(\d*)", rng)
        if m:
            if m.group(1): start = int(m.group(1))
            if m.group(2): end = min(int(m.group(2)), size - 1)
        h.send_response(206)
        h.send_header("Content-Range", f"bytes {start}-{end}/{size}")
    else:
        h.send_response(200)
    h.send_header("Content-Type", ctype)
    h.send_header("Accept-Ranges", "bytes")
    h.send_header("Content-Length", str(end - start + 1))
    h.end_headers()
    if head: return
    with open(path, "rb") as f:
        f.seek(start)
        remaining = end - start + 1
        try:
            while remaining > 0:
                chunk = f.read(min(65536, remaining))
                if not chunk: break
                h.wfile.write(chunk); remaining -= len(chunk)
        except (BrokenPipeError, ConnectionResetError):
            pass


class Quiet(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    def log_message(self, fmt, *args):
        LOG.append(f"{self.server.server_port} {self.command} {self.path}")


class Static(Quiet):
    def do_GET(self, head=False):
        p = urllib.parse.unquote(urllib.parse.urlparse(self.path).path).lstrip("/")
        send_file(self, os.path.join(WWW, p), head)
    def do_HEAD(self):
        self.do_GET(head=True)


class Dav(Quiet):
    def authed(self):
        want = "Basic " + base64.b64encode(b"test:secret").decode()
        if self.headers.get("Authorization") != want:
            self.send_response(401)
            self.send_header("WWW-Authenticate", 'Basic realm="Nextcloud"')
            self.send_header("Content-Length", "0"); self.end_headers()
            return False
        return True

    def local(self):
        path = urllib.parse.unquote(urllib.parse.urlparse(self.path).path)
        if not path.startswith(DAV_PREFIX.rstrip("/")):
            return None
        rel = path[len(DAV_PREFIX):] if len(path) >= len(DAV_PREFIX) else ""
        return os.path.join(DAV, rel)

    def do_PROPFIND(self):
        # Drain the body first, even when refusing: keep-alive connections are reused
        n = int(self.headers.get("Content-Length") or 0)
        if n: self.rfile.read(n)
        if not self.authed(): return
        target = self.local()
        if target is None or not os.path.exists(target):
            self.send_error(404); return
        base = urllib.parse.urlparse(self.path).path
        if os.path.isdir(target) and not base.endswith("/"): base += "/"
        entries = [(base, target)]
        if os.path.isdir(target) and self.headers.get("Depth", "1") != "0":
            for name in sorted(os.listdir(target)):
                full = os.path.join(target, name)
                href = base + urllib.parse.quote(name) + ("/" if os.path.isdir(full) else "")
                entries.append((href, full))
        out = ['<?xml version="1.0"?><d:multistatus xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns">']
        for href, full in entries:
            if os.path.isdir(full):
                prop = "<d:resourcetype><d:collection/></d:resourcetype><oc:size>0</oc:size>"
            else:
                st = os.stat(full)
                prop = (f"<d:resourcetype/><d:getcontentlength>{st.st_size}</d:getcontentlength>"
                        f"<d:getlastmodified>{email.utils.formatdate(st.st_mtime, usegmt=True)}</d:getlastmodified>")
            out.append(f"<d:response><d:href>{href}</d:href><d:propstat><d:prop>{prop}</d:prop>"
                       "<d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>")
        out.append("</d:multistatus>")
        body = "".join(out).encode()
        self.send_response(207)
        self.send_header("Content-Type", "application/xml; charset=utf-8")
        self.send_header("Content-Length", str(len(body))); self.end_headers()
        self.wfile.write(body)

    def do_GET(self, head=False):
        if not self.authed(): return
        target = self.local()
        if target is None: self.send_error(404); return
        send_file(self, target, head)

    def do_HEAD(self):
        self.do_GET(head=True)


TOKEN = "tok-123"
ITEMS = {
    "lib1": {"Name": "Movies", "Id": "lib1", "Type": "CollectionFolder", "IsFolder": True, "CollectionType": "movies"},
    "m1": {"Name": "Bravo Movie", "Id": "m1", "Type": "Movie", "IsFolder": False, "ProductionYear": 2024,
           "RunTimeTicks": 350000000, "Overview": "Colour bars, the movie.",
           "UserData": {"PlaybackPositionTicks": 120000000, "Played": False},
           "MediaSources": [{"Id": "m1", "Container": "mp4", "MediaStreams": [
               {"Type": "Subtitle", "Index": 2, "IsExternal": True, "IsTextSubtitleStream": True, "DisplayTitle": "English - SRT"}]}]},
    "m2": {"Name": "Second Movie", "Id": "m2", "Type": "Movie", "IsFolder": False, "RunTimeTicks": 350000000,
           "UserData": {"PlaybackPositionTicks": 0, "Played": False}, "MediaSources": [{"Id": "m2", "Container": "mp4"}]},
}
REPORTS = []


class Jelly(Quiet):
    def body(self):
        n = int(self.headers.get("Content-Length") or 0)
        return self.rfile.read(n).decode() if n else ""

    def json(self, obj, code=200):
        b = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(b))); self.end_headers()
        self.wfile.write(b)

    def token_ok(self):
        auth = (self.headers.get("Authorization") or "") + (self.headers.get("X-Emby-Authorization") or "")
        q = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
        return f'Token="{TOKEN}"' in auth or q.get("api_key") == [TOKEN]

    def do_POST(self):
        path = urllib.parse.urlparse(self.path).path
        body = self.body()
        if path == "/Users/AuthenticateByName":
            data = json.loads(body or "{}")
            if data.get("Username") == "test" and data.get("Pw") == "secret":
                self.json({"User": {"Name": "test", "Id": "u1", "ServerId": "srv"}, "AccessToken": TOKEN, "ServerId": "srv"})
            else:
                self.json({}, 401)
            return
        if path.startswith("/Sessions/"):
            if not self.token_ok(): self.json({}, 401); return
            REPORTS.append((path, json.loads(body or "{}")))
            self.send_response(204); self.send_header("Content-Length", "0"); self.end_headers(); return
        self.send_error(404)

    def do_GET(self, head=False):
        u = urllib.parse.urlparse(self.path)
        path = u.path
        if path == "/System/Info/Public":
            self.json({"ServerName": "Mock Jellyfin", "Version": "10.9.11", "Id": "srv", "ProductName": "Jellyfin Server"}); return
        if path == "/debug/reports":
            self.json([[p, b] for p, b in REPORTS]); return
        if not self.token_ok():
            self.json({}, 401); return
        if path == "/Users/u1/Views":
            self.json({"Items": [ITEMS["lib1"]]}); return
        if path == "/Users/u1/Items/Resume":
            self.json({"Items": [ITEMS["m1"]]}); return
        if path == "/Shows/NextUp":
            self.json({"Items": []}); return
        if path == "/Users/u1/Items/Latest":
            self.json([ITEMS["m2"], ITEMS["m1"]]); return
        if path == "/Users/u1/Items":
            q = urllib.parse.parse_qs(u.query)
            if q.get("SearchTerm"):
                term = q["SearchTerm"][0].lower()
                self.json({"Items": [i for i in ITEMS.values() if term in i["Name"].lower() and not i["IsFolder"]]}); return
            self.json({"Items": [ITEMS["m1"], ITEMS["m2"]]}); return
        m = re.match(r"/Users/u1/Items/(\w+)$", path)
        if m and m.group(1) in ITEMS:
            self.json(ITEMS[m.group(1)]); return
        if re.match(r"/Videos/\w+/stream$", path):
            send_file(self, os.path.join(WWW, "Bravo Bars.mp4"), head); return
        if re.match(r"/Videos/\w+/\w+/Subtitles/\d+/Stream\.srt$", path):
            b = b"1\n00:00:00,500 --> 00:00:30,000\nJellyfin server subtitle\n"
            self.send_response(200); self.send_header("Content-Type", "application/x-subrip")
            self.send_header("Content-Length", str(len(b))); self.end_headers(); self.wfile.write(b); return
        self.send_error(404)

    def do_HEAD(self):
        self.do_GET(head=True)


def main():
    servers = [ThreadingHTTPServer(("0.0.0.0", 8765), Static),
               ThreadingHTTPServer(("0.0.0.0", 8766), Dav),
               ThreadingHTTPServer(("0.0.0.0", 18096), Jelly)]
    for s in servers:
        threading.Thread(target=s.serve_forever, daemon=True).start()
    print("serving 8765 static, 8766 webdav, 18096 jellyfin", flush=True)
    import time
    while True:
        time.sleep(5)
        if LOG:
            with open(os.path.join(ROOT, "access.log"), "a") as f:
                while LOG: f.write(LOG.pop(0) + "\n")


if __name__ == "__main__":
    main()
