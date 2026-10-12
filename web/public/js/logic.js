// Pure logic shared by the browser and the server (push alerts). Ported from the
// Android app so both clients agree: TripProgress, CatchEstimate, community keys,
// history insights. No DOM, no fetch.

/** "Bus 507105" -> "507105", same as the Android planner. */
export const lineNumber = (line) => String(line || '').trim().split(' ').pop();

/** "Zvolen,,AS" -> "Zvolen, AS" */
export const readableStop = (name) =>
  String(name || '').split(',').map((s) => s.trim()).filter(Boolean).join(', ');

/**
 * A planner bus to follow: { ref, boardingPlatformIds, alightOrder }. A SAD Zvolen bus from the
 * live data ("sadzv:trip?...") is followed live from the stop where the user gets on; any other
 * bus by its timetable trip link, which knows the user's stops itself.
 */
export function plannerLeg(segment) {
  const stops = { fromStop: readableStop(segment.departureStop), toStop: readableStop(segment.arrivalStop),
    leaves: segment.departureTime || '', arrives: segment.arrivalTime || '' };
  return { ...plannerLegOf(segment), ...stops };
}

function plannerLegOf(segment) {
  const url = String(segment.routeUrl || '');
  if (url.startsWith('sadzv:trip?')) {
    const q = new URLSearchParams(url.slice('sadzv:trip?'.length));
    // sadzv only knows today's run: a city bus on another day comes from the timetable, with that day's times.
    if (q.get('tt') && segment.serviceDate && segment.serviceDate !== isoDate(todayLocal())) {
      return plannerLegOf({ ...segment, routeUrl: q.get('tt') });
    }
    const n = (k) => Number(q.get(k)) || 0;
    return {
      ref: { line: q.get('line') || lineNumber(segment.line), lineId: n('lineId'), routeNumber: q.get('route') || '', tripNumber: n('trip'),
        destination: readableStop(segment.arrivalStop), scheduleUrl: '', serviceDate: segment.serviceDate || '',
        fromStopId: n('stop'), plannedSecondOfDay: q.has('planned') ? n('planned') : -1 },
      boardingPlatformIds: n('board') ? [n('board')] : [],
      alightOrder: n('alight') || null,
    };
  }
  return {
    ref: { line: lineNumber(segment.line), lineId: 0, routeNumber: '', tripNumber: 0, destination: readableStop(segment.arrivalStop),
      scheduleUrl: url, serviceDate: segment.serviceDate || '', fromStopId: 0, plannedSecondOfDay: -1 },
    boardingPlatformIds: [],
    alightOrder: null,
  };
}

const bratislavaDate = (ms) => new Intl.DateTimeFormat('sv-SE', { timeZone: 'Europe/Bratislava' }).format(new Date(ms));

// ---------------------------------------------------------------- Slovak local time
// Operator and timetable times are Slovak wall-clock time, whatever zone the device is in.

const tzFormat = new Intl.DateTimeFormat('en-US', {
  timeZone: 'Europe/Bratislava', hourCycle: 'h23', year: 'numeric', month: '2-digit', day: '2-digit',
  hour: '2-digit', minute: '2-digit', second: '2-digit',
});
function tzParts(ms) {
  return Object.fromEntries(tzFormat.formatToParts(new Date(ms)).map((p) => [p.type, Number(p.value)]));
}
function offsetMs(ms) {
  const p = tzParts(ms);
  return Date.UTC(p.year, p.month - 1, p.day, p.hour, p.minute, p.second) - Math.floor(ms / 1000) * 1000;
}
/** Slovak local date/time -> epoch ms (month is 1-based). */
export function localToEpoch(year, month, day, hour, minute) {
  const guess = Date.UTC(year, month - 1, day, hour, minute);
  return guess - offsetMs(guess - offsetMs(guess));
}
/** The date in Slovakia as {year, month, day}. */
export function todayLocal(ms = Date.now()) {
  const p = tzParts(ms);
  return { year: p.year, month: p.month, day: p.day };
}
/** Minutes after midnight in Slovakia. */
export const localMinutes = (ms) => { const p = tzParts(ms); return p.hour * 60 + p.minute; };
export function addDays({ year, month, day }, n) {
  const d = new Date(Date.UTC(year, month - 1, day + n));
  return { year: d.getUTCFullYear(), month: d.getUTCMonth() + 1, day: d.getUTCDate() };
}
export const isoDate = ({ year, month, day }) => `${year}-${String(month).padStart(2, '0')}-${String(day).padStart(2, '0')}`;

