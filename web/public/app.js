'use strict';

// ---------------------------------------------------------------------------
// Config & state
// ---------------------------------------------------------------------------
const POLL_INTERVAL_MS = 25000; // qrbus rotates tokens roughly every 25s
const RETRY_MS = 3000;
const LS_KEY = 'testem.creds';
const LS_THRESHOLD = 'testem.threshold';

const state = {
  sessionId: null,
  serial: '',
  account: null,
  lastBase64: null,
  pollTimer: null,
  polling: false,
  reauthInFlight: false,
  wakeLock: null,
};

// ---------------------------------------------------------------------------
// DOM
// ---------------------------------------------------------------------------
const $ = (id) => document.getElementById(id);
const loginView = $('login-view');
const appView = $('app-view');
const loginForm = $('login-form');
const loginBtn = $('login-btn');
const loginError = $('login-error');
const qrEl = $('qr');
const qrPlaceholder = $('qr-placeholder');
const statusEl = $('status');
const refreshFill = $('refresh-fill');

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------
function loadCreds() {
  try {
    return JSON.parse(localStorage.getItem(LS_KEY) || 'null');
  } catch {
    return null;
  }
}
function saveCreds(creds) {
  localStorage.setItem(LS_KEY, JSON.stringify(creds));
}
function clearCreds() {
  localStorage.removeItem(LS_KEY);
}
function getThreshold() {
  const v = parseFloat(localStorage.getItem(LS_THRESHOLD));
  return Number.isFinite(v) && v >= 0 ? v : 1.0;
}

async function api(path, body) {
  const res = await fetch(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body || {}),
  });
  let json = null;
  try {
    json = await res.json();
  } catch {
    /* ignore */
  }
  return { status: res.status, ok: res.ok, json: json || {} };
}

function setStatus(text, isError) {
  statusEl.textContent = text;
  statusEl.classList.toggle('err', !!isError);
}

function fmtDate(value) {
  if (!value) return '—';
  // Account-detail timestamps may be seconds or milliseconds.
  const ms = value < 1e12 ? value * 1000 : value;
  const d = new Date(ms);
  if (Number.isNaN(d.getTime())) return '—';
  return d.toLocaleDateString(undefined, { day: '2-digit', month: '2-digit', year: 'numeric' });
}

function fmtRange(from, to) {
  if (!from && !to) return '—';
  return `${fmtDate(from)} – ${fmtDate(to)}`;
}

// ---------------------------------------------------------------------------
// QR rendering
// ---------------------------------------------------------------------------
function renderQR(text) {
  // typeNumber 0 = auto-size; error-correction 'H' matches the Android client.
  const qr = qrcode(0, 'H');
  qr.addData(text);
  qr.make();
  qrEl.innerHTML = qr.createSvgTag({ scalable: true, margin: 0 });
  qrPlaceholder.hidden = true;
}

function resetRefreshBar() {
  refreshFill.style.transition = 'none';
  refreshFill.style.transform = 'scaleX(1)';
  // Force reflow so the next transition runs from full width.
  void refreshFill.offsetWidth;
  refreshFill.style.transition = `transform ${POLL_INTERVAL_MS}ms linear`;
  refreshFill.style.transform = 'scaleX(0)';
}

// ---------------------------------------------------------------------------
// Account rendering
// ---------------------------------------------------------------------------
function renderAccount(account) {
  if (!account) return;
  state.account = account;

  $('user-name').textContent = account.userName || 'testEM';
  $('card-type').textContent = account.cardTypeName || '';

  if (account.creditLastBalance != null) {
    const cur = account.currencySymbol || '';
    $('balance').textContent = `${account.creditLastBalance.toFixed(2)}${cur ? ' ' + cur : ''}`;
  } else {
    $('balance').textContent = '—';
  }

  $('organization').textContent = account.organizationName || account.cardTypeName || '—';
  $('ticket-validity').textContent = fmtRange(account.ticketValidFrom, account.ticketValidTo);
  $('card-validity').textContent = fmtRange(account.cardValidFrom, account.cardValidTo);

  // Low-credit banner
  const banner = $('low-credit-banner');
  const threshold = getThreshold();
  if (account.creditLastBalance != null && account.creditLastBalance < threshold) {
    const cur = account.currencySymbol || '';
    $('low-credit-text').textContent =
      `Low credit: ${account.creditLastBalance.toFixed(2)}${cur ? ' ' + cur : ''} remaining`;
    banner.hidden = false;
  } else {
    banner.hidden = true;
  }
}

