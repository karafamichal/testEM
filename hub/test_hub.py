"""Run: python3 test_hub.py (uses a throwaway database)."""
import datetime
import http.client
import json
import math
import os
import sqlite3
import subprocess
import sys
import tempfile
import threading
import urllib.error
import urllib.parse
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
os.environ["HUB_DB"] = os.path.join(tempfile.mkdtemp(), "test.db")
os.environ["HUB_BOOTSTRAP_PASSWORD"] = "bootstrap-pass-1"
os.environ["HUB_SMTP_HOST"] = "127.0.0.1"
os.environ["HUB_SMTP_PORT"] = "1"  # nothing listens: sending fails fast
os.environ["HUB_WEB_URL"] = "https://web.example/"
sys.path.insert(0, HERE)
import hub  # noqa: E402

hub.init_db()
NOW = 1_000_000


def r(delay, age, who, stop=3):
    return {"delay_s": delay, "created_at": NOW - age, "reporter": who, "stop_index": stop, "stop_name": "X"}


# ---- community delay pooling
assert hub.aggregate_delay([], NOW) is None
assert hub.aggregate_delay([r(300, hub.COMMUNITY_WINDOW_S + 1, "a")], NOW) is None
agg = hub.aggregate_delay([r(60, 600, "a"), r(240, 10, "a")], NOW)
assert agg["delaySeconds"] == 240 and agg["reporters"] == 1, agg          # latest report per rider wins
agg = hub.aggregate_delay([r(300, 30, "a"), r(330, 60, "b"), r(2700, 5, "troll")], NOW)
assert 300 <= agg["delaySeconds"] <= 330 and agg["reporters"] == 2, agg   # outlier dropped
assert hub.aggregate_delay([r(0, 20 * 60, "a"), r(600, 0, "b")], NOW)["delaySeconds"] > 400  # fresh weighs more

# ---- history: same trip, other days of the same kind
assert hub.trip_run("sadzv|9|31|2026-09-25|2") == ("sadzv|9|31|2", "2026-09-25")
assert hub.trip_run("101|Zvolen, AS|1790311200000") == ("101|Zvolen, AS|06:40", "2026-09-25")
assert hub.trip_run("junk") is None
assert [hub.day_type(d) for d in ("2026-09-25", "2026-09-26", "2026-09-27")] == ["workday", "saturday", "sunday"]
hc = sqlite3.connect(":memory:")
hc.executescript(hub.SCHEMA.replace("PRAGMA journal_mode=WAL;", ""))
hc.execute("ALTER TABLE community_reports ADD COLUMN kind TEXT NOT NULL DEFAULT 'position'")
def hist(key, delay):
    hc.execute("INSERT INTO community_reports(created_at, trip_key, line, stop_index, stop_name, scheduled_ms, delay_s, reporter) "
               "VALUES (?,?,?,?,?,?,?,?)", (hub.now(), key, "2", 0, "x", 0, delay, "r"))
assert hub.trip_history(hc, "sadzv|9|31|2026-09-25|2") is None
for day, delay in (("2026-09-22", 200), ("2026-09-23", 60), ("2026-09-23", 120), ("2026-09-24", 300),
                   ("2026-09-26", 900), ("2026-09-25", 999)):  # a Saturday and today don't count
    hist(f"sadzv|9|31|{day}|2", delay)
hist("sadzv|9|32|2026-09-24|2", 999)  # another trip
h = hub.trip_history(hc, "sadzv|9|31|2026-09-25|2")
assert h == {"delaySeconds": 200, "runs": 3, "lateRuns": 2, "dayType": "workday"}, h
# a run with exact departure moments uses those, not the rough between-stop estimates
hc.execute("INSERT INTO community_reports(created_at, trip_key, line, stop_index, stop_name, scheduled_ms, delay_s, reporter, kind) "
           "VALUES (?,?,?,?,?,?,?,?,?)", (hub.now(), "sadzv|9|31|2026-09-21|2", "2", 0, "x", 0, 600, "r", "gps"))
hc.execute("INSERT INTO community_reports(created_at, trip_key, line, stop_index, stop_name, scheduled_ms, delay_s, reporter, kind) "
           "VALUES (?,?,?,?,?,?,?,?,?)", (hub.now(), "sadzv|9|31|2026-09-21|2", "2", 1, "x", 0, 30, "r", "departed"))
h = hub.trip_history(hc, "sadzv|9|31|2026-09-25|2")
assert h["runs"] == 4 and h["delaySeconds"] == 145, h  # 21st counts as 30 s, not 600

# ---- templates escape user text, keep prepared HTML, fill every placeholder
bug = {"id": 7, "title": "<script>x</script>", "description": "line1\n\n<b>line2</b>", "name": "Ann", "email": "a@b.sk",
       "status": "in_progress", "category": "crash", "platform": "android", "app_version": "2.0", "device": "Pixel",
       "logs": "a\nb", "created_at": NOW}
out = hub.render_template("admin_new_bug", hub.bug_values(bug))
assert "EM-0007" in out and "&lt;script&gt;" in out and "<script>" not in out
assert "&lt;b&gt;line2&lt;/b&gt;" in out and "<p " in out
assert "EM-0007" in hub.html_to_text(out)
admin = {"username": "adm", "email": "adm@example.com"}
for name in hub.TEMPLATES:
    assert "{{" not in hub.render_template(name, hub.sample_values(name, admin)), name

# ---- saved settings and templates win over env values and files
assert hub.setting("smtp_host") == "127.0.0.1"
with hub.db() as conn:
    hub.save_settings(conn, {"smtp_host": "mail.example.com", "oidc_label": "Karafa SSO"})
    conn.execute("INSERT INTO email_templates(name, html, updated_at) VALUES ('test', 'Hi {{username}}', 0)")
assert hub.setting("smtp_host") == "mail.example.com" and hub.setting("oidc_label") == "Karafa SSO"
assert hub.render_template("test", {"username": "<b>"}) == "Hi &lt;b&gt;"
with hub.db() as conn:
    hub.save_settings(conn, {"smtp_host": "127.0.0.1"})
    conn.execute("DELETE FROM email_templates")

# ---- passwords
stored = hub.hash_password("correct horse")
assert hub.check_password("correct horse", stored) and not hub.check_password("nope", stored)
assert not hub.check_password("anything", "!"), "invited accounts have no usable password"

# ---- invite flow over HTTP: invite -> accept once -> sign in
server = hub.ThreadingHTTPServer(("127.0.0.1", 0), hub.Handler)
threading.Thread(target=server.serve_forever, daemon=True).start()
base = f"http://127.0.0.1:{server.server_port}"


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args):
        return None


opener = urllib.request.build_opener(NoRedirect)


def call(path, data=None, cookie="", form=False):
    body = urllib.parse.urlencode(data).encode() if form else (json.dumps(data).encode() if data is not None else None)
    req = urllib.request.Request(base + path, data=body, headers={
        "Content-Type": "application/x-www-form-urlencoded" if form else "application/json", "Cookie": cookie})
    try:
        res = opener.open(req)
    except urllib.error.HTTPError as err:
        res = err
    return res.status, res.headers, res.read()


status, headers, _ = call("/auth/login", {"username": "karafamichal", "password": "bootstrap-pass-1"}, form=True)
cookie = headers["Set-Cookie"].split(";")[0]
status, _, body = call("/admin/api/admins", {"username": "newbie", "email": "newbie@example.com"}, cookie)
invite = json.loads(body)
assert status == 201 and invite["mailError"], invite       # SMTP is unreachable here...
token = urllib.parse.parse_qs(urllib.parse.urlsplit(invite["link"]).query)["token"][0]  # ...so the admin gets the link
assert json.loads(call("/auth/invite/check?token=" + token)[2]) == {"ok": True, "username": "newbie"}
status, headers, _ = call("/auth/login", {"username": "newbie", "password": ""}, form=True)
assert "error=invalid" in headers["Location"], "no sign-in before the invite is accepted"
status, headers, _ = call("/auth/invite", {"token": token, "password": "short", "confirm": "short"}, form=True)
assert "error=short" in headers["Location"]
status, headers, _ = call("/auth/invite", {"token": token, "password": "newbie-pass-1", "confirm": "newbie-pass-1"}, form=True)
assert headers["Location"] == "/dashboard#settings" and "hub_session=" in headers["Set-Cookie"]
status, headers, _ = call("/auth/invite", {"token": token, "password": "again-pass-1", "confirm": "again-pass-1"}, form=True)
assert "error=expired" in headers["Location"], "the link works only once"
status, headers, _ = call("/auth/login", {"username": "newbie", "password": "newbie-pass-1"}, form=True)
assert headers["Location"] == "/dashboard"
admins = json.loads(call("/admin/api/admins", cookie=cookie)[2])
assert [a["invited"] for a in admins] == [0, 0]
# settings: secrets are never sent back, and a blank secret keeps the saved one
call("/admin/api/settings", {"smtp_pass": "s3cret"}, cookie)
settings = json.loads(call("/admin/api/settings", cookie=cookie)[2])
assert settings["smtp_pass"] == "" and settings["smtp_pass_set"] is True
call("/admin/api/settings", {"smtp_pass": "", "smtp_host": "127.0.0.1"}, cookie)
assert hub.setting("smtp_pass") == "s3cret"
# Authentik needs issuer and client ID together
status, _, body = call("/admin/api/settings", {"oidc_client_id": "abc", "oidc_issuer": ""}, cookie)
assert status == 400 and b"issuer" in body
status, _, _ = call("/admin/api/settings", {"oidc_client_id": "abc", "oidc_issuer": "https://auth.example.com/application/o/emhub/"}, cookie)
assert status == 200 and hub.oidc_enabled()
call("/admin/api/settings", {"oidc_client_id": "", "oidc_issuer": ""}, cookie)
assert not hub.oidc_enabled()
# ---- Authentik link + sign-in against a fake provider that, like Cloudflare, blocks Python's User-Agent
import base64, hashlib
from http.server import BaseHTTPRequestHandler as BH
fake = {}


class FakeAuthentik(BH):
    def log_message(self, *a):
        pass

    def reply(self, code, obj):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.headers.get("User-Agent", "").startswith("Python-urllib"):
            return self.reply(403, {"error": "blocked"})
        if self.path.endswith("/.well-known/openid-configuration"):
            root = f"http://127.0.0.1:{self.server.server_port}"
            return self.reply(200, {"issuer": root + "/application/o/test-em/", "authorization_endpoint": root + "/authorize/",
                                    "token_endpoint": root + "/token/", "userinfo_endpoint": root + "/userinfo/"})
        if self.path == "/userinfo/" and self.headers.get("Authorization") == "Bearer at-1":
            return self.reply(200, {"sub": "authentik-user-1", "preferred_username": "michal"})
        self.reply(404, {})

    def do_POST(self):
        form = dict(urllib.parse.parse_qsl(self.rfile.read(int(self.headers["Content-Length"])).decode()))
        pkce = base64.urlsafe_b64encode(hashlib.sha256(form["code_verifier"].encode()).digest()).rstrip(b"=").decode()
        if form.get("code") == "code-1" and pkce == fake["challenge"] and form.get("client_secret") == "shh":
            return self.reply(200, {"access_token": "at-1", "token_type": "Bearer"})
        self.reply(400, {"error": "invalid_grant"})


provider = hub.ThreadingHTTPServer(("127.0.0.1", 0), FakeAuthentik)
threading.Thread(target=provider.serve_forever, daemon=True).start()
issuer = f"http://127.0.0.1:{provider.server_port}/application/o/test-em"
with hub.db() as conn:  # (the settings API insists on https; set it directly for the test)
    hub.save_settings(conn, {"oidc_issuer": hub.normalize_issuer(issuer + hub.WELL_KNOWN), "oidc_client_id": "cid",
                             "oidc_client_secret": "shh"})
assert hub.setting("oidc_issuer") == issuer + "/", "the OpenID Configuration URL is accepted too"
status, _, body = call("/admin/api/settings/test-oidc", {}, cookie)
assert status == 200 and json.loads(body)["issuer"].endswith("/test-em/"), body


def start(mode, who):
    status, headers, _ = call(f"/auth/oidc/start?mode={mode}", cookie=who)
    q = dict(urllib.parse.parse_qsl(urllib.parse.urlsplit(headers["Location"]).query))
    fake["challenge"] = q["code_challenge"]
    return q["state"]


state = start("link", cookie)
status, headers, _ = call(f"/auth/oidc/callback?code=code-1&state={state}", cookie=cookie)
assert headers["Location"] == "/dashboard#settings/linked", headers["Location"]
state = start("login", "")
status, headers, _ = call(f"/auth/oidc/callback?code=code-1&state={state}")
assert headers["Location"] == "/dashboard" and "hub_session=" in headers["Set-Cookie"], "sign in with Authentik"
state = start("link", cookie)
status, headers, _ = call(f"/auth/oidc/callback?error=access_denied&state={state}", cookie=cookie)
assert "oidc-error:" in headers["Location"] and "access_denied" in urllib.parse.unquote(headers["Location"])
with hub.db() as conn:  # provider down -> back to settings with a reason, not a 500
    hub.save_settings(conn, {"oidc_issuer": "http://127.0.0.1:1/x/"})
