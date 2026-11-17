#!/usr/bin/env python3
"""Throwaway share servers for ChaosPlay device tests (test-only, scratch).

  :4445   SMB2  (impacket)  share VIDEOS -> share/, user test / secret
  :2121   FTP   (pyftpdlib) root share/,  user test / secret, PASV 30000-30009 (advertised as 10.0.2.2)
  :2222   SFTP  (asyncssh)  root share/,  user test / secret
  :8767   DLNA  MediaServer mock: /desc.xml + ContentDirectory Browse, media served by :8765
"""
import asyncio, os, sys, threading, html
HOSTADDR = os.environ.get("HOSTADDR", "10.0.2.2")
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler

ROOT = os.path.dirname(os.path.abspath(__file__))
SHARE = os.path.join(ROOT, "share")


def smb():
    from impacket import smbserver, ntlm
    server = smbserver.SimpleSMBServer(listenAddress="0.0.0.0", listenPort=4445)
    server.addShare("VIDEOS", SHARE, "Test videos")
    server.setSMB2Support(True)
    server.addCredential("test", 1000, ntlm.compute_lmhash("secret"), ntlm.compute_nthash("secret"))
    server.setSMBChallenge("")
    server.setLogFile(os.path.join(ROOT, "smb.log"))
    server.start()


def ftp():
    from pyftpdlib.authorizers import DummyAuthorizer
    from pyftpdlib.handlers import FTPHandler
    from pyftpdlib.servers import FTPServer
    auth = DummyAuthorizer()
    auth.add_user("test", "secret", SHARE, perm="elr")
    handler = FTPHandler
    handler.authorizer = auth
    handler.passive_ports = range(30000, 30010)
    handler.masquerade_address = HOSTADDR
    handler.permit_foreign_addresses = True
    FTPServer(("0.0.0.0", 2121), handler).serve_forever()


def sftp():
    import asyncssh

    class Srv(asyncssh.SSHServer):
        def begin_auth(self, username): return True
        def password_auth_supported(self): return True
        def validate_password(self, username, password): return username == "test" and password == "secret"

    async def run():
        key_path = os.path.join(ROOT, "ssh_host_key")
        if not os.path.exists(key_path):
            asyncssh.generate_private_key("ssh-ed25519").write_private_key(key_path)
        await asyncssh.create_server(Srv, "0.0.0.0", 2222, server_host_keys=[key_path],
                                     sftp_factory=lambda chan: asyncssh.SFTPServer(chan, chroot=SHARE.encode()))
        await asyncio.Future()
    asyncio.run(run())


DESC = """<?xml version="1.0"?>
<root xmlns="urn:schemas-upnp-org:device-1-0"><specVersion><major>1</major><minor>0</minor></specVersion>
<device><deviceType>urn:schemas-upnp-org:device:MediaServer:1</deviceType><friendlyName>Mock DLNA</friendlyName>
<UDN>uuid:chaos-mock-dlna</UDN><serviceList>
<service><serviceType>urn:schemas-upnp-org:service:ContentDirectory:1</serviceType><controlURL>/ctl/ContentDir</controlURL></service>
</serviceList></device></root>"""

TREE = {
    "0": '<container id="1" parentID="0" childCount="2"><dc:title>Videos</dc:title><upnp:class>object.container.storageFolder</upnp:class></container>'
         '<container id="2" parentID="0" childCount="0"><dc:title>Pictures</dc:title><upnp:class>object.container.storageFolder</upnp:class></container>',
    "1": '<item id="1$1" parentID="1"><dc:title>Bravo over DLNA</dc:title><upnp:class>object.item.videoItem</upnp:class>'
         '<res duration="0:00:35.000" size="351932" protocolInfo="http-get:*:video/mp4:*">http://'+HOSTADDR+':8765/Bravo%20Bars.mp4</res>'
         '<res protocolInfo="http-get:*:text/srt:*">http://'+HOSTADDR+':8767/sub.srt</res></item>'
         '<item id="1$2" parentID="1"><dc:title>Photo</dc:title><upnp:class>object.item.imageItem</upnp:class>'
         '<res protocolInfo="http-get:*:image/jpeg:*">http://'+HOSTADDR+':8767/x.jpg</res></item>',
}


class Dlna(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    def log_message(self, *a): pass

    def send(self, code, body, ctype):
        b = body.encode()
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(b)))
        self.end_headers()
        self.wfile.write(b)

    def do_GET(self):
        if self.path == "/desc.xml":
            self.send(200, DESC, "text/xml")
        elif self.path == "/sub.srt":
            self.send(200, "1\n00:00:00,500 --> 00:00:30,000\nDLNA subtitle line\n", "text/srt")
        else:
            self.send(404, "", "text/plain")

    def do_POST(self):
        body = self.rfile.read(int(self.headers.get("Content-Length") or 0)).decode()
        oid = body.split("<ObjectID>")[1].split("</ObjectID>")[0] if "<ObjectID>" in body else "0"
        oid = html.unescape(oid)
        if oid not in TREE:
            fault = ('<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><s:Fault>'
                     '<faultcode>s:Client</faultcode><detail><UPnPError xmlns="urn:schemas-upnp-org:control-1-0">'
                     '<errorCode>701</errorCode><errorDescription>No such object</errorDescription></UPnPError></detail></s:Fault></s:Body></s:Envelope>')
            self.send(500, fault, "text/xml"); return
        didl = ('<DIDL-Lite xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/" '
                'xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/">' + TREE[oid] + '</DIDL-Lite>')
        n = TREE[oid].count('id="')
        resp = ('<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>'
                '<u:BrowseResponse xmlns:u="urn:schemas-upnp-org:service:ContentDirectory:1"><Result>' + html.escape(didl) +
                f'</Result><NumberReturned>{n}</NumberReturned><TotalMatches>{n}</TotalMatches><UpdateID>1</UpdateID></u:BrowseResponse></s:Body></s:Envelope>')
        self.send(200, resp, 'text/xml; charset="utf-8"')


def dlna():
    ThreadingHTTPServer(("0.0.0.0", 8767), Dlna).serve_forever()


if __name__ == "__main__":
    for target in (smb, ftp, sftp, dlna):
        threading.Thread(target=target, daemon=True).start()
    print("share servers up: smb 4445, ftp 2121, sftp 2222, dlna 8767", flush=True)
    threading.Event().wait()
