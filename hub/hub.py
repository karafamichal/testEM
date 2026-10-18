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
import math
import os
import queue
import re
import secrets
import shutil
import smtplib
import socket
import sqlite3
import struct
import ssl
import sys
import threading
import time
import traceback
import urllib.error
import urllib.parse
import urllib.request
import statistics
from collections import deque
from datetime import date, datetime, timedelta
from zoneinfo import ZoneInfo
from email.message import EmailMessage
from email.utils import formataddr, make_msgid
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import sadzv
import timetable

VERSION = "1.38.0"
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
    "web_url": ("HUB_WEB_URL", "", False),  # the web version (iPhone PWA): shared trips open there
}
GTFS_DIR = Path(ENV("HUB_GTFS_DIR", str(Path(DB_PATH).parent / "gtfs")))
INVITE_HOURS = 72
SECURE_COOKIES = PUBLIC_URL.startswith("https://")
SESSION_DAYS = 14

# awaiting_release: fixed, but the version with the fix isn't out yet (Releases marks it resolved and tells the reporter).
BUG_STATUSES = ["new", "triaged", "in_progress", "awaiting_release", "resolved", "closed"]
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
CREATE TABLE IF NOT EXISTS map_stops (
  station_id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  lat REAL NOT NULL,
  lon REAL NOT NULL,
  orig_lat REAL,
  orig_lon REAL,
  updated_by TEXT NOT NULL DEFAULT '',
  updated_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS map_stations (
  station_id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  data TEXT NOT NULL,
  updated_by TEXT NOT NULL DEFAULT '',
  updated_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS map_children (
  child_id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  parent_id TEXT NOT NULL,
  data TEXT NOT NULL,
  updated_by TEXT NOT NULL DEFAULT '',
  updated_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS map_segments (
  segment TEXT PRIMARY KEY,
  points TEXT NOT NULL,
  source TEXT NOT NULL DEFAULT 'manual',
  updated_by TEXT NOT NULL DEFAULT '',
  updated_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS users (
  id INTEGER PRIMARY KEY,
  username TEXT UNIQUE NOT NULL,
  password_hash TEXT NOT NULL,
  email TEXT NOT NULL DEFAULT '',
  oidc_sub TEXT UNIQUE,
  oidc_name TEXT NOT NULL DEFAULT '',
  created_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS crowd_reports (
  id INTEGER PRIMARY KEY,
  created_at INTEGER NOT NULL,
  trip_key TEXT NOT NULL,
  level INTEGER NOT NULL,
  reporter TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS crowd_trip ON crowd_reports(trip_key, created_at);
CREATE TABLE IF NOT EXISTS submissions (
  id INTEGER PRIMARY KEY,
  created_at INTEGER NOT NULL,
  kind TEXT NOT NULL,
  name TEXT NOT NULL,
  email TEXT NOT NULL DEFAULT '',
  data TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'new',
  note TEXT NOT NULL DEFAULT '',
  reviewed_by TEXT NOT NULL DEFAULT '',
  reviewed_at INTEGER
);
CREATE TABLE IF NOT EXISTS saved_replies (
  id INTEGER PRIMARY KEY,
  title TEXT NOT NULL,
  body_sk TEXT NOT NULL DEFAULT '',
  body_en TEXT NOT NULL DEFAULT '',
  updated_at INTEGER NOT NULL,
  updated_by TEXT NOT NULL DEFAULT ''
);
CREATE TABLE IF NOT EXISTS releases (
  version TEXT PRIMARY KEY,
  released_at INTEGER,
  notes_en TEXT NOT NULL DEFAULT '',
  notes_sk TEXT NOT NULL DEFAULT '',
  notified INTEGER NOT NULL DEFAULT 0,
  updated_by TEXT NOT NULL DEFAULT ''
);
CREATE TABLE IF NOT EXISTS map_history (
  id INTEGER PRIMARY KEY,
  created_at INTEGER NOT NULL,
  username TEXT NOT NULL,
  action TEXT NOT NULL,
  tbl TEXT NOT NULL,
  key_col TEXT NOT NULL,
  key TEXT NOT NULL,
  before TEXT,
  after TEXT,
  undone_at INTEGER
);
CREATE TABLE IF NOT EXISTS feed_history (
  day TEXT NOT NULL,
  feed TEXT NOT NULL,
  trips INTEGER,
  result TEXT NOT NULL DEFAULT '',
  alerted INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (day, feed)
);
CREATE TABLE IF NOT EXISTS build_snapshots (
  built INTEGER PRIMARY KEY,
  data TEXT NOT NULL,
  changes TEXT NOT NULL DEFAULT ''
);
CREATE TABLE IF NOT EXISTS notices (
  id INTEGER PRIMARY KEY,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  updated_by TEXT NOT NULL DEFAULT '',
  level TEXT NOT NULL DEFAULT 'info',
  title_sk TEXT NOT NULL DEFAULT '',
  title_en TEXT NOT NULL DEFAULT '',
  body_sk TEXT NOT NULL DEFAULT '',
  body_en TEXT NOT NULL DEFAULT '',
  lines TEXT NOT NULL DEFAULT '',
  stops TEXT NOT NULL DEFAULT '',
  starts_at INTEGER,
  ends_at INTEGER
);
CREATE TABLE IF NOT EXISTS audit_log (
  id INTEGER PRIMARY KEY,
  created_at INTEGER NOT NULL,
  username TEXT NOT NULL,
  action TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS admin_lines (
  id INTEGER PRIMARY KEY,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  updated_by TEXT NOT NULL,
  data TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS map_vias (
  segment TEXT PRIMARY KEY,
  points TEXT NOT NULL,
  updated_by TEXT NOT NULL DEFAULT '',
  updated_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS map_routes (
  route_key TEXT PRIMARY KEY,
  stations TEXT NOT NULL,
  updated_by TEXT NOT NULL DEFAULT '',
  updated_at INTEGER NOT NULL
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


# Saved replies to start with (editable and addable in the dashboard): title, Slovak, English.
DEFAULT_REPLIES = [
    ("Update the app",
     "Ďakujeme za hlásenie. Prosím, aktualizujte si Plan&Ride na najnovšiu verziu v Google Play a dajte nám vedieť, či sa to stále deje.",
     "Thanks for the report. Please update Plan&Ride to the latest version in Google Play and let us know if it still happens."),
    ("Allow alarms and reminders",
     "Ďakujeme, našli sme príčinu. Android 14 a novší spúšťa pripomienky bez povolenia „Budíky a pripomienky“ až s hodinovým oneskorením. "
     "Povoľte ho v Nastavenia > Aplikácie > Plan&Ride > Budíky a pripomienky. Pomôže aj nastaviť batériu pre Plan&Ride na „Bez obmedzení“.",
     "Thanks, we found the cause. Android 14 and newer can start reminders up to an hour late without the Alarms and reminders permission. "
     "Allow it in Settings > Apps > Plan&Ride > Alarms and reminders. Setting Plan&Ride's battery use to Unrestricted helps too."),
    ("Fixed in the next update",
     "Ďakujeme, chybu sme opravili. Oprava príde v najbližšej aktualizácii; keď vyjde, dáme vám vedieť.",
     "Thanks, we fixed it. The fix comes with the next update; we'll let you know when it's out."),
    ("Need more details",
     "Ďakujeme za hlásenie. Aby sme to vedeli opraviť, napíšte nám prosím, kedy presne sa to stalo, na akej zastávke alebo linke, a ak môžete, pošlite snímku obrazovky.",
     "Thanks for the report. To fix it, please tell us when exactly it happened, at which stop or on which line, and send a screenshot if you can."),
]


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
        # 1.34.0: the reporter's language (emails in Slovak or English), labels, the version a fix ships in.
        bug_cols = {r[1] for r in conn.execute("PRAGMA table_info(bugs)")}
        for col in ("lang", "labels", "fixed_in"):
            if col not in bug_cols:
                conn.execute(f"ALTER TABLE bugs ADD COLUMN {col} TEXT NOT NULL DEFAULT ''")
        if not conn.execute("SELECT 1 FROM saved_replies LIMIT 1").fetchone():
            for title, body_sk, body_en in DEFAULT_REPLIES:
                conn.execute("INSERT INTO saved_replies(title, body_sk, body_en, updated_at) VALUES (?,?,?,?)",
                             (title, body_sk, body_en, now()))
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


def load_map_edits():
    """The dashboard map's edits into the timetable (applied on every rebuild, so a GTFS update keeps them)."""
    with db() as conn:
        timetable.edits["stops"] = {r["station_id"]: {"name": r["name"], "lat": r["lat"], "lon": r["lon"],
                                                      "origLat": r["orig_lat"] if r["orig_lat"] is not None else r["lat"],
                                                      "origLon": r["orig_lon"] if r["orig_lon"] is not None else r["lon"]}
                                    for r in conn.execute("SELECT * FROM map_stops")}
        timetable.edits["segments"] = {r["segment"]: json.loads(r["points"]) for r in conn.execute("SELECT segment, points FROM map_segments")}
        timetable.edits["stations"] = {r["station_id"]: dict(json.loads(r["data"]), name=r["name"]) for r in conn.execute("SELECT * FROM map_stations")}
        timetable.edits["children"] = {r["child_id"]: dict(json.loads(r["data"]), name=r["name"], parent=r["parent_id"])
                                       for r in conn.execute("SELECT * FROM map_children")}
        timetable.edits["routes"] = {r["route_key"]: json.loads(r["stations"]) for r in conn.execute("SELECT * FROM map_routes")}
        timetable.edits["vias"] = {r["segment"]: json.loads(r["points"]) for r in conn.execute("SELECT * FROM map_vias")}
        approved = [(r["id"], r["kind"], json.loads(r["data"])) for r in conn.execute(
            "SELECT id, kind, data FROM submissions WHERE status='approved' AND kind IN ('stop', 'trip') ORDER BY id")]
        timetable.edits["extra_stations"] = [dict(d, id=f"submitted-{i}") for i, kind, d in approved if kind == "stop"]
        timetable.edits["extra_trips"] = [dict(d, id=i) for i, kind, d in approved if kind == "trip"]
        # Lines added in the dashboard: one trip per departure from the first stop, the others at their minutes after it.
        for r in conn.execute("SELECT id, data FROM admin_lines ORDER BY id"):
            timetable.edits["extra_trips"].extend(line_trips(r["id"], json.loads(r["data"])))


def line_trips(line_id, d):
    """An admin line's trips for the timetable: each departure with the stops at their minutes after the first."""
    return [{"id": f"L{line_id}-{k}", "tripId": f"line-{line_id}-{k}", "line": d["line"], "headsign": d.get("headsign", ""),
             "operator": d.get("operator", ""), "weekdays": d["weekdays"], "validFrom": d.get("validFrom"), "validTo": d.get("validTo"),
             "stops": [[sid, start + minutes * 60] for sid, minutes in d["stops"]]}
            for k, start in enumerate(d["departures"])]


def clean_admin_line(data, t):
    """
    A line as the dashboard sends it, checked: line, headsign, operator, weekdays (0 = Monday), stops [[station id,
    minutes after the first stop]] in order, departures ["HH:MM", ...] from the first stop. Raises ValueError.
    """
    line = one_line(data.get("line"), 12)
    if not line:
        raise ValueError("The line needs a number or name")
    try:
        weekdays = sorted({int(x) for x in data.get("weekdays") or [] if 0 <= int(x) <= 6})
        stops = [[str(sid), int(minutes)] for sid, minutes in data.get("stops") or []]
        departures = []
        for hhmm in data.get("departures") or []:
            h, m = (int(x) for x in str(hhmm).strip().split(":"))
            if not (0 <= h < 30 and 0 <= m < 60):
                raise ValueError
            departures.append(h * 3600 + m * 60)
    except (TypeError, ValueError):
        raise ValueError("Times are HH:MM and minutes are whole numbers") from None
    if not weekdays:
        raise ValueError("Pick at least one day")
    if not 2 <= len(stops) <= 80:
        raise ValueError("A line has 2 to 80 stops")
    if t is None or any(sid not in t.by_id for sid, _ in stops):
        raise ValueError("Each stop must be one of the timetable's stops")
    if stops[0][1] != 0 or any(b[1] < a[1] for a, b in zip(stops, stops[1:])) or stops[-1][1] > 24 * 60:
        raise ValueError("Minutes start at 0 and never go back")
    if not 1 <= len(departures) <= 300:
        raise ValueError("Add 1 to 300 departures")
    valid = [str(data.get(k) or "") for k in ("validFrom", "validTo")]
    if any(v and not re.fullmatch(r"\d{4}-\d{2}-\d{2}", v) for v in valid) or (all(valid) and valid[0] > valid[1]):
        raise ValueError("Valid from and to are dates, the first not after the second")
    return {"line": line, "headsign": one_line(data.get("headsign"), 80) or t.names[t.by_id[stops[-1][0]]],
            "operator": one_line(data.get("operator"), 80), "weekdays": weekdays, "stops": stops,
            "departures": sorted(set(departures)), "validFrom": valid[0] or None, "validTo": valid[1] or None}


# ------------------------------------------------------------------ map: history with undo, to-do list, bulk snapping

MAP_TABLES = {"map_stops": "station_id", "map_stations": "station_id", "map_children": "child_id", "map_segments": "segment",
              "map_vias": "segment", "map_routes": "route_key"}


def history_targets(route, data):
    """The rows a map edit changes, as (table, key column, key): their before and after go into the history."""
    seg = f"{data.get('from')}>{data.get('to')}"
    if route in ("stop", "stop/reset"):
        return [("map_stops", "station_id", str(data.get("id")))]
    if route == "station":
        return [("map_stations", "station_id", str(data.get("id")))]
    if route in ("child", "child/remove"):
        return [("map_children", "child_id", str(data.get("child")))]
    if route in ("segment", "segment/reset") or (route == "snap" and data.get("from")):
        return [("map_segments", "segment", seg)]
    if route == "via":
        return [("map_vias", "segment", seg)]
    if route == "route" and timetable.current is not None:
        key = timetable.current.route_key(str(data.get("trip") or ""))
        return [("map_routes", "route_key", key)] if key else []
    return []


def map_row(table, key_col, key):
    with db() as conn:
        row = conn.execute(f"SELECT * FROM {table} WHERE {key_col}=?", (key,)).fetchone()
    return dict(row) if row else None


def undo_map_change(entry):
    """Puts a map row back as it was before [entry] (removes it when it didn't exist)."""
    table, key_col = entry["tbl"], entry["key_col"]
    if MAP_TABLES.get(table) != key_col:
        raise ValueError("Not a map table")
    before = json.loads(entry["before"]) if entry["before"] else None
    with db() as conn:
        conn.execute(f"DELETE FROM {table} WHERE {key_col}=?", (entry["key"],))
        if before:
            cols = [c for c in before if re.fullmatch(r"[a-z_]+", c)]
            conn.execute(f"INSERT INTO {table}({','.join(cols)}) VALUES ({','.join('?' * len(cols))})", [before[c] for c in cols])
        conn.execute("UPDATE map_history SET undone_at=? WHERE id=?", (now(), entry["id"]))


_todo = {}


def map_todo(t):
    """
    What's still wrong on the map, most used first: stops without a place, parts still drawn straight, and traces far
    longer than the way between their stops (loops, wrong roads). Cached for 10 minutes per timetable build.
    """
    key = int(t.built)
    if key in _todo and time.time() - _todo[key][0] < 600:
        return _todo[key][1]
    traced = timetable.edits.get("segments", {})
    today = (datetime.now(timetable.ZONE).date() - t.first_day).days
    uses, first_trip, unplaced = {}, {}, {}
    for i, trip in enumerate(t.trips):
        st = trip[7]
        for k, a in enumerate(st):
            if t.lat[a] is None:
                unplaced[a] = unplaced.get(a, 0) + 1
                first_trip.setdefault(("stop", a), i)
        for a, b in zip(st, st[1:]):
            if t.lat[a] is None or t.lat[b] is None:
                continue
            pair = (a, b)
            uses[pair] = uses.get(pair, 0) + 1
            first_trip.setdefault(pair, i)

    def trip_ref(i):
        trip = t.trips[i]
        days = t.services.get(trip[6], ())
        day = t.first_day + timedelta(days=min((d for d in days if d >= today), default=max(days, default=today)))
        return {"tripId": f"{trip[0]}:{trip[1]}:{day:%Y%m%d}", "line": trip[2], "operator": trip[4]}

    straight = sorted(((n, pair) for pair, n in uses.items() if f"{t.station_ids[pair[0]]}>{t.station_ids[pair[1]]}" not in traced), reverse=True)[:150]
    detours = []
    for seg, points in traced.items():
        if len(points) < 2 or ">" not in seg:
            continue
        a, b = (t.by_id.get(x) for x in seg.split(">", 1))
        if a is None or b is None or t.lat[a] is None or t.lat[b] is None:
            continue
        direct = timetable.meters(t.lat[a], t.lon[a], t.lat[b], t.lon[b])
        length = sum(timetable.meters(p[0], p[1], q[0], q[1]) for p, q in zip(points, points[1:]))
        if length > 3 * direct + 400:
            detours.append((round(length / max(direct, 1), 1), a, b, uses.get((a, b), 0), int(length)))
    detours.sort(key=lambda x: -x[3])
    result = {
        "unplaced": [dict(id=t.station_ids[s], name=t.names[s], uses=n, **trip_ref(first_trip[("stop", s)]))
                     for s, n in sorted(unplaced.items(), key=lambda kv: -kv[1])[:100]],
        "straight": [dict(**{"from": t.station_ids[a], "to": t.station_ids[b]}, fromName=t.names[a], toName=t.names[b], uses=n,
                          **trip_ref(first_trip[(a, b)])) for n, (a, b) in straight],
        "detours": [dict(**{"from": t.station_ids[a], "to": t.station_ids[b]}, fromName=t.names[a], toName=t.names[b], uses=n,
                         times=ratio, meters=length, **(trip_ref(first_trip[(a, b)]) if (a, b) in first_trip else {}))
                    for ratio, a, b, n, length in detours[:100]],
        "counts": {"unplaced": len(unplaced), "straight": sum(1 for pair in uses if f"{t.station_ids[pair[0]]}>{t.station_ids[pair[1]]}" not in traced),
                   "detours": len(detours)},
    }
    _todo.clear()
    _todo[key] = (time.time(), result)
    return result


bulk = {"running": False}


def bulk_snap(operator, username, limit=600):
    """
    Snaps an operator's parts still drawn straight, most used first, one a second (the public routers ask for that),
    at most [limit] a run. Progress and the result are in [bulk] for the dashboard.
    """
    t = timetable.current
    if t is None or bulk.get("running"):
        return
    traced = timetable.edits.get("segments", {})
    uses = {}
    for trip in t.trips:
        if trip[4] != operator:
            continue
        for a, b in zip(trip[7], trip[7][1:]):
            if t.lat[a] is not None and t.lat[b] is not None and f"{t.station_ids[a]}>{t.station_ids[b]}" not in traced:
                uses[(a, b)] = uses.get((a, b), 0) + 1
    todo = [pair for pair, _ in sorted(uses.items(), key=lambda kv: -kv[1])][:limit]
    bulk.clear()
    bulk.update(running=True, operator=operator, by=username, started=now(), total=len(todo), done=0, failed=0, remaining=len(uses))

    def run():
        try:
            for a, b in todo:
                if not bulk.get("running"):
                    break
                key = f"{t.station_ids[a]}>{t.station_ids[b]}"
                try:
                    points = snap_segment([t.lat[a], t.lon[a]], [t.lat[b], t.lon[b]], other_ends=[p[:2] for p in t.free_spots(b)],
                                          via=timetable.edits.get("vias", {}).get(key, ()))
                except Exception:  # noqa: BLE001 - one bad part doesn't stop the run
                    points = None
                if points:
                    with db() as conn:
                        conn.execute("""INSERT INTO map_segments(segment, points, source, updated_by, updated_at) VALUES (?,?,?,?,?)
                                        ON CONFLICT(segment) DO NOTHING""", (key, json.dumps(points), "osrm", f"{username} (bulk)", now()))
                    bulk["done"] += 1
                else:
                    bulk["failed"] += 1
                time.sleep(1)
            load_map_edits()
        finally:
            bulk.update(running=False, finished=now(), remaining=max(0, bulk["remaining"] - bulk["done"]))
    threading.Thread(target=run, daemon=True).start()


# ------------------------------------------------------------------ timetable builds: feed health and what changed

def build_summary(t):
    """What a build has, to compare with the next: each operator's line with its trips, and every stop."""
    lines = {}
    for trip in t.trips:
        key = f"{trip[4]}|{trip[2]}"
        lines[key] = lines.get(key, 0) + 1
    return {"lines": lines, "stops": {t.station_ids[s]: t.names[s] for s in range(len(t.names))}}


def build_changes(old, new):
    """Lines and stops that came or went, and lines with far fewer or more trips (30 % and at least 5)."""
    ol, nl = old["lines"], new["lines"]
    changed = [{"line": k, "before": ol[k], "after": nl[k]} for k in ol.keys() & nl.keys()
               if abs(nl[k] - ol[k]) >= max(5, 0.3 * ol[k])]
    return {
        "newLines": sorted(nl.keys() - ol.keys()), "goneLines": sorted(ol.keys() - nl.keys()),
        "changedLines": sorted(changed, key=lambda x: -abs(x["after"] - x["before"]))[:200],
        "newStops": sorted(new["stops"][s] for s in new["stops"].keys() - old["stops"].keys())[:500],
        "goneStops": sorted(old["stops"][s] for s in old["stops"].keys() - new["stops"].keys())[:500],
    }


def record_build(t):
    """After each timetable build: each feed's trips today, what changed since the last build, an email when a feed broke."""
    day = datetime.now(timetable.ZONE).date().isoformat()
    alerts = []
    with db() as conn:
        for feed, trips in t.feeds.items():
            result = (timetable.downloads.get(feed) or {}).get("result", "")
            failed = trips == "failed"
            prev = conn.execute("SELECT trips FROM feed_history WHERE feed=? AND day<? AND trips IS NOT NULL ORDER BY day DESC LIMIT 1",
                                (feed, day)).fetchone()
            dropped = not failed and prev and prev["trips"] >= 20 and trips < 0.7 * prev["trips"]
            row = conn.execute("SELECT alerted FROM feed_history WHERE day=? AND feed=?", (day, feed)).fetchone()
            conn.execute("""INSERT INTO feed_history(day, feed, trips, result) VALUES (?,?,?,?)
                            ON CONFLICT(day, feed) DO UPDATE SET trips=excluded.trips, result=excluded.result""",
                         (day, feed, None if failed else trips, result[:200]))
            if (failed or dropped) and not (row and row["alerted"]):
                alerts.append(f"{feed}: " + ("could not be read" if failed else f"{trips} trips, was {prev['trips']}"))
                conn.execute("UPDATE feed_history SET alerted=1 WHERE day=? AND feed=?", (day, feed))
        summary = build_summary(t)
        last = conn.execute("SELECT data FROM build_snapshots ORDER BY built DESC LIMIT 1").fetchone()
        changes = build_changes(json.loads(last["data"]), summary) if last else {}
        conn.execute("INSERT OR REPLACE INTO build_snapshots(built, data, changes) VALUES (?,?,?)",
                     (int(t.built), json.dumps(summary), json.dumps(changes)))
        conn.execute("DELETE FROM build_snapshots WHERE built NOT IN (SELECT built FROM build_snapshots ORDER BY built DESC LIMIT 15)")
        conn.execute("DELETE FROM feed_history WHERE day < ?", ((datetime.now(timetable.ZONE).date() - timedelta(days=90)).isoformat(),))
    if alerts:
        to = setting("admin_email") or ",".join(r["email"] for r in db().execute("SELECT email FROM users WHERE email<>''"))
        body = "<p>A timetable feed looks broken after today's update:</p><ul>" + "".join(f"<li>{html.escape(a)}</li>" for a in alerts) + \
               f"</ul><p><a href=\"{PUBLIC_URL}/dashboard#timetable\">Open the timetable in emHUB</a></p>"
        queue_mail(to, "emHUB: a timetable feed looks broken", body)


timetable.on_loaded.append(record_build)


# ------------------------------------------------------------------ service notices and riders' statistics

def clean_notice(data):
    """A notice as the dashboard sends it, checked: Slovak and/or English text, optional lines, stops and time window."""
    out = {"level": data.get("level") if data.get("level") in ("info", "warning") else "info"}
    for k in ("title_sk", "title_en"):
        out[k] = one_line(data.get(k), 120)
    for k in ("body_sk", "body_en"):
        out[k] = clean(data.get(k), 2000)
    if not (out["title_sk"] or out["title_en"]):
        raise ValueError("A notice needs a title in Slovak or English")
    out["lines"] = ",".join(dict.fromkeys(x.strip() for x in re.split(r"[,\s]+", str(data.get("lines") or "")) if x.strip()))[:300]
    out["stops"] = clean(str(data.get("stops") or ""), 1000)
    for k in ("starts_at", "ends_at"):
        v = data.get(k)
        out[k] = int(v) if isinstance(v, (int, float)) and v > 0 else None
    if out["starts_at"] and out["ends_at"] and out["ends_at"] <= out["starts_at"]:
        raise ValueError("A notice has to end after it starts")
    return out


def active_notices(conn, at=None):
    """Notices showing now: started (or no start) and not ended (or no end)."""
    at = at or now()
    rows = conn.execute("""SELECT id, level, title_sk, title_en, body_sk, body_en, lines, stops, starts_at, ends_at, updated_at FROM notices
                           WHERE (starts_at IS NULL OR starts_at<=?) AND (ends_at IS NULL OR ends_at>?) ORDER BY level='warning' DESC, id DESC""",
                        (at, at)).fetchall()
    return [dict(r, lines=[x for x in r["lines"].split(",") if x], stops=[x.strip() for x in r["stops"].split(";") if x.strip()]) for r in rows]


def rider_stats(conn, days=30):
    """
    Per line, from riders' reports of the last [days]: trips reported, the usual delay (each trip's last report), how many
    were 3+ minutes late, by hour of the planned departure; and how full riders said the buses were.
    """
    since = now() - days * 86400
    last = conn.execute("""SELECT line, trip_key, delay_s, scheduled_ms FROM community_reports c
                           WHERE created_at>=? AND id=(SELECT MAX(id) FROM community_reports WHERE trip_key=c.trip_key)""", (since,)).fetchall()
    lines = {}
    for r in last:
        x = lines.setdefault(r["line"] or "?", {"line": r["line"] or "?", "trips": 0, "late": 0, "sum": 0, "hours": [[0, 0] for _ in range(24)]})
        x["trips"] += 1
        x["sum"] += r["delay_s"]
        x["late"] += r["delay_s"] >= 180
        if r["scheduled_ms"]:
            h = datetime.fromtimestamp(r["scheduled_ms"] / 1000, timetable.ZONE).hour
            x["hours"][h][0] += 1
            x["hours"][h][1] += r["delay_s"]
    delays = sorted(({"line": x["line"], "trips": x["trips"], "avgDelay": round(x["sum"] / x["trips"]), "lateShare": round(x["late"] / x["trips"], 2),
                      "byHour": [round(s_ / n) if n else None for n, s_ in x["hours"]]} for x in lines.values()),
                    key=lambda x: (-x["avgDelay"], -x["trips"]))
    crowd = {}
    for r in conn.execute("SELECT trip_key, level FROM crowd_reports WHERE created_at>=?", (since,)):
        line = r["trip_key"].split("|")[0] if not r["trip_key"].startswith("sadzv|") else r["trip_key"].split("|")[4] if r["trip_key"].count("|") >= 4 else "?"
        c = crowd.setdefault(line, [0, 0, 0])
        c[r["level"] - 1] += 1
    return {"days": days, "delays": delays[:200],
            "crowd": sorted(({"line": k, "seats": v[0], "standing": v[1], "full": v[2]} for k, v in crowd.items()), key=lambda x: -(x["full"] * 2 + x["standing"]))[:100]}


def audit(username, action):
    """One line in the admin activity log; the oldest go after 20 000."""
    with db() as conn:
        conn.execute("INSERT INTO audit_log(created_at, username, action) VALUES (?,?,?)", (now(), username, action[:200]))
        conn.execute("DELETE FROM audit_log WHERE id <= (SELECT MAX(id) FROM audit_log) - 20000")


class Etas:
    """Live arrival times shared as a link (2.17.0). In memory: they matter for a few hours only."""
    KEEP_S = 6 * 3600
    MAX = 20_000  # a flood can't fill the memory: past this, new links are refused until old ones expire

    def __init__(self):
        self.items, self.lock = {}, threading.Lock()

    def create(self, label, stop):
        with self.lock:
            t = now()
            for key in [k for k, v in self.items.items() if t - v["created"] > self.KEEP_S]:
                del self.items[key]
            if len(self.items) >= self.MAX:
                return None
            item = dict(id=secrets.token_urlsafe(9), token=secrets.token_urlsafe(24), label=label, stop=stop, created=t,
                        updated=0, arrivalMs=0, delaySeconds=0, line="", ended=False)
            self.items[item["id"]] = item
            return item

    def owned(self, item_id, token):
        with self.lock:
            item = self.items.get(item_id)
            return item if item and hmac.compare_digest(item["token"], str(token or "")) else None

    def get(self, item_id):
        with self.lock:
            item = self.items.get(item_id)
            return dict(item) if item and now() - item["created"] <= self.KEEP_S else None

    def end(self, item_id):
        with self.lock:
            if item_id in self.items:
                self.items[item_id]["ended"] = True


etas = Etas()


def eta_page(item):
    """The shared arrival as a small page that refreshes itself: no app, no account."""
    head = ('<!doctype html><html lang="sk"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">'
            '<meta name="robots" content="noindex"><title>Plan&amp;Ride · arrival</title>'
            '<style>:root{color-scheme:light dark;--bg:#F4F6FA;--card:#fff;--text:#0E1726;--muted:#5B6678;--accent:#1D4FB8}'
            '@media (prefers-color-scheme:dark){:root{--bg:#0B0F14;--card:#141B24;--text:#E8EDF5;--muted:#93A0B4;--accent:#8AB4FF}}'
            'body{margin:0;min-height:100vh;display:grid;place-items:center;background:var(--bg);color:var(--text);'
            'font:16px/1.4 system-ui,-apple-system,"Segoe UI",sans-serif}main{background:var(--card);border-radius:24px;'
            'padding:28px 26px;margin:16px;max-width:380px;width:calc(100% - 32px);box-shadow:0 10px 40px rgba(0,0,0,.12)}'
            '.k{color:var(--muted);font-size:14px;margin:0}.t{font-size:56px;font-weight:800;letter-spacing:-.02em;margin:6px 0;'
            'color:var(--accent)}.s{margin:4px 0 0}.f{color:var(--muted);font-size:12px;margin-top:18px}</style>')
    if not item:
        return head + "<main><p class=t style='font-size:28px'>Not shared any more</p><p class=s>This arrival link has ended.</p></main>"
    if item["ended"]:
        return head + f"<main><p class=k>{html.escape(item['label'])}</p><p class=t style='font-size:30px'>Arrived</p></main>"
    if not item["arrivalMs"]:
        return head + (f"<meta http-equiv=refresh content=20><main><p class=k>{html.escape(item['label'])} is on the way to "
                       f"{html.escape(item['stop'])}</p><p class=t style='font-size:28px'>Waiting for the bus…</p></main>")
    at = datetime.fromtimestamp(item["arrivalMs"] / 1000, LOCAL_ZONE)
    minutes = max(0, round((item["arrivalMs"] / 1000 - time.time()) / 60))
    late = item["delaySeconds"] // 60
    status = f"{late} min late" if late >= 2 else "on time" if late > -2 else f"{-late} min early"
    return head + (f"<meta http-equiv=refresh content=20><main><p class=k>{html.escape(item['label'])} arrives at "
                   f"{html.escape(item['stop'])}</p><p class=t>{at:%H:%M}</p><p class=s>in {minutes} min · line "
                   f"{html.escape(item['line'])} · {status}</p><p class=f>Updated {max(0, (now() - item['updated']) // 60)} min ago"
                   f" · Plan&amp;Ride</p></main>")


_weather = {}


def weather_at(lat, lon):
    """2.17.0: rain in the next 3 hours around a town (Open-Meteo, asked by emHUB, cached 30 min per ~10 km)."""
    key = (round(lat, 1), round(lon, 1))
    hit = _weather.get(key)
    if hit and hit[0] > time.time():
        return hit[1]
    try:
        data = http_json(f"https://api.open-meteo.com/v1/forecast?latitude={key[0]}&longitude={key[1]}"
                         "&hourly=precipitation,precipitation_probability&forecast_hours=3&timezone=Europe%2FBratislava")
    except ProviderError:
        return None
    hourly = data.get("hourly") or {}
    rain = round(sum(x or 0 for x in hourly.get("precipitation") or []), 1)
    chance = max([x or 0 for x in hourly.get("precipitation_probability") or []] or [0])
    result = {"rainMm": rain, "chance": chance, "rainy": rain >= 0.5 or chance >= 70}
    if len(_weather) > 2000:
        _weather.clear()
    _weather[key] = (time.time() + 1800, result)
    return result


# 2.17.0: offline timetables by region (Slovakia's regions, roughly; a stop near a border is in both).
OFFLINE_REGIONS = {
    "ba": ("Bratislavský kraj", (47.95, 16.83, 48.40, 17.40)),
    "tt": ("Trnavský kraj", (47.75, 17.10, 48.80, 17.95)),
    "tn": ("Trenčiansky kraj", (48.45, 17.55, 49.30, 18.75)),
    "nr": ("Nitriansky kraj", (47.73, 17.75, 48.60, 18.95)),
    "za": ("Žilinský kraj", (48.85, 18.25, 49.62, 19.95)),
    "bb": ("Banskobystrický kraj", (47.95, 18.70, 49.00, 20.35)),
    "po": ("Prešovský kraj", (48.75, 20.15, 49.62, 22.57)),
    "ke": ("Košický kraj", (48.35, 20.10, 49.05, 22.25)),
}
OFFLINE_DAYS = 21
_offline = {}


def offline_region(t, rid):
    """
    A region's timetable for the apps to keep (next 21 days): its stations and every trip with two or more stops in it
    (cut to those). Cached per timetable build; the version tells the apps when to download it again.
    """
    key = (rid, int(t.built))
    if key not in _offline:
        south, west, north, east = OFFLINE_REGIONS[rid][1]
        inside = [s for s in range(len(t.names)) if t.lat[s] is not None and south <= t.lat[s] <= north and west <= t.lon[s] <= east]
        index = {s: i for i, s in enumerate(inside)}
        today = (datetime.now(timetable.ZONE).date() - t.first_day).days
        trips = []
        for trip in t.trips:
            kept = [k for k, s in enumerate(trip[7]) if s in index]
            if len(kept) < 2:
                continue
            runs = t.services.get(trip[6], ())
            days = [d for d in range(OFFLINE_DAYS) if today + d in runs]
            if days:
                trips.append([trip[2], trip[3] or t.names[trip[7][-1]], trip[4], [index[trip[7][k]] for k in kept],
                              [trip[9][k] for k in kept], days])
        if len(_offline) > 20:
            _offline.clear()
        _offline[key] = {"region": rid, "version": int(t.built), "from": (datetime.now(timetable.ZONE).date()).isoformat(),
                         "stations": [[t.station_ids[s], t.names[s], round(t.lat[s], 5), round(t.lon[s], 5)] for s in inside],
                         "trips": trips}
    return _offline[key]


_rebuilding = threading.Lock()


def rebuild_timetable():
    """The timetable built again from the files already here (no download), in the background, one at a time: an
    approved stop or connection takes effect in about half a minute."""
    def run():
        if not _rebuilding.acquire(blocking=False):
            return
        try:
            timetable.load(GTFS_DIR, gtfs_feeds())
        except Exception:  # noqa: BLE001 - the timetable in use stays
            traceback.print_exc()
        finally:
            _rebuilding.release()
    threading.Thread(target=run, daemon=True).start()


def clean_lines(value):
    """Lines boarding at a place: "34, 60101" or a list -> ["34", "60101"]."""
    items = value if isinstance(value, list) else re.split(r"[,\s]+", str(value or ""))
    return [one_line(x, 12) for x in items if one_line(x, 12)][:200]


def clean_directions(p):
    """
    A place's or sub-station's directions [{"name", "lines", "towards"}]: which lines leave from it, and the towns they
    head for (a bus that ends there or passes it later). One without directions keeps its lines as its only one.
    """
    out = []
    for d in (p.get("directions") or [])[:20]:
        lines = clean_lines(d.get("lines"))
        # Towns or stops it heads for, separated by ";" (a stop's name has commas: "Zvolen, Západ").
        towards = [one_line(x, 60) for x in (d.get("towards") if isinstance(d.get("towards"), list) else str(d.get("towards") or "").split(";"))]
        towards = [x for x in towards if x][:30]
        if lines or towards:
            out.append({"name": one_line(d.get("name"), 60), "lines": lines, "towards": towards})
    return out


def platform_fields(p):
    """Label, platform number, directions, and all their lines (what older apps and the map's tooltips read)."""
    directions = clean_directions(p)
    lines = list(dict.fromkeys(x for d in directions for x in d["lines"])) if directions else clean_lines(p.get("lines"))
    return {"label": one_line(p.get("label"), 60), "platform": one_line(p.get("platform"), 12), "lines": lines, "directions": directions}


def clean_place(p):
    lat, lon = float(p["lat"]), float(p["lon"])
    if not (47 < lat < 51 and 16 < lon < 23.5):
        raise ValueError("A place is outside Slovakia")
    return {"lat": round(lat, 6), "lon": round(lon, 6), **platform_fields(p)}


def clean_zone(z):
    """None, a circle {"type": "circle", "lat", "lon", "radius" (m)} or a drawn shape {"type": "polygon", "points"}."""
    if not z:
        return None
    if z.get("type") == "circle":
        center = clean_place(z)
        return {"type": "circle", "lat": center["lat"], "lon": center["lon"], "radius": max(10, min(3000, int(z.get("radius") or 100)))}
    points = [[clean_place({"lat": p[0], "lon": p[1]})[k] for k in ("lat", "lon")] for p in (z.get("points") or [])][:300]
    if len(points) < 3:
        raise ValueError("A zone needs at least 3 points")
    return {"type": "polygon", "points": points}


OSRM_URL = ENV("HUB_OSRM_URL", "https://router.project-osrm.org")
# Valhalla's bus profile knows bus-only roads and lanes (a bus station's own exit), which OSRM's car profile doesn't:
# it goes round the block instead. "off" leaves only OSRM.
VALHALLA_URL = ENV("HUB_VALHALLA_URL", "https://valhalla1.openstreetmap.de")


_drives = {}  # (from, to) -> (valid until, seconds)


def drive_seconds(a, b):
    """Seconds by car from a to b along the roads (OSRM), or None; the same pair is asked once in 10 min."""
    hit = _drives.get((a, b))
    if hit and hit[0] > time.time():
        return hit[1]
    try:
        route = (http_json(f"{OSRM_URL}/route/v1/driving/{a[1]},{a[0]};{b[1]},{b[0]}?overview=false").get("routes") or [None])[0]
    except ProviderError:
        return None
    seconds = round(route["duration"]) if route else None
    if len(_drives) > 5000:
        _drives.clear()
    _drives[(a, b)] = (time.time() + 600, seconds)
    return seconds


def polyline6(text):
    """Valhalla's encoded shape: [[lat, lon]]."""
    out, index, lat, lon = [], 0, 0, 0
    while index < len(text):
        for which in (0, 1):
            shift = result = 0
            while True:
                c = ord(text[index]) - 63
                index += 1
                result |= (c & 0x1f) << shift
                shift += 5
                if c < 0x20:
                    break
            delta = ~(result >> 1) if result & 1 else result >> 1
            if which == 0:
                lat += delta
            else:
                lon += delta
        out.append([round(lat / 1e6, 6), round(lon / 1e6, 6)])
    return out


def valhalla_route(a, b, curb=True, via=()):
    """Like osrm_route, by bus. With `curb`, the bus leaves and arrives on the stop's side of the road; it passes `via`
    (checkpoints set in the map editor) in order."""
    side = {"preferred_side": "same"} if curb else {}
    body = {"locations": [{"lat": a[0], "lon": a[1], "type": "break", **side},
                          *({"lat": p[0], "lon": p[1], "type": "through"} for p in via),
                          {"lat": b[0], "lon": b[1], "type": "break", **side}],
            "costing": "bus", "directions_type": "none"}
    trip = http_json(VALHALLA_URL.rstrip("/") + "/route", json.dumps(body).encode(), {"Content-Type": "application/json"}).get("trip") or {}
    legs = trip.get("legs") or []
    if not legs:
        return None
    return polyline6(legs[0]["shape"])[1:-1], float((legs[0].get("summary") or {}).get("length") or 0) * 1000


def road(a, b, curb=True, via=()):
    """The way by bus from Valhalla, else by car from OSRM, through `via` in order: (points without the ends, metres)
    or None."""
    if VALHALLA_URL != "off":
        try:
            route = valhalla_route(a, b, curb, via)
            if route:
                return route
        except ProviderError:
            pass  # e.g. too busy: OSRM instead
    return osrm_route(a, b, curb, via)


def osrm_route(a, b, curb=True, via=()):
    """
    The road from a to b ([lat, lon] each) from OSRM: (points without the ends, metres), or None. With `curb`, the bus
    leaves and arrives on the kerb side of the road (the stop's side), so on a dual carriageway it starts on the right one.
    It passes `via` in order.
    """
    points = [a, *via, b]
    url = f"{OSRM_URL}/route/v1/driving/{';'.join(f'{p[1]},{p[0]}' for p in points)}?overview=full&geometries=geojson"
    sides = ";".join(["curb", *["unrestricted"] * len(via), "curb"])
    route = (http_json(url + (f"&approaches={sides}" if curb else "")).get("routes") or [None])[0]
    if not route:
        return None
    coords = [[round(lat, 6), round(lon, 6)] for lon, lat in route["geometry"]["coordinates"]]
    return coords[1:-1], float(route.get("distance") or 0)


def u_turns(points, window=20.0):
    """How often a way turns back on itself (more than 120 degrees within about 20 m each side): a bus between two stops doesn't."""
    run = [0.0]
    for p, q in zip(points, points[1:]):
        run.append(run[-1] + timetable.meters(p[0], p[1], q[0], q[1]))

    def heading(a, b):
        return math.atan2((b[1] - a[1]) * math.cos(math.radians(a[0])), b[0] - a[0])
    count, i = 0, 1
    while i < len(points) - 1:
        a = max((k for k in range(i) if run[i] - run[k] >= window), default=0)
        b = next((k for k in range(i + 1, len(points)) if run[k] - run[i] >= window), len(points) - 1)
        if run[i] - run[a] >= 8 and run[b] - run[i] >= 8:
            turn = abs((heading(points[i], points[b]) - heading(points[a], points[i]) + math.pi) % (2 * math.pi) - math.pi)
            if turn > math.radians(120):
                count, i = count + 1, b
                continue
        i += 1
    return count


def snap_segment(a, b, pause=1.0, other_ends=(), via=()):
    """
    The road between two stops, from the bus's spot at a to the spot of b it goes to (both included), or None. From the bus's own platform at a: leaving and arriving on
    the kerb side, and the plain way. The kerb rule keeps a bus on its side of a dual carriageway, but where a stop's
    point is on the other side it loops round to reach it (line 1, Partizanska cesta in Banska Bystrica). Each turn back
    counts as 300 m; the kerb way wins a near tie. other_ends: b's other free spots; the one reached most directly wins
    (the trip then stops there: Timetable.traced_spot). `pause`: the routing servers ask for one request a second.
    """
    tried = [(snap_one(a, end, pause, via), end) for end in [tuple(b), *map(tuple, other_ends)]]
    tried = [(way, end) for way, end in tried if way]
    if not tried:
        return None
    way, end = min(tried, key=lambda x: x[0][1] + 300 * u_turns([tuple(a), *x[0][0], x[1]]))
    # The trace starts and ends exactly at the spots it was made for: which of two close places a trip stops at is then
    # known, not guessed from the road's last point (Okresny urad: 25 m apart, the road's end nearer the wrong one).
    return [[a[0], a[1]], *way[0], [end[0], end[1]]]


def through_checkpoints(a, b, via, pause):
    """
    The way through checkpoints set in the editor, stretch by stretch (stop to checkpoint, checkpoint to checkpoint, on
    to the stop), so one turn the map data doesn't allow can't send the whole way round the block. A stretch the router
    can only do the long way (more than twice the distance: line 5 north of the Nove Kaliste roundabout) is drawn
    straight between the checkpoints: they say where the bus goes. (points without the ends, metres).
    """
    chain, points, total = [tuple(a), *via, tuple(b)], [], 0.0
    for i, (p, q) in enumerate(zip(chain, chain[1:])):
        straight = timetable.meters(p[0], p[1], q[0], q[1])
        try:
            leg = road(p, q, curb=False)
        except ProviderError:
            leg = None
        if not leg or leg[1] > straight * 2 + 150:
            leg = ([], straight)
        if i:
            points.append([p[0], p[1]])
        points += leg[0]
        total += leg[1]
        time.sleep(pause)
    return points, total


def snap_one(a, b, pause, via=()):
    """The best way from a to b (through `via`): (points, metres), or None (no way, or a way three times the distance)."""
    if via:
        return through_checkpoints(a, b, [tuple(p) for p in via], pause)
    kerb = road(a, b)
    time.sleep(pause)
    plain = road(a, b, curb=False)
    if not kerb or not plain:
        kerb = plain = kerb or plain
        if not kerb:
            return None

    def cost(route):
        return route[1] + 300 * u_turns([tuple(a), *route[0], tuple(b)])
    best = plain if cost(plain) < cost(kerb) * 0.9 - 50 else kerb
    # A way three times the distance (4.8 km for 0.9 km into Zvolen AS) is the router lost, not the bus: none.
    chain = [tuple(a), *via, tuple(b)]
    if best[1] > sum(timetable.meters(p[0], p[1], q[0], q[1]) for p, q in zip(chain, chain[1:])) * 3 + 1000:
        return None
    return best


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
submission_limiter = RateLimiter(20, 60 * 60)  # per IP: missing stops and connections sent from the apps
eta_limiter = RateLimiter(30, 60 * 60)  # shared arrival links per IP an hour
report_limiter = RateLimiter(1200, 60 * 60)  # per IP: phones on mobile data share addresses
reporter_limiter = RateLimiter(300, 60 * 60)  # per rider: GPS every 15 s is 240 an hour
timetable_limiter = RateLimiter(2000, 60 * 60)  # per IP: a planner search is a few requests
share_limiter = RateLimiter(30, 60 * 60)  # per IP: new share codes
claim_limiter = RateLimiter(12, 15 * 60)  # per IP: codes tried (6 digits: guessing one takes ~40 000 windows)


class Shares:
    """
    Sharing a bus by a one-time code. The server makes a 6-digit code (valid CODE_S) the sharer tells someone; that
    person enters it with their name; the sharer sees the name and accepts or declines; only then does the other side
    get the bus, and from then on each side may send where it is and sees the other's last position (POSITION_S fresh).
    Each side holds a random token for its half. ponytail: in memory (a restart ends shares), one process.
    """
    CODE_S = 10 * 60
    POSITION_S = 5 * 60
    KEEP_S = 6 * 3600  # answered shares are forgotten after this

    def __init__(self):
        self.items = {}  # id -> dict
        self.lock = threading.Lock()
        self.sockets = {}  # share id -> {"owner": set(WebSocket), "claim": set(WebSocket)}

    def attach(self, share_id, side, ws):
        with self.lock:
            self.sockets.setdefault(share_id, {"owner": set(), "claim": set()})[side].add(ws)

    def detach(self, share_id, side, ws):
        with self.lock:
            self.sockets.get(share_id, {}).get(side, set()).discard(ws)

    def push(self, share, side, message):
        """A message to every open connection of one side ("owner" / "claim") of a share."""
        with self.lock:
            targets = list(self.sockets.get(share["id"], {}).get(side, ()))
        for ws in targets:
            ws.send_json(message)

    def changed(self, share):
        """Status news to both sides: someone entered the code (the sharer gets the name), accepted, declined, ended."""
        self.push(share, "owner", {"type": "status", "status": share["status"], "name": share["name"]})
        body = {"type": "status", "status": share["status"], "label": share["label"]}
        if share["status"] == "accepted":
            body["trip"] = share["trip"]
        self.push(share, "claim", body)

    def _sweep(self, t):
        for key in [k for k, v in self.items.items() if t - v["created"] > self.KEEP_S or (v["status"] == "waiting" and t > v["expires"])]:
            del self.items[key]

    def create(self, trip, label):
        t = now()
        with self.lock:
            self._sweep(t)
            active = {v["code"] for v in self.items.values() if v["status"] == "waiting"}
            code = next(c for c in (f"{secrets.randbelow(1_000_000):06d}" for _ in range(100)) if c not in active)
            # pos/claimPos: where each side last was (only after the sharer accepted; overwritten, never kept).
            share = dict(id=secrets.token_urlsafe(12), code=code, owner=secrets.token_urlsafe(24), trip=trip, label=label,
                         created=t, expires=t + self.CODE_S, status="waiting", name="", claim=None, claimToken=None,
                         pos=None, claimPos=None)
            self.items[share["id"]] = share
            return share

    def claim(self, code, name):
        t = now()
        with self.lock:
            self._sweep(t)
            share = next((v for v in self.items.values() if v["code"] == code and v["status"] == "waiting" and t <= v["expires"]), None)
            if not share:
                return None
            # One use: the code is spent, whatever the sharer answers.
            share.update(status="asked", name=name, claim=secrets.token_urlsafe(12), claimToken=secrets.token_urlsafe(24))
        self.changed(share)
        return share

    def owned(self, share_id, token):
        with self.lock:
            share = self.items.get(share_id)
            return share if share and hmac.compare_digest(share["owner"], token or "") else None

    def claimed(self, claim_id, token):
        with self.lock:
            share = next((v for v in self.items.values() if v["claim"] == claim_id), None)
            return share if share and hmac.compare_digest(share["claimToken"] or "", token or "") else None


shares = Shares()


class WebSocket:
    """
    A server-side WebSocket (RFC 6455) on a request's socket: text frames only, pings answered, our own pings when
    quiet. ponytail: one thread per connection (ThreadingHTTPServer); fine for the few shares open at a time.
    """
    GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
    IDLE_S = 30

    def __init__(self, sock):
        self.sock, self.lock, self.open = sock, threading.Lock(), True

    @classmethod
    def accept(cls, handler):
        key = handler.headers["Sec-WebSocket-Key"].strip()
        accept = base64.b64encode(hashlib.sha1((key + cls.GUID).encode()).digest()).decode()
        handler.send_response(101, "Switching Protocols")
        handler.send_header("Upgrade", "websocket")
        handler.send_header("Connection", "Upgrade")
        handler.send_header("Sec-WebSocket-Accept", accept)
        handler.end_headers()
        handler.wfile.flush()
        sock = handler.connection
        sock.settimeout(cls.IDLE_S)
        return cls(sock)

    def _send(self, opcode, payload=b""):
        header = bytes([0x80 | opcode])
        n = len(payload)
        header += bytes([n]) if n < 126 else bytes([126]) + struct.pack(">H", n) if n < 65536 else bytes([127]) + struct.pack(">Q", n)
        with self.lock:
            if not self.open:
                return
            try:
                self.sock.sendall(header + payload)
            except OSError:
                self.open = False

    def send_json(self, data):
        self._send(1, json.dumps(data).encode())

    def _read(self, n):
        data = b""
        while len(data) < n:
            chunk = self.sock.recv(n - len(data))
            if not chunk:
                raise ConnectionError("closed")
            data += chunk
        return data

    def messages(self):
        """Text messages from the client as JSON objects, until it closes (or goes quiet past a ping)."""
        waiting_pong = False
        while self.open:
            try:
                b1, b2 = self._read(2)
            except socket.timeout:
                if waiting_pong:
                    break
                waiting_pong = True
                self._send(9)  # ping: anyone still there?
                continue
            except (ConnectionError, OSError):
                break
            waiting_pong = False
            opcode, n = b1 & 0x0F, b2 & 0x7F
            try:
                if n == 126:
                    n = struct.unpack(">H", self._read(2))[0]
                elif n == 127:
                    n = struct.unpack(">Q", self._read(8))[0]
                if n > 64_000:
                    break
                mask = self._read(4) if b2 & 0x80 else b"\0\0\0\0"
                payload = bytes(c ^ mask[i % 4] for i, c in enumerate(self._read(n)))
            except (ConnectionError, OSError):
                break
            if opcode == 8:
                self._send(8)
                break
            if opcode == 9:
                self._send(10, payload)
            elif opcode == 1:
                try:
                    message = json.loads(payload.decode("utf-8"))
                except (ValueError, UnicodeDecodeError):
                    continue
                if isinstance(message, dict):
                    yield message
        self.open = False


class MapRoom:
    """
    Everyone with the dashboard's map open, each over a WebSocket: who is on which stop or trip, and every edit pushed
    to the others as it's saved. A stop someone else has open can't be saved (two people overwriting each other).
    """
    def __init__(self):
        self.lock = threading.Lock()
        self.people = {}  # WebSocket -> {"name", "tab", "station", "trip"}

    def join(self, ws, name, tab):
        with self.lock:
            self.people[ws] = {"name": name, "tab": tab, "station": None, "trip": None}

    def leave(self, ws):
        with self.lock:
            self.people.pop(ws, None)

    def focus(self, ws, station, trip):
        with self.lock:
            if ws in self.people:
                self.people[ws].update(station=station, trip=trip)

    def editors(self):
        with self.lock:
            return [dict(p) for p in self.people.values()]

    def busy(self, station, tab):
        """The name of someone else (another tab) who has the stop open, or None."""
        with self.lock:
            return next((p["name"] for p in self.people.values() if p["station"] == station and p["tab"] != tab), None)

    def push(self, message, skip_tab=None):
        with self.lock:
            targets = [ws for ws, p in self.people.items() if p["tab"] != skip_tab]
        for ws in targets:
            ws.send_json(message)

    def presence(self):
        self.push({"type": "presence", "editors": self.editors()})


map_room = MapRoom()


def position_of(data):
    """A position sent by one side of a share: checked numbers only."""
    lat, lon = float(data.get("lat")), float(data.get("lon"))
    if not (-90 <= lat <= 90 and -180 <= lon <= 180):
        raise ValueError("Bad position")
    return {"lat": round(lat, 6), "lon": round(lon, 6), "accuracy": min(10_000, max(0, int(data.get("accuracy") or 0))), "at": now()}


def fresh_position(pos):
    return pos if pos and now() - pos["at"] <= Shares.POSITION_S else None


# ------------------------------------------------------------------ email

# Editable in the dashboard. Placeholders: {{x}} is escaped text, {{{x}}} is prepared HTML.
TEMPLATES = {
    "admin_new_bug": ("New bug (to admins)", ["bug_id", "title", "description_html", "name", "email", "category", "platform", "app_version", "device", "logs_lines", "created", "dashboard_url"]),
    "user_ack": ("Report received (to reporter)", ["bug_id", "title", "description_html", "name", "platform", "app_version", "created"]),
    "user_reply": ("Admin reply (to reporter)", ["bug_id", "title", "name", "reply_html", "agent", "status_label"]),
    "user_status": ("Status changed (to reporter)", ["bug_id", "title", "name", "status_label", "created"]),
    "user_fixed": ("Fixed in a released version (to reporter)", ["bug_id", "title", "name", "version", "created"]),
    "user_ack.sk": ("Report received (to reporter, Slovak)", ["bug_id", "title", "description_html", "name", "platform", "app_version", "created"]),
    "user_reply.sk": ("Admin reply (to reporter, Slovak)", ["bug_id", "title", "name", "reply_html", "agent", "status_label"]),
    "user_status.sk": ("Status changed (to reporter, Slovak)", ["bug_id", "title", "name", "status_label", "created"]),
    "user_fixed.sk": ("Fixed in a released version (to reporter, Slovak)", ["bug_id", "title", "name", "version", "created"]),
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


STATUS_SK = {"new": "nové", "triaged": "posúdené", "in_progress": "rieši sa", "awaiting_release": "opravené, čaká na aktualizáciu",
             "resolved": "vyriešené", "closed": "uzavreté"}


def bug_lang(bug):
    """The reporter's language: "sk" or "en" (English only when the app or web said so, or the text clearly is)."""
    return "en" if (bug["lang"] if "lang" in bug.keys() else "") == "en" else "sk"


def user_template(name, bug):
    """A reporter email in their language: user_ack.sk for Slovak reporters, user_ack for English ones."""
    return f"{name}.sk" if bug_lang(bug) == "sk" else name


def user_subject(kind, bug, **values):
    code, sk = bug_code(bug["id"]), bug_lang(bug) == "sk"
    return {
        "ack": f"[{code}] " + ("Vaše hlásenie sme dostali" if sk else "We got your report"),
        "reply": f"Re: [{code}] {bug['title']}",
        "status": f"[{code}] " + (f"Stav: {STATUS_SK.get(bug['status'], bug['status'])}" if sk else f"Status: {bug['status'].replace('_', ' ')}"),
        "fixed": f"[{code}] " + (f"Opravené vo verzii {values.get('version')}" if sk else f"Fixed in version {values.get('version')}"),
    }[kind]


def guess_lang(data, logs, text):
    """The app or web says its language; older versions only in the logs header ("language en"); else the text tells."""
    said = str(data.get("language") or "").lower()[:2]
    if said in ("sk", "en"):
        return said
    m = re.search(r"\blanguage (sk|en)\b", logs[:2000])
    if m:
        return m.group(1)
    if re.search(r"[áäčďéíĺľňóôŕšťúýž]", text.lower()):
        return "sk"
    words = set(re.findall(r"[a-z']+", text.lower()))
    english = {"the", "is", "are", "isn't", "it's", "there's", "and", "when", "with", "this", "that", "in", "of", "both",
               "same", "doesn't", "don't", "not", "app", "bus", "time", "notification", "from", "there", "it", "it's", "but"}
    return "en" if len(words & english) >= 2 else "sk"  # Slovak typed without diacritics has none of these


def bug_values(bug, **extra):
    values = dict(bug)
    sk = bug_lang(bug) == "sk"
    values.update(
        bug_id=bug_code(bug["id"]),
        name=bug["name"] or ("" if sk else "there"),
        created=time.strftime("%d.%m.%Y %H:%M", time.localtime(bug["created_at"])),
        status_label=STATUS_SK.get(bug["status"], bug["status"]) if sk else bug["status"].replace("_", " "),
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
           "app_version": "2.1.0", "device": "Google Pixel 8, Android 16", "logs": "a\nb\nc", "created_at": now(),
           "lang": "sk" if name.endswith(".sk") else "en"}
    values = bug_values(bug, reply_html=paragraphs("Thanks, Jana. A fix is in version 2.1.1.\n\nDoes it also happen on Wi-Fi?"),
                        agent=user["username"], version="2.1.1")
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
        "lang": lang, "page": name, "title": title, "description": description, "nav": nav, "nav_label": words["nav"],
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


def history_runs(conn, trip_key):
    """This trip's delay on each of its last runs on the same kind of day, {date: seconds}, today's left out."""
    run = trip_run(trip_key)
    if not run:
        return {}
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
    return {d: statistics.median([x for x, exact in r if exact] or [x for x, _ in r])
            for d, r in sorted(runs.items(), reverse=True)[:HISTORY_RUNS]}


def transfer_odds(conn, arriving_key, leaving_key, slack_s):
    """
    2.17.0: how often a change worked on earlier days: the first bus's delay against the next one's (its usual delay
    when it has no report that day) and the time between them. {"worked", "runs"}, or None with too few runs.
    """
    arriving = history_runs(conn, arriving_key)
    if len(arriving) < HISTORY_MIN_RUNS:
        return None
    leaving = history_runs(conn, leaving_key)
    usual = statistics.median(leaving.values()) if leaving else 0
    return {"worked": sum(a - leaving.get(d, usual) <= slack_s for d, a in arriving.items()), "runs": len(arriving)}


def crowd_now(conn, trip_key):
    """How full the bus is from riders on it: the newest report in the last 20 min, {"level": 1-3, "minutesAgo"}."""
    row = conn.execute("""SELECT level, created_at FROM crowd_reports WHERE trip_key=? AND created_at>=?
                          AND reporter NOT IN (SELECT reporter FROM blocked_reporters) ORDER BY created_at DESC LIMIT 1""",
                       (trip_key, now() - 20 * 60)).fetchone()
    return {"level": row["level"], "minutesAgo": (now() - row["created_at"]) // 60} if row else None


def trip_history(conn, trip_key):
    """How late this trip usually is: its last runs on the same kind of day, today's run left out."""
    runs = history_runs(conn, trip_key)
    if len(runs) < HISTORY_MIN_RUNS:
        return None
    recent = list(runs.values())
    return {
        "delaySeconds": int(statistics.median(recent)),
        "runs": len(recent),
        "lateRuns": sum(d >= HISTORY_LATE_S for d in recent),
        "dayType": day_type(trip_run(trip_key)[1]),
    }


def trip_delay(conn, trip_key):
    """Riders' phones on the bus (last 3 min) beat everything; otherwise all reports (45 min)."""
    rows = [dict(r) for r in conn.execute(
        """SELECT delay_s, created_at, reporter, stop_index, stop_name, kind FROM community_reports
           WHERE trip_key=? AND created_at>=? AND reporter NOT IN (SELECT reporter FROM blocked_reporters)""",
        (trip_key, now() - COMMUNITY_WINDOW_S),
    )]
    gps = [r for r in rows if r["kind"] in PHONE_KINDS and now() - r["created_at"] <= GPS_WINDOW_S]
    used = gps or rows
    result = aggregate_delay(used, now())
    if result:
        # "gps" when what it's made of came from phones on the bus (automatic mode), also once those reports are
        # older than 3 minutes (the bus between two far-apart stops); "riders" only when taps are in it. The apps
        # still rank it first only while it's fresh (they check updatedAt).
        result["source"] = "gps" if all(r["kind"] in PHONE_KINDS for r in used) else "riders"
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
# The dashboard also has the map: Leaflet from unpkg (pinned, with its hash) and OpenStreetMap's tiles.
CSP_DASHBOARD = CSP.replace("script-src 'self' 'unsafe-inline'", "script-src 'self' 'unsafe-inline' https://unpkg.com") \
    .replace("style-src 'self' 'unsafe-inline'", "style-src 'self' 'unsafe-inline' https://unpkg.com") \
    .replace("img-src 'self' data:", "img-src 'self' data: https://tile.openstreetmap.org https://unpkg.com")


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


def walk_leg(a, b, at_ms, arriving):
    """A walk between two stops 30 m or more apart (planner leg format), ending at at_ms (arriving) or
    starting then; None when they are the same place."""
    if a.get("lat") is None or b.get("lat") is None or a.get("name") == b.get("name"):
        return None  # the same stop (its platforms can be some way apart)
    d = timetable.meters(a["lat"], a["lon"], b["lat"], b["lon"])
    if d < 30:
        return None
    ms = (int(d / timetable.WALK_SPEED) + 60) * 1000
    start = at_ms - ms if arriving else at_ms
    return {"type": "walk", "from": {k: a[k] for k in ("id", "name", "lat", "lon") if k in a},
            "to": {k: b[k] for k in ("id", "name", "lat", "lon") if k in b}, "departure": start, "arrival": start + ms}


def add_transfer_odds(t, journeys):
    """2.17.0: each change gets how often it worked on earlier days ("transfer": {"worked", "runs"}), when known."""
    def key(leg):
        try:
            feed, rest = leg["tripId"].split(":", 1)
            tid, day = rest.rsplit(":", 1)
            index = t.trip_index[(feed, tid)]
            keys = t.community_keys(index, datetime.strptime(day, "%Y%m%d").date())
            return keys[-1]  # the sadzv key when it's a city bus (riders report under it), else the timetable one
        except (KeyError, ValueError, AttributeError):
            return None
    with db() as conn:
        for journey in journeys:
            legs = [leg for leg in journey["legs"] if leg.get("type") == "trip" and leg.get("tripId")]
            for a, b in zip(legs, legs[1:]):
                ka, kb = key(a), key(b)
                if ka and kb:
                    odds = transfer_odds(conn, ka, kb, (b["departure"] - a["arrival"]) // 1000)
                    if odds:
                        b["transfer"] = odds


# "Arrive by": how far before the wanted arrival connections are searched.
ARRIVE_WINDOW_S = 4 * 3600


def merge_live(journeys, live):
    """Timetable and live (sadzv) connections by departure; the live copy of the same bus wins, it can be followed live."""
    def same_bus(j, leg):
        legs = [x for x in j["legs"] if x["type"] == "trip"]
        a, b = (legs[0]["line"], leg["line"]) if legs else ("", "")
        return len(legs) == 1 and abs(legs[0]["departure"] - leg["departure"]) <= 60_000 and \
            (a == b or a.endswith(b) or b.endswith(a))
    bus = lambda j: next(x for x in j["legs"] if x["type"] == "trip")  # noqa: E731 - a live one may start with a walk
    kept = [j for j in journeys if not any(same_bus(j, bus(l)) for l in live)]
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
    if route == "weather":
        try:
            lat, lon = float(query.get("lat")), float(query.get("lon"))
        except (TypeError, ValueError):
            return 400, {"error": "lat and lon are required"}
        if not (47 < lat < 51 and 16 < lon < 23.5):
            return 400, {"error": "That place is outside Slovakia"}
        result = weather_at(lat, lon)
        return (200, result) if result else (502, {"error": "No weather right now"})
    if route == "offline":
        return 200, {"version": int(t.built), "regions": [{"id": k, "name": v[0], "bbox": v[1]} for k, v in OFFLINE_REGIONS.items()]}
    if route == "offline/region":
        rid = query.get("id")
        if rid not in OFFLINE_REGIONS:
            return 404, {"error": "No such region"}
        return 200, offline_region(t, rid)
    if route == "drive":
        # EM-0072: the drive from about where the rider is to their stop, along the roads at their speeds (OSRM's car
        # profile: town streets, 90 km/h roads, 130 km/h motorways). The apps send their position rounded to ~100 m.
        try:
            a = tuple(round(float(x), 3) for x in (query.get("from") or "").split(",")[:2])
            b = tuple(round(float(x), 4) for x in (query.get("to") or "").split(",")[:2])
        except ValueError:
            a = b = ()
        if len(a) != 2 or len(b) != 2 or not all(47 < p[0] < 51 and 16 < p[1] < 23.5 for p in (a, b)):
            return 400, {"error": "from and to: lat,lon in Slovakia"}
        seconds = drive_seconds(a, b)
        return (200, {"seconds": seconds}) if seconds is not None else (502, {"error": "No route by car"})
    if route == "path":
        # EM-0064: the roads between a trip's stops for the apps' maps, as drawn or snapped in the dashboard. Stops come
        # as positions (a live S.A.D. bus doesn't know the timetable's stations): of the stations near each, a pair with
        # a road trace between them. A hop without one is [] (a straight line).
        try:
            stops = [tuple(float(x) for x in p.split(",")[:2]) for p in (query.get("stops") or "").split(";") if p][:80]
        except ValueError:
            return 400, {"error": "stops: lat,lon;lat,lon;..."}
        traces = timetable.edits["segments"]
        near = [[s["id"] for s in t.near(lat, lon, 300, 3)] for lat, lon in stops]
        return 200, {"hops": [next((traces[f"{x}>{y}"] for x in a for y in b if f"{x}>{y}" in traces), [])
                              for a, b in zip(near, near[1:])]}
    if route == "trip":
        trip = t.trip(query.get("id") or "") if re.fullmatch(r"[^:]+:.+:\d{8}", query.get("id") or "") else None
        return (200, trip) if trip else (404, {"error": "No such trip"})
    if route == "plan":
        origin, target = t.resolve(query.get("from") or ""), t.resolve(query.get("to") or "")
        if origin is None or target is None:
            return 404, {"error": "Stop not found", "from": origin is not None, "to": target is not None}
        when, count = when_param(query.get("time")), limit(5, 10)
        # EM-0053, "arrive by": `time` is when the rider wants to be there. Connections are searched from a few hours
        # before it and only those arriving in time are kept: the ones that leave latest.
        arrive_by = when if query.get("arrive") in ("1", "true") else None
        if arrive_by is not None:
            when = arrive_by - ARRIVE_WINDOW_S
        found = count * 4 if arrive_by is not None else count
        # SAD Zvolen's live data is asked at the same time as the timetable is searched (about 1 s each).
        a = (t.lat[origin], t.lon[origin]) if t.lat[origin] is not None else sadzv.locate(t.names[origin], t.town_center(origin))
        b = (t.lat[target], t.lon[target]) if t.lat[target] is not None else sadzv.locate(t.names[target], t.town_center(target))
        name_of = lambda lat, lon: next((s["name"] for s in t.near(lat, lon, 120, 1)), None)  # noqa: E731
        live_job = LIVE_POOL.submit(sadzv.journeys, a, b, when, found, name_of) if a and b else None
        # change: the rider's least time between two buses in minutes (app setting; 0 = any), 1 by default.
        raw = str(query.get("change") or "")
        change = min(30, int(raw)) * 60 if raw.isdigit() else timetable.CHANGE_S
        journeys = t.plan(origin, target, when, found, query.get("direct") in ("1", "true"), change,
                          walk_start=query.get("walkstart") not in ("0", "false"))
        if live_job:
            # City buses aren't in the open timetables: SAD Zvolen's live data adds its direct buses.
            live = live_job.result()
            for j in live:
                # sadzv counts stops up to 250 m away as the searched ones: when the bus really leaves from (or
                # gets to) another stop nearby, say so with a short walk instead of renaming it (EM-0024).
                bus = j["legs"][0]
                before = walk_leg(t.station_json(origin), bus["from"], bus["departure"], arriving=True)
                after = walk_leg(bus["to"], t.station_json(target), bus["arrival"], arriving=False)
                j["legs"] = [leg for leg in (before, bus, after) if leg]
                j["departure"], j["arrival"] = j["legs"][0]["departure"], j["legs"][-1]["arrival"]
            journeys = merge_live(journeys, live)
        if arrive_by is not None:
            journeys = [j for j in journeys if j["arrival"] <= arrive_by * 1000][-count:]
        journeys = journeys[:count]
        add_transfer_odds(t, journeys)
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
        self._status = status
        hook = self.__dict__.pop("_before_send", None)
        if hook and status < 400:
            hook()
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
            self.send_header("Content-Security-Policy", (headers or {}).pop("Content-Security-Policy", CSP))
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
        if hasattr(self, "_json"):
            return self._json
        raw = self.read_body(limit)
        data = json.loads(raw or b"{}")
        self._json = data
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
        started = time.monotonic()
        try:
            self.route(method)
        finally:
            stream = "Upgrade" in self.headers or "text/event-stream" in (self.headers.get("Accept") or "")
            # Only answered routes: unknown ones (401/404) would let anyone grow the table.
            if not stream and (self._status < 400 or self._status >= 500):
                ms = (time.monotonic() - started) * 1000
                t = timings.setdefault(route_group(urllib.parse.urlsplit(self.path).path), [0, 0.0, 0.0])
                t[0] += 1
                t[1] += ms
                t[2] = max(t[2], ms)
                version = (self.headers.get("X-App-Version") or "")[:40]
                if version and (version in app_versions or len(app_versions) < 50):
                    app_versions[version] = app_versions.get(version, 0) + 1

    def route(self, method):
        self.__dict__.pop("_json", None)  # one connection serves many requests: never another request's body
        self.__dict__.pop("_before_send", None)
        self._status = 500
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
                route_ = path[len("/admin/api/"):]
                who = self.current_user()
                if method == "POST" and who:
                    targets = []
                    if route_.startswith("map/"):
                        try:
                            targets = history_targets(route_[4:], self.json_body())
                        except ValueError:
                            targets = []
                    before = [(target, map_row(*target)) for target in targets]

                    def record():
                        # Before the answer goes out, so the next request (the dashboard redrawing) already sees it.
                        audit(who["username"], route_)
                        for target, old in before:
                            new = map_row(*target)
                            if new != old:
                                with db() as conn:
                                    conn.execute("""INSERT INTO map_history(created_at, username, action, tbl, key_col, key, before, after)
                                                    VALUES (?,?,?,?,?,?,?,?)""", (now(), who["username"], route_[4:], *target,
                                                                                 json.dumps(old) if old else None, json.dumps(new) if new else None))
                    self._before_send = record
                return self.admin_api(method, route_, query)
            return self.pages(method, path, query)
        except (ValueError, json.JSONDecodeError) as error:
            self.error(400, str(error), cors=path.startswith("/api/"))
        except Exception:  # noqa: BLE001 - keep serving; log the trace
            traceback.print_exc()
            note_error(f"{method} {path}")
            self.error(500, "Server error", cors=path.startswith("/api/"))

    # ---- pages & auth

    def pages(self, method, path, query):
        page = site_route(path)
        if method == "GET" and page:
            return self.send(200, render_site(*page), "text/html")
        if method == "GET" and path.startswith("/eta/"):
            return self.send(200, eta_page(etas.get(path[len("/eta/"):])), "text/html")
        if method == "GET" and path == "/f":
            # A followed bus shared from the app: the web version opens it (the app's link stays the same if it moves).
            trip = query.get("t", "")
            web = setting("web_url").rstrip("/")
            if web and re.fullmatch(r"[A-Za-z0-9_-]{1,3000}", trip):
                return self.redirect(web + "/?follow=" + trip)
            return self.send(404, b"<!doctype html><meta charset=utf-8><title>Plan&amp;Ride</title>"
                             b"<p>This shared bus can't be opened here. Open it in Plan&amp;Ride.</p>", "text/html")
        if method == "GET" and path == "/mark.png":
            return self.static("mark.png", "image/png")
        if method == "GET" and path == "/login":
            # Already signed in (e.g. from the website's Admin link): straight to the dashboard.
            return self.redirect("/dashboard") if self.current_user() else self.static("login.html")
        if method == "GET" and path == "/dashboard":
            if not self.current_user():
                return self.redirect("/login")
            # Never from the browser's cache: a new version's buttons show on the next load.
            return self.send(200, (HERE / "static" / "dashboard.html").read_bytes(), "text/html",
                             {"Content-Security-Policy": CSP_DASHBOARD, "Cache-Control": "no-store"})
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
        if method == "GET" and route == "shares/live":
            return self.share_socket(query)
        given = self.headers.get("X-App-Key", "")
        if not any(hmac.compare_digest(given.encode(), k.encode()) for k in APP_KEYS):
            return self.error(401, "Missing or wrong app key", cors=True)
        if method == "GET" and route.startswith("timetable/"):
            if not timetable_limiter.allow(self.client_ip()):
                return self.error(429, "Too many requests", cors=True)
            status, body = timetable_query(route[len("timetable/"):], query)
            return self.send(status, body, headers=self.cors())
        if route.startswith("shares"):
            return self.share_api(method, route, query)
        if method == "POST" and route == "bugs":
            return self.submit_bug()
        if method == "POST" and route == "community/reports":
            return self.submit_report()
        if method == "GET" and route == "community/delay":
            key = clean(query.get("trip"), 300)
            if not key:
                return self.error(400, "trip is required", cors=True)
            with db() as conn:
                return self.send(200, {"trip": key, "delay": trip_delay(conn, key), "history": trip_history(conn, key),
                                       "crowd": crowd_now(conn, key)}, headers=self.cors())
        if method == "POST" and route == "community/crowd":
            # 2.17.0: how full the bus is, from a rider on it (the apps ask only once it has left their stop).
            data = self.json_body(limit=2_000)
            reporter, key = clean(data.get("reporter"), 64), clean(data.get("tripKey"), 300)
            level = data.get("level")
            if not reporter or not key or level not in (1, 2, 3):
                return self.error(400, "tripKey, reporter and level 1-3 are required", cors=True)
            if not (report_limiter.allow(self.client_ip()) and reporter_limiter.allow(reporter)):
                return self.error(429, "Too many reports", cors=True)
            with db() as conn:
                conn.execute("INSERT INTO crowd_reports(created_at, trip_key, level, reporter) VALUES (?,?,?,?)",
                             (now(), key, level, reporter))
                return self.send(201, {"crowd": crowd_now(conn, key)}, headers=self.cors())
        if method == "GET" and route == "notices":
            with db() as conn:
                return self.send(200, {"notices": active_notices(conn)}, headers=self.cors())
        if method == "POST" and route == "submissions":
            return self.submit_submission()
        if route.startswith("eta"):
            return self.eta_api(method, route, query)
        self.error(404, "Not found", cors=True)

    def share_socket(self, query):
        """
        A share over a WebSocket: GET /api/v1/shares/live?side=owner&id=<share id>&token=… (the sharer) or
        side=claim&id=<claim id>&token=… (the one who entered the code). The server pushes status changes and the other
        side's positions; the client sends {"type": "position", "lat", "lon", "accuracy"} once the share is accepted.
        The share's own token is the key, so the web version connects straight here.
        """
        side = query.get("side")
        share = shares.owned(query.get("id"), query.get("token")) if side == "owner" else \
            shares.claimed(query.get("id"), query.get("token")) if side == "claim" else None
        if not share or "websocket" not in (self.headers.get("Upgrade") or "").lower() or not self.headers.get("Sec-WebSocket-Key"):
            return self.error(404, "No such share, or not a WebSocket request", cors=True)
        ws = WebSocket.accept(self)
        shares.attach(share["id"], side, ws)
        try:
            # What has happened so far, then the other side's last position.
            ws.send_json({"type": "status", "status": share["status"], "name": share["name"],
                                                             "label": share["label"], **({"trip": share["trip"]} if side == "claim" and share["status"] == "accepted" else {})})
            other = fresh_position(share["claimPos"] if side == "owner" else share["pos"])
            if other and share["status"] == "accepted":
                ws.send_json({"type": "position", "position": other})
            for message in ws.messages():
                if message.get("type") != "position" or share["status"] != "accepted":
                    continue
                try:
                    pos = position_of(message)
                except (TypeError, ValueError):
                    continue
                share["pos" if side == "owner" else "claimPos"] = pos
                shares.push(share, "claim" if side == "owner" else "owner", {"type": "position", "position": pos})
        finally:
            shares.detach(share["id"], side, ws)
            self.close_connection = True

    def map_socket(self, user, query):
        """
        The map's live channel: GET /admin/api/map/live?tab=<id> as a WebSocket (signed in, from our own pages). The
        client says what it has open ({"type": "focus", "station", "trip"}); the server sends who is where
        ({"type": "presence"}) and what others saved ({"type": "changed"}).
        """
        origin = (self.headers.get("Origin") or "").rstrip("/")
        if origin not in ORIGINS or "websocket" not in (self.headers.get("Upgrade") or "").lower() or not self.headers.get("Sec-WebSocket-Key"):
            return self.error(400, "Not a WebSocket request from the dashboard")
        ws = WebSocket.accept(self)
        map_room.join(ws, user["oidc_name"] or user["username"], str(query.get("tab") or "")[:40])
        try:
            map_room.presence()
            for message in ws.messages():
                if message.get("type") == "focus":
                    station, trip = message.get("station"), message.get("trip")
                    map_room.focus(ws, str(station)[:200] if station else None, str(trip)[:300] if trip else None)
                    map_room.presence()
        finally:
            map_room.leave(ws)
            map_room.presence()
            self.close_connection = True

    def share_api(self, method, route, query):
        """One-time share codes (Shares): create, claim, the sharer's answer, and both sides asking how it went."""
        parts = route.strip("/").split("/")
        cors = self.cors()
        if method == "POST" and route == "shares":
            if not share_limiter.allow(self.client_ip()):
                return self.error(429, "Too many shares, try again later", cors=True)
            data = self.json_body(limit=8_000)
            trip = str(data.get("trip") or "")
            if not re.fullmatch(r"[A-Za-z0-9_-]{1,3000}", trip):
                return self.error(400, "trip is required", cors=True)
            share = shares.create(trip, one_line(data.get("label"), 120))
            return self.send(201, {"id": share["id"], "code": share["code"], "token": share["owner"], "expiresAt": share["expires"]}, headers=cors)
        if method == "POST" and route == "shares/claim":
            if not claim_limiter.allow(self.client_ip()):
                return self.error(429, "Too many tries, wait a few minutes", cors=True)
            data = self.json_body(limit=2_000)
            code, name = str(data.get("code") or "").strip(), one_line(data.get("name"), 80)
            if not re.fullmatch(r"\d{6}", code) or not name:
                return self.error(400, "A 6-digit code and your name are needed", cors=True)
            share = shares.claim(code, name)
            if not share:
                return self.error(404, "No such code, or it was already used or expired", cors=True)
            return self.send(200, {"claimId": share["claim"], "token": share["claimToken"], "label": share["label"]}, headers=cors)
        if method == "GET" and len(parts) == 3 and parts[1] == "claim":
            share = shares.claimed(parts[2], query.get("token"))
            if not share:
                return self.error(404, "Not found", cors=True)
            body = {"status": share["status"], "label": share["label"]}
            if share["status"] == "accepted":
                body["trip"] = share["trip"]
                body["position"] = fresh_position(share["pos"])
            return self.send(200, body, headers=cors)
        if method == "POST" and len(parts) == 4 and parts[1] == "claim" and parts[3] == "position":
            data = self.json_body(limit=2_000)
            share = shares.claimed(parts[2], data.get("token"))
            if not share or share["status"] != "accepted":
                return self.error(404, "Not shared", cors=True)
            share["claimPos"] = position_of(data)
            shares.push(share, "owner", {"type": "position", "position": share["claimPos"]})
            return self.send(200, {"ok": True, "position": fresh_position(share["pos"])}, headers=cors)
        if len(parts) >= 2:
            self._share_body = {} if method == "GET" else self.json_body(limit=2_000)
            share = shares.owned(parts[1], query.get("token") if method == "GET" else self._share_body.get("token"))
            if not share:
                return self.error(404, "Not found", cors=True)
            if method == "GET" and len(parts) == 2:
                status = "expired" if share["status"] == "waiting" and now() > share["expires"] else share["status"]
                return self.send(200, {"status": status, "name": share["name"], "code": share["code"], "expiresAt": share["expires"],
                                       "position": fresh_position(share["claimPos"]) if status == "accepted" else None}, headers=cors)
            if method == "POST" and len(parts) == 3 and parts[2] == "position":
                if share["status"] != "accepted":
                    return self.error(409, "Not accepted", cors=True)
                share["pos"] = position_of(self._share_body)
                shares.push(share, "claim", {"type": "position", "position": share["pos"]})
                return self.send(200, {"ok": True, "position": fresh_position(share["claimPos"])}, headers=cors)
            if method == "POST" and len(parts) == 3 and parts[2] in ("accept", "decline", "cancel"):
                if parts[2] != "cancel" and share["status"] != "asked":
                    return self.error(409, "Nobody has entered the code yet", cors=True)
                share["status"] = {"accept": "accepted", "decline": "declined", "cancel": "cancelled"}[parts[2]]
                shares.changed(share)
                return self.send(200, {"status": share["status"]}, headers=cors)
        return self.error(404, "Not found", cors=True)

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
                   platform, app_version, device, logs, consent_at, kind, lang) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                (t, t, "new", category, title, description, one_line(data.get("name"), 120), email,
                 one_line(data.get("platform"), 20), one_line(data.get("appVersion"), 40), one_line(data.get("device"), 200),
                 logs, t, kind, guess_lang(data, logs, title + " " + description)),
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
            queue_mail(email, user_subject("ack", bug), render_template(user_template("user_ack", bug), bug_values(bug)),
                       reply_to=setting("mail_reply_to"))
        publish("bugs")
        self.send(201, {"id": bug["id"], "code": code}, headers=self.cors())

    def submit_submission(self):
        """
        2.17.0: a stop, a connection or a problem a rider says the timetable lacks. Kept for an admin, who approves it
        before it changes anything. A stop: name, lat, lon. A trip: line, weekdays (0 = Monday), stops [[station id,
        "HH:MM"], ...]. A problem: station id and text.
        """
        if not submission_limiter.allow(self.client_ip()):
            return self.error(429, "Too many submissions, try again later", cors=True)
        data = self.json_body(limit=20_000)
        kind, name = data.get("kind"), clean(data.get("name"), 80)
        if kind not in ("stop", "trip", "problem") or not name:
            return self.error(400, "kind (stop, trip or problem) and name are required", cors=True)
        t = timetable.current
        try:
            if kind == "stop":
                lat, lon = float(data["lat"]), float(data["lon"])
                if not (47 < lat < 51 and 16 < lon < 23.5):
                    raise ValueError("That place is outside Slovakia")
                body = {"name": one_line(data.get("stopName"), 80), "lat": round(lat, 6), "lon": round(lon, 6)}
                if not body["name"]:
                    raise ValueError("The stop needs a name")
            elif kind == "trip":
                stops = []
                for sid, hhmm in data.get("stops") or []:
                    h, m = (int(x) for x in str(hhmm).split(":"))
                    if t is None or str(sid) not in t.by_id or not (0 <= h < 30 and 0 <= m < 60):
                        raise ValueError("Each stop needs a known stop and a time HH:MM")
                    stops.append([str(sid), h * 3600 + m * 60])
                if len(stops) < 2 or len(stops) > 80 or any(b[1] < a[1] for a, b in zip(stops, stops[1:])):
                    raise ValueError("At least two stops, in order of time")
                weekdays = sorted({int(d) for d in data.get("weekdays") or [] if 0 <= int(d) <= 6})
                line = one_line(data.get("line"), 12)
                if not weekdays or not line:
                    raise ValueError("The line and at least one day are required")
                body = {"line": line, "weekdays": weekdays, "stops": stops, "headsign": one_line(data.get("headsign"), 80),
                        "operator": one_line(data.get("operator"), 80)}
            else:
                text = clean(data.get("text"), 2000)
                if not text:
                    raise ValueError("Say what the problem is")
                body = {"station": clean(data.get("station"), 200), "text": text}
        except (KeyError, TypeError, ValueError) as error:
            return self.error(400, str(error) if isinstance(error, ValueError) and str(error)[:1].isupper() else "Check the details", cors=True)
        with db() as conn:
            sid = conn.execute("INSERT INTO submissions(created_at, kind, name, email, data) VALUES (?,?,?,?,?)",
                               (now(), kind, name, clean(data.get("email"), 120), json.dumps(body))).lastrowid
        publish("submissions")
        return self.send(201, {"id": sid}, headers=self.cors())

    def eta_api(self, method, route, query):
        """
        2.17.0: a live arrival time anyone can open in a browser (no app): POST eta {label, stop} -> {id, token, url};
        POST eta/<id> {token, arrivalMs, delaySeconds, line} keeps it current; DELETE-like POST eta/<id>/end.
        """
        parts = route.split("/")
        if method == "POST" and route == "eta":
            data = self.json_body(limit=2_000)
            if not eta_limiter.allow(self.client_ip()):
                return self.error(429, "Too many shared arrivals", cors=True)
            item = etas.create(one_line(data.get("label"), 60), one_line(data.get("stop"), 80))
            if item is None:
                return self.error(503, "Sharing is busy, try again later", cors=True)
            return self.send(201, {"id": item["id"], "token": item["token"], "url": PUBLIC_URL.rstrip("/") + "/eta/" + item["id"]},
                             headers=self.cors())
        if method == "POST" and len(parts) in (2, 3):
            data = self.json_body(limit=2_000)
            item = etas.owned(parts[1], data.get("token"))
            if not item:
                return self.error(404, "No such arrival", cors=True)
            if len(parts) == 3 and parts[2] == "end":
                etas.end(item["id"])
            else:
                try:
                    item.update(arrivalMs=int(data.get("arrivalMs") or 0), delaySeconds=int(data.get("delaySeconds") or 0),
                                line=one_line(data.get("line"), 12), updated=now())
                except (TypeError, ValueError):
                    return self.error(400, "arrivalMs and delaySeconds are numbers", cors=True)
            return self.send(200, {"ok": True}, headers=self.cors())
        return self.error(404, "Not found", cors=True)

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
        if method == "GET" and route == "feeds/health":
            # Each feed's trips per day (last 30 days) and the last builds' changes.
            with db() as conn:
                days = [r["day"] for r in conn.execute("SELECT DISTINCT day FROM feed_history ORDER BY day DESC LIMIT 30")][::-1]
                rows = conn.execute("SELECT * FROM feed_history WHERE day>=? ORDER BY feed, day", (days[0] if days else "",)).fetchall()
                feeds = {}
                for r in rows:
                    feeds.setdefault(r["feed"], {})[r["day"]] = {"trips": r["trips"], "alerted": r["alerted"], "result": r["result"]}
                builds = [{"built": r["built"], "changes": json.loads(r["changes"] or "{}")}
                          for r in conn.execute("SELECT built, changes FROM build_snapshots ORDER BY built DESC LIMIT 10")]
            return self.send(200, {"days": days, "feeds": feeds, "builds": builds})
        if method == "GET" and route.startswith("timetable/"):
            status, body = timetable_query(route[len("timetable/"):], query)
            return self.send(status, body)
        if route.startswith("map/"):
            return self.map_api(user, method, route[len("map/"):], query)
        if method == "GET" and route == "lines":
            # Lines added here, with their stops by name; and every operator in the timetable, to pick from.
            t = timetable.current
            with db() as conn:
                rows = [dict(r, data=json.loads(r["data"])) for r in conn.execute("SELECT * FROM admin_lines ORDER BY id DESC")]
            for r in rows:
                r["data"]["stopNames"] = [t.names[t.by_id[s]] if t and s in t.by_id else s for s, _ in r["data"]["stops"]]
            operators = sorted({trip[4] for trip in t.trips if trip[4]}) if t else []
            return self.send(200, {"lines": rows, "operators": operators})
        if method == "POST" and re.fullmatch(r"lines(/\d+)?(/delete)?", route):
            parts = route.split("/")
            line_id = int(parts[1]) if len(parts) > 1 else None
            with db() as conn:
                if line_id is not None and not conn.execute("SELECT 1 FROM admin_lines WHERE id=?", (line_id,)).fetchone():
                    return self.error(404, "No such line")
                if route.endswith("/delete"):
                    conn.execute("DELETE FROM admin_lines WHERE id=?", (line_id,))
                else:
                    try:
                        body = json.dumps(clean_admin_line(self.json_body(limit=200_000), timetable.current))
                    except ValueError as error:
                        return self.error(400, str(error))
                    if line_id is None:
                        line_id = conn.execute("INSERT INTO admin_lines(created_at, updated_at, updated_by, data) VALUES (?,?,?,?)",
                                               (now(), now(), user["username"], body)).lastrowid
                    else:
                        conn.execute("UPDATE admin_lines SET data=?, updated_at=?, updated_by=? WHERE id=?",
                                     (body, now(), user["username"], line_id))
            load_map_edits()
            rebuild_timetable()  # in the planner, boards and offline packs in about half a minute
            return self.send(200, {"ok": True, "id": line_id})
        if method == "POST" and route == "stops/new":
            # A stop the timetable lacks, placed on the map by an admin (kept like an approved rider's stop).
            data = self.json_body()
            try:
                lat, lon = float(data["lat"]), float(data["lon"])
            except (KeyError, TypeError, ValueError):
                return self.error(400, "lat and lon are required")
            stop_name = one_line(data.get("name"), 80)
            if not stop_name or not (47 < lat < 51 and 16 < lon < 23.5):
                return self.error(400, "A name and a place in Slovakia are required")
            with db() as conn:
                sid = conn.execute("""INSERT INTO submissions(created_at, kind, name, data, status, reviewed_by, reviewed_at)
                                      VALUES (?,?,?,?,?,?,?)""", (now(), "stop", user["username"],
                                   json.dumps({"name": stop_name, "lat": round(lat, 6), "lon": round(lon, 6)}), "approved",
                                   user["username"], now())).lastrowid
            load_map_edits()
            rebuild_timetable()
            return self.send(200, {"ok": True, "id": f"submitted-{sid}"})
        if method == "GET" and route == "notices":
            with db() as conn:
                return self.send(200, {"notices": [dict(r) for r in conn.execute("SELECT * FROM notices ORDER BY id DESC LIMIT 200")], "now": now()})
        if method == "POST" and re.fullmatch(r"notices(/\d+)?(/delete)?", route):
            parts = route.split("/")
            nid = int(parts[1]) if len(parts) > 1 else None
            with db() as conn:
                if nid is not None and not conn.execute("SELECT 1 FROM notices WHERE id=?", (nid,)).fetchone():
                    return self.error(404, "No such notice")
                if route.endswith("/delete"):
                    conn.execute("DELETE FROM notices WHERE id=?", (nid,))
                else:
                    n = clean_notice(self.json_body(limit=20_000))
                    cols = list(n)
                    if nid is None:
                        nid = conn.execute(f"INSERT INTO notices(created_at, updated_at, updated_by, {','.join(cols)}) VALUES (?,?,?,{','.join('?' * len(cols))})",
                                           [now(), now(), user["username"], *n.values()]).lastrowid
                    else:
                        conn.execute(f"UPDATE notices SET updated_at=?, updated_by=?, {', '.join(c + '=?' for c in cols)} WHERE id=?",
                                     [now(), user["username"], *n.values(), nid])
            return self.send(200, {"ok": True, "id": nid})
        if method == "GET" and route == "community/stats":
            days = min(max(int(query.get("days") or 30), 1), 365) if str(query.get("days") or "30").isdigit() else 30
            with db() as conn:
                return self.send(200, rider_stats(conn, days))
        if method == "GET" and route == "replies":
            with db() as conn:
                return self.send(200, {"replies": [dict(x) for x in conn.execute("SELECT * FROM saved_replies ORDER BY title")]})
        if method == "POST" and re.fullmatch(r"replies(/\d+)?(/delete)?", route):
            parts = route.split("/")
            rid = int(parts[1]) if len(parts) > 1 else None
            with db() as conn:
                if rid is not None and not conn.execute("SELECT 1 FROM saved_replies WHERE id=?", (rid,)).fetchone():
                    return self.error(404, "No such reply")
                if route.endswith("/delete"):
                    conn.execute("DELETE FROM saved_replies WHERE id=?", (rid,))
                    return self.send(200, {"ok": True})
                data = self.json_body(limit=40_000)
                title, body_sk, body_en = one_line(data.get("title"), 80), clean(data.get("bodySk"), 10_000), clean(data.get("bodyEn"), 10_000)
                if not title or not (body_sk or body_en):
                    return self.error(400, "A reply needs a title and a text in Slovak or English")
                if rid is None:
                    rid = conn.execute("INSERT INTO saved_replies(title, body_sk, body_en, updated_at, updated_by) VALUES (?,?,?,?,?)",
                                       (title, body_sk, body_en, now(), user["username"])).lastrowid
                else:
                    conn.execute("UPDATE saved_replies SET title=?, body_sk=?, body_en=?, updated_at=?, updated_by=? WHERE id=?",
                                 (title, body_sk, body_en, now(), user["username"], rid))
                return self.send(200, {"ok": True, "id": rid})
        if method == "GET" and route == "releases":
            # Versions with fixes marked for them, and the ones already released.
            with db() as conn:
                counts = {r["fixed_in"]: dict(r) for r in conn.execute(
                    """SELECT fixed_in, COUNT(*) n, SUM(email<>'') with_email FROM bugs WHERE fixed_in<>'' GROUP BY fixed_in""")}
                released = {r["version"]: dict(r) for r in conn.execute("SELECT * FROM releases")}
                versions = sorted(set(counts) | set(released), key=lambda v: [int(x) for x in v.split(".")], reverse=True)
                return self.send(200, {"releases": [dict(version=v, fixes=counts.get(v, {}).get("n", 0),
                                                         withEmail=counts.get(v, {}).get("with_email", 0) or 0,
                                                         **{k: released.get(v, {}).get(k) for k in ("released_at", "notified", "notes_en", "notes_sk")})
                                                    for v in versions]})
        m = re.fullmatch(r"releases/(\d+(?:\.\d+){1,3})(/draft|/notes|/release)?", route)
        if m:
            version, what = m.group(1), m.group(2) or ""
            with db() as conn:
                if method == "GET" and what == "/draft":
                    return self.send(200, release_draft(conn, version))
                if method == "POST" and what == "/notes":
                    data = self.json_body(limit=20_000)
                    conn.execute("""INSERT INTO releases(version, notes_en, notes_sk, updated_by) VALUES (?,?,?,?)
                                    ON CONFLICT(version) DO UPDATE SET notes_en=excluded.notes_en, notes_sk=excluded.notes_sk,
                                    updated_by=excluded.updated_by""",
                                 (version, clean(data.get("en"), 4000), clean(data.get("sk"), 4000), user["username"]))
                    return self.send(200, {"ok": True})
                if method == "POST" and what == "/release":
                    # Released: every reporter of a fix in it (who left an email) hears so, once, in their language.
                    row = conn.execute("SELECT notified FROM releases WHERE version=?", (version,)).fetchone()
                    if row and row["notified"]:
                        return self.error(409, f"{version} was already announced to its reporters")
                    conn.execute("""INSERT INTO releases(version, released_at, updated_by) VALUES (?,?,?)
                                    ON CONFLICT(version) DO UPDATE SET released_at=excluded.released_at, updated_by=excluded.updated_by""",
                                 (version, now(), user["username"]))
                    sent = 0
                    for bug in conn.execute("SELECT * FROM bugs WHERE fixed_in=?", (version,)).fetchall():
                        if bug["status"] not in ("resolved", "closed"):
                            conn.execute("UPDATE bugs SET status='resolved', updated_at=? WHERE id=?", (now(), bug["id"]))
                        if not bug["email"]:
                            continue
                        cur = conn.execute("INSERT INTO bug_messages(bug_id, created_at, kind, author, body, mail_status) VALUES (?,?,?,?,?,?)",
                                           (bug["id"], now(), "system", user["username"], f"Released in {version}: reporter told", "queued"))
                        queue_mail(bug["email"], user_subject("fixed", bug, version=version),
                                   render_template(user_template("user_fixed", bug), bug_values(bug, version=version)),
                                   cur.lastrowid, setting("mail_reply_to"))
                        sent += 1
                    conn.execute("UPDATE releases SET notified=1 WHERE version=?", (version,))
                    publish("bugs")
                    return self.send(200, {"ok": True, "emailed": sent})
            return self.error(404, "Not found")
        if method == "GET" and route == "labels":
            with db() as conn:
                found = {}
                for r in conn.execute("SELECT labels FROM bugs WHERE labels<>''"):
                    for x in r["labels"].split(","):
                        found[x] = found.get(x, 0) + 1
                return self.send(200, {"labels": sorted(found.items(), key=lambda kv: -kv[1])})
        if method == "GET" and route == "server":
            return self.send(200, server_health())
        if method == "POST" and route == "server/backup":
            return self.send(200, {"ok": True, "name": backup_db().name, "backups": list_backups()})
        if method == "GET" and route == "audit":
            with db() as conn:
                return self.send(200, {"entries": [dict(r) for r in conn.execute("SELECT * FROM audit_log ORDER BY id DESC LIMIT 300")]})
        if method == "GET" and route == "submissions":
            with db() as conn:
                rows = [dict(r, data=json.loads(r["data"])) for r in conn.execute(
                    "SELECT * FROM submissions ORDER BY status='new' DESC, id DESC LIMIT 300")]
            t = timetable.current
            for r in rows:  # the stops of a trip by name, for reading it
                if r["kind"] == "trip" and t is not None:
                    r["data"]["stopNames"] = [t.names[t.by_id[s]] if s in t.by_id else s for s, _ in r["data"]["stops"]]
            return self.send(200, {"submissions": rows})
        if method == "POST" and re.fullmatch(r"submissions/\d+/(approve|reject|done)", route):
            sid, action = int(route.split("/")[1]), route.split("/")[2]
            note = clean(self.json_body().get("note"), 500)
            status = {"approve": "approved", "reject": "rejected", "done": "done"}[action]
            with db() as conn:
                row = conn.execute("SELECT kind FROM submissions WHERE id=?", (sid,)).fetchone()
                if not row:
                    return self.error(404, "No such submission")
                conn.execute("UPDATE submissions SET status=?, note=?, reviewed_by=?, reviewed_at=? WHERE id=?",
                             (status, note, user["username"], now(), sid))
            if row["kind"] in ("stop", "trip") and status in ("approved", "rejected"):
                load_map_edits()
                rebuild_timetable()  # the stop or connection in (or out of) the timetable in about half a minute
            publish("submissions")
            return self.send(200, {"ok": True, "status": status})
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
                waiting = conn.execute("SELECT COUNT(*) FROM submissions WHERE status='new'").fetchone()[0]
                return self.send(200, {"bugs": counts, "ideas": ideas, "activeTrips": active, "reportsToday": reports_today,
                                       "submissions": waiting})
            if method == "GET" and route == "bugs":
                sql, args = "SELECT id, created_at, updated_at, status, category, title, name, email, platform, kind, lang, labels, fixed_in, app_version FROM bugs", []
                where = ["kind=?"]
                args.append(query.get("kind") if query.get("kind") in BUG_KINDS else "bug")
                if query.get("status") in BUG_STATUSES:
                    where.append("status=?")
                    args.append(query["status"])
                elif query.get("status") == "open":
                    where.append("status NOT IN ('resolved','closed')")
                if query.get("label"):
                    where.append("(','||labels||',') LIKE ?")
                    args.append("%," + clean(query["label"], 30).lower() + ",%")
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
                return self.send(200, {"html": render_template(user_template("user_reply", values), values)})
        self.error(404, "Not found")

    def map_api(self, user, method, route, query):
        """The dashboard's map: stops, buses now, a trip's way; moving stops and drawing or snapping road traces."""
        if method == "GET" and route == "live":
            return self.map_socket(user, query)
        t = timetable.current
        if t is None:
            return self.error(503, "Timetable is loading, try again in a minute")
        tab = self.headers.get("X-Map-Tab") or ""

        def changed(**what):
            """Tell the others on the map (not this tab) what was just saved."""
            map_room.push({"type": "changed", "by": user["oidc_name"] or user["username"], **what}, skip_tab=tab)

        def taken(sid):
            """Who else has this stop open in the map, or None."""
            return map_room.busy(sid, tab)

        def box():
            south, west, north, east = (float(x) for x in (query.get("bbox") or "").split(","))
            return south, west, north, east
        if method == "GET" and route == "stations":
            return self.send(200, {"stations": t.stations_in(*box())})
        if method == "GET" and route == "lines":
            return self.send(200, {"routes": t.line_routes(query.get("q") or "")})
        if method == "GET" and route == "todo":
            return self.send(200, map_todo(t))
        if method == "GET" and route == "history":
            with db() as conn:
                rows = [dict(r) for r in conn.execute("SELECT id, created_at, username, action, tbl, key, undone_at FROM map_history ORDER BY id DESC LIMIT 200")]
            for row in rows:  # stop ids and parts by name, for reading
                ids = row["key"].split(">") if ">" in row["key"] else [row["key"]]
                row["what"] = " → ".join(t.names[t.by_id[x]] if x in t.by_id else x for x in ids)
            return self.send(200, {"history": rows})
        if method == "GET" and route == "bulk":
            ops = sorted({trip[4] for trip in t.trips if trip[4]})
            return self.send(200, {"status": dict(bulk), "operators": ops})
        if method == "GET" and route == "vehicles":
            with db() as conn:
                reported = {r[0] for r in conn.execute("SELECT DISTINCT trip_key FROM community_reports WHERE created_at>=?",
                                                       (now() - COMMUNITY_WINDOW_S,))}
                cache = {}

                def delay_of(keys):
                    for key in keys:
                        if key in reported:
                            if key not in cache:
                                cache[key] = trip_delay(conn, key)
                            if cache[key]:
                                return cache[key]
                    return None
                return self.send(200, {"vehicles": t.vehicles(int(time.time()), box(), delay_of, sadzv.vehicles()), "at": now()})
        if method == "GET" and route == "station":
            s = t.by_id.get(query.get("id") or "")
            return self.send(200, t.station_detail(s)) if s is not None else self.error(404, "No such stop")
        if method == "GET" and route == "trip":
            shape = t.trip_shape(query.get("id") or "") if re.fullmatch(r"[^:]+:.+:\d{8}", query.get("id") or "") else None
            return self.send(200, shape) if shape else self.error(404, "No such trip")
        if method != "POST":
            return self.error(404, "Not found")
        data = self.json_body()
        if route in ("stop", "stop/reset"):
            s = t.by_id.get(str(data.get("id")))
            if s is None:
                return self.error(404, "No such stop")
            sid = t.station_ids[s]
            if taken(sid):
                return self.error(409, f"{taken(sid)} is editing this stop right now")
            with db() as conn:
                if route == "stop/reset":
                    old = conn.execute("SELECT orig_lat, orig_lon FROM map_stops WHERE station_id=?", (sid,)).fetchone()
                    conn.execute("DELETE FROM map_stops WHERE station_id=?", (sid,))
                    if old and old["orig_lat"] is not None:
                        t.lat[s], t.lon[s] = old["orig_lat"], old["orig_lon"]  # where the timetable had it
                else:
                    lat, lon = float(data["lat"]), float(data["lon"])
                    if not (47 < lat < 51 and 16 < lon < 23.5):
                        return self.error(400, "That's outside Slovakia")
                    old = conn.execute("SELECT orig_lat, orig_lon FROM map_stops WHERE station_id=?", (sid,)).fetchone()
                    orig = (old["orig_lat"], old["orig_lon"]) if old else (t.lat[s], t.lon[s])
                    conn.execute("""INSERT INTO map_stops(station_id, name, lat, lon, orig_lat, orig_lon, updated_by, updated_at)
                                    VALUES (?,?,?,?,?,?,?,?) ON CONFLICT(station_id) DO UPDATE SET lat=excluded.lat, lon=excluded.lon,
                                    updated_by=excluded.updated_by, updated_at=excluded.updated_at""",
                                 (sid, t.names[s], lat, lon, orig[0], orig[1], user["username"], now()))
                    t.lat[s], t.lon[s] = lat, lon  # in use right away; the rebuild keeps it
            load_map_edits()
            changed(station=sid)
            return self.send(200, t.station_json(s))
        m_undo = re.fullmatch(r"history/(\d+)/undo", route)
        if m_undo:
            with db() as conn:
                entry = conn.execute("SELECT * FROM map_history WHERE id=?", (int(m_undo.group(1)),)).fetchone()
            if not entry:
                return self.error(404, "No such change")
            if entry["undone_at"]:
                return self.error(409, "Already undone")
            newer = map_row(entry["tbl"], entry["key_col"], entry["key"])
            if (json.dumps(newer) if newer else None) != entry["after"]:
                return self.error(409, "Changed again since: undo the newer change first")
            undo_map_change(dict(entry))
            load_map_edits()
            t.apply_station_edits()
            t.apply_routes()
            if entry["tbl"] == "map_stops":
                rebuild_timetable()  # a stop's place is used across the timetable
            changed(segment=entry["key"] if ">" in entry["key"] else None, station=None if ">" in entry["key"] else entry["key"])
            return self.send(200, {"ok": True})
        if route == "bulk":
            if bulk.get("running"):
                return self.error(409, f"Already snapping {bulk.get('operator')}")
            operator = one_line(data.get("operator"), 120)
            if operator == "stop":
                bulk["running"] = False
                return self.send(200, {"ok": True})
            if not any(trip[4] == operator for trip in t.trips):
                return self.error(400, "No such operator in the timetable")
            bulk_snap(operator, user["username"])
            return self.send(202, {"ok": True, "total": bulk.get("total", 0)})
        if route == "station":
            # A station's extra places, zone and central flag; fields left out stay as they are.
            s = t.by_id.get(str(data.get("id")))
            if s is None:
                return self.error(404, "No such stop")
            sid = t.station_ids[s]
            if taken(sid):
                return self.error(409, f"{taken(sid)} is editing this stop right now")
            with db() as conn:
                row = conn.execute("SELECT data FROM map_stations WHERE station_id=?", (sid,)).fetchone()
                current = json.loads(row["data"]) if row else {}
                if "places" in data:
                    current["places"] = [clean_place(p) for p in (data["places"] or [])][:100]
                if "zone" in data:
                    current["zone"] = clean_zone(data["zone"])
                if "central" in data:
                    current["central"] = bool(data["central"])
                if not current.get("central"):  # no longer central: its sub-stations are ordinary stops again
                    conn.execute("DELETE FROM map_children WHERE parent_id=?", (sid,))
                if not any(current.get(k) for k in ("places", "zone", "central")):
                    conn.execute("DELETE FROM map_stations WHERE station_id=?", (sid,))
                else:
                    conn.execute("""INSERT INTO map_stations(station_id, name, data, updated_by, updated_at) VALUES (?,?,?,?,?)
                                    ON CONFLICT(station_id) DO UPDATE SET data=excluded.data, updated_by=excluded.updated_by,
                                    updated_at=excluded.updated_at""", (sid, t.names[s], json.dumps(current), user["username"], now()))
            load_map_edits()
            t.apply_station_edits()
            changed(station=sid)
            return self.send(200, t.station_detail(s))
        if route in ("child", "child/remove"):
            # A central station's sub-station: another stop that is its platform for some lines.
            child = t.by_id.get(str(data.get("child")))
            if child is None:
                return self.error(404, "No such stop")
            with db() as conn:
                if route == "child/remove":
                    conn.execute("DELETE FROM map_children WHERE child_id=?", (t.station_ids[child],))
                else:
                    parent = t.by_id.get(str(data.get("parent")))
                    if parent is None or parent == child:
                        return self.error(400, "Pick another stop as the central one")
                    if parent not in t.central:
                        return self.error(400, "Make the station central first")
                    if child in t.central:
                        return self.error(400, "A central station can't be a sub-station")
                    conn.execute("""INSERT INTO map_children(child_id, name, parent_id, data, updated_by, updated_at) VALUES (?,?,?,?,?,?)
                                    ON CONFLICT(child_id) DO UPDATE SET parent_id=excluded.parent_id, data=excluded.data,
                                    updated_by=excluded.updated_by, updated_at=excluded.updated_at""",
                                 (t.station_ids[child], t.names[child], t.station_ids[parent],
                                  json.dumps(platform_fields(data)), user["username"], now()))
            load_map_edits()
            t.apply_station_edits()
            changed(station=t.station_ids[t.parent_of[child][0]] if child in t.parent_of else str(data.get("parent") or ""),
                    child=t.station_ids[child])
            return self.send(200, t.station_detail(t.parent_of[child][0]) if child in t.parent_of else t.station_detail(child))
        if route in ("segment", "segment/reset"):
            key = f"{data.get('from')}>{data.get('to')}"
            if data.get("from") not in t.by_id or data.get("to") not in t.by_id:
                return self.error(404, "No such stop")
            with db() as conn:
                if route == "segment/reset":
                    conn.execute("DELETE FROM map_segments WHERE segment=?", (key,))
                else:
                    points = [[float(p[0]), float(p[1])] for p in data.get("points") or []][:2000]
                    conn.execute("""INSERT INTO map_segments(segment, points, source, updated_by, updated_at) VALUES (?,?,?,?,?)
                                    ON CONFLICT(segment) DO UPDATE SET points=excluded.points, source=excluded.source,
                                    updated_by=excluded.updated_by, updated_at=excluded.updated_at""",
                                 (key, json.dumps(points), "manual", user["username"], now()))
            load_map_edits()
            changed(segment=key)
            return self.send(200, {"ok": True})
        if route == "via":
            # Checkpoints for one stop-to-stop part: snapping goes through them in order ([] removes them).
            key = f"{data.get('from')}>{data.get('to')}"
            if data.get("from") not in t.by_id or data.get("to") not in t.by_id:
                return self.error(404, "No such stop")
            try:
                points = [[round(float(p[0]), 6), round(float(p[1]), 6)] for p in data.get("points") or []][:25]
            except (TypeError, ValueError, IndexError):
                return self.error(400, "Checkpoints are [lat, lon] pairs")
            if any(not (47 < p[0] < 51 and 16 < p[1] < 23.5) for p in points):
                return self.error(400, "A checkpoint is outside Slovakia")
            with db() as conn:
                if points:
                    conn.execute("""INSERT INTO map_vias(segment, points, updated_by, updated_at) VALUES (?,?,?,?)
                                    ON CONFLICT(segment) DO UPDATE SET points=excluded.points, updated_by=excluded.updated_by,
                                    updated_at=excluded.updated_at""", (key, json.dumps(points), user["username"], now()))
                else:
                    conn.execute("DELETE FROM map_vias WHERE segment=?", (key,))
            load_map_edits()
            changed(segment=key)
            return self.send(200, {"ok": True})
        if route == "route":
            # A way's stops set by hand (S.A.D. leaves some out, a regional feed may too); [] goes back to the timetable's.
            key = t.route_key(str(data.get("trip") or ""))
            if not key:
                return self.error(400, "A rider's submitted connection is changed in Submissions, not here")
            stations = [str(x) for x in data.get("stations") or [] if str(x) in t.by_id][:120]
            with db() as conn:
                if len(stations) < 2:
                    conn.execute("DELETE FROM map_routes WHERE route_key=?", (key,))
                else:
                    conn.execute("""INSERT INTO map_routes(route_key, stations, updated_by, updated_at) VALUES (?,?,?,?)
                                    ON CONFLICT(route_key) DO UPDATE SET stations=excluded.stations, updated_by=excluded.updated_by,
                                    updated_at=excluded.updated_at""", (key, json.dumps(stations), user["username"], now()))
            load_map_edits()
            t.apply_routes()
            changed(trip=str(data.get("trip")), route=key)
            return self.send(200, {"ok": True})
        if route == "snap":
            # Every stop-to-stop part of the trip along the roads (OSRM), except parts drawn by hand.
            shape = t.trip_shape(str(data.get("id") or ""))
            if not shape:
                return self.error(404, "No such trip")
            done, failed = 0, 0
            with db() as conn:
                manual = {r[0] for r in conn.execute("SELECT segment FROM map_segments WHERE source='manual'")}
                for seg in shape["segments"]:
                    key = f"{seg['from']}>{seg['to']}"
                    # One part only (the dashboard goes part by part, to show its progress).
                    if data.get("from") and (seg["from"], seg["to"]) != (data.get("from"), data.get("to")):
                        continue
                    if (key in manual and not data.get("force")) or len(seg["points"]) < 2 or (data.get("keep") and seg["traced"]):
                        continue
                    try:
                        points = snap_segment(seg["points"][0], seg["points"][-1],
                                              other_ends=[p[:2] for p in t.free_spots(t.by_id[seg["to"]])],
                                              via=timetable.edits.get("vias", {}).get(key, ()))
                    except ProviderError:
                        points = False  # the router not answering: what was there stays
                    if not points:
                        if points is None:
                            # No sensible way: an earlier snap that went wrong goes too (a straight line until drawn by hand).
                            conn.execute("DELETE FROM map_segments WHERE segment=? AND source='osrm'", (key,))
                        failed += 1
                        continue
                    conn.execute("""INSERT INTO map_segments(segment, points, source, updated_by, updated_at) VALUES (?,?,?,?,?)
                                    ON CONFLICT(segment) DO UPDATE SET points=excluded.points, source=excluded.source,
                                    updated_by=excluded.updated_by, updated_at=excluded.updated_at""",
                                 (key, json.dumps(points), "osrm", user["username"], now()))
                    done += 1
                    time.sleep(1)  # the public OSRM server asks for at most one request a second
            load_map_edits()
            if done or failed:
                changed(trip=str(data.get("id")), segment=f"{data.get('from')}>{data.get('to')}" if data.get("from") else None)
            return self.send(200, {"snapped": done, "failed": failed, "trip": t.trip_shape(str(data.get("id")))})
        return self.error(404, "Not found")

    def admin_bug(self, conn, user, method, bug_id, action):
        bug = conn.execute("SELECT * FROM bugs WHERE id=?", (bug_id,)).fetchone()
        if not bug:
            return self.error(404, "No such bug")
        if method == "GET" and not action:
            messages = conn.execute("SELECT * FROM bug_messages WHERE bug_id=? ORDER BY id", (bug_id,)).fetchall()
            images = conn.execute("SELECT id, mime, length(data) size FROM bug_images WHERE bug_id=? ORDER BY id", (bug_id,)).fetchall()
            return self.send(200, dict(bug, code=bug_code(bug_id), messages=[dict(m) for m in messages],
                                       images=[dict(i) for i in images], lang=bug_lang(bug), similar=similar_bugs(conn, bug)))
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
            queue_mail(bug["email"], user_subject("reply", bug), render_template(user_template("user_reply", bug), values),
                       cur.lastrowid, setting("mail_reply_to") or user["email"])
            return self.send(200, {"ok": True})
        if action == "meta":
            # The reporter's language (which emails they get), labels, and the version the fix ships in.
            lang = data.get("lang") if data.get("lang") in ("sk", "en") else bug["lang"]
            labels = ",".join(dict.fromkeys(x for x in (one_line(v, 30).lower() for v in str(data.get("labels", bug["labels"])).split(",")) if x))[:300]
            fixed_in = one_line(data.get("fixedIn", bug["fixed_in"]), 20)
            if fixed_in and not re.fullmatch(r"\d+(\.\d+){1,3}", fixed_in):
                return self.error(400, "A version looks like 2.17.1")
            # A version set: it waits for that release; taken off again: back to being worked on.
            if fixed_in and bug["status"] not in ("resolved", "closed"):
                status = "awaiting_release"
            elif not fixed_in and bug["status"] == "awaiting_release":
                status = "in_progress"
            else:
                status = bug["status"]
            conn.execute("UPDATE bugs SET lang=?, labels=?, fixed_in=?, status=?, updated_at=? WHERE id=?",
                         (lang, labels, fixed_in, status, t, bug_id))
            if status != bug["status"]:
                conn.execute("INSERT INTO bug_messages(bug_id, created_at, kind, author, body) VALUES (?,?,?,?,?)",
                             (bug_id, t, "status", user["username"], f"{bug['status']} -> {status}"))
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
                queue_mail(bug["email"], user_subject("status", bug),
                           render_template(user_template("user_status", bug), bug_values(bug)), cur.lastrowid, setting("mail_reply_to"))
            return self.send(200, {"ok": True})
        self.error(404, "Not found")


def crash_signature(logs):
    """The exception of a crash in the logs ("java.lang.IllegalStateException: ..."), without numbers, or ''."""
    m = re.search(r"FATAL EXCEPTION[\s\S]{0,400}?\n.*?((?:[\w$]+\.)+[\w$]*(?:Exception|Error)\b[^\n]*)", logs or "")
    return re.sub(r"\d+", "#", m.group(1).strip())[:200] if m else ""


def similar_bugs(conn, bug, limit=5):
    """Other reports that may be the same problem: the same crash, or mostly the same words in the title."""
    words = lambda text: {w for w in re.findall(r"\w{3,}", text.lower())}  # noqa: E731
    mine, crash = words(bug["title"]), crash_signature(bug["logs"])
    found = []
    for o in conn.execute("SELECT id, title, status, logs FROM bugs WHERE id<>? AND kind=? ORDER BY id DESC LIMIT 400",
                          (bug["id"], bug["kind"])):
        theirs = words(o["title"])
        overlap = len(mine & theirs) / max(1, len(mine | theirs))
        same_crash = bool(crash) and crash == crash_signature(o["logs"])
        if same_crash or overlap >= 0.5:
            found.append((same_crash, overlap, {"id": o["id"], "code": bug_code(o["id"]), "title": o["title"],
                                                 "status": o["status"], "why": "same crash" if same_crash else "similar title"}))
    found.sort(key=lambda x: (not x[0], -x[1]))
    return [f[2] for f in found[:limit]]


def release_draft(conn, version):
    """Play notes from the bugs and suggestions fixed in [version]: bullet lists in English and Slovak, to edit."""
    rows = conn.execute("SELECT id, kind, title, lang FROM bugs WHERE fixed_in=? ORDER BY kind DESC, id", (version,)).fetchall()
    items = [dict(r, code=bug_code(r["id"])) for r in rows]
    fixes = [r for r in items if r["kind"] == "bug"]
    ideas = [r for r in items if r["kind"] == "feature"]

    def notes(new, fixed):
        lines = [f"• {x['title']}" for x in ideas] + [f"• {fixed}: {x['title']}" for x in fixes]
        return "\n".join(lines) if lines else ""
    return {"version": version, "items": items, "en": notes("New", "Fixed"), "sk": notes("Nové", "Opravené")}


# ------------------------------------------------------------------ server health and database backups

STARTED = time.time()
BACKUP_DIR = Path(ENV("HUB_BACKUP_DIR", str(Path(DB_PATH).parent / "backups")))
BACKUP_KEEP = 14
timings = {}  # "app: timetable" -> [requests, total ms, slowest ms], since the server started
recent_errors = deque(maxlen=30)
app_versions = {}  # X-App-Version -> requests since the server started


def route_group(path):
    parts = path.strip("/").split("/")
    if parts[:2] == ["api", "v1"]:
        return "app: " + "/".join(parts[2:3])
    if parts[:2] == ["admin", "api"]:
        return "admin: " + "/".join(parts[2:3])
    return "pages"


def note_error(where):
    recent_errors.appendleft({"at": now(), "where": where, "error": traceback.format_exc()[-2000:]})


def backup_db():
    """A consistent copy of the database (sqlite's online backup) in BACKUP_DIR; keeps the newest BACKUP_KEEP."""
    BACKUP_DIR.mkdir(parents=True, exist_ok=True)
    target = BACKUP_DIR / f"hub-{datetime.now(LOCAL_ZONE):%Y-%m-%d-%H%M%S}.db"
    src, dst = db(), sqlite3.connect(target)
    try:
        src.backup(dst)
    finally:
        dst.close()
        src.close()
    for old in sorted(BACKUP_DIR.glob("hub-*.db"))[:-BACKUP_KEEP]:
        old.unlink()
    return target


def list_backups():
    return [{"name": p.name, "size": p.stat().st_size, "at": int(p.stat().st_mtime)} for p in sorted(BACKUP_DIR.glob("hub-*.db"), reverse=True)]


def backup_worker():
    """A backup once a day. ponytail: hourly check of the newest backup's age, so the hour drifts with restarts."""
    time.sleep(60)
    while True:
        try:
            newest = max((p.stat().st_mtime for p in BACKUP_DIR.glob("hub-*.db")), default=0)
            if time.time() - newest > 23.5 * 3600:
                print(f"[emhub] database backup {backup_db().name}", flush=True)
        except Exception:  # noqa: BLE001 - try again in an hour; shown on the Server page
            traceback.print_exc()
            note_error("daily backup")
        time.sleep(3600)


def server_health():
    """What the Server page shows: this process, the machine, the database, request times, recent errors."""
    out = {"version": VERSION, "started": int(STARTED), "now": now(), "threads": threading.active_count(), "mailQueue": mail_queue.qsize()}
    try:  # Linux only
        for line in Path("/proc/self/status").read_text().splitlines():
            if line.startswith("VmRSS:"):
                out["rss"] = int(line.split()[1]) * 1024
        mem = dict(line.split(":", 1) for line in Path("/proc/meminfo").read_text().splitlines() if ":" in line)
        out["memTotal"], out["memAvailable"] = (int(mem[k].split()[0]) * 1024 for k in ("MemTotal", "MemAvailable"))
        out["load"] = [round(x, 2) for x in os.getloadavg()]
        out["cpus"] = os.cpu_count()
    except (OSError, KeyError, AttributeError):
        pass
    disk = shutil.disk_usage(Path(DB_PATH).parent)
    out["disk"] = {"total": disk.total, "free": disk.free}
    out["dbSize"] = sum(p.stat().st_size for p in Path(DB_PATH).parent.glob(Path(DB_PATH).name + "*"))
    out["routes"] = sorted(({"route": k, "count": c, "avgMs": round(t / c), "maxMs": round(m)} for k, (c, t, m) in list(timings.items())),
                           key=lambda x: -x["count"])
    out["errors"] = list(recent_errors)
    out["appVersions"] = sorted(({"version": k, "requests": v} for k, v in list(app_versions.items())), key=lambda x: -x["requests"])
    with db() as conn:
        out["reportedVersions"] = [dict(r) for r in conn.execute(
            """SELECT platform, app_version AS version, COUNT(*) AS reports, MAX(created_at) AS last FROM bugs
               WHERE created_at>=? AND app_version<>'' GROUP BY platform, app_version ORDER BY last DESC LIMIT 20""", (now() - 90 * 86400,))]
    out["backups"] = list_backups()
    return out


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
    threading.Thread(target=backup_worker, daemon=True).start()
    if ENV("HUB_TIMETABLE", "on") != "off":
        load_map_edits()  # moved stops and road traces, kept through every timetable rebuild
        timetable.start(GTFS_DIR, gtfs_feeds, collect=sadzv.collect)
    host, port = ENV("HUB_HOST", "0.0.0.0"), int(ENV("HUB_PORT", "8080"))
    print(f"[emhub] listening on {host}:{port}, public URL {PUBLIC_URL}", flush=True)
    Server((host, port), Handler).serve_forever()


if __name__ == "__main__":
    main()
