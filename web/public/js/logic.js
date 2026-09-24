// Pure logic shared by the browser and the server (push alerts). Ported from the
// Android app so both clients agree: TripProgress, CatchEstimate, community keys,
// history insights. No DOM, no fetch.

/** "Bus 507105" -> "507105", same as the Android planner. */
export const lineNumber = (line) => String(line || '').trim().split(' ').pop();

/** "Zvolen,,AS" -> "Zvolen, AS" */
export const readableStop = (name) =>
  String(name || '').split(',').map((s) => s.trim()).filter(Boolean).join(', ');

/** Same bus, same key on every phone: line, first stop, first departure (epoch ms). */
export function communityKey(detail) {
  const first = detail?.stops?.[0];
  return first ? `${detail.line}|${first.name}|${first.scheduledMs}` : null;
}

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
