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
    # Some platforms don't list every trip stopping there (line 1 in Banska Bystrica lost 5 of its 14 stops, its end
    # too): each route's whole run is asked once and every trip of it gets the missing stops back.
    routes = {}
    for trip in trips.values():
        routes.setdefault((trip["lineId"], trip["route"]), []).append(trip)

    def run_of(group):
        t = max(group, key=lambda t: len(t["stops"]))
        body = {"lineId": t["lineId"], "lineNumber": t["line"], "tripNumber": t["trip"],
                "routeNumber": int(t["route"]) if t["route"].isdigit() else t["route"], "organizationSystemEntityId": 0}
        for attempt in range(3):
            try:
                return [(int(x["platformId"]), int(x["departureTime"]), int(x.get("stopOrder") or 0))
                        for x in sorted(_call("getPlatformsDataWithHistory", body).get("platforms") or [], key=lambda x: x.get("stopOrder") or 0)
                        if x.get("platformId") and x.get("departureTime")]
            except Exception:  # noqa: BLE001 - the trip keeps the stops it has
                time.sleep(1 + attempt * 2)
        return None
    with ThreadPoolExecutor(max_workers=3) as pool:
        runs = dict(zip(routes, pool.map(run_of, routes.values())))
    fetched = dict(runs)  # as sadzv gives them: the way back is read from these
    for key, run in runs.items():
        if run:
            runs[key] = bridge(run, [r for (line_id, _), r in runs.items() if line_id == key[0] and r])
    # S.A.D. leaves some ways without their stops for the whole day (line 6 towards Zeleznicna stanica: every run ends
    # at Nad Moskovskou or jumps from stop 5 to 16; line 1 likewise). Rebuilt from the line's way back, then a run that
    # ends early goes on like the line's run to the same place.
    stop_of = {p[0]: p for p in plats.values()}
    for key, run in runs.items():
        if run:
            runs[key] = bridge_back(run, [r for (line_id, other), r in fetched.items() if line_id == key[0] and other != key[1] and r], stop_of)
    for key, run in runs.items():
        if run:
            runs[key] = extend(run, [r for (line_id, other), r in runs.items() if line_id == key[0] and other != key[1] and r],
                               stop_of, routes[key][0]["headsign"])
    for key, group in routes.items():
        for trip in group:
            trip["stops"] = fill_stops(trip["stops"], runs[key] and [(pid, t) for pid, t, _ in runs[key]])
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


def bridge(run, others):
    """
    sadzv leaves out stops of some runs itself: stop numbers jump (EM-0062: line 1 to Zeleznicna stanica as stops 1, 2,
    3, 12). The stops between come from a run of the same line through both ends with nothing left out, timed in
    proportion within the gap; none when no such run has exactly the missing number (line 1's other way has 10 for 8).
    run, others: [(platform, time, stop number)]; returns the run with its gaps filled.
    """
    out = [run[0]]
    for (p, tp, op), (q, tq, oq) in zip(run, run[1:]):
        if oq - op > 1:
            for other in others:
                if any(b[2] - a[2] != 1 for a, b in zip(other, other[1:])):
                    continue
                ids = [x[0] for x in other]
                # Exactly as many stops between as are missing: another number means another way (not a guess).
                if p in ids and q in ids[ids.index(p) + 2:] and ids.index(q, ids.index(p) + 2) - ids.index(p) == oq - op:
                    a, b = ids.index(p), ids.index(q, ids.index(p) + 2)
                    span = (other[b][1] - other[a][1]) or 1
                    out += [(x[0], tp + round((x[1] - other[a][1]) * (tq - tp) / span), 0) for x in other[a + 1:b]]
                    break
        out.append((q, tq, oq))
    return out


def consecutive(run):
    """sadzv numbers a run's stops 1, 2, 3...: no number skipped means no stop left out."""
    return all(b[2] - a[2] == 1 for a, b in zip(run, run[1:]))


