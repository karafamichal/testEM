#!/usr/bin/env python3
"""
emhub: bug reports + community bus delays for Plan&Ride (testEM), and the Plan&Ride website.

One stdlib-only process:
  /, /privacy, /support, /changelog (and /en/...)   public website, pages edited in the dashboard
  /api/v1/*      public API used by the Android app and the web app (X-App-Key)
  /login, /auth  admin sign-in (local password or Authentik via OIDC)
  /dashboard     admin dashboard (bugs, community tracking, settings)
  /admin/api/*   JSON API behind the dashboard (session cookie)
  /api/v1/timetable/*   stops, boards, trips and connections from open GTFS feeds (timetable.py)

Configuration comes from environment variables (see hub.env.example).
"""
from concurrent.futures import ThreadPoolExecutor
import base64
import hashlib
import hmac
import html
import ipaddress
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

import sadzv
import timetable

VERSION = "1.14.0"
HERE = Path(__file__).resolve().parent
ENV = os.environ.get

PUBLIC_URL = ENV("HUB_PUBLIC_URL", "http://localhost:8080").rstrip("/")
# Older hostnames the dashboard may still be opened on (e.g. after a domain move), comma-separated.
ORIGINS = {PUBLIC_URL} | {o.strip().rstrip("/") for o in ENV("HUB_EXTRA_ORIGINS", "").split(",") if o.strip()}
DB_PATH = ENV("HUB_DB", str(HERE / "hub.db"))
APP_KEYS = {k.strip() for k in ENV("HUB_APP_KEYS", "dev-key").split(",") if k.strip()}
# Email and Authentik settings are edited in the dashboard (Settings) and stored in the
# database. These environment variables are only the starting values.
SETTINGS = {  # key: (env var, default, is secret)
    "admin_email": ("HUB_ADMIN_EMAIL", "", False),
    "mail_from": ("HUB_MAIL_FROM", "Plan&Ride <noreply@karafa.network>", False),
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
    "gtfs_feeds": ("HUB_GTFS_FEEDS", timetable.DEFAULT_FEEDS, False),  # transiq feed names, comma-separated
}
GTFS_DIR = Path(ENV("HUB_GTFS_DIR", str(Path(DB_PATH).parent / "gtfs")))
INVITE_HOURS = 72
SECURE_COOKIES = PUBLIC_URL.startswith("https://")
SESSION_DAYS = 14

BUG_STATUSES = ["new", "triaged", "in_progress", "resolved", "closed"]
BUG_CATEGORIES = ["crash", "ticket", "departures", "planner", "tracking", "account", "feature", "other"]
# A report is a bug or a feature suggestion; the dashboard lists them separately.
BUG_KINDS = ("bug", "feature")