// ---------------------------------------------------------------------------
// History
// ---------------------------------------------------------------------------
async function loadHistory() {
  const list = $('history-list');
  list.innerHTML = '<p class="muted history-empty"><span class="spinner"></span> Loading…</p>';
  const { ok, status, json } = await api('/api/history', { sessionId: state.sessionId, limit: 30 });

  if (status === 401) {
    if (await reauthenticate()) return loadHistory();
    list.innerHTML = '<p class="muted history-empty">Session expired.</p>';
    return;
  }
  if (!ok || !json.success) {
    list.innerHTML = `<p class="muted history-empty">${json.error || 'Failed to load history.'}</p>`;
    return;
  }
  const items = json.items || [];
  if (!items.length) {
    list.innerHTML = '<p class="muted history-empty">No history yet.</p>';
    return;
  }
  list.innerHTML = '';
  for (const it of items) {
    const row = document.createElement('div');
    row.className = 'history-item';
    const amt = (it.amountText || '').trim();
    const amtClass = amt.startsWith('+') ? 'pos' : amt.startsWith('-') ? 'neg' : 'neutral';

    const add = (cls, text) => {
      const d = document.createElement('div');
      d.className = cls;
      d.textContent = text;
      row.appendChild(d);
    };

    // Matches the Android layout: date/time, title, subtitle, amount (stacked).
    const when = fmtDateTime(it.timestampMs);
    if (when) add('h-time', when);
    add('h-title', it.title);
    if (it.subtitle) add('h-sub', it.subtitle);
    if (amt) add('h-amount ' + amtClass, amt);

    list.appendChild(row);
  }
}

function pad2(n) {
  return String(n).padStart(2, '0');
}

function fmtDateTime(ms) {
  if (!ms) return '';
  const d = new Date(ms);
  if (Number.isNaN(d.getTime())) return '';
  // The API stores local wall-clock encoded as a unix timestamp. Read it back
  // with UTC getters so the displayed time is identical on any device timezone
  // (this reproduces what the Android app shows).
  return (
    `${d.getUTCFullYear()}-${pad2(d.getUTCMonth() + 1)}-${pad2(d.getUTCDate())} ` +
    `${pad2(d.getUTCHours())}:${pad2(d.getUTCMinutes())}:${pad2(d.getUTCSeconds())}`
  );
}

// ---------------------------------------------------------------------------
// Token polling
// ---------------------------------------------------------------------------
async function pollOnce() {
  if (!state.sessionId) return;
  const { status, ok, json } = await api('/api/token', { sessionId: state.sessionId });

  if (status === 401) {
    setStatus('Session expired — reconnecting…');
    if (await reauthenticate()) {
      scheduleNext(500);
    } else {
      setStatus('Could not reconnect. Please log in again.', true);
      handleAuthFailure();
    }
    return;
  }

  if (ok && json.success && json.base64) {
    if (json.base64 !== state.lastBase64) {
      state.lastBase64 = json.base64;
      renderQR(json.base64);
    }
    resetRefreshBar();
    setStatus('Ticket active');
    scheduleNext(POLL_INTERVAL_MS);
  } else {
    setStatus(json.error || 'No ticket available — retrying…', !json.success);
    scheduleNext(RETRY_MS);
  }
}

