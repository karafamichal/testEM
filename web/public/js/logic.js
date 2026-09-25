// Pure logic shared by the browser and the server (push alerts). Ported from the
// Android app so both clients agree: TripProgress, CatchEstimate, community keys,
// history insights. No DOM, no fetch.

/** "Bus 507105" -> "507105", same as the Android planner. */
export const lineNumber = (line) => String(line || '').trim().split(' ').pop();

/** "Zvolen,,AS" -> "Zvolen, AS" */
export const readableStop = (name) =>
  String(name || '').split(',').map((s) => s.trim()).filter(Boolean).join(', ');

const bratislavaDate = (ms) => new Intl.DateTimeFormat('sv-SE', { timeZone: 'Europe/Bratislava' }).format(new Date(ms));

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
    if (d > maxMeters) continue;
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
  const nextMs = expected[nextIndex] ?? expected[expected.length - 1] ?? now;
  return {
    onBoard,
    finished: nextIndex > endIndex,
    position,
    nextIndex,
    boardingIndex,
    boardingStopName: boardingIndex != null ? stops[boardingIndex].name : '',
    boardingExpectedMs: boardingMs,
    secondsToBoarding: Math.trunc((boardingMs - now) / 1000),
    currentStopName: stops[nextIndex - 1]?.name ?? null,
    nextStopName: stops[nextIndex]?.name ?? null,
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
