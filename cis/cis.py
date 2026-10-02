"""
cis: GTFS feeds for Slovak bus carriers from the CIS CP timetables (portal.cp.sk XLSX files).

Runs on the web server (not on emhub): every night it lists each carrier's lines on portal.cp.sk,
downloads XLSX files it doesn't have yet, and writes OUT/<feed>.zip, which emhub fetches over
the tunnel. A port of transiq's cis (github.com/xhyrom/transiq, Apache-2.0, Jozef Steinhübl)
with fixes: trips that don't start at the first stop row keep their times, and a line's current
timetable stays in use until its next published version starts.

    python3 cis.py [feed ...]      # default: all FEEDS
Stdlib only. Stop positions come from transiq's kaeru.csv (cis name -> name, lat, lon, district).
"""
import csv
import hashlib
import html
import io
import os
import re
import sys
import time
import unicodedata
import urllib.request
import xml.etree.ElementTree as ET
import zipfile
from datetime import date, timedelta
from pathlib import Path

DATA = Path(os.environ.get("CIS_DIR", "/var/lib/cis"))
PORTAL = "http://portal.cp.sk/"
KAERU_URL = "https://raw.githubusercontent.com/xhyrom/transiq/main/.transiq/kaeru.csv"
USER_AGENT = "emhub-cis (Plan&Ride timetables; karafa.net)"
PAUSE_S = 1.0  # between requests to portal.cp.sk
# feed name (as emhub knows it) -> carrier folder on the portal (sanitized carrier name)
FEEDS = {f: f for f in [
    "sad-zvolen-as", "sad-lucenec-as", "sad-zilina-as", "sad-trencin-as", "sad-prievidza-as", "sad-poprad-as",
    "sad-presov-sk", "sad-humenne-as", "sad-dunajska-streda-as", "arriva-liorbus-as", "arriva-michalovce-as",
    "arriva-nitra-as", "arriva-nove-zamky-as", "arriva-trnava-as", "eurobus-as", "slovak-lines-express-as",
    "a-express-sro", "cestovna-kancelaria-daka"]}
FEEDS["regiojet-as"] = "student-agency-ks"


def log(text):
    print(f"[cis] {text}", flush=True)


def get(url, timeout=60):
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return response.read()


# ------------------------------------------------------------------ portal.cp.sk: carriers, lines, files

def folder_name(name):
    """transiq's sanitizeFolderName: 'SAD Zvolen, a.s.' -> 'sad-zvolen-as'."""
    name = re.sub(r"[.,:;'\"!?()\[\]{}/\\+*=&%$#@^|<>]", "", name.lower())
    name = re.sub(r"-+", "-", re.sub(r"\s+", "-", name)).strip("-")
    return "".join(c for c in unicodedata.normalize("NFD", name) if not unicodedata.combining(c))


def carrier_short(full):
    if ", a.s.," in full or ", spol. s r.o.," in full:
        return ",".join(full.split(",")[:2])
    return full.split(",")[0]


def listing(io_, page):
    time.sleep(PAUSE_S)
    return get(f"{PORTAL}Search.aspx?c=7&mi=7&sv=&io={io_}&p={page}").decode("windows-1250", "replace")


def scrape(wanted):
    """{folder: {"full": carrier text, "files": [xlsx file names]}} for the wanted carrier folders."""
    found = {}
    io_ = 0
    while True:
        body = listing(io_, 0)
        if "Chyba spracovania dotazu" in body:
            break
        title = re.search(r'class="SectionTitle"[^>]*>[^<]*<b>(.*?)</b>', body, re.S)
        full = html.unescape(title.group(1)).strip() if title else ""
        folder = folder_name(carrier_short(full))
        if folder in wanted:
            carrier = found.setdefault(folder, {"full": full, "files": []})
            page = 0
            while "Žiadny záznam nevyhovuje" not in body:
                carrier["files"] += [html.unescape(f) for f in re.findall(r'href="Down\.aspx\?f=xls/([^"]+\.xlsx)"', body)]
                page += 1
                body = listing(io_, page)
            log(f"{folder}: {len(carrier['files'])} files ({io_})")
        io_ += 1
    return found