/**
 * Same bus, same key on every phone and in the Android app. Buses from sadzv's board:
 * line and trip ids and the date; timetable trips from the planner: line, first stop, time.
 */
export function communityKey(detail, ref = {}) {
  const first = detail?.stops?.[0];
  if (!first) return null;
  if (!ref.scheduleUrl && ref.lineId) return `sadzv|${ref.lineId}|${ref.tripNumber}|${bratislavaDate(first.scheduledMs)}|${ref.line}`;
  return `${detail.line}|${first.name}|${first.scheduledMs}`;
}

/** GPS further than this from the route is not on it (bus feed / rider's phone). */
export const BUS_GPS_MAX_M = 300;
export const RIDER_GPS_MAX_M = 80;
export const GPS_FRESH_MS = 2 * 60000;

/**
 * Fractional stop index of a GPS point along the stops (2.4 = 40 % from stop 2 to 3) and
 * its distance, or null when too far. expectedIndex breaks ties on out-and-back routes.
 */
export function snapToRoute(lat, lon, coords, maxMeters, expectedIndex = null) {
  const k = Math.cos((lat * Math.PI) / 180);
  const xy = ([a, b]) => [(b - lon) * k * 111320, (a - lat) * 110540];
  let best = null;
  for (let i = 0; i < coords.length - 1; i++) {
    if (!coords[i] || !coords[i + 1]) continue;
    const [ax, ay] = xy(coords[i]);
    const [bx, by] = xy(coords[i + 1]);
    const dx = bx - ax, dy = by - ay, len2 = dx * dx + dy * dy;
    const t = len2 === 0 ? 0 : Math.min(1, Math.max(0, -(ax * dx + ay * dy) / len2));
    const d = Math.hypot(ax + t * dx, ay + t * dy);
    // Roads bend away from the straight line between stops km apart: allow 10 % of the hop, but only beside it
    // (EM-0063: 1.5 km past a stop counted as standing at it).
    if (d > (t > 0 && t < 1 ? Math.max(maxMeters, Math.sqrt(len2) * 0.1) : maxMeters)) continue;
    const index = i + t;
    const cost = d + (expectedIndex == null ? 0 : Math.max(0, Math.abs(index - expectedIndex) - 2) * 150);
    if (!best || cost < best.cost) best = { index, meters: d, cost };
  }
  return best ? { index: best.index, meters: best.meters } : null;
}

export function scheduledAt(index, scheduledMs) {
  if (!scheduledMs.length) return 0;
  const i = Math.min(Math.max(Math.floor(index), 0), scheduledMs.length - 1);
  if (i === scheduledMs.length - 1) return scheduledMs[i];
  const frac = Math.min(1, Math.max(0, index - i));
  return scheduledMs[i] + Math.round((scheduledMs[i + 1] - scheduledMs[i]) * frac);
}

// Timetable times are departures: behind them the bus is late, but it is early only once it
// has left a stop before that stop's time (a bus waiting at a stop early just waits).
export function referenceAt(index, scheduledMs, now = Date.now()) {
  const at = scheduledAt(index, scheduledMs);
  if (now >= at || !scheduledMs.length) return at;
  const left = Math.floor(index - 0.1); // a little past a stop still counts as at it
  if (left < 0) return now;
  return Math.max(now, scheduledMs[Math.min(left, scheduledMs.length - 1)]);
}

export const delayAt = (index, scheduledMs, now = Date.now()) => Math.trunc((now - referenceAt(index, scheduledMs, now)) / 1000);

export function distanceMeters(lat1, lon1, lat2, lon2) {
  const rad = Math.PI / 180;
  const x = (lon2 - lon1) * rad * Math.cos(((lat1 + lat2) / 2) * rad);
  const y = (lat2 - lat1) * rad;
  return Math.round(Math.sqrt(x * x + y * y) * 6371000);
}

/**
 * Where the bus is relative to the user's stops.
 * trip: { boardingPlatformIds: number[], alightOrder: number|null }
 */
