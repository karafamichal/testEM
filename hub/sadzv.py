"""
City buses from S.A.D. Zvolen's live data (sadzv.qrbus.me, public, the same calls its map page makes).

The open timetables have SAD Zvolen's regional lines but not its city lines in Zvolen and
Banská Bystrica. When both ends of a search are near sadzv stops, the planner asks here too:
the next departures at the start and which of those buses pass the destination later on.
Direct buses only, and only as far ahead as sadzv's board reaches (usually several hours).
"""
import json
import threading
import time
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone

from timetable import ZONE, city_operator, meters

BASE = "https://sadzv.qrbus.me/index/"
NEAR_M = 250            # a sadzv platform this close to a searched stop counts as that stop
MAX_TRIPS = 25          # stop lists fetched per search at most (cached for the day)
_lock = threading.Lock()
_cache = {}             # key -> (expires, value)
_pool = ThreadPoolExecutor(max_workers=6)


def _call(path, body=None):
    request = urllib.request.Request(BASE + path, data=json.dumps(body).encode() if body is not None else None, headers={
        "X-Requested-With": "XMLHttpRequest", "Content-Type": "application/json; charset=utf-8", "User-Agent": "emhub (Plan&Ride)"})
    with urllib.request.urlopen(request, timeout=20) as response:
        return json.load(response)


def _cached(key, ttl, fetch):
    now = time.time()
    with _lock:
        hit = _cache.get(key)
        if hit and hit[0] > now:
            return hit[1]
    value = fetch()
    with _lock:
        if len(_cache) > 5000:
            _cache.clear()  # ponytail: crude bound, fine for one city's worth of trips
        _cache[key] = (now + ttl, value)
    return value


def platforms():
    """[(id, name, lat, lon, number, stop id)], cached for a day."""
    def fetch():
        root = _call("getAllPlatforms")
        rows = root if isinstance(root, list) else root.get("platforms", [])
        return [(int(r[0]), str(r[1]).strip(), r[2] / 1e5, r[3] / 1e5, str(r[4] or ""), int(r[5] or 0))
                for r in rows if isinstance(r, list) and len(r) >= 6 and r[2]]
    return _cached("platforms", 86400, fetch)


def near(lat, lon):
    return {p[0] for p in platforms() if abs(p[2] - lat) < 0.01 and abs(p[3] - lon) < 0.015 and meters(lat, lon, p[2], p[3]) <= NEAR_M}


def locate(name, town_center):
    """
    Where a timetable stop without a position is, from sadzv's stop of the same name: "Zvolen,
    Nám.SNP" is "Námestie SNP" there (town left out, words shortened), and the town's centre
    picks between towns with the same street.
    """
    from timetable import fold, match_score
    words = fold(name.split(",", 1)[1] if "," in name else name)
    if not words or not town_center:
        return None
    best = None
    for p in platforms():
        d = meters(town_center[0], town_center[1], p[2], p[3])
        score = match_score(words, fold(p[1])) if d < 8000 else None
        if score is not None and (best is None or (score, -d) > best[0]):
            best = ((score, -d), (p[2], p[3]))
    return best[1] if best else None


KEEP_DAYS = 21


def collect(folder):
    """
    Today's timetable of every sadzv line (Zvolen and Banská Bystrica city buses): each platform's
    list of the day's trips, put back together into trips by line, route and trip number. Saved
    as sadzv-YYYYMMDD.json; sadzv only gives today, so the timetable reuses it for later days of
    the same kind. Returns True when a new day was saved.
    """
    from pathlib import Path
    from timetable import log
    folder = Path(folder)
    day = datetime.now(ZONE).date()
    path = folder / f"sadzv-{day:%Y%m%d}.json"
    if path.exists():
        return False
    with _lock:
        _cache.pop("platforms", None)  # new stops appear with timetable changes
    plats = {p[0]: p for p in platforms()}

    def fetch(pid):
        for attempt in range(3):  # sadzv refuses some calls when asked too fast
            try:
                return pid, _call("getTripsOnPlatform", {"platformIds": [pid], "organizationSystemEntityId": 0}).get("lines") or []
            except Exception:  # noqa: BLE001 - counted below
                time.sleep(1 + attempt * 2)
        return pid, None
    trips, failed = {}, 0
    with ThreadPoolExecutor(max_workers=3) as pool:
        results = list(pool.map(fetch, plats))
    for pid, lines in results:
        if lines is None:
            failed += 1
            continue
        for line in lines:
            for route in line.get("routes") or []:
                for t in route.get("trips") or []:
                    # timeUnix is wall-clock time written as if it were UTC, like everything from sadzv.
                    wall = datetime.fromtimestamp(t["timeUnix"], timezone.utc)
                    secs = (wall.date() - day).days * 86400 + wall.hour * 3600 + wall.minute * 60 + wall.second
                    key = f'{line["id"]}-{route.get("routeNumber")}-{t["number"]}'
                    trip = trips.setdefault(key, {
                        "lineId": line["id"], "line": str(line.get("numberText") or line.get("number") or "").rstrip("_"),
                        "route": str(route.get("routeNumber") or ""), "trip": t["number"],
                        "headsign": str(route.get("endName") or "").strip(), "stops": []})
                    trip["stops"].append([pid, secs])
    if failed > len(plats) // 10:
        raise RuntimeError(f"sadzv: {failed} of {len(plats)} platforms failed, today's city timetable not saved")
    for trip in trips.values():
        trip["stops"].sort(key=lambda s: s[1])
    data = {"date": day.isoformat(), "trips": [t for t in trips.values() if len(t["stops"]) >= 2],
            "platforms": {str(p[0]): [p[1], p[2], p[3], p[4], p[5]] for p in plats.values()}}
    folder.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(".tmp")
    tmp.write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
    tmp.replace(path)
    for old in sorted(folder.glob("sadzv-*.json"))[:-KEEP_DAYS]:
        old.unlink()
    log(f"sadzv: {len(data['trips'])} city bus trips of {len({t['lineId'] for t in data['trips']})} lines "
        f"from {len(plats) - failed} platforms" + (f" ({failed} failed)" if failed else ""))
    return True


