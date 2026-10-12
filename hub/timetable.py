"""
Timetables for the app's planner, boards and trips, from GTFS feeds: our own conversion of the CIS CP
timetables (cis/cis.py on the web server, over the tunnel) and transiq for the rest.

Feeds are downloaded once a day into HUB_GTFS_DIR, loaded into memory (about 620k stop
times for all of Slovakia) and swapped in whole, so requests never see a half-built one.
Stops of all feeds with the same name are one station ("Zvolen, AS" is served by several
carriers), so changes between carriers work. Connections use the Connection Scan Algorithm.

Times inside a day are seconds since the day's GTFS base (noon minus 12 h, local time),
which keeps daylight-saving days right.
"""
import bisect
import collections
import csv
import io
import json
import math
import os
import re
import threading
import time
import traceback
import unicodedata
import urllib.error
import urllib.request
import zipfile
from datetime import date, datetime, timedelta
from email.utils import formatdate
from pathlib import Path
from zoneinfo import ZoneInfo

ZONE = ZoneInfo("Europe/Bratislava")
FEED_URL = os.environ.get("HUB_GTFS_URL", "https://transiq.xhyrom.dev/gtfs/sk/{}.zip")
# Our own CIS conversion; a feed it doesn't build (404) comes from FEED_URL. Empty: FEED_URL only.
CIS_URL = os.environ.get("HUB_CIS_URL", "http://10.99.153.2:8090/{}.zip")
# Buses only for now: add "zsr" for trains (zssk.zip is the same feed again).
DEFAULT_FEEDS = ",".join([
    "sad-zvolen-as", "sad-lucenec-as", "sad-zilina-as", "sad-trencin-as", "sad-prievidza-as",
    "sad-poprad-as", "sad-presov-sk", "sad-humenne-as", "sad-dunajska-streda-as", "arriva-liorbus-as",
    "arriva-michalovce-as", "arriva-nitra-as", "arriva-nove-zamky-as", "arriva-trnava-as", "eurobus-as",
    "ids-bk_regional", "dpmp", "slovak-lines-express-as", "regiojet-as", "a-express-sro",
    "cestovna-kancelaria-daka"])

CHANGE_S = 60             # changing vehicles at one station (cp.sk allows 1 min at the same stop, EM-0022)
WALK_M = 400              # stations this close are joined by a footpath
WALK_SPEED = 1.2 / 1.3    # m/s along a straight line (streets are ~30 % longer)
SAME_NAME_M = 1500        # same-named stops further apart are different stations
DAYS_BACK, DAYS_AHEAD = 2, 120
MAX_VEHICLES = 6          # a connection uses at most this many buses
MAX_TRAVEL_S = 24 * 3600  # and leaves within this long (a village's first bus may be tomorrow)
SLACK_S, SLACK_SHARE = 10 * 60, 0.15  # a journey with fewer changes may arrive this much later
MODES = {0: "tram", 2: "rail", 3: "bus", 11: "trolleybus", 200: "coach"}
CITY_FEED = "sad-zvolen-as-city"   # S.A.D. Zvolen city buses read from sadzv (sadzv.collect)
def city_operator(lat):
    """sadzv doesn't say who runs a line (DPMBB or S.A.D. Zvolen in Banská Bystrica), so name the city's transport."""
    return "MHD Banská Bystrica" if lat and lat > 48.66 else "MHD Zvolen"
CITY_REUSE_DAYS = 14               # a day read from sadzv stands in for later days of its kind this long


def mode_of(route_type):
    t = int(route_type or 3)
    return MODES.get(t) or ("rail" if 100 <= t < 200 else "coach" if 200 <= t < 300 else "bus")


def fold(text):
    """Lowercase ASCII words: "Zvolen,,Zlatý Potok" -> ["zvolen", "zlaty", "potok"]."""
    plain = unicodedata.normalize("NFD", text).encode("ascii", "ignore").decode().lower()
    return re.findall(r"[a-z0-9]+", plain)


def clean_name(name):
    """CIS names use ",," for a missing part: "Banská Bystrica,,Magurská otoč." -> "Banská Bystrica, Magurská otoč."."""
    return ", ".join(" ".join(p.split()) for p in name.split(",") if p.strip())


def meters(lat1, lon1, lat2, lon2):
    x = math.radians(lon2 - lon1) * math.cos(math.radians((lat1 + lat2) / 2))
    return math.hypot(x, math.radians(lat2 - lat1)) * 6_371_000


def seconds(text):
    h, m, s = text.split(":")
    return int(h) * 3600 + int(m) * 60 + int(s)


def base_epoch(day):
    """GTFS times count from noon minus 12 hours (differs from midnight on DST days)."""
    return int(datetime(day.year, day.month, day.day, 12, tzinfo=ZONE).timestamp()) - 43200


# ------------------------------------------------------------------ stop search

def prefix_match(token, words, i):
    """Can `token` be split into non-empty prefixes of words[i], words[i+1], ...? Returns words used, or 0."""
    if i >= len(words):
        return 0
    w = words[i]
    if len(w) >= 3 and len(token) > len(w) and token.startswith(w):
        return 1  # the timetable abbreviates, the rider doesn't: "nabrezie" -> "Štadlerovo nábr."
    for k in range(min(len(token), len(w)), 0, -1):
        if token[:k] != w[:k]:
            continue
        if k == len(token):
            return 1
        rest = prefix_match(token[k:], words, i + 1)
        if rest:
            return 1 + rest
    return 0


def match_score(tokens, words):
    """
    cp.sk-style shortcuts: each typed word starts a word of the name, in order, words may be
    skipped ("zvol zla po kon", "z z p k" -> "Zvolen, Zlatý Potok konečná"), and one typed
    word may cover several ("bb as" -> "Banská Bystrica, AS"). None = no match; higher is better.
    """
    best = None

    def go(t, i, penalty, first):
        nonlocal best
        if t == len(tokens):
            # Starting at the town and typing whole words rank first; busier stations break ties.
            score = -penalty + (4 if first == 0 else 0) + (2 if tokens[-1] == words[i - 1] else 0)
            best = score if best is None else max(best, score)
            return
        for j in range(i, len(words)):
            used = prefix_match(tokens[t], words, j)
            if used:
                go(t + 1, j + used, penalty + (j - i) * 3 * (t > 0) + (used - 1) * 2, j if t == 0 else first)

    go(0, 0, 0, 0)
    return best


# ------------------------------------------------------------------ the loaded timetable