export function tripProgress(detail, trip, now = Date.now()) {
  const stops = detail.stops;
  const delayMs = (detail.delaySeconds || 0) * 1000;
  const expected = stops.map((s) => s.actualMs ?? s.scheduledMs + delayMs);
  let nextIndex = stops.findIndex((s, i) => s.actualMs == null && expected[i] >= now - 30000);
  if (nextIndex < 0) nextIndex = stops.length;
  let boardingIndex = stops.findIndex((s) => (trip.boardingPlatformIds || []).includes(s.platformId));
  if (boardingIndex < 0) boardingIndex = null;
  let alightIndex = trip.alightOrder != null ? stops.findIndex((s) => s.order === trip.alightOrder) : -1;
  if (alightIndex < 0) alightIndex = null;
  const endIndex = alightIndex ?? stops.length - 1;
  const onBoard = boardingIndex == null || nextIndex > boardingIndex;

  let position;
  if (nextIndex <= 0) position = 0;
  else if (nextIndex >= stops.length) position = stops.length - 1;
  else {
    const from = expected[nextIndex - 1];
    const to = expected[nextIndex];
    const fraction = to > from ? Math.min(1, Math.max(0, (now - from) / (to - from))) : 0;
    position = nextIndex - 1 + fraction;
  }
  const boardingMs = boardingIndex != null ? expected[boardingIndex] : 0;
  // EM-0059: from a minute before the bus is due at a stop, that stop is "now" and the one after it "next" (only the
  // names; alerts keep going by nextIndex). Before, the stop before stayed "now" until the bus had left.
  const atIndex = nextIndex < stops.length && now >= expected[nextIndex] - 60000 ? nextIndex : null;
  const shownNext = atIndex != null ? atIndex + 1 : nextIndex;
  const nextMs = expected[shownNext] ?? expected[expected.length - 1] ?? now;
  return {
    onBoard,
    finished: nextIndex > endIndex,
    position,
    nextIndex,
    boardingIndex,
    boardingStopName: boardingIndex != null ? stops[boardingIndex].name : '',
    boardingExpectedMs: boardingMs,
    secondsToBoarding: Math.trunc((boardingMs - now) / 1000),
    currentStopName: stops[atIndex ?? nextIndex - 1]?.name ?? null,
    nextStopName: stops[shownNext]?.name ?? null,
    nextExpectedMs: nextMs,
    secondsToNext: Math.max(0, Math.trunc((nextMs - now) / 1000)),
    alightIndex,
    alightStopName: alightIndex != null ? stops[alightIndex].name : null,
    nextIsAlight: alightIndex != null && nextIndex === alightIndex,
    lastStopName: stops[endIndex]?.name ?? '',
    expected,
  };
}

/** Walking pace and street detour; the knobs to tune if estimates run early or late. */
export const WALK_METERS_PER_SECOND = 1.3;
export const DETOUR_FACTOR = 1.3;

/**
 * Before the user is at the stop: when to look at the location again (GPS off in between).
 * After most of the remaining walk, but every 1 to 3 minutes.
 */
export const RECHECK_SHARE = 0.75;
export function nextCheckMs(distance) {
  const walkMs = ((distance * DETOUR_FACTOR) / WALK_METERS_PER_SECOND) * 1000;
  return Math.min(3 * 60000, Math.max(60000, Math.trunc(walkMs * RECHECK_SHARE)));
}

/** Live GPS at the stop starts this long before the bus can come. */
export const LIVE_GPS_BEFORE_MS = 4 * 60000;

/**
 * When the bus can be at the user's stop at the earliest: its expected time, or earlier when
 * it's running early, or (nothing live known) usually leaves early. Lateness isn't counted
 * on, so nobody is told to leave too late. Same as the Android app.
 */
export function earliestBoardingMs(detail, p, positionKnown) {
  const stop = p.boardingIndex != null ? detail.stops[p.boardingIndex] : null;
  if (!stop) return p.boardingExpectedMs;
  const usual = !positionKnown && detail.history ? detail.history.delaySeconds : 0;
  const early = Math.max(0, -(detail.delaySeconds || 0), -usual);
  return early > 0 ? Math.min(p.boardingExpectedMs, stop.scheduledMs - early * 1000) : p.boardingExpectedMs;
}