def fetch_files(folder, names):
    """Download the XLSX files not cached yet; drop cached ones the portal no longer lists."""
    path = DATA / "xlsx" / folder
    path.mkdir(parents=True, exist_ok=True)
    new = 0
    for name in names:
        target = path / name
        if target.exists():
            continue
        time.sleep(PAUSE_S)
        try:
            data = get(f"{PORTAL}Down.aspx?f=xls/{name}")
            zipfile.ZipFile(io.BytesIO(data))  # an error page is HTML, not a workbook
        except Exception as error:  # noqa: BLE001 - try again tomorrow
            log(f"{folder}/{name}: {error}")
            continue
        target.write_bytes(data)
        new += 1
    for old in path.glob("*.xlsx"):
        if old.name not in names:
            old.unlink()
    return new


# ------------------------------------------------------------------ XLSX: sheets as {(row, column): text}

NS = {"m": "http://schemas.openxmlformats.org/spreadsheetml/2006/main",
      "r": "http://schemas.openxmlformats.org/officeDocument/2006/relationships"}
REL = "{http://schemas.openxmlformats.org/package/2006/relationships}"


def cell_ref(ref):
    """'AB12' -> (12, 'AB')"""
    m = re.match(r"([A-Z]+)(\d+)", ref)
    return int(m.group(2)), m.group(1)


def read_xlsx(data):
    """{sheet name: {(row, column letters): str}}. Numbers that are times of day come back as 'H:MM'."""
    zf = zipfile.ZipFile(io.BytesIO(data))
    shared = []
    if "xl/sharedStrings.xml" in zf.namelist():
        for si in ET.fromstring(zf.read("xl/sharedStrings.xml")).findall("m:si", NS):
            shared.append("".join(t.text or "" for t in si.iter(f"{{{NS['m']}}}t")))
    rels = {r.get("Id"): r.get("Target") for r in ET.fromstring(zf.read("xl/_rels/workbook.xml.rels")).iter(f"{REL}Relationship")}
    sheets = {}
    for sheet in ET.fromstring(zf.read("xl/workbook.xml")).iter(f"{{{NS['m']}}}sheet"):
        target = rels[sheet.get(f"{{{NS['r']}}}id")].lstrip("/")
        target = target if target.startswith("xl/") else "xl/" + target
        cells = {}
        for c in ET.fromstring(zf.read(target)).iter(f"{{{NS['m']}}}c"):
            kind, v = c.get("t"), c.find("m:v", NS)
            if kind == "s" and v is not None:
                text = shared[int(v.text)]
            elif kind == "inlineStr":
                text = "".join(t.text or "" for t in c.iter(f"{{{NS['m']}}}t"))
            elif v is not None and v.text is not None:
                text = v.text
                if kind is None and re.fullmatch(r"0?\.\d+", text):  # a time stored as a fraction of a day
                    minutes = round(float(text) * 1440)
                    text = f"{minutes // 60}:{minutes % 60:02d}"
            else:
                continue
            if text.strip():
                cells[cell_ref(c.get("r"))] = text
        sheets[sheet.get("name")] = cells
    return sheets


# ------------------------------------------------------------------ days: holidays, validity, sign explanations

def easter(year):
    a, b, c = year % 19, year // 100, year % 100
    d, e = b // 4, b % 4
    g = (8 * b + 13) // 25
    h = (19 * a + b - d - g + 15) % 30
    i, k = c // 4, c % 4
    m = (32 + 2 * e + 2 * i - h - k) % 7
    n = (a + 11 * h + 22 * m) // 451
    month = (h + m - 7 * n + 114) // 31
    return date(year, month, (h + m - 7 * n + 114) % 31 + 1)


def holidays(year):
    """Slovak public holidays that are days off (since 2024 without 1 September)."""
    fixed = [(1, 1), (1, 6), (5, 1), (5, 8), (7, 5), (8, 29), (9, 15), (11, 1), (11, 17), (12, 24), (12, 25), (12, 26)]
    sunday = easter(year)
    return {date(year, m, d) for m, d in fixed} | {sunday - timedelta(days=2), sunday + timedelta(days=1)}


