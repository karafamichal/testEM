// Plan&Ride web version (testEM internally), unofficial client for sadzv.qrbus.me.
// Serves the PWA static files AND acts as a same-origin proxy to qrbus,
// because a browser cannot talk to qrbus directly (CORS + cross-site cookies).
//
// The proxy is the ONLY part that talks to qrbus. It mirrors the request
// sequence the Android app performs (see QRDaemonService.kt).

import express from 'express';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';
import * as transit from './transit.js';
import { idsLogin, idsAccount, idsQr, idsHistory } from './ids.js';
import { startFollowing, stopFollowing, vapidPublicKey, syncRides } from './push.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

const BASE_URL = process.env.QRBUS_BASE_URL || 'https://sadzv.qrbus.me';
const PORT = process.env.PORT || 3000;
// emhub (bug reports + community delays). The key stays on this server, never in the browser.
const HUB_URL = (process.env.HUB_URL || '').replace(/\/$/, '');
const HUB_APP_KEY = process.env.HUB_APP_KEY || '';
const USER_AGENT =
  'Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/144.0.0.0 Mobile Safari/537.36';

// ---------------------------------------------------------------------------
// Cookie jar: a minimal name->value store, enough for this single host.
// ---------------------------------------------------------------------------
class CookieJar {
  constructor() {
    this.cookies = new Map();
    // Consent cookies are normally set by the site's JavaScript. The Android
    // client injects them manually; we do the same so the session works.
    this.cookies.set('pisnotshowhint', 'true');
    this.cookies.set(
      'piscookiewindow',
      '{%22requiredCookies%22:true%2C%22analyticsCookies%22:true}'
    );
  }

  // Accept either a getSetCookie() array or a single Set-Cookie string.
  store(setCookieHeaders) {
    if (!setCookieHeaders) return;
    const list = Array.isArray(setCookieHeaders) ? setCookieHeaders : [setCookieHeaders];
    for (const header of list) {
      if (!header) continue;
      const firstPair = header.split(';')[0];
      const eq = firstPair.indexOf('=');
      if (eq <= 0) continue;
      const name = firstPair.slice(0, eq).trim();
      const value = firstPair.slice(eq + 1).trim();
      if (name) this.cookies.set(name, value);
    }
  }

  header() {
    // Send WPIS first, like the Android client does.
    const entries = [...this.cookies.entries()];
    entries.sort((a, b) => {
      const aw = a[0].toUpperCase() === 'WPIS' ? 0 : 1;
      const bw = b[0].toUpperCase() === 'WPIS' ? 0 : 1;
      return aw - bw || a[0].localeCompare(b[0]);
    });
    return entries.map(([k, v]) => `${k}=${v}`).join('; ');
  }

  get(name) {
    return this.cookies.get(name);
  }

  csrfToken() {
    const raw =
      this.get('XSRF-TOKEN') || this.get('CSRF-TOKEN') || this.get('csrftoken');
    return raw ? decodeURIComponent(raw) : null;
  }

  hasSession() {
    return this.cookies.has('WPIS');
  }
}

function baseHeaders(jar, extra = {}) {
  const headers = {
    Accept: '*/*',
    'Accept-Language': 'en-US,en;q=0.9',
    'Cache-Control': 'no-cache',
    Pragma: 'no-cache',
    'User-Agent': USER_AGENT,
    'X-Requested-With': 'XMLHttpRequest',
    Cookie: jar.header(),
    ...extra,
  };
  const csrf = jar.csrfToken();
  if (csrf) {
    headers['X-XSRF-TOKEN'] = csrf;
    headers['X-CSRF-TOKEN'] = csrf;
  }
  return headers;
}

// ---------------------------------------------------------------------------
// qrbus operations
// ---------------------------------------------------------------------------
async function login(email, password) {
  const jar = new CookieJar();

  // Step 1 + 2: warm up the session (collect cookies) like the Android app.
  const warmup = await fetch(`${BASE_URL}/`, {
    headers: { 'User-Agent': USER_AGENT },
    redirect: 'follow',
  });
  jar.store(warmup.headers.getSetCookie?.());

  const account = await fetch(`${BASE_URL}/account`, {
    headers: { 'User-Agent': USER_AGENT, Referer: BASE_URL },
    redirect: 'follow',
  });
  jar.store(account.headers.getSetCookie?.());

  // Step 3: submit credentials.
  const body = new URLSearchParams();
  body.set('post[login]', email);
  body.set('post[password]', password);

  const res = await fetch(`${BASE_URL}/accountapi/login`, {
    method: 'POST',
    headers: baseHeaders(jar, {
      'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8',
      Origin: BASE_URL,
      Referer: `${BASE_URL}/account/login`,
    }),
    body,
    redirect: 'follow',
  });
  jar.store(res.headers.getSetCookie?.());

  const text = await res.text();
  if (res.status !== 200) {
    throw new HttpError(res.status, `Login failed: HTTP ${res.status}`);
  }
  let json;
  try {
    json = JSON.parse(text);
  } catch {
    throw new HttpError(502, 'Login response was not JSON (wrong credentials or site changed)');
  }
  if (!json.success) {
    throw new HttpError(401, 'Invalid email or password');
  }
  if (!jar.hasSession()) {
    throw new HttpError(502, 'Login succeeded but no session cookie was issued');
  }

  // Pull account detail to recover the card serial number + name + balance.
  const detail = await fetchAccountDetail(jar).catch(() => null);
  return { jar, detail };
}