status, headers, _ = call("/auth/oidc/start?mode=link", cookie=cookie)
assert status == 303 and "oidc-error:" in headers["Location"], (status, headers["Location"])
provider.shutdown()
with hub.db() as conn:
    hub.save_settings(conn, {"oidc_issuer": "", "oidc_client_id": "", "oidc_client_secret": ""})
    conn.execute("UPDATE users SET oidc_sub=NULL")

# community reports: far too early (bus not there yet) is refused, a normal delay is accepted
key = {"X-App-Key": "dev-key"}
def report(minutes_from_now):
    body = json.dumps({"tripKey": "5|Zvolen, AS|1", "line": "5", "stopIndex": 0, "stopName": "Zvolen, AS",
                       "scheduledMs": (hub.now() + minutes_from_now * 60) * 1000, "reporter": f"r{minutes_from_now}"}).encode()
    req = urllib.request.Request(base + "/api/v1/community/reports", data=body, headers={"Content-Type": "application/json", **key})
    try:
        return opener.open(req).status
    except urllib.error.HTTPError as err:
        return err.status
assert report(173) == 400, "bus 3 hours away can't be 'here'"
assert report(10) == 201 and report(-12) == 201, "10 min early / 12 min late are fine"
body = json.dumps({"tripKey": "5|Zvolen, AS|2", "line": "5", "stopIndex": 0, "stopName": "Zvolen, AS", "kind": "arrival",
                   "scheduledMs": (hub.now() + 6 * 60) * 1000, "reporter": "arr"}).encode()
res = opener.open(urllib.request.Request(base + "/api/v1/community/reports", data=body, headers={"Content-Type": "application/json", **key}))
assert json.loads(res.read())["yourDelaySeconds"] == 0, "bus at the stop 6 min before departure is on time, not early"

# a rider's phone on the bus beats taps: taps say 4 min late, the phone says 1 min late
def send(kind, who, minutes_late, trip="7|gps|1"):
    body = json.dumps({"tripKey": trip, "line": "7", "stopIndex": 2, "stopName": "X", "kind": kind,
                       "scheduledMs": (hub.now() - minutes_late * 60) * 1000, "reporter": who}).encode()
    return json.loads(opener.open(urllib.request.Request(base + "/api/v1/community/reports", data=body,
                                                         headers={"Content-Type": "application/json", **key})).read())
send("position", "tap1", 4)
assert send("position", "tap2", 4)["delay"]["source"] == "riders"
pooled = send("gps", "phone1", 1)["delay"]
assert pooled["source"] == "gps" and abs(pooled["delaySeconds"] - 60) <= 2 and pooled["reporters"] == 1, pooled

# dashboard: trips of any day, narrowed by line; a bad day is refused
def admin_get(path):
    return json.loads(opener.open(urllib.request.Request(base + "/admin/api/" + path, headers={"Cookie": cookie})).read())
today = datetime.datetime.now(hub.LOCAL_ZONE).date().isoformat()
trips = admin_get(f"community/trips?day={today}&line=5")
assert trips and all(t["line"] == "5" for t in trips) and "last_delay" in trips[0] and "max_delay" in trips[0], trips
assert admin_get("community/trips?day=2020-01-01") == []
try:
    admin_get("community/trips?day=nope")
    raise AssertionError("bad day accepted")
except urllib.error.HTTPError as err:
    assert err.status == 400

# live dashboard: a new report reaches an open event stream
stream = http.client.HTTPConnection("127.0.0.1", server.server_port, timeout=10)
stream.request("GET", "/admin/api/events", headers={"Cookie": cookie})
events = stream.getresponse()
assert events.status == 200 and events.getheader("Content-Type") == "text/event-stream"
assert events.fp.readline() == b"retry: 5000\n"
events.fp.readline()
send("gps", "phone2", 1)
assert events.fp.readline() == b"event: community\n"
stream.close()

# keep-alive: a POST whose handler ignores the body must not corrupt the next request
import http.client
kc = http.client.HTTPConnection("127.0.0.1", server.server_port)
for _ in range(2):
    kc.request("POST", "/admin/api/settings/test-oidc", body="{}", headers={"Content-Type": "application/json", "Cookie": cookie})
    kc.getresponse().read()
    kc.request("GET", "/admin/api/me", headers={"Cookie": cookie})
    res = kc.getresponse()
    assert res.status == 200 and json.loads(res.read())["username"] == "karafamichal", "next request on the same connection works"
kc.close()
# ---- suggestions are listed apart from bugs; a title cannot break an email header
def app_post(data, key="dev-key"):
    req = urllib.request.Request(base + "/api/v1/bugs", data=json.dumps(data).encode(),
                                 headers={"Content-Type": "application/json", "X-App-Key": key})
    try:
        res = opener.open(req)
    except urllib.error.HTTPError as err:
        res = err
    return res.status, json.loads(res.read() or b"{}")


assert app_post({"consent": True, "description": "x"}, key="wrong")[0] == 401
status, idea = app_post({"consent": True, "kind": "feature", "title": "Dark map\r\nBcc: evil@example.com", "logs": "secret log"})
assert status == 201, idea
status, bug = app_post({"consent": True, "kind": "bug", "description": "Crash on start"})
# photos with a report (EM-0016): JPEG only, cleaned (no EXIF, nothing after the end), admins only
def jpeg(w=10, h=10, tail=b""):
    seg = lambda m, body: b"\xff" + bytes([m]) + (len(body) + 2).to_bytes(2, "big") + body
    return (b"\xff\xd8" + seg(0xE0, b"JFIF\0\1\1\0\0\1\0\1\0\0") + seg(0xE1, b"Exif\0\0GPS 48.57N 19.12E")
            + seg(0xC0, b"\x08" + h.to_bytes(2, "big") + w.to_bytes(2, "big") + b"\x01\x01\x11\x00")
            + seg(0xDA, b"\x01\x01\x00\x00\x3f\x00") + b"\x12\x34\xff\x00" + b"\xff\xd9" + tail)
b64 = lambda blob: base64.b64encode(blob).decode()
status, pic = app_post({"consent": True, "description": "Wrong bus", "images": [b64(jpeg(tail=b"<script>alert(1)</script>"))]})
assert status == 201, (status, pic)
detail = json.loads(call("/admin/api/bugs/%d" % pic["id"], cookie=cookie)[2])
assert len(detail["images"]) == 1 and detail["images"][0]["mime"] == "image/jpeg", detail["images"]
status, headers, body = call("/admin/api/bugs/%d/images/%d" % (pic["id"], detail["images"][0]["id"]), cookie=cookie)
assert status == 200 and headers["Content-Type"] == "image/jpeg" and headers["X-Content-Type-Options"] == "nosniff"
assert "sandbox" in headers["Content-Security-Policy"], headers
assert body.startswith(b"\xff\xd8\xff\xe0") and body.endswith(b"\xff\xd9") and b"GPS" not in body and b"script" not in body, body
assert call("/admin/api/bugs/%d/images/%d" % (pic["id"], detail["images"][0]["id"]))[0] == 401, "admins only"
status, headers, _ = call("/login", cookie=cookie)
assert status in (302, 303) and headers["Location"].endswith("/dashboard"), "signed in: /login goes to the dashboard"
assert call("/login")[0] == 200
for bad in [b"\x89PNG\r\n\x1a\n" + b"\0" * 32, b"GIF89a....", jpeg(w=60000, h=60000), jpeg()[:30], b"\xff\xd8<svg onload=alert(1)>"]:
    assert app_post({"consent": True, "description": "x", "images": [b64(bad)]})[0] == 400, bad[:12]
assert app_post({"consent": True, "description": "x", "images": [b64(jpeg())] * 6})[0] == 400, "at most 5"
assert app_post({"consent": True, "description": "x", "images": ["not base64!"]})[0] == 400
ideas = json.loads(call("/admin/api/bugs?kind=feature&status=all", cookie=cookie)[2])
bugs = json.loads(call("/admin/api/bugs?status=all", cookie=cookie)[2])
assert [b["id"] for b in ideas] == [idea["id"]] and idea["id"] not in [b["id"] for b in bugs] and bug["id"] in [b["id"] for b in bugs]
saved = json.loads(call("/admin/api/bugs/%d" % idea["id"], cookie=cookie)[2])
assert "\n" not in saved["title"] and "\r" not in saved["title"] and saved["logs"] == "" and saved["category"] == "feature", saved
stats = json.loads(call("/admin/api/stats", cookie=cookie)[2])
assert stats["ideas"]["new"] == 1, stats
headers = call("/dashboard", cookie=cookie)[1]
assert "frame-ancestors" in headers["Content-Security-Policy"]
assert hub.trusted_proxy("10.1.2.3") and hub.trusted_proxy("::1") and not hub.trusted_proxy("172.217.0.1") and not hub.trusted_proxy("x")

# ---- website: public pages in two languages, edited and reset from the dashboard
status, headers, body = call("/")
assert status == 200 and b'lang="sk"' in body and b"Plan&amp;Ride" in body and "frame-ancestors" in headers["Content-Security-Policy"]
assert call("/en/privacy")[0] == 200 and b"privacy policy" in call("/en/privacy")[2]
assert call("/support/")[0] == 200 and call("/nope")[0] == 404 and call("/mark.png")[0] == 200
# A shared bus goes on to the web version; anything but a plain token doesn't.
assert call("/f?t=eyJyZWYi_-")[1]["Location"] == "https://web.example/?follow=eyJyZWYi_-"
assert call("/f?t=a%22%3E")[0] == 404 and call("/f")[0] == 404


def api(path, data=None):
    req = urllib.request.Request(base + "/api/v1/" + path, data=None if data is None else json.dumps(data).encode(),
                                 headers={"Content-Type": "application/json", **key})
    try:
        res = opener.open(req)
    except urllib.error.HTTPError as err:
        res = err
    return res.status, json.loads(res.read() or b"{}")


# One-time share codes: the code is spent once entered, and the bus goes over only after the sharer accepts.
st, made = api("shares", {"trip": "eyJyZWYi", "label": "36"})
assert st == 201 and len(made["code"]) == 6, made
assert api("shares/claim", {"code": made["code"]})[0] == 400, "a name is required"
st, claim = api("shares/claim", {"code": made["code"], "name": "Jana Nováková"})
assert st == 200 and claim["label"] == "36"
assert api("shares/claim", {"code": made["code"], "name": "Someone else"})[0] == 404, "one use only"
assert api(f"shares/{made['id']}?token=wrong")[0] == 404
assert api(f"shares/{made['id']}?token={made['token']}")[1] == {"status": "asked", "name": "Jana Nováková", "code": made["code"],
                                                               "expiresAt": made["expiresAt"], "position": None}
assert "trip" not in api(f"shares/claim/{claim['claimId']}?token={claim['token']}")[1], "nothing before the answer"
assert api(f"shares/{made['id']}/accept", {"token": made["token"]})[1]["status"] == "accepted"
assert api(f"shares/claim/{claim['claimId']}?token={claim['token']}")[1]["trip"] == "eyJyZWYi"
# After accepting, each side sends where it is and sees the other's.
assert api(f"shares/{made['id']}/position", {"token": made["token"], "lat": 48.73, "lon": 19.15, "accuracy": 12})[0] == 200
assert api(f"shares/claim/{claim['claimId']}?token={claim['token']}")[1]["position"]["lat"] == 48.73
st, back = api(f"shares/claim/{claim['claimId']}/position", {"token": claim["token"], "lat": 48.58, "lon": 19.12})
assert st == 200 and back["position"]["lon"] == 19.15
assert api(f"shares/{made['id']}?token={made['token']}")[1]["position"]["lat"] == 48.58
assert api(f"shares/claim/{claim['claimId']}/position", {"token": "wrong", "lat": 1, "lon": 1})[0] == 404


# The same over WebSockets: the sharer hears about the code being entered, the other side gets the bus and positions.
import socket as _socket
import struct as _struct


def ws_open(query):
    host, port = base.split("//")[1].split(":")
    sock = _socket.create_connection((host, int(port)), timeout=5)
    crlf = "\r\n"
    sock.sendall((f"GET /api/v1/shares/live?{query} HTTP/1.1{crlf}Host: {host}{crlf}Upgrade: websocket{crlf}Connection: Upgrade{crlf}"
                  f"Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ=={crlf}Sec-WebSocket-Version: 13{crlf}{crlf}").encode())
    head = b""
    while (crlf + crlf).encode() not in head:
        head += sock.recv(1)
    assert b" 101 " in head and b"s3pPLMBiTxaQ9kYGzzhZRbK+xOo=" in head, head
    return sock


def ws_read(sock):
    b1, b2 = sock.recv(1)[0], sock.recv(1)[0]
    n = b2 & 0x7F
    if n == 126:
        n = _struct.unpack(">H", sock.recv(2))[0]
    data = b""
    while len(data) < n:
        data += sock.recv(n - len(data))
    return json.loads(data)


def ws_send(sock, obj):
    payload, mask = json.dumps(obj).encode(), b"abcd"
    sock.sendall(bytes([0x81, 0x80 | len(payload)]) + mask + bytes(c ^ mask[i % 4] for i, c in enumerate(payload)))


