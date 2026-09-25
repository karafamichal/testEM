#!/usr/bin/env python3
"""
emhub: bug reports + community bus delays for testEM.

One stdlib-only process:
  /api/v1/*      public API used by the Android app and the web app (X-App-Key)
  /login, /auth  admin sign-in (local password or Authentik via OIDC)
  /dashboard     admin dashboard (bugs, community tracking, settings)
  /admin/api/*   JSON API behind the dashboard (session cookie)

Configuration comes from environment variables (see hub.env.example).
"""
import base64
import hashlib
import hmac
import html
import json
import os
import queue
import re
import secrets
import smtplib
import sqlite3
import ssl
import sys
import threading
import time
import traceback
import urllib.error
import urllib.parse
import urllib.request
import statistics
from datetime import date, datetime
from zoneinfo import ZoneInfo
from email.message import EmailMessage
from email.utils import formataddr, make_msgid
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

VERSION = "1.4.0"
HERE = Path(__file__).resolve().parent
ENV = os.environ.get

PUBLIC_URL = ENV("HUB_PUBLIC_URL", "http://localhost:8080").rstrip("/")
DB_PATH = ENV("HUB_DB", str(HERE / "hub.db"))
APP_KEYS = {k.strip() for k in ENV("HUB_APP_KEYS", "dev-key").split(",") if k.strip()}
# Email and Authentik settings are edited in the dashboard (Settings) and stored in the
# database. These environment variables are only the starting values.
SETTINGS = {  # key: (env var, default, is secret)
    "admin_email": ("HUB_ADMIN_EMAIL", "", False),
    "mail_from": ("HUB_MAIL_FROM", "testEM <noreply@karafa.network>", False),
    "mail_reply_to": ("HUB_MAIL_REPLY_TO", "", False),
    "smtp_host": ("HUB_SMTP_HOST", "localhost", False),
    "smtp_port": ("HUB_SMTP_PORT", "25", False),
    "smtp_user": ("HUB_SMTP_USER", "", False),
    "smtp_pass": ("HUB_SMTP_PASS", "", True),
    "smtp_security": ("HUB_SMTP_SECURITY", "none", False),  # none | starttls | ssl
    "oidc_issuer": ("HUB_OIDC_ISSUER", "", False),
    "oidc_client_id": ("HUB_OIDC_CLIENT_ID", "", False),
    "oidc_client_secret": ("HUB_OIDC_CLIENT_SECRET", "", True),
    "oidc_label": ("HUB_OIDC_LABEL", "Authentik", False),
}
INVITE_HOURS = 72
SECURE_COOKIES = PUBLIC_URL.startswith("https://")
SESSION_DAYS = 14

BUG_STATUSES = ["new", "triaged", "in_progress", "resolved", "closed"]
BUG_CATEGORIES = ["crash", "ticket", "departures", "planner", "tracking", "account", "other"]

# Community delay: how far back reports count, and how fast they fade.
COMMUNITY_WINDOW_S = 45 * 60
GPS_WINDOW_S = 3 * 60  # riders' phones on the bus: only this recent counts
REPORT_KINDS = ("arrival", "position", "gps")
COMMUNITY_HALF_LIFE_S = 10 * 60
MAX_ABS_DELAY_S = 3 * 60 * 60   # latest a bus can plausibly be
MAX_EARLY_S = 20 * 60            # earliest: a report further ahead of the timetable is a mistake
# History: earlier runs of the same trip on the same kind of day predict today's delay.
HISTORY_DAYS = 120
HISTORY_RUNS = 10
HISTORY_MIN_RUNS = 2
HISTORY_LATE_S = 120
LOCAL_ZONE = ZoneInfo("Europe/Bratislava")


# ------------------------------------------------------------------ database

SCHEMA = """
PRAGMA journal_mode=WAL;
CREATE TABLE IF NOT EXISTS users (
  id INTEGER PRIMARY KEY,
  username TEXT UNIQUE NOT NULL,
  password_hash TEXT NOT NULL,
  email TEXT NOT NULL DEFAULT '',
  oidc_sub TEXT UNIQUE,
  oidc_name TEXT NOT NULL DEFAULT '',
  created_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS sessions (
  token_hash TEXT PRIMARY KEY,
  user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  expires_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS oidc_states (
  state TEXT PRIMARY KEY,
  verifier TEXT NOT NULL,
  mode TEXT NOT NULL,
  user_id INTEGER,
  created_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS bugs (
  id INTEGER PRIMARY KEY,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  status TEXT NOT NULL DEFAULT 'new',
  category TEXT NOT NULL DEFAULT 'other',
  title TEXT NOT NULL,
  description TEXT NOT NULL,
  name TEXT NOT NULL DEFAULT '',
  email TEXT NOT NULL DEFAULT '',
  platform TEXT NOT NULL DEFAULT '',
  app_version TEXT NOT NULL DEFAULT '',
  device TEXT NOT NULL DEFAULT '',
  logs TEXT NOT NULL DEFAULT '',
  consent_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS bug_messages (
  id INTEGER PRIMARY KEY,
  bug_id INTEGER NOT NULL REFERENCES bugs(id) ON DELETE CASCADE,
  created_at INTEGER NOT NULL,
  kind TEXT NOT NULL,            -- reply | note | status | system
  author TEXT NOT NULL DEFAULT '',
  body TEXT NOT NULL,
  mail_status TEXT NOT NULL DEFAULT ''
);
CREATE TABLE IF NOT EXISTS community_reports (
  id INTEGER PRIMARY KEY,
  created_at INTEGER NOT NULL,
  trip_key TEXT NOT NULL,
  line TEXT NOT NULL,
  stop_index INTEGER NOT NULL,
  stop_name TEXT NOT NULL,
  scheduled_ms INTEGER NOT NULL,
  delay_s INTEGER NOT NULL,
  reporter TEXT NOT NULL,
  platform TEXT NOT NULL DEFAULT ''
);
CREATE INDEX IF NOT EXISTS community_trip ON community_reports(trip_key, created_at);
CREATE TABLE IF NOT EXISTS blocked_reporters (
  reporter TEXT PRIMARY KEY,
  created_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS settings (
  key TEXT PRIMARY KEY,
  value TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS email_templates (
  name TEXT PRIMARY KEY,
  html TEXT NOT NULL,
  updated_at INTEGER NOT NULL,
  updated_by TEXT NOT NULL DEFAULT ''
);
CREATE TABLE IF NOT EXISTS invites (
  token_hash TEXT PRIMARY KEY,
  user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  expires_at INTEGER NOT NULL
);
"""


def db():
    # Autocommit: handlers answer inside `with db()`, so every write must be visible
    # to the next request before the response goes out.
    # ponytail: no multi-statement transactions; fine for these small independent writes.
    conn = sqlite3.connect(DB_PATH, timeout=10, isolation_level=None)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys=ON")
    return conn


