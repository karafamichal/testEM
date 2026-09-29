"""Run: python3 test_hub.py (uses a throwaway database)."""
import datetime
import http.client
import json
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