st, made = api("shares", {"trip": "eyJyZWYi", "label": "Michal · 36"})
owner = ws_open(f"side=owner&id={made['id']}&token={made['token']}")
assert ws_read(owner)["status"] == "waiting"
st, claim = api("shares/claim", {"code": made["code"], "name": "Jana"})
assert ws_read(owner) == {"type": "status", "status": "asked", "name": "Jana"}
other = ws_open(f"side=claim&id={claim['claimId']}&token={claim['token']}")
assert ws_read(other)["status"] == "asked"
api(f"shares/{made['id']}/accept", {"token": made["token"]})
assert ws_read(owner)["status"] == "accepted"
got = ws_read(other)
assert got["status"] == "accepted" and got["trip"] == "eyJyZWYi", got
ws_send(owner, {"type": "position", "lat": 48.7, "lon": 19.1, "accuracy": 5})
assert ws_read(other)["position"]["lat"] == 48.7
ws_send(other, {"type": "position", "lat": 48.6, "lon": 19.2})
assert ws_read(owner)["position"]["lon"] == 19.2
assert ws_open.__name__ and call("/api/v1/shares/live?side=owner&id=x&token=y")[0] in (401, 404)
owner.close(); other.close()
assert call("/admin/api/site")[0] == 401, "editing needs a signed-in admin"
pages = json.loads(call("/admin/api/site", cookie=cookie)[2])
assert len(pages) == 8 and not any(p["custom"] for p in pages)
assert b"Draft" in json.loads(call("/admin/api/site/support/en/preview", {"html": "<h1>Draft</h1>"}, cookie)[2])["html"].encode()
assert b"Draft" not in call("/en/support")[2], "a preview is not published"
assert call("/admin/api/site/support/en/save", {"html": "<h1>New support</h1>"}, cookie)[0] == 200
assert b"New support" in call("/en/support")[2] and b"New support" not in call("/support")[2]
assert call("/admin/api/site/support/en/reset", {}, cookie)[0] == 200
assert b"New support" not in call("/en/support")[2]
assert call("/admin/api/site/support/de/save", {"html": "x"}, cookie)[0] == 404

# ---- timetable: two carriers meet at "Town, AS"; shortcuts, boards, trips, connections, API
import io  # noqa: E402
import zipfile  # noqa: E402
import timetable  # noqa: E402


def gtfs(path, stops, trips):
    """stops: [(id, name, lat, lon)]; trips: {trip_id: (line, [(stop_id, "HH:MM")])}. Runs every day of 2026."""
    files = {
        "agency.txt": "agency_id,agency_name\nx,Carrier " + os.path.basename(path)[0],
        "calendar.txt": "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date\nall,1,1,1,1,1,1,1,20260101,20261231",
        "stops.txt": "stop_id,stop_name,stop_lat,stop_lon\n" + "\n".join(f"{i},\"{n}\",{la},{lo}" for i, n, la, lo in stops),
        "routes.txt": "route_id,agency_id,route_short_name,route_long_name,route_type\n" + "\n".join(
            f"r{t},x,{line},,3" for t, (line, _) in trips.items()),
        "trips.txt": "route_id,service_id,trip_id,trip_headsign\n" + "\n".join(f"r{t},all,{t}," for t in trips),
        "stop_times.txt": "trip_id,arrival_time,departure_time,stop_id,stop_sequence\n" + "\n".join(
            f"{t},{hm}:00,{hm}:00,{s},{k}" for t, (_, rows) in trips.items() for k, (s, hm) in enumerate(rows)),
    }
    with zipfile.ZipFile(path, "w") as z:
        for name, text in files.items():
            z.writestr(name, text)


folder = tempfile.mkdtemp()
gtfs(os.path.join(folder, "a.zip"), [("1", "Town,,AS", 48.70, 19.10), ("2", "Town, Park", 48.71, 19.12),
                                     ("3", "Village,,Zlatý Potok,konečná", 48.60, 19.20)],
     {"a1": ("101", [("3", "06:00"), ("1", "06:20"), ("2", "06:30")])})
gtfs(os.path.join(folder, "b.zip"), [("B1", "Town, AS", 48.7001, 19.1001), ("B2", "City, Station", 48.80, 19.30),
                                     ("B3", "Nowhere", -1.0, -1.0)],
     {"b1": ("202", [("B1", "06:30"), ("B2", "07:10")]), "b2": ("202", [("B1", "07:30"), ("B3", "07:50"), ("B2", "08:10")])})
tt = timetable.Timetable({"a": os.path.join(folder, "a.zip"), "b": os.path.join(folder, "b.zip")}, today=datetime.date(2026, 9, 29))
assert [s["name"] for s in tt.search("v z p k")] == ["Village, Zlatý Potok, konečná"], "initials, in order"
assert [s["name"] for s in tt.search("vill zla po kon")] == ["Village, Zlatý Potok, konečná"]
assert tt.search("t as")[0]["name"] == "Town, AS" and tt.search("zzz") == []
assert tt.search("town parkovisko")[0]["name"] == "Town, Park", "a full word finds the abbreviated name (EM-0016: nábr.)"
assert len([n for n in tt.names if n == "Town, AS"]) == 1, "one station for both carriers"
at = lambda hm: int(datetime.datetime(2026, 9, 30, *map(int, hm.split(":")), tzinfo=timetable.ZONE).timestamp())  # noqa: E731
hm = lambda ms: datetime.datetime.fromtimestamp(ms / 1000, timetable.ZONE).strftime("%d %H:%M")  # noqa: E731
village, city, town = tt.resolve("v z p k"), tt.resolve("city station"), tt.resolve("town as")
first = tt.plan(village, city, at("05:50"), 2)[0]
assert [(leg["line"], hm(leg["departure"]), hm(leg["arrival"])) for leg in first["legs"]] == \
    [("101", "30 06:00", "30 06:20"), ("202", "30 06:30", "30 07:10")], first
assert tt.plan(village, city, at("05:50"), 1, direct=True) == [], "no single bus"
assert [hm(j["departure"]) for j in tt.plan(town, city, at("06:00"), 2, direct=True)] == ["30 06:30", "30 07:30"]
board = tt.departures([town], at("06:00"), 5)
assert [(d["line"], hm(d["at"])) for d in board[:3]] == [("101", "30 06:20"), ("202", "30 06:30"), ("202", "30 07:30")], board
trip = tt.trip(board[1]["tripId"])
assert [s["name"] for s in trip["stops"]] == ["Town, AS", "City, Station"] and hm(trip["stops"][1]["arrival"]) == "30 07:10"
assert tt.trip("b:nope:20260930") is None
# EM-0022: a 1-minute change at the same stop counts (city bus 1 at Štadlerovo 15:29, IDS 60101 leaves 15:30)
gtfs(os.path.join(folder, "m.zip"), [("1", "Home", 48.70, 19.10), ("2", "Hub, Stop", 48.71, 19.12)], {"m1": ("1", [("1", "06:00"), ("2", "06:09")])})
gtfs(os.path.join(folder, "n.zip"), [("1", "Hub, Stop", 48.71, 19.12), ("2", "Far, Town", 48.60, 19.20)],
     {"n1": ("60101", [("1", "06:10"), ("2", "06:26")]), "n2": ("60101", [("1", "07:10"), ("2", "07:26")])})
tm = timetable.Timetable({"m": os.path.join(folder, "m.zip"), "n": os.path.join(folder, "n.zip")}, today=datetime.date(2026, 9, 29))
quick = tm.plan(tm.resolve("home"), tm.resolve("far town"), at("05:55"), 1)[0]
assert [(l.get("line"), hm(l["departure"])) for l in quick["legs"]] == [("1", "30 06:00"), ("60101", "30 06:10")], quick["legs"]
# a change stop the rider chose: board there, no walk to another stop first
assert all(l["type"] == "trip" for c in tt.plan(tt.resolve("town as"), tt.resolve("city station"), at("06:00"), 3, walk_start=False)
           for l in c["legs"][:1]), "the first leg is a bus from the chosen stop"
careful = tm.plan(tm.resolve("home"), tm.resolve("far town"), at("05:55"), 1, change=5 * 60)[0]
assert [(l.get("line"), hm(l["departure"])) for l in careful["legs"]] == [("1", "30 06:00"), ("60101", "30 07:10")], "5 min to change: the later bus"
city = tt.resolve("city station")
assert tt.departures([city], at("06:00"), 5) == [], "nothing leaves the end of the line"
ends = tt.departures([city], at("06:00"), 5, ending=True)
assert [(d["line"], hm(d["at"]), d.get("ends")) for d in ends[:2]] == [("202", "30 07:10", True), ("202", "30 08:10", True)], ends
# city buses read from sadzv: its stops join ours by position and name, the day read stands in for later days of its kind
city_day = {"date": "2026-09-29", "platforms": {  # a Tuesday
    "501": ["Námestie X", 48.7300, 19.1400, "1", 900], "502": ["Park", 48.71005, 19.12005, "2", 901]},
    "trips": [{"lineId": 77, "line": "9", "route": "9001", "trip": 5, "headsign": "Zvolen, Park", "stops": [[501, 25200], [502, 25800]]}]}
tc = timetable.Timetable({"a": os.path.join(folder, "a.zip"), "b": os.path.join(folder, "b.zip")}, today=datetime.date(2026, 9, 29), city=[city_day])
assert tc.feeds[timetable.CITY_FEED] == 1 and tc.search("town namestie x")[0]["name"] == "Town, Námestie X", "a new stop gets its town"
assert tc.resolve("town park") == tt.resolve("town park"), "sadzv's Park is our Town, Park (same place)"
wed = tc.plan(tc.resolve("town namestie x"), tc.resolve("town park"), at("06:50"), 1)[0]["legs"][0]
assert (wed["line"], hm(wed["departure"]), wed["live"]["lineId"], wed["from"]["platformId"], wed["to"]["seq"]) == ("9", "30 07:00", 77, 501, 2), wed
assert (wed["live"]["fromIndex"], wed["live"]["toIndex"]) == (0, 1) and tc.trip(wed["tripId"])["stops"][1]["name"] == "Town, Park",     "on another day the apps open the timetable trip"
sat = int(datetime.datetime(2026, 10, 3, 6, 50, tzinfo=timetable.ZONE).timestamp())
assert tc.plan(tc.resolve("town namestie x"), tc.resolve("town park"), sat, 1) == [], "a workday's buses don't run on Saturday"
x = tc.resolve("town namestie x")
assert [(d["line"], hm(d["at"])) for d in tc.departures([x], at("06:00"), 5)][0] == ("9", "30 07:00")
assert tc.departures([x], at("06:00"), 5, {"sad-zvolen-as"}) == [], "the apps show SAD Zvolen live instead"
# A city way's stops set in the map editor: an added stop is timed by distance between the trip's own stops.
mid = tc.by_id.get("town as")
if mid is not None:
    r0 = [(x, 25200, "501"), (tc.resolve("town park"), 25800, "502")]
    ids = [tc.station_ids[x], tc.station_ids[mid], tc.station_ids[r0[1][0]]]
    own = tc._own_stops(r0, ids, {})
    assert [o[0] for o in own] == [x, mid, r0[1][0]] and 25200 < own[1][1] < 25800, own
    assert tc._own_stops(r0, list(reversed(ids)), {}) == r0, "another order: S.A.D.'s stops stay"
    # Line 7: S.A.D. ends the trip early; stops added after its last one go on at its speed (and before its first).
    far = tc.by_id.get("city station")
    if far is not None:
        longer = tc._own_stops(r0, [tc.station_ids[x], tc.station_ids[r0[1][0]], tc.station_ids[far]], {})
        assert [o[0] for o in longer] == [x, r0[1][0], far] and longer[2][1] > 25800, longer
        earlier = tc._own_stops(r0, [tc.station_ids[far], tc.station_ids[x], tc.station_ids[r0[1][0]]], {})
        assert earlier[0][0] == far and earlier[0][1] < 25200, earlier
# A way's stops set in the editor apply right away, and taking them off goes back to S.A.D.'s stops.
city_i = next(iter(tc.city))
key9 = f"{tc.city[city_i][0]}-{tc.city[city_i][1]}"
own9 = [tc.station_ids[s] for s in tc.trips[city_i][7]] + [tc.station_ids[tc.by_id["city station"]]]
timetable.edits["routes"][key9] = own9
tc.apply_routes()
assert tc.station_ids[tc.trips[city_i][7][-1]] == "city station" and len(tc.trips[city_i][7]) == 3, tc.trips[city_i][7]
del timetable.edits["routes"][key9]
tc.apply_routes()
assert len(tc.trips[city_i][7]) == 2, "removed again"
# Many requests for a day not built yet (every map tab after a restart): built once, the others wait for it.
import threading as _th
built_days = []
_real_build = tc._build_day
tc._build_day = lambda d: built_days.append(d) or _real_build(d)
with tc._lock:
    tc._days.clear()
day_ = datetime.date(2026, 9, 30)
workers = [_th.Thread(target=tc.day, args=(day_,)) for _ in range(8)]
[w.start() for w in workers]; [w.join() for w in workers]
assert built_days == [day_], built_days
tc._build_day = _real_build

