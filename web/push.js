// Web push for "Follow this bus" on the web version (iOS 16.4+ home-screen apps and
// other browsers). The server polls the trip and sends the same alerts as the Android
// notification: bus arrives in 2 min, get off at the next stop, arrived.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import crypto from 'node:crypto';
import webpush from 'web-push';
import { getLiveTrip, getScheduledTrip } from './cp.js';
import { tripProgress, communityKey } from './public/js/logic.js';

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
  },
  sk: {
    arriving: (line, stop, min) => [`Autobus ${line} príde o ${min} min`, `Pripravte sa na zastávke ${stop}.`],
    alight: (stop) => ['Vystupujete na ďalšej zastávke', `Ďalšia zastávka: ${stop}`],
    arrived: (stop) => ['Ste v cieli', `Autobus dorazil na zastávku ${stop}. Sledovanie skončilo.`],
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

async function send(f, key, [title, body]) {
  if (f.sent.has(key)) return;
  f.sent.add(key);
  try {
    await webpush.sendNotification(f.subscription, JSON.stringify({ title, body, tag: 'testem-' + key }), { TTL: 300 });
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
      const p = tripProgress(detail, f);
      const t = TEXT[f.lang];
      if (p.finished) {
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
