// Public transit data for the web client, ported from the Android app:
//  - sadzv.qrbus.me live data (stops, departure boards, trips, vehicles)
//  - cp.sk planner (stop suggestions, connections, paging, route pages)
// All times the browser gets are epoch ms; Slovak wall-clock conversion happens here.
import { parse } from 'node-html-parser';
import { readableStop, tripProgress, snapToRoute, delayAt, BUS_GPS_MAX_M, GPS_FRESH_MS, localToEpoch, todayLocal, addDays, isoDate } from './public/js/logic.js';

export { localToEpoch, todayLocal };

const LIVE = 'https://sadzv.qrbus.me/index';
const TZ = 'Europe/Bratislava';
const UA = 'Mozilla/5.0';

// ---------------------------------------------------------------- fetch helpers

async function fetchText(url, init = {}) {
  const res = await fetch(url, { ...init, headers: { 'User-Agent': UA, ...(init.headers || {}) }, signal: AbortSignal.timeout(20000) });
  if (!res.ok) throw Object.assign(new Error(`Upstream HTTP ${res.status}`), { status: 502 });
  return res.text();
}

const liveGet = (path) => fetchText(`${LIVE}/${path}`, { headers: { 'X-Requested-With': 'XMLHttpRequest' } }).then(JSON.parse);
const livePost = (path, body) =>
  fetchText(`${LIVE}/${path}`, {
    method: 'POST',
    headers: { 'X-Requested-With': 'XMLHttpRequest', 'Content-Type': 'application/json; charset=utf-8' },
    body: JSON.stringify(body),
  }).then(JSON.parse);

const num = (v) => (v == null || v === '' || Number.isNaN(Number(v)) ? null : Number(v));
const str = (v) => (v == null ? '' : String(v).trim());

// ---------------------------------------------------------------- live: stops

let stopsCache = { at: 0, stops: null };

export function normalize(value) {
  return String(value).normalize('NFD').replace(/\p{M}+/gu, '').toLowerCase().replace(/[^a-z0-9]+/g, ' ').trim();
}

export async function getStops() {
  if (stopsCache.stops && Date.now() - stopsCache.at < 24 * 3600 * 1000) return stopsCache.stops;
  try {
    const root = await liveGet('getAllPlatforms');
    const array = Array.isArray(root) ? root : root.platforms || [];
    const platforms = array.map((el) =>
      Array.isArray(el)
        ? { id: num(el[0]), name: str(el[1]), lat: num(el[2]) / 1e5, lon: num(el[3]) / 1e5, number: str(el[4]), stopId: num(el[5]) }
        : { id: num(el.id), name: str(el.name), lat: num(el.lat) / 1e5, lon: num(el.long) / 1e5, number: str(el.platf), stopId: num(el.stopId) }
    ).filter((p) => p.id != null && p.name && p.lat);
    const groups = new Map();
    for (const p of platforms) {
      if (!groups.has(p.stopId)) groups.set(p.stopId, []);
      groups.get(p.stopId).push(p);
    }
    const stops = [...groups.entries()].map(([id, group]) => {
      const counts = {};
      for (const g of group) counts[g.name] = (counts[g.name] || 0) + 1;
      const name = Object.entries(counts).sort((a, b) => b[1] - a[1])[0][0];
      return {
        id, name,
        platforms: group.sort((a, b) => a.number.localeCompare(b.number)).map(({ id, number, lat, lon }) => ({ id, number, lat, lon })),
        lat: group.reduce((a, g) => a + g.lat, 0) / group.length,
        lon: group.reduce((a, g) => a + g.lon, 0) / group.length,
        key: normalize(name),
      };
    }).sort((a, b) => a.name.localeCompare(b.name));
    stopsCache = { at: Date.now(), stops };
    return stops;
  } catch (err) {
    if (stopsCache.stops) return stopsCache.stops; // stale beats nothing
    throw err;
  }
}

/** Best match for a stop name from cp.sk ("Banska Bystrica,,Namestie slobody"). */
export async function matchStopByName(name) {
  const key = normalize(name);
  if (!key) return null;
  const all = await getStops();
  const exact = all.find((s) => s.key === key);
  if (exact) return exact;
  const longest = (list) => list.sort((a, b) => b.key.length - a.key.length)[0] || null;
  return longest(all.filter((s) => s.key.length >= 3 && key.endsWith(s.key))) ||
    longest(all.filter((s) => s.key.length >= 5 && key.includes(s.key)));
}