# A city bus sending its GPS (sadzv) is on the map where it is, with its delay; without it, where the timetable says.
on_map = lambda gps: [v for v in tc.vehicles(at("07:05"), (-90, -180, 90, 180), gps=gps) if v["line"] == "9"]  # noqa: E731
assert [(v["lat"], v["lon"], v["delay"], v["source"]) for v in on_map({(77, 5): (48.72, 19.13, 60)})] == [(48.72, 19.13, 60, "bus")]
assert [(v["live"], v["delay"]) for v in on_map({})] == [(False, None)]
# EM-0014: a city bus that catches the same regional bus further on must not replace that direct bus
gtfs(os.path.join(folder, "c.zip"), [("X", "Zvolen, Zlatý Potok konečná", 48.58, 19.14), ("M", "Zvolen, Kozáčeka", 48.575, 19.125),
                                     ("Y", "Banská Bystrica, Tajovského školy", 48.74, 19.12)],
     {"r101": ("60101", [("X", "06:40"), ("M", "06:53"), ("Y", "07:33")]), "c8": ("8", [("X", "06:45"), ("M", "06:50")])})
tr = timetable.Timetable({"c": os.path.join(folder, "c.zip")}, today=datetime.date(2026, 9, 29))
first = tr.plan(tr.resolve("zvolen zlaty potok konecna"), tr.resolve("bb tajovskeho skoly"), at("06:39"), 5)[0]
assert [(leg["line"], hm(leg["departure"])) for leg in first["legs"]] == [("60101", "30 06:40")], first
timetable.current = tt
def app_get(path, key="dev-key"):
    req = urllib.request.Request(base + "/api/v1/timetable/" + path, headers={"X-App-Key": key})
    try:
        res = opener.open(req)
    except urllib.error.HTTPError as err:
        res = err
    return res.status, json.loads(res.read() or b"{}")
assert app_get("stops?q=t%20as", key="wrong")[0] == 401
assert app_get("stops?q=t%20as")[1]["stops"][0]["id"] == "town as"

# ==== 2.17.0
def v1(method, path, data=None, raw=False):
    req = urllib.request.Request(base + path, method=method, data=None if data is None else json.dumps(data).encode(),
                                 headers={"Content-Type": "application/json", "X-App-Key": "dev-key"})
    try:
        res = opener.open(req)
    except urllib.error.HTTPError as err:
        res = err
    body = res.read()
    return res.status, (body.decode() if raw else json.loads(body or b"{}"))

# Crowding: a level 1-3 from a rider on the bus, the newest in 20 min shown with the delay; anything else refused.
assert v1("POST", "/api/v1/community/crowd", {"tripKey": "9|x|1", "reporter": "c1", "level": 7})[0] == 400
assert v1("POST", "/api/v1/community/crowd", {"tripKey": "9|x|1", "level": 2})[0] == 400, "no reporter"
assert v1("POST", "/api/v1/community/crowd", {"tripKey": "9|x|1", "reporter": "c1", "level": "2"})[0] == 400, "a string is not a level"
assert v1("POST", "/api/v1/community/crowd", {"tripKey": "9|x|1", "reporter": "c1", "level": 2})[1]["crowd"]["level"] == 2
v1("POST", "/api/v1/community/crowd", {"tripKey": "9|x|1", "reporter": "c2", "level": 3})
assert v1("GET", "/api/v1/community/delay?trip=9%7Cx%7C1")[1]["crowd"]["level"] == 3, "the newest report counts"
assert v1("GET", "/api/v1/community/delay?trip=9%7Cx%7C2")[1]["crowd"] is None

# How often a change worked on earlier days: the first bus's delay against the time to change.
def past_key(line, days_ago):
    d = datetime.datetime.now(hub.LOCAL_ZONE).replace(hour=7, minute=0, second=0, microsecond=0) - datetime.timedelta(days=days_ago)
    return f"{line}|Stop A|{int(d.timestamp()) * 1000}"
with hub.db() as conn_:
    for days_ago, delay in ((7, 60), (14, 300), (21, 600)):
        conn_.execute("""INSERT INTO community_reports(created_at, trip_key, line, stop_index, stop_name, scheduled_ms, delay_s,
                         reporter, platform, kind) VALUES (?,?,?,?,?,?,?,?,?,?)""",
                      (hub.now() - days_ago * 86400, past_key("91", days_ago), "91", 1, "Stop A", 0, delay, f"h{days_ago}", "", "passed"))
    odds = hub.transfer_odds(conn_, past_key("91", 0), past_key("92", 0), 240)
    assert odds == {"worked": 1, "runs": 3}, odds
    assert hub.transfer_odds(conn_, past_key("93", 0), past_key("92", 0), 240) is None, "no history: nothing said"

# Riders' submissions: checked on the way in, kept for an admin, used only once approved.
hub.rebuild_timetable = lambda: None  # the test timetable stays (the real one is rebuilt in the background)
assert v1("POST", "/api/v1/submissions", {"kind": "stop", "name": "Ann", "stopName": "New", "lat": 40, "lon": 19})[0] == 400, "outside Slovakia"
assert v1("POST", "/api/v1/submissions", {"kind": "stop", "name": "", "stopName": "New", "lat": 48.7, "lon": 19.1})[0] == 400, "a name"
assert v1("POST", "/api/v1/submissions", {"kind": "lol", "name": "Ann"})[0] == 400
assert v1("POST", "/api/v1/submissions", {"kind": "trip", "name": "Ann", "line": "X1", "weekdays": [0],
                                          "stops": [["town as", "07:00"], ["no such stop zz", "07:10"]]})[0] == 400, "unknown stop"
assert v1("POST", "/api/v1/submissions", {"kind": "trip", "name": "Ann", "line": "X1", "weekdays": [0],
                                          "stops": [["town as", "07:10"], ["town park", "07:00"]]})[0] == 400, "times go back"
assert v1("POST", "/api/v1/submissions", {"kind": "trip", "name": "Ann", "line": "X1", "weekdays": [0],
                                          "stops": [["town as", "7:x"], ["town park", "07:20"]]})[0] == 400, "a bad time"
assert v1("POST", "/api/v1/submissions", {"kind": "trip", "name": "Ann", "line": "X1", "weekdays": [],
                                          "stops": [["town as", "07:00"], ["town park", "07:20"]]})[0] == 400, "no day"
status, sub_trip = v1("POST", "/api/v1/submissions", {"kind": "trip", "name": "Ann", "email": "a@b.c", "line": "X1",
                      "weekdays": list(range(7)), "stops": [["town as", "07:00"], ["town park", "07:20"]]})
assert status == 201, sub_trip
status, sub_stop = v1("POST", "/api/v1/submissions", {"kind": "stop", "name": "Ben", "stopName": "Town, New Corner", "lat": 48.701, "lon": 19.101})
assert status == 201
assert v1("POST", "/api/v1/submissions", {"kind": "problem", "name": "Cy", "station": "town as", "text": "Timetable missing"})[0] == 201
listed = admin_get("submissions")["submissions"]
assert {s["kind"] for s in listed} >= {"trip", "stop", "problem"} and all(s["status"] == "new" for s in listed[:3])
assert [s for s in listed if s["id"] == sub_trip["id"]][0]["data"]["stopNames"][0] == tt.names[tt.by_id["town as"]]
assert not timetable.edits["extra_trips"], "nothing used before approval"
call(f"/admin/api/submissions/{sub_trip['id']}/approve", {"note": "checked"}, cookie)
call(f"/admin/api/submissions/{sub_stop['id']}/approve", {}, cookie)
assert [x["line"] for x in timetable.edits["extra_trips"]] == ["X1"] and timetable.edits["extra_stations"][0]["name"] == "Town, New Corner"
tx = timetable.Timetable({"a": os.path.join(folder, "a.zip"), "b": os.path.join(folder, "b.zip")}, today=datetime.date(2026, 9, 29))
assert tx.resolve("town new corner") is not None, "an approved stop is a stop"
x_trips = [x for x in tx.trips if x[2] == "X1"]
assert x_trips and tx.station_ids[x_trips[0][7][0]] == "town as" and x_trips[0][9] == (25200, 26400)
x1 = tx.departures([tx.by_id["town as"]], int(datetime.datetime(2026, 9, 30, 6, 50, tzinfo=timetable.ZONE).timestamp()), 20)
assert any(d["line"] == "X1" for d in x1), x1
call(f"/admin/api/submissions/{sub_trip['id']}/reject", {}, cookie)
assert not timetable.edits["extra_trips"], "rejected again: gone"

# Lines added in the dashboard: checked, one trip per departure, in the timetable; edited and deleted again.
def acall(path_, data_, ck=""):
    st_, _, b_ = call(path_, data_, ck)
    return st_, json.loads(b_ or b"{}")
bad_line = {"line": "X9", "weekdays": [0], "stops": [["town as", 0], ["town park", 12]], "departures": ["7:00"]}
for broken, why in (({**bad_line, "line": ""}, "line"), ({**bad_line, "weekdays": []}, "day"), ({**bad_line, "stops": [["town as", 0]]}, "2 to 80"),
                    ({**bad_line, "stops": [["town as", 5], ["town park", 12]]}, "start at 0"), ({**bad_line, "stops": [["town as", 0], ["town park", -1]]}, "go back"),
                    ({**bad_line, "stops": [["town as", 0], ["nope zz", 3]]}, "timetable's stops"), ({**bad_line, "departures": ["25:99"]}, "HH:MM"),
                    ({**bad_line, "departures": []}, "departures"), ({**bad_line, "stops": "x"}, "")):
    status_, body_ = acall("/admin/api/lines", broken, cookie)
    assert status_ == 400 and why in body_.get("error", ""), (broken, status_, body_)
assert call("/admin/api/lines", bad_line)[0] == 401, "only admins add lines"
status_, made = acall("/admin/api/lines", {**bad_line, "departures": ["7:30", "7:00", "7:00"]}, cookie)
assert status_ == 200, made
listed_ = admin_get("lines")
mine = [x for x in listed_["lines"] if x["id"] == made["id"]][0]
assert mine["data"]["departures"] == [25200, 27000] and mine["data"]["headsign"] == tt.names[tt.by_id["town park"]] and listed_["operators"]
x9 = [x for x in timetable.edits["extra_trips"] if x["line"] == "X9"]
assert [x["stops"] for x in x9] == [[["town as", 25200], ["town park", 25920]], [["town as", 27000], ["town park", 27720]]], x9
tl = timetable.Timetable({"a": os.path.join(folder, "a.zip"), "b": os.path.join(folder, "b.zip")}, today=datetime.date(2026, 9, 29))
on_board = tl.departures([tl.by_id["town as"]], int(datetime.datetime(2026, 9, 28, 6, 50, tzinfo=timetable.ZONE).timestamp()), 30)
x9_deps = [d for d in on_board if d["line"] == "X9"]
assert len(x9_deps) == 2 and x9_deps[0]["tripId"].startswith("extra:line-"), x9_deps
assert tl.trip(x9_deps[0]["tripId"])["stops"][-1]["id"] == "town park", "a line's trip can be opened and followed"
assert call(f"/admin/api/lines/{made['id']}", {**bad_line, "line": "X10"}, cookie)[0] == 200
assert {x["line"] for x in timetable.edits["extra_trips"]} >= {"X10"} and not [x for x in timetable.edits["extra_trips"] if x["line"] == "X9"]
assert call(f"/admin/api/lines/{made['id']}/delete", {}, cookie)[0] == 200
assert not [x for x in timetable.edits["extra_trips"] if x["line"] == "X10"] and call("/admin/api/lines/999999", bad_line, cookie)[0] == 404
# A stop placed on the map by an admin: a stop of the timetable at once (after the rebuild).
status_, new_stop = acall("/admin/api/stops/new", {"name": "Town, Brand New", "lat": 48.7, "lon": 19.1}, cookie)
assert status_ == 200 and any(s["name"] == "Town, Brand New" for s in timetable.edits["extra_stations"])
assert call("/admin/api/stops/new", {"name": "x", "lat": 1, "lon": 1}, cookie)[0] == 400

# Weather around the stop, asked by emHUB (cached), never by the phone.
real_http = hub.http_json
hub.http_json = lambda url, *a, **k: {"hourly": {"precipitation": [0.2, 0.6, 0.1], "precipitation_probability": [40, 80, 30]}}
hub._weather.clear()
status, wx = app_get("weather?lat=48.73&lon=19.15")
assert status == 200 and wx == {"rainMm": 0.9, "chance": 80, "rainy": True}, wx
assert app_get("weather?lat=40&lon=19")[0] == 400 and app_get("weather?lat=x&lon=1")[0] == 400
hub.http_json = real_http

# Offline timetables by region, with a version that changes with each timetable build.
status, idx = app_get("offline")
assert status == 200 and idx["version"] == int(tt.built) and {r["id"] for r in idx["regions"]} >= {"bb", "ba", "ke"}
status, region = app_get("offline/region?id=bb")
assert status == 200 and region["version"] == idx["version"] and region["stations"] and region["trips"], region.keys()
st_ids = [s_[0] for s_ in region["stations"]]
assert all(len(x[3]) == len(x[4]) >= 2 and all(0 <= i < len(st_ids) for i in x[3]) for x in region["trips"])
assert app_get("offline/region?id=../etc")[0] == 404