/**
 * When the bus left or drove past each stop, from the rider's fixes (also while it stands).
 * Timetable times are departures, so that moment is what the delay is measured at. Stop
 * positions are only roughly where buses stop, so crossing a stop decides nothing yet:
 * - it stops anywhere in the stop's area (before or after the point): it stopped there, and
 *   left when it moved on (between the last two fixes);
 * - it leaves the area without stopping: it drove past, at the moment it crossed the point.
 * Returns onFix(index, moving, atMs, atStop) -> [stop, leftAtMs, stoppedThere] | null
 * (atStop: the fix is within the nearest stop's area). Same as the Android app.
 */
export function stopPassDetector() {
  let lastIndex = -1, lastAt = 0, waitingAt = -1, crossed = null, stoppedBefore = false;
  return (index, moving, atMs, atStop = false) => {
    let result = null;
    const nearest = Math.round(index); // atStop is about the nearest stop; close stops overlap
    if (crossed) {
      const inArea = atStop && nearest === crossed[0];
      if (!moving && inArea) { waitingAt = crossed[0]; crossed = null; } // stopped just past the point
      else if (!inArea) { result = crossed; crossed = null; } // out of the area without stopping
    }
    if (!result && waitingAt >= 0 && moving) {
      result = [waitingAt, Math.round((lastAt + atMs) / 2), true];
      waitingAt = -1;
    }
    if (lastIndex >= 0) {
      const stop = Math.floor(index);
      if (stop > Math.floor(lastIndex) && index > lastIndex) {
        const at = lastAt + Math.round(((stop - lastIndex) / (index - lastIndex)) * (atMs - lastAt));
        if (stoppedBefore && moving) result ??= [stop, at, true]; // stood before the point, drove on
        else if (moving && !(atStop && nearest === stop)) result ??= [stop, at, false]; // already past the area
        else if (moving) crossed = [stop, at, false];
        else waitingAt = stop;
      }
    }
    const atStopAhead = atStop && nearest > Math.floor(index); // standing just after a stop is waitingAt
    stoppedBefore = (!moving && atStopAhead) || (stoppedBefore && atStopAhead);
    if (index >= lastIndex) lastIndex = index; // GPS jitter backwards keeps the furthest point
    lastAt = atMs;
    return result;
  };
}

// ---------------------------------------------------------------- regular rides
// { id, route: { city, from, to, fromSug, toSug }, time: 'HH:MM', lines: [], days: [1..7 ISO],
//   dates: ['YYYY-MM-DD'], startDate, endDate, leadMinutes, enabled }

export const WORKDAYS = [1, 2, 3, 4, 5];
export const EVERY_DAY = [1, 2, 3, 4, 5, 6, 7];

export function rideRunsOn(ride, day) {
  const iso = isoDate(day);
  if ((ride.dates || []).includes(iso)) return true;
  const weekday = new Date(Date.UTC(day.year, day.month - 1, day.day)).getUTCDay() || 7;
  if (!(ride.days || []).includes(weekday)) return false;
  return (!ride.startDate || iso >= ride.startDate) && (!ride.endDate || iso <= ride.endDate);
}

/** Epoch ms of the next departure whose follow start (departure - lead) is after [afterMs], or null. */
export function nextRideDeparture(ride, afterMs = Date.now()) {
  const m = /^(\d{1,2}):(\d{2})$/.exec(ride.time || '');
  if (!ride.enabled || !m) return null;
  let day = todayLocal(afterMs);
  for (let i = 0; i <= 366; i++, day = addDays(day, 1)) {
    if (!rideRunsOn(ride, day)) continue;
    const departure = localToEpoch(day.year, day.month, day.day, Number(m[1]), Number(m[2]));
    if (departure - (ride.leadMinutes ?? 10) * 60000 > afterMs) return departure;
  }
  return null;
}

/**
 * That day's bus for [ride] among the planner's results: the same time and lines, else the same
 * first line within 15 min, else any connection within 10 min.
 */
export function pickRideConnection(connections, ride, departureMs) {
  const day = isoDate(todayLocal(departureMs));
  const wanted = localMinutes(departureMs);
  const gap = (c) => {
    const first = c.segments[0];
    const m = /(\d{1,2}):(\d{2})/.exec(first?.departureTime || '');
    if (!m || (first.serviceDate && first.serviceDate !== day)) return Infinity;
    return Math.abs(Number(m[1]) * 60 + Number(m[2]) - wanted);
  };
  const lines = (c) => c.segments.map((s) => lineNumber(s.line));
  const lines0 = ride.lines || [];
  const best = (list) => list.sort((a, b) => gap(a) - gap(b))[0] || null;
  return connections.find((c) => gap(c) === 0 && (!lines0.length || lines(c).join(' ') === lines0.join(' ')))
    || best(connections.filter((c) => lines0.length && lines(c)[0] === lines0[0] && gap(c) <= 15))
    || best(connections.filter((c) => gap(c) <= 10));
}