async function fetchAccountDetail(jar) {
  const res = await fetch(`${BASE_URL}/userapi/getAccountDetail`, {
    headers: baseHeaders(jar, { Referer: `${BASE_URL}/account/login` }),
    redirect: 'follow',
  });
  if (res.status === 401) throw new HttpError(401, 'Session expired');
  const text = await res.text();
  if (!res.ok || !text.trim()) return null;
  let json;
  try {
    json = JSON.parse(text);
  } catch {
    return null;
  }
  return parseAccountDetail(json);
}

async function fetchToken(jar, serial) {
  const body = new URLSearchParams();
  body.set('post[serialnumber]', serial);

  const res = await fetch(`${BASE_URL}/cardapi/getQrToken`, {
    method: 'POST',
    headers: baseHeaders(jar, {
      'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8',
      Origin: BASE_URL,
      Referer: `${BASE_URL}/account`,
    }),
    body,
    redirect: 'follow',
  });

  if (res.status === 401) throw new HttpError(401, 'Session expired');
  if (res.url && res.url.includes('/account/login')) {
    throw new HttpError(401, 'Session expired');
  }
  const text = await res.text();
  if (!res.ok) throw new HttpError(502, `Token HTTP ${res.status}`);

  let json;
  try {
    json = JSON.parse(text);
  } catch {
    throw new HttpError(502, 'Token response was not JSON');
  }
  const data = typeof json.data === 'string' ? json.data : '';
  const base64Field = typeof json.base64 === 'string' ? json.base64 : '';
  const raw = base64Field || data;
  if (!json.success || !raw) {
    return { success: false, base64: '' };
  }
  return { success: true, base64: raw };
}

async function fetchHistory(jar, serial, limit) {
  const url = `${BASE_URL}/cardapi/getCardHistory/${encodeURIComponent(serial)}/0/${limit}`;
  const res = await fetch(url, {
    headers: baseHeaders(jar, { Referer: `${BASE_URL}/account` }),
    redirect: 'follow',
  });
  if (res.status === 401) throw new HttpError(401, 'Session expired');
  if (res.url && res.url.includes('/account/login')) {
    throw new HttpError(401, 'Session expired');
  }
  if (!res.ok) throw new HttpError(502, `History HTTP ${res.status}`);
  const text = await res.text();
  if (!text.trim()) return [];
  if (text.trimStart().startsWith('<')) {
    throw new HttpError(401, 'Session expired');
  }
  if (process.env.DEBUG_HISTORY) {
    // Opt-in: dump the raw upstream payload so field names can be mapped.
    console.log('\n[DEBUG_HISTORY] raw response:\n' + text.slice(0, 6000) + '\n');
  }
  return parseHistory(JSON.parse(text));
}

// ---------------------------------------------------------------------------
// Parsing helpers (ported from QRDaemonService.kt / fetchCardHistory)
// ---------------------------------------------------------------------------
function readString(obj, ...keys) {
  if (!obj) return '';
  for (const key of keys) {
    const v = obj[key];
    if (typeof v === 'string' && v.trim()) return v.trim();
    if (typeof v === 'number') return String(v);
  }
  return '';
}

function readLong(obj, ...keys) {
  if (!obj) return 0;
  for (const key of keys) {
    const v = obj[key];
    if (typeof v === 'number') return Math.trunc(v);
    if (typeof v === 'string' && v.trim() && !Number.isNaN(Number(v))) {
      return Math.trunc(Number(v));
    }
  }
  return 0;
}