DAY = r"\d{1,2}\.\s?\d{1,2}\.\s?\d{2,4}"


def parse_day(text):
    """'29.10.26' or '29.10.2026' -> date"""
    d, m, y = (int(x) for x in re.findall(r"\d+", text)[:3])
    return date(2000 + y if y < 100 else y, m, d)


def explained_days(text):
    """All days named in an explanation: 'od 1.7.26 do 31.8.26' ranges and single dates."""
    days = set()
    for start, end in re.findall(rf"od\s+({DAY})\s+do\s+({DAY})", text):
        d, end = parse_day(start), parse_day(end)
        while d <= end:
            days.add(d)
            d += timedelta(days=1)
    for single in re.findall(DAY, re.sub(rf"od\s+{DAY}\s+do\s+{DAY}", "", text)):
        days.add(parse_day(single))
    return days


def explanation_kind(text):
    """'not' (nejde ...), 'also' (ide aj ...), 'only' (ide od ... / ide 24.12.26) or None (anything else)."""
    t = text.strip().lower()
    if not re.search(DAY, t):
        return None  # e.g. 'ide v dňoch školského vyučovania'; ponytail: needs the school calendar, runs as marked
    if t.startswith("nejde"):
        return "not"
    if t.startswith("ide aj"):
        return "also"
    if t.startswith("ide"):
        return "only"
    return None


# ------------------------------------------------------------------ stops: CIS name -> name and position (kaeru.csv)

def load_kaeru():
    path = DATA / "kaeru.csv"
    try:
        data = get(KAERU_URL, timeout=120)
        if b"cis_name" in data[:200]:
            path.write_bytes(data)
    except Exception as error:  # noqa: BLE001 - use the copy from last time
        log(f"kaeru.csv not updated: {error}")
    by_name, districts = {}, {}
    for row in csv.DictReader(io.StringIO(path.read_text(encoding="utf-8-sig"))):
        try:
            row["lat"], row["lon"] = float(row["lat"]), float(row["lon"])
        except ValueError:
            row["lat"] = row["lon"] = None
        by_name.setdefault(row["cis_name"], []).append(row)
        if row["lat"] is not None:
            districts.setdefault(row["district"], []).append((row["lat"], row["lon"]))
    centres = {d: (sum(p[0] for p in pts) / len(pts), sum(p[1] for p in pts) / len(pts)) for d, pts in districts.items()}
    return by_name, centres


def stop_key(cis_name, row=None):
    base = cis_name + (f"-{row['country_code']}-{row['district']}" if row else "")
    return "S-" + hashlib.md5(base.encode()).hexdigest()[:8]


def plain_name(cis_name):
    """'Banská Bystrica,,Nám.Slobody' -> 'Banská Bystrica, Nám.Slobody' when kaeru doesn't know the stop."""
    return re.sub(r"\s*,+\s*", ", ", cis_name).strip(", ")


def resolve_stops(names, kaeru, stops):
    """Resolve one line's stops in order. Same-named stops in several places are told apart by where the
    line's other stops are (transiq's resolver, per line instead of per carrier). Adds to stops;
    returns {cis name: stop id}."""
    by_name, centres = kaeru
    found, ids = [], {}
    for name in dict.fromkeys(names):
        matches = by_name.get(name, [])
        row = matches[0] if len(matches) == 1 else None
        if len(matches) > 1:
            counts = {}
            for r in found:
                counts[r["district"]] = counts.get(r["district"], 0) + 1
            row = next((m for d in sorted(counts, key=counts.get, reverse=True) for m in matches if m["district"] == d), None)
            located = [r for r in found if r["lat"] is not None]
            if row is None and located:
                lat = sum(r["lat"] for r in located) / len(located)
                lon = sum(r["lon"] for r in located) / len(located)
                near = sorted(centres, key=lambda d: (centres[d][0] - lat) ** 2 + (centres[d][1] - lon) ** 2)[:3]
                row = next((m for d in near for m in matches if m["district"] == d), None)
        if row:
            found.append(row)
        sid = stop_key(name, row)
        ids[name] = sid
        stops.setdefault(sid, (row["name"] if row and row["name"] else plain_name(name),
                               row["lat"] if row else None, row["lon"] if row else None))
    return ids