def init_db():
    with db() as conn:
        conn.executescript(SCHEMA)
        # 1.3.0: report kind (tap, arrival, rider phone GPS).
        if "kind" not in {r[1] for r in conn.execute("PRAGMA table_info(community_reports)")}:
            conn.execute("ALTER TABLE community_reports ADD COLUMN kind TEXT NOT NULL DEFAULT 'position'")
        # 1.1.0: per-admin "sign in with Authentik only".
        if "oidc_only" not in {r[1] for r in conn.execute("PRAGMA table_info(users)")}:
            conn.execute("ALTER TABLE users ADD COLUMN oidc_only INTEGER NOT NULL DEFAULT 0")
        if not conn.execute("SELECT 1 FROM users LIMIT 1").fetchone():
            username = ENV("HUB_BOOTSTRAP_USER", "karafamichal")
            password = ENV("HUB_BOOTSTRAP_PASSWORD", "")
            if not password:
                password = secrets.token_urlsafe(9)
                print(f"[emhub] created admin '{username}' with password: {password}", flush=True)
            conn.execute(
                "INSERT INTO users(username, password_hash, email, created_at) VALUES (?,?,?,?)",
                (username, hash_password(password), ENV("HUB_ADMIN_EMAIL", ""), now()),
            )


# ------------------------------------------------------------------ settings

_settings = {}
_settings_lock = threading.Lock()


def setting(key):
    """Dashboard value if one was saved, else the environment's starting value."""
    with _settings_lock:
        if not _settings:
            with db() as conn:
                _settings.update({r["key"]: r["value"] for r in conn.execute("SELECT key, value FROM settings")})
            _settings["_loaded"] = "1"
        if key in _settings:
            return _settings[key]
    env_var, default, _ = SETTINGS[key]
    return ENV(env_var, default)


def save_settings(conn, values):
    for key, value in values.items():
        conn.execute("INSERT INTO settings(key, value) VALUES (?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value",
                     (key, value))
    with _settings_lock:
        _settings.clear()
    _discovery.clear()  # the issuer may have changed


def smtp_label():
    return f"{setting('smtp_host')}:{setting('smtp_port')} ({setting('smtp_security')})"


def now():
    return int(time.time())


# ------------------------------------------------------------------ passwords & sessions

def hash_password(password):
    salt = secrets.token_bytes(16)
    digest = hashlib.scrypt(password.encode(), salt=salt, n=2**14, r=8, p=1)
    return "scrypt$" + base64.b64encode(salt).decode() + "$" + base64.b64encode(digest).decode()


def check_password(password, stored):
    try:
        _, salt, digest = stored.split("$")
        actual = hashlib.scrypt(password.encode(), salt=base64.b64decode(salt), n=2**14, r=8, p=1)
        return hmac.compare_digest(actual, base64.b64decode(digest))
    except ValueError:
        return False


def token_hash(token):
    return hashlib.sha256(token.encode()).hexdigest()


def create_session(conn, user_id):
    token = secrets.token_urlsafe(32)
    conn.execute(
        "INSERT INTO sessions(token_hash, user_id, expires_at) VALUES (?,?,?)",
        (token_hash(token), user_id, now() + SESSION_DAYS * 86400),
    )
    conn.execute("DELETE FROM sessions WHERE expires_at < ?", (now(),))
    return token


def session_cookie(token, max_age):
    parts = [f"hub_session={token}", "Path=/", "HttpOnly", "SameSite=Lax", f"Max-Age={max_age}"]
    if SECURE_COOKIES:
        parts.append("Secure")
    return "; ".join(parts)


class RateLimiter:
    """Fixed-window counter per key. ponytail: in-memory, resets on restart; fine for one process."""

    def __init__(self, limit, window_s):
        self.limit, self.window = limit, window_s
        self.hits = {}
        self.lock = threading.Lock()

    def allow(self, key):
        t = time.time()
        with self.lock:
            start, count = self.hits.get(key, (t, 0))
            if t - start > self.window:
                start, count = t, 0
            if count >= self.limit:
                return False
            self.hits[key] = (start, count + 1)
            if len(self.hits) > 50_000:
                self.hits = {k: v for k, v in self.hits.items() if t - v[0] <= self.window}
            return True


login_limiter = RateLimiter(10, 15 * 60)
bug_limiter = RateLimiter(10, 60 * 60)
report_limiter = RateLimiter(120, 60 * 60)
reporter_limiter = RateLimiter(30, 60 * 60)


# ------------------------------------------------------------------ email

# Editable in the dashboard. Placeholders: {{x}} is escaped text, {{{x}}} is prepared HTML.
TEMPLATES = {
    "admin_new_bug": ("New bug (to admins)", ["bug_id", "title", "description_html", "name", "email", "category", "platform", "app_version", "device", "logs_lines", "created", "dashboard_url"]),
    "user_ack": ("Report received (to reporter)", ["bug_id", "title", "description_html", "name", "platform", "app_version", "created"]),
    "user_reply": ("Admin reply (to reporter)", ["bug_id", "title", "name", "reply_html", "agent", "status_label"]),
    "user_status": ("Status changed (to reporter)", ["bug_id", "title", "name", "status_label", "created"]),
    "invite": ("Admin invitation", ["username", "invited_by", "invite_url", "expires_hours", "dashboard_url"]),
    "test": ("Test email", ["username", "smtp", "dashboard_url"]),
}


def default_template(name):
    return (HERE / "emails" / f"{name}.html").read_text(encoding="utf-8")


def template_source(name):
    with db() as conn:
        row = conn.execute("SELECT html FROM email_templates WHERE name=?", (name,)).fetchone()
    return row["html"] if row else default_template(name)


def render_template(name, values, source=None):
    """{{name}} placeholders, HTML-escaped. {{{name}}} inserts raw (pre-escaped) HTML."""
    text = source if source is not None else template_source(name)
    text = re.sub(r"\{\{\{(\w+)\}\}\}", lambda m: str(values.get(m.group(1), "")), text)
    return re.sub(r"\{\{(\w+)\}\}", lambda m: html.escape(str(values.get(m.group(1), ""))), text)


def html_to_text(markup):
    markup = re.sub(r"(?is)<(style|head)\b.*?</\1>", "", markup)
    markup = re.sub(r"(?i)<br\s*/?>|</p>|</tr>|</h\d>|</div>", "\n", markup)
    text = html.unescape(re.sub(r"<[^>]+>", "", markup))
    return re.sub(r"\n\s*\n\s*\n+", "\n\n", "\n".join(line.strip() for line in text.splitlines())).strip()


def paragraphs(text):
    """Plain text from a user or admin -> safe HTML paragraphs."""
    blocks = [b.strip() for b in re.split(r"\n\s*\n", text or "") if b.strip()]
    return "".join(
        '<p style="margin:0 0 14px 0;">' + html.escape(b).replace("\n", "<br>") + "</p>" for b in blocks
    )


mail_queue = queue.Queue()


def send_mail_now(to, subject, html_body, reply_to=""):
    msg = EmailMessage()
    msg["From"] = setting("mail_from")
    msg["To"] = to
    msg["Subject"] = subject
    msg["Message-ID"] = make_msgid(domain="karafa.network")
    if reply_to:
        msg["Reply-To"] = reply_to
    msg.set_content(html_to_text(html_body))
    msg.add_alternative(html_body, subtype="html")
    host, port, security = setting("smtp_host"), int(setting("smtp_port") or 25), setting("smtp_security")
    if security == "ssl":
        server = smtplib.SMTP_SSL(host, port, timeout=20, context=ssl.create_default_context())
    else:
        server = smtplib.SMTP(host, port, timeout=20)
    with server:
        if security == "starttls":
            server.starttls(context=ssl.create_default_context())
        if setting("smtp_user"):
            server.login(setting("smtp_user"), setting("smtp_pass"))
        server.send_message(msg)


def queue_mail(to, subject, html_body, message_id=None, reply_to=""):
    """Sends in the background; the result lands in bug_messages.mail_status when message_id is given."""
    if to:
        mail_queue.put((to, subject, html_body, message_id, reply_to))


