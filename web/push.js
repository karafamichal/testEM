// Web push for "Follow this bus" on the web version (iOS 16.4+ home-screen apps and
// other browsers). The server polls the trip and sends the same alerts as the Android
// notification: bus arrives in 2 min, get off at the next stop, arrived.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import crypto from 'node:crypto';
import webpush from 'web-push';
import { getLiveTrip, getScheduledTrip, searchConnections } from './cp.js';
import { tripProgress, communityKey, nextRideDeparture, pickRideConnection, lineNumber, readableStop, todayLocal, localMinutes } from './public/js/logic.js';

const DATA_DIR = process.env.DATA_DIR || path.join(path.dirname(fileURLToPath(import.meta.url)), 'data');
const TICK_MS = 20_000;
const MAX_FOLLOW_MS = 3 * 60 * 60 * 1000;

let keys = null;
function vapid() {
  if (keys) return keys;
  const file = path.join(DATA_DIR, 'vapid.json');
  try {
    keys = JSON.parse(fs.readFileSync(file, 'utf8'));
  } catch {
    keys = webpush.generateVAPIDKeys();
    fs.mkdirSync(DATA_DIR, { recursive: true });
    fs.writeFileSync(file, JSON.stringify(keys), { mode: 0o600 });
  }
  webpush.setVapidDetails(process.env.VAPID_SUBJECT || 'mailto:noreply@karafa.network', keys.publicKey, keys.privateKey);
  return keys;
}

export const vapidPublicKey = () => vapid().publicKey;

const TEXT = {
  en: {
    arriving: (line, stop, min) => [`Bus ${line} arrives in ${min} min`, `Get ready at ${stop}.`],
    alight: (stop) => ['Get off at the next stop', `Next stop: ${stop}`],
    arrived: (stop) => ['You’ve arrived', `The bus reached ${stop}. Following has stopped.`],
    change: (line, stop) => [`Change to bus ${line}`, `Get off at ${stop}. Now following your next bus.`],
    rideStarted: (label, time) => [label, `Following your ${time} bus. Tap to see it.`],
    rideMissing: (label, time) => [`${label}: bus not found`, `There’s no ${time} connection today. Check the planner.`],
  },
  sk: {
    arriving: (line, stop, min) => [`Autobus ${line} príde o ${min} min`, `Pripravte sa na zastávke ${stop}.`],
    alight: (stop) => ['Vystupujete na ďalšej zastávke', `Ďalšia zastávka: ${stop}`],
    arrived: (stop) => ['Ste v cieli', `Autobus dorazil na zastávku ${stop}. Sledovanie skončilo.`],
    change: (line, stop) => [`Prestúpte na autobus ${line}`, `Vystúpte na zastávke ${stop}. Teraz sledujeme ďalší autobus.`],
    rideStarted: (label, time) => [label, `Sledujeme váš autobus o ${time}. Ťuknite a pozrite si ho.`],
    rideMissing: (label, time) => [`${label}: autobus sa nenašiel`, `Dnes nejde spoj o ${time}. Pozrite si vyhľadávač.`],
  },
};

// ponytail: follows live in memory; a restart drops them (the app re-subscribes when opened).
const follows = new Map();
let timer = null;
let hub = null;

export function startFollowing(follow, hubRequest) {
  vapid();
  hub = hubRequest;
  // One follow per push subscription: following a new bus replaces the old one.
  for (const [id, f] of follows) if (f.subscription.endpoint === follow.subscription.endpoint) follows.delete(id);
  const id = crypto.randomBytes(12).toString('hex');
  follows.set(id, { ...follow, sent: new Set(), startedAt: Date.now() });
  timer ??= setInterval(tick, TICK_MS);
  return id;
}

export function stopFollowing(id) {
  return follows.delete(id);
}

async function send(f, key, [title, body], url = '/?show=follow') {
  if (f.sent.has(key)) return;
  f.sent.add(key);
  try {
    // url: where tapping it leads (the followed bus's stops on the home tab).
    await webpush.sendNotification(f.subscription, JSON.stringify({ title, body, tag: 'testem-' + key, url }), { TTL: 300 });
  } catch (err) {
    if (err.statusCode === 404 || err.statusCode === 410) f.gone = true; // subscription expired
  }
}