# ------------------------------------------------------------------ one XLSX -> route, trips, stop times

TIME = re.compile(r"(\d{1,2}):(\d{2})")
WEEKDAY_WORDS = ["pondelok", "utorok", "stredu", "štvrtok", "piatok", "sobotu", "nedeľu"]


def validity(sheets):
    for cells in sheets.values():
        m = re.search(rf"Platí od ({DAY}) do ({DAY})", cells.get((2, "A"), ""))
        if m:
            return parse_day(m.group(1)), parse_day(m.group(2))
    return None


def convert_line(sheets, kaeru, stops, start, end, line_id, version):
    """Trips of one timetable file, run from start to end (inclusive).
    Returns (route row, [trip rows], [(trip id, weekdays, added days, removed days)], [stop time rows])."""
    signs_sheet = sheets.get("Vysvetlenie značiek", {})
    explanations = {}
    for (row, col), sign in signs_sheet.items():
        if col == "A" and row >= 4 and (row, "B") in signs_sheet:
            explanations.setdefault(sign.strip(), []).append(signs_sheet[(row, "B")])
    header = next((s for s in sheets.values() if (1, "A") in s), {})
    short = line_id if not re.fullmatch(r"L\d+", line_id) else int(line_id[4:]) if line_id.startswith("L010") else int(line_id[1:])
    long_name = re.sub(r"\s*\(.*?\)\s*", "", re.sub(r"^/\d+\s*", "", header.get((1, "C"), "").strip()))
    long_name = long_name.replace(" - ", "-").replace("-", " - ")
    route = {"route_id": line_id, "route_short_name": short, "route_long_name": long_name, "route_type": 3}
    days_off = set().union(*(holidays(y) for y in range(start.year, end.year + 1)))
    trips, services, stop_times = [], [], []
    for direction, sheet_name in ((0, "Smer tam"), (1, "Smer späť")):
        cells = sheets.get(sheet_name)
        if not cells:
            continue
        rows = sorted(r for r, c in cells if c == "A" and r >= 5 and cells[(r, "A")].strip().isdigit() and (r, "B") in cells)
        names = {r: cells[(r, "B")].split(";")[0].strip() for r in rows}
        ids = resolve_stops([names[r] for r in rows], kaeru, stops)
        for (r, col), head in cells.items():
            spoj = re.match(r"\s*Spoj\s+(\d+)", head) if r == 3 else None
            if not spoj:
                continue
            signs = cells.get((4, col), "").split()
            weekdays = [0] * 7
            if "X" in signs:
                weekdays[:5] = [1] * 5
            for d in "1234567":
                if d in signs:
                    weekdays[int(d) - 1] = 1
            added, removed = set(), set()
            if "+" in signs:
                weekdays[6] = 1
                added |= days_off
            else:
                removed |= days_off
            only = False
            for sign in signs:
                for text in explanations.get(sign, []):
                    day = re.match(r"\s*ide vo? (pondelok|utorok|stredu|štvrtok|piatok|sobotu|nedeľu)\s*$", text.lower())
                    if day:  # 'ide vo štvrtok': a weekday spelled out instead of its number
                        weekdays[WEEKDAY_WORDS.index(day.group(1))] = 1
                    kind = explanation_kind(text)
                    days = explained_days(text) if kind else set()
                    if kind == "not":
                        removed |= days
                        added -= days
                    elif kind == "also":
                        added |= days
                        removed -= days
                    elif kind == "only":
                        only = True
                        added |= days
            if only:
                weekdays = [0] * 7
            elif not any(weekdays):
                weekdays = [1] * 7  # no day marks: runs daily
            trip_id = f"{line_id}-{version}-{direction}-{spoj.group(1)}"
            times, last, offset = [], -1, 0
            for r in rows:  # every stop row: a trip may start or end anywhere on the line (transiq stopped at the first gap)
                value = cells.get((r, col), "").strip()
                t = TIME.search(value)
                if not t or value.startswith(("~", "|")):
                    continue
                minutes = int(t.group(1)) * 60 + int(t.group(2)) + offset
                if minutes < last - 60:  # past midnight
                    offset += 1440
                    minutes += 1440
                last = minutes
                hhmm = f"{minutes // 60:02d}:{minutes % 60:02d}:00"
                sid = ids[names[r]]
                if times and times[-1]["stop_id"] == sid and "odjazd" in cells.get((r, "C"), "").lower():
                    times[-1]["departure_time"] = hhmm  # the departure row after the arrival row at one stop
                    continue
                stop_signs = cells[(r, "B")].split(";")[1].split() if ";" in cells[(r, "B")] else []
                closed = "CLO" in stop_signs
                times.append({"trip_id": trip_id, "stop_id": sid, "arrival_time": hhmm, "departure_time": hhmm,
                              "pickup_type": 1 if closed or "(" in value else 0,
                              "drop_off_type": 1 if closed or ")" in value else 0})
            if len(times) < 2:
                continue
            for i, st in enumerate(times, 1):
                st["stop_sequence"] = i
            trips.append({"route_id": line_id, "trip_id": trip_id, "trip_headsign": stops[times[-1]["stop_id"]][0],
                          "direction_id": direction, "wheelchair_accessible": 1 if "BB" in signs else 2})
            services.append((trip_id, tuple(weekdays), frozenset(d for d in added if start <= d <= end),
                             frozenset(d for d in removed if start <= d <= end)))
            stop_times += times
    return route, trips, services, stop_times