function scheduleNext(delay) {
  clearTimeout(state.pollTimer);
  if (!state.polling) return;
  state.pollTimer = setTimeout(pollOnce, delay);
}

function startPolling() {
  if (state.polling) return;
  state.polling = true;
  pollOnce();
}

function stopPolling() {
  state.polling = false;
  clearTimeout(state.pollTimer);
}

// ---------------------------------------------------------------------------
// Auth
// ---------------------------------------------------------------------------
async function reauthenticate() {
  if (state.reauthInFlight) return false;
  const creds = loadCreds();
  if (!creds) return false;
  state.reauthInFlight = true;
  try {
    const { ok, json } = await api('/api/login', creds);
    if (ok && json.success) {
      state.sessionId = json.sessionId;
      state.serial = json.serial || state.serial;
      if (json.account) renderAccount(json.account);
      return true;
    }
    return false;
  } catch {
    return false;
  } finally {
    state.reauthInFlight = false;
  }
}

function handleAuthFailure() {
  stopPolling();
  state.sessionId = null;
  showLogin();
}

async function doLogin(creds, { fromForm } = {}) {
  const { ok, json, status } = await api('/api/login', creds);
  if (!ok || !json.success) {
    const msg = json.error || `Login failed (${status})`;
    if (fromForm) {
      loginError.textContent = msg;
      loginError.hidden = false;
    }
    return false;
  }
  state.sessionId = json.sessionId;
  state.serial = json.serial || '';
  saveCreds({ email: creds.email, password: creds.password, serial: state.serial });
  if (json.account) renderAccount(json.account);
  showApp();
  startPolling();
  return true;
}

// ---------------------------------------------------------------------------
// View switching
// ---------------------------------------------------------------------------
function showApp() {
  loginView.hidden = true;
  appView.hidden = false;
  requestWakeLock();
}

function showLogin() {
  releaseWakeLock();
  appView.hidden = true;
  loginView.hidden = false;
  loginError.hidden = true;
  // Pre-fill saved email for convenience.
  const creds = loadCreds();
  if (creds?.email) $('email').value = creds.email;
}

// ---------------------------------------------------------------------------
// Wake lock (keep screen on while showing the ticket)
// ---------------------------------------------------------------------------
async function requestWakeLock() {
  if (!('wakeLock' in navigator)) return;
  try {
    state.wakeLock = await navigator.wakeLock.request('screen');
  } catch {
    /* user agent may reject; ignore */
  }
}
function releaseWakeLock() {
  try {
    state.wakeLock?.release();
  } catch {
    /* ignore */
  }
  state.wakeLock = null;
}

// ---------------------------------------------------------------------------
// Events
// ---------------------------------------------------------------------------
loginForm.addEventListener('submit', async (e) => {
  e.preventDefault();
  loginError.hidden = true;
  const creds = {
    email: $('email').value.trim(),
    password: $('password').value,
  };
  if (!creds.email || !creds.password) return;
  loginBtn.disabled = true;
  loginBtn.textContent = 'Logging in…';
  await doLogin(creds, { fromForm: true });
  loginBtn.disabled = false;
  loginBtn.textContent = 'Log in';
});

$('history-refresh').addEventListener('click', loadHistory);

$('logout-btn').addEventListener('click', async () => {
  if (state.sessionId) api('/api/logout', { sessionId: state.sessionId });
  stopPolling();
  clearCreds();
  state.sessionId = null;
  state.lastBase64 = null;
  qrEl.innerHTML = '';
  qrPlaceholder.hidden = false;
  $('password').value = '';
  showLogin();
});