def mail_worker():
    while True:
        to, subject, body, message_id, reply_to = mail_queue.get()
        status = "sent"
        try:
            send_mail_now(to, subject, body, reply_to)
        except Exception as error:  # noqa: BLE001 - any SMTP failure is reported, not fatal
            status = f"failed: {error}"[:300]
            print(f"[emhub] mail to {to} failed: {error}", file=sys.stderr, flush=True)
        if message_id:
            with db() as conn:
                conn.execute("UPDATE bug_messages SET mail_status=? WHERE id=?", (status, message_id))


def bug_code(bug_id):
    return f"EM-{bug_id:04d}"


def bug_values(bug, **extra):
    values = dict(bug)
    values.update(
        bug_id=bug_code(bug["id"]),
        name=bug["name"] or "there",
        created=time.strftime("%d.%m.%Y %H:%M", time.localtime(bug["created_at"])),
        status_label=bug["status"].replace("_", " "),
        dashboard_url=f"{PUBLIC_URL}/dashboard#bug/{bug['id']}",
        logs_lines=len(bug["logs"].splitlines()),
        description_html=paragraphs(bug["description"]),
    )
    values.update(extra)
    return values


def sample_values(name, user):
    """Realistic values for template previews and the test email."""
    bug = {"id": 42, "title": "QR code doesn't refresh after the phone wakes up", "description": "Every morning the code stays old until I reopen the app.\n\nPixel 8, Android 16.",
           "name": "Jana", "email": "jana@example.com", "status": "in_progress", "category": "ticket", "platform": "android",
           "app_version": "2.1.0", "device": "Google Pixel 8, Android 16", "logs": "a\nb\nc", "created_at": now()}
    values = bug_values(bug, reply_html=paragraphs("Thanks, Jana. A fix is in version 2.1.1.\n\nDoes it also happen on Wi-Fi?"),
                        agent=user["username"])
    values.update(username="newadmin" if name == "invite" else user["username"], invited_by=user["username"],
                  invite_url=PUBLIC_URL + "/invite?token=example", expires_hours=INVITE_HOURS, smtp=smtp_label())
    return values


# ------------------------------------------------------------------ community delays