// ---------------------------------------------------------------- live: boards & trips

function computeDelay(nowMs, secondsUntil, plannedSecondOfDay) {
  const expectedMs = nowMs + secondsUntil * 1000;
  const today = todayLocal(nowMs);
  const candidates = [-1, 0, 1].map((n) => {
    const d = addDays(today, n);
    return localToEpoch(d.year, d.month, d.day, 0, 0) + plannedSecondOfDay * 1000;
  });
  const planned = candidates.sort((a, b) => Math.abs(a - expectedMs) - Math.abs(b - expectedMs))[0];
  return Math.trunc((expectedMs - planned) / 1000);
}

export async function getDepartures(platformIds) {
  const root = await livePost('getDeparturesOnPlatformWeb', { platformIds, organizationSystemEntityId: 0 });
  const now = Date.now();
  return (root.departures || []).filter((o) => num(o.plan) != null).map((o) => {
    const real = num(o.real) || 0;
    const realtime = num(o.realTimePaired) === 1;
    return {
      line: str(o.lineNumberText).replace(/_+$/, ''),
      lineId: num(o.lineId) || 0,
      destination: str(o.destinationName),
      platformNumber: str(o.platformNumber),
      routeNumber: str(o.routeNumber),
      tripNumber: num(o.tripNumber) || 0,
      plannedSecondOfDay: num(o.plan),
      departsAtMs: now + real * 1000,
      isRealtime: realtime,
      isCancelled: o.cancelled === true,
      delaySeconds: realtime ? computeDelay(now, real, num(o.plan)) : null,
    };
  }).sort((a, b) => a.departsAtMs - b.departsAtMs);
}

/** "2026-09-24T20:31:40Z" from sadzv is Slovak local time despite the Z. */
function localTimestamp(textValue) {
  const m = /(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})/.exec(textValue || '');
  return m ? localToEpoch(+m[1], +m[2], +m[3], +m[4], +m[5]) + +m[6] * 1000 : 0;
}

async function getVehicles() {
  const root = await liveGet('getAllRtdVehicles');
  return (root.vehicles || []).filter((a) => Array.isArray(a) && a.length >= 8 && num(a[3]))
    .map((a) => ({
      lineId: num(a[3]), tripNumber: num(a[5]) || 0, delaySeconds: num(a[7]),
      lat: (num(a[0]) || 0) / 1e5, lon: (num(a[1]) || 0) / 1e5, reportedAtMs: localTimestamp(a[6]),
    }));
}

const coordinateCache = new Map(); // "city|stop name" -> [lat, lon] | null

/**
 * Where a stop named as on cp.sk is. City stop names ("Nám. slobody") exist in many towns,
 * so search the trip's own timetable first, all of Slovakia only if that finds nothing,
 * and take the match closest to [near] (the previous stop).
 */
async function coordinatesOf(name, city, near) {
  const found = async (c) => (await suggestStops(c, name).catch(() => []))
    .filter((x) => x.coorX && x.coorY).map((x) => [Number(x.coorX), Number(x.coorY)]);
  let all = await found(city);
  if (!all.length && city !== 'slovensko') all = await found('slovensko');
  if (!near) return all[0] || null;
  return all.sort((a, b) => distance(near[0], near[1], a[0], a[1]) - distance(near[0], near[1], b[0], b[1]))[0] || null;
}