// Settings sheet
const sheetBackdrop = $('sheet-backdrop');
$('menu-btn').addEventListener('click', () => {
  $('threshold-input').value = getThreshold().toFixed(2);
  $('session-info').textContent = state.serial ? `Card: ${state.serial}` : 'No card serial';
  sheetBackdrop.hidden = false;
});
$('sheet-close').addEventListener('click', () => (sheetBackdrop.hidden = true));
sheetBackdrop.addEventListener('click', (e) => {
  if (e.target === sheetBackdrop) sheetBackdrop.hidden = true;
});
$('threshold-input').addEventListener('change', (e) => {
  const v = parseFloat(e.target.value);
  if (Number.isFinite(v) && v >= 0) {
    localStorage.setItem(LS_THRESHOLD, String(v));
    renderAccount(state.account);
  }
});
$('reload-app').addEventListener('click', async () => {
  if ('serviceWorker' in navigator) {
    const regs = await navigator.serviceWorker.getRegistrations();
    await Promise.all(regs.map((r) => r.update()));
  }
  location.reload();
});

// Pause polling when hidden; resume + refresh immediately when visible.
document.addEventListener('visibilitychange', () => {
  if (document.hidden) {
    clearTimeout(state.pollTimer);
  } else if (state.polling && state.sessionId) {
    requestWakeLock();
    pollOnce();
  }
});

// ---------------------------------------------------------------------------
// Install gate: on phones, require the app to be launched from the Home Screen
// ---------------------------------------------------------------------------
function isStandalone() {
  return (
    window.matchMedia('(display-mode: standalone)').matches ||
    window.matchMedia('(display-mode: fullscreen)').matches ||
    window.matchMedia('(display-mode: minimal-ui)').matches ||
    window.navigator.standalone === true // iOS Safari
  );
}

function deviceInfo() {
  const ua = navigator.userAgent || '';
  const isIOS =
    /iphone|ipad|ipod/i.test(ua) ||
    // iPadOS 13+ reports as desktop Safari but has touch points.
    (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);
  const isAndroid = /android/i.test(ua);
  return { isIOS, isAndroid, isMobile: isIOS || isAndroid };
}

let deferredInstallPrompt = null;
window.addEventListener('beforeinstallprompt', (e) => {
  // Chrome/Android: capture so we can offer a one-tap install button.
  e.preventDefault();
  deferredInstallPrompt = e;
  const btn = $('android-install-btn');
  if (btn) btn.hidden = false;
});

window.addEventListener('appinstalled', () => {
  deferredInstallPrompt = null;
  const manual = $('android-manual');
  if (manual) manual.hidden = true;
});

const androidInstallBtn = $('android-install-btn');
if (androidInstallBtn) {
  androidInstallBtn.addEventListener('click', async () => {
    if (!deferredInstallPrompt) return;
    deferredInstallPrompt.prompt();
    try {
      await deferredInstallPrompt.userChoice;
    } catch {
      /* ignore */
    }
    deferredInstallPrompt = null;
    androidInstallBtn.hidden = true;
  });
}

// Returns true if the user is blocked (gate shown), false if allowed through.
function enforceInstallGate() {
  if (isStandalone()) return false;
  const { isIOS, isAndroid, isMobile } = deviceInfo();
  if (!isMobile) return false; // desktop browsers may use it directly

  loginView.hidden = true;
  appView.hidden = true;
  $('ios-instructions').hidden = !isIOS;
  $('android-instructions').hidden = !(isAndroid || (!isIOS && isMobile));
  $('install-gate').hidden = false;
  return true;
}

// ---------------------------------------------------------------------------
// Service worker
// ---------------------------------------------------------------------------
if ('serviceWorker' in navigator) {
  window.addEventListener('load', () => {
    navigator.serviceWorker.register('/sw.js').catch(() => {});
  });
}

// ---------------------------------------------------------------------------
// Boot
// ---------------------------------------------------------------------------
(async function boot() {
  // On phones, block everything until the app is installed to the Home Screen.
  if (enforceInstallGate()) return;

  const creds = loadCreds();
  if (creds?.email && creds?.password) {
    showApp();
    setStatus('Connecting…');
    const ok = await doLogin(creds);
    if (!ok) {
      setStatus('Login failed — check credentials', true);
      showLogin();
    }
  } else {
    showLogin();
  }
})();