function readDouble(obj, ...keys) {
  if (!obj) return null;
  const centKeys = new Set(['creditLastBalance', 'credit']);
  for (const key of keys) {
    const v = obj[key];
    if (v == null) continue;
    if (typeof v === 'string') {
      const raw = v.trim();
      if (!raw) continue;
      const normalized = raw.replace(',', '.');
      const parsed = Number(normalized);
      if (Number.isNaN(parsed)) continue;
      if (centKeys.has(key) && !normalized.includes('.')) return parsed / 100.0;
      return parsed;
    }
    if (typeof v === 'number') {
      if (centKeys.has(key) && v % 1 === 0) return v / 100.0;
      return v;
    }
  }
  return null;
}

function firstCard(obj) {
  if (!obj) return null;
  if (obj.card) return obj.card;
  if (Array.isArray(obj.cards) && obj.cards.length > 0) return obj.cards[0];
  if (obj.wertyzUser) return firstCard(obj.wertyzUser);
  if (obj.user) return firstCard(obj.user);
  return null;
}

function firstTicket(card) {
  if (!card || !Array.isArray(card.tickets)) return null;
  let first = null;
  for (const t of card.tickets) {
    if (!t || typeof t !== 'object') continue;
    if (first == null) first = t;
    if (t.active === true) return t;
  }
  return first;
}