def aggregate_delay(reports, now_s):
    """
    Community delay for one trip from its reports (dicts with delay_s, created_at, reporter).
    Latest report per reporter counts, weighted by age (half-life), after dropping outliers
    more than 5 min away from the median when there are 3+ reporters.
    """
    latest = {}
    for r in sorted(reports, key=lambda r: r["created_at"]):
        if now_s - r["created_at"] <= COMMUNITY_WINDOW_S:
            latest[r["reporter"]] = r
    rows = list(latest.values())
    if not rows:
        return None
    delays = sorted(r["delay_s"] for r in rows)
    median = delays[len(delays) // 2]
    if len(rows) >= 3:
        rows = [r for r in rows if abs(r["delay_s"] - median) <= 300] or rows
    weights = [0.5 ** ((now_s - r["created_at"]) / COMMUNITY_HALF_LIFE_S) for r in rows]
    delay = sum(w * r["delay_s"] for w, r in zip(weights, rows)) / sum(weights)
    newest = max(rows, key=lambda r: r["created_at"])
    return {
        "delaySeconds": int(round(delay)),
        "reporters": len(rows),
        "updatedAt": newest["created_at"] * 1000,
        "lastStopIndex": newest.get("stop_index"),
        "lastStopName": newest.get("stop_name"),
    }


def trip_run(trip_key):
    """
    (the same trip on any day, its date) from a community key, or None. Keys are
    "sadzv|lineId|trip|YYYY-MM-DD|line" or "line|first stop|first departure epoch ms".
    """
    parts = trip_key.split("|")
    if parts[0] == "sadzv" and len(parts) >= 5:
        return "|".join(parts[:3] + parts[4:]), parts[3]
    if len(parts) >= 3 and parts[-1].isdigit():
        start = datetime.fromtimestamp(int(parts[-1]) / 1000, LOCAL_ZONE)
        return "|".join(parts[:-1]) + start.strftime("|%H:%M"), start.date().isoformat()
    return None


def day_type(day):
    # ponytail: public holidays count as workdays; add a holiday list if their runs skew predictions.
    weekday = date.fromisoformat(day).weekday()
    return "saturday" if weekday == 5 else "sunday" if weekday == 6 else "workday"


def trip_history(conn, trip_key):
    """How late this trip usually is: its last runs on the same kind of day, today's run left out."""
    run = trip_run(trip_key)
    if not run:
        return None
    trip, day = run
    kind = day_type(day)
    parts = trip_key.split("|")
    prefix = "|".join(parts[:3] if parts[0] == "sadzv" else parts[:-1]) + "|%"
    runs = {}
    for key, delay_s in conn.execute(
        """SELECT trip_key, delay_s FROM community_reports WHERE trip_key LIKE ? AND created_at>=?
           AND reporter NOT IN (SELECT reporter FROM blocked_reporters)""",
        (prefix, now() - HISTORY_DAYS * 86400),
    ):
        other = trip_run(key)
        if other and other[0] == trip and other[1] != day and day_type(other[1]) == kind:
            runs.setdefault(other[1], []).append(delay_s)
    recent = [statistics.median(delays) for _, delays in sorted(runs.items(), reverse=True)[:HISTORY_RUNS]]
    if len(recent) < HISTORY_MIN_RUNS:
        return None
    return {
        "delaySeconds": int(statistics.median(recent)),
        "runs": len(recent),
        "lateRuns": sum(d >= HISTORY_LATE_S for d in recent),
        "dayType": kind,
    }


def trip_delay(conn, trip_key):
    """Riders' phones on the bus (last 3 min) beat everything; otherwise all reports (45 min)."""
    rows = [dict(r) for r in conn.execute(
        """SELECT delay_s, created_at, reporter, stop_index, stop_name, kind FROM community_reports
           WHERE trip_key=? AND created_at>=? AND reporter NOT IN (SELECT reporter FROM blocked_reporters)""",
        (trip_key, now() - COMMUNITY_WINDOW_S),
    )]
    gps = [r for r in rows if r["kind"] == "gps" and now() - r["created_at"] <= GPS_WINDOW_S]
    result = aggregate_delay(gps or rows, now())
    if result:
        result["source"] = "gps" if gps else "riders"
    return result


# ------------------------------------------------------------------ OIDC (Authentik)

_discovery = {}


def oidc_enabled():
    return bool(setting("oidc_issuer") and setting("oidc_client_id"))


WELL_KNOWN = "/.well-known/openid-configuration"


def normalize_issuer(value):
    """Accept the issuer or the full "OpenID Configuration URL" Authentik shows."""
    value = value.strip()
    if value.endswith(WELL_KNOWN):
        value = value[: -len(WELL_KNOWN)]
    return value.rstrip("/") + "/" if value else ""


class ProviderError(Exception):
    """Authentik (or the proxy in front of it) answered with something unusable."""


def http_json(url, data=None, headers=None):
    """Outgoing JSON request. Sends our own User-Agent: Cloudflare in front of Authentik
    answers 403 to Python's default "Python-urllib"."""
    request = urllib.request.Request(url, data=data, headers={
        "User-Agent": f"emhub/{VERSION} (+{PUBLIC_URL})", "Accept": "application/json", **(headers or {})})
    try:
        with urllib.request.urlopen(request, timeout=10) as res:
            return json.load(res)
    except urllib.error.HTTPError as error:
        detail = error.read(300).decode("utf-8", "replace").strip()
        raise ProviderError(f"{url} answered HTTP {error.code}" + (f": {detail}" if detail else "")) from error
    except urllib.error.URLError as error:
        raise ProviderError(f"Couldn't reach {url}: {error.reason}") from error
    except (json.JSONDecodeError, UnicodeDecodeError) as error:
        raise ProviderError(f"{url} didn't return JSON") from error


def oidc_config():
    if not _discovery:
        config = http_json(normalize_issuer(setting("oidc_issuer")).rstrip("/") + WELL_KNOWN)
        missing = [k for k in ("authorization_endpoint", "token_endpoint", "userinfo_endpoint") if not config.get(k)]
        if missing:
            raise ProviderError("The OpenID configuration has no " + ", ".join(missing))
        _discovery.update(config)
    return _discovery


def b64url(raw):
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode()


def oidc_redirect_uri():
    return PUBLIC_URL + "/auth/oidc/callback"


def oidc_exchange(code, verifier):
    """Code -> tokens -> userinfo. Userinfo comes straight from the provider over TLS,
    so the id_token signature does not need checking here (OIDC Core 3.1.3.7)."""
    config = oidc_config()
    form = urllib.parse.urlencode({
        "grant_type": "authorization_code",
        "code": code,
        "redirect_uri": oidc_redirect_uri(),
        "client_id": setting("oidc_client_id"),
        "client_secret": setting("oidc_client_secret"),
        "code_verifier": verifier,
    }).encode()
    tokens = http_json(config["token_endpoint"], data=form)
    if not tokens.get("access_token"):
        raise ProviderError("The token response had no access token")
    info = http_json(config["userinfo_endpoint"], headers={"Authorization": "Bearer " + tokens["access_token"]})
    if not info.get("sub"):
        raise ProviderError("The userinfo response had no user id (sub); add the openid scope to the provider")
    return info


# ------------------------------------------------------------------ HTTP

def clean(value, limit):
    return str(value or "").strip()[:limit]


EMAIL_RE = re.compile(r"^[^@\s]+@[^@\s]+\.[^@\s]+$")


class Handler(BaseHTTPRequestHandler):
    server_version = "emhub/" + VERSION
    protocol_version = "HTTP/1.1"
    timeout = 60  # idle keep-alive connections don't hold a thread forever

    # ---- plumbing

    def log_message(self, fmt, *args):
        if fmt.startswith("Request timed out"):
            return  # idle keep-alive connection closed by our timeout; normal
        print(f"[emhub] {self.client_ip()} {fmt % args}", flush=True)

    def client_ip(self):
        # cloudflared sets CF-Connecting-IP; only trust it when the hop itself is local/LAN.
        direct = self.client_address[0]
        forwarded = self.headers.get("CF-Connecting-IP") or self.headers.get("X-Forwarded-For", "").split(",")[0].strip()
        if forwarded and (direct.startswith(("127.", "10.", "192.168.", "172.")) or direct == "::1"):
            return forwarded
        return direct

    def send(self, status, body=b"", content_type="application/json", headers=None):
        if isinstance(body, (dict, list)):
            body = json.dumps(body).encode()
        elif isinstance(body, str):
            body = body.encode()
        self.send_response(status)
        self.send_header("Content-Type", content_type + ("; charset=utf-8" if "json" in content_type or "html" in content_type else ""))
        self.send_header("Content-Length", str(len(body)))
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Referrer-Policy", "same-origin")
        if content_type == "text/html":
            self.send_header("X-Frame-Options", "DENY")
            self.send_header("Cache-Control", "no-store")
        for key, value in (headers or {}).items():
            if isinstance(value, list):
                for v in value:
                    self.send_header(key, v)
            else:
                self.send_header(key, value)
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def redirect(self, location, cookies=None):
        self.send(HTTPStatus.SEE_OTHER, b"", "text/plain", {"Location": location, "Set-Cookie": cookies or []})

    def error(self, status, message, cors=False):
        self.send(status, {"error": message}, headers=self.cors() if cors else None)

    MAX_BODY = 2_000_000

    def read_body(self, limit=1_000_000):
        """The body dispatch() already read (every request's body is always consumed)."""
        if len(self._raw) > limit:
            raise ValueError("Request too large")
        return self._raw

    def json_body(self, limit=1_000_000):
        raw = self.read_body(limit)
        data = json.loads(raw or b"{}")
        if not isinstance(data, dict):
            raise ValueError("Expected a JSON object")
        return data

    def cors(self):
        return {
            "Access-Control-Allow-Origin": "*",
            "Access-Control-Allow-Headers": "Content-Type, X-App-Key",
            "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
            "Access-Control-Max-Age": "86400",
        }

    def cookies(self):
        jar = {}
        for part in (self.headers.get("Cookie") or "").split(";"):
            if "=" in part:
                k, v = part.strip().split("=", 1)
                jar[k] = v
        return jar

    def current_user(self):
        token = self.cookies().get("hub_session")
        if not token:
            return None
        with db() as conn:
            row = conn.execute(
                """SELECT users.* FROM sessions JOIN users ON users.id=sessions.user_id
                   WHERE token_hash=? AND expires_at>?""",
                (token_hash(token), now()),
            ).fetchone()
        return dict(row) if row else None

    def static(self, name, content_type="text/html"):
        self.send(200, (HERE / "static" / name).read_bytes(), content_type)

    # ---- routing

    def do_OPTIONS(self):
        self.send(204, b"", "text/plain", self.cors())

    def do_HEAD(self):
        self.do_GET()

    def do_GET(self):
        self.dispatch("GET")

    def do_POST(self):
        self.dispatch("POST")

    def dispatch(self, method):
        url = urllib.parse.urlsplit(self.path)
        path, query = url.path, dict(urllib.parse.parse_qsl(url.query))
        # Read the whole body up front, even when a handler ignores it: on a keep-alive
        # connection unread bytes would become the start of the next request ("{}GET").
        length = int(self.headers.get("Content-Length") or 0)
        if length > self.MAX_BODY:
            self.close_connection = True
            return self.error(413, "Request too large", cors=path.startswith("/api/"))
        self._raw = self.rfile.read(length) if length > 0 else b""
        try:
            if path.startswith("/api/v1/"):
                return self.public_api(method, path[len("/api/v1/"):], query)
            if path.startswith("/admin/api/"):
                return self.admin_api(method, path[len("/admin/api/"):], query)
            return self.pages(method, path, query)
        except (ValueError, json.JSONDecodeError) as error:
            self.error(400, str(error), cors=path.startswith("/api/"))
        except Exception:  # noqa: BLE001 - keep serving; log the trace
            traceback.print_exc()
            self.error(500, "Server error", cors=path.startswith("/api/"))

    # ---- pages & auth

    def pages(self, method, path, query):
        if method == "GET" and path == "/":
            return self.redirect("/dashboard" if self.current_user() else "/login")
        if method == "GET" and path == "/login":
            return self.static("login.html")
        if method == "GET" and path == "/dashboard":
            return self.static("dashboard.html") if self.current_user() else self.redirect("/login")
        if method == "GET" and path == "/brand.css":
            return self.static("brand.css", "text/css")
        if method == "GET" and path == "/favicon.svg":
            return self.static("favicon.svg", "image/svg+xml")
        if method == "GET" and path == "/auth/config":
            return self.send(200, {"oidc": oidc_enabled(), "oidcLabel": setting("oidc_label")})
        if method == "POST" and path == "/auth/login":
            return self.password_login()
        if method == "POST" and path == "/auth/logout":
            token = self.cookies().get("hub_session")
            if token:
                with db() as conn:
                    conn.execute("DELETE FROM sessions WHERE token_hash=?", (token_hash(token),))
            return self.redirect("/login", [session_cookie("", 0)])
        if method == "GET" and path == "/invite":
            return self.static("invite.html")
        if method == "GET" and path == "/auth/invite/check":
            with db() as conn:
                row = self.invite_row(conn, query.get("token", ""))
            return self.send(200, {"ok": bool(row), "username": row["username"] if row else ""})
        if method == "POST" and path == "/auth/invite":
            return self.accept_invite()
        if method == "GET" and path == "/auth/oidc/start":
            return self.oidc_start(query.get("mode", "login"))
        if method == "GET" and path == "/auth/oidc/callback":
            return self.oidc_callback(query)
        self.error(404, "Not found")

    def invite_row(self, conn, token):
        if not token:
            return None
        return conn.execute(
            """SELECT users.id, users.username FROM invites JOIN users ON users.id=invites.user_id
               WHERE token_hash=? AND expires_at>?""",
            (token_hash(token), now()),
        ).fetchone()

    def accept_invite(self):
        form = dict(urllib.parse.parse_qsl(self.read_body(10_000).decode()))
        token = form.get("token", "")
        back = "/invite?token=" + urllib.parse.quote(token)
        if not login_limiter.allow(self.client_ip()):
            return self.redirect(back + "&error=rate")
        password = form.get("password", "")
        if len(password) < 10:
            return self.redirect(back + "&error=short")
        if password != form.get("confirm", ""):
            return self.redirect(back + "&error=mismatch")
        with db() as conn:
            row = self.invite_row(conn, token)
            if not row:
                return self.redirect(back + "&error=expired")
            conn.execute("UPDATE users SET password_hash=? WHERE id=?", (hash_password(password), row["id"]))
            conn.execute("DELETE FROM invites WHERE user_id=?", (row["id"],))  # one-time link
            session = create_session(conn, row["id"])
        self.redirect("/dashboard#settings", [session_cookie(session, SESSION_DAYS * 86400)])

    def send_invite(self, conn, invited, inviter):
        """New one-time link (older ones stop working). Returns (link, error or None)."""
        token = secrets.token_urlsafe(32)
        conn.execute("DELETE FROM invites WHERE user_id=?", (invited["id"],))
        conn.execute("INSERT INTO invites(token_hash, user_id, expires_at) VALUES (?,?,?)",
                     (token_hash(token), invited["id"], now() + INVITE_HOURS * 3600))
        link = f"{PUBLIC_URL}/invite?token={token}"
        values = {"username": invited["username"], "invited_by": inviter["username"], "invite_url": link,
                  "expires_hours": INVITE_HOURS, "dashboard_url": PUBLIC_URL + "/dashboard"}
        try:
            send_mail_now(invited["email"], "You're invited to emhub", render_template("invite", values),
                          inviter["email"] or setting("mail_reply_to"))
            return link, None
        except Exception as error:  # noqa: BLE001 - the admin gets the link to pass on instead
            return link, str(error)

    def password_login(self):
        form = dict(urllib.parse.parse_qsl(self.read_body(10_000).decode()))
        if not login_limiter.allow(self.client_ip()):
            return self.redirect("/login?error=rate")
        with db() as conn:
            user = conn.execute("SELECT * FROM users WHERE username=?", (form.get("username", "").strip(),)).fetchone()
            if not user or not check_password(form.get("password", ""), user["password_hash"]):
                return self.redirect("/login?error=invalid")
            if user["oidc_only"]:
                return self.redirect("/login?error=oidc_only")
            token = create_session(conn, user["id"])
        self.redirect("/dashboard", [session_cookie(token, SESSION_DAYS * 86400)])

    def oidc_start(self, mode):
        if not oidc_enabled():
            return self.redirect("/login?error=oidc_off")
        user = self.current_user()
        if mode == "link" and not user:
            return self.redirect("/login")
        state, verifier = secrets.token_urlsafe(24), secrets.token_urlsafe(48)
        with db() as conn:
            conn.execute("DELETE FROM oidc_states WHERE created_at<?", (now() - 600,))
            conn.execute(
                "INSERT INTO oidc_states(state, verifier, mode, user_id, created_at) VALUES (?,?,?,?,?)",
                (state, verifier, "link" if mode == "link" else "login", user["id"] if user else None, now()),
            )
        params = urllib.parse.urlencode({
            "response_type": "code",
            "client_id": setting("oidc_client_id"),
            "redirect_uri": oidc_redirect_uri(),
            "scope": "openid email profile",
            "state": state,
            "code_challenge": b64url(hashlib.sha256(verifier.encode()).digest()),
            "code_challenge_method": "S256",
        })
        try:
            endpoint = oidc_config()["authorization_endpoint"]
        except ProviderError as error:
            return self.oidc_failed(mode, error)
        self.redirect(endpoint + "?" + params)

    def oidc_failed(self, mode, error):
        """Back to where the user came from, with the reason."""
        _discovery.clear()  # retry discovery next time
        print(f"[emhub] OIDC {mode} failed: {error}", file=sys.stderr, flush=True)
        if mode == "link" and self.current_user():
            return self.redirect("/dashboard#settings/oidc-error:" + urllib.parse.quote(str(error)[:300], safe=""))
        return self.redirect("/login?error=oidc_failed")

    def oidc_callback(self, query):
        with db() as conn:
            row = conn.execute(
                "DELETE FROM oidc_states WHERE state=? AND created_at>=? RETURNING *",
                (query.get("state", ""), now() - 600),
            ).fetchone()
        if not row:
            return self.redirect("/login?error=oidc_state")
        if "error" in query or "code" not in query:
            # e.g. access_denied: the user declined, or the app isn't allowed for them in Authentik
            reason = query.get("error_description") or query.get("error") or "no code returned"
            return self.oidc_failed(row["mode"], f"Authentik said: {reason}")
        try:
            info = oidc_exchange(query["code"], row["verifier"])
        except ProviderError as error:
            return self.oidc_failed(row["mode"], error)
        sub = info.get("sub")
        label = info.get("preferred_username") or info.get("email") or sub
        with db() as conn:
            if row["mode"] == "link":
                current = self.current_user()
                if not current or current["id"] != row["user_id"]:
                    return self.redirect("/login")
                taken = conn.execute("SELECT id FROM users WHERE oidc_sub=? AND id<>?", (sub, current["id"])).fetchone()
                if taken:
                    return self.redirect("/dashboard#settings/link-taken")
                conn.execute("UPDATE users SET oidc_sub=?, oidc_name=? WHERE id=?", (sub, label, current["id"]))
                return self.redirect("/dashboard#settings/linked")
            user = conn.execute("SELECT * FROM users WHERE oidc_sub=?", (sub,)).fetchone()
            if not user:
                return self.redirect("/login?error=not_linked")
            conn.execute("UPDATE users SET oidc_name=? WHERE id=?", (label, user["id"]))
            token = create_session(conn, user["id"])
        self.redirect("/dashboard", [session_cookie(token, SESSION_DAYS * 86400)])

    # ---- public API (app + web)

    def public_api(self, method, route, query):
        if route == "health":
            return self.send(200, {"ok": True, "version": VERSION}, headers=self.cors())
        if self.headers.get("X-App-Key", "") not in APP_KEYS:
            return self.error(401, "Missing or wrong app key", cors=True)
        if method == "POST" and route == "bugs":
            return self.submit_bug()
        if method == "POST" and route == "community/reports":
            return self.submit_report()
        if method == "GET" and route == "community/delay":
            key = clean(query.get("trip"), 300)
            if not key:
                return self.error(400, "trip is required", cors=True)
            with db() as conn:
                return self.send(200, {"trip": key, "delay": trip_delay(conn, key), "history": trip_history(conn, key)},
                                 headers=self.cors())
        self.error(404, "Not found", cors=True)

    def submit_bug(self):
        if not bug_limiter.allow(self.client_ip()):
            return self.error(429, "Too many reports, try again later", cors=True)
        data = self.json_body(limit=1_200_000)
        if data.get("consent") is not True:
            return self.error(400, "Consent is required", cors=True)
        description = clean(data.get("description"), 10_000)
        logs = clean(data.get("logs"), 1_000_000)
        email = clean(data.get("email"), 200)
        # Only the logs are essential; name, email, summary and text are all optional.
        if not description and not logs:
            return self.error(400, "Describe the problem or attach logs", cors=True)
        title = clean(data.get("title"), 140) or (description.splitlines()[0][:80] if description else "")
        title = title or f"Logs from {clean(data.get('platform'), 20) or 'app'}"
        if email and not EMAIL_RE.match(email):
            return self.error(400, "Email address looks wrong", cors=True)
        category = data.get("category") if data.get("category") in BUG_CATEGORIES else "other"
        t = now()
        with db() as conn:
            cur = conn.execute(
                """INSERT INTO bugs(created_at, updated_at, status, category, title, description, name, email,
                   platform, app_version, device, logs, consent_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                (t, t, "new", category, title, description, clean(data.get("name"), 120), email,
                 clean(data.get("platform"), 20), clean(data.get("appVersion"), 40), clean(data.get("device"), 200),
                 logs, t),
            )
            bug = conn.execute("SELECT * FROM bugs WHERE id=?", (cur.lastrowid,)).fetchone()
            conn.execute(
                "INSERT INTO bug_messages(bug_id, created_at, kind, author, body) VALUES (?,?,?,?,?)",
                (bug["id"], t, "system", "", "Report received" + (", confirmation sent to reporter" if email else "")),
            )
        code = bug_code(bug["id"])
        admin_to = setting("admin_email") or ",".join(
            r["email"] for r in db().execute("SELECT email FROM users WHERE email<>''")
        )
        queue_mail(admin_to, f"[{code}] New bug: {title}", render_template("admin_new_bug", bug_values(bug)))
        if email:
            queue_mail(email, f"[{code}] We got your report", render_template("user_ack", bug_values(bug)),
                       reply_to=setting("mail_reply_to"))
        self.send(201, {"id": bug["id"], "code": code}, headers=self.cors())

    def submit_report(self):
        data = self.json_body(limit=10_000)
        reporter = clean(data.get("reporter"), 64)
        trip_key = clean(data.get("tripKey"), 300)
        if not reporter or not trip_key:
            return self.error(400, "tripKey and reporter are required", cors=True)
        if not (report_limiter.allow(self.client_ip()) and reporter_limiter.allow(reporter)):
            return self.error(429, "Too many reports", cors=True)
        scheduled_ms = int(data.get("scheduledMs") or 0)
        # Server clock decides "now", so a phone with a wrong clock cannot skew the delay.
        delay = now() - scheduled_ms // 1000
        if data.get("kind") == "arrival":
            delay = max(0, delay)  # at the stop before departure time: it waits, so on time
        if delay > MAX_ABS_DELAY_S or delay < -MAX_EARLY_S:
            return self.error(400, "That stop time is too far from now", cors=True)
        kind = data.get("kind") if data.get("kind") in REPORT_KINDS else "position"
        with db() as conn:
            blocked = conn.execute("SELECT 1 FROM blocked_reporters WHERE reporter=?", (reporter,)).fetchone()
            if not blocked:
                conn.execute(
                    """INSERT INTO community_reports(created_at, trip_key, line, stop_index, stop_name, scheduled_ms,
                       delay_s, reporter, platform, kind) VALUES (?,?,?,?,?,?,?,?,?,?)""",
                    (now(), trip_key, clean(data.get("line"), 40), int(data.get("stopIndex") or 0),
                     clean(data.get("stopName"), 120), scheduled_ms, delay, reporter, clean(data.get("platform"), 20), kind),
                )
            # A blocked reporter gets the same answer, so blocking is not obvious to them.
            result = trip_delay(conn, trip_key)
        self.send(201, {"delay": result, "yourDelaySeconds": delay}, headers=self.cors())

    # ---- admin API

    def admin_api(self, method, route, query):
        user = self.current_user()
        if not user:
            return self.error(401, "Sign in first")
        if method == "POST":
            # Cross-site forms cannot send JSON with a custom content type without a preflight.
            if "application/json" not in (self.headers.get("Content-Type") or ""):
                return self.error(415, "JSON only")
            origin = self.headers.get("Origin")
            if origin and origin.rstrip("/") != PUBLIC_URL:
                return self.error(403, "Wrong origin")
        parts = route.strip("/").split("/")
        with db() as conn:
            if method == "GET" and route == "me":
                return self.send(200, {
                    "username": user["username"], "email": user["email"],
                    "oidcLinked": bool(user["oidc_sub"]), "oidcName": user["oidc_name"], "oidcOnly": bool(user["oidc_only"]),
                    "oidcEnabled": oidc_enabled(), "oidcLabel": setting("oidc_label"),
                    "smtp": smtp_label(), "adminEmail": setting("admin_email"),
                    "publicUrl": PUBLIC_URL,
                })
            if method == "GET" and route == "stats":
                counts = {r["status"]: r["n"] for r in conn.execute("SELECT status, COUNT(*) n FROM bugs GROUP BY status")}
                active = conn.execute(
                    "SELECT COUNT(DISTINCT trip_key) FROM community_reports WHERE created_at>=?", (now() - COMMUNITY_WINDOW_S,)
                ).fetchone()[0]
                reports_today = conn.execute(
                    "SELECT COUNT(*) FROM community_reports WHERE created_at>=?", (now() - 86400,)
                ).fetchone()[0]
                return self.send(200, {"bugs": counts, "activeTrips": active, "reportsToday": reports_today})
            if method == "GET" and route == "bugs":
                sql, args = "SELECT id, created_at, updated_at, status, category, title, name, email, platform FROM bugs", []
                where = []
                if query.get("status") in BUG_STATUSES:
                    where.append("status=?")
                    args.append(query["status"])
                elif query.get("status") == "open":
                    where.append("status NOT IN ('resolved','closed')")
                if query.get("q"):
                    where.append("(title LIKE ? OR description LIKE ? OR email LIKE ? OR name LIKE ?)")
                    args += ["%" + query["q"] + "%"] * 4
                if where:
                    sql += " WHERE " + " AND ".join(where)
                rows = conn.execute(sql + " ORDER BY updated_at DESC LIMIT 300", args).fetchall()
                return self.send(200, [dict(r, code=bug_code(r["id"])) for r in rows])
            if parts[0] == "bugs" and len(parts) >= 2 and parts[1].isdigit():
                return self.admin_bug(conn, user, method, int(parts[1]), parts[2] if len(parts) > 2 else "")
            if method == "GET" and route == "community/trips":
                rows = conn.execute(
                    """SELECT trip_key, line, MAX(created_at) last, COUNT(*) n FROM community_reports
                       WHERE created_at>=? GROUP BY trip_key ORDER BY last DESC LIMIT 200""",
                    (now() - 6 * 3600,),
                ).fetchall()
                trips = [dict(r, delay=trip_delay(conn, r["trip_key"])) for r in rows]
                return self.send(200, trips)
            if method == "GET" and route == "community/reports":
                sql = "SELECT * FROM community_reports"
                args = []
                if query.get("trip"):
                    sql += " WHERE trip_key=?"
                    args.append(query["trip"])
                rows = conn.execute(sql + " ORDER BY created_at DESC LIMIT 300", args).fetchall()
                blocked = {r[0] for r in conn.execute("SELECT reporter FROM blocked_reporters")}
                return self.send(200, [dict(r, blocked=r["reporter"] in blocked) for r in rows])
            if method == "POST" and route == "community/delete":
                conn.execute("DELETE FROM community_reports WHERE id=?", (int(self.json_body()["id"]),))
                return self.send(200, {"ok": True})
            if method == "POST" and route == "community/block":
                data = self.json_body()
                reporter = clean(data.get("reporter"), 64)
                if data.get("unblock"):
                    conn.execute("DELETE FROM blocked_reporters WHERE reporter=?", (reporter,))
                else:
                    conn.execute("INSERT OR IGNORE INTO blocked_reporters VALUES (?,?)", (reporter, now()))
                return self.send(200, {"ok": True})
            if method == "POST" and route == "account/password":
                data = self.json_body()
                if not check_password(data.get("current", ""), user["password_hash"]):
                    return self.error(400, "Current password is wrong")
                if len(data.get("new", "")) < 10:
                    return self.error(400, "Use at least 10 characters")
                conn.execute("UPDATE users SET password_hash=? WHERE id=?", (hash_password(data["new"]), user["id"]))
                conn.execute("DELETE FROM sessions WHERE user_id=? AND token_hash<>?",
                             (user["id"], token_hash(self.cookies()["hub_session"])))
                return self.send(200, {"ok": True})
            if method == "POST" and route == "account/email":
                email = clean(self.json_body().get("email"), 200)
                if email and not EMAIL_RE.match(email):
                    return self.error(400, "Email address looks wrong")
                conn.execute("UPDATE users SET email=? WHERE id=?", (email, user["id"]))
                return self.send(200, {"ok": True})
            if method == "POST" and route == "account/unlink":
                if user["oidc_only"]:
                    return self.error(400, "Turn off \"Authentik only\" first, or you could not sign in at all")
                conn.execute("UPDATE users SET oidc_sub=NULL, oidc_name='' WHERE id=?", (user["id"],))
                return self.send(200, {"ok": True})
            if method == "POST" and route == "account/oidc-only":
                enabled = bool(self.json_body().get("enabled"))
                if enabled and not (user["oidc_sub"] and oidc_enabled()):
                    return self.error(400, "Link your Authentik account first")
                conn.execute("UPDATE users SET oidc_only=? WHERE id=?", (int(enabled), user["id"]))
                if enabled:
                    # Password sessions end; only this one (and future Authentik ones) stay.
                    conn.execute("DELETE FROM sessions WHERE user_id=? AND token_hash<>?",
                                 (user["id"], token_hash(self.cookies()["hub_session"])))
                return self.send(200, {"ok": True})
            if method == "GET" and route == "admins":
                rows = conn.execute(
                    """SELECT id, username, email, oidc_name, oidc_only, created_at, password_hash='!' AS invited,
                       (SELECT MAX(expires_at) FROM invites WHERE user_id=users.id) AS invite_expires
                       FROM users ORDER BY id"""
                ).fetchall()
                return self.send(200, [dict(r, me=r["id"] == user["id"]) for r in rows])
            if method == "POST" and route == "admins":
                data = self.json_body()
                username = clean(data.get("username"), 60)
                email = clean(data.get("email"), 200)
                if not re.fullmatch(r"[a-zA-Z0-9._-]{3,60}", username):
                    return self.error(400, "Username: 3 to 60 letters, digits, dots, dashes or underscores")
                if not EMAIL_RE.match(email):
                    return self.error(400, "Enter the email address the invitation goes to")
                try:
                    # "!" can never match a password: the account works only after the invite is accepted.
                    cur = conn.execute("INSERT INTO users(username, password_hash, email, created_at) VALUES (?,?,?,?)",
                                       (username, "!", email, now()))
                except sqlite3.IntegrityError:
                    return self.error(400, "That username is taken")
                invited = conn.execute("SELECT * FROM users WHERE id=?", (cur.lastrowid,)).fetchone()
                link, failed = self.send_invite(conn, invited, user)
                return self.send(201, {"ok": True, "link": link, "mailError": failed})
            if parts[0] == "admins" and len(parts) == 3 and parts[1].isdigit() and method == "POST":
                target = conn.execute("SELECT * FROM users WHERE id=?", (int(parts[1]),)).fetchone()
                if not target:
                    return self.error(404, "No such admin")
                if parts[2] == "resend":
                    if target["password_hash"] != "!":
                        return self.error(400, "This admin has already set a password")
                    link, failed = self.send_invite(conn, target, user)
                    return self.send(200, {"ok": True, "link": link, "mailError": failed})
                if parts[2] == "remove":
                    if target["id"] == user["id"]:
                        return self.error(400, "You can't remove yourself")
                    active = conn.execute("SELECT COUNT(*) FROM users WHERE password_hash<>'!' AND id<>?", (target["id"],)).fetchone()[0]
                    if not active:
                        return self.error(400, "At least one admin with a password must stay")
                    conn.execute("DELETE FROM users WHERE id=?", (target["id"],))
                    return self.send(200, {"ok": True})
            if method == "GET" and route == "settings":
                values = {k: ("" if secret else setting(k)) for k, (_, _, secret) in SETTINGS.items()}
                values.update({k + "_set": bool(setting(k)) for k, (_, _, secret) in SETTINGS.items() if secret})
                return self.send(200, dict(values, redirect_uri=oidc_redirect_uri()))
            if method == "POST" and route == "settings":
                data = self.json_body()
                changes = {}
                for key, (_, _, secret) in SETTINGS.items():
                    if key not in data:
                        continue
                    value = clean(data[key], 500)
                    if secret and not value and not data.get(key + "_clear"):
                        continue  # blank secret = keep the saved one
                    changes[key] = value
                if "smtp_security" in changes and changes["smtp_security"] not in ("none", "starttls", "ssl"):
                    return self.error(400, "Security must be none, starttls or ssl")
                if "smtp_port" in changes and not changes["smtp_port"].isdigit():
                    return self.error(400, "Port must be a number")
                for key in ("admin_email", "mail_reply_to"):
                    if changes.get(key) and not all(EMAIL_RE.match(a.strip()) for a in changes[key].split(",")):
                        return self.error(400, "Check the email addresses")
                if changes.get("oidc_issuer"):
                    changes["oidc_issuer"] = normalize_issuer(changes["oidc_issuer"])
                if changes.get("oidc_issuer") and not changes["oidc_issuer"].startswith("https://"):
                    return self.error(400, "The issuer must be an https:// address")
                issuer = changes.get("oidc_issuer", setting("oidc_issuer"))
                client_id = changes.get("oidc_client_id", setting("oidc_client_id"))
                if bool(issuer) != bool(client_id):
                    return self.error(400, "Authentik needs both the issuer URL and the client ID (or neither)")
                if not changes.get("oidc_issuer", setting("oidc_issuer")) and conn.execute(
                        "SELECT 1 FROM users WHERE oidc_only=1").fetchone():
                    return self.error(400, "Some admins sign in with Authentik only; turn that off before removing Authentik")
                save_settings(conn, changes)
                return self.send(200, {"ok": True, "saved": sorted(changes)})
            if method == "POST" and route == "settings/test-oidc":
                if not oidc_enabled():
                    return self.error(400, "Save an issuer and client ID first")
                _discovery.clear()  # always check afresh
                try:
                    config = oidc_config()
                except ProviderError as error:
                    _discovery.clear()
                    return self.error(502, str(error))
                return self.send(200, {"ok": True, "issuer": config.get("issuer", "")})
            if method == "GET" and route == "templates":
                names = {r["name"]: dict(r) for r in conn.execute("SELECT name, updated_at, updated_by FROM email_templates")}
                return self.send(200, [
                    {"name": n, "label": label, "placeholders": ph, "custom": n in names,
                     "updated_at": names.get(n, {}).get("updated_at"), "updated_by": names.get(n, {}).get("updated_by")}
                    for n, (label, ph) in TEMPLATES.items()
                ])
            if parts[0] == "templates" and len(parts) >= 2 and parts[1] in TEMPLATES:
                name = parts[1]
                action = parts[2] if len(parts) > 2 else ""
                if method == "GET" and not action:
                    return self.send(200, {"name": name, "html": template_source(name), "default": default_template(name)})
                if method == "POST" and action == "preview":
                    return self.send(200, {"html": render_template(name, sample_values(name, user), self.json_body(400_000).get("html", ""))})
                if method == "POST" and action == "save":
                    source = str(self.json_body(400_000).get("html", ""))
                    if not source.strip():
                        return self.error(400, "The template is empty")
                    conn.execute("""INSERT INTO email_templates(name, html, updated_at, updated_by) VALUES (?,?,?,?)
                                    ON CONFLICT(name) DO UPDATE SET html=excluded.html, updated_at=excluded.updated_at,
                                    updated_by=excluded.updated_by""", (name, source, now(), user["username"]))
                    return self.send(200, {"ok": True})
                if method == "POST" and action == "reset":
                    conn.execute("DELETE FROM email_templates WHERE name=?", (name,))
                    return self.send(200, {"ok": True})
            if method == "POST" and route == "mail/test":
                to = user["email"] or setting("admin_email")
                if not to:
                    return self.error(400, "Add your email address first")
                try:
                    send_mail_now(to, "emhub test email", render_template("test", sample_values("test", user)))
                except Exception as error:  # noqa: BLE001
                    return self.error(502, f"Sending failed: {error}")
                return self.send(200, {"ok": True, "to": to})
            if method == "POST" and route == "preview":
                data = self.json_body()
                bug = conn.execute("SELECT * FROM bugs WHERE id=?", (int(data.get("bugId") or 0),)).fetchone()
                if not bug:
                    return self.error(404, "No such bug")
                values = bug_values(bug, reply_html=paragraphs(data.get("body", "")), agent=user["username"])
                return self.send(200, {"html": render_template("user_reply", values)})
        self.error(404, "Not found")

    def admin_bug(self, conn, user, method, bug_id, action):
        bug = conn.execute("SELECT * FROM bugs WHERE id=?", (bug_id,)).fetchone()
        if not bug:
            return self.error(404, "No such bug")
        if method == "GET" and not action:
            messages = conn.execute("SELECT * FROM bug_messages WHERE bug_id=? ORDER BY id", (bug_id,)).fetchall()
            return self.send(200, dict(bug, code=bug_code(bug_id), messages=[dict(m) for m in messages]))
        if method != "POST":
            return self.error(404, "Not found")
        data = self.json_body()
        t = now()
        status = data.get("status") if data.get("status") in BUG_STATUSES else bug["status"]
        if action == "reply":
            body = clean(data.get("body"), 20_000)
            if not body:
                return self.error(400, "Write a reply first")
            if not bug["email"]:
                return self.error(400, "This reporter left no email address")
            cur = conn.execute(
                "INSERT INTO bug_messages(bug_id, created_at, kind, author, body, mail_status) VALUES (?,?,?,?,?,?)",
                (bug_id, t, "reply", user["username"], body, "queued"),
            )
            conn.execute("UPDATE bugs SET status=?, updated_at=? WHERE id=?", (status, t, bug_id))
            bug = conn.execute("SELECT * FROM bugs WHERE id=?", (bug_id,)).fetchone()
            values = bug_values(bug, reply_html=paragraphs(body), agent=user["username"])
            queue_mail(bug["email"], f"Re: [{bug_code(bug_id)}] {bug['title']}", render_template("user_reply", values),
                       cur.lastrowid, setting("mail_reply_to") or user["email"])
            return self.send(200, {"ok": True})
        if action == "note":
            body = clean(data.get("body"), 20_000)
            if not body:
                return self.error(400, "Write a note first")
            conn.execute("INSERT INTO bug_messages(bug_id, created_at, kind, author, body) VALUES (?,?,?,?,?)",
                         (bug_id, t, "note", user["username"], body))
            conn.execute("UPDATE bugs SET updated_at=? WHERE id=?", (t, bug_id))
            return self.send(200, {"ok": True})
        if action == "status":
            if status == bug["status"]:
                return self.send(200, {"ok": True})
            notify = bool(data.get("notify")) and bool(bug["email"])
            cur = conn.execute(
                "INSERT INTO bug_messages(bug_id, created_at, kind, author, body, mail_status) VALUES (?,?,?,?,?,?)",
                (bug_id, t, "status", user["username"], f"{bug['status']} -> {status}", "queued" if notify else ""),
            )
            conn.execute("UPDATE bugs SET status=?, updated_at=? WHERE id=?", (status, t, bug_id))
            if notify:
                bug = conn.execute("SELECT * FROM bugs WHERE id=?", (bug_id,)).fetchone()
                queue_mail(bug["email"], f"[{bug_code(bug_id)}] Status: {status.replace('_', ' ')}",
                           render_template("user_status", bug_values(bug)), cur.lastrowid, setting("mail_reply_to"))
            return self.send(200, {"ok": True})
        self.error(404, "Not found")


class Server(ThreadingHTTPServer):
    daemon_threads = True

    def handle_error(self, request, client_address):
        # Clients closing idle connections is normal; log only real errors.
        if not isinstance(sys.exc_info()[1], (ConnectionError, TimeoutError)):
            super().handle_error(request, client_address)


def main():
    init_db()
    # Recovery if Authentik is down: python3 hub.py allow-password <username>
    if len(sys.argv) == 3 and sys.argv[1] == "allow-password":
        with db() as conn:
            changed = conn.execute("UPDATE users SET oidc_only=0 WHERE username=?", (sys.argv[2],)).rowcount
        print(f"password sign-in {'allowed again' if changed else 'unchanged: no such user'} for {sys.argv[2]}")
        return
    threading.Thread(target=mail_worker, daemon=True).start()
    host, port = ENV("HUB_HOST", "0.0.0.0"), int(ENV("HUB_PORT", "8080"))
    print(f"[emhub] listening on {host}:{port}, public URL {PUBLIC_URL}", flush=True)
    Server((host, port), Handler).serve_forever()


if __name__ == "__main__":
    main()