# ------------------------------------------------------------------ one carrier -> GTFS zip

def line_versions(names):
    """{line: [(valid from (from the file name), file name)]}, oldest first."""
    lines = {}
    for name in dict.fromkeys(names):
        m = re.match(r"(L\d+)_(\d{6})_", name)
        key, when = (m.group(1), date(2000 + int(m.group(2)[:2]), int(m.group(2)[2:4]), int(m.group(2)[4:]))) if m else (name, date.min)
        lines.setdefault(key, []).append((when, name))
    return {k: sorted(v) for k, v in lines.items()}


def csv_text(columns, rows):
    out = io.StringIO()
    writer = csv.DictWriter(out, columns, lineterminator="\n", extrasaction="ignore")
    writer.writeheader()
    writer.writerows(rows)
    return out.getvalue()


def build_feed(folder, carrier, kaeru, today=None):
    """GTFS zip bytes for one carrier from its cached XLSX files."""
    today = today or date.today()
    path = DATA / "xlsx" / folder
    stops, routes, trips, stop_times, services = {}, {}, [], [], {}
    trip_service, problems = {}, 0
    for line, versions in line_versions(carrier["files"]).items():
        parsed = []
        for _, name in versions:
            try:
                sheets = read_xlsx((path / name).read_bytes())
                valid = validity(sheets)
                if valid:
                    parsed.append((valid, name, sheets))
            except Exception as error:  # noqa: BLE001 - one bad file mustn't lose the carrier
                problems += 1
                log(f"{folder}/{name}: {error}")
        parsed.sort(key=lambda p: p[0][0])
        for i, ((start, end), name, sheets) in enumerate(parsed):
            if i + 1 < len(parsed):  # the next published version takes over the day it starts
                end = min(end, parsed[i + 1][0][0] - timedelta(days=1))
            if end < today - timedelta(days=2) or end < start:
                continue
            version = name.split("_")[1] if "_" in name else "0"
            route, line_trips, line_services, line_times = convert_line(sheets, kaeru, stops, start, end, line, version)
            routes.setdefault(route["route_id"], route)
            trips += line_trips
            stop_times += line_times
            for trip_id, weekdays, added, removed in line_services:
                key = (weekdays, start, end, added, removed)
                trip_service[trip_id] = services.setdefault(key, f"SE-{len(services) + 1}")
    agency_name = carrier_short(carrier["full"])
    agency_id = "A-" + hashlib.md5(re.sub(r"[^a-z0-9]+", "-", agency_name.lower()).strip("-").encode()).hexdigest()[:8]
    url = re.search(r"www\.[^\s,]+", carrier["full"])
    for t in trips:
        t["service_id"] = trip_service[t["trip_id"]]
    for r in routes.values():
        r["agency_id"] = agency_id
    calendar, calendar_dates = [], []
    names = ("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")
    for (weekdays, start, end, added, removed), sid in services.items():
        calendar.append({"service_id": sid, **dict(zip(names, weekdays)),
                         "start_date": start.strftime("%Y%m%d"), "end_date": end.strftime("%Y%m%d")})
        calendar_dates += [{"service_id": sid, "date": d.strftime("%Y%m%d"), "exception_type": 1} for d in sorted(added)]
        calendar_dates += [{"service_id": sid, "date": d.strftime("%Y%m%d"), "exception_type": 2} for d in sorted(removed - added)]
    files = {
        "agency.txt": csv_text(["agency_id", "agency_name", "agency_url", "agency_timezone", "agency_lang"], [{
            "agency_id": agency_id, "agency_name": agency_name, "agency_url": f"https://{url.group(0)}" if url else "https://portal.cp.sk",
            "agency_timezone": "Europe/Bratislava", "agency_lang": "sk"}]),
        "stops.txt": csv_text(["stop_id", "stop_name", "stop_lat", "stop_lon"], [
            {"stop_id": sid, "stop_name": n, "stop_lat": "" if lat is None else lat, "stop_lon": "" if lon is None else lon}
            for sid, (n, lat, lon) in sorted(stops.items())]),
        "routes.txt": csv_text(["route_id", "agency_id", "route_short_name", "route_long_name", "route_type"], routes.values()),
        "trips.txt": csv_text(["route_id", "service_id", "trip_id", "trip_headsign", "direction_id", "wheelchair_accessible"], trips),
        "calendar.txt": csv_text(["service_id", *names, "start_date", "end_date"], calendar),
        "calendar_dates.txt": csv_text(["service_id", "date", "exception_type"], calendar_dates),
        "stop_times.txt": csv_text(["trip_id", "arrival_time", "departure_time", "stop_id", "stop_sequence", "pickup_type",
                                    "drop_off_type"], stop_times),
    }
    out = io.BytesIO()
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as zf:
        for name, text in files.items():  # fixed dates: the same timetable gives the same bytes
            zf.writestr(zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0)), text, zipfile.ZIP_DEFLATED)
    return out.getvalue(), {"routes": len(routes), "trips": len(trips), "stops": len(stops), "problems": problems}


def run(feeds):
    wanted = {FEEDS[f]: f for f in feeds}
    carriers = scrape(set(wanted))
    kaeru = load_kaeru()
    out = DATA / "gtfs"
    out.mkdir(parents=True, exist_ok=True)
    for folder, feed in wanted.items():
        carrier = carriers.get(folder)
        if not carrier or not carrier["files"]:
            log(f"{feed}: not on the portal, keeping the previous feed")
            continue
        try:
            new = fetch_files(folder, set(carrier["files"]))
            data, info = build_feed(folder, carrier, kaeru)
        except Exception as error:  # noqa: BLE001 - keep the previous zip
            log(f"{feed}: failed, keeping the previous feed: {error}")
            continue
        target = out / f"{feed}.zip"
        if target.exists() and target.read_bytes() == data:
            log(f"{feed}: unchanged ({info['trips']} trips)")
            continue
        tmp = target.with_suffix(".tmp")
        tmp.write_bytes(data)
        tmp.replace(target)
        log(f"{feed}: written, {new} new files, {info}")


if __name__ == "__main__":
    run(sys.argv[1:] or list(FEEDS))