function extractTemplateBase64(templateRaw) {
  if (!templateRaw) return '';
  try {
    const cleaned = templateRaw.replace(/\\\\/g, '\\').replace(/\\"/g, '"');
    const templateJson = JSON.parse(cleaned);
    return (templateJson.base64 || '').trim();
  } catch {
    const m = /base64\\":\\"([^\\"]+)/.exec(templateRaw);
    return m ? m[1].trim() : '';
  }
}

function allCards(obj) {
  if (!obj) return [];
  if (Array.isArray(obj.cards) && obj.cards.length) return obj.cards;
  if (obj.card) return [obj.card];
  return allCards(obj.wertyzUser) .length ? allCards(obj.wertyzUser) : allCards(obj.user);
}

function parseCard(cardObj) {
  const ticketObj = firstTicket(cardObj);
  const templateRaw = readString(cardObj, 'template');
  return {
    serialNumber: readString(cardObj, 'snr', 'cardSnr', 'cardSNR', 'cardNumber', 'cardnumber', 'serialNumber', 'serialnumber'),
    cardTypeName: readString(cardObj, 'cardTypeName', 'typeName', 'cardType'),
    organizationName: readString(cardObj, 'organizationName', 'organization', 'companyName'),
    cardValidFrom: readLong(cardObj, 'validFrom', 'cardValidFrom'),
    cardValidTo: readLong(cardObj, 'validTo', 'cardValidTo'),
    ticketValidFrom: readLong(ticketObj, 'timeValidityFrom', 'validFrom'),
    ticketValidTo: readLong(ticketObj, 'timeValidityTo', 'validTo'),
    discountValidFrom: readLong(cardObj, 'discountValidFrom'),
    discountValidTo: readLong(cardObj, 'discountValidTo'),
    creditLastBalance: readDouble(cardObj, 'creditLastBalance', 'credit'),
    currencySymbol: readString(cardObj, 'currencySymbol', 'currency'),
    cardTemplateBase64: readString(cardObj, 'base64', 'cardBase64') || extractTemplateBase64(templateRaw),
  };
}

function parseAccountDetail(json) {
  const data = json.data && typeof json.data === 'object' ? json.data : json;
  const userObj = data.wertyzUser || data.user || data;
  const cardObj = firstCard(userObj) || firstCard(data);
  const ticketObj = firstTicket(cardObj);
  const cards = (allCards(userObj).length ? allCards(userObj) : allCards(data)).map(parseCard).filter((c) => c.serialNumber);

  const cardFullName = readString(cardObj, 'fullName', 'fullname', 'ownerFullName', 'name');
  const cardFirst = readString(cardObj, 'ownerFirstName', 'firstName', 'firstname', 'first_name');
  const cardLast = readString(cardObj, 'ownerLastName', 'lastName', 'lastname', 'last_name');
  const dataFullName = readString(userObj, 'fullName', 'fullname', 'name');
  const dataFirst = readString(userObj, 'firstName', 'firstname', 'first_name');
  const dataLast = readString(userObj, 'lastName', 'lastname', 'last_name');

  let userName = '';
  if (cardFullName) userName = cardFullName;
  else if (dataFullName) userName = dataFullName;
  else if (cardFirst || cardLast) userName = [cardFirst, cardLast].filter(Boolean).join(' ');
  else if (dataFirst || dataLast) userName = [dataFirst, dataLast].filter(Boolean).join(' ');

  const serialNumber =
    readString(cardObj, 'snr', 'cardSnr', 'cardSNR', 'cardNumber', 'cardnumber', 'serialNumber', 'serialnumber') ||
    readString(data, 'snr', 'cardSnr', 'cardSNR', 'cardNumber', 'cardnumber', 'serialNumber', 'serialnumber');

  const templateRaw = readString(cardObj, 'template');
  const cardTemplateBase64 =
    readString(cardObj, 'base64', 'cardBase64') || extractTemplateBase64(templateRaw);

  return {
    userName,
    serialNumber,
    cardTypeName: readString(cardObj, 'cardTypeName', 'typeName', 'cardType'),
    organizationName: readString(cardObj, 'organizationName', 'organization', 'companyName'),
    cardValidFrom: readLong(cardObj, 'validFrom', 'cardValidFrom'),
    cardValidTo: readLong(cardObj, 'validTo', 'cardValidTo'),
    ticketValidFrom: readLong(ticketObj, 'timeValidityFrom', 'validFrom'),
    ticketValidTo: readLong(ticketObj, 'timeValidityTo', 'validTo'),
    discountValidFrom: readLong(cardObj, 'discountValidFrom'),
    discountValidTo: readLong(cardObj, 'discountValidTo'),
    creditLastBalance: readDouble(cardObj, 'creditLastBalance', 'credit'),
    currencySymbol: readString(cardObj, 'currencySymbol', 'currency'),
    cardTemplateBase64,
    cards,
  };
}

const CURRENCY = '€';

function num(v) {
  if (typeof v === 'number') return v;
  if (typeof v === 'string' && v.trim() && !Number.isNaN(Number(v))) return Number(v);
  return null;
}

function eur(cents) {
  return `${(cents / 100).toFixed(2)} ${CURRENCY}`;
}

function signedEur(cents) {
  return `${cents >= 0 ? '+' : '-'}${(Math.abs(cents) / 100).toFixed(2)} ${CURRENCY}`;
}

function parseHistory(root) {
  const tickets = Array.isArray(root.tickets) ? root.tickets : [];
  const transactions = Array.isArray(root.transactions) ? root.transactions : [];
  const result = [];

  tickets.forEach((t, index) => {
    // Current qrbus API nests data under payment/tariff/place. Fall back to the
    // older flat field names for safety.
    const payment = t.payment || {};
    const balance = payment.balance || {};
    const tariff = t.tariff || {};
    const tariffType = tariff.type || {};
    const stop = (t.place && t.place.stop) || {};

    const saleTimeSec = num(payment.saleTime) ?? num(t.saleTime) ?? 0;
    const timestampMs = saleTimeSec * 1000; // real UTC epoch; the client shows it in local time

    const oldValue = num(balance.oldValue) ?? num(t.oldBalance);
    const newValue = num(balance.newValue) ?? num(t.newBalance);
    const priceCents = num(payment.price) ?? num(t.price) ?? 0;

    // Sign from the balance movement; magnitude from price. A top-up is
    // operationType 6 / tariff type 3 ("Vklad na kartu").
    let deltaCents;
    if (oldValue != null && newValue != null) {
      deltaCents = newValue - oldValue;
    } else {
      const isDeposit = t.operationType === 6 || tariffType.id === 3 || priceCents < 0;
      deltaCents = isDeposit ? Math.abs(priceCents) : -Math.abs(priceCents);
    }

    const id =
      (t.ticketSnr && String(t.ticketSnr).trim()) ||
      (t.ticketSNR && String(t.ticketSNR).trim()) ||
      `ticket-${saleTimeSec}-${index}`;

    const title =
      (tariff.name && tariff.name.trim()) ||
      (tariffType.name && tariffType.name.trim()) ||
      (t.ticketTypeName && String(t.ticketTypeName).trim()) ||
      'Ticket';

    const stopName = (stop.name && String(stop.name).trim()) || '';
    const balancePart =
      oldValue != null && newValue != null ? `${eur(oldValue)} → ${eur(newValue)}` : '';
    const subtitle = [stopName, balancePart].filter(Boolean).join(' • ');

    const isTopUp = t.operationType === 6 || tariffType.id === 3 || deltaCents > 0;
    result.push({
      id,
      sourceType: 'TICKET',
      timestampMs,
      title,
      subtitle,
      amountText: signedEur(deltaCents),
      amountCents: deltaCents,
      isTopUp,
      stopName,
    });
  });

  transactions.forEach((obj, index) => {
    const createdAt = (num(obj.createdAt) ?? 0) * 1000;
    const type = obj.transactionType || 0;
    const changes = Array.isArray(obj.changes) ? obj.changes : [];
    let subtitle = changes
      .map((c) => c.value ?? c.valueAfter ?? c.valueBefore ?? '')
      .filter((v) => v && String(v).trim())
      .join(' • ');
    if (!subtitle) subtitle = 'Transaction details';
    result.push({
      id: `transaction-${createdAt}-${index}`,
      sourceType: 'TRANSACTION',
      timestampMs: createdAt,
      title: `Transaction #${type}`,
      subtitle,
      amountText: '',
      amountCents: null,
      isTopUp: false,
      stopName: '',
    });
  });

  return result.sort((a, b) => b.timestampMs - a.timestampMs);
}

// ---------------------------------------------------------------------------
// Session store (server-side; keeps the WPIS cookie out of the browser).
// ---------------------------------------------------------------------------
class HttpError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}

const SESSION_TTL_MS = 6 * 60 * 60 * 1000; // 6 hours
const sessions = new Map(); // id -> { jar, serial, createdAt }

function createSession(jar, serial) {
  const id = crypto.randomBytes(24).toString('hex');
  sessions.set(id, { jar, serial, createdAt: Date.now() });
  return id;
}

function getSession(id) {
  const s = sessions.get(id);
  if (!s) return null;
  if (Date.now() - s.createdAt > SESSION_TTL_MS) {
    sessions.delete(id);
    return null;
  }
  return s;
}

setInterval(() => {
  const now = Date.now();
  for (const [id, s] of sessions) {
    if (now - s.createdAt > SESSION_TTL_MS) sessions.delete(id);
  }
}, 30 * 60 * 1000).unref();

// ---------------------------------------------------------------------------
// HTTP API
// ---------------------------------------------------------------------------
const app = express();
app.disable('x-powered-by');
// Bug reports carry logs and up to 3 photos; every other route keeps a small body limit.
const smallJson = express.json({ limit: '1.5mb' }), bugJson = express.json({ limit: '16mb' });
app.use((req, res, next) => (req.path === '/api/hub/bugs' ? bugJson : smallJson)(req, res, next));

// Security headers. Scripts only from this server; inline styles are used for bar heights etc.
app.use((req, res, next) => {
  res.set({
    'Content-Security-Policy': "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; " +
      "font-src https://fonts.gstatic.com; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; " +
      // Credit top-up posts the carrier's signed order form to its payment gateway (GP webpay).
      "form-action 'self' https://3dsecure.gpwebpay.com https://*.gpwebpay.com; object-src 'none'",
    'X-Content-Type-Options': 'nosniff',
    'Referrer-Policy': 'same-origin',
    'X-Frame-Options': 'DENY',
    'Strict-Transport-Security': 'max-age=31536000',
  });
  next();
});

/** Fixed-window counter per key. ponytail: in memory, resets on restart; fine for one process. */
function limiter(limit, windowMs) {
  const hits = new Map();
  return (key) => {
    const now = Date.now();
    const h = hits.get(key);
    if (!h || now - h.start > windowMs) { hits.set(key, { start: now, count: 1 }); if (hits.size > 50000) hits.clear(); return true; }
    return ++h.count <= limit;
  };
}
// Sign-ins go to sadzv with the user's password: keep guessing through this server slow.
const loginLimit = limiter(10, 15 * 60 * 1000);

function sendError(res, err) {
  const status = err instanceof HttpError || Number.isInteger(err?.status) ? err.status : 500;
  res.status(status).json({ success: false, error: err.message || 'Server error' });
}

app.post('/api/login', async (req, res) => {
  try {
    if (!loginLimit(clientIp(req))) return res.status(429).json({ success: false, error: 'Too many sign-in attempts. Try again in a few minutes.' });
    const { email, password, serial } = req.body || {};
    if (!email || !password) {
      return res.status(400).json({ success: false, error: 'Email and password are required' });
    }
    const { jar, detail } = await login(email, password);
    const serialNumber = detail?.serialNumber || (serial ? String(serial).trim() : '');
    const sessionId = createSession(jar, serialNumber);
    res.json({ success: true, sessionId, serial: serialNumber, account: detail });
  } catch (err) {
    sendError(res, err);
  }
});

app.post('/api/token', async (req, res) => {
  try {
    const { sessionId } = req.body || {};
    const session = getSession(sessionId);
    if (!session) return res.status(401).json({ success: false, error: 'Session expired' });
    if (!session.serial) {
      return res.status(400).json({ success: false, error: 'No card serial number' });
    }
    const token = await fetchToken(session.jar, session.serial);
    res.json(token);
  } catch (err) {
    if (err instanceof HttpError && err.status === 401) sessions.delete(req.body?.sessionId);
    sendError(res, err);
  }
});

app.post('/api/account', async (req, res) => {
  try {
    const { sessionId } = req.body || {};
    const session = getSession(sessionId);
    if (!session) return res.status(401).json({ success: false, error: 'Session expired' });
    const detail = await fetchAccountDetail(session.jar);
    if (detail?.serialNumber) session.serial = detail.serialNumber;
    res.json({ success: true, account: detail });
  } catch (err) {
    if (err instanceof HttpError && err.status === 401) sessions.delete(req.body?.sessionId);
    sendError(res, err);
  }
});

app.post('/api/history', async (req, res) => {
  try {
    const { sessionId, limit } = req.body || {};
    const session = getSession(sessionId);
    if (!session) return res.status(401).json({ success: false, error: 'Session expired' });
    if (!session.serial) {
      return res.status(400).json({ success: false, error: 'No card serial number' });
    }
    const items = await fetchHistory(session.jar, session.serial, Math.min(Number(limit) || 20, 100));
    res.json({ success: true, items });
  } catch (err) {
    if (err instanceof HttpError && err.status === 401) sessions.delete(req.body?.sessionId);
    sendError(res, err);
  }
});

app.post('/api/select-card', (req, res) => {
  const { sessionId, serial } = req.body || {};
  const session = getSession(sessionId);
  if (!session) return res.status(401).json({ success: false, error: 'Session expired' });
  session.serial = String(serial || '').trim();
  res.json({ success: true, serial: session.serial });
});

// ---------------------------------------------------------------------------
// Credit top-up (SAD Zvolen, "Vklad na kartu"), as the Android app: sadzv makes the order and the rider pays it
// on the carrier's own gateway (GP webpay). The money goes to the carrier, never through this server.
// ---------------------------------------------------------------------------
async function sadzvJson(jar, path, init = {}) {
  const res = await fetch(`${BASE_URL}${path}`, { ...init, headers: baseHeaders(jar, { Referer: `${BASE_URL}/account`, ...(init.headers || {}) }), redirect: 'follow' });
  if (res.status === 401 || (res.url && res.url.includes('/account/login'))) throw new HttpError(401, 'Session expired');
  const text = await res.text();
  if (!res.ok) throw new HttpError(502, `sadzv HTTP ${res.status}`);
  if (text.trimStart().startsWith('<')) throw new HttpError(401, 'Session expired');
  return JSON.parse(text);
}

app.post('/api/topup/limits', async (req, res) => {
  try {
    const session = getSession(req.body?.sessionId);
    if (!session) return res.status(401).json({ success: false, error: 'Session expired' });
    const tariffId = (await sadzvJson(session.jar, '/accountapi/getCardDepositTariff')).tariffId;
    const props = (await sadzvJson(session.jar, `/ticketapi/getTariffInformation/${encodeURIComponent(tariffId)}`)).properties || [];
    const cents = (name, d) => Number(props.find((p) => p.name === name)?.valueText) || d;
    res.json({ success: true, tariffId, minCents: cents('MinVklad', 200), maxCents: cents('MaxVklad', 5000), defaultCents: cents('DefaultVklad', 500) });
  } catch (err) {
    sendError(res, err);
  }
});

app.post('/api/topup/start', async (req, res) => {
  try {
    const session = getSession(req.body?.sessionId);
    if (!session) return res.status(401).json({ success: false, error: 'Session expired' });
    const cents = Math.round(Number(req.body?.cents));
    const tariffId = Number(req.body?.tariffId);
    if (!session.serial || !Number.isFinite(cents) || cents < 100 || !tariffId) return res.status(400).json({ success: false, error: 'Bad amount' });
    const order = [{ snr: session.serial, tariffId, zoneId: 0, validFrom: 0, amountInCents: cents, paymentTypeId: 3 }];
    const json = await sadzvJson(session.jar, '/ticketapi/buyTicket', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8', Origin: BASE_URL },
      body: new URLSearchParams({ post: JSON.stringify(order) }),
    });
    const gateway = String(json.gatewayUrl || '');
    // Only ever hand the rider's browser to the bank's gateway.
    let host = '';
    try { host = new URL(gateway).hostname; } catch { /* not a URL */ }
    if (!gateway.startsWith('https://') || !(host === 'gpwebpay.com' || host.endsWith('.gpwebpay.com')) || !Array.isArray(json.requestFields)) {
      throw new HttpError(502, 'No payment gateway');
    }
    res.json({ success: true, gatewayUrl: gateway, fields: json.requestFields.map((f) => ({ name: String(f.name), value: String(f.value) })) });
  } catch (err) {
    sendError(res, err);
  }
});

// ---------------------------------------------------------------------------
// IDS BBSK account (eshop.idsbbsk.sk, EM-0023): its static QR code, credit, tickets and history.
// ---------------------------------------------------------------------------
const idsSessions = new Map(); // id -> { jar, name, createdAt }

function idsSession(id) {
  const s = idsSessions.get(id);
  if (!s || Date.now() - s.createdAt > SESSION_TTL_MS) { idsSessions.delete(id); return null; }
  return s;
}

app.post('/api/ids/login', async (req, res) => {
  try {
    if (!loginLimit(clientIp(req))) return res.status(429).json({ success: false, error: 'Too many sign-in attempts. Try again in a few minutes.' });
    const { email, password } = req.body || {};
    if (!email || !password) return res.status(400).json({ success: false, error: 'Email and password are required' });
    const jar = await idsLogin(String(email), String(password));
    const account = await idsAccount(jar);
    const id = crypto.randomBytes(24).toString('hex');
    idsSessions.set(id, { jar, name: account.name, createdAt: Date.now() });
    res.json({ success: true, sessionId: id, account, history: idsHistory(account) });
  } catch (err) {
    sendError(res, err);
  }
});

app.post('/api/ids/account', async (req, res) => {
  try {
    const session = idsSession(req.body?.sessionId);
    if (!session) return res.status(401).json({ success: false, error: 'Session expired' });
    const account = await idsAccount(session.jar, session.name);
    res.json({ success: true, account, history: idsHistory(account) });
  } catch (err) {
    if (err.status === 401) idsSessions.delete(req.body?.sessionId);
    sendError(res, err);
  }
});

app.post('/api/ids/qr', async (req, res) => {
  try {
    const session = idsSession(req.body?.sessionId);
    if (!session) return res.status(401).json({ success: false, error: 'Session expired' });
    res.json({ success: true, image: await idsQr(session.jar, String(req.body?.qrId || '')) });
  } catch (err) {
    sendError(res, err);
  }
});

app.post('/api/ids/logout', (req, res) => {
  idsSessions.delete(req.body?.sessionId);
  res.json({ success: true });
});

// ---------------------------------------------------------------------------
// Public transit (no sign-in): live departures, trips, planner (emhub timetables).
// ---------------------------------------------------------------------------
const wrap = (fn) => async (req, res) => {
  try {
    res.json(await fn(req));
  } catch (err) {
    res.status(err.status || 502).json({ success: false, error: err.message || 'Upstream error' });
  }
};
const asTripRef = (b) => ({
  line: String(b.line || ''), lineId: Number(b.lineId) || 0, routeNumber: String(b.routeNumber || ''),
  tripNumber: Number(b.tripNumber) || 0, destination: String(b.destination || ''),
  scheduleUrl: String(b.scheduleUrl || ''), serviceDate: String(b.serviceDate || ''),
  fromStopId: Number(b.fromStopId) || 0, plannedSecondOfDay: Number.isFinite(Number(b.plannedSecondOfDay)) ? Number(b.plannedSecondOfDay) : -1,
});

app.get('/api/live/stops', wrap(() => transit.getStops()));
app.post('/api/live/departures', wrap(async (req) => {
  const ids = (req.body?.platformIds || []).map(Number).filter(Number.isFinite).slice(0, 20);
  if (!ids.length) throw Object.assign(new Error('platformIds required'), { status: 400 });
  // With the stop, other carriers' buses from the timetable join the board.
  const stopId = Number(req.body?.stopId);
  const stop = stopId ? (await transit.getStops()).find((x) => x.id === stopId) : null;
  return transit.getBoard(ids, stop);
}));
app.post('/api/live/trip', wrap(async (req) => {
  const ref = asTripRef(req.body || {});
  const trip = await (ref.scheduleUrl ? transit.getScheduledTrip(ref) : transit.getLiveTrip(ref));
  // Automatic mode places the rider's phone on the route: it needs stop coordinates.
  return req.body?.withCoordinates ? transit.withCoordinates(trip, !ref.scheduleUrl && !trip.fromTimetable) : trip;
}));
app.get('/api/live/match', wrap((req) => transit.matchStopByName(String(req.query.name || ''))));
app.get('/api/cp/suggest', wrap((req) => transit.suggestStops(String(req.query.city || ''), String(req.query.q || '').slice(0, 80))));
app.post('/api/cp/search', wrap((req) => transit.searchConnections(req.body || {})));
app.post('/api/cp/more', wrap((req) => transit.moreConnections(req.body?.cursor)));

// ---------------------------------------------------------------------------
// Follow a bus: web push alerts (arriving, get off, arrived) while the app is closed.
// ---------------------------------------------------------------------------
app.post('/api/follow', wrap((req) => {
  const b = req.body || {};
  if (!b.subscription?.endpoint) throw Object.assign(new Error('Push subscription required'), { status: 400 });
  return {
    id: startFollowing({
      subscription: b.subscription,
      ref: asTripRef(b.ref || {}),
      boardingPlatformIds: (b.boardingPlatformIds || []).map(Number),
      alightOrder: b.alightOrder == null ? null : Number(b.alightOrder),
      lang: b.lang === 'en' ? 'en' : 'sk',
      arriveLeadMinutes: Math.min(60, Math.max(1, Math.round(Number(b.arriveLeadMinutes) || 2))),
      community: !!b.community,
    }, hubRequest),
  };
}));
app.post('/api/unfollow', wrap((req) => ({ ok: stopFollowing(String(req.body?.id || '')) })));

// Regular rides: the server keeps them with the phone's push subscription, finds that day's
// bus before each departure, starts the alerts for it and tells the phone.
app.post('/api/rides', wrap((req) => {
  const b = req.body || {};
  if (!b.subscription?.endpoint) throw Object.assign(new Error('Push subscription required'), { status: 400 });
  return syncRides({
    subscription: b.subscription,
    rides: Array.isArray(b.rides) ? b.rides.slice(0, 30) : [],
    lang: b.lang === 'en' ? 'en' : 'sk',
    arriveLeadMinutes: Math.min(60, Math.max(1, Math.round(Number(b.arriveLeadMinutes) || 2))),
    community: !!b.community,
  }, hubRequest);
}));

// ---------------------------------------------------------------------------
// emhub proxy: bug reports and community delays.
// ---------------------------------------------------------------------------
async function hubRequest(route, { method = 'GET', body, clientIp, timeout = 15000 } = {}) {
  if (!HUB_URL || !HUB_APP_KEY) throw Object.assign(new Error('Bug reports and community delays are not set up on this server'), { status: 503 });
  const res = await fetch(`${HUB_URL}/api/v1/${route}`, {
    method,
    headers: { 'X-App-Key': HUB_APP_KEY, 'Content-Type': 'application/json', ...(clientIp ? { 'X-Forwarded-For': clientIp } : {}) },
    body: body ? JSON.stringify(body) : undefined,
    signal: AbortSignal.timeout(timeout),
  });
  const json = await res.json().catch(() => ({}));
  if (!res.ok) throw Object.assign(new Error(json.error || `emhub HTTP ${res.status}`), { status: res.status });
  return json;
}
// cloudflared adds CF-Connecting-IP; trust it only when the request comes from the tunnel on this host/LAN.
const clientIp = (req) => {
  const direct = (req.socket.remoteAddress || '').replace(/^::ffff:/, '');
  const trusted = /^(127\.|10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.|::1$)/.test(direct);
  return (trusted && req.get('CF-Connecting-IP')) || direct;
};

app.post('/api/hub/bugs', wrap((req) =>
  hubRequest('bugs', { method: 'POST', body: { ...req.body, platform: 'web' }, clientIp: clientIp(req), timeout: 60000 })));
app.post('/api/hub/report', wrap((req) =>
  hubRequest('community/reports', { method: 'POST', body: { ...req.body, platform: 'web' }, clientIp: clientIp(req) })));
app.get('/api/hub/delay', wrap((req) =>
  hubRequest('community/delay?trip=' + encodeURIComponent(String(req.query.trip || '')), { clientIp: clientIp(req) })));

app.get('/api/config', (req, res) => {
  res.json({ hub: !!(HUB_URL && HUB_APP_KEY), vapidPublicKey: vapidPublicKey() });
});

app.post('/api/logout', (req, res) => {
  const { sessionId } = req.body || {};
  if (sessionId) sessions.delete(sessionId);
  res.json({ success: true });
});

// Static PWA assets.
app.use(
  express.static(path.join(__dirname, 'public'), {
    setHeaders(res, filePath) {
      // The service worker must always be revalidated so updates ship.
      if (filePath.endsWith('sw.js')) res.setHeader('Cache-Control', 'no-cache');
    },
  })
);

app.listen(PORT, () => {
  console.log(`Plan&Ride web (testEM) listening on http://localhost:${PORT}`);
  console.log(`Proxying to ${BASE_URL}`);
});