# A shared arrival: only its owner updates it; the page shows it to anyone, then that it arrived.
status, eta = v1("POST", "/api/v1/eta", {"label": "Ann", "stop": "Town, Park"})
assert status == 201 and eta["url"].endswith("/eta/" + eta["id"])
assert v1("POST", f"/api/v1/eta/{eta['id']}", {"token": "nope", "arrivalMs": 1})[0] == 404
assert "Waiting for the bus" in v1("GET", f"/eta/{eta['id']}", raw=True)[1]
arrive_ms = (hub.now() + 600) * 1000
assert v1("POST", f"/api/v1/eta/{eta['id']}", {"token": eta["token"], "arrivalMs": arrive_ms, "delaySeconds": 240, "line": "7"})[0] == 200
page_ = v1("GET", f"/eta/{eta['id']}", raw=True)[1]
assert "arrives at" in page_ and "4 min late" in page_ and "line 7" in page_, page_
assert "&lt;script" in v1("GET", "/eta/" + v1("POST", "/api/v1/eta", {"label": "<script>", "stop": "x"})[1]["id"], raw=True)[1]
v1("POST", f"/api/v1/eta/{eta['id']}/end", {"token": eta["token"]})
assert "Arrived" in v1("GET", f"/eta/{eta['id']}", raw=True)[1]
assert "Not shared any more" in v1("GET", "/eta/unknown", raw=True)[1]
# Making links is limited per address (they live in memory), so a flood is refused.
assert 429 in [v1("POST", "/api/v1/eta", {"stop": "x"})[0] for _ in range(35)]

# ==== 1.34: reporters' language, saved replies, labels, fixed-in, duplicates, releases, activity log
sent_mail = []
real_queue_mail = hub.queue_mail
hub.queue_mail = lambda to, subject, body, message_id=None, reply_to="": sent_mail.append((to, subject, body))
hub.bug_limiter.allow = lambda *a, **k: True
assert hub.guess_lang({"language": "en"}, "", "Autobus mešká") == "en", "the app's own language wins"
assert hub.guess_lang({}, "Plan&Ride web 2.17.0\nlanguage en, standalone true", "x") == "en", "older web versions: the logs header"
assert hub.guess_lang({}, "", "Notifikácia chodí o hodinu neskôr") == "sk"
assert hub.guess_lang({}, "", "There is dual time in the notification and it is the same") == "en"
st_, sk_bug = app_post({"consent": True, "title": "Notifikácia mešká", "description": "Chodí o hodinu neskôr", "email": "sk@example.com", "language": "sk"})
assert st_ == 201
assert any(m[0] == "sk@example.com" and "Vaše hlásenie sme dostali" in m[1] and 'lang="sk"' in m[2] for m in sent_mail), sent_mail[-2:]
st_, en_bug = app_post({"consent": True, "title": "Dual time in the notification", "description": "Both times are the same", "email": "en@example.com",
                        "logs": "x\nFATAL EXCEPTION: main\nProcess: com.ksjd.testem, PID: 123\njava.lang.IllegalStateException: boom 42\n"})
assert any(m[0] == "en@example.com" and "We got your report" in m[1] for m in sent_mail)
detail = admin_get(f"bugs/{en_bug['id']}")
assert detail["lang"] == "en" and detail["similar"] == []
st_, en_bug2 = app_post({"consent": True, "title": "Dual time in notification bar", "description": "Same", "logs":
                         "y\nFATAL EXCEPTION: main\nProcess: com.ksjd.testem, PID: 999\njava.lang.IllegalStateException: boom 7\n"})
similar = admin_get(f"bugs/{en_bug2['id']}")["similar"]
assert similar and similar[0]["id"] == en_bug["id"] and similar[0]["why"] == "same crash", similar
# Language, labels and fixed-in set by an admin; a bad version is refused; the list filters by label.
assert call(f"/admin/api/bugs/{en_bug['id']}/meta", {"fixedIn": "two"}, cookie)[0] == 400
assert call(f"/admin/api/bugs/{en_bug['id']}/meta", {"labels": "Notifications, tracking,notifications", "fixedIn": "2.17.1"}, cookie)[0] == 200
assert call(f"/admin/api/bugs/{sk_bug['id']}/meta", {"fixedIn": "2.17.1", "lang": "sk"}, cookie)[0] == 200
assert admin_get(f"bugs/{en_bug['id']}")["labels"] == "notifications,tracking"
assert admin_get(f"bugs/{en_bug['id']}")["status"] == "awaiting_release", "a version set: waiting for that update"
assert en_bug["id"] in [b["id"] for b in admin_get("bugs?status=open")], "still open until it's out"
assert [b["id"] for b in admin_get("bugs?status=open&label=tracking")] == [en_bug["id"]]
assert ("notifications", 1) in [tuple(x) for x in admin_get("labels")["labels"]]
# A reply goes in the reporter's language.
sent_mail.clear()
call(f"/admin/api/bugs/{sk_bug['id']}/reply", {"body": "Ďakujeme"}, cookie)
assert sent_mail and 'lang="sk"' in sent_mail[-1][2] and "Dobrý deň" in sent_mail[-1][2]
# Saved replies: the defaults, then add, edit and delete; a reply needs a title and a text.
replies = admin_get("replies")["replies"]
assert any(r["title"] == "Allow alarms and reminders" and "Budíky a pripomienky" in r["body_sk"] for r in replies)
assert call("/admin/api/replies", {"title": "", "bodySk": "x"}, cookie)[0] == 400
st_, _, made_ = call("/admin/api/replies", {"title": "Thanks", "bodySk": "Ďakujeme!", "bodyEn": "Thanks!"}, cookie)
rid = json.loads(made_)["id"]
assert call(f"/admin/api/replies/{rid}", {"title": "Thanks a lot", "bodySk": "Veľmi ďakujeme!", "bodyEn": ""}, cookie)[0] == 200
assert [r for r in admin_get("replies")["replies"] if r["id"] == rid][0]["body_sk"] == "Veľmi ďakujeme!"
assert call(f"/admin/api/replies/{rid}/delete", {}, cookie)[0] == 200 and not [r for r in admin_get("replies")["replies"] if r["id"] == rid]
assert call("/admin/api/replies/999999", {"title": "x", "bodySk": "y"}, cookie)[0] == 404
# Releases: the draft lists what's fixed in it; releasing emails each reporter once, in their language.
draft = admin_get("releases/2.17.1/draft")
assert {x["id"] for x in draft["items"]} == {en_bug["id"], sk_bug["id"]} and "Dual time in the notification" in draft["en"], draft
assert call("/admin/api/releases/2.17.1/notes", {"en": "• Fixes", "sk": "• Opravy"}, cookie)[0] == 200
sent_mail.clear()
st_, _, out_ = call("/admin/api/releases/2.17.1/release", {}, cookie)
assert st_ == 200 and json.loads(out_)["emailed"] == 2, out_
assert {m[0] for m in sent_mail} == {"sk@example.com", "en@example.com"}
assert any("Opravené vo verzii 2.17.1" in m[1] for m in sent_mail) and any("Fixed in version 2.17.1" in m[1] for m in sent_mail)
assert admin_get(f"bugs/{sk_bug['id']}")["status"] == "resolved"
assert call("/admin/api/releases/2.17.1/release", {}, cookie)[0] == 409, "announced once"
rel = [x for x in admin_get("releases")["releases"] if x["version"] == "2.17.1"][0]
assert rel["notified"] == 1 and rel["notes_sk"] == "• Opravy" and rel["fixes"] == 2
# The activity log has the changes (the route, not what was sent).
entries = admin_get("audit")["entries"]
assert any(e["action"] == "releases/2.17.1/release" and e["username"] == "karafamichal" for e in entries)
assert not any("bodySk" in e["action"] for e in entries)
hub.queue_mail = real_queue_mail
# ==== 1.36: feed health after each build, what changed since the last one, an email when a feed broke
sent_mail.clear()
hub.queue_mail = lambda to, subject, body, message_id=None, reply_to="": sent_mail.append((to, subject, body))
class _Built:  # just what record_build reads
    def __init__(self, built, feeds, trips, stations):
        self.built, self.feeds, self.trips = built, feeds, [(None, None, line, None, op) for op, line in trips]
        self.station_ids, self.names = list(stations), [f"Stop {x}" for x in stations]
with hub.db() as conn_:
    conn_.execute("DELETE FROM feed_history"); conn_.execute("DELETE FROM build_snapshots")
    yesterday_ = (datetime.datetime.now(hub.LOCAL_ZONE).date() - datetime.timedelta(days=1)).isoformat()
    conn_.execute("INSERT INTO feed_history(day, feed, trips) VALUES (?, 'zz', 100)", (yesterday_,))
hub.record_build(_Built(1000, {"zz": 100, "yy": "failed"}, [("Op", "1")] * 10 + [("Op", "2")] * 10, ["a", "b"]))
assert any("yy: could not be read" in m[2] for m in sent_mail), sent_mail
sent_mail.clear()
hub.record_build(_Built(1001, {"zz": 100, "yy": "failed"}, [("Op", "1")] * 30 + [("Op", "3")] * 2, ["a", "c"]))
assert not sent_mail, "one email a feed a day"
health = admin_get("feeds/health")
assert health["feeds"]["zz"] and health["feeds"]["yy"][health["days"][-1]]["trips"] is None
ch = health["builds"][0]["changes"]
assert ch["newLines"] == ["Op|3"] and ch["goneLines"] == ["Op|2"] and ch["changedLines"][0] == {"line": "Op|1", "before": 10, "after": 30}, ch
assert ch["newStops"] == ["Stop c"] and ch["goneStops"] == ["Stop b"]
with hub.db() as conn_:  # a drop of more than 30 % against an earlier day
    conn_.execute("DELETE FROM feed_history WHERE day<>?", (yesterday_,))
hub.record_build(_Built(1002, {"zz": 40}, [("Op", "1")], ["a"]))
assert any("zz: 40 trips, was 100" in m[2] for m in sent_mail), sent_mail
hub.queue_mail = real_queue_mail
# A line runs only between its valid dates.
st_, _, made_ = call("/admin/api/lines", {**bad_line, "line": "X11", "validFrom": "2026-10-01", "validTo": "2026-09-30"}, cookie)
assert st_ == 400
st_, _, made_ = call("/admin/api/lines", {**bad_line, "line": "X11", "weekdays": list(range(7)), "validFrom": "2026-09-30", "validTo": "2026-09-30"}, cookie)
assert st_ == 200
tv = timetable.Timetable({"a": os.path.join(folder, "a.zip"), "b": os.path.join(folder, "b.zip")}, today=datetime.date(2026, 9, 29))
x11 = [x for x in tv.trips if x[2] == "X11"]
assert x11 and all(tv.services[x[6]] == {(datetime.date(2026, 9, 30) - tv.first_day).days} for x in x11), "only on its one valid day"
call(f"/admin/api/lines/{json.loads(made_)['id']}/delete", {}, cookie)
# ==== 1.37: service notices for the apps, written in emHUB; riders' delay and crowding statistics
assert call("/admin/api/notices", {"body_sk": "x"}, cookie)[0] == 400, "a title"
assert call("/admin/api/notices", {"title_sk": "x", "starts_at": 200, "ends_at": 100}, cookie)[0] == 400, "ends after it starts"
st_, _, n1 = call("/admin/api/notices", {"title_sk": "Linka 7 ide obchádzkou", "title_en": "Line 7 detour", "body_sk": "Do piatku.",
                                         "lines": "7, 8 7", "stops": "Zvolen, AS; Zvolen, Námestie SNP", "level": "warning"}, cookie)
assert st_ == 200
st_, _, n2 = call("/admin/api/notices", {"title_en": "Later", "starts_at": hub.now() + 3600}, cookie)
st_, _, n3 = call("/admin/api/notices", {"title_en": "Over", "starts_at": hub.now() - 7200, "ends_at": hub.now() - 3600}, cookie)
shown = v1("GET", "/api/v1/notices")[1]["notices"]
assert [x["title_en"] for x in shown] == ["Line 7 detour"], shown
assert shown[0]["lines"] == ["7", "8"] and shown[0]["stops"] == ["Zvolen, AS", "Zvolen, Námestie SNP"] and shown[0]["level"] == "warning"
assert call(f"/admin/api/notices/{json.loads(n1)['id']}", {"title_sk": "Linka 7 opäť normálne", "level": "info"}, cookie)[0] == 200
assert v1("GET", "/api/v1/notices")[1]["notices"][0]["title_sk"] == "Linka 7 opäť normálne"
for n_ in (n1, n2, n3):
    assert call(f"/admin/api/notices/{json.loads(n_)['id']}/delete", {}, cookie)[0] == 200
assert v1("GET", "/api/v1/notices")[1]["notices"] == [] and call("/admin/api/notices/999999/delete", {}, cookie)[0] == 404
stats = admin_get("community/stats?days=60")
assert stats["days"] == 60 and stats["delays"] and {"line", "trips", "avgDelay", "lateShare", "byHour"} <= set(stats["delays"][0]), stats["delays"][:1]
assert len(stats["delays"][0]["byHour"]) == 24 and any(c["line"] == "9" for c in stats["crowd"]), stats["crowd"]
assert admin_get("community/stats?days=x")["days"] == 30