async function tick() {
  const trips = new Map(); // one fetch per bus per tick, however many followers
  for (const [id, f] of follows) {
    if (f.gone || Date.now() - f.startedAt > MAX_FOLLOW_MS) { follows.delete(id); continue; }
    const key = JSON.stringify(f.ref);
    try {
      if (!trips.has(key)) {
        trips.set(key, (f.ref.scheduleUrl ? getScheduledTrip(f.ref) : getLiveTrip(f.ref)).then(async (detail) => {
          const ck = f.community && hub ? communityKey(detail, f.ref) : null;
          if (!ck) return detail;
          const pooled = (await hub('community/delay?trip=' + encodeURIComponent(ck)).catch(() => null))?.delay;
          // Riders' phones beat the bus feed; taps only correct a bus without live GPS.
          if (pooled && (pooled.source === 'gps' || detail.positionSource !== 'busGps')) return { ...detail, delaySeconds: pooled.delaySeconds };
          return detail;
        }));
      }
      const detail = await trips.get(key);
      if (!detail.stops.length) continue;
      // Planner buses learn where the user gets on and off from cp.sk's route page.
      if (!f.boardingPlatformIds?.length && detail.boardingOrder) {
        f.boardingPlatformIds = [detail.boardingOrder];
        f.alightOrder ??= detail.alightOrder ?? null;
      }
      const p = tripProgress(detail, f);
      const t = TEXT[f.lang];
      if (p.finished) {
        const [next, ...rest] = f.nextLegs || [];
        if (next) {
          // A journey with changes: on to the next bus.
          await send(f, 'change-' + next.ref.line + '-' + next.ref.scheduleUrl, t.change(next.ref.line, p.lastStopName));
          Object.assign(f, { ref: next.ref, boardingPlatformIds: [], alightOrder: null, nextLegs: rest, sent: new Set(f.sent) });
          for (const k of ['arriving', 'alight']) f.sent.delete(k);
          continue;
        }
        await send(f, 'arrived', t.arrived(p.lastStopName));
        follows.delete(id);
        continue;
      }
      const lead = f.arriveLeadMinutes || 2; // the rider's choice (Reminders)
      if (!p.onBoard && p.secondsToBoarding >= 0 && p.secondsToBoarding <= lead * 60) {
        const min = Math.min(lead, Math.max(1, Math.round(p.secondsToBoarding / 60)));
        await send(f, 'arriving', t.arriving(f.ref.line, p.boardingStopName, min));
      }
      if (p.onBoard && p.nextIsAlight) await send(f, 'alight', t.alight(p.alightStopName));
    } catch {
      // Upstream hiccup; try again next tick.
    }
  }
  if (!follows.size) { clearInterval(timer); timer = null; }
}

// ---------------------------------------------------------------------------
// Regular rides: kept per push subscription in DATA_DIR/rides.json (survives restarts).
// ---------------------------------------------------------------------------

const RIDES_FILE = path.join(DATA_DIR, 'rides.json');
let rideStore = null; // endpoint -> { subscription, rides, lang, arriveLeadMinutes, community, fired: { rideId: departureMs } }
let rideTimer = null;

function loadRides() {
  if (rideStore) return rideStore;
  try { rideStore = JSON.parse(fs.readFileSync(RIDES_FILE, 'utf8')); } catch { rideStore = {}; }
  return rideStore;
}
function saveRides() {
  fs.mkdirSync(DATA_DIR, { recursive: true });
  fs.writeFileSync(RIDES_FILE, JSON.stringify(rideStore), { mode: 0o600 });
}

const clip = (v, n) => String(v ?? '').slice(0, n);
const isoDay = (v) => (/^\d{4}-\d{2}-\d{2}$/.test(v || '') ? v : null);
const suggestion = (x) => (x && typeof x === 'object'
  ? { text: clip(x.text, 200), value: clip(x.value, 200), value2: clip(x.value2, 200), coorX: clip(x.coorX, 30), coorY: clip(x.coorY, 30) }
  : null);
const CITIES = ['vlakbusmhd', 'slovensko', 'banskabystrica', 'zvolen'];