export function catchEstimate(distance, secondsToBoarding) {
  const walkSeconds = Math.trunc((distance * DETOUR_FACTOR) / WALK_METERS_PER_SECOND);
  const marginSeconds = secondsToBoarding - walkSeconds;
  let verdict;
  if (walkSeconds <= 45) verdict = 'atStop';
  else if (marginSeconds < 0) verdict = 'miss';
  else if (marginSeconds <= 60) verdict = 'leaveNow';
  else if (marginSeconds <= 300) verdict = 'leaveSoon';
  else verdict = 'relaxed';
  return { walkSeconds, marginSeconds, verdict };
}

/** Same numbers as the Android History tab. Items carry amountCents, isTopUp, stopName. */
export function historyInsights(items, balance, now = new Date()) {
  const monthKey = (ms) => {
    const d = new Date(ms);
    return d.getFullYear() * 12 + d.getMonth(); // real UTC timestamps, grouped by local month like Android
  };
  const current = now.getFullYear() * 12 + now.getMonth();
  const spending = items.filter((i) => !i.isTopUp && (i.amountCents ?? 0) < 0);
  const trips = spending.filter((i) => i.sourceType === 'TICKET');
  const months = [];
  for (let back = 5; back >= 0; back--) {
    const key = current - back;
    months.push({
      year: Math.floor(key / 12),
      month: key % 12,
      spentCents: spending.filter((i) => monthKey(i.timestampMs) === key).reduce((a, i) => a - i.amountCents, 0),
      trips: trips.filter((i) => monthKey(i.timestampMs) === key).length,
    });
  }
  const fares = [...trips].sort((a, b) => b.timestampMs - a.timestampMs).slice(0, 20).map((i) => -i.amountCents);
  const averageFareCents = fares.length ? Math.trunc(fares.reduce((a, b) => a + b, 0) / fares.length) : null;
  const tripsLeft = averageFareCents > 0 && balance != null ? Math.max(0, Math.trunc((balance * 100) / averageFareCents)) : null;
  const counts = new Map();
  for (const t of trips) {
    const s = (t.stopName || '').trim();
    if (s) counts.set(s, (counts.get(s) || 0) + 1);
  }
  const topStops = [...counts.entries()].sort((a, b) => b[1] - a[1]).slice(0, 3);
  const oldest = items.length ? Math.min(...items.map((i) => i.timestampMs)) : 0;
  return { months, averageFareCents, tripsLeft, topStops, oldestRecordMs: oldest };
}

const csvField = (v) => (/[",\r\n]/.test(v) ? `"${v.replace(/"/g, '""')}"` : v);

/** RFC 4180 CSV, same columns as the Android export. */
export function historyToCsv(items) {
  const pad = (n) => String(n).padStart(2, '0');
  let out = 'date,type,description,stop,amount_eur\r\n';
  for (const i of items) {
    const d = new Date(i.timestampMs);
    const date = `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
    const type = i.isTopUp ? 'top-up' : i.sourceType === 'TICKET' ? 'ticket' : 'transaction';
    const amount = i.amountCents != null ? (i.amountCents / 100).toFixed(2) : '';
    out += [date, type, i.title || '', i.stopName || '', amount].map(csvField).join(',') + '\r\n';
  }
  return out;
}

/**
 * Which ticket a bus takes, as the Android app's WalletRule: SAD Zvolen's city buses in Banská Bystrica and Zvolen
 * (not in IDS BBSK) the sadzv.qrbus.me card; IDS BBSK lines (licence 060xxx: regional buses, and the city buses of
 * Žiar nad Hronom, Brezno and Nová Baňa) the IDS BBSK one. null: neither for sure, the default is shown.
 */
export function walletForLine(line) {
  const number = String(line || '').trim().split(' ').at(-1);
  if (/^0?60\d{3}$/.test(number)) return 'ids';
  if (/^\d{1,3}[A-Z]?$/.test(number)) return 'sadzv';
  return null;
}
