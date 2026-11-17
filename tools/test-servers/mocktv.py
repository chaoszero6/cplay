#!/usr/bin/env python3
"""Mock DLNA MediaRenderer ("Mock TV") for ChaosPlay's Play-on-TV tests (scratch).

SSDP on 239.255.255.250:1900 (shared socket), description + AVTransport on :8768.
Like a real TV it fetches the media URL with Range requests; /debug/state shows
what it received.
"""
import json, os, re, socket, struct, threading, time, html, urllib.request
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler

HOST = os.environ.get("HOSTADDR", "127.0.0.1")
PORT = 8768
LOC = f"http://{HOST}:{PORT}/desc.xml"
STATE = {"uri": None, "transport": "NO_MEDIA_PRESENT", "actions": [], "fetches": [], "started_at": None, "offset": 0.0}
LOCK = threading.Lock()

DESC = f"""<?xml version="1.0"?>
<root xmlns="urn:schemas-upnp-org:device-1-0"><specVersion><major>1</major><minor>0</minor></specVersion>
<device><deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType><friendlyName>Mock TV</friendlyName>
<manufacturer>ChaosTest</manufacturer><UDN>uuid:chaos-mock-tv</UDN><serviceList>
<service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType><controlURL>/ctl/AVT</controlURL></service>
<service><serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType><controlURL>/ctl/RC</controlURL></service>
</serviceList></device></root>"""


def ssdp():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEPORT, 1)
    except Exception:
        pass
    s.bind(("", 1900))
    mreq = struct.pack("4s4s", socket.inet_aton("239.255.255.250"), socket.inet_aton(HOST))
    s.setsockopt(socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP, mreq)
    while True:
        data, addr = s.recvfrom(4096)
        text = data.decode(errors="ignore")
        if not text.startswith("M-SEARCH"):
            continue
        st = re.search(r"(?im)^ST:\s*(.+)$", text)
        st = st.group(1).strip() if st else ""
        if st not in ("ssdp:all", "upnp:rootdevice", "urn:schemas-upnp-org:device:MediaRenderer:1", "urn:schemas-upnp-org:service:AVTransport:1"):
            continue
        reply = ("HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nEXT:\r\n"
                 f"LOCATION: {LOC}\r\nSERVER: Linux UPnP/1.0 MockTV/1\r\n"
                 "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n"
                 "USN: uuid:chaos-mock-tv::urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n")
        out = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        out.sendto(reply.encode(), addr)
        out.close()
        with LOCK:
            STATE["actions"].append(f"SSDP from {addr[0]}")


def fetch(uri, rng):
    try:
        req = urllib.request.Request(uri, headers={"Range": rng, "User-Agent": "MockTV"})
        with urllib.request.urlopen(req, timeout=15) as r:
            body = r.read(65536)
            rec = {"range": rng, "status": r.status, "bytes": len(body), "type": r.headers.get("Content-Type"),
                   "content_range": r.headers.get("Content-Range")}
    except Exception as e:
        rec = {"range": rng, "error": str(e)}
    with LOCK:
        STATE["fetches"].append(rec)


def position():
    if STATE["transport"] == "PLAYING" and STATE["started_at"]:
        return STATE["offset"] + (time.time() - STATE["started_at"])
    return STATE["offset"]


def hms(sec):
    sec = int(sec)
    return f"{sec // 3600}:{(sec % 3600) // 60:02d}:{sec % 60:02d}"


class H(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    def log_message(self, *a): pass

    def send(self, code, body, ctype="text/xml"):
        b = body.encode()
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(b)))
        self.end_headers()
        self.wfile.write(b)

    def do_GET(self):
        if self.path == "/desc.xml":
            self.send(200, DESC)
        elif self.path == "/debug/state":
            with LOCK:
                self.send(200, json.dumps(dict(STATE, position=position())), "application/json")
        elif self.path == "/debug/reset":
            with LOCK:
                STATE.update({"uri": None, "transport": "NO_MEDIA_PRESENT", "actions": [], "fetches": [], "started_at": None, "offset": 0.0})
            self.send(200, "{}", "application/json")
        else:
            self.send(404, "")

    def do_POST(self):
        body = self.rfile.read(int(self.headers.get("Content-Length") or 0)).decode()
        action = (self.headers.get("SOAPAction") or "").strip('"').split("#")[-1]
        with LOCK:
            STATE["actions"].append(action)
        args = ""
        if action == "SetAVTransportURI":
            uri = html.unescape(re.search(r"<CurrentURI>(.*?)</CurrentURI>", body, re.S).group(1))
            with LOCK:
                STATE.update({"uri": uri, "transport": "STOPPED", "offset": 0.0, "started_at": None})
            threading.Thread(target=fetch, args=(uri, "bytes=0-65535"), daemon=True).start()
            threading.Thread(target=fetch, args=(uri, "bytes=100000-"), daemon=True).start()
        elif action == "Play":
            with LOCK:
                STATE.update({"transport": "PLAYING", "started_at": time.time()})
        elif action == "Pause":
            with LOCK:
                STATE.update({"offset": position(), "transport": "PAUSED_PLAYBACK", "started_at": None})
        elif action == "Stop":
            with LOCK:
                STATE.update({"transport": "STOPPED", "offset": 0.0, "started_at": None})
        elif action == "Seek":
            t = re.search(r"<Target>(.*?)</Target>", body).group(1)
            h, m, s = [int(float(x)) for x in t.split(":")]
            with LOCK:
                STATE["offset"] = h * 3600 + m * 60 + s
                if STATE["transport"] == "PLAYING":
                    STATE["started_at"] = time.time()
        elif action == "GetPositionInfo":
            args = f"<Track>1</Track><TrackDuration>0:00:35</TrackDuration><RelTime>{hms(position())}</RelTime>"
        elif action == "GetTransportInfo":
            args = f"<CurrentTransportState>{STATE['transport']}</CurrentTransportState><CurrentTransportStatus>OK</CurrentTransportStatus>"
        resp = ('<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>'
                f'<u:{action}Response xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">{args}</u:{action}Response></s:Body></s:Envelope>')
        self.send(200, resp)


if __name__ == "__main__":
    threading.Thread(target=ssdp, daemon=True).start()
    print(f"mock TV up at {LOC}", flush=True)
    ThreadingHTTPServer(("0.0.0.0", PORT), H).serve_forever()