/** Only the fields a ride has, with sane sizes: this comes from the browser. */
function cleanRide(r) {
  if (!r || typeof r !== 'object' || !/^\d{1,2}:\d{2}$/.test(r.time || '') || !r.route?.from || !r.route?.to) return null;
  return {
    id: clip(r.id, 64),
    route: {
      city: CITIES.includes(r.route.city) ? r.route.city : 'vlakbusmhd',
      from: clip(r.route.from, 120), to: clip(r.route.to, 120),
      fromSug: suggestion(r.route.fromSug), toSug: suggestion(r.route.toSug),
    },
    time: r.time,
    lines: (Array.isArray(r.lines) ? r.lines : []).slice(0, 10).map((x) => clip(x, 20)),
    days: (Array.isArray(r.days) ? r.days : []).map(Number).filter((d) => d >= 1 && d <= 7),
    dates: (Array.isArray(r.dates) ? r.dates : []).map(isoDay).filter(Boolean).slice(0, 100),
    startDate: isoDay(r.startDate), endDate: isoDay(r.endDate),
    leadMinutes: Math.min(120, Math.max(1, Math.round(Number(r.leadMinutes) || 10))),
    enabled: r.enabled !== false,
  };
}

export function syncRides(entry, hubRequest) {
  vapid();
  hub = hubRequest;
  loadRides();
  const rides = entry.rides.map(cleanRide).filter(Boolean);
  const key = entry.subscription.endpoint;
  if (!rides.length) delete rideStore[key];
  else rideStore[key] = { ...entry, rides, fired: rideStore[key]?.fired || {} };
  saveRides();
  rideTimer ??= setInterval(rideTick, 60_000);
  return { ok: true, rides: rides.length };
}

const rideLabel = (r) => `${readableStop(r.route.from)} → ${readableStop(r.route.to)}`;
const hhmm = (ms) => { const m = localMinutes(ms); return `${Math.floor(m / 60)}:${String(m % 60).padStart(2, '0')}`; };

async function rideTick() {
  const now = Date.now();
  let changed = false;
  for (const [key, entry] of Object.entries(loadRides())) {
    for (const ride of entry.rides) {
      // Due: its follow start (departure - lead) has come within the last 10 minutes.
      const departure = nextRideDeparture(ride, now - 10 * 60000);
      if (departure == null || departure - ride.leadMinutes * 60000 > now || entry.fired[ride.id] === departure) continue;
      entry.fired[ride.id] = departure;
      changed = true;
      const f = { subscription: entry.subscription, sent: new Set() };
      try {
        const legs = await findRideLegs(ride, departure);
        const t = TEXT[entry.lang];
        if (!legs.length) { await send(f, 'missing', t.rideMissing(rideLabel(ride), hhmm(departure)), '/'); continue; }
        startFollowing({
          subscription: entry.subscription, ref: legs[0], boardingPlatformIds: [], alightOrder: null,
          nextLegs: legs.slice(1).map((ref) => ({ ref })), lang: entry.lang,
          arriveLeadMinutes: entry.arriveLeadMinutes, community: entry.community,
        }, hub);
        await send(f, 'started', t.rideStarted(rideLabel(ride), hhmm(departure)), `/?ride=${encodeURIComponent(ride.id)}&dep=${departure}`);
      } catch {
        // cp.sk down: nothing to follow this time.
      }
      if (f.gone) { delete rideStore[key]; break; }
    }
  }
  if (changed) saveRides();
}

/** That day's buses for a ride, as trip refs for following (cp.sk timetable times). */
async function findRideLegs(ride, departure) {
  const day = todayLocal(departure);
  const search = departure - 2 * 60000;
  const r = await searchConnections({
    city: ride.route.city, from: ride.route.from, to: ride.route.to, fromSug: ride.route.fromSug, toSug: ride.route.toSug,
    time: hhmm(search), date: `${day.day}.${day.month}.${day.year}`, directOnly: false,
  });
  const connection = pickRideConnection(r.connections, ride, departure);
  return (connection?.segments || []).filter((s) => s.routeUrl).map((s) => ({
    line: lineNumber(s.line), lineId: 0, routeNumber: '', tripNumber: 0, destination: readableStop(s.arrivalStop),
    scheduleUrl: s.routeUrl, serviceDate: s.serviceDate, fromStopId: 0, plannedSecondOfDay: -1,
  }));
}

// Rides saved before a restart keep running.
if (Object.keys(loadRides()).length) { vapid(); rideTimer = setInterval(rideTick, 60_000); }