def bridge_back(run, others, stop_of):
    """
    Gaps no run of this way fills: from the line's way back, read backwards, when exactly as many stops lie before the
    gap's end there as are missing (line 6 towards Zeleznicna stanica misses 10, the way back has 10 between it and
    Tulska). When the gap's start is on the way back too, exactly as many must lie between the two (line 1 to Zeleznicna
    stanica misses 8 of the 10 back there: which 2 isn't known, so it stays). Each stop goes on its other platform (this
    way's side of the road), timed as far apart as on the way back, within the gap.
    run, others: [(platform, time, stop number)]; stop_of: platform -> (id, name, lat, lon, number, stop id).
    """
    def stop(pid):
        return stop_of[pid][5] if pid in stop_of else None

    def this_side(pid):
        """The same stop's platform nearest to pid other than pid (across the road), else pid."""
        here = stop_of.get(pid)
        if not here:
            return pid
        across = [x for x in stop_of.values() if x[5] == here[5] and x[0] != pid]
        return min(across, key=lambda x: meters(here[2], here[3], x[2], x[3]))[0] if across else pid

    out = [run[0]]
    for (p, tp, op), (q, tq, oq) in zip(run, run[1:]):
        missing = oq - op - 1
        if missing > 0:
            for other in others:
                if not consecutive(other) or stop(other[-1][0]) != stop(run[0][0]):
                    continue  # only the way back: it ends where this one starts
                back = other[::-1]
                stops = [stop(x[0]) for x in back]
                if stop(q) not in stops:
                    continue
                i = stops.index(stop(q))
                if stop(p) in stops[:i] and i - stops.index(stop(p)) - 1 != missing:
                    continue
                if i < missing + 1:
                    continue
                part = back[i - missing - 1:i + 1]  # the stop before them, them, the gap's end
                gaps = [abs(b[1] - a[1]) for a, b in zip(part, part[1:])]
                total, run_s = sum(gaps) or 1, 0
                for x, g in zip(part[1:-1], gaps):
                    run_s += g
                    out.append((this_side(x[0]), tp + round(run_s * (tq - tp) / total), 0))
                break
        out.append((q, tq, oq))
    return out


def reaches(run, stop_of, headsign):
    """Does the run end where it's going? Names are shortened there ("B.B.Roosevelt.nem." is Rooseveltova nemocnica)."""
    from timetable import fold, match_score
    name = stop_of.get(run[-1][0], (0, ""))[1]
    return bool(name) and match_score(fold(name), fold(headsign)) is not None


def extend(run, others, stop_of, headsign):
    """
    A run that ends before the place it's going to (6017 ends at Nad Moskovskou, "to Zeleznicna stanica"): the rest of
    the way from a run of the line that stops at the same platform (so goes the same way) and gets there, timed as on
    that run. Runs starting with the same stops first.
    """
    if reaches(run, stop_of, headsign):
        return run
    pids, last = [x[0] for x in run], run[-1][0]
    ranked = sorted(others, key=lambda o: [x[0] for x in o[:len(run)]] != pids)
    for other in ranked:
        ids = [x[0] for x in other]
        if last in ids[:-1] and reaches(other, stop_of, headsign):
            at = other[ids.index(last)]
            return run + [(x[0], run[-1][1] + x[1] - at[1], 0) for x in other[ids.index(last) + 1:]]
    return run


def fill_stops(stops, run):
    """
    A trip's [platform, seconds] stops with the ones missing from a whole run of its route ([(platform, time)]) put
    back, timed from the stop before them as on that run. Unchanged when its stops aren't that run's, in order.
    """
    if not run or not stops:
        return stops
    order, at, i = [p for p, _ in run], {}, 0
    for pid, secs in stops:
        if pid not in order[i:]:
            return stops
        i = order.index(pid, i)
        at[i] = secs
        i += 1
    out = []
    for k, (pid, t) in enumerate(run):
        ref = k if k in at else max((j for j in at if j < k), default=min(at))
        out.append([pid, at[ref] + t - run[ref][1]])
    return out


def vehicles():
    """
    City buses sending their GPS now (Banska Bystrica's; Zvolen's are weeks old there): {(line id, trip number): (lat,
    lon, delay s)}. A position over 3 minutes old doesn't count. Asked at most every 20 s.
    """
    def fetch():
        out, now = {}, time.time()
        for r in _call("getAllRtdVehicles", {}).get("vehicles") or []:
            if not isinstance(r, list) or len(r) < 8 or not r[3] or not r[0] or not r[6]:
                continue
            try:
                at = _wall(int(datetime.strptime(str(r[6])[:19], "%Y-%m-%dT%H:%M:%S").replace(tzinfo=timezone.utc).timestamp()))
            except ValueError:
                continue
            if abs(now - at) <= 180:
                out[(int(r[3]), int(r[5] or 0))] = (r[0] / 1e5, r[1] / 1e5, int(r[7] or 0))
        return out
    try:
        return _cached("vehicles", 20, fetch)
    except Exception:  # noqa: BLE001 - sadzv down: the map goes by timetable and riders
        return {}


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