status, plan = app_get("plan?from=v%20z%20p%20k&to=city%20station&time=2026-09-30T05:50&n=1")
assert status == 200 and len(plan["connections"][0]["legs"]) == 2, plan
assert app_get("plan?from=qqq&to=city")[0] == 404 and app_get("trip?id=junk")[0] == 404
assert app_get(f"departures?lat=48.7&lon=19.1&time={at('07:00')}")[1]["departures"][0]["line"] == "202"
st = json.loads(call("/admin/api/timetable/status", cookie=cookie)[2])
assert st["ready"] and st["trips"] == 3 and "gtfs_feeds" not in st, st
assert call("/admin/api/timetable/feeds", {"feeds": "a,b; rm -rf"}, cookie)[0] == 400
assert json.loads(call("/admin/api/timetable/stops?q=city", cookie=cookie)[2])["stops"][0]["name"] == "City, Station"

# ---- the map: buses now (timetable, or as late as riders say), stops moved by hand kept through a rebuild, road traces
box = (48.0, 18.0, 49.5, 20.5)
now_bus = [v for v in tt.vehicles(at("06:25"), box) if v["line"] == "101"]
assert len(now_bus) == 1 and not now_bus[0]["live"] and now_bus[0]["next"] == "Town, Park", now_bus
assert abs(now_bus[0]["lat"] - 48.705) < 0.002, "halfway between Town, AS (06:20) and Town, Park (06:30)"
late = [v for v in tt.vehicles(at("06:25"), box, lambda keys: {"delaySeconds": 600, "source": "gps"}) if v["line"] == "101"]
assert late and late[0]["live"] and late[0]["next"] == "Town, AS" and late[0]["delay"] == 600, "10 min late: still before Town, AS"
assert tt.vehicles(at("05:00"), box) == [] and tt.vehicles(at("06:25"), (0, 0, 1, 1)) == []
assert any(k.startswith("101|Village, Zlatý Potok, konečná|") for k in tt.community_keys(tt.trip_index[("a", "a1")], datetime.date(2026, 9, 30)))
status, body_ = call("/admin/api/map/stations?bbox=48.6,19.0,48.8,19.2", cookie=cookie)[0], None
assert status == 200
assert call("/admin/api/map/stop", {"id": "town park", "lat": 48.7111, "lon": 19.1222}, cookie)[0] == 200
assert call("/admin/api/map/stop", {"id": "town park", "lat": 10, "lon": 10}, cookie)[0] == 400, "only in Slovakia"
assert tt.lat[tt.by_id["town park"]] == 48.7111, "in use right away"
rebuilt = timetable.Timetable({"a": os.path.join(folder, "a.zip"), "b": os.path.join(folder, "b.zip")}, today=datetime.date(2026, 9, 29))
assert rebuilt.lat[rebuilt.by_id["town park"]] == 48.7111, "a timetable update keeps the moved stop"
moved = json.loads(call("/admin/api/map/stations?bbox=48.6,19.0,48.8,19.2", cookie=cookie)[2])["stations"]
assert next(x for x in moved if x["id"] == "town park")["moved"]
assert call("/admin/api/map/segment", {"from": "town as", "to": "town park", "points": [[48.72, 19.11]]}, cookie)[0] == 200
shape = json.loads(call("/admin/api/map/trip?id=" + urllib.parse.quote(now_bus[0]["tripId"]), cookie=cookie)[2])
traced = next(x for x in shape["segments"] if x["from"] == "town as")
assert traced["traced"] and len(traced["points"]) == 3 and traced["points"][1] == [48.72, 19.11], traced
assert call("/admin/api/map/segment/reset", {"from": "town as", "to": "town park"}, cookie)[0] == 200
assert call("/admin/api/map/stop/reset", {"id": "town park"}, cookie)[0] == 200 and tt.lat[tt.by_id["town park"]] == 48.71
# 1.35: every map edit is in the history and can be undone (newest first; not over a newer change).
hist = json.loads(call("/admin/api/map/history", cookie=cookie)[2])["history"]
seg_edits = [h for h in hist if h["tbl"] == "map_segments" and h["key"] == "town as>town park"]
assert [h["action"] for h in seg_edits[:2]] == ["segment/reset", "segment"] and "Town, Park" in seg_edits[0]["what"], seg_edits
stop_edits = [h for h in hist if h["tbl"] == "map_stops" and h["key"] == "town park"]
assert [h["action"] for h in stop_edits[:2]] == ["stop/reset", "stop"], stop_edits
assert call(f"/admin/api/map/history/{seg_edits[1]['id']}/undo", {}, cookie)[0] == 409, "the newer reset has to be undone first"
assert call(f"/admin/api/map/history/{seg_edits[0]['id']}/undo", {}, cookie)[0] == 200
assert timetable.edits["segments"]["town as>town park"][0] == [48.72, 19.11], "the drawn part is back"
assert call(f"/admin/api/map/history/{seg_edits[0]['id']}/undo", {}, cookie)[0] == 409, "undone once"
assert call(f"/admin/api/map/history/{seg_edits[1]['id']}/undo", {}, cookie)[0] == 200
assert "town as>town park" not in timetable.edits["segments"], "before the first save there was nothing"
assert call(f"/admin/api/map/history/{stop_edits[0]['id']}/undo", {}, cookie)[0] == 200
with hub.db() as conn_:
    assert conn_.execute("SELECT lat FROM map_stops WHERE station_id='town park'").fetchone()[0] == 48.7111, "the moved stop is back"
assert call(f"/admin/api/map/history/{stop_edits[1]['id']}/undo", {}, cookie)[0] == 200
assert call("/admin/api/map/history/999999/undo", {}, cookie)[0] == 404
# The to-do list: parts still straight, stops without a place, long detours; each with a trip to open.
todo = json.loads(call("/admin/api/map/todo", cookie=cookie)[2])
assert todo["straight"] and all(x["tripId"].count(":") == 2 and x["uses"] >= 1 for x in todo["straight"]), todo["straight"][:2]
assert todo["counts"]["straight"] >= len(todo["straight"]) and isinstance(todo["unplaced"], list)
# Bulk snapping an operator: in the background, parts still straight, most used first (router stubbed here).
real_snap, real_sleep = hub.snap_segment, hub.time.sleep
hub.snap_segment = lambda a, b, **k: [a, b]
hub.time.sleep = lambda s_: None
op_ = next(x[4] for x in tt.trips if x[4])
assert call("/admin/api/map/bulk", {"operator": "No such company"}, cookie)[0] == 400
assert call("/admin/api/map/bulk", {"operator": op_}, cookie)[0] == 202
for _ in range(200):
    if not hub.bulk.get("running"):
        break
    real_sleep(0.05)
st_bulk = json.loads(call("/admin/api/map/bulk", cookie=cookie)[2])
assert st_bulk["status"]["done"] >= 1 and not st_bulk["status"]["running"] and op_ in st_bulk["operators"], st_bulk
with hub.db() as conn_:
    snapped = conn_.execute("SELECT COUNT(*) FROM map_segments WHERE updated_by LIKE '%(bulk)'").fetchone()[0]
    assert snapped == st_bulk["status"]["done"]
    conn_.execute("DELETE FROM map_segments WHERE updated_by LIKE '%(bulk)'")
hub.load_map_edits()
hub.snap_segment, hub.time.sleep = real_snap, real_sleep
assert call("/admin/api/map/vehicles?bbox=48,18,49.5,20.5", cookie=cookie)[0] == 200
# Stations set up by hand: an extra place where line 101 stops, a zone, and a central station with a sub-station.
place = {"label": "Smer centrum", "lat": 48.7105, "lon": 19.1215, "lines": "101"}
st_ = json.loads(call("/admin/api/map/station", {"id": "town park", "places": [place], "zone": {"type": "circle", "lat": 48.71, "lon": 19.12, "radius": 150}}, cookie)[2])
assert st_["places"][0]["lines"] == ["101"] and st_["zone"]["radius"] == 150, st_
a1 = tt.trip(now_bus[0]["tripId"])
park = next(x for x in a1["stops"] if x["name"] == "Town, Park")
assert (park["lat"], park["lon"], park["platform"]) == (48.7105, 19.1215, "Smer centrum") and park["zone"]["type"] == "circle", park
assert tt.near(48.7108, 19.1208, 50)[0]["meters"] == 0, "inside the zone counts as at the stop"
assert call("/admin/api/map/station", {"id": "town park", "zone": {"type": "polygon", "points": [[48.7, 19.1]]}}, cookie)[0] == 400
village_id = tt.station_ids[village]
assert call("/admin/api/map/child", {"parent": "town as", "child": village_id, "lines": "101"}, cookie)[0] == 400, "central first"
assert json.loads(call("/admin/api/map/station", {"id": "town as", "central": True}, cookie)[2])["central"]
fam = json.loads(call("/admin/api/map/child", {"parent": "town as", "child": village_id, "label": "Platform 2", "lines": "101"}, cookie)[2])
assert fam["children"][0]["id"] == village_id and fam["children"][0]["label"] == "Platform 2", fam
board = tt.departures([tt.by_id["town as"]], at("05:50"), 10)
assert board[0]["stop"]["id"] == village_id and board[0]["platform"] == "Platform 2", "the central station lists its sub-station's buses"
a1 = tt.trip(board[0]["tripId"])
assert a1["stops"][0]["central"]["id"] == "town as" and a1["stops"][0]["platform"] == "Platform 2", a1["stops"][0]
rebuilt = timetable.Timetable({"a": os.path.join(folder, "a.zip"), "b": os.path.join(folder, "b.zip")}, today=datetime.date(2026, 9, 29))
assert rebuilt.platform_for(rebuilt.by_id["town park"], "101")[2] == "Smer centrum" and rebuilt.by_id["town as"] in rebuilt.central, "kept through a rebuild"
assert json.loads(call("/admin/api/map/station", {"id": "town as", "central": False}, cookie)[2])["children"] == [], "not central: no sub-stations"
# Directions: one platform, two ways out for the same line; a bus takes the one heading where it goes (or passes).
bus_id = now_bus[0]["tripId"]
bus = tt.trips[tt.trip_index[(bus_id.split(":", 1)[0], bus_id.split(":", 1)[1].rsplit(":", 1)[0])]]
k_park = bus[7].index(tt.by_id["town park"])
heading = bus[3] or tt.names[bus[7][-1]]
two_ways = {"label": "", "platform": "3", "lat": 48.7105, "lon": 19.1215,
            "directions": [{"name": "Smer Nowhere", "lines": "101", "towards": "Nowhere"}, {"name": "Smer " + heading, "lines": "101", "towards": [heading]}]}
st_ = json.loads(call("/admin/api/map/station", {"id": "town park", "places": [two_ways]}, cookie)[2])
assert st_["places"][0]["lines"] == ["101"] and len(st_["places"][0]["directions"]) == 2, st_
way = tt.serving(bus[7][0])["directions"][0]
assert way["next"] == tt.names[bus[7][1]] and "101" in way["lines"] and way["headsigns"], "ways out, grouped by the next stop"
assert tt.platform_for(tt.by_id["town park"], "101", bus, k_park) == (48.7105, 19.1215, "3"), "the direction it heads"
assert tt.platform_for(tt.by_id["town park"], "101") is None, "without the trip, a direction 'towards' can't be told"
only_elsewhere = dict(two_ways, directions=[{"name": "Smer Nowhere", "lines": "101", "towards": "Nowhere"}])
call("/admin/api/map/station", {"id": "town park", "places": [only_elsewhere]}, cookie)
assert tt.platform_for(tt.by_id["town park"], "101", bus, k_park) is None, "a bus going the other way isn't sent there"
# Two platforms at the first stop: "towards" where the bus ends beats "towards" a stop it only passes.
first = bus[7][0]
passing = {"label": "", "platform": "1", "lat": 48.7, "lon": 19.1, "directions": [{"name": "", "lines": "101", "towards": tt.names[bus[7][1]]}]}
ending = {"label": "", "platform": "2", "lat": 48.7001, "lon": 19.1001, "directions": [{"name": "", "lines": "101", "towards": heading}]}
call("/admin/api/map/station", {"id": tt.station_ids[first], "places": [passing, ending]}, cookie)
assert tt.platform_for(first, "101", bus, 0)[2] == "2", "the bus's own end wins over a stop it passes"
call("/admin/api/map/station", {"id": tt.station_ids[first], "places": []}, cookie)
# No directions set up: a platform across the road from the stop's point is used by buses for which it's the right side.
park = tt.by_id["town park"]
b4, af = bus[7][max(k_park - 1, 0)], bus[7][min(k_park + 1, len(bus[7]) - 1)]
north, east = tt.lat[af] - tt.lat[b4], tt.lon[af] - tt.lon[b4]
size = math.hypot(north, east)
right = (round(tt.lat[park] - east / size * 0.0002, 6), round(tt.lon[park] + north / size * 0.0002, 6))
left = (round(tt.lat[park] + east / size * 0.0002, 6), round(tt.lon[park] - north / size * 0.0002, 6))
for side, want in ((right, right), (left, (tt.lat[park], tt.lon[park]))):
    call("/admin/api/map/station", {"id": "town park", "places": [{"label": "", "platform": "", "lat": side[0], "lon": side[1]}]}, cookie)
    assert tt.platform_for(park, "101", bus, k_park)[:2] == want, (side, want, tt.platform_for(park, "101", bus, k_park))