class Timetable:
    def __init__(self, feeds, today=None, city=()):
        """feeds: {name: path to GTFS zip}; city: days of city buses read by sadzv.collect."""
        today = today or datetime.now(ZONE).date()
        self.built = time.time()
        self.first_day = today - timedelta(days=DAYS_BACK)
        self.station_ids, self.names, self.lat, self.lon, self.words, self.weight = [], [], [], [], [], []
        self.by_id = {}
        self.trips = []          # (feed, trip_id, line, headsign, operator, mode, service, stations, arr, dep, platforms)
        self.trip_index = {}
        self.services = {}       # (feed, service_id) -> set of day numbers since first_day
        self.feeds = {}
        self.city = {}           # trip index -> (lineId, route, trip number, line, sadzv platform ids) of city buses
        self.city_platforms = {} # sadzv platform id (str) -> [name, lat, lon, number, stop id]
        self._days = {}
        self._lock = threading.Lock()
        stops = {}               # (feed, stop_id) -> (name, lat, lon, platform)
        raw_trips = []
        for feed, path in feeds.items():
            try:
                self._read_feed(feed, path, stops, raw_trips)
            except Exception:  # noqa: BLE001 - one broken feed must not take the others down
                traceback.print_exc()
                log(f"{feed}: could not read the feed")
                self.feeds[feed] = "failed"
        station_of = self._stations(stops)
        for feed, trip_id, line, headsign, operator, mode, service, rows in raw_trips:
            rows.sort()
            ids = [station_of.get((feed, r[1])) for r in rows]
            keep = [k for k, s in enumerate(ids) if s is not None]
            if len(keep) < 2:
                continue
            self.trip_index[(feed, trip_id)] = len(self.trips)
            self.trips.append((feed, trip_id, line, headsign, operator, mode, service,
                               tuple(ids[k] for k in keep), tuple(rows[k][2] for k in keep),
                               tuple(rows[k][3] for k in keep), tuple(stops[(feed, rows[k][1])][3] for k in keep)))
            for s in ids:
                if s is not None:
                    self.weight[s] += 1
        try:
            self._add_city(city)
        except Exception:  # noqa: BLE001 - the regional timetable still works without city buses
            traceback.print_exc()
            log("city buses: could not add them")
        self.apply_stop_edits()
        self.apply_station_edits()
        self._footpaths()

    def _add_city(self, collections):
        """
        S.A.D. Zvolen city buses, each read day also standing in for later days of the same kind
        (sadzv only gives today). Their stops join our stations: the same-named one nearby, or in
        the same town when ours has no position (then it gets sadzv's), else a new station.
        """
        if not collections:
            return
        collections = sorted(collections, key=lambda c: c["date"])
        kind = lambda d: max(d.weekday(), 4)  # noqa: E731 - 4 workday, 5 Saturday, 6 Sunday; ponytail: holidays count as workdays
        read = [date.fromisoformat(c["date"]) for c in collections]
        use = {}
        for n in range(DAYS_BACK + DAYS_AHEAD + 1):
            d = self.first_day + timedelta(days=n)
            pick = d if d in read else next((r for r in reversed(read)
                                             if r < d and kind(r) == kind(d) and (d - r).days <= CITY_REUSE_DAYS), None)
            if pick:
                use.setdefault(pick, set()).add(n)
        for c in collections:
            self.city_platforms.update(c["platforms"])
        stops = {}
        for pid, (name, lat, lon, _, stop_id) in self.city_platforms.items():
            stops.setdefault(stop_id, []).append((pid, name, lat, lon))
        station_of = {}
        for members in stops.values():
            name = max({m[1] for m in members}, key=lambda n: sum(m[1] == n for m in members))
            lat = sum(m[2] for m in members) / len(members)
            lon = sum(m[3] for m in members) / len(members)
            s = self._city_station(name, lat, lon)
            for m in members:
                station_of[m[0]] = s
        count = 0
        for c, day in zip(collections, read):
            service = ("sadzv", c["date"])
            self.services[service] = use.get(day, set())
            if not self.services[service]:
                continue
            for t in c["trips"]:
                rows = [(station_of[str(pid)], secs, str(pid)) for pid, secs in t["stops"] if str(pid) in station_of]
                if len(rows) < 2:
                    continue
                trip_id = f'{c["date"].replace("-", "")}-{t["lineId"]}-{t["route"]}-{t["trip"]}'
                self.trip_index[(CITY_FEED, trip_id)] = len(self.trips)
                self.city[len(self.trips)] = (t["lineId"], t["route"], t["trip"], t["line"], tuple(r[2] for r in rows))
                self.trips.append((CITY_FEED, trip_id, t["line"], clean_name(t["headsign"]), city_operator(self.lat[rows[0][0]]), "bus", service,
                                   tuple(r[0] for r in rows), tuple(r[1] for r in rows), tuple(r[1] for r in rows),
                                   tuple(str(self.city_platforms[r[2]][3]) for r in rows)))
                for r in rows:
                    self.weight[r[0]] += 1
                count += 1
        self.feeds[CITY_FEED] = count

    def _city_station(self, name, lat, lon):
        """Our station for a sadzv stop ("Námestie SNP" is "Zvolen, Nám.SNP"), or a new one."""
        words = fold(name)

        def named(s):
            own = fold(self.names[s].split(",", 1)[1]) if "," in self.names[s] else self.words[s]
            return own and words and (match_score(own, words) is not None or match_score(words, own) is not None)
        near, town = [], None
        for s in range(len(self.names)):
            if self.lat[s] is None or abs(self.lat[s] - lat) > 0.05 or abs(self.lon[s] - lon) > 0.07:
                continue
            d = meters(lat, lon, self.lat[s], self.lon[s])
            near.append((d, s))
        near.sort()
        for d, s in near:
            # A similar name only counts close by: "Ďumbierska" 280 m from "Ďumbierska ŠZŠ" is another stop.
            if d <= 120 and named(s) or d <= 25:
                return s
        town = next((self.names[s].split(",")[0].strip() for d, s in near if "," in self.names[s] and d < 5000), None)
        if town:
            for s in range(len(self.names)):
                if self.lat[s] is None and self.names[s].startswith(town + ",") and named(s):
                    self.lat[s], self.lon[s] = lat, lon
                    return s
        display = clean_name(name if not town or fold(name)[:len(fold(town))] == fold(town) else f"{town}, {name}")
        sid, n = " ".join(fold(display)), 1
        while sid in self.by_id:
            sid, n = f"{' '.join(fold(display))}~{n}", n + 1
        self.station_ids.append(sid)
        self.names.append(display)
        self.lat.append(lat)
        self.lon.append(lon)
        self.words.append(fold(display))
        self.weight.append(0)
        self.by_id[sid] = len(self.names) - 1
        return len(self.names) - 1

    def _read_feed(self, feed, path, stops, raw_trips):
        with zipfile.ZipFile(path) as zf:
            def rows(name):
                if name not in zf.namelist():
                    return []
                return csv.DictReader(io.TextIOWrapper(zf.open(name), "utf-8-sig"))
            last = self.first_day + timedelta(days=DAYS_BACK + DAYS_AHEAD)
            weekdays = ("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")
            for r in rows("calendar.txt"):
                start = max(datetime.strptime(r["start_date"], "%Y%m%d").date(), self.first_day)
                end = min(datetime.strptime(r["end_date"], "%Y%m%d").date(), last)
                days = self.services.setdefault((feed, r["service_id"]), set())
                d = start
                while d <= end:
                    if r[weekdays[d.weekday()]] == "1":
                        days.add((d - self.first_day).days)
                    d += timedelta(days=1)
            for r in rows("calendar_dates.txt"):
                d = datetime.strptime(r["date"], "%Y%m%d").date()
                if not self.first_day <= d <= last:
                    continue
                days = self.services.setdefault((feed, r["service_id"]), set())
                (days.add if r["exception_type"] == "1" else days.discard)((d - self.first_day).days)
            for r in rows("stops.txt"):
                if r.get("location_type") not in (None, "", "0"):
                    continue
                lat, lon = float(r["stop_lat"] or 0), float(r["stop_lon"] or 0)
                ok = 47 < lat < 51 and 16 < lon < 23.5 or 35 < lat < 60 and 5 < lon < 30
                stops[(feed, r["stop_id"])] = (clean_name(r["stop_name"]), lat if ok else None, lon if ok else None,
                                               (r.get("platform_code") or "").strip())
            agencies = {r.get("agency_id", ""): r["agency_name"].strip() for r in rows("agency.txt")}
            routes = {r["route_id"]: (r["route_short_name"].strip() or r["route_long_name"].strip(),
                                      agencies.get(r.get("agency_id", ""), next(iter(agencies.values()), "")),
                                      mode_of(r["route_type"])) for r in rows("routes.txt")}
            trips = {}
            for r in rows("trips.txt"):
                if r["service_id"] and (feed, r["service_id"]) in self.services and self.services[(feed, r["service_id"])]:
                    line, operator, mode = routes.get(r["route_id"], ("", "", "bus"))
                    trips[r["trip_id"]] = (line, clean_name(r.get("trip_headsign") or ""), operator, mode, (feed, r["service_id"]), [])
            for r in rows("stop_times.txt"):
                trip = trips.get(r["trip_id"])
                if trip is None or r.get("pickup_type") == "1" and r.get("drop_off_type") == "1":
                    continue
                arr, dep = r["arrival_time"] or r["departure_time"], r["departure_time"] or r["arrival_time"]
                if arr and dep:
                    trip[5].append((int(r["stop_sequence"]), r["stop_id"], seconds(arr), seconds(dep)))
            for trip_id, (line, headsign, operator, mode, service, st) in trips.items():
                raw_trips.append((feed, trip_id, line, headsign, operator, mode, service, st))
            self.feeds[feed] = len(trips)

    def _stations(self, stops):
        by_name = {}
        for key, (name, lat, lon, _) in stops.items():
            by_name.setdefault(name, []).append((key, lat, lon))
        station_of = {}
        for name, members in by_name.items():
            clusters = []  # [anchor lat, lon, [keys], [coords]]
            for key, lat, lon in sorted(members, key=lambda m: m[1] is None):
                home = next((c for c in clusters if lat is None or meters(c[0], c[1], lat, lon) <= SAME_NAME_M), None) \
                    if clusters else None
                if home is None:
                    home = [lat, lon, [], []]
                    clusters.append(home)
                home[2].append(key)
                if lat is not None:
                    home[3].append((lat, lon))
            base = " ".join(fold(name))
            for n, (_, _, keys, coords) in enumerate(sorted(clusters, key=lambda c: -len(c[2]))):
                sid = base if n == 0 else f"{base}~{n}"
                index = len(self.names)
                self.station_ids.append(sid)
                self.names.append(name)
                self.lat.append(sum(c[0] for c in coords) / len(coords) if coords else None)
                self.lon.append(sum(c[1] for c in coords) / len(coords) if coords else None)
                self.words.append(fold(name))
                self.weight.append(0)
                self.by_id[sid] = index
                for key in keys:
                    station_of[key] = index
        return station_of

    def _footpaths(self):
        grid = {}
        for s, (lat, lon) in enumerate(zip(self.lat, self.lon)):
            if lat is not None:
                grid.setdefault((int(lat * 200), int(lon * 130)), []).append(s)  # ~550 m cells
        self.foot = [[] for _ in self.names]
        for (gy, gx), members in grid.items():
            near = [t for dy in (-1, 0, 1) for dx in (-1, 0, 1) for t in grid.get((gy + dy, gx + dx), ())]
            for s in members:
                for t in near:
                    if t != s:
                        d = meters(self.lat[s], self.lon[s], self.lat[t], self.lon[t])
                        if d <= WALK_M:
                            self.foot[s].append((t, int(d / WALK_SPEED) + 60))

    # ---- the map (dashboard): stop positions moved by hand, road traces, buses running now

    def apply_stop_edits(self):
        """
        Stops moved in the dashboard's map keep their place through every rebuild: found by station id, or (renamed in
        a new GTFS) by the same name near where it was. Returns how many were placed.
        """
        placed = 0
        for sid, e in edits["stops"].items():
            s = self.by_id.get(sid)
            if s is None:
                s = next((i for i, n in enumerate(self.names) if n == e["name"] and self.lat[i] is not None
                          and meters(self.lat[i], self.lon[i], e["origLat"], e["origLon"]) <= 500), None)
            if s is not None:
                self.lat[s], self.lon[s] = e["lat"], e["lon"]
                placed += 1
        return placed

    def _edited(self, sid, name):
        """A station an edit is about: by id, or (renamed in a new GTFS) the one station of that name."""
        if sid in self.by_id:
            return self.by_id[sid]
        same = [i for i, n in enumerate(self.names) if n == name]
        return same[0] if len(same) == 1 else None

    def apply_station_edits(self):
        """
        Stations set up in the dashboard: extra places (each may say which lines board there), a zone around the
        station (circle or drawn shape), and central stations whose sub-stations are their platforms for some lines.
        """
        self.places, self.zones, self.central, self.children, self.parent_of = {}, {}, set(), {}, {}
        for sid, e in edits["stations"].items():
            s = self._edited(sid, e.get("name"))
            if s is None:
                continue
            if e.get("places"):
                self.places[s] = e["places"]
            if e.get("zone"):
                self.zones[s] = e["zone"]
            if e.get("central"):
                self.central.add(s)
        for sid, e in edits["children"].items():
            child, parent = self._edited(sid, e.get("name")), self.by_id.get(e.get("parent"))
            if child is None or parent is None or child == parent:
                continue
            info = {"label": e.get("label") or "", "platform": e.get("platform") or "", "lines": e.get("lines") or [],
                    "directions": e.get("directions") or []}
            self.children.setdefault(parent, []).append((child, info))
            self.parent_of[child] = (parent, info)

    def platform_for(self, s, line, trip=None, k=None):
        """
        Where a bus of line `line` stops at station s, if set up in the dashboard: a place of s, or s itself as a central
        station's sub-station. A place or sub-station has directions, each with its lines and the towns it heads towards;
        with the trip known (trip, its stop number k), a direction "towards" a town fits buses that end there or pass it
        later. The best fit wins: a direction towards where the bus ends beats one towards a town it only passes (a bus
        to Dudince through Krupina goes to "towards Dudince", not "towards Krupina"); then a direction with lines beats
        one without. (lat, lon, label) or None.
        """
        own = line_key(line)
        heading = ends = None
        if trip is not None and k is not None:
            end = trip[3] or self.names[trip[7][-1]]
            ends = name_keys(end) | name_keys(self.names[trip[7][-1]])
            heading = ends | {key for x in trip[7][k + 1:] for key in name_keys(self.names[x])}
        best, best_score = None, 0
        candidates = [(p["lat"], p["lon"], p) for p in getattr(self, "places", {}).get(s, ())]
        parent = getattr(self, "parent_of", {}).get(s)
        if parent and self.lat[s] is not None:
            candidates.append((self.lat[s], self.lon[s], parent[1]))
        for lat, lon, spot in candidates:
            for d in directions_of(spot):
                lines = {line_key(x) for x in d.get("lines") or ()}
                towards = {" ".join(fold(x)) for x in d.get("towards") or () if x}
                if lines and own not in lines:
                    continue
                if towards and (heading is None or not towards & heading):
                    continue
                score = (4 if towards and towards & ends else 2 if towards else 0) + (1 if lines else 0)
                if score > best_score:
                    best, best_score = (lat, lon, spot.get("platform") or spot.get("label") or ""), score
        if best is None and trip is not None and k is not None:
            best = self.traced_spot(s, trip, k) or self.kerb_side(s, trip, k)
        return best

    def free_spots(self, s):
        """
        Where a bus may stop at station s when no direction says which: its places without directions and its own
        point, (lat, lon, label) each. Only for a stop with up to two places: a bus station's bays don't count.
        """
        spots = [(p["lat"], p["lon"], p.get("platform") or p.get("label") or "")
                 for p in getattr(self, "places", {}).get(s, ()) if not directions_of(p)]
        if not spots or len(spots) > 2 or s in getattr(self, "central", ()) or self.lat[s] is None:
            return []
        return spots + [(self.lat[s], self.lon[s], "")]

    def traced_spot(self, s, trip, k):
        """
        Of a stop's free spots, the one the trip's road traces go to (snapping picks the one reached without a detour:
        Namestie slobody, 44 m apart on one side of the road, one only after a loop round the block). Lines and stops
        then always meet. None without a trace there.
        """
        spots = self.free_spots(s)
        if not spots:
            return None
        stops = trip[7]
        # The way there decides (snapping goes stop by stop, the next part leaves from where this one arrived).
        into = edits["segments"].get(f"{self.station_ids[stops[k - 1]]}>{self.station_ids[s]}", [])[-1:] if k > 0 else []
        out = edits["segments"].get(f"{self.station_ids[s]}>{self.station_ids[stops[k + 1]]}", [])[:1] if k + 1 < len(stops) else []
        end = (into or out or [None])[0]
        return min(spots, key=lambda p: meters(p[0], p[1], end[0], end[1])) if end else None

    def kerb_side(self, s, trip, k):
        """
        No direction set up: of station s's places without directions and its own point, the one on the right of the
        way the bus goes from the stop before to the one after (traffic keeps right), so it stops on its own side of
        the road (Zvolen, Rakos: a platform added across the road, for buses to Banska Bystrica). (lat, lon, label) or None.
        """
        spots = [(p["lat"], p["lon"], p.get("platform") or p.get("label") or "")
                 for p in getattr(self, "places", {}).get(s, ()) if not directions_of(p)]
        # Only a stop with its place across the road: a bus station's bays (Zvolen AS has 21) have no "right side".
        if len(spots) != 1 or s in getattr(self, "central", ()) or self.lat[s] is None:
            return None
        spots.append((self.lat[s], self.lon[s], ""))
        stops = trip[7]
        before, after = stops[max(k - 1, 0)], stops[min(k + 1, len(stops) - 1)]
        if before == after or self.lat[before] is None or self.lat[after] is None:
            return None
        cos = math.cos(math.radians(self.lat[s]))
        dx, dy = (self.lon[after] - self.lon[before]) * cos, self.lat[after] - self.lat[before]
        size = math.hypot(dx, dy) or 1
        lat0, lon0 = sum(p[0] for p in spots) / len(spots), sum(p[1] for p in spots) / len(spots)
        # How far left of the way the bus goes, in metres (negative = on its right).
        left = {p: (dx * (p[0] - lat0) - dy * (p[1] - lon0) * cos) / size * 111_000 for p in spots}
        # Two places along the same side (Namestie slobody: 44 m apart, 6 m across) aren't two sides of the road.
        if max(left.values()) - min(left.values()) < 10:
            return None
        return min(spots, key=left.get)

    def position_for(self, s, line, trip=None, k=None):
        p = self.platform_for(s, line, trip, k)
        return (p[0], p[1]) if p else (self.lat[s], self.lon[s])

    def in_zone(self, s, lat, lon):
        """Whether a point is in station s's zone (a circle, or a drawn shape)."""
        z = getattr(self, "zones", {}).get(s)
        if not z:
            return False
        if z.get("type") == "circle":
            return meters(lat, lon, z["lat"], z["lon"]) <= z["radius"]
        return inside_polygon(lat, lon, z.get("points") or [])

    def with_platforms(self, stations):
        """Central stations bring their sub-stations along (departures from the central one list theirs too)."""
        out = list(stations)
        for s in stations:
            out += [c for c, _ in getattr(self, "children", {}).get(s, ()) if c not in out]
        return out

    def serving(self, s):
        """What stops at station s, for the dashboard's choices: its lines (with where they go) and platform numbers."""
        if not hasattr(self, "_stops_at"):  # station -> (trip, stop number), built once (about a second for all of Slovakia)
            self._stops_at = {}
            for t, trip in enumerate(self.trips):
                for k, st in enumerate(trip[7]):
                    self._stops_at.setdefault(st, []).append((t, k))
        lines, platforms, ways = {}, set(), {}
        for t, k in self._stops_at.get(s, ()):
            trip = self.trips[t]
            if k < len(trip[7]) - 1:  # where it goes from here (not where it ends)
                head = trip[3] or self.names[trip[7][-1]]
                lines.setdefault(trip[2], set()).add(head)
                # Buses whose next stop is the same leave the same way: one direction for the dashboard to offer.
                way = ways.setdefault(self.names[trip[7][k + 1]], {"lines": set(), "headsigns": {}, "trips": 0})
                way["lines"].add(trip[2])
                way["headsigns"][head] = way["headsigns"].get(head, 0) + 1
                way["trips"] += 1
            if trip[10][k]:
                platforms.add(trip[10][k])
        order = lambda x: (len(x) > 3, x.zfill(6))  # noqa: E731 - city lines first, then by number
        directions = [{"next": nxt, "lines": sorted(w["lines"], key=order), "trips": w["trips"],
                       "headsigns": [h for h, _ in sorted(w["headsigns"].items(), key=lambda kv: -kv[1])][:8]}
                      for nxt, w in sorted(ways.items(), key=lambda kv: -kv[1]["trips"])]
        return {"lines": [{"line": line, "headsigns": sorted(h)[:3]} for line, h in sorted(lines.items(), key=lambda kv: order(kv[0]))],
                "platforms": sorted(platforms, key=lambda x: x.zfill(4)), "directions": directions[:12]}

    def station_detail(self, s):
        """Everything set up for a station, for the dashboard, with what stops there (for its choices)."""
        parent = self.parent_of.get(s)
        return dict(self.station_json(s), places=self.places.get(s, []), zone=self.zones.get(s), central=s in self.central,
                    serving=self.serving(s),
                    moved=self.station_ids[s] in edits["stops"],
                    parent=dict(self.station_json(parent[0]), **parent[1]) if parent else None,
                    children=[dict(self.station_json(c), serving=self.serving(c), **info) for c, info in self.children.get(s, ())])

    def stations_in(self, south, west, north, east, limit=4000):
        """Stations in a box, with what was set up for them by hand."""
        moved = set(edits["stops"])
        out = []
        for s in range(len(self.names)):
            if self.lat[s] is None or not (south <= self.lat[s] <= north and west <= self.lon[s] <= east):
                continue
            row = dict(self.station_json(s), weight=self.weight[s], moved=self.station_ids[s] in moved)
            if s in self.places:
                row["places"] = self.places[s]
            if s in self.zones:
                row["zone"] = self.zones[s]
            if s in self.central:
                row["central"] = True
            if s in self.parent_of:
                row["parent"] = self.station_ids[self.parent_of[s][0]]
            out.append(row)
        out.sort(key=lambda x: -x["weight"])
        return out[:limit]

    def fits_trace(self, a, b, line, trip, k):
        """
        A road trace between a and b made for this trip's platforms there: not one snapped to another place of the stop
        (Namestie slobody: drawn to it, the line zig-zagged from the trace to the trip's platform and back).
        """
        traced = edits["segments"].get(f"{self.station_ids[a]}>{self.station_ids[b]}")
        if not traced:
            return False

        def fits(s, at, end):
            if at is None:
                return True
            mine = meters(at[0], at[1], end[0], end[1])
            others = [meters(p[0], p[1], end[0], end[1]) for p in self.positions_of(s) if (p[0], p[1]) != (at[0], at[1])]
            return mine <= 60 or not others or min(others) + 20 > mine
        return fits(a, self.position_for(a, line, trip, k), traced[0]) and fits(b, self.position_for(b, line, trip, k + 1), traced[-1])

    def positions_of(self, s):
        """Every place a bus may stop at station s: its places, its sub-stations and its own point."""
        out = [(p["lat"], p["lon"]) for p in getattr(self, "places", {}).get(s, ())]
        out += [(self.lat[c], self.lon[c]) for c, _ in getattr(self, "children", {}).get(s, ())]
        return [p for p in out + [(self.lat[s], self.lon[s])] if p[0] is not None]

    def segment_points(self, a, b, line=None, trip=None, k=None):
        """
        The way between two stations (where `line` stops there; with the trip and a's stop number k, at the platform for
        the way it heads): the road trace drawn or snapped, else a straight line.
        """
        traced = edits["segments"].get(f"{self.station_ids[a]}>{self.station_ids[b]}")
        ends = ([self.position_for(a, line, trip, k), self.position_for(b, line, trip, None if k is None else k + 1)] if line
                else [(self.lat[a], self.lon[a]), (self.lat[b], self.lon[b])])
        return [ends[0], *[tuple(p) for p in traced], ends[1]] if traced else ends

    def community_keys(self, index, service_day):
        """The keys riders' reports for this trip go under (as the apps make them, LiveRepository.communityKey)."""
        trip = self.trips[index]
        base = base_epoch(service_day)
        keys = [f"{trip[2]}|{self.names[trip[7][0]]}|{(base + trip[9][0]) * 1000}"]
        city = self.city.get(index)
        if city:
            keys.append(f"sadzv|{city[0]}|{city[2]}|{service_day.isoformat()}|{city[3]}")
        return keys

    def vehicles(self, when, box, delay_of=lambda keys: None, gps=None):
        """
        Buses on the road at `when` (epoch s) in box (south, west, north, east): where the timetable puts them, or, with
        riders' reports (delay_of(keys) -> {"delaySeconds", "source"}), as late as they are ("live"). Along the road
        traces where there are some. A city bus sending its own GPS (gps: {(line id, trip number): (lat, lon, delay s)},
        sadzv) is where it says, with its delay; only a rider's phone on board comes first.
        """
        gps = gps or {}
        south, west, north, east = box
        d = datetime.fromtimestamp(when, ZONE).date()
        out, seen = [], set()
        for day in (self.day(d - timedelta(days=1)), self.day(d)):
            t0 = when - day.base
            lo = bisect.bisect_left(day.deps, t0 - 3 * 3600)
            hi = bisect.bisect_right(day.deps, t0 + 3600)
            for c in range(lo, hi):
                run = day.conns[c][4]
                index, shift = divmod(run, 3)
                service_day = day.date + timedelta(days=shift - 1)
                # Yesterday's connections hold today's trips too (after midnight): each trip once.
                if (index, service_day) in seen:
                    continue
                seen.add((index, service_day))
                trip = self.trips[index]
                offset = base_epoch(service_day) - day.base
                live = delay_of(self.community_keys(index, service_day))
                city = self.city.get(index)
                bus = gps.get((city[0], city[2])) if city and service_day == d and not (live and live.get("source") == "gps") else None
                if bus:
                    live = {"delaySeconds": bus[2], "source": "bus"}
                t = t0 - (live["delaySeconds"] if live else 0) - offset
                st, arr, dep = trip[7], trip[8], trip[9]
                if t < dep[0] or t > arr[-1]:
                    continue
                k = next((k for k in range(len(st) - 1) if t <= arr[k + 1]), len(st) - 2)
                a, b = st[k], st[k + 1]
                if self.lat[a] is None or self.lat[b] is None:
                    continue
                f = 0.0 if t <= dep[k] else min(1.0, (t - dep[k]) / max(1, arr[k + 1] - dep[k]))
                lat, lon = bus[:2] if bus else along(self.segment_points(a, b, trip[2], trip, k), f)
                if not (south <= lat <= north and west <= lon <= east):
                    continue
                out.append({"tripId": f"{trip[0]}:{trip[1]}:{service_day:%Y%m%d}", "line": trip[2], "headsign": trip[3] or self.names[st[-1]],
                            "mode": trip[5], "operator": trip[4], "lat": round(lat, 6), "lon": round(lon, 6),
                            "next": self.names[b], "live": bool(live), "delay": live["delaySeconds"] if live else None,
                            "source": live.get("source") if live else None})
        return out

    def trip_shape(self, trip_id):
        """A trip's stops and the way between each two, for drawing and editing its road traces."""
        trip = self.trip(trip_id)
        if not trip:
            return None
        index = self.trip_index[(trip_id.split(":", 1)[0], trip_id.split(":", 1)[1].rsplit(":", 1)[0])]
        st = self.trips[index][7]
        trip["segments"] = [{"from": self.station_ids[a], "to": self.station_ids[b],
                             "points": (self.segment_points(a, b, trip["line"], self.trips[index], k)
                                        if self.lat[a] is not None and self.lat[b] is not None else []),
                             "traced": self.fits_trace(a, b, trip["line"], self.trips[index], k)}
                            for k, (a, b) in enumerate(zip(st, st[1:]))]
        return trip

    # ---- per-day connections

    def day(self, d):
        """Connections usable on day d: that day's trips, the previous day's after midnight, the next day's."""
        with self._lock:
            cached = self._days.get(d)
            if cached:
                return cached
        n = (d - self.first_day).days
        base = base_epoch(d)
        conns = []
        for shift in (-1, 0, 1):
            offset = base_epoch(d + timedelta(days=shift)) - base
            for t, trip in enumerate(self.trips):
                if n + shift not in self.services.get(trip[6], ()):
                    continue
                run = t * 3 + shift + 1
                st, arr, dep = trip[7], trip[8], trip[9]
                for k in range(len(st) - 1):
                    dt = dep[k] + offset
                    if shift == -1 and dt < 0 or shift == 1 and dt > 2 * 86400:
                        continue
                    conns.append((dt, arr[k + 1] + offset, st[k], st[k + 1], run, k))
        conns.sort()
        day = Day(d, base, conns)
        with self._lock:
            if len(self._days) >= 4:
                self._days.pop(next(iter(self._days)))
            self._days[d] = day
        return day

    def run_trip(self, day, run):
        """(trip tuple, service date) of a run from Day.conns."""
        t, shift = divmod(run, 3)
        return self.trips[t], day.date + timedelta(days=shift - 1)

    # ---- API helpers

    def station_json(self, s):
        return {"id": self.station_ids[s], "name": self.names[s], "lat": self.lat[s], "lon": self.lon[s]}

    def search_words(self):
        """
        Each station's words for search. A name without its town ("Lipovec", a part of Zvolen) gets the town of the
        nearest station within 1.5 km that has one in front, so "z lipo" finds it like "Zvolen, Lipovec" (EM-0065).
        """
        if not hasattr(self, "_search_words"):
            cell = lambda lat, lon: (int(lat * 60), int(lon * 40))  # noqa: E731 - about 1.9 km by 1.9 km
            towns = {}
            for s, name in enumerate(self.names):
                if "," in name and self.lat[s] is not None:
                    towns.setdefault(cell(self.lat[s], self.lon[s]), []).append(s)
            words = list(self.words)
            for s, name in enumerate(self.names):
                if "," in name or self.lat[s] is None:
                    continue
                y, x = cell(self.lat[s], self.lon[s])
                near = [(meters(self.lat[s], self.lon[s], self.lat[o], self.lon[o]), o)
                        for dy in (-1, 0, 1) for dx in (-1, 0, 1) for o in towns.get((y + dy, x + dx), ())]
                d, o = min(near, default=(None, None))
                if d is not None and d <= 1500:
                    town = fold(self.names[o].split(",")[0])
                    if town != words[s][:len(town)]:
                        words[s] = town + words[s]
            self._search_words = words
        return self._search_words

    def search(self, text, limit=18):
        tokens = fold(text)
        if not tokens:
            return []
        found = []
        for s, words in enumerate(self.search_words()):
            if not words or tokens[0][0] not in {w[0] for w in words}:
                continue
            score = match_score(tokens, words)
            if score is not None:
                found.append((-score, -self.weight[s], self.names[s], s))
        found.sort()
        return [self.station_json(s) for *_, s in found[:limit]]

    def near(self, lat, lon, radius=300, limit=10):
        def distance(s):
            if self.in_zone(s, lat, lon):
                return 0.0
            return min([meters(lat, lon, self.lat[s], self.lon[s])] + [meters(lat, lon, p["lat"], p["lon"]) for p in self.places.get(s, ())])
        found = sorted((distance(s), s) for s in range(len(self.names))
                       if self.lat[s] is not None and abs(self.lat[s] - lat) < 0.05 and abs(self.lon[s] - lon) < 0.07)
        return [dict(self.station_json(s), meters=round(d)) for d, s in found if d <= radius][:limit]

    def town_center(self, s):
        """Mean position of the stations of the town in station s's name ("Zvolen, AS" -> Zvolen), or None."""
        town = self.names[s].split(",")[0].strip()
        if not hasattr(self, "_towns"):
            sums = {}
            for i, name in enumerate(self.names):
                if self.lat[i] is not None:
                    acc = sums.setdefault(name.split(",")[0].strip(), [0.0, 0.0, 0])
                    acc[0] += self.lat[i]
                    acc[1] += self.lon[i]
                    acc[2] += 1
            self._towns = {k: (v[0] / v[2], v[1] / v[2]) for k, v in sums.items()}
        return self._towns.get(town)

    def resolve(self, value):
        """A station id, or failing that the best name match (routes saved by name)."""
        if value in self.by_id:
            return self.by_id[value]
        hit = self.search(value, 1)
        return self.by_id[hit[0]["id"]] if hit else None

    def departures(self, stations, when, limit=30, exclude=(), ending=False):
        """Buses leaving the stations from `when`. ending: also buses that end their trip there ("ends": true);
        sadzv lists those as departures too (Banská Bystrica city buses at a terminus, EM-0019)."""
        d = datetime.fromtimestamp(when, ZONE).date()
        rows = []
        stations = self.with_platforms(stations)
        for day in (self.day(d - timedelta(days=1)), self.day(d)):
            t0 = when - day.base
            for s in stations:
                conns = [(c, False) for c in day.from_station.get(s, ())]
                if ending:
                    conns += [(c, True) for c in day.to_station.get(s, ())]
                for c, arriving in conns:
                    dep, arr, _, _, run, k = day.conns[c]
                    at = arr if arriving else dep
                    if at < t0:
                        continue
                    trip, service_day = self.run_trip(day, run)
                    if arriving and k + 2 != len(trip[7]):  # only the trip's last stop
                        continue
                    if any(trip[0].startswith(x) for x in exclude):  # "sad-zvolen-as" also drops its city buses
                        continue
                    rows.append((day.base + at, trip, service_day, k + arriving, s, arriving))
        rows.sort(key=lambda r: r[0])
        out, seen = [], set()
        for at, trip, service_day, k, s, arriving in rows:
            key = (trip[0], trip[1], service_day)
            if key in seen:
                continue
            seen.add(key)
            set_up = self.platform_for(s, trip[2], trip, k)
            out.append({"at": at * 1000, "tripId": f"{trip[0]}:{trip[1]}:{service_day:%Y%m%d}", "line": trip[2],
                        "headsign": trip[3] or self.names[trip[7][-1]], "operator": trip[4], "mode": trip[5],
                        "platform": (set_up[2] if set_up and set_up[2] else trip[10][k]), "stop": self.station_json(s), "seq": k, "feed": trip[0],
                        **({"ends": True} if arriving else {})})
            if len(out) >= limit:
                break
        return out

    def line_routes(self, line, limit=60):
        """
        Every way line `line` goes ("34", "60101"): one trip for each list of stops, dated the next day it runs, the
        most frequent first. For drawing the roads of buses that aren't on the road right now.
        """
        want = line_key(line)
        if not want:
            return []
        today = (datetime.now(ZONE).date() - self.first_day).days
        groups = {}
        for i, trip in enumerate(self.trips):
            if line_key(trip[2]) == want:
                groups.setdefault((trip[0], tuple(trip[7])), []).append(i)
        out = []
        for (_, stops), indexes in groups.items():
            trip = self.trips[indexes[0]]
            days = self.services.get(trip[6], ())
            day = self.first_day + timedelta(days=min((d for d in days if d >= today), default=max(days, default=today)))
            out.append({"tripId": f"{trip[0]}:{trip[1]}:{day:%Y%m%d}", "line": trip[2], "headsign": trip[3] or self.names[stops[-1]],
                        "from": self.names[stops[0]], "to": self.names[stops[-1]], "stops": len(stops), "trips": len(indexes),
                        "operator": trip[4]})
        out.sort(key=lambda r: (-r["trips"], r["from"], r["to"]))
        return out[:limit]

    def trip(self, trip_id):
        feed, tid, day_text = trip_id.split(":", 1)[0], *trip_id.split(":", 1)[1].rsplit(":", 1)
        index = self.trip_index.get((feed, tid))
        if index is None:
            return None
        trip = self.trips[index]
        base = base_epoch(datetime.strptime(day_text, "%Y%m%d").date())
        stops = []
        for k, s in enumerate(trip[7]):
            stop = dict(self.station_json(s), arrival=(base + trip[8][k]) * 1000, departure=(base + trip[9][k]) * 1000, platform=trip[10][k])
            set_up = self.platform_for(s, trip[2], trip, k)
            if set_up:
                stop.update(lat=set_up[0], lon=set_up[1], platform=set_up[2] or stop["platform"])
            if s in self.zones:
                stop["zone"] = self.zones[s]
            if s in self.parent_of:
                stop["central"] = self.station_json(self.parent_of[s][0])
            stops.append(stop)
        city = self.city.get(index)
        if city:  # sadzv's platform, stop order and stop of each stop (the apps move a change and follow it live)
            for k, pid in enumerate(city[4]):
                stops[k].update(platformId=int(pid), seq=k + 1, stopId=self.city_platforms[pid][4])
        return {"tripId": trip_id, "line": trip[2], "headsign": trip[3] or stops[-1]["name"], "operator": trip[4],
                "mode": trip[5], "stops": stops}

    # ---- connection search (CSA)

    def plan(self, origin, target, when, count=5, direct=False, change=CHANGE_S, walk_start=True):
        """
        Up to `count` connections leaving `origin` from `when` (epoch s). Each is the one with the
        fewest vehicles that arrives at most a little later than the fastest, and among equal
        arrivals the one that leaves last. change: the least time between two buses (s), the rider's setting.
        walk_start=False: board at `origin` itself, no walk to another stop first (a change stop the rider chose).
        """
        day = self.day(datetime.fromtimestamp(when, ZONE).date())
        t = when - day.base
        out = []
        for _ in range(count * 4):
            if len(out) >= count or t >= 2 * 86400:
                break
            found = self._search(day, origin, target, t, 1 if direct else MAX_VEHICLES, change, walk_start)
            if not found:
                break
            arrival, vehicles = found
            legs = self._latest(day, origin, target, t, arrival, vehicles, change, walk_start)
            if not legs:
                break
            journey = self._journey(day, legs)
            buses = lambda j: sum(leg["type"] == "trip" for leg in j["legs"])  # noqa: E731
            if out and out[-1]["arrival"] == journey["arrival"]:
                # Same arrival, later start: better only without an extra change. A city bus that
                # catches the same regional bus further on must not replace that direct bus (EM-0014).
                if buses(journey) <= buses(out[-1]):
                    out[-1] = journey
            else:
                out.append(journey)
            t = (day.conns[legs[0][1]][0] if legs[0][0] == "trip" else legs[0][2]) + 60
        return out

    def _targets(self, target):
        """Stations from which the target is reached, with the walk it takes."""
        return {target: 0, **{s: w for s, w in self.foot[target]}}

    def _earliest(self, day, origin, target, t0, change, walk_start=True):
        """The earliest arrival at the target with any number of vehicles, in one pass (None: unreachable today)."""
        conns, ends, foot = day.conns, self._targets(target), self.foot
        ready = [math.inf] * len(self.names)
        ready[origin] = t0
        for s, w in (foot[origin] if walk_start else ()):
            ready[s] = min(ready[s], t0 + w)
        on, best, horizon = set(), math.inf, t0 + MAX_TRAVEL_S
        for i in range(bisect.bisect_left(day.deps, t0), len(conns)):
            dep, arr, a, b, run, _ = conns[i]
            if dep >= best or dep > horizon:
                break
            if run not in on:
                if ready[a] > dep:
                    continue
                on.add(run)
            if b in ends and arr + ends[b] < best:
                best = arr + ends[b]
            if arr + change < ready[b]:
                ready[b] = arr + change
                for s2, w in foot[b]:
                    if arr + max(w, change) < ready[s2]:
                        ready[s2] = arr + max(w, change)
        return None if best == math.inf else best

    def _search(self, day, origin, target, t0, rounds, change=CHANGE_S, walk_start=True):
        """
        Connection scan in rounds: round r finds the earliest arrivals using at most r vehicles.
        Returns (arrival, vehicles) of the pick: the fewest vehicles arriving within the slack of the fastest.
        """
        conns, ends = day.conns, self._targets(target)
        start = bisect.bisect_left(day.deps, t0)
        horizon = t0 + MAX_TRAVEL_S
        if rounds > 1:
            # One quick pass finds the fastest arrival; the rounds then only scan up to it plus the slack
            # (twice, for safety) instead of a whole day, which made long trips take seconds.
            fastest = self._earliest(day, origin, target, t0, change, walk_start)
            if fastest is None:
                return None
            horizon = min(horizon, fastest + 2 * (SLACK_S + (fastest - t0) * SLACK_SHARE))
        ready = [math.inf] * len(self.names)  # per station: when a vehicle can be boarded there (a list: fast)
        ready[origin] = t0
        for s, w in (self.foot[origin] if walk_start else ()):
            ready[s] = min(ready[s], t0 + w)
        found = []                # (arrival at target, round)
        best = math.inf
        for r in range(1, rounds + 1):
            new, on = {}, set()   # new[station] = arrival in this round
            for i in range(start, len(conns)):
                dep, arr, a, b, run, _ = conns[i]
                if dep >= best or dep > horizon:
                    break
                if run not in on:
                    if ready[a] > dep:
                        continue
                    on.add(run)
                if arr < new.get(b, math.inf):
                    new[b] = arr
                    if b in ends and arr + ends[b] < best:
                        best = arr + ends[b]
            end = min((arr + ends[s] for s, arr in new.items() if s in ends), default=None)
            if end is not None:
                found.append((end, r))
                # Rounds only add vehicles: the first within the slack of the fastest is the pick already.
                if rounds > 1 and end <= fastest + SLACK_S + (fastest - t0) * SLACK_SHARE:
                    return end, r
            if not new or r == rounds:
                break
            ready = ready[:]
            for s, arr in new.items():
                if arr + change < ready[s]:
                    ready[s] = arr + change
                for s2, w in self.foot[s]:  # a walk to another stop takes at least the change time too
                    if arr + max(w, change) < ready[s2]:
                        ready[s2] = arr + max(w, change)
        if not found:
            return None
        fastest = min(found)[0]
        slack = SLACK_S + (fastest - t0) * SLACK_SHARE
        arrival, r = min((f for f in found if f[0] <= fastest + slack), key=lambda f: (f[1], f[0]))
        return arrival, r

    def _latest(self, day, origin, target, t0, deadline, rounds, change=CHANGE_S, walk_start=True):
        """
        The mirror of _search: with at most `rounds` vehicles and arriving by `deadline`, the
        journey that leaves `origin` last. Same legs format.
        """
        conns, ends = day.conns, self._targets(target)
        starts = {origin: 0, **{s: w for s, w in (self.foot[origin] if walk_start else ())}}
        # need[r] = (per station: latest arrival there by vehicle that still works, {station: what comes next})
        times = [-math.inf] * len(self.names)
        for s, w in ends.items():
            times[s] = deadline - w
        need = [(times, {s: ("end", w) for s, w in ends.items()})]
        departures = [{}]         # per round: station -> (departure, board conn, alight conn)
        found = []                # (departure from origin, round, start station)
        best = -math.inf
        top = bisect.bisect_right(day.arrivals, deadline) - 1
        for r in range(1, rounds + 1):
            (prev, prev_how), new, off = need[r - 1], {}, {}
            for j in range(top, -1, -1):
                i = day.by_arrival[j]
                dep, arr, a, b, run, _ = conns[i]
                if arr <= best or arr < t0:
                    break
                if dep < t0:
                    continue
                if run not in off:
                    if prev[b] < arr:
                        continue
                    off[run] = i
                if dep > new.get(a, (-math.inf,))[0]:
                    new[a] = (dep, i, off[run])
                    if a in starts and dep - starts[a] > best:
                        best = dep - starts[a]
            departures.append(new)
            start = max(((dep - starts[s], s) for s, (dep, _, _) in new.items() if s in starts), default=None)
            if start:
                found.append((start[0], r, start[1]))
            if not new or r == rounds:
                break
            nxt, how = prev[:], dict(prev_how)
            for s, (dep, _, _) in new.items():
                if dep - change > nxt[s]:
                    nxt[s], how[s] = dep - change, ("dep", r, s)
                for s2, w in self.foot[s]:
                    if dep - max(w, change) > nxt[s2]:
                        nxt[s2], how[s2] = dep - max(w, change), ("walk", r, s, w)
            need.append((nxt, how))
        if not found:
            return None
        leave, r, s = max(found, key=lambda f: (f[0], -f[1]))
        legs = [("walk", origin, leave, s, starts[s])] if s != origin else []
        while True:
            _, board, alight = departures[r][s]
            legs.append(("trip", board, alight))
            b = conns[alight][3]
            how = need[r - 1][1][b]
            if how[0] == "end":
                if b != target:
                    legs.append(("walk", b, conns[alight][1], target, how[1]))
                return legs
            r, s = how[1], how[2]
            if how[0] == "walk":
                legs.append(("walk", b, conns[alight][1], s, how[3]))

    def leg_stop(self, s, trip, k):
        """Where a connection gets on or off: the station, at the place or platform set up for this line in the dashboard."""
        stop = dict(self.station_json(s), seq=k, platform=trip[10][k])
        set_up = self.platform_for(s, trip[2], trip, k)
        if set_up:
            stop.update(lat=set_up[0], lon=set_up[1], platform=set_up[2] or stop["platform"])
        if s in self.parent_of:
            stop["central"] = self.station_json(self.parent_of[s][0])
        return stop

    def _journey(self, day, legs):
        out = []
        for leg in legs:
            if leg[0] == "walk":
                _, a, at, b, w = leg
                out.append({"type": "walk", "from": self.station_json(a), "to": self.station_json(b),
                            "departure": (day.base + at) * 1000, "arrival": (day.base + at + w) * 1000})
                continue
            _, i, j = leg
            first, last = day.conns[i], day.conns[j]
            trip, service_day = self.run_trip(day, first[4])
            out.append({"type": "trip", "tripId": f"{trip[0]}:{trip[1]}:{service_day:%Y%m%d}", "line": trip[2],
                        "headsign": trip[3] or self.names[trip[7][-1]], "operator": trip[4], "mode": trip[5],
                        "from": self.leg_stop(first[2], trip, first[5]), "to": self.leg_stop(last[3], trip, last[5] + 1),
                        "departure": (day.base + first[0]) * 1000, "arrival": (day.base + last[1]) * 1000})
            city = self.city.get(first[4] // 3)
            if city:
                # A city bus: the apps follow it live in sadzv (its stop order counts from 1).
                line_id, route, number, line, pids = city
                # sadzv only knows today's run: on another day the apps open the timetable trip instead
                # (tripId with fromIndex/toIndex, the same as any timetable leg).
                out[-1]["live"] = {"lineId": line_id, "routeNumber": route, "tripNumber": number, "line": line,
                                   "stopId": self.city_platforms[pids[first[5]]][4], "fromIndex": first[5], "toIndex": last[5] + 1}
                out[-1]["from"].update(platformId=int(pids[first[5]]), seq=first[5] + 1)
                out[-1]["to"].update(platformId=int(pids[last[5] + 1]), seq=last[5] + 2)
        return {"departure": out[0]["departure"], "arrival": out[-1]["arrival"], "legs": out}


class Day:
    def __init__(self, d, base, conns):
        self.date, self.base, self.conns = d, base, conns
        self.deps = [c[0] for c in conns]
        self.by_arrival = sorted(range(len(conns)), key=lambda i: conns[i][1])
        self.arrivals = [conns[i][1] for i in self.by_arrival]
        self.from_station, self.to_station = {}, {}
        for i, c in enumerate(conns):
            self.from_station.setdefault(c[2], []).append(i)
            self.to_station.setdefault(c[3], []).append(i)


def along(points, f):
    """The point a fraction f of the way along a polyline [(lat, lon), …]."""
    if len(points) < 2:
        return points[0]
    lengths = [meters(*p, *q) for p, q in zip(points, points[1:])]
    goal, total = f * sum(lengths), 0.0
    for (p, q), l in zip(zip(points, points[1:]), lengths):
        if total + l >= goal and l > 0:
            g = (goal - total) / l
            return p[0] + (q[0] - p[0]) * g, p[1] + (q[1] - p[1]) * g
        total += l
    return points[-1]


def name_keys(name):
    """
    A stop's name as a direction can say it: the whole name, or just its town. "Zvolen, Západ" -> {"zvolen zapad",
    "zvolen"}, so "towards Krupina" fits every Krupina stop while "towards Zvolen, Západ" fits only that one.
    """
    return {" ".join(fold(str(name))), " ".join(fold(str(name).split(",")[0]))}


def directions_of(spot):
    """A place's or sub-station's directions; one set up before directions existed has its lines as its only one."""
    return spot.get("directions") or ([{"name": "", "lines": spot.get("lines") or [], "towards": []}] if spot.get("lines") else [])


def line_key(line):
    """A line as the dashboard writes it and the timetable has it: "60101", "Linka 34" -> "34"."""
    return str(line or "").strip().split(" ")[-1].lower()


def inside_polygon(lat, lon, points):
    """Ray casting: is (lat, lon) inside the polygon [[lat, lon], …]?"""
    inside, j = False, len(points) - 1
    for i in range(len(points)):
        (yi, xi), (yj, xj) = points[i], points[j]
        if (yi > lat) != (yj > lat) and lon < (xj - xi) * (lat - yi) / ((yj - yi) or 1e-12) + xi:
            inside = not inside
        j = i
    return inside


# Map edits from the dashboard (the hub loads them from its database): moved stops by station id
# {"name", "lat", "lon", "origLat", "origLon"}; road traces between two stations "from>to" -> [[lat, lon], …];
# stations' extra places, zones and the central flag {"name", "places": [{"label", "platform", "lat", "lon", "lines", "directions"}],
# "zone", "central"}; and sub-stations of central stations {"name", "parent", "label", "platform", "lines", "directions"} by the
# sub-station's id. A direction is {"name", "lines", "towards"}; "lines" of a place is all its directions' lines together.
edits = {"stops": {}, "segments": {}, "stations": {}, "children": {}}


# ------------------------------------------------------------------ downloads, the daily refresh, the log

current = None  # the Timetable in use; replaced whole
LOG = collections.deque(maxlen=300)   # (epoch s, text) for the dashboard
downloads = {}                        # feed -> {"at", "result"} of the last download attempt
_update_lock = threading.Lock()
_wake = threading.Event()


def log(text):
    LOG.append((int(time.time()), text))
    print(f"[timetable] {text}", flush=True)


def fetch(url, path):
    """The feed at url if it changed since our copy (which must have come from the same place), else None."""
    source = path.with_suffix(".src")  # where our copy came from: a copy from elsewhere is never "unchanged"
    request = urllib.request.Request(url, headers={"User-Agent": "emhub (Plan&Ride)"})
    if path.exists() and source.exists() and source.read_text() == url:
        request.add_header("If-Modified-Since", formatdate(path.stat().st_mtime, usegmt=True))
    try:
        with urllib.request.urlopen(request, timeout=120) as response:
            data = response.read()
    except urllib.error.HTTPError as error:
        if error.code == 304:
            return None
        raise
    zipfile.ZipFile(io.BytesIO(data)).testzip()
    tmp = path.with_suffix(".tmp")
    tmp.write_bytes(data)
    tmp.replace(path)
    source.write_text(url)
    return data


def download(folder, feeds):
    """Fetch feeds that changed (If-Modified-Since); a failed download keeps the previous file.
    Our CIS conversion first; only a feed it doesn't have (404) falls back to transiq."""
    folder.mkdir(parents=True, exist_ok=True)
    changed = 0
    for feed in feeds:
        path = folder / f"{feed}.zip"
        try:
            try:
                url = CIS_URL.format(feed) if CIS_URL else FEED_URL.format(feed)
                data = fetch(url, path)
            except urllib.error.HTTPError as error:
                if not CIS_URL or error.code != 404:
                    raise
                url = FEED_URL.format(feed)
                data = fetch(url, path)
            origin = "cis" if url != FEED_URL.format(feed) else "transiq"
            result = "unchanged" if data is None else f"updated ({len(data) // 1024} kB, {origin})"
            changed += data is not None
        except urllib.error.HTTPError as error:
            result = f"HTTP {error.code}"
        except Exception as error:  # noqa: BLE001
            result = f"failed: {error}"
        downloads[feed] = {"at": int(time.time()), "result": result}
        if result != "unchanged":
            log(f"{feed}: {result}")
    return changed


def city_days(folder):
    """The days of city buses read by sadzv.collect, oldest first."""
    days = []
    for path in sorted(Path(folder).glob("sadzv-*.json")):
        try:
            days.append(json.loads(path.read_text(encoding="utf-8")))
        except Exception as error:  # noqa: BLE001
            log(f"{path.name}: {error}")
    return days


def load(folder, feeds):
    global current
    started = time.time()
    paths = {f: folder / f"{f}.zip" for f in feeds if (folder / f"{f}.zip").exists()}
    built = Timetable(paths, city=city_days(folder))
    today = datetime.now(ZONE).date()
    for d in (today, today + timedelta(days=1)):  # the first search of the day mustn't wait for this
        built.day(d)
    current = built
    failed = [f for f, n in built.feeds.items() if n == "failed"]
    log(f"loaded {len(built.trips)} trips, {len(built.names)} stations from {len(paths)} feeds "
        f"in {time.time() - started:.0f} s" + (f"; failed: {', '.join(failed)}" if failed else ""))


_collect = None  # sadzv.collect, set by start(): today's city buses


def update(folder, feeds, fetch=True):
    """Download (optional), read today's city buses (once a day) and rebuild; one at a time."""
    if not _update_lock.acquire(blocking=False):
        return False
    try:
        if fetch:
            log(f"checking {len(feeds)} feeds for updates")
            download(folder, feeds)
        if _collect:
            try:
                _collect(folder)
            except Exception as error:  # noqa: BLE001 - the previous days still stand in
                log(f"city buses not read: {error}")
        load(folder, feeds)
    except Exception as error:  # noqa: BLE001 - keep the previous timetable
        traceback.print_exc()
        log(f"update failed, keeping the previous timetable: {error}")
    finally:
        _update_lock.release()
    return True


def refresher(folder, feeds):
    """Load at start (downloading anything missing), then refresh every night at 04:30 or when asked."""
    # A feed we don't have, or don't know the source of (from before 1.12.0), is fetched right away.
    update(folder, feeds(), fetch=any(not (folder / f"{f}.zip").exists() or not (folder / f"{f}.src").exists() for f in feeds()))
    while True:
        now = datetime.now(ZONE)
        wake = now.replace(hour=4, minute=30, second=0, microsecond=0)
        if wake <= now:
            wake += timedelta(days=1)
        _wake.wait((wake - now).total_seconds())
        _wake.clear()
        update(folder, feeds())


def start(folder, feeds, collect=None):
    """feeds: a function returning the feed list (it can change in the dashboard); collect: sadzv.collect."""
    global _collect
    _collect = collect
    threading.Thread(target=refresher, args=(Path(folder), feeds), daemon=True).start()


def request_update():
    """Ask the refresher to download and rebuild now (the dashboard's button)."""
    _wake.set()


def status(folder, feeds):
    t = current
    files = {}
    for feed in feeds:
        path = Path(folder) / f"{feed}.zip"
        files[feed] = {"size": path.stat().st_size if path.exists() else None,
                       "modified": int(path.stat().st_mtime) if path.exists() else None,
                       "trips": (t.feeds.get(feed) if t else None), **downloads.get(feed, {})}
    days = sorted(Path(folder).glob("sadzv-*.json"))
    if days:
        files[CITY_FEED] = {"size": days[-1].stat().st_size, "modified": int(days[-1].stat().st_mtime),
                            "trips": t.feeds.get(CITY_FEED) if t else None,
                            "result": f"{len(days)} days read from sadzv, latest {days[-1].stem[6:]}", "at": int(days[-1].stat().st_mtime)}
    return {"ready": t is not None, "busy": _update_lock.locked(), "builtAt": int(t.built) if t else None,
            "trips": len(t.trips) if t else 0, "stations": len(t.names) if t else 0, "feeds": files,
            "log": [{"at": at, "text": text} for at, text in reversed(LOG)]}