# Community delay: how far back reports count, and how fast they fade.
COMMUNITY_WINDOW_S = 45 * 60
GPS_WINDOW_S = 3 * 60  # riders' phones on the bus: only this recent counts
# arrival: at the stop (a tap, or a phone on the bus standing there); position: a tap;
# gps: a phone on the bus between stops; departed / passed: the exact moment it left or
# drove past a stop (timetable times are departures, so these are the most exact delays).
REPORT_KINDS = ("arrival", "position", "gps", "departed", "passed")
PHONE_KINDS = ("gps", "departed", "passed")
EXACT_KINDS = ("departed", "passed")
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
CREATE TABLE IF NOT EXISTS bug_images (
  id INTEGER PRIMARY KEY,
  bug_id INTEGER NOT NULL REFERENCES bugs(id) ON DELETE CASCADE,
  created_at INTEGER NOT NULL,
  mime TEXT NOT NULL,
  data BLOB NOT NULL
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
CREATE TABLE IF NOT EXISTS site_pages (
  name TEXT NOT NULL,
  lang TEXT NOT NULL,
  html TEXT NOT NULL,
  updated_at INTEGER NOT NULL,
  updated_by TEXT NOT NULL DEFAULT '',
  PRIMARY KEY (name, lang)
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
        # 1.6.0: feature suggestions next to bug reports.
        if "kind" not in {r[1] for r in conn.execute("PRAGMA table_info(bugs)")}:
            conn.execute("ALTER TABLE bugs ADD COLUMN kind TEXT NOT NULL DEFAULT 'bug'")
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


DUMMY_HASH = hash_password(secrets.token_urlsafe(16))


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


# sadzv lookups for the planner run beside the timetable search.
LIVE_POOL = ThreadPoolExecutor(max_workers=4)

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
user_login_limiter = RateLimiter(20, 60 * 60)  # per username: many addresses guessing one account
bug_limiter = RateLimiter(10, 60 * 60)
report_limiter = RateLimiter(1200, 60 * 60)  # per IP: phones on mobile data share addresses
reporter_limiter = RateLimiter(300, 60 * 60)  # per rider: GPS every 15 s is 240 an hour
timetable_limiter = RateLimiter(2000, 60 * 60)  # per IP: a planner search is a few requests


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


MAX_IMAGES, MAX_IMAGE_BYTES, MAX_IMAGE_SIDE = 5, 4_000_000, 8000
# Start-of-frame markers (baseline, progressive, ...): they carry the picture size.
JPEG_SOF = {0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF}


def clean_jpeg(blob):
    """Rebuild a JPEG from its own segments, or raise ValueError. Drops APP1..APP15 and comments (EXIF with GPS,
    XMP, anything smuggled in) and bytes after the end marker (a polyglot's HTML/ZIP tail); caps the picture size
    so a tiny file can't decode into gigabytes in the dashboard."""
    if not blob.startswith(b"\xff\xd8"):
        raise ValueError("Only JPEG images")
    out, i, size = [b"\xff\xd8"], 2, None
    while True:
        if i + 4 > len(blob) or blob[i] != 0xFF:
            raise ValueError("An image is damaged")
        marker, length = blob[i + 1], int.from_bytes(blob[i + 2:i + 4], "big")
        segment = blob[i:i + 2 + length]
        if length < 2 or len(segment) != 2 + length:
            raise ValueError("An image is damaged")
        if marker in JPEG_SOF and length >= 7:
            size = int.from_bytes(segment[5:7], "big"), int.from_bytes(segment[7:9], "big")
        if marker == 0xDA:  # start of scan: picture data follows up to the end marker
            end = blob.rfind(b"\xff\xd9")
            if size is None or end < i:
                raise ValueError("An image is damaged")
            out.append(blob[i:end + 2])
            break
        if not (0xE1 <= marker <= 0xEF or marker == 0xFE):
            out.append(segment)
        i += 2 + length
    if not all(0 < side <= MAX_IMAGE_SIDE for side in size):
        raise ValueError(f"An image is too large (max {MAX_IMAGE_SIDE} px)")
    return b"".join(out)


def bug_images(values):
    """Screenshots or photos sent with a bug report: base64 JPEG (the apps re-encode whatever the user picks),
    at most 5 of 4 MB each. Only JPEG: one format to check, and nothing a browser could run as a script."""
    if not values:
        return []
    if not isinstance(values, list) or len(values) > MAX_IMAGES:
        raise ValueError(f"At most {MAX_IMAGES} images")
    images = []
    for value in values:
        try:
            blob = base64.b64decode(str(value), validate=True)
        except ValueError:
            raise ValueError("An image could not be read") from None
        if len(blob) > MAX_IMAGE_BYTES:
            raise ValueError("An image is too large (max 4 MB)")
        images.append(("image/jpeg", clean_jpeg(blob)))
    return images


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


# ------------------------------------------------------------------ website

# name: (path, {lang: (menu label, page title, description)}). Slovak at /path, English at /en/path.
SITE_PAGES = {
    "home": ("", {
        "sk": ("Domov", "Plan&Ride: autobusy na Slovensku naživo", "Odchody naživo, vyhľadávač spojení a sledovanie autobusu so živým upozornením."),
        "en": ("Home", "Plan&Ride: every bus in Slovakia, live", "Live departures, a journey planner and following your bus with a live notification."),
    }),
    "changelog": ("changelog", {
        "sk": ("Čo je nové", "Čo je nové v Plan&Ride", "Zmeny v každej verzii aplikácie Plan&Ride."),
        "en": ("What's new", "What's new in Plan&Ride", "What changed in each version of Plan&Ride."),
    }),
    "support": ("support", {
        "sk": ("Podpora", "Podpora Plan&Ride", "Ako nahlásiť chybu, navrhnúť funkciu a kontakt."),
        "en": ("Support", "Plan&Ride support", "How to report a bug, suggest a feature, and contact us."),
    }),
    "privacy": ("privacy", {
        "sk": ("Súkromie", "Zásady ochrany súkromia Plan&Ride", "Čo aplikácia Plan&Ride posiela kam a vaše práva."),
        "en": ("Privacy", "Plan&Ride privacy policy", "What the Plan&Ride app sends where, and your rights."),
    }),
}
SITE_LANGS = ("sk", "en")
SITE_WORDS = {"sk": {"nav": "Hlavná ponuka", "admin": "Správa", "other": "English"},
              "en": {"nav": "Main menu", "admin": "Admin", "other": "Slovensky"}}


def site_url(name, lang):
    path = SITE_PAGES[name][0]
    return ("/en/" if lang == "en" else "/") + path


def site_route(path):
    """(page, lang) for a website path, or None."""
    lang = "sk"
    if path == "/en" or path.startswith("/en/"):
        lang, path = "en", path[3:]
    path = path.strip("/")
    for name, (page_path, _) in SITE_PAGES.items():
        if path == page_path:
            return name, lang
    return None


def site_default(name, lang):
    return (HERE / "site" / f"{name}.{lang}.html").read_text(encoding="utf-8")


def site_source(name, lang):
    with db() as conn:
        row = conn.execute("SELECT html FROM site_pages WHERE name=? AND lang=?", (name, lang)).fetchone()
    return row["html"] if row else site_default(name, lang)


def render_site(name, lang, content=None):
    """A whole page: the shared layout around the page's (edited or default) content."""
    label, title, description = SITE_PAGES[name][1][lang]
    other = "en" if lang == "sk" else "sk"
    current = ' aria-current="page"'
    nav = "".join(
        f'<a href="{site_url(n, lang)}"{current if n == name else ""}>{html.escape(p[1][lang][0])}</a>'
        for n, p in SITE_PAGES.items()
    )
    words = SITE_WORDS[lang]
    values = {
        "lang": lang, "title": title, "description": description, "nav": nav, "nav_label": words["nav"],
        "home_url": site_url("home", lang), "url_sk": PUBLIC_URL + site_url(name, "sk"), "url_en": PUBLIC_URL + site_url(name, "en"),
        "lang_switch": f'<a href="{site_url(name, other)}" hreflang="{other}" lang="{other}">{words["other"]}</a>',
        "admin_label": words["admin"], "year": datetime.now(LOCAL_ZONE).year,
        "content": site_source(name, lang) if content is None else content,
    }
    return render_template("", values, (HERE / "site" / "layout.html").read_text(encoding="utf-8"))


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


# ------------------------------------------------------------------ live dashboard (Server-Sent Events)

_listeners = set()
_listeners_lock = threading.Lock()
MAX_LISTENERS = 20  # open dashboards; each holds a thread


def publish(kind):
    """Tell open dashboards that "bugs" or "community" changed; they reload that view."""
    with _listeners_lock:
        for q in _listeners:
            try:
                q.put_nowait(kind)
            except queue.Full:
                pass


def local_day_range(day):
    """[start, end) epoch seconds of a YYYY-MM-DD day in Slovakia, or None."""
    try:
        start = datetime.combine(date.fromisoformat(day), datetime.min.time(), LOCAL_ZONE).timestamp()
    except (TypeError, ValueError):
        return None
    return int(start), int(start) + 86400


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
    for key, delay_s, report_kind in conn.execute(
        """SELECT trip_key, delay_s, kind FROM community_reports WHERE trip_key LIKE ? AND created_at>=?
           AND reporter NOT IN (SELECT reporter FROM blocked_reporters)""",
        (prefix, now() - HISTORY_DAYS * 86400),
    ):
        other = trip_run(key)
        if other and other[0] == trip and other[1] != day and day_type(other[1]) == kind:
            runs.setdefault(other[1], []).append((delay_s, report_kind in EXACT_KINDS))
    # A run's delay: from the exact moments it left or passed stops when a phone recorded any.
    recent = [statistics.median([d for d, exact in r if exact] or [d for d, _ in r])
              for _, r in sorted(runs.items(), reverse=True)[:HISTORY_RUNS]]
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
    gps = [r for r in rows if r["kind"] in PHONE_KINDS and now() - r["created_at"] <= GPS_WINDOW_S]
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


def one_line(value, limit):
    """Titles and names end up in email headers: no line breaks or control characters."""
    return re.sub(r"[\x00-\x1f\x7f]+", " ", clean(value, limit)).strip()


# Dashboard pages: scripts and styles are inline, fonts come from Google, the only other
# requests go back to emhub itself. Blocks injected external scripts and data leaving.
CSP = ("default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; "
       "font-src https://fonts.gstatic.com; img-src 'self' data:; connect-src 'self'; frame-src 'self'; "
       "frame-ancestors 'none'; base-uri 'none'; form-action 'self'; object-src 'none'")


def trusted_proxy(address):
    """cloudflared and the web server reach emhub over the LAN or loopback."""
    try:
        ip = ipaddress.ip_address(address)
    except ValueError:
        return False
    return ip.is_private or ip.is_loopback


EMAIL_RE = re.compile(r"^[^@\s]+@[^@\s]+\.[^@\s]+$")


# ------------------------------------------------------------------ timetable API (app, web, dashboard)

def gtfs_feeds():
    return [f.strip() for f in setting("gtfs_feeds").split(",") if f.strip()]


def when_param(value):
    """Epoch seconds, or a local ISO time ("2026-09-30T07:00"); empty = now."""
    if not value:
        return now()
    if re.fullmatch(r"\d{9,11}", value):
        return int(value)
    moment = datetime.fromisoformat(value)
    return int((moment if moment.tzinfo else moment.replace(tzinfo=LOCAL_ZONE)).timestamp())


def merge_live(journeys, live):
    """Timetable and live (sadzv) connections by departure; the live copy of the same bus wins, it can be followed live."""
    def same_bus(j, leg):
        legs = [x for x in j["legs"] if x["type"] == "trip"]
        a, b = (legs[0]["line"], leg["line"]) if legs else ("", "")
        return len(legs) == 1 and abs(legs[0]["departure"] - leg["departure"]) <= 60_000 and \
            (a == b or a.endswith(b) or b.endswith(a))
    kept = [j for j in journeys if not any(same_bus(j, l["legs"][0]) for l in live)]
    return sorted(kept + live, key=lambda j: (j["departure"], j["arrival"]))


def timetable_query(route, query):
    """(HTTP status, body) for GET timetable/<route>."""
    t = timetable.current
    if t is None:
        return 503, {"error": "Timetable is loading, try again in a minute"}
    limit = lambda default, top: max(1, min(top, int(query.get("n") or default)))  # noqa: E731
    if route == "stops":
        if query.get("q"):
            return 200, {"stops": t.search(query["q"], limit(18, 50))}
        if query.get("lat") and query.get("lon"):
            return 200, {"stops": t.near(float(query["lat"]), float(query["lon"]), min(2000, int(query.get("radius") or 300)),
                                         limit(10, 50))}
        return 400, {"error": "q or lat and lon are required"}
    if route == "departures":
        if query.get("stop"):
            stations = [s for s in (t.by_id.get(i) for i in query["stop"].split(",")) if s is not None]
        elif query.get("lat") and query.get("lon"):
            radius = min(1000, int(query.get("radius") or 150))
            stations = [t.by_id[s["id"]] for s in t.near(float(query["lat"]), float(query["lon"]), radius, 20)]
        else:
            return 400, {"error": "stop or lat and lon are required"}
        exclude = {f for f in (query.get("exclude") or "").split(",") if f}
        return 200, {"departures": t.departures(stations, when_param(query.get("time")), limit(30, 200), exclude,
                                                ending=query.get("ending") in ("1", "true")),
                     "stops": [t.station_json(s) for s in stations]}
    if route == "trip":
        trip = t.trip(query.get("id") or "") if re.fullmatch(r"[^:]+:.+:\d{8}", query.get("id") or "") else None
        return (200, trip) if trip else (404, {"error": "No such trip"})
    if route == "plan":
        origin, target = t.resolve(query.get("from") or ""), t.resolve(query.get("to") or "")
        if origin is None or target is None:
            return 404, {"error": "Stop not found", "from": origin is not None, "to": target is not None}
        when, count = when_param(query.get("time")), limit(5, 10)
        # SAD Zvolen's live data is asked at the same time as the timetable is searched (about 1 s each).
        a = (t.lat[origin], t.lon[origin]) if t.lat[origin] is not None else sadzv.locate(t.names[origin], t.town_center(origin))
        b = (t.lat[target], t.lon[target]) if t.lat[target] is not None else sadzv.locate(t.names[target], t.town_center(target))
        name_of = lambda lat, lon: next((s["name"] for s in t.near(lat, lon, 120, 1)), None)  # noqa: E731
        live_job = LIVE_POOL.submit(sadzv.journeys, a, b, when, count, name_of) if a and b else None
        # change: the rider's least time between two buses in minutes (app setting; 0 = any), 1 by default.
        raw = str(query.get("change") or "")
        change = min(30, int(raw)) * 60 if raw.isdigit() else timetable.CHANGE_S
        journeys = t.plan(origin, target, when, count, query.get("direct") in ("1", "true"), change)
        if live_job:
            # City buses aren't in the open timetables: SAD Zvolen's live data adds its direct buses.
            live = live_job.result()
            for j in live:  # the bus runs between the two searched stops: show their names
                j["legs"][0]["from"]["name"], j["legs"][0]["to"]["name"] = t.names[origin], t.names[target]
            journeys = merge_live(journeys, live)[:count]
        return 200, {"from": t.station_json(origin), "to": t.station_json(target), "connections": journeys}
    return 404, {"error": "Not found"}


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
        if forwarded and trusted_proxy(direct):
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
            self.send_header("Content-Security-Policy", CSP)
        if SECURE_COOKIES:
            self.send_header("Strict-Transport-Security", "max-age=31536000")
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

    MAX_BODY = 20_000_000  # a bug report may carry a few photos

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
        page = site_route(path)
        if method == "GET" and page:
            return self.send(200, render_site(*page), "text/html")
        if method == "GET" and path == "/mark.png":
            return self.static("mark.png", "image/png")
        if method == "GET" and path == "/login":
            # Already signed in (e.g. from the website's Admin link): straight to the dashboard.
            return self.redirect("/dashboard") if self.current_user() else self.static("login.html")
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
        username = form.get("username", "").strip()[:60]
        if not login_limiter.allow(self.client_ip()) or not user_login_limiter.allow(username.lower()):
            return self.redirect("/login?error=rate")
        with db() as conn:
            user = conn.execute("SELECT * FROM users WHERE username=?", (username,)).fetchone()
            # A missing user costs the same scrypt as a wrong password.
            ok = check_password(form.get("password", ""), user["password_hash"] if user else DUMMY_HASH)
            if not user or not ok:
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
        given = self.headers.get("X-App-Key", "")
        if not any(hmac.compare_digest(given.encode(), k.encode()) for k in APP_KEYS):
            return self.error(401, "Missing or wrong app key", cors=True)
        if method == "GET" and route.startswith("timetable/"):
            if not timetable_limiter.allow(self.client_ip()):
                return self.error(429, "Too many requests", cors=True)
            status, body = timetable_query(route[len("timetable/"):], query)
            return self.send(status, body, headers=self.cors())
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
        data = self.json_body(limit=18_000_000)
        if data.get("consent") is not True:
            return self.error(400, "Consent is required", cors=True)
        kind = data.get("kind") if data.get("kind") in BUG_KINDS else "bug"
        description = clean(data.get("description"), 10_000)
        logs = clean(data.get("logs"), 1_000_000) if kind == "bug" else ""
        email = one_line(data.get("email"), 200)
        title = one_line(data.get("title"), 140)
        # Only the logs are essential; name, email, summary and text are all optional.
        if kind == "feature" and not description and not title:
            return self.error(400, "Describe your idea", cors=True)
        if kind == "bug" and not description and not logs:
            return self.error(400, "Describe the problem or attach logs", cors=True)
        title = title or one_line(description.splitlines()[0] if description else "", 80)
        title = title or f"Logs from {one_line(data.get('platform'), 20) or 'app'}"
        if email and not EMAIL_RE.match(email):
            return self.error(400, "Email address looks wrong", cors=True)
        category = "feature" if kind == "feature" else data.get("category") if data.get("category") in BUG_CATEGORIES else "other"
        images = bug_images(data.get("images"))
        t = now()
        with db() as conn:
            cur = conn.execute(
                """INSERT INTO bugs(created_at, updated_at, status, category, title, description, name, email,
                   platform, app_version, device, logs, consent_at, kind) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                (t, t, "new", category, title, description, one_line(data.get("name"), 120), email,
                 one_line(data.get("platform"), 20), one_line(data.get("appVersion"), 40), one_line(data.get("device"), 200),
                 logs, t, kind),
            )
            bug = conn.execute("SELECT * FROM bugs WHERE id=?", (cur.lastrowid,)).fetchone()
            for mime, blob in images:
                conn.execute("INSERT INTO bug_images(bug_id, created_at, mime, data) VALUES (?,?,?,?)", (bug["id"], t, mime, blob))
            conn.execute(
                "INSERT INTO bug_messages(bug_id, created_at, kind, author, body) VALUES (?,?,?,?,?)",
                (bug["id"], t, "system", "", "Report received" + (f" with {len(images)} image(s)" if images else "")
                 + (", confirmation sent to reporter" if email else "")),
            )
        code = bug_code(bug["id"])
        admin_to = setting("admin_email") or ",".join(
            r["email"] for r in db().execute("SELECT email FROM users WHERE email<>''")
        )
        what = "New suggestion" if kind == "feature" else "New bug"
        queue_mail(admin_to, f"[{code}] {what}: {title}", render_template("admin_new_bug", bug_values(bug)))
        if email:
            queue_mail(email, f"[{code}] We got your report", render_template("user_ack", bug_values(bug)),
                       reply_to=setting("mail_reply_to"))
        publish("bugs")
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
        publish("community")
        self.send(201, {"delay": result, "yourDelaySeconds": delay}, headers=self.cors())

    # ---- admin API

    def events(self):
        """Stream "bugs"/"community" change events to an open dashboard (text/event-stream)."""
        q = queue.Queue(maxsize=50)
        with _listeners_lock:
            if len(_listeners) >= MAX_LISTENERS:
                return self.error(503, "Too many open dashboards")
            _listeners.add(q)
        self.close_connection = True
        try:
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.send_header("Cache-Control", "no-cache")
            self.send_header("X-Accel-Buffering", "no")
            self.end_headers()
            self.wfile.write(b"retry: 5000\n\n")
            self.wfile.flush()
            while True:
                try:
                    message = f"event: {q.get(timeout=20)}\ndata: {{}}\n\n"
                except queue.Empty:
                    message = ": ping\n\n"  # keeps proxies (Cloudflare) from closing an idle stream
                self.wfile.write(message.encode())
                self.wfile.flush()
        except OSError:
            pass  # dashboard closed
        finally:
            with _listeners_lock:
                _listeners.discard(q)

    def admin_api(self, method, route, query):
        user = self.current_user()
        if not user:
            return self.error(401, "Sign in first")
        if method == "GET" and route == "events":
            return self.events()
        if method == "POST":
            # Cross-site forms cannot send JSON with a custom content type without a preflight.
            if "application/json" not in (self.headers.get("Content-Type") or ""):
                return self.error(415, "JSON only")
            origin = self.headers.get("Origin")
            if origin and origin.rstrip("/") not in ORIGINS:
                return self.error(403, "Wrong origin")
        if route == "timetable/status":
            return self.send(200, dict(timetable.status(GTFS_DIR, gtfs_feeds()), feedList=setting("gtfs_feeds"),
                                       source=" then ".join(u for u in (timetable.CIS_URL, timetable.FEED_URL) if u)))
        if method == "POST" and route == "timetable/update":
            timetable.request_update()
            return self.send(202, {"ok": True})
        if method == "POST" and route == "timetable/feeds":
            names = [f.strip() for f in re.split(r"[,\s]+", str(self.json_body().get("feeds", ""))) if f.strip()]
            if not names or not all(re.fullmatch(r"[a-z0-9_-]{1,60}", f) for f in names):
                return self.error(400, "Feed names are lowercase letters, digits, - and _")
            with db() as conn:
                save_settings(conn, {"gtfs_feeds": ",".join(dict.fromkeys(names))})
            timetable.log(f"{user['username']} changed the feed list: {', '.join(names)}")
            timetable.request_update()
            return self.send(200, {"ok": True})
        if method == "GET" and route.startswith("timetable/"):
            status, body = timetable_query(route[len("timetable/"):], query)
            return self.send(status, body)
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
                counts = {r["status"]: r["n"] for r in conn.execute("SELECT status, COUNT(*) n FROM bugs WHERE kind='bug' GROUP BY status")}
                ideas = {r["status"]: r["n"] for r in conn.execute("SELECT status, COUNT(*) n FROM bugs WHERE kind='feature' GROUP BY status")}
                active = conn.execute(
                    "SELECT COUNT(DISTINCT trip_key) FROM community_reports WHERE created_at>=?", (now() - COMMUNITY_WINDOW_S,)
                ).fetchone()[0]
                reports_today = conn.execute(
                    "SELECT COUNT(*) FROM community_reports WHERE created_at>=?", (now() - 86400,)
                ).fetchone()[0]
                return self.send(200, {"bugs": counts, "ideas": ideas, "activeTrips": active, "reportsToday": reports_today})
            if method == "GET" and route == "bugs":
                sql, args = "SELECT id, created_at, updated_at, status, category, title, name, email, platform, kind FROM bugs", []
                where = ["kind=?"]
                args.append(query.get("kind") if query.get("kind") in BUG_KINDS else "bug")
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
                return self.admin_bug(conn, user, method, int(parts[1]), "/".join(parts[2:]))
            if method == "GET" and route == "community/trips":
                # Default: the last 6 hours; ?day=YYYY-MM-DD for any past day, ?line= to narrow it.
                span = local_day_range(query["day"]) if query.get("day") else (now() - 6 * 3600, now() + 60)
                if not span:
                    return self.error(400, "day must be YYYY-MM-DD")
                sql = """SELECT trip_key, line, MIN(created_at) first, MAX(created_at) last, COUNT(*) n,
                         COUNT(DISTINCT reporter) riders FROM community_reports WHERE created_at>=? AND created_at<?"""
                args = list(span)
                if query.get("line"):
                    sql += " AND line=?"
                    args.append(clean(query["line"], 40))
                rows = conn.execute(sql + " GROUP BY trip_key ORDER BY last DESC LIMIT 300", args).fetchall()
                trips = []
                for r in rows:
                    last = conn.execute("""SELECT delay_s, stop_name FROM community_reports WHERE trip_key=?
                                           ORDER BY created_at DESC LIMIT 1""", (r["trip_key"],)).fetchone()
                    worst = conn.execute("SELECT MAX(delay_s) FROM community_reports WHERE trip_key=?", (r["trip_key"],)).fetchone()[0]
                    trips.append(dict(r, delay=trip_delay(conn, r["trip_key"]), history=trip_history(conn, r["trip_key"]),
                                      last_delay=last["delay_s"], last_stop=last["stop_name"], max_delay=worst))
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
            if method == "GET" and route == "site":
                edited = {(r["name"], r["lang"]): dict(r) for r in conn.execute("SELECT name, lang, updated_at, updated_by FROM site_pages")}
                return self.send(200, [
                    {"name": n, "lang": lang, "label": p[1][lang][0], "url": site_url(n, lang), "custom": (n, lang) in edited,
                     "updated_at": edited.get((n, lang), {}).get("updated_at"), "updated_by": edited.get((n, lang), {}).get("updated_by")}
                    for n, p in SITE_PAGES.items() for lang in SITE_LANGS
                ])
            if parts[0] == "site" and len(parts) >= 3 and parts[1] in SITE_PAGES and parts[2] in SITE_LANGS:
                name, lang = parts[1], parts[2]
                action = parts[3] if len(parts) > 3 else ""
                if method == "GET" and not action:
                    return self.send(200, {"name": name, "lang": lang, "html": site_source(name, lang), "default": site_default(name, lang)})
                if method == "POST" and action == "preview":
                    return self.send(200, {"html": render_site(name, lang, str(self.json_body(400_000).get("html", "")))})
                if method == "POST" and action == "save":
                    source = str(self.json_body(400_000).get("html", ""))
                    if not source.strip():
                        return self.error(400, "The page is empty")
                    conn.execute("""INSERT INTO site_pages(name, lang, html, updated_at, updated_by) VALUES (?,?,?,?,?)
                                    ON CONFLICT(name, lang) DO UPDATE SET html=excluded.html, updated_at=excluded.updated_at,
                                    updated_by=excluded.updated_by""", (name, lang, source, now(), user["username"]))
                    return self.send(200, {"ok": True})
                if method == "POST" and action == "reset":
                    conn.execute("DELETE FROM site_pages WHERE name=? AND lang=?", (name, lang))
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
            images = conn.execute("SELECT id, mime, length(data) size FROM bug_images WHERE bug_id=? ORDER BY id", (bug_id,)).fetchall()
            return self.send(200, dict(bug, code=bug_code(bug_id), messages=[dict(m) for m in messages],
                                       images=[dict(i) for i in images]))
        if method == "GET" and action.startswith("images/") and action[7:].isdigit():
            image = conn.execute("SELECT mime, data FROM bug_images WHERE id=? AND bug_id=?", (int(action[7:]), bug_id)).fetchone()
            if not image:
                return self.error(404, "No such image")
            # Reporter-supplied bytes: never sniffed, scripted or framed, even when opened on their own.
            return self.send(200, bytes(image["data"]), image["mime"], {
                "Cache-Control": "private, max-age=86400", "Content-Security-Policy": "default-src 'none'; sandbox",
                "Content-Disposition": f'inline; filename="{bug_code(bug_id)}-{action[7:]}.jpg"'})
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
    if ENV("HUB_TIMETABLE", "on") != "off":
        timetable.start(GTFS_DIR, gtfs_feeds, collect=sadzv.collect)
    host, port = ENV("HUB_HOST", "0.0.0.0"), int(ENV("HUB_PORT", "8080"))
    print(f"[emhub] listening on {host}:{port}, public URL {PUBLIC_URL}", flush=True)
    Server((host, port), Handler).serve_forever()


if __name__ == "__main__":
    main()
