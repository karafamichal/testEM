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
hub.road = lambda a, b, curb=True: ([[1, 1]], 1490.0) if curb else ([[2, 2]], 624.0)
assert hub.snap_segment((48.0, 19.0), (48.0, 19.006), pause=0) == [[2, 2]], "a kerb loop loses to the plain way"
hub.road = lambda a, b, curb=True: ([[1, 1]], 4825.0)
assert hub.snap_segment((48.56836, 19.12968), (48.5713, 19.118966), pause=0) is None, "a 4.8 km way for 0.9 km isn't kept"
hub.road = lambda a, b, curb=True: ([[1, 1]], 900.0) if curb else ([[2, 2]], 850.0)
assert hub.snap_segment((48.0, 19.0), (48.0, 19.006), pause=0) == [[1, 1]], "about the same: the kerb side stays"
# Two spots of the next stop (Namestie slobody): the one reached without a loop round the block wins.
hub.road = lambda a, b, curb=True: ([[48.001, 19.003]], 400.0) if b == (48.0, 19.006) else ([[48.01, 19.0]], 1900.0)
assert hub.snap_segment((48.0, 19.0), (48.0004, 19.006), pause=0, other_ends=[(48.0, 19.006)]) == [[48.001, 19.003]]
# Line 1 at Partizanska cesta: the kerb way first heads the wrong way and turns back (saved trace).
hook = [(48.73968, 19.16086), (48.73972, 19.16102), (48.73977, 19.16089), (48.7398, 19.16081), (48.73983, 19.16066), (48.73967, 19.15989),
        (48.73949, 19.15998), (48.73945, 19.15977), (48.73932, 19.15919), (48.73915, 19.15844)]
assert hub.u_turns(hook) == 1 and hub.u_turns(hook[3:]) == 0
hub.road = lambda a, b, curb=True: (hook[1:-1], 560.0) if curb else (hook[4:-1], 520.0)
assert hub.snap_segment(hook[0], hook[-1], pause=0) == hook[4:-1], "the way that turns back loses"
hub.road = real_route
# Valhalla's shape encoding (precision 6), and the way falls back to OSRM when Valhalla fails.
assert hub.polyline6("_p~iF~ps|U_ulLnnqC_mqNvxq`@") == [[3.85, -12.02], [4.07, -12.095], [4.3252, -12.6453]]
real_valhalla, real_osrm = hub.valhalla_route, hub.osrm_route
def _busy(a, b, curb=True):
    raise hub.ProviderError("busy")
hub.valhalla_route, hub.osrm_route = _busy, lambda a, b, curb=True: ([[3, 3]], 10.0)
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
