// Public transit data for the web client, the same as the Android app's:
//  - sadzv.qrbus.me live data (stops, departure boards, trips, vehicles)
//  - emhub timetables (stop suggestions, connections, other carriers, trip stop lists)
// All times the browser gets are epoch ms; Slovak wall-clock conversion happens here.
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

/** Best match for a stop name from the timetable ("Banska Bystrica, Namestie slobody"). */
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

/** Stop coordinates: sadzv platforms for sadzv trips; timetable trips come with theirs from emhub. */
export async function withCoordinates(trip, fromSadzv) {
  if (!fromSadzv || trip.stops.every((s) => s.lat != null)) return trip;
  const platforms = new Map((await getStops()).flatMap((s) => s.platforms.map((p) => [p.id, p])));
  return { ...trip, stops: trip.stops.map((s) => { const p = platforms.get(s.platformId); return p ? { ...s, lat: p.lat, lon: p.lon } : s; }) };
}

/** The bus's own GPS, when fresh and on the route, becomes the delay. */
async function placeBus(trip, busPosition, fromSadzv) {
  if (!busPosition || trip.stops.length < 2) return trip;
  const placed = await withCoordinates(trip, fromSadzv);
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

const minutesGap = (a, b) => Math.min(Math.abs(a - b), 1440 - Math.abs(a - b));

// ---------------------------------------------------------------- timetable (emhub)

const HUB_URL = (process.env.HUB_URL || '').replace(/\/$/, '');
const HUB_APP_KEY = process.env.HUB_APP_KEY || '';
/** value2 of stop suggestions from emhub; older saved ones came from cp.sk and are looked up by name. */
const HUB_STOP = 'hub';
/** S.A.D. Zvolen buses are on the board live from sadzv already. */
const SADZV_FEED = 'sad-zvolen-as';

async function timetable(path, params) {
  if (!HUB_URL || !HUB_APP_KEY) throw Object.assign(new Error('Timetables are not set up on this server'), { status: 503 });
  const res = await fetch(`${HUB_URL}/api/v1/timetable/${path}?${new URLSearchParams(params)}`, {
    headers: { 'X-App-Key': HUB_APP_KEY }, signal: AbortSignal.timeout(30000),
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw Object.assign(new Error(data.error || `Timetable HTTP ${res.status}`), { status: res.status === 404 || res.status === 400 ? res.status : 502 });
  return data;
}

/** EM-0064: the roads between a trip's stops ("lat,lon;lat,lon;..."), as drawn or snapped in emHUB: one list per hop. */
/** EM-0072: seconds by car along the roads, "lat,lon" to "lat,lon". */
export async function driveSeconds(from, to) {
  return timetable('drive', { from: String(from).slice(0, 40), to: String(to).slice(0, 40) });
}

export async function roadPath(stops) {
  return (await timetable('path', { stops: String(stops).slice(0, 2000) })).hops || [];
}

const clockOf = (ms) => new Intl.DateTimeFormat('en-GB', { timeZone: TZ, hour: 'numeric', minute: '2-digit', hourCycle: 'h23' }).format(ms);
const dateOf = (ms) => new Intl.DateTimeFormat('sv-SE', { timeZone: TZ }).format(ms);
const minutesOf = (ms) => { const [h, m] = clockOf(ms).split(':').map(Number); return h * 60 + m; };

/** A trip link: "hub:trip?id=<feed:trip:YYYYMMDD>&from=<stop index>&to=<stop index>", like the Android app's. */
export const tripUrl = (id, from, to) => `hub:trip?id=${encodeURIComponent(id)}${from != null ? `&from=${from}` : ''}${to != null ? `&to=${to}` : ''}`;
/** A live SAD Zvolen bus with where the user gets on and off (see plannerLeg in logic.js). */
const liveUrl = (l) => `sadzv:trip?${new URLSearchParams({
  line: l.live.line, lineId: l.live.lineId, route: l.live.routeNumber, trip: l.live.tripNumber, board: l.from.platformId,
  alight: l.to.seq, stop: l.live.stopId, planned: minutesOf(l.departure) * 60,
  // sadzv only knows today's run: on another day the bus opens as this timetable trip.
  ...(l.live.fromIndex != null ? { tt: tripUrl(l.tripId, l.live.fromIndex, l.live.toIndex) } : {}),
})}`;
export function parseTripUrl(url) {
  if (!String(url || '').startsWith('hub:trip?')) return null;
  const q = new URLSearchParams(url.slice('hub:trip?'.length));
  const n = (v) => (v == null || v === '' ? null : Number(v));
  return q.get('id') ? { id: q.get('id'), from: n(q.get('from')), to: n(q.get('to')) } : null;
}

const sameLine = (a, b) => a === b || (!!a && !!b && (a.endsWith(b) || b.endsWith(a)));

/** sadzv and the timetable write lines differently ("606106" vs "60106"): same minute and matching line. */
function sameBus(d, row) {
  return minutesGap(Math.floor(d.plannedSecondOfDay / 60), row.minutes) <= 1 && sameLine(d.line, row.line);
}

const suggestion = (s) => ({ text: s.name, value: s.id, value2: HUB_STOP, coorX: s.lat ?? '', coorY: s.lon ?? '', description: '' });

/** EM-0068: the timetable stop nearest to the rider (within 1.5 km), or null. */
export async function nearestStop(lat, lon) {
  if (!Number.isFinite(lat) || !Number.isFinite(lon)) return null;
  const s = (await timetable('stops', { lat, lon, radius: 1500, n: 1 })).stops[0];
  return s ? suggestion(s) : null;
}

export async function suggestStops(_city, input) {
  const q = String(input || '').trim();
  return q ? (await timetable('stops', { q })).stops.map(suggestion) : [];
}

async function boardRows(params) {
  return (await timetable('departures', params)).departures.map((d) => ({
    line: d.line, minutes: minutesOf(d.at), destination: d.headsign, platform: d.platform || '', operator: d.operator || '',
    atMs: d.at, scheduleUrl: tripUrl(d.tripId, d.seq, null), serviceDate: dateOf(d.at),
  }));
}

const nearStops = new Map(); // sadzv stop id -> timetable station | null
const boards = new Map(); // sadzv stop id -> { at, rows }

/** Buses of other carriers (SAD Lučenec, Arriva, Slovak Lines, ...) at a sadzv stop, minus the ones sadzv shows live. */
export async function otherCarriers(stop, live) {
  if (!nearStops.has(stop.id)) {
    if (nearStops.size > 2000) nearStops.clear();
    const found = await timetable('stops', { lat: stop.lat, lon: stop.lon, radius: 300, n: 1 }).catch(() => null);
    nearStops.set(stop.id, found?.stops[0] || null);
  }
  const station = nearStops.get(stop.id);
  if (!station) return [];
  const now = Date.now();
  let cached = boards.get(stop.id);
  // The timetable doesn't change: every 2 min is plenty (boards refresh every 15 s).
  if (!cached || now - cached.at > 2 * 60000) {
    cached = { at: now, rows: await boardRows({ stop: station.id, n: 40, exclude: SADZV_FEED }) };
    if (boards.size > 500) boards.clear();
    boards.set(stop.id, cached);
  }
  return cached.rows.filter((r) => r.atMs > now - 60000 && !live.some((d) => sameBus(d, r))).map((r) => ({
    line: r.line, lineId: 0, destination: readableStop(r.destination), platformNumber: r.platform, routeNumber: '', tripNumber: 0,
    plannedSecondOfDay: r.minutes * 60, departsAtMs: r.atMs, isRealtime: false, isCancelled: false, delaySeconds: null,
    fromTimetable: true, operator: r.operator, scheduleUrl: r.scheduleUrl, serviceDate: r.serviceDate,
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
 * The timetable trip of a sadzv bus without a stop list: the same line leaving within 300 m of
 * the stop within 2 minutes of its planned time. Returns { scheduleUrl, serviceDate } or null.
 */
export async function findTimetableTrip(stop, line, plannedMinutes) {
  const today = todayLocal();
  const nowMinutes = minutesOf(Date.now());
  // A planned time far from now belongs to the nearest day (tomorrow morning's bus, or last night's).
  const shift = plannedMinutes - nowMinutes > 720 ? -1 : nowMinutes - plannedMinutes > 720 ? 1 : 0;
  const d = addDays(today, shift);
  const start = localToEpoch(d.year, d.month, d.day, 0, 0) + (plannedMinutes - 3) * 60000;
  const rows = await boardRows({ lat: stop.lat, lon: stop.lon, radius: 300, time: Math.floor(start / 1000), n: 60 });
  return rows.filter((r) => sameLine(r.line, line) && minutesGap(r.minutes, plannedMinutes) <= 2)
    .sort((a, b) => minutesGap(a.minutes, plannedMinutes) - minutesGap(b.minutes, plannedMinutes))[0] || null;
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
    const row = await findTimetableTrip(stop, ref.line, Math.floor(ref.plannedSecondOfDay / 60) % 1440).catch(() => null);
    if (row) trip = await getScheduledTrip({ line: ref.line, destination: ref.destination, scheduleUrl: row.scheduleUrl, serviceDate: row.serviceDate }).catch(() => null);
  }
  if (fallbackCache.size > 2000) fallbackCache.clear();
  fallbackCache.set(key, { trip: trip?.stops.length ? trip : null, at: Date.now() });
  return trip?.stops.length ? trip : null;
}

export async function getLiveTrip(ref) {
  // A timetable bus without its trip link: sadzv knows nothing about it.
  if (!ref.lineId) {
    const found = await timetableFallback(ref);
    return found
      ? { ...found, line: ref.line, destination: ref.destination || found.destination, alightOrder: null, fromTimetable: true, positionSource: 'timetable' }
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
    // sadzv often has no stop list (its own site too): quietly use the timetable,
    // keeping sadzv's live delay when the bus reports one.
    const found = await timetableFallback(ref);
    if (found) {
      return placeBus({ ...found, line: ref.line, destination: ref.destination || found.destination,
        delaySeconds: vehicle?.delaySeconds ?? null, alightOrder: null, fromTimetable: true,
        positionSource: vehicle?.delaySeconds != null ? 'busDelay' : 'timetable' }, busPosition, false);
    }
  }
  const delaySeconds = vehicle?.delaySeconds ?? passedDelay;
  return placeBus({
    line: ref.line,
    destination: ref.destination || stops.at(-1)?.name || '',
    stops,
    delaySeconds,
    positionSource: delaySeconds != null ? 'busDelay' : 'timetable',
  }, busPosition, true);
}

/**
 * A trip from emhub; `from`/`to` are where the user gets on and off (null: not chosen),
 * and where they get off the arrival time counts. Same rules as the Android app.
 */
export function hubTrip(json, ref, from, to) {
  const stops = (json.stops || []).map((s, i) => ({
    name: s.name, platformId: i + 1, order: i + 1, scheduledMs: i === to ? s.arrival : s.departure, actualMs: null,
    platform: s.platform || '', lat: s.lat ?? null, lon: s.lon ?? null,
  }));
  return {
    line: ref.line, destination: json.headsign || stops.at(-1)?.name || '', stops, delaySeconds: null,
    boardingOrder: (from ?? 0) + 1, alightOrder: to != null ? to + 1 : null,
  };
}

const scheduleCache = new Map();

export async function getScheduledTrip(ref) {
  const link = parseTripUrl(ref.scheduleUrl);
  // Journeys saved before 2.7 point at cp.sk, which we no longer use: such a trip has no stops.
  if (!link) return { line: ref.line, destination: ref.destination, stops: [], delaySeconds: null };
  if (scheduleCache.has(ref.scheduleUrl)) return scheduleCache.get(ref.scheduleUrl);
  const trip = hubTrip(await timetable('trip', { id: link.id }), ref, link.from, link.to);
  if (trip.stops.length) {
    if (scheduleCache.size > 500) scheduleCache.clear(); // ponytail: crude bound, LRU if it ever matters
    scheduleCache.set(ref.scheduleUrl, trip);
  }
  return trip;
}

// ---------------------------------------------------------------- planner (emhub)

const stopParam = (sug, text) => (sug?.value2 === HUB_STOP ? sug.value : String(sug?.text || text || '').trim());

function connection(c) {
  const trips = c.legs.filter((l) => l.type === 'trip');
  const segments = trips.map((l) => ({
    // Trains carry their kind ("R 833"); buses get a word, like the Android app ("Bus 60107").
    line: l.mode === 'rail' ? l.line : `Bus ${l.line}`,
    operatorName: l.operator || '',
    departureTime: clockOf(l.departure), departureStop: l.from.name,
    arrivalTime: clockOf(l.arrival), arrivalStop: l.to.name,
    departurePlatform: l.from.platform || '', arrivalPlatform: l.to.platform || '',
    // A city bus from SAD Zvolen's live data is followed live; others by their timetable trip.
    routeUrl: l.live ? liveUrl(l) : tripUrl(l.tripId, l.from.seq, l.to.seq), serviceDate: dateOf(l.departure),
    transfer: l.transfer || null, // 2.17.0: { worked, runs } of the change onto this bus on earlier days
  }));
  const walkMinutes = Math.round(c.legs.filter((l) => l.type === 'walk').reduce((n, w) => n + (w.arrival - w.departure), 0) / 60000);
  const minutes = Math.round((c.arrival - c.departure) / 60000);
  return {
    id: trips.map((l) => l.tripId).join('|'),
    departureTime: segments[0]?.departureTime || clockOf(c.departure),
    arrivalTime: segments.at(-1)?.arrivalTime || clockOf(c.arrival),
    totalDuration: minutes < 60 ? `${minutes} min` : `${Math.floor(minutes / 60)} h ${minutes % 60} min`,
    segments, walkMinutes,
  };
}

async function plan(from, to, epochS, directOnly, listed, arriveBy = false) {
  const r = await timetable('plan', { from, to, time: epochS, n: 5, direct: directOnly ? 1 : 0, arrive: arriveBy ? 1 : 0 });
  const connections = r.connections.map(connection).filter((c) => c.segments.length && !listed.includes(c.id));
  // "More" lists later departures: none after the time to be there (EM-0053).
  const last = arriveBy ? null : r.connections.at(-1);
  const cursor = last ? { from, to, next: Math.floor(last.departure / 1000) + 60, directOnly: !!directOnly, listedIds: [...listed, ...connections.map((c) => c.id)] } : null;
  return { connections, cursor, from: suggestion(r.from), to: suggestion(r.to) };
}

/** date: "" (today), "2026-09-30" or "30.9.2026"; time: "7:05". */
export async function searchConnections({ from, to, fromSug, toSug, time, date, directOnly, arriveBy }) {
  const iso = /^(\d{1,2})\.(\d{1,2})\.(\d{4})$/.exec(date || '');
  const day = iso ? { year: +iso[3], month: +iso[2], day: +iso[1] }
    : /^\d{4}-\d{2}-\d{2}$/.test(date || '') ? (([y, m, d]) => ({ year: y, month: m, day: d }))(date.split('-').map(Number)) : todayLocal();
  const hm = /^(\d{1,2}):(\d{2})$/.exec(String(time || '').trim());
  const epoch = hm ? localToEpoch(day.year, day.month, day.day, +hm[1], +hm[2]) : Date.now();
  const r = await plan(stopParam(fromSug, from), stopParam(toSug, to), Math.floor(epoch / 1000), directOnly, [], !!(arriveBy && hm));
  // 2.17.0: rain where the journey starts (emHUB asks Open-Meteo): connections with little walking first.
  const { coorX: lat, coorY: lon } = r.from || {};
  const rainy = lat !== '' && lon !== '' && lat != null
    && !!(await timetable('weather', { lat: Number(lat).toFixed(3), lon: Number(lon).toFixed(3) }).catch(() => null))?.rainy;
  if (rainy) r.connections = [...r.connections].sort((a, b) => (a.walkMinutes > 3) - (b.walkMinutes > 3));
  return { ...r, rainy };
}

export async function moreConnections(cursor) {
  if (!cursor?.from || !cursor?.to || !Number.isFinite(Number(cursor.next))) return { connections: [], cursor: null };
  const r = await plan(String(cursor.from), String(cursor.to), Number(cursor.next), cursor.directOnly, (cursor.listedIds || []).map(String));
  return { connections: r.connections, cursor: r.connections.length ? r.cursor : null };
}