call("/admin/api/map/station", {"id": "town park", "places": []}, cookie)
call("/admin/api/map/station", {"id": "town park", "places": [{"label": "", "platform": "", "lat": left[0], "lon": left[1]},
                                                           {"label": "", "platform": "", "lat": right[0], "lon": right[1]}]}, cookie)
assert tt.platform_for(park, "101", bus, k_park) is None, "bays of a bus station: no right side to pick"
call("/admin/api/map/station", {"id": "town park", "places": []}, cookie)
# "Line ends at" in the editor: a trace into the stop ending exactly on one of its spots decides, before directions.
pin_spot = (round(tt.lat[park] - 0.0003, 6), round(tt.lon[park], 6))
call("/admin/api/map/station", {"id": "town park", "places": [two_ways, {"label": "", "platform": "7", "lat": pin_spot[0], "lon": pin_spot[1]}]}, cookie)
pin_key = f"{tt.station_ids[b4]}>town park"
timetable.edits["segments"][pin_key] = [list(pin_spot)]
assert tt.platform_for(park, "101", bus, k_park) == (pin_spot[0], pin_spot[1], "7"), tt.platform_for(park, "101", bus, k_park)
del timetable.edits["segments"][pin_key]
call("/admin/api/map/station", {"id": "town park", "places": []}, cookie)
# The trip stops at the spot its road trace goes to: a spot the snapping chose, so line and stop meet.
near_spot = (round(tt.lat[park] + 0.0004, 6), round(tt.lon[park], 6))
call("/admin/api/map/station", {"id": "town park", "places": [{"label": "", "platform": "", "lat": near_spot[0], "lon": near_spot[1]}]}, cookie)
key_ = f"{tt.station_ids[b4]}>town park"
timetable.edits["segments"][key_] = [[near_spot[0] + 0.0001, near_spot[1]]]
assert tt.platform_for(park, "101", bus, k_park)[:2] == near_spot
del timetable.edits["segments"][key_]
call("/admin/api/map/station", {"id": "town park", "places": []}, cookie)
call("/admin/api/map/station", {"id": "town park", "places": [], "zone": None}, cookie)
assert tt.platform_for(tt.by_id["town park"], "101") is None

# EM-0065: a stop named without its town ("Lipovec" in Zvolen) is found with the town of the stops around it.
near_town = next(s for s, n in enumerate(tt.names) if "," in n and tt.lat[s] is not None)
tt.names.append("Lipovec"); tt.words.append(timetable.fold("Lipovec"))
tt.lat.append(tt.lat[near_town] + 0.003); tt.lon.append(tt.lon[near_town])
tt.weight.append(0); tt.station_ids.append("lipovec-test")
tt.by_id["lipovec-test"] = len(tt.names) - 1
if hasattr(tt, "_search_words"):
    del tt._search_words
town = tt.names[near_town].split(",")[0].strip()
hits = [x["name"] for x in tt.search(town[:1] + " lipo")]
assert "Lipovec" in hits, (town, hits)
for lst in (tt.names, tt.words, tt.lat, tt.lon, tt.weight, tt.station_ids):
    lst.pop()
del tt.by_id["lipovec-test"], tt._search_words

# The map editor finds a line's ways by its number, also when none of its buses is on the road.
some_line = tt.trips[0][2]
ways = tt.line_routes(some_line)
assert ways and all(w["line"] == some_line for w in ways) and tt.trip_shape(ways[0]["tripId"]), ways
assert tt.line_routes("") == [] and tt.line_routes("no such") == []
# ... and by a stop ("zvolen as": the ways through it) or a connection ("a > b": the ways from one to the other).
g0 = next(i for i, x in enumerate(tt.trips) if x[0] not in (timetable.CITY_FEED, "extra") and len(x[7]) >= 2)
first_, last_ = tt.names[tt.trips[g0][7][0]], tt.names[tt.trips[g0][7][-1]]
assert any(w["from"] == first_ for w in tt.line_routes(first_)), tt.line_routes(first_)
assert any(w["from"] == first_ and w["to"] == last_ for w in tt.line_routes(f"{first_} > {last_}"))
assert not [w for w in tt.line_routes(f"{last_} > {first_}") if w["from"] == first_ and w["to"] == last_], "the other way round"
# A regional (GTFS) bus's stops can be set too, kept by its feed, line and stops; removing them restores its own.
gid = f"{tt.trips[g0][0]}:{tt.trips[g0][1]}:20260930"
gkey = tt.route_key(gid)
assert gkey and gkey.startswith("g:"), gkey
own_g = [tt.station_ids[s] for s in tt.trips[g0][7]]
extra_st = next(s for s in range(len(tt.names)) if s not in tt.trips[g0][7] and tt.lat[s] is not None)
timetable.edits["routes"][gkey] = own_g[:1] + [tt.station_ids[extra_st]] + own_g[1:]
before_g = tt.trips[g0]
tt.apply_routes()
assert tt.trips[g0][7][1] == extra_st and len(tt.trips[g0][7]) == len(before_g[7]) + 1 and tt.trips[g0][10][1] == ""
assert tt.trips[g0][9][0] < tt.trips[g0][9][1] <= tt.trips[g0][9][2] and tt.route_key(gid) == gkey, "same way after the edit"
assert tt.trip_shape(gid)["ownStops"]
del timetable.edits["routes"][gkey]
tt.apply_routes()
assert tt.trips[g0] == before_g, "back to the feed's own stops"
for junk in (">", "->", " - ", "a > ", "> b", "→→", "(", "[", "\\", "*", "a" * 300, "x > y > z"):
    assert isinstance(tt.line_routes(junk), list), junk

# The map's live channel: who has which stop open; another tab can't save that stop meanwhile.
class _Sock:
    def __init__(self): self.sent = []
    def send_json(self, m): self.sent.append(m)
room, a_, b_ = hub.MapRoom(), _Sock(), _Sock()
room.join(a_, "Ann", "tab-a"); room.join(b_, "Ben", "tab-b")
room.focus(a_, "town park", None)
assert room.busy("town park", "tab-b") == "Ann" and room.busy("town park", "tab-a") is None and room.busy("town as", "tab-b") is None
room.push({"type": "changed", "station": "town park"}, skip_tab="tab-a")
assert b_.sent and not a_.sent, "the one who saved isn't told"
room.leave(a_)
assert room.busy("town park", "tab-b") is None

# A city stop takes the timetable stop with the closest name, not the first that fits (Part.cesta vs Part.cesta ČS PL).
base_town = tt.names[near_town].split(",")[0].strip()
for name_ in (f"{base_town}, Part.cesta ČS PL", f"{base_town}, Part.cesta"):
    tt.names.append(name_); tt.words.append(timetable.fold(name_)); tt.lat.append(None); tt.lon.append(None)
    tt.weight.append(0); tt.station_ids.append(name_); tt.by_id[name_] = len(tt.names) - 1
got = tt._city_station("Partizánska cesta", tt.lat[near_town] + 0.002, tt.lon[near_town])
assert tt.names[got] == f"{base_town}, Part.cesta", tt.names[got]
for _ in range(2):
    del tt.by_id[tt.station_ids[-1]]
    for lst in (tt.names, tt.words, tt.lat, tt.lon, tt.weight, tt.station_ids):
        lst.pop()

# EM-0072: the drive to the stop, asked of the router once per pair in 10 min; bad positions refused.
calls = []
real_http = hub.http_json
hub.http_json = lambda url, *a, **k: calls.append(url) or {"routes": [{"duration": 412.4}]}
hub._drives.clear()
assert hub.timetable_query("drive", {"from": "48.7351,19.1592", "to": "48.7213,19.1203"}) == (200, {"seconds": 412})
assert hub.timetable_query("drive", {"from": "48.7354,19.1594", "to": "48.7213,19.1203"})[1] == {"seconds": 412} and len(calls) == 1, calls
assert hub.timetable_query("drive", {"from": "x", "to": "48.7,19.1"})[0] == 400
assert hub.timetable_query("drive", {"from": "40.0,19.1", "to": "48.7,19.1"})[0] == 400
hub.http_json = real_http

# EM-0064: the apps' maps get the road between stops given by position; a hop without a trace is straight ([]).
pa, pb = tt.by_id["town park"], tt.by_id["town as"]
timetable.edits["segments"]["town park>town as"] = [[48.71, 19.13]]
there = hub.timetable_query("path", {"stops": f"{tt.lat[pa]},{tt.lon[pa]};{tt.lat[pb]},{tt.lon[pb]};{tt.lat[pa]},{tt.lon[pa]}"})[1]["hops"]
assert there == [[[48.71, 19.13]], []], there
del timetable.edits["segments"]["town park>town as"]
assert hub.timetable_query("path", {"stops": "48.7,x"})[0] == 400

# EM-0053, "arrive by": only connections that are there in time, the ones leaving latest.
route = {"from": "v z p k", "to": "city station"}
plan_dep = hub.timetable_query("plan", dict(route, time="2026-09-30T05:50", n="3"))[1]["connections"]
assert plan_dep, "a connection to arrive by"
want = plan_dep[0]["arrival"] // 1000
by = hub.timetable_query("plan", dict(route, time=str(want), n="3", arrive="1"))[1]["connections"]
assert by and all(j["arrival"] <= want * 1000 for j in by), by
assert by[-1]["arrival"] == plan_dep[0]["arrival"], "the latest one arriving in time is listed"
early = hub.timetable_query("plan", dict(route, time=str(want - 60), n="3", arrive="1"))[1]["connections"]
assert all(j["arrival"] <= (want - 60) * 1000 for j in early), "a minute earlier, that one no longer fits"

# Snapping to the roads: the kerb way unless the plain one is clearly better (shorter, or no turning back).
real_route = hub.road
hub.road = lambda a, b, curb=True, via=(): ([[1, 1]], 1490.0) if curb else ([[2, 2]], 624.0)
assert hub.snap_segment((48.0, 19.0), (48.0, 19.006), pause=0)[1:-1] == [[2, 2]], "a kerb loop loses to the plain way"
hub.road = lambda a, b, curb=True, via=(): ([[1, 1]], 4825.0)
assert hub.snap_segment((48.56836, 19.12968), (48.5713, 19.118966), pause=0) is None, "a 4.8 km way for 0.9 km isn't kept"
hub.road = lambda a, b, curb=True, via=(): ([[1, 1]], 900.0) if curb else ([[2, 2]], 850.0)
assert hub.snap_segment((48.0, 19.0), (48.0, 19.006), pause=0)[1:-1] == [[1, 1]], "about the same: the kerb side stays"
# Checkpoints set in the editor: routed stretch by stretch through them; a stretch the router can only do the long way
# (a turn the map data doesn't allow) is drawn straight between the checkpoints.
hub.road = lambda a, b, curb=True, via=(): ([[round((a[0] + b[0]) / 2, 6), round((a[1] + b[1]) / 2, 6)]], timetable.meters(a[0], a[1], b[0], b[1]) * 1.1)
out = hub.snap_segment((48.0, 19.0), (48.0, 19.006), pause=0, via=[(48.001, 19.003)])
assert [48.001, 19.003] in out and len(out) == 5 and out[0] == [48.0, 19.0] and out[-1] == [48.0, 19.006], out
hub.road = lambda a, b, curb=True, via=(): ([[49.0, 20.0]], 5000.0)
assert hub.snap_segment((48.0, 19.0), (48.0, 19.006), pause=0, via=[(48.001, 19.003)])[1:-1] == [[48.001, 19.003]], "straight between checkpoints"
# Two spots of the next stop (Namestie slobody): the one reached without a loop round the block wins.
hub.road = lambda a, b, curb=True, via=(): ([[48.001, 19.003]], 400.0) if b == (48.0, 19.006) else ([[48.01, 19.0]], 1900.0)
assert hub.snap_segment((48.0, 19.0), (48.0004, 19.006), pause=0, other_ends=[(48.0, 19.006)]) == [[48.0, 19.0], [48.001, 19.003], [48.0, 19.006]], "ends at the spot it chose"
# Line 1 at Partizanska cesta: the kerb way first heads the wrong way and turns back (saved trace).
hook = [(48.73968, 19.16086), (48.73972, 19.16102), (48.73977, 19.16089), (48.7398, 19.16081), (48.73983, 19.16066), (48.73967, 19.15989),
        (48.73949, 19.15998), (48.73945, 19.15977), (48.73932, 19.15919), (48.73915, 19.15844)]