/** Stop coordinates: sadzv platforms for sadzv trips, cp.sk suggestions for timetable stops. */
export async function withCoordinates(trip, fromSadzv, ref = {}) {
  if (trip.stops.every((s) => s.lat != null)) return trip;
  const platforms = fromSadzv ? new Map((await getStops()).flatMap((s) => s.platforms.map((p) => [p.id, p]))) : new Map();
  // Town of the timetable: from the cp.sk link, else where the user boards (sadzv trips on cp.sk's times).
  const boardingStop = ref.fromStopId ? (await getStops()).find((s) => s.id === ref.fromStopId) : null;
  const slug = (/cp\.sk\/([^/]+)\//.exec(ref.scheduleUrl || '') || [])[1];
  const city = CITY_PREFIX[slug] && slug !== 'slovensko' ? slug : boardingStop ? cityFor(boardingStop.lat, boardingStop.lon) : 'slovensko';
  let near = boardingStop ? [boardingStop.lat, boardingStop.lon] : null;
  const stops = [];
  for (const stop of trip.stops) {
    const key = city + '|' + stop.name;
    let c = platforms.get(stop.platformId);
    c = c ? [c.lat, c.lon] : coordinateCache.get(key);
    if (c === undefined) {
      c = await coordinatesOf(stop.name, city, near);
      if (coordinateCache.size > 5000) coordinateCache.clear();
      coordinateCache.set(key, c);
    }
    if (c) near = c;
    stops.push({ ...stop, lat: c?.[0] ?? null, lon: c?.[1] ?? null });
  }
  return { ...trip, stops };
}

/** The bus's own GPS, when fresh and on the route, becomes the delay. */
async function placeBus(trip, busPosition, fromSadzv, ref) {
  if (!busPosition || trip.stops.length < 2) return trip;
  const placed = await withCoordinates(trip, fromSadzv, ref);
  const expected = tripProgress(placed, { boardingPlatformIds: [], alightOrder: null }).position;
  const hit = snapToRoute(busPosition[0], busPosition[1], placed.stops.map((s) => (s.lat != null ? [s.lat, s.lon] : null)), BUS_GPS_MAX_M, expected);
  if (!hit) return placed; // the feed sometimes pairs a position with the wrong trip
  return { ...placed, delaySeconds: delayAt(hit.index, placed.stops.map((s) => s.scheduledMs)), positionSource: 'busGps' };
}

/** Trip times are local wall-clock values encoded as if they were UTC. */
function wallClockToEpochMs(wallSeconds) {
  if (!wallSeconds || wallSeconds <= 0) return 0;
  const d = new Date(wallSeconds * 1000);
  return localToEpoch(d.getUTCFullYear(), d.getUTCMonth() + 1, d.getUTCDate(), d.getUTCHours(), d.getUTCMinutes()) + d.getUTCSeconds() * 1000;
}

/** cp.sk city timetable for a stop: Zvolen or Banská Bystrica city buses, else all of Slovakia. */
export function cityFor(lat, lon) {
  const near = [['zvolen', 48.577, 19.125], ['banskabystrica', 48.736, 19.146]]
    .map(([slug, a, b]) => [slug, distance(lat, lon, a, b)]).filter(([, d]) => d < 12000).sort((x, y) => x[1] - y[1]);
  return near[0]?.[0] || 'slovensko';
}
function distance(lat1, lon1, lat2, lon2) {
  const rad = Math.PI / 180, x = (lon2 - lon1) * rad * Math.cos(((lat1 + lat2) / 2) * rad), y = (lat2 - lat1) * rad;
  return Math.sqrt(x * x + y * y) * 6371000;
}

const minutesGap = (a, b) => Math.min(Math.abs(a - b), 1440 - Math.abs(a - b));
const hhmm = (m) => `${Math.floor((((m % 1440) + 1440) % 1440) / 60)}:${String(((m % 60) + 60) % 60).padStart(2, '0')}`;
const lineNo = (line) => String(line || '').trim().split(/\s+/).pop();

/** cp.sk stop nearest to a sadzv stop (names differ, e.g. "Zl.Potok A.Hlinku", and repeat across towns). */
async function nearestCpStop(city, stop) {
  const words = stop.name.split(/[\s,.]+/).filter((w) => w.length >= 4);
  const queries = [...new Set([stop.name, words.slice(-2).join(' '), words.at(-1)].filter(Boolean))];
  const found = [];
  const meters = (s) => (s.coorX && s.coorY ? distance(stop.lat, stop.lon, Number(s.coorX), Number(s.coorY)) : Infinity);
  for (const q of queries) {
    found.push(...(await suggestStops(city, q).catch(() => [])));
    if (found.some((s) => meters(s) < 300)) break;
  }
  const best = found.map((s) => [s, meters(s)]).sort((a, b) => a[1] - b[1])[0];
  return best && best[1] < 600 ? best[0] : null;
}

async function departureBoard(city, stop, time) {
  const url = `https://cp.sk${prefixFor(city)}/odchody/`;
  const form = new URLSearchParams({ From: stop.text, FromHidden: hidden(stop), PositionFromHidden: position(stop), Date: '', Time: time, IsArr: 'False' });
  const html = await fetchText(url, { method: 'POST', body: form, headers: { Referer: url, 'Content-Type': 'application/x-www-form-urlencoded' } });
  return parse(html).querySelectorAll('tr.dep-row-first').map((row) => {
    // data-datetime="28.09.2026 18:05:00", Slovak local time; the carrier is on the second row.
    const m = /(\d{1,2})\.(\d{1,2})\.(\d{4}) (\d{1,2}):(\d{2})/.exec(row.getAttribute('data-datetime') || '');
    const second = row.nextElementSibling?.classList.contains('dep-row-second') ? row.nextElementSibling : null;
    return {
      line: lineNo(text(row.querySelector('.code h3'))),
      minutes: clockMinutes(row.getAttribute('data-datetime')),
      destination: parse(row.getAttribute('data-stationname') || '').text.trim(),
      platform: text(row.querySelectorAll('span[title]').find((x) => /^nást/i.test(x.getAttribute('title') || ''))),
      operator: text(second?.querySelector('span[title="dopravce"]')),
      atMs: m ? localToEpoch(+m[3], +m[2], +m[1], +m[4], +m[5]) : 0,
    };
  }).filter((r) => r.minutes != null);
}

// ---------------------------------------------------------------- other carriers on a stop's board

const cpStops = new Map(); // sadzv stop id -> cp.sk intercity stop | null
const cpBoards = new Map(); // sadzv stop id -> { at, rows }

/** sadzv and cp.sk write lines differently ("606106" vs "106"): same minute and matching line. */
function sameBus(d, row) {
  const gap = minutesGap(Math.floor(d.plannedSecondOfDay / 60), row.minutes);
  return gap <= 1 && (d.line === row.line || d.line.endsWith(row.line) || row.line.endsWith(d.line));
}

/**
 * Intercity buses of every carrier (Slovak Lines, Arriva, FlixBus, ...) at a sadzv stop, from
 * cp.sk's timetable, minus the ones sadzv already shows live. Same as the Android app.
 */
export async function otherCarriers(stop, live) {
  if (!cpStops.has(stop.id)) {
    if (cpStops.size > 2000) cpStops.clear();
    cpStops.set(stop.id, await nearestCpStop('slovensko', stop).catch(() => null));
  }
  const cpStop = cpStops.get(stop.id);
  if (!cpStop) return [];
  const now = Date.now();
  let cached = cpBoards.get(stop.id);
  // The timetable doesn't change: every 2 min is plenty (boards refresh every 15 s).
  if (!cached || now - cached.at > 2 * 60000) {
    cached = { at: now, rows: await departureBoard('slovensko', cpStop, '') };
    if (cpBoards.size > 500) cpBoards.clear();
    cpBoards.set(stop.id, cached);
  }
  return cached.rows.filter((r) => r.atMs > now - 60000 && !live.some((d) => sameBus(d, r))).map((r) => ({
    line: r.line, lineId: 0, destination: readableStop(r.destination), platformNumber: r.platform, routeNumber: '', tripNumber: 0,
    plannedSecondOfDay: r.minutes * 60, departsAtMs: r.atMs, isRealtime: false, isCancelled: false, delaySeconds: null,
    fromTimetable: true, operator: r.operator,
  }));
}

/** The whole board for a stop: sadzv's live departures plus other carriers. Fails only when both fail. */
export async function getBoard(platformIds, stop) {
  const [live, other] = await Promise.allSettled([getDepartures(platformIds), stop ? otherCarriers(stop, []) : Promise.resolve([])]);
  if (live.status === 'rejected' && other.status === 'rejected') throw live.reason;
  const liveRows = live.status === 'fulfilled' ? live.value : [];
  const otherRows = other.status === 'fulfilled' ? other.value.filter((r) => !liveRows.some((d) => sameBus(d, { ...r, minutes: r.plannedSecondOfDay / 60 }))) : [];
  return [...liveRows, ...otherRows].sort((a, b) => a.departsAtMs - b.departsAtMs);
}

/**
 * The cp.sk trip for a bus seen on sadzv: cp.sk's departure board for the same stop,
 * the same line within 2 minutes, then that bus's route via a direct connection to
 * cp.sk's own name for its final stop.
 */
export async function findTimetableTrip(stop, line, plannedMinutes, intercityOnly = false) {
  for (const city of [...new Set([intercityOnly ? 'slovensko' : cityFor(stop.lat, stop.lon), 'slovensko'])]) {
    const cpStop = await nearestCpStop(city, stop);
    if (!cpStop) continue;
    const rows = (await departureBoard(city, cpStop, hhmm(plannedMinutes - 3)).catch(() => []))
      .filter((r) => r.line === line && minutesGap(r.minutes, plannedMinutes) <= 2)
      .sort((a, b) => minutesGap(a.minutes, plannedMinutes) - minutesGap(b.minutes, plannedMinutes));
    for (const row of rows) {
      const r = await searchConnections({ city, from: cpStop.text, fromSug: cpStop, to: row.destination, time: hhmm(row.minutes), directOnly: true })
        .catch(() => ({ connections: [] }));
      const seg = r.connections.map((c) => c.segments[0])
        .find((s) => lineNo(s.line) === line && clockMinutes(s.departureTime) === row.minutes && s.routeUrl);
      if (seg) return seg;
    }
  }
  return null;
}

const fallbackCache = new Map(); // key -> { trip, at }; misses retried after 10 min

async function timetableFallback(ref) {
  if (!ref.fromStopId || !(ref.plannedSecondOfDay >= 0)) return null;
  const key = `${ref.lineId}-${ref.line}-${ref.tripNumber}-${ref.plannedSecondOfDay}-${isoDate(todayLocal())}`;
  const cached = fallbackCache.get(key);
  if (cached && (cached.trip || Date.now() - cached.at < 10 * 60000)) return cached.trip;
  const stop = (await getStops()).find((s) => s.id === ref.fromStopId);
  let trip = null;
  if (stop) {
    // Other carriers' buses (lineId 0) are on the intercity timetable, not a city one.
    const seg = await findTimetableTrip(stop, ref.line, Math.floor(ref.plannedSecondOfDay / 60) % 1440, !ref.lineId).catch(() => null);
    if (seg) trip = await getScheduledTrip({ line: ref.line, destination: ref.destination, scheduleUrl: seg.routeUrl, serviceDate: seg.serviceDate }).catch(() => null);
  }
  if (fallbackCache.size > 2000) fallbackCache.clear();
  fallbackCache.set(key, { trip: trip?.stops.length ? trip : null, at: Date.now() });
  return trip?.stops.length ? trip : null;
}

export async function getLiveTrip(ref) {
  // Another carrier's bus from cp.sk's board: sadzv knows nothing about it.
  if (!ref.lineId) {
    const timetable = await timetableFallback(ref);
    return timetable
      ? { ...timetable, line: ref.line, destination: ref.destination || timetable.destination, alightOrder: null, fromTimetable: true, positionSource: 'timetable' }
      : { line: ref.line, destination: ref.destination, stops: [], delaySeconds: null, positionSource: 'timetable' };
  }
  const root = await livePost('getPlatformsDataWithHistory', {
    lineId: ref.lineId, lineNumber: ref.line, tripNumber: ref.tripNumber, routeNumber: ref.routeNumber, organizationSystemEntityId: 0,
  });
  const stops = (root.platforms || []).map((o) => {
    const actual = [num(o.realDeparture), num(o.realArrival)].find((v) => v > 0);
    return {
      name: str(o.stopName),
      platformId: num(o.platformId) || 0,
      order: num(o.stopOrder) || 0,
      scheduledMs: wallClockToEpochMs(num(o.departureTime) || 0),
      actualMs: actual ? actual * 1000 : null,
    };
  }).sort((a, b) => a.order - b.order);
  const vehicle = await getVehicles().then((v) => v.find((x) => x.lineId === ref.lineId && x.tripNumber === ref.tripNumber)).catch(() => null);
  // sadzv keeps some positions for months: only a recent one says where the bus is.
  const age = vehicle ? Date.now() - vehicle.reportedAtMs : Infinity;
  const busPosition = vehicle?.lat && age > -60000 && age < GPS_FRESH_MS ? [vehicle.lat, vehicle.lon] : null;
  const passed = [...stops].reverse().find((s) => s.actualMs != null);
  const passedDelay = passed ? Math.trunc((passed.actualMs - passed.scheduledMs) / 1000) : null;
  if (!stops.length) {
    // sadzv often has no stop list (its own site too): quietly use cp.sk's timetable,
    // keeping sadzv's live delay when the bus reports one.
    const timetable = await timetableFallback(ref);
    if (timetable) {
      return placeBus({ ...timetable, line: ref.line, destination: ref.destination || timetable.destination,
        delaySeconds: vehicle?.delaySeconds ?? null, alightOrder: null, fromTimetable: true,
        positionSource: vehicle?.delaySeconds != null ? 'busDelay' : 'timetable' }, busPosition, false, ref);
    }
  }
  const delaySeconds = vehicle?.delaySeconds ?? passedDelay;
  return placeBus({
    line: ref.line,
    destination: ref.destination || stops.at(-1)?.name || '',
    stops,
    delaySeconds,
    positionSource: delaySeconds != null ? 'busDelay' : 'timetable',
  }, busPosition, true, ref);
}

// ---------------------------------------------------------------- cp.sk

const clockMinutes = (text) => {
  const m = /(\d{1,2}):(\d{2})/.exec(text || '');
  return m ? Number(m[1]) * 60 + Number(m[2]) : null;
};
const ownText = (el) => (el ? el.childNodes.filter((n) => n.nodeType === 3).map((n) => n.text).join('').trim() : '');
const text = (el) => (el ? el.text.replace(/\s+/g, ' ').trim() : '');

/** cp.sk route page ("Dráha spoja") -> trip with epoch times; same rules as the Android parser. */
export function parseScheduledTrip(html, ref) {
  const rows = parse(html).querySelectorAll('ul.line-itinerary li.item').map((li) => {
    const name = readableStop(text(li.querySelector('strong.name')));
    const departure = clockMinutes(ownText(li.querySelector('span.departure')));
    const arrival = clockMinutes(ownText(li.querySelector('span.arrival')));
    if (!name || (departure == null && arrival == null)) return null;
    // Platform (nástupište) or track (koľaj) at this stop.
    const platform = text(li.querySelectorAll('span[title]').find((s) => /^(nást|koľ|kol)/i.test(s.getAttribute('title') || '')));
    return { name, departure, arrival, platform, active: !li.classList.contains('inactive'), minutes: departure ?? arrival };
  }).filter(Boolean);
  if (!rows.length) return { line: ref.line, destination: ref.destination, stops: [], delaySeconds: null };
  let boarding = rows.findIndex((r) => r.active);
  if (boarding < 0) boarding = 0;
  let alight = rows.map((r) => r.active).lastIndexOf(true);
  if (alight <= boarding) alight = null;
  const date = /^\d{4}-\d{2}-\d{2}$/.test(ref.serviceDate || '')
    ? (([y, m, d]) => ({ year: y, month: m, day: d }))(ref.serviceDate.split('-').map(Number))
    : todayLocal();
  const days = new Array(rows.length).fill(0);
  for (let i = boarding + 1; i < rows.length; i++) days[i] = days[i - 1] + (rows[i].minutes < rows[i - 1].minutes ? 1 : 0);
  for (let i = boarding - 1; i >= 0; i--) days[i] = days[i + 1] - (rows[i].minutes > rows[i + 1].minutes ? 1 : 0);
  const stops = rows.map((row, i) => {
    // Where the user gets off, the arrival matters (long dwell times at bus stations).
    const useArrival = i === alight && row.arrival != null;
    const minutes = useArrival ? row.arrival : row.minutes;
    const day = days[i] - (useArrival && row.arrival > row.minutes ? 1 : 0);
    const d = addDays(date, day);
    return { name: row.name, platformId: i + 1, order: i + 1, scheduledMs: localToEpoch(d.year, d.month, d.day, Math.floor(minutes / 60), minutes % 60), actualMs: null, platform: row.platform };
  });
  return {
    line: ref.line,
    destination: stops.at(-1).name,
    stops,
    delaySeconds: null,
    boardingOrder: stops[boarding].order,
    alightOrder: alight != null ? stops[alight].order : null,
  };
}

const scheduleCache = new Map();

export async function getScheduledTrip(ref) {
  const url = new URL(ref.scheduleUrl);
  // Only ever fetch cp.sk; this URL comes from the browser.
  if (url.protocol !== 'https:' || !/(^|\.)cp\.sk$/.test(url.hostname)) {
    throw Object.assign(new Error('Only cp.sk route pages are allowed'), { status: 400 });
  }
  const key = `${url}|${ref.serviceDate}|${ref.line}`;
  if (scheduleCache.has(key)) return scheduleCache.get(key);
  const trip = parseScheduledTrip(await fetchText(url.toString()), ref);
  if (trip.stops.length) {
    if (scheduleCache.size > 500) scheduleCache.clear(); // ponytail: crude bound, LRU if it ever matters
    scheduleCache.set(key, trip);
  }
  return trip;
}

// vlakbusmhd: trains, intercity and city buses together, so the planner finds changes between them.
const CITY_PREFIX = { slovensko: '/bus', banskabystrica: '/banskabystrica', zvolen: '/zvolen', vlakbusmhd: '/vlakbusmhd' };
const prefixFor = (city) => CITY_PREFIX[city] || '/bus';
const unwrapJsonp = (raw) => {
  const a = raw.indexOf('('), b = raw.lastIndexOf(')');
  return a >= 0 && b > a ? raw.slice(a + 1, b) : raw;
};

export async function suggestStops(city, input) {
  const q = String(input || '').trim();
  if (!q) return [];
  const prefix = prefixFor(city);
  const url = `https://cp.sk${prefix}/Ajax/SearchTimetableObjects/?callback=cb&count=18&prefixText=${encodeURIComponent(q)}` +
    '&positionAccuracy=&searchByPosition=false&onlyStation=false&line=&format=json&bindTtIndex=&date=';
  const raw = await fetchText(url, { headers: { 'X-Requested-With': 'XMLHttpRequest', Referer: `https://cp.sk${prefix}/spojenie/` } });
  let arr;
  try { arr = JSON.parse(unwrapJsonp(raw)); } catch { return []; }
  return (Array.isArray(arr) ? arr : []).filter((o) => !o.isHint && o.text && o.value && o.value2).map((o) => ({
    text: str(o.text), value: str(o.value), value2: str(o.value2), coorX: o.coorX ?? '', coorY: o.coorY ?? '', description: str(o.description),
  }));
}

function parseDayMonth(label) {
  const m = /(\d{1,2})\.(\d{1,2})\./.exec(label || '');
  if (!m) return null;
  const today = todayLocal();
  const t = Date.UTC(today.year, today.month - 1, today.day);
  return [today.year - 1, today.year, today.year + 1]
    .map((year) => ({ year, month: Number(m[2]), day: Number(m[1]) }))
    .sort((a, b) => Math.abs(Date.UTC(a.year, a.month - 1, a.day) - t) - Math.abs(Date.UTC(b.year, b.month - 1, b.day) - t))[0];
}

export function parseConnections(html) {
  const doc = parse(html);
  return doc.querySelectorAll('div.connection').filter((b) => (b.id || '').startsWith('connectionBox-')).map((box) => {
    const departureTime = ownText(box.querySelector('div.connection-head h2.date'));
    let date = parseDayMonth(text(box.querySelector('div.connection-head h2.date span.date-after')));
    let previous = clockMinutes(departureTime);
    const segments = [];
    for (const seg of box.querySelectorAll('div.connection-details div.line-item > div.outside-of-popup')) {
      const items = seg.querySelectorAll('ul.stations li.item');
      if (!items.length) continue;
      const first = items[0], last = items.at(-1);
      const platformOf = (item) => text(item.querySelectorAll('p.station span[title]').find((s) => /^(nást|koľ|kol)/i.test(s.getAttribute('title') || '')));
      const s = {
        line: text(seg.querySelector('h3 span')),
        operatorName: text(seg.querySelector('p.line-right-part span.owner span')),
        departureTime: text(first.querySelector('p.time')),
        departureStop: text(first.querySelector('p.station strong.name')),
        arrivalTime: text(last.querySelector('p.time')),
        arrivalStop: text(last.querySelector('p.station strong.name')),
        departurePlatform: platformOf(first),
        arrivalPlatform: platformOf(last),
      };
      if (!s.departureTime || !s.departureStop || !s.arrivalTime || !s.arrivalStop) continue;
      const dep = clockMinutes(s.departureTime);
      if (date && dep != null && previous != null && dep < previous) date = addDays(date, 1);
      previous = clockMinutes(s.arrivalTime) ?? dep;
      const href = seg.querySelectorAll('a').map((a) => a.getAttribute('href') || '').find((h) => h.includes('/draha/'));
      s.routeUrl = href ? new URL(href, 'https://cp.sk/').toString() : '';
      s.serviceDate = date ? isoDate(date) : '';
      segments.push(s);
    }
    if (!segments.length) return null;
    return {
      id: box.id.replace('connectionBox-', ''),
      departureTime: departureTime || segments[0].departureTime,
      arrivalTime: segments.at(-1).arrivalTime,
      totalDuration: text(box.querySelector('div.connection-head p.total strong')),
      segments,
    };
  }).filter(Boolean);
}

const sortKey = (c) => `${c.segments[0].serviceDate} ${c.segments[0].departureTime.padStart(5, '0')}`;
const hidden = (s) => `${s.text}%${s.value}%${s.value2}`;
const position = (s) => (s.coorX && s.coorY ? `${s.coorX}%${s.coorY}` : '');

function token(html, key) {
  const m = new RegExp(`["']?${key}["']?\\s*:\\s*(?:["']([^"']+)["']|([^,}\\s]+))`).exec(html);
  return m ? (m[1] || m[2] || '').trim() : '';
}

export async function searchConnections({ city, from, to, fromSug, toSug, time, date, directOnly }) {
  const prefix = prefixFor(city);
  const resolve = async (input, sug) => sug || (await suggestStops(city, input)).find((s) => s.text.toLowerCase() === input.trim().toLowerCase())
    || (await suggestStops(city, input))[0] || { text: input, value: input, value2: input };
  const [f, t] = await Promise.all([resolve(from, fromSug), resolve(to, toSug)]);
  const form = new URLSearchParams({
    From: f.text, FromHidden: hidden(f), PositionFromHidden: position(f),
    To: t.text, ToHidden: hidden(t), PositionToHidden: position(t),
    'AdvancedForm.Via[0]': '', 'AdvancedForm.ViaHidden[0]': '', AdvancedForm_ViaHiddenCoor_0_: '',
    Date: date || '', Time: (time || '').trim(), IsArr: 'False', OnlyDirect: directOnly ? 'True' : 'False',
    ViaReverse: 'False', DefaultMaxArcLengthFrom: 'true',
  });
  const url = `https://cp.sk${prefix}/spojenie/`;
  const html = await fetchText(url, { method: 'POST', body: form, headers: { Referer: url, 'Content-Type': 'application/x-www-form-urlencoded' } });
  let connections = parseConnections(html);
  if (directOnly) connections = connections.filter((c) => c.segments.length <= 1);
  connections.sort((a, b) => sortKey(a).localeCompare(sortKey(b)));
  const handle = token(html, 'handle') || token(html, 'handleconnthere');
  const searchDate = (/["']?(?:searchDate|dtSearchDate)["']?\s*:\s*["']([^"']+)["']/.exec(html) || [])[1] || '';
  const cursor = handle && searchDate && connections.length
    ? { city, prefix, fromText: f.text, toText: t.text, handle, searchDate, listedIds: connections.map((c) => c.id), directOnly: !!directOnly }
    : null;
  return { connections, cursor, from: f, to: t };
}

export async function moreConnections(cursor) {
  if (!cursor?.listedIds?.length || !CITY_PREFIX[cursor.city]) return { connections: [], cursor: null };
  const prefix = prefixFor(cursor.city);
  const form = new URLSearchParams();
  for (const id of cursor.listedIds) form.append('listedIds[]', id);
  form.set('isPrev', 'false');
  form.set('handle', cursor.handle);
  form.set('searchDate', cursor.searchDate);
  form.set('connId', cursor.listedIds.at(-1));
  form.set('arrivalThere', '0001-01-01T00:00:00');
  form.set('from', cursor.fromText);
  form.set('to', cursor.toText);
  const raw = await fetchText(`https://cp.sk${prefix}/Ajax/ConnPaging/?callback=cb`, {
    method: 'POST', body: form,
    headers: { 'X-Requested-With': 'XMLHttpRequest', Referer: `https://cp.sk${prefix}/spojenie/`, 'Content-Type': 'application/x-www-form-urlencoded' },
  });
  let payload = {};
  try { payload = JSON.parse(unwrapJsonp(raw)); } catch { /* treated as no more results */ }
  let connections = parseConnections((payload.newConnections || []).join('\n'));
  if (cursor.directOnly) connections = connections.filter((c) => c.segments.length <= 1);
  if (!connections.length) return { connections: [], cursor: null };
  return {
    connections,
    cursor: payload.allowNext ? { ...cursor, listedIds: [...new Set([...cursor.listedIds, ...connections.map((c) => c.id)])] } : null,
  };
}