def _departures(ids):
    key = ("deps", tuple(sorted(ids)))
    return _cached(key, 60, lambda: (_call("getDeparturesOnPlatformWeb", {"platformIds": sorted(ids), "organizationSystemEntityId": 0})
                                     or {}).get("departures", []))


def _wall(seconds):
    """sadzv trip times are Slovak wall-clock time written as if it were UTC."""
    local = datetime.fromtimestamp(seconds, timezone.utc).replace(tzinfo=ZONE)
    return int(local.timestamp())


def _trip(d, day):
    key = ("trip", d["lineId"], d.get("routeNumber"), d["tripNumber"], day)
    body = {"lineId": d["lineId"], "lineNumber": d["lineNumberText"], "tripNumber": d["tripNumber"],
            "routeNumber": d.get("routeNumber"), "organizationSystemEntityId": 0}
    return _cached(key, 6 * 3600, lambda: [
        (int(p["platformId"]), int(p.get("stopOrder") or 0), str(p.get("stopName") or "").strip(), _wall(int(p["departureTime"])))
        for p in sorted(_call("getPlatformsDataWithHistory", body).get("platforms") or [], key=lambda p: p.get("stopOrder") or 0)
        if p.get("platformId") and p.get("departureTime")])


def direct(origin, target, when, count=5, name_of=None):
    """
    Up to `count` direct buses from near `origin` (lat, lon) to near `target`, leaving from `when`
    (epoch s), as legs in the timetable API's format plus "live" (what the apps need to follow
    the bus live). `name_of(lat, lon)` gives our stop name for a platform, else sadzv's is used.
    """
    starts, ends = near(*origin), near(*target)
    ends -= starts
    if not starts or not ends or when > time.time() + 12 * 3600:
        return []
    now = time.time()
    deps = [d for d in _departures(starts) if d.get("lineId") and not d.get("cancelled")
            and now + int(d.get("real") or 0) >= when - 300][:MAX_TRIPS]
    today = datetime.now(ZONE).strftime("%Y%m%d")
    stops = {p[0]: p for p in platforms()}
    out = []
    for d, trip in zip(deps, _pool.map(lambda d: _safe_trip(d, today), deps)):
        board = next((k for k, p in enumerate(trip) if p[0] in starts), None)
        if board is None or trip[board][3] < when - 60:
            continue
        alight = next((k for k in range(board + 1, len(trip)) if trip[k][0] in ends), None)
        if alight is None:
            continue
        a, b = trip[board], trip[alight]
        line = str(d["lineNumberText"]).rstrip("_")
        day = datetime.fromtimestamp(a[3], ZONE).strftime("%Y%m%d")

        def place(p):
            s = stops.get(p[0])
            lat, lon = (s[2], s[3]) if s else (None, None)
            return {"id": f"sadzv-{p[0]}", "name": (name_of(lat, lon) if name_of and lat else None) or (s[1] if s else p[2]),
                    "lat": lat, "lon": lon, "seq": p[1], "platform": s[4] if s else "", "platformId": p[0]}
        out.append({"type": "trip", "tripId": f"sadzv:{d['lineId']}:{d.get('routeNumber')}:{d['tripNumber']}:{day}",
                    "line": line, "headsign": str(d.get("destinationName") or trip[-1][2]).strip(), "operator": city_operator(stops[a[0]][2] if a[0] in stops else None),
                    "mode": "bus", "from": place(a), "to": place(b), "departure": a[3] * 1000, "arrival": b[3] * 1000,
                    "live": {"lineId": d["lineId"], "routeNumber": str(d.get("routeNumber") or ""), "tripNumber": d["tripNumber"],
                             "line": line, "stopId": stops[a[0]][5] if a[0] in stops else 0}})
    out.sort(key=lambda leg: leg["departure"])
    seen, result = set(), []
    for leg in out:
        if leg["tripId"] not in seen:
            seen.add(leg["tripId"])
            result.append(leg)
    return result[:count]


def _safe_trip(d, day):
    try:
        return _trip(d, day)
    except Exception:  # noqa: BLE001 - one missing stop list must not sink the search
        return []


def journeys(origin, target, when, count=5, name_of=None):
    """direct() as whole connections, the shape the plan endpoint returns."""
    try:
        legs = direct(origin, target, when, count, name_of)
    except Exception as error:  # noqa: BLE001 - sadzv down: the timetable answer still stands
        print(f"[sadzv] {error}", flush=True)
        return []
    return [{"departure": leg["departure"], "arrival": leg["arrival"], "legs": [leg]} for leg in legs]