assert hub.u_turns(hook) == 1 and hub.u_turns(hook[3:]) == 0
hub.road = lambda a, b, curb=True, via=(): (hook[1:-1], 560.0) if curb else (hook[4:-1], 520.0)
assert hub.snap_segment(hook[0], hook[-1], pause=0)[1:-1] == hook[4:-1], "the way that turns back loses"
hub.road = real_route
# Valhalla's shape encoding (precision 6), and the way falls back to OSRM when Valhalla fails.
assert hub.polyline6("_p~iF~ps|U_ulLnnqC_mqNvxq`@") == [[3.85, -12.02], [4.07, -12.095], [4.3252, -12.6453]]
real_valhalla, real_osrm = hub.valhalla_route, hub.osrm_route
def _busy(a, b, curb=True, via=()):
    raise hub.ProviderError("busy")
hub.valhalla_route, hub.osrm_route = _busy, lambda a, b, curb=True, via=(): ([[3, 3]], 10.0)
assert hub.road((48, 19), (48, 19.01)) == ([[3, 3]], 10.0)
hub.valhalla_route, hub.osrm_route = real_valhalla, real_osrm

# sadzv: stops a trip lost (platforms that don't list it) come back from its route's whole run, timed as on it.
import sadzv as _sadzv  # noqa: E402
run = [(5277, 1000), (5266, 1060), (93, 1240), (5255, 1500), (388, 1740)]
assert _sadzv.fill_stops([[5277, 43000], [5266, 43060], [5255, 43320]], run) == \
    [[5277, 43000], [5266, 43060], [93, 43240], [5255, 43320], [388, 43560]], "middle and end stops filled"
assert _sadzv.fill_stops([[5266, 43060], [5277, 43120]], run) == [[5266, 43060], [5277, 43120]], "another order: unchanged"
# EM-0062: sadzv's own run leaves out stops 4-11; they come from a whole run of the line, timed within the gap.
short = [(388, 100, 1), (113, 220, 2), (5255, 340, 3), (5277, 1300, 6)]
whole = [(5255, 0, 1), (389, 60, 2), (4903, 120, 3), (5277, 600, 4)]
assert [x[:2] for x in _sadzv.bridge(short, [whole])] == [(388, 100), (113, 220), (5255, 340), (389, 436), (4903, 532), (5277, 1300)]
assert _sadzv.bridge(short, [whole[:2] + [(4903, 120, 5)] + whole[3:]]) == short, "a run with its own gap isn't used"
assert _sadzv.bridge(short[:3] + [(5277, 1300, 12)], [whole]) == short[:3] + [(5277, 1300, 12)], "8 missing, 2 known: not bridged"
# Line 6 towards Zeleznicna stanica: S.A.D. leaves stops out of every run; the way back has them, on its own platforms.
plat = lambda pid, stop_id, lat: (pid, f"S{stop_id}", lat, 19.1, "1", stop_id)  # noqa: E731
stop_of = {x[0]: x for x in [plat(1, 100, 48.700), plat(11, 100, 48.7001), plat(2, 101, 48.710), plat(3, 102, 48.720), plat(12, 102, 48.7201),
                             plat(4, 103, 48.730), plat(13, 103, 48.7301), plat(5, 104, 48.740), plat(6, 104, 48.7401)]}
way = [(1, 1000, 1), (2, 1060, 2), (5, 1360, 5)]            # A, B, (C and D left out), E
back = [(6, 0, 1), (13, 100, 2), (12, 200, 3), (11, 400, 4)]  # E, D, C, A on the other side of the road
assert _sadzv.bridge_back(way, [back], stop_of) == [(1, 1000, 1), (2, 1060, 2), (3, 1210, 0), (4, 1285, 0), (5, 1360, 5)]
assert _sadzv.bridge_back([(1, 1000, 1), (2, 1060, 2), (5, 1360, 6)], [back], stop_of)[2][0] == 5, "3 missing, 2 there: left as is"
# Line 1 to Zeleznicna stanica: both ends on the way back, more stops there than missing: all of them.
assert [x[0] for x in _sadzv.bridge_back([(1, 1000, 1), (5, 1360, 3)], [back], stop_of)] == [1, 3, 4, 5]
# A run that ends early (6017 at Nad Moskovskou, "to Zeleznicna stanica") goes on like the line's run that gets there.
early = [(1, 1000, 1), (2, 1060, 2)]
there = [(1, 0, 1), (2, 60, 2), (3, 210, 0), (4, 285, 0), (5, 360, 5)]
assert _sadzv.extend(early, [there], stop_of, "Town, S104") == early + [(3, 1210, 0), (4, 1285, 0), (5, 1360, 0)]
# ... also from a run that only passes the stop where it ended (same platform: the same way), and abbreviations count.
assert _sadzv.extend([(9, 900, 1), (2, 1060, 2)], [there], stop_of, "Town, S104")[-1] == (5, 1360, 0)
assert _sadzv.reaches([(5, 0, 1)], {5: (5, "B.B.Roosevelt.nem.", 0, 0, "", 1)}, "Banská Bystrica, Rooseveltova nemocnica")
assert _sadzv.extend(early, [there], stop_of, "Town, S101") == early, "already there"


# ---- city buses from sadzv live data: direct buses between stops the timetable doesn't serve
import sadzv  # noqa: E402
import time as _time  # noqa: E402
base = int(_time.time()) + 600
wall = lambda epoch: int(datetime.datetime.fromtimestamp(epoch, timetable.ZONE).replace(tzinfo=datetime.timezone.utc).timestamp())  # noqa: E731
fake = {
    "getAllPlatforms": [[1, "Námestie SNP", 4870000, 1910000, "1", 10], [2, "Lipovec, Potraviny", 4880000, 1930000, "1", 20],
                        [3, "Somewhere", 4890000, 1940000, "1", 30]],
    "getDeparturesOnPlatformWeb": {"departures": [
        {"lineNumberText": "9", "lineId": 91, "destinationName": "Sekier", "routeNumber": "9004", "tripNumber": 67, "real": 600},
        {"lineNumberText": "4", "lineId": 94, "destinationName": "Elsewhere", "routeNumber": "4001", "tripNumber": 5, "real": 900}]},
}
trips = {91: [(1, 1, base), (3, 2, base + 300), (2, 3, base + 480)], 94: [(1, 1, base + 300), (3, 2, base + 600)]}
def fake_call(path, body=None):
    if path == "getPlatformsDataWithHistory":
        return {"platforms": [{"platformId": p, "stopOrder": o, "stopName": f"S{p}", "departureTime": wall(at)} for p, o, at in trips[body["lineId"]]]}
    return fake[path]
sadzv._call, sadzv._cache = fake_call, {}
found = sadzv.journeys((48.70, 19.10), (48.80, 19.30), base - 60, 5, name_of=lambda lat, lon: "Zvolen, Nám.SNP" if lat < 48.75 else None)
assert len(found) == 1, found  # line 4 never reaches Lipovec
leg = found[0]["legs"][0]
assert (leg["line"], leg["from"]["name"], leg["to"]["name"], leg["to"]["seq"]) == ("9", "Zvolen, Nám.SNP", "Lipovec, Potraviny", 3), leg
assert leg["departure"] == base * 1000 and leg["arrival"] == (base + 480) * 1000 and leg["live"]["lineId"] == 91
assert sadzv.journeys((48.0, 17.0), (48.80, 19.30), base, 5) == [], "no sadzv stop at the start"
assert sadzv.locate("Zvolen, Nám.SNP", (48.69, 19.09)) == (48.70, 19.10), "a stop without a position is found by name in its town"
assert sadzv.locate("Zvolen, Nám.SNP", (47.0, 17.0)) is None, "not in another town"
timetable_leg = {"legs": [{"type": "trip", "line": "60109", "departure": base * 1000}], "departure": base * 1000, "arrival": 0}
assert hub.merge_live([timetable_leg], found) == found, "the live copy of the same bus replaces the timetable one"
# EM-0024: a live bus from another stop nearby keeps its own name, with a walk to it
here = {"id": "zvolen zapad", "name": "Zvolen, Západ", "lat": 48.5770, "lon": 19.1330}
gym = {"id": "sadzv-1", "name": "Zvolen, Gymnázium", "lat": 48.5786, "lon": 19.1352}
walk = hub.walk_leg(here, gym, base * 1000, arriving=True)
assert walk["to"]["name"] == "Zvolen, Gymnázium" and walk["arrival"] == base * 1000 and 120_000 < base * 1000 - walk["departure"] < 400_000, walk
assert hub.walk_leg(gym, dict(gym, id="x"), base * 1000, arriving=False) is None, "the same place: no walk"
assert hub.walk_leg(gym, dict(gym, lat=48.5790), base * 1000, arriving=False) is None, "another platform of the same stop: no walk"
walked = {"legs": [walk, dict(leg)], "departure": walk["departure"], "arrival": leg["arrival"]}
assert hub.merge_live([timetable_leg], [walked]) == [walked], "a live journey starting with a walk still replaces the timetable bus"

# Server page: request times by route (unknown routes not counted), app versions, backups keeping the newest 14
base = f"http://127.0.0.1:{server.server_port}"  # the sadzv test above reused the name
hub.BACKUP_DIR = __import__("pathlib").Path(tempfile.mkdtemp())
assert call("/admin/api/server")[0] == 401 and call("/admin/api/server/backup", {})[0] == 401, "admins only"
for i in range(16):
    (hub.BACKUP_DIR / f"hub-2000-01-{i + 1:02d}-000000.db").write_bytes(b"x")
status_, made = acall("/admin/api/server/backup", {}, cookie)
assert status_ == 200 and len(made["backups"]) == 14 and made["backups"][0]["name"] == made["name"], made
copy = sqlite3.connect(hub.BACKUP_DIR / made["name"])
assert copy.execute("SELECT COUNT(*) FROM users").fetchone()[0] >= 1, "a real copy of the database"
copy.close()
req = urllib.request.Request(f"{base}/api/v1/notices", headers={"X-App-Key": "dev-key", "X-App-Version": "android 9.9.9"})
urllib.request.urlopen(req).read()
call("/api/v1/no-such-route-" + "x" * 30)
health = admin_get("server")
assert health["version"] == hub.VERSION and health["dbSize"] > 0 and health["backups"][0]["name"] == made["name"], health.keys()
assert any(r["route"] == "app: notices" for r in health["routes"]) and not any("no-such" in r["route"] for r in health["routes"]), health["routes"]
assert {"version": "android 9.9.9", "requests": 1} in health["appVersions"], health["appVersions"]
print("ok server")

server.shutdown()
print("ok")

# ---- migration from 1.0 adds oidc_only; the recovery command clears it
path = os.path.join(tempfile.mkdtemp(), "old.db")
old = sqlite3.connect(path)
old.executescript(hub.SCHEMA.replace("PRAGMA journal_mode=WAL;", ""))
old.execute("INSERT INTO users(username, password_hash, created_at) VALUES ('adm', 'x', 0)")
old.commit()
old.close()
env = dict(os.environ, HUB_DB=path)
subprocess.run([sys.executable, "-c", "import hub; hub.init_db()"], env=env, check=True, cwd=HERE)
conn = sqlite3.connect(path)
conn.execute("UPDATE users SET oidc_only=1")
conn.commit()
out = subprocess.run([sys.executable, "hub.py", "allow-password", "adm"], env=env, capture_output=True, text=True, cwd=HERE).stdout
assert "allowed again" in out, out
assert conn.execute("SELECT oidc_only FROM users").fetchone()[0] == 0
conn.close()
print("ok migration")

# feeds: our CIS conversion first, transiq only for what it doesn't build (404); a copy from transiq
# is replaced by ours even when ours is older
import functools, http.server, time as _t  # noqa: E401,E402
served = {name: tempfile.mkdtemp() for name in ("cis", "transiq")}
gtfs(os.path.join(served["cis"], "a.zip"), [("1", "A", 48.7, 19.1), ("2", "B", 48.71, 19.11)], {"t": ("1", [("1", "08:00"), ("2", "08:10")])})
gtfs(os.path.join(served["transiq"], "b.zip"), [("1", "C", 48.7, 19.1), ("2", "D", 48.71, 19.11)], {"t": ("2", [("1", "08:00"), ("2", "08:10")])})
os.utime(os.path.join(served["cis"], "a.zip"), (1_000_000_000, 1_000_000_000))  # older than anything we have
ports = {}
for name, root in served.items():
    srv = http.server.ThreadingHTTPServer(("127.0.0.1", 0), functools.partial(http.server.SimpleHTTPRequestHandler, directory=root))
    srv.RequestHandlerClass.log_message = lambda *a: None
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    ports[name] = srv.server_address[1]
timetable.CIS_URL = f"http://127.0.0.1:{ports['cis']}/{{}}.zip"
timetable.FEED_URL = f"http://127.0.0.1:{ports['transiq']}/{{}}.zip"
local = __import__("pathlib").Path(tempfile.mkdtemp())
(local / "a.zip").write_bytes(b"old transiq copy")
(local / "a.src").write_text(timetable.FEED_URL.format("a"))
assert timetable.download(local, ["a", "b"]) == 2, timetable.downloads
assert "cis" in timetable.downloads["a"]["result"] and "transiq" in timetable.downloads["b"]["result"], timetable.downloads
assert zipfile.ZipFile(local / "a.zip").read("routes.txt").endswith(b",1,,3")
assert timetable.download(local, ["a", "b"]) == 0 and timetable.downloads["a"]["result"] == "unchanged", timetable.downloads
print("ok feeds")
