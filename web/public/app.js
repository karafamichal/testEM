// Plan&Ride web version (testEM internally): entry point. Home, account, history, settings,
// PIN lock, bug reports and suggestions. The account is optional, like in the Android app.
import { t, lang, setLang, applyI18n } from './js/i18n.js';
import { $, $$, esc, api, apiJson, store, config, loadConfig, clock, dateText, money, toMs, toast, openPage, closeOverlay, switchRow, collectLogs, log, plate, countdown, VERSION } from './js/core.js';
import { historyInsights, historyToCsv, nextRideDeparture, pickRideConnection, readableStop, lineNumber, todayLocal } from './js/logic.js';
import * as live from './js/live.js';
import { renderPlanner, searchAt, savedJourneys, journeyTimes, removeJourney } from './js/planner.js';
import { rides, rideDayText, resyncRides } from './js/rides.js';

const POLL_MS = 25000;
const RETRY_MS = 3000;

const s = {
  creds: null, // in memory only once decrypted
  sessionId: null, serial: '', account: null,
  lastBase64: null, lastTokenAt: 0, pollTimer: null, polling: false, reauthInFlight: false,
  history: null, historyError: false, tab: 'ticket', wakeLock: null, hiddenAt: 0, pin: null,
};

// ------------------------------------------------------------------ theme

const PRESETS = [
  { id: 'stop', name: 'Zastávka', color: '#1D4FB8' },
  { id: 'ocean', name: 'Ocean', color: '#136F63' },
  { id: 'sunset', name: 'Sunset', color: '#E85D04' },
];
function applyTheme() {
  const preset = store.get('themeId', 'stop');
  const color = preset === 'custom' ? store.get('themeColor', '#1D4FB8') : (PRESETS.find((x) => x.id === preset) || PRESETS[0]).color;
  const root = document.documentElement;
  root.style.setProperty('--accent', color);
  const mode = store.get('themeMode', 'auto');
  if (mode === 'auto') delete root.dataset.theme; else root.dataset.theme = mode;
  root.dataset.amoled = store.get('amoled', false) ? '1' : '';
  $('meta[name="theme-color"]').content = getComputedStyle(root).getPropertyValue('--bg').trim() || '#0F1A30';
}

// ------------------------------------------------------------------ credentials & PIN

const enc = new TextEncoder(), dec = new TextDecoder();
const b64 = (buf) => btoa(String.fromCharCode(...new Uint8Array(buf)));
const unb64 = (str) => Uint8Array.from(atob(str), (c) => c.charCodeAt(0));
async function pinKey(pin, salt) {
  const base = await crypto.subtle.importKey('raw', enc.encode(pin), 'PBKDF2', false, ['deriveKey']);
  return crypto.subtle.deriveKey({ name: 'PBKDF2', salt, iterations: 250000, hash: 'SHA-256' }, base, { name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt']);
}
async function encryptCreds(creds, pin) {
  const salt = crypto.getRandomValues(new Uint8Array(16)), iv = crypto.getRandomValues(new Uint8Array(12));
  const data = await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, await pinKey(pin, salt), enc.encode(JSON.stringify(creds)));
  store.set('credsEnc', { salt: b64(salt), iv: b64(iv), data: b64(data) });
  store.set('creds', null);
}
async function decryptCreds(pin) {
  const e = store.get('credsEnc');
  try {
    const plain = await crypto.subtle.decrypt({ name: 'AES-GCM', iv: unb64(e.iv) }, await pinKey(pin, unb64(e.salt)), unb64(e.data));
    return JSON.parse(dec.decode(plain));
  } catch { return null; }
}
const hasPin = () => !!store.get('credsEnc');
/** An account is saved on this device (older versions could keep it without a PIN). */
const hasAccount = () => hasPin() || !!store.get('creds')?.password;
/** The ticket and account may be shown: the password is decrypted in memory. */
const unlocked = () => hasAccount() && !!s.creds;
/** The password is only ever stored encrypted with the PIN. */
async function saveCreds(creds) {
  s.creds = creds;
  if (s.pin) await encryptCreds(creds, s.pin);
}

// ------------------------------------------------------------------ views

function show(id) {
  for (const v of ['install-gate', 'pin-view', 'login-view', 'app-view']) $('#' + v).hidden = v !== id;
}

function isStandalone() {
  return matchMedia('(display-mode: standalone)').matches || matchMedia('(display-mode: fullscreen)').matches || navigator.standalone === true;
}
function enforceInstallGate() {
  if (isStandalone()) return false;
  const ua = navigator.userAgent;
  const ios = /iphone|ipad|ipod/i.test(ua) || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);
  const android = /android/i.test(ua);
  if (!ios && !android) return false; // desktop may use it in the browser
  $('#ios-steps').hidden = !ios;
  $('#android-steps').hidden = ios;
  show('install-gate');
  return true;
}
let installPrompt = null;
addEventListener('beforeinstallprompt', (e) => { e.preventDefault(); installPrompt = e; $('#install-btn').hidden = false; });

// ---- PIN: set up after adding an account; entered in a dialog to show the ticket

let lockTries = 0, lockUntil = 0;

/** Right after an account is added: its password is saved only encrypted with a PIN. */
function showPinSetup() {
  stopPolling();
  show('pin-view');
  applyI18n($('#pin-view'));
  const form = $('#pin-form');
  form.reset();
  $('#pin-error').hidden = true;
  setTimeout(() => form.pin.focus(), 50);
}
$('#pin-form').onsubmit = async (e) => {
  e.preventDefault();
  const f = e.target, err = $('#pin-error');
  err.hidden = false;
  if (!/^\d{4,8}$/.test(f.pin.value)) return (err.textContent = t('pin_short'));
  if (f.pin.value !== f.confirm.value) return (err.textContent = t('pin_mismatch'));
  s.pin = f.pin.value;
  await encryptCreds(s.creds, s.pin);
  store.set('creds', null);
  showApp();
  if (s.sessionId) startPolling(); else signIn(s.creds);
};
$('#pin-cancel').onclick = () => removeAccount();

/** PIN (the dialog) -> the password is decrypted -> signed in -> the ticket shows. */
function unlockDialog() {
  if (!hasPin() || unlocked() || $('#unlock')) return;
  const d = document.createElement('div');
  d.id = 'unlock';
  d.className = 'dialog-backdrop';
  d.innerHTML = `<form class="dialog stack" role="dialog" aria-modal="true" aria-labelledby="unlock-title">
    <h2 id="unlock-title">${t('lock_title')}</h2>
    <p class="hint small">${t('unlock_hint')}</p>
    <input name="pin" class="pin-input" type="password" inputmode="numeric" pattern="[0-9]*" maxlength="8" autocomplete="off" aria-labelledby="unlock-title" required>
    <p class="error-text" role="alert" hidden></p>
    <div class="dialog-actions"><button type="button" class="text-btn" data-forgot>${t('lock_forgot')}</button>
      <button type="button" class="text-btn" data-cancel>${t('cancel')}</button><button class="btn" type="submit">${t('unlock')}</button></div></form>`;
  document.body.appendChild(d);
  const input = $('input', d), err = $('.error-text', d);
  setTimeout(() => input.focus(), 50);
  d.onclick = (e) => {
    if (e.target === d || e.target.closest('[data-cancel]')) d.remove();
    if (e.target.closest('[data-forgot]') && confirm(t('remove_confirm'))) { d.remove(); removeAccount(); }
  };
  const submit = $('button[type=submit]', d);
  d.onsubmit = async (e) => {
    e.preventDefault();
    if (submit.disabled) return; // the PIN is still being checked
    if (Date.now() < lockUntil) { err.textContent = t('lock_wait', Math.ceil((lockUntil - Date.now()) / 1000)); err.hidden = false; return; }
    submit.disabled = true;
    const creds = await decryptCreds(input.value);
    submit.disabled = false;
    if (!creds) {
      lockTries++;
      // After 5 wrong PINs: 30 s, doubling each time.
      if (lockTries >= 5) { lockUntil = Date.now() + 30000 * 2 ** Math.min(lockTries - 5, 7); err.textContent = t('lock_wait', Math.ceil((lockUntil - Date.now()) / 1000)); }
      else err.textContent = t('lock_wrong', 5 - lockTries);
      err.hidden = false;
      input.value = '';
      return;
    }
    lockTries = 0;
    s.creds = creds;
    s.pin = input.value;
    d.remove();
    // Show the ticket at once ("Connecting…"); signing in to sadzv takes a few seconds and
    // until now the screen still looked locked, so people entered the PIN again and again.
    selectTab(s.tab);
    if (s.sessionId) startPolling(); else signIn(creds);
  };
}

/** Hides the ticket and account again; the rest of the app stays usable. */
function lockNow() {
  if (!hasPin()) return;
  stopPolling();
  s.creds = null;
  s.pin = null;
  s.lastBase64 = null;
  closeOverlay('page');
  if (!$('#app-view').hidden) selectTab(s.tab);
}

// ---- login

$('#login-form').onsubmit = async (e) => {
  e.preventDefault();
  const btn = $('#login-btn');
  const creds = { email: $('#email').value.trim(), password: $('#password').value };
  if (!creds.email || !creds.password) return;
  btn.disabled = true;
  btn.textContent = t('logging_in');
  $('#login-error').hidden = true;
  const ok = await signIn(creds, { fromForm: true });
  btn.disabled = false;
  btn.textContent = t('login');
  if (ok) $('#password').value = '';
};
// Welcome: continue without an account. From Settings: cancel.
$('#browse-btn').onclick = () => { store.set('onboarded', true); showApp(); };

let signingIn = null; // one sign-in at a time; a second call waits for the first
function signIn(creds, opts = {}) {
  signingIn ??= doSignIn(creds, opts).finally(() => { signingIn = null; });
  return signingIn;
}

async function doSignIn(creds, { fromForm } = {}) {
  const r = await api('/api/login', { ...creds, serial: store.get('serial', '') });
  if (!r.ok || !r.json.success) {
    if (fromForm) {
      $('#login-error').textContent = r.json.error === 'offline' ? t('ticket_offline_no_code') : r.json.error || t('login_failed', r.status);
      $('#login-error').hidden = false;
    } else if (r.status === 401) {
      updateStatus(t('ticket_signed_out'), true);
    } else if (hasPin()) {
      // No network or sadzv busy: say so, and try again shortly while the ticket is shown.
      updateStatus(r.status === 0 ? t('ticket_offline_no_code') : t('login_failed', r.status), true);
      setTimeout(() => { if (unlocked() && !s.sessionId) signIn(s.creds); }, 10000);
    }
    return false;
  }
  store.set('onboarded', true);
  s.sessionId = r.json.sessionId;
  if (!hasPin()) {
    // A new account: the password is saved only once a PIN encrypts it.
    s.creds = creds;
    setAccount(r.json.account);
    s.serial = r.json.serial || '';
    showPinSetup();
    return true;
  }
  // A new password from the form is encrypted again; after an unlock it is unchanged.
  if (fromForm) await saveCreds(creds); else s.creds = creds;
  setAccount(r.json.account);
  // Keep the card the user picked last time.
  const preferred = store.get('serial', '');
  s.serial = s.account?.cards?.some((c) => c.serialNumber === preferred) ? preferred : r.json.serial || '';
  if (s.serial !== r.json.serial) await api('/api/select-card', { sessionId: s.sessionId, serial: s.serial });
  showApp();
  startPolling();
  return true;
}

async function reauth() {
  if (s.reauthInFlight || !s.creds) return false;
  s.reauthInFlight = true;
  try {
    const r = await api('/api/login', { ...s.creds, serial: s.serial });
    if (!r.ok || !r.json.success) return false;
    s.sessionId = r.json.sessionId;
    setAccount(r.json.account);
    if (s.serial && s.serial !== r.json.serial) await api('/api/select-card', { sessionId: s.sessionId, serial: s.serial });
    return true;
  } finally { s.reauthInFlight = false; }
}

/** welcome: the first-run screen (the account is optional); otherwise adding one from Settings. */
function showLogin({ welcome = false } = {}) {
  show('login-view');
  applyI18n($('#login-view'));
  $('#login-title').textContent = welcome ? t('welcome_title') : t('login');
  $('#login-sub').textContent = welcome ? t('welcome_body') : t('login_sub');
  $('#browse-btn').textContent = welcome ? t('welcome_skip') : t('cancel');
  $('#welcome-hint').hidden = !welcome;
  $('#email').value = s.creds?.email || '';
  $('#login-error').hidden = true;
}

/** Removes the account from this device: password, PIN, card; the app keeps working without it. */
function removeAccount() {
  if (s.sessionId) api('/api/logout', { sessionId: s.sessionId });
  stopPolling();
  Object.assign(s, { creds: null, pin: null, sessionId: null, account: null, lastBase64: null, history: null, serial: '' });
  store.set('creds', null);
  store.set('credsEnc', null);
  store.set('serial', null);
  store.set('onboarded', true);
  closeOverlay('page');
  $('#unlock')?.remove();
  s.tab = 'ticket';
  showApp();
}

// ------------------------------------------------------------------ app shell & tabs

const TABS = ['ticket', 'departures', 'planner', 'history', 'account'];
function showApp() {
  show('app-view');
  // History exists only with an account; the last tab is Account or just Settings.
  const allowed = hasAccount() ? TABS : TABS.filter((x) => x !== 'history');
  $$('#tabbar [data-tab]').forEach((b) => (b.hidden = !allowed.includes(b.dataset.tab)));
  if (!allowed.includes(s.tab)) s.tab = 'ticket';
  $('#tabbar').classList.toggle('no-account', !hasAccount());
  applyI18n($('#tabbar'));
  $('[data-tab="account"] span', $('#tabbar')).textContent = t(hasAccount() ? 'tab_account' : 'settings');
  selectTab(s.tab);
  maybeAskPrivacy();
  requestWakeLock();
}

function selectTab(tab) {
  s.tab = tab;
  $$('#tabbar [data-tab]').forEach((b) => b.setAttribute('aria-current', b.dataset.tab === tab ? 'page' : 'false'));
  $$('.tab').forEach((el) => (el.hidden = el.id !== 'tab-' + tab));
  const el = $('#tab-' + tab);
  if (tab === 'ticket') renderTicket(el);
  if (tab === 'departures') live.renderDepartures(el);
  if (tab === 'planner') renderPlanner(el, { openStop: openStopFromPlanner });
  if (tab === 'history') unlocked() ? renderHistory(el) : renderLocked(el);
  if (tab === 'account') renderAccount(el);
  el.scrollTop = 0;
  window.scrollTo(0, 0);
}
$('#tabbar').onclick = (e) => { const b = e.target.closest('[data-tab]'); if (b) selectTab(b.dataset.tab); };
document.addEventListener('click', (e) => { if (e.target.closest('[data-signin]')) showLogin(); });

/** In place of a tab whose content is behind the PIN (History). */
function renderLocked(el) {
  el.innerHTML = `<div class="empty locked"><h3>${t('locked_title')}</h3><p>${t('locked_body')}</p><button class="btn" data-unlock>${t('unlock')}</button></div>`;
  el.onclick = (e) => { if (e.target.closest('[data-unlock]')) unlockDialog(); };
}

function openStopFromPlanner(name, highlight) {
  selectTab('departures');
  live.openStopByName(name, highlight);
}
live.liveState.onChange = () => { if (s.tab === 'ticket') renderPreview(); };

// ------------------------------------------------------------------ account data

function setAccount(account) {
  if (!account) return;
  s.account = account;
  if (!account.cards?.length && account.serialNumber) account.cards = [account];
  if (s.tab === 'ticket') renderBanners();
}
const selectedCard = () => s.account?.cards?.find((c) => c.serialNumber === s.serial) || s.account?.cards?.[0] || s.account;

async function selectCard(serial) {
  s.serial = serial;
  store.set('serial', serial);
  s.lastBase64 = null;
  s.history = null;
  await api('/api/select-card', { sessionId: s.sessionId, serial });
  pollOnce();
}

// ------------------------------------------------------------------ ticket tab

function renderTicket(el) {
  const cards = s.account?.cards || [];
  const open = unlocked();
  el.innerHTML = `
    <header class="screen-head"><div><h1>${esc(open && s.account?.userName ? s.account.userName.split(' ')[0] : hasAccount() ? t('tab_ticket') : 'Plan&Ride')}</h1><p class="hint small" id="card-type"></p></div></header>
    <div id="banners"></div>
    ${!hasAccount() ? '' : !open
      ? `<button class="locked-stub" data-unlock><span class="locked-icon" aria-hidden="true">▦</span><span class="row-text"><span class="row-title">${t('ticket_locked_title')}</span><span class="row-sub">${t('ticket_locked_body')}</span></span><span aria-hidden="true">🔒</span></button>`
      : `${cards.length > 1 ? `<div class="segmented card-switch" role="radiogroup" aria-label="${esc(t('ticket_card'))}">${cards.map((c) => `<button role="radio" aria-checked="${c.serialNumber === s.serial}" data-card="${esc(c.serialNumber)}">${esc(c.cardTypeName || c.serialNumber)}</button>`).join('')}</div>` : ''}
    <section class="ticket">
      <button class="qr-wrap" id="qr-btn" aria-label="${esc(t('ticket_fullscreen'))}"><div id="qr" class="qr"></div><span id="qr-placeholder" class="qr-placeholder">${t('ticket_loading')}</span></button>
      <div class="stub">
        <div class="refresh-bar"><span id="refresh-fill"></span></div>
        <p id="status" class="status" role="status">${t('ticket_connecting')}</p>
        <p id="validity" class="hint small"></p>
      </div>
    </section>`}
    <div id="follow-slot-ticket"></div>
    <div id="journeys">${journeysHtml()}</div>
    <div id="rides-next">${ridesHtml()}</div>
    <div id="preview"></div>
    ${hasAccount() ? '' : `<div class="notice">${t('home_add_account_hint')} <button class="text-btn" data-add-account>${t('account_add')}</button></div>`}`;
  el.onclick = (e) => {
    const c = e.target.closest('[data-card]');
    if (c) { selectCard(c.dataset.card); renderTicket(el); }
    if (e.target.closest('#qr-btn') && s.lastBase64) openFullscreenQr();
    if (e.target.closest('[data-unlock]')) unlockDialog();
    if (e.target.closest('[data-add-account]')) showLogin();
    const stop = e.target.closest('[data-preview-stop]');
    if (stop) { selectTab('departures'); live.openStopById(stop.dataset.previewStop); }
    const follow = e.target.closest('[data-jfollow]');
    if (follow) return followJourney(savedJourneys().find((j) => j.id === follow.dataset.jfollow));
    const remove = e.target.closest('[data-jremove]');
    if (remove) { removeJourney(remove.dataset.jremove); $('#journeys', el).innerHTML = journeysHtml(); return; }
    const j = e.target.closest('[data-jopen]');
    if (j) {
      const journey = savedJourneys().find((x) => x.id === j.dataset.jopen);
      const first = journey.connection.segments[0];
      selectTab('planner');
      searchAt(journey.route, first.departureTime, first.serviceDate, $('#tab-planner'));
    }
    if (e.target.closest('[data-rides-open]')) selectTab('planner');
  };
  if (open && s.lastBase64) renderQR(s.lastBase64);
  if (open) { renderBanners(); updateStatus(); }
  live.renderFollowSlot($('#follow-slot-ticket', el));
  renderPreview();
}

/** Starred planner results that haven't ended yet, with Follow. */
function journeysHtml() {
  const now = Date.now();
  const list = savedJourneys().filter((j) => { const { arr } = journeyTimes(j); return !arr || arr > now; }).slice(0, 5);
  if (!list.length) return '';
  return `<h2 class="section-label">${t('home_journeys')}</h2><ul class="list">${list.map((j) => {
    const c = j.connection, { dep } = journeyTimes(j);
    const mins = Math.round((dep - now) / 60000);
    const when = !dep ? '' : mins < 0 ? t('journey_underway') : mins < 60 ? t('journey_in', countdown(mins * 60)) : `${rideDayText(dep)} ${clock(dep)}`;
    return `<li class="journey"><button class="row-main" data-jopen="${esc(j.id)}">
        <span class="row-title">${esc(c.departureTime)} – ${esc(c.arrivalTime)} <span class="hint small">${esc(when)}</span></span>
        <span class="row-sub">${esc(readableStop(c.segments[0].departureStop))} → ${esc(readableStop(c.segments.at(-1).arrivalStop))}</span>
        <span class="journey-lines">${c.segments.map((x) => plate(lineNumber(x.line) || '·')).join('')}</span></button>
      <span class="journey-actions"><button class="btn" data-jfollow="${esc(j.id)}">${t('journey_start')}</button>
        <button class="icon-btn" data-jremove="${esc(j.id)}" aria-label="${esc(t('journey_remove'))}">✕</button></span></li>`;
  }).join('')}</ul>`;
}

function followJourney(j) {
  if (!j) return;
  const legs = j.connection.segments.filter((x) => x.routeUrl).map((x) => ({ ref: live.scheduledRef(x), boardingPlatformIds: [], alightOrder: null }));
  if (!legs.length) return;
  live.startJourney(legs);
  toast(t('journey_started'));
}

/** The next run of each regular ride. */
function ridesHtml() {
  const next = rides().map((r) => [r, nextRideDeparture(r)]).filter(([, at]) => at).sort((a, b) => a[1] - b[1]).slice(0, 3);
  if (!next.length) return '';
  return `<h2 class="section-label">${t('rides_title')}</h2><ul class="list">${next.map(([r, at]) =>
    `<li><button class="row link" data-rides-open><span class="row-text"><span class="row-title">${esc(readableStop(r.route.from))} → ${esc(readableStop(r.route.to))}</span>
      <span class="row-sub">${esc(t('rides_next', rideDayText(at), clock(at)))}</span></span><span aria-hidden="true">›</span></button></li>`).join('')}</ul>`;
}

function renderPreview() {
  const el = $('#preview');
  if (el) live.renderPreview(el);
}

function renderBanners() {
  const el = $('#banners');
  const card = selectedCard();
  if (!el || !card || !unlocked()) return;
  $('#card-type').textContent = card.cardTypeName || '';
  $('#validity').textContent = card.ticketValidTo ? t('ticket_valid_until', dateText(toMs(card.ticketValidTo))) : '';
  const out = [];
  const threshold = store.get('threshold', 1.0);
  if (card.creditLastBalance != null && card.creditLastBalance < threshold) out.push(t('low_credit', money(card.creditLastBalance, card.currencySymbol)));
  if (store.get('expiryWarn', true)) {
    for (const [label, to] of [[t('ticket'), card.ticketValidTo], [t('card'), card.cardValidTo], [t('discount'), card.discountValidTo]]) {
      if (!to) continue;
      const days = Math.floor((toMs(to) - Date.now()) / 86400000);
      if (days < 0) out.push(t('expired', label));
      else if (days <= 7) out.push(t('expires_soon', label, days === 0 ? t('today') : t('days_left', days)));
    }
  }
  el.innerHTML = out.map((m) => `<div class="notice warn">${esc(m)}</div>`).join('');
}

function renderQR(text) {
  const el = $('#qr');
  if (!el) return;
  const qr = qrcode(0, 'H'); // error correction H, like Android
  qr.addData(text);
  qr.make();
  el.innerHTML = qr.createSvgTag({ scalable: true, margin: 0 });
  $('#qr-placeholder').hidden = true;
}

function openFullscreenQr() {
  const o = document.createElement('div');
  o.className = 'qr-full';
  o.innerHTML = `<div class="qr-full-code"></div><p>${t('ticket_fullscreen_close')}</p>`;
  const qr = qrcode(0, 'H');
  qr.addData(s.lastBase64);
  qr.make();
  $('.qr-full-code', o).innerHTML = qr.createSvgTag({ scalable: true, margin: 2 });
  o.onclick = () => o.remove();
  document.body.appendChild(o);
}

function resetRefreshBar() {
  const fill = $('#refresh-fill');
  if (!fill) return;
  fill.style.transition = 'none';
  fill.style.transform = 'scaleX(1)';
  void fill.offsetWidth;
  fill.style.transition = `transform ${POLL_MS}ms linear`;
  fill.style.transform = 'scaleX(0)';
}

let statusOverride = '';
function updateStatus(text, isError) {
  if (text !== undefined) statusOverride = text ? { text, isError } : '';
  const el = $('#status');
  if (!el) return;
  // No code yet: still connecting, not "ready".
  let msg = statusOverride?.text || t(s.lastTokenAt ? 'ticket_ready' : 'ticket_connecting'), err = statusOverride?.isError;
  const age = s.lastTokenAt ? Math.round((Date.now() - s.lastTokenAt) / 60000) : 0;
  if (!navigator.onLine) {
    msg = s.lastBase64 ? t('ticket_offline', clock(s.lastTokenAt), age) : t('ticket_offline_no_code');
    err = true;
  } else if (!statusOverride && s.lastTokenAt) {
    msg = t('ticket_refresh_in', Math.max(0, Math.ceil((s.lastTokenAt + POLL_MS - Date.now()) / 1000)));
  }
  el.textContent = msg;
  el.classList.toggle('err', !!err);
}
setInterval(() => { if (s.tab === 'ticket' && !document.hidden) updateStatus(); }, 1000);
addEventListener('online', () => pollOnce());
addEventListener('offline', () => updateStatus());

async function pollOnce() {
  if (!s.sessionId) return;
  const r = await api('/api/token', { sessionId: s.sessionId });
  if (r.status === 401) {
    updateStatus(t('ticket_reconnecting'));
    if (await reauth()) return schedule(500);
    updateStatus(t('ticket_signed_out'), true);
    return schedule(30000);
  }
  if (r.status === 400 && /serial/i.test(r.json.error || '')) { updateStatus(t('ticket_no_card'), true); return schedule(60000); }
  if (r.ok && r.json.success && r.json.base64) {
    if (r.json.base64 !== s.lastBase64) { s.lastBase64 = r.json.base64; renderQR(s.lastBase64); }
    s.lastTokenAt = Date.now();
    resetRefreshBar();
    updateStatus('');
    return schedule(POLL_MS);
  }
  updateStatus(r.status === 0 ? '' : t('ticket_retry'), r.status !== 0);
  schedule(RETRY_MS);
}
function schedule(ms) {
  clearTimeout(s.pollTimer);
  if (s.polling) s.pollTimer = setTimeout(pollOnce, ms);
}
function startPolling() { if (!s.polling) { s.polling = true; pollOnce(); } }
function stopPolling() { s.polling = false; clearTimeout(s.pollTimer); }

// Pause when hidden, lock after the chosen timeout, refresh at once when back.
document.addEventListener('visibilitychange', () => {
  if (document.hidden) { s.hiddenAt = Date.now(); clearTimeout(s.pollTimer); return; }
  // Only after the app was really in the background (iOS also fires this on other occasions).
  if (hasPin() && s.creds && s.hiddenAt && Date.now() - s.hiddenAt >= store.get('lockAfter', 0) * 1000) lockNow();
  s.hiddenAt = 0;
  requestWakeLock();
  if (s.polling) pollOnce();
  live.resumeFollow();
});

async function requestWakeLock() {
  try { if ('wakeLock' in navigator && s.tab === 'ticket' && unlocked()) s.wakeLock = await navigator.wakeLock.request('screen'); } catch { /* not allowed */ }
}

// ------------------------------------------------------------------ history tab

async function renderHistory(el) {
  el.innerHTML = `<header class="screen-head"><h1>${t('history_title')}</h1>
    <span><button class="text-btn" data-csv ${s.history?.length ? '' : 'disabled'}>${t('export_csv')}</button><button class="text-btn" data-reload>${t('refresh')}</button></span></header>
    <div id="hist-body">${s.history ? '' : '<p class="hint"><span class="spinner"></span></p>'}</div>`;
  el.onclick = (e) => {
    if (e.target.closest('[data-reload]')) { s.history = null; renderHistory(el); }
    if (e.target.closest('[data-csv]')) exportCsv();
  };
  if (!s.history) {
    const r = await api('/api/history', { sessionId: s.sessionId, limit: 100 });
    if (r.status === 401 && await reauth()) return renderHistory(el);
    s.historyError = !r.ok;
    s.history = r.ok ? r.json.items || [] : null;
    if (s.tab === 'history') renderHistory(el);
    return;
  }
  const body = $('#hist-body', el);
  if (s.historyError) { body.innerHTML = `<div class="notice error">${t('history_failed')}</div>`; return; }
  if (!s.history.length) { body.innerHTML = `<div class="empty"><p>${t('history_empty')}</p></div>`; return; }
  const card = selectedCard();
  const ins = historyInsights(s.history, card?.creditLastBalance ?? null);
  const max = Math.max(1, ...ins.months.map((m) => m.spentCents));
  const monthName = (m) => new Date(m.year, m.month, 1).toLocaleDateString(lang() === 'en' ? 'en-GB' : 'sk-SK', { month: 'short' });
  const tripsText = (n) => (n === 1 ? t('trip_count_1') : t('trips_count', n));
  const now = ins.months.at(-1), prev = ins.months.at(-2);
  body.innerHTML = `
    <section class="insights">
      <div class="big-stat"><span class="hint">${t('spent_month')}</span><strong>${money(now.spentCents / 100)}</strong><span class="hint small">${tripsText(now.trips)} · ${esc(t('last_month', money(prev.spentCents / 100)))}</span></div>
      <div class="bars" role="img">${ins.months.map((m) => `<div class="bar"><span style="height:${Math.round((m.spentCents / max) * 100)}%" title="${money(m.spentCents / 100)}"></span><small>${esc(monthName(m))}</small></div>`).join('')}</div>
      <dl class="stats">
        ${ins.averageFareCents != null ? `<div><dt>${t('avg_fare')}</dt><dd>${money(ins.averageFareCents / 100)}</dd></div>` : ''}
        ${ins.tripsLeft != null ? `<div><dt>${t('credit_covers')}</dt><dd>${t('trips_left', ins.tripsLeft)}</dd></div>` : ''}
      </dl>
      ${ins.topStops.length ? `<h2 class="section-label">${t('top_stops')}</h2><ol class="top-stops">${ins.topStops.map(([name, n]) => `<li><span>${esc(name)}</span><b>${n}×</b></li>`).join('')}</ol>` : ''}
      ${ins.oldestRecordMs ? `<p class="hint small">${t('insights_basis', dateText(ins.oldestRecordMs))}</p>` : ''}
    </section>
    <ul class="list history">${s.history.map((it) => {
      const amt = (it.amountText || '').trim();
      const cls = amt.startsWith('+') ? 'pos' : amt.startsWith('-') ? 'neg' : '';
      const d = new Date(it.timestampMs);
      // Real UTC timestamps, shown in the phone's time zone like the Android app.
      const when = `${d.getDate()}. ${d.getMonth() + 1}. ${d.getFullYear()} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
      return `<li class="row"><span class="row-text"><span class="row-title">${esc(it.title)}</span><span class="row-sub">${esc(when)}${it.subtitle ? ' · ' + esc(it.subtitle) : ''}</span></span>${amt ? `<b class="amount ${cls}">${esc(amt)}</b>` : ''}</li>`;
    }).join('')}</ul>`;
}

function exportCsv() {
  const blob = new Blob(['﻿' + historyToCsv(s.history)], { type: 'text/csv' });
  const file = new File([blob], `testem-history-${new Date().toISOString().slice(0, 10)}.csv`, { type: 'text/csv' });
  if (navigator.canShare?.({ files: [file] })) { navigator.share({ files: [file] }).catch(() => {}); return; }
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = file.name;
  a.click();
  toast(t('exported'));
}

// ------------------------------------------------------------------ account tab

function renderAccount(el) {
  const open = unlocked();
  const card = open ? selectedCard() : null;
  const cards = open ? s.account?.cards || [] : [];
  const days = (to) => Math.floor((toMs(to) - Date.now()) / 86400000);
  const validity = (label, from, to) => `<li class="row"><span class="row-text"><span class="row-title">${esc(label)}</span><span class="row-sub">${to ? `${dateText(toMs(from))} – ${dateText(toMs(to))}` : t('not_set')}</span></span>
    ${to ? `<b class="days ${days(to) < 0 ? 'neg' : days(to) <= 7 ? 'warn' : ''}">${days(to) < 0 ? '—' : t('days_left', days(to))}</b>` : ''}</li>`;
  const link = (page, title, hint) => `<li><button class="row link" data-page="${page}"><span class="row-text"><span class="row-title">${esc(title)}</span>${hint ? `<span class="row-sub">${esc(hint)}</span>` : ''}</span><span aria-hidden="true">›</span></button></li>`;
  const account = !hasAccount()
    ? `<h2 class="section-label">${t('account_section')}</h2><ul class="list"><li><button class="row link" data-add-account><span class="row-text"><span class="row-title">${t('account_add')}</span><span class="row-sub">${t('account_add_hint')}</span></span><span aria-hidden="true">›</span></button></li></ul>`
    : !open
      ? `<div class="notice">${t('account_locked')} <button class="text-btn" data-unlock>${t('unlock')}</button></div>`
      : `${card?.cardTemplateBase64 ? `<img class="card-image" alt="" src="data:image/png;base64,${esc(card.cardTemplateBase64.replace(/[^A-Za-z0-9+/=_-]/g, ''))}">` : ''}
    ${card?.creditLastBalance != null ? `<div class="big-stat"><span class="hint">${t('balance')}</span><strong>${money(card.creditLastBalance, card.currencySymbol)}</strong><span class="hint small">${esc(card.cardTypeName || '')}</span></div>` : ''}
    ${card ? `<h2 class="section-label">${t('validity')}</h2><ul class="list">${validity(t('ticket'), card.ticketValidFrom, card.ticketValidTo)}${validity(t('card'), card.cardValidFrom, card.cardValidTo)}${card.discountValidTo ? validity(t('discount'), card.discountValidFrom, card.discountValidTo) : ''}</ul>` : ''}
    ${cards.length > 1 ? `<h2 class="section-label">${t('cards')}</h2><ul class="list">${cards.map((c) => `<li><label class="row"><span class="row-text"><span class="row-title">${esc(c.cardTypeName || c.serialNumber)}</span><span class="row-sub">${esc([c.organizationName, c.creditLastBalance != null ? money(c.creditLastBalance, c.currencySymbol) : ''].filter(Boolean).join(', '))}</span></span><input type="radio" name="card" value="${esc(c.serialNumber)}" ${c.serialNumber === s.serial ? 'checked' : ''}></label></li>`).join('')}</ul>` : ''}
    ${card ? `<h2 class="section-label">${t('details')}</h2><ul class="list">${card.organizationName ? `<li class="row"><span class="row-text"><span class="row-title">${t('organization')}</span><span class="row-sub">${esc(card.organizationName)}</span></span></li>` : ''}<li class="row"><span class="row-text"><span class="row-title">${t('card_number')}</span><span class="row-sub">${esc(card.serialNumber || s.serial)}</span></span></li></ul>` : ''}`;
  el.innerHTML = `
    <header class="screen-head"><div><h1>${esc(open ? s.account?.userName || t('account_title') : hasAccount() ? t('account_title') : t('settings'))}</h1><p class="hint small">${esc(open ? s.creds?.email || '' : '')}</p></div>
      ${open ? `<button class="text-btn" data-refresh-account>${t('refresh')}</button>` : ''}</header>
    ${account}
    <h2 class="section-label">${t('settings')}</h2>
    <ul class="list">
      ${link('reminders', t('s_reminders'), hasAccount() ? t('s_reminders_hint') : t('s_reminders_hint_guest'))}
      ${hasAccount() ? link('security', t('s_security'), t('s_security_hint')) : ''}
      ${link('appearance', t('s_appearance'), t('s_appearance_hint'))}
      ${link('language', t('s_language'), lang() === 'en' ? t('lang_en') : t('lang_sk'))}
      ${link('community', t('s_community'), t('s_community_hint'))}
      ${link('about', t('s_about'), t('version', VERSION))}
    </ul>
    <ul class="list">${link('bug', t('s_bug'), t('s_bug_hint'))}${link('feature', t('s_feature'), t('s_feature_hint'))}<li><button class="row link" data-support><span class="row-text"><span class="row-title">${t('s_support')}</span><span class="row-sub">${t('s_support_hint')}</span></span><span aria-hidden="true">›</span></button></li></ul>
    ${hasAccount() ? `<ul class="list">${open ? `<li><button class="row link" data-lock><span class="row-title">${t('lock_now')}</span></button></li>` : ''}<li><button class="row link danger" data-remove-account><span class="row-text"><span class="row-title">${t('account_remove')}</span><span class="row-sub">${t('account_remove_hint')}</span></span></button></li></ul>` : ''}`;
  el.onclick = async (e) => {
    const page = e.target.closest('[data-page]');
    // Security needs the PIN first.
    if (page) return page.dataset.page === 'security' && !open ? unlockDialog() : openSettings(page.dataset.page);
    if (e.target.closest('[data-add-account]')) return showLogin();
    if (e.target.closest('[data-unlock]')) return unlockDialog();
    if (e.target.closest('[data-lock]')) return lockNow();
    // Removing needs the PIN too, so someone holding the phone can't wipe it.
    if (e.target.closest('[data-remove-account]')) return open ? (confirm(t('remove_confirm')) && removeAccount()) : unlockDialog();
    if (e.target.closest('[data-support]')) return showSupport();
    if (e.target.closest('[data-refresh-account]')) {
      const r = await api('/api/account', { sessionId: s.sessionId });
      if (r.ok) { setAccount(r.json.account); renderAccount(el); }
    }
  };
  el.onchange = (e) => { if (e.target.name === 'card') selectCard(e.target.value).then(() => renderAccount(el)); };
}

// ------------------------------------------------------------------ settings pages

function openSettings(page) {
  const titles = { reminders: 's_reminders', security: 's_security', appearance: 's_appearance', language: 's_language', community: 's_community', about: 's_about', bug: 's_bug', feature: 's_feature' };
  const body = openPage(t(titles[page]), () => { if (s.tab === 'account') renderAccount($('#tab-account')); });
  ({ reminders, security, appearance, language, community: communitySettings, about, bug: bugReport, feature: (b) => bugReport(b, true) })[page](body);
}

const ARRIVE_CHOICES = [1, 2, 3, 5, 10];
function reminders(body) {
  const lead = store.get('arriveLead', 2);
  const custom = !ARRIVE_CHOICES.includes(lead);
  body.innerHTML = `
    ${hasAccount() ? `<label class="field">${t('threshold')}<input type="number" step="0.10" min="0" inputmode="decimal" id="threshold" value="${store.get('threshold', 1).toFixed(2)}"></label>
    <ul class="list">${switchRow('expiry', t('expiry_warn'), t('expiry_warn_hint'), store.get('expiryWarn', true))}</ul>` : ''}
    <h2 class="section-label">${t('arrive_title')}</h2>
    <p class="hint">${t('arrive_label')}</p>
    <div class="chips" role="radiogroup" id="arrive">
      ${ARRIVE_CHOICES.map((m) => `<label class="chip"><input type="radio" name="arrive" value="${m}" ${lead === m ? 'checked' : ''}>${m} min</label>`).join('')}
      <label class="chip"><input type="radio" name="arrive" value="custom" ${custom ? 'checked' : ''}>${t('arrive_custom')}</label>
    </div>
    <label class="field" id="arrive-custom" ${custom ? '' : 'hidden'}>${t('arrive_custom_label')}<input type="number" min="1" max="60" step="1" inputmode="numeric" value="${lead}"></label>`;
  const threshold = $('#threshold', body);
  if (threshold) threshold.onchange = (e) => { const v = parseFloat(e.target.value); if (v >= 0) store.set('threshold', v); };
  const customBox = $('#arrive-custom', body);
  $('#arrive', body).onchange = (e) => {
    customBox.hidden = e.target.value !== 'custom';
    if (e.target.value !== 'custom') { store.set('arriveLead', Number(e.target.value)); resyncRides(); }
    else $('input', customBox).focus();
  };
  $('input', customBox).onchange = (e) => {
    const v = Math.round(Number(e.target.value));
    if (v >= 1 && v <= 60) store.set('arriveLead', v); else e.target.value = store.get('arriveLead', 2);
  };
  const expiry = $('#expiry', body);
  if (expiry) expiry.onchange = (e) => store.set('expiryWarn', e.target.checked);
}

function security(body) {
  const timeouts = [[0, 'lock_immediately'], [30, 'lock_30s'], [60, 'lock_1m'], [300, 'lock_5m']];
  const pinForm = (withCurrent) => `<form class="stack" id="pin-form">
      ${withCurrent ? `<label class="field">${t('pin_current')}<input type="password" inputmode="numeric" pattern="[0-9]*" maxlength="8" name="current" required></label>` : ''}
      <label class="field">${t('pin_new')}<input type="password" inputmode="numeric" pattern="[0-9]*" maxlength="8" name="pin" required></label>
      <label class="field">${t('pin_confirm')}<input type="password" inputmode="numeric" pattern="[0-9]*" maxlength="8" name="confirm" required></label>
      <p class="error-text" id="pin-error" hidden></p>
      <button class="btn" type="submit">${t('save')}</button></form>`;
  body.innerHTML = `<p class="hint">${t('pin_hint')}</p>
    <h2 class="section-label">${t('pin_change')}</h2>${pinForm(true)}
    <h2 class="section-label">${t('lock_after')}</h2><ul class="list">${timeouts.map(([sec, key]) => `<li><label class="row"><span class="row-title">${t(key)}</span><input type="radio" name="lockAfter" value="${sec}" ${store.get('lockAfter', 0) === sec ? 'checked' : ''}></label></li>`).join('')}</ul>`;
  const form = $('#pin-form', body);
  form.onsubmit = async (e) => {
    e.preventDefault();
    const f = Object.fromEntries(new FormData(form));
    const err = $('#pin-error', body);
    err.hidden = false;
    if (!/^\d{4,8}$/.test(f.pin)) return (err.textContent = t('pin_short'));
    if (f.pin !== f.confirm) return (err.textContent = t('pin_mismatch'));
    if (hasPin() && !(await decryptCreds(f.current))) return (err.textContent = t('lock_wrong', '–'));
    await encryptCreds(s.creds, f.pin);
    s.pin = f.pin;
    toast(t('pin_saved'));
    security(body);
  };
  body.onchange = (e) => { if (e.target.name === 'lockAfter') store.set('lockAfter', Number(e.target.value)); };
}

function appearance(body) {
  const id = store.get('themeId', 'stop');
  body.innerHTML = `<h2 class="section-label">${t('theme_colours')}</h2>
    <ul class="list">${PRESETS.map((p) => `<li><label class="row"><span class="swatch" style="background:${p.color}"></span><span class="row-title">${esc(p.name)}</span><input type="radio" name="theme" value="${p.id}" ${id === p.id ? 'checked' : ''}></label></li>`).join('')}
      <li><label class="row"><input type="color" id="custom-color" value="${esc(store.get('themeColor', '#1D4FB8'))}" aria-label="${esc(t('theme_custom'))}"><span class="row-title">${t('theme_custom')}</span><input type="radio" name="theme" value="custom" ${id === 'custom' ? 'checked' : ''}></label></li></ul>
    <h2 class="section-label">${t('theme_mode')}</h2>
    <ul class="list">${['auto', 'light', 'dark'].map((m) => `<li><label class="row"><span class="row-title">${t('mode_' + m)}</span><input type="radio" name="mode" value="${m}" ${store.get('themeMode', 'auto') === m ? 'checked' : ''}></label></li>`).join('')}</ul>
    <ul class="list">${switchRow('amoled', t('amoled'), t('amoled_hint'), store.get('amoled', false))}</ul>`;
  body.onchange = (e) => {
    if (e.target.name === 'theme') store.set('themeId', e.target.value);
    if (e.target.id === 'custom-color') { store.set('themeColor', e.target.value); store.set('themeId', 'custom'); $('input[value="custom"]', body).checked = true; }
    if (e.target.name === 'mode') store.set('themeMode', e.target.value);
    if (e.target.id === 'amoled') store.set('amoled', e.target.checked);
    applyTheme();
  };
}

function language(body) {
  body.innerHTML = `<ul class="list">${[['sk', t('lang_sk')], ['en', t('lang_en')]].map(([code, label]) => `<li><label class="row"><span class="row-title">${label}</span><input type="radio" name="lang" value="${code}" ${lang() === code ? 'checked' : ''}></label></li>`).join('')}</ul>`;
  body.onchange = (e) => {
    setLang(e.target.value);
    resyncRides(); // the server's alerts follow the language
    applyI18n();
    closeOverlay('page');
    showApp();
    selectTab('account');
  };
}

const MODES = [['off', 'mode_off', 'mode_off_hint'], ['buttons', 'mode_buttons', 'mode_buttons_hint'], ['auto', 'mode_auto', 'mode_auto_hint']];
function communitySettings(body) {
  const catchOn = store.get('catchOn', false);
  const mode = live.communityMode();
  body.innerHTML = `<p class="hint">${t('privacy_community_body')}</p>
    <ul class="list">${MODES.map(([value, title, hint]) => `<li><label class="row"><span class="row-text"><span class="row-title">${t(title)}</span><span class="row-sub">${t(hint)}</span></span><input type="radio" name="community-mode" value="${value}" ${mode === value ? 'checked' : ''}></label></li>`).join('')}</ul>
    <ul class="list">${switchRow('predict', t('predict_switch'), t('predict_switch_hint'), store.get('predictShift', false))}</ul>
    <p class="hint">${t('privacy_catch_body')}</p>
    <ul class="list">${switchRow('catch-on', t('catch_switch'), '', catchOn)}${switchRow('catch-tt', t('catch_timetable'), t('catch_timetable_hint'), store.get('catchTimetable', false), !catchOn)}</ul>`;
  body.onchange = (e) => {
    if (e.target.name === 'community-mode') {
      store.set('communityMode', e.target.value);
      if (e.target.value === 'auto') navigator.geolocation?.getCurrentPosition(() => {}, () => toast(t('catch_location_off')), { enableHighAccuracy: true });
    }
    if (e.target.id === 'catch-on') {
      store.set('catchOn', e.target.checked);
      $('#catch-tt', body).disabled = !e.target.checked;
      if (e.target.checked) navigator.geolocation?.getCurrentPosition(() => {}, () => toast(t('catch_location_off')));
    }
    if (e.target.id === 'catch-tt') store.set('catchTimetable', e.target.checked);
    if (e.target.id === 'predict') store.set('predictShift', e.target.checked);
  };
}

function about(body) {
  body.innerHTML = `<ul class="list"><li class="row"><span class="row-title">${t('version', VERSION)}</span></li>
    ${s.serial ? `<li class="row"><span class="row-title">${esc(t('card_serial', s.serial))}</span></li>` : ''}</ul>
    <ul class="list"><li><a class="row link" href="${site('changelog')}" target="_blank" rel="noopener"><span class="row-title">${t('whats_new')}</span><span aria-hidden="true">›</span></a></li>
      <li><a class="row link" href="${site('privacy')}" target="_blank" rel="noopener"><span class="row-title">${t('privacy_policy')}</span><span aria-hidden="true">›</span></a></li></ul>
    <button class="btn outline wide" data-update>${t('check_updates')}</button>
    <p class="hint small">${t('login_disclaimer')}</p>`;
  $('[data-update]', body).onclick = async () => {
    const regs = await navigator.serviceWorker?.getRegistrations() || [];
    await Promise.all(regs.map((r) => r.update()));
    location.reload();
  };
}

/** The Plan&Ride website (served by emhub): Slovak at /page, English at /en/page. */
const site = (page) => `https://par.karafa.net/${lang() === 'en' ? 'en/' : ''}${page}`;

const CATEGORIES = ['crash', 'ticket', 'departures', 'planner', 'tracking', 'account', 'other'];

/** A photo for a bug report: at most 1600 px on the longer side, JPEG, as base64 (a phone photo is ~200 kB then). */
async function shrinkImage(file) {
  const bitmap = await createImageBitmap(file);
  const scale = Math.min(1, 1600 / Math.max(bitmap.width, bitmap.height));
  const canvas = document.createElement('canvas');
  canvas.width = Math.round(bitmap.width * scale);
  canvas.height = Math.round(bitmap.height * scale);
  canvas.getContext('2d').drawImage(bitmap, 0, 0, canvas.width, canvas.height);
  const blob = await new Promise((done) => canvas.toBlob(done, 'image/jpeg', 0.8));
  const bytes = new Uint8Array(await blob.arrayBuffer());
  let binary = '';
  for (let i = 0; i < bytes.length; i += 0x8000) binary += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  return btoa(binary);
}
/** feature: a suggestion (no category, no logs); emhub lists them apart from bugs. */
function bugReport(body, feature = false) {
  if (!config.hub) { body.innerHTML = `<div class="notice warn">${t('bug_off')}</div>`; return; }
  const logs = feature ? '' : collectLogs();
  // Only while unlocked: the name and email belong to the protected account.
  const name = unlocked() ? s.account?.userName || '' : '', email = unlocked() ? s.creds?.email || '' : '';
  body.innerHTML = `<form class="stack" id="bug-form">
    ${feature ? '' : `<fieldset class="chips"><legend class="section-label">${t('bug_category')}</legend>
      ${CATEGORIES.map((c, i) => `<label class="chip"><input type="radio" name="category" value="${c}" ${i === CATEGORIES.length - 1 ? 'checked' : ''}>${t('cat_' + c)}</label>`).join('')}</fieldset>`}
    <label class="field">${t(feature ? 'feature_summary' : 'bug_summary')}<input name="title" maxlength="140"></label>
    <label class="field">${t(feature ? 'feature_description' : 'bug_description')}<textarea name="description" rows="5" maxlength="10000"></textarea></label>
    <label class="field">${t('bug_name')}<input name="name" maxlength="120" value="${esc(name)}" autocomplete="name"></label>
    <label class="field">${t('bug_email')}<input name="email" type="email" maxlength="200" value="${esc(email)}" autocomplete="email"><small class="hint">${t('bug_email_hint')}</small></label>
    <div class="notice warn" id="no-email" ${email || feature ? 'hidden' : ''}>${t('bug_no_email')}</div>
    <label class="field">${t('bug_images')}<input type="file" name="images" accept="image/*" multiple><small class="hint">${t('bug_images_hint')}</small></label>
    ${feature ? '' : `<ul class="list">${switchRow('attach-logs', t('bug_logs'), t('bug_logs_hint'), true)}</ul>
    <details><summary>${t('bug_logs_show')}</summary><pre class="logs">${esc(logs)}</pre></details>`}
    <label class="consent"><input type="checkbox" name="consent" required> <span>${t(feature ? 'feature_consent' : 'bug_consent')}</span></label>
    <p class="error-text" id="bug-error" role="alert" hidden></p>
    <button class="btn" type="submit" id="bug-send" disabled>${t(feature ? 'feature_send' : 'bug_send')}</button></form>`;
  const form = $('#bug-form', body), send = $('#bug-send', body);
  // Only logs are essential: a description is needed only when no logs are attached.
  const withLogs = () => !feature && $('#attach-logs', body).checked;
  const hasContent = () => withLogs() || form.description.value.trim() || (feature && form.title.value.trim());
  form.oninput = form.onchange = () => {
    $('#no-email', body).hidden = feature || !!form.email.value.trim();
    send.disabled = !form.checkValidity() || !hasContent();
  };
  form.onsubmit = async (e) => {
    e.preventDefault();
    const f = Object.fromEntries(new FormData(form));
    const err = $('#bug-error', body);
    send.disabled = true;
    send.textContent = t('bug_sending');
    try {
      const images = await Promise.all([...form.images.files].slice(0, 5).map(shrinkImage));
      const r = await apiJson('/api/hub/bugs', {
        kind: feature ? 'feature' : 'bug', category: feature ? 'feature' : f.category, title: f.title.trim(), description: f.description.trim(), name: f.name.trim(), email: f.email.trim(),
        appVersion: VERSION, device: navigator.userAgent, logs: withLogs() ? logs : '', consent: true, images,
      });
      closeOverlay('page');
      toast(t(feature ? 'feature_sent' : 'bug_sent', r.code));
    } catch (error) {
      err.textContent = t('bug_failed', error.message);
      err.hidden = false;
      send.disabled = false;
      send.textContent = t(feature ? 'feature_send' : 'bug_send');
    }
  };
}

// ------------------------------------------------------------------ support

const SUPPORT_URL = 'https://karafa.net/support/';
function showSupport() {
  const d = document.createElement('div');
  d.className = 'dialog-backdrop';
  d.innerHTML = `<div class="dialog" role="dialog" aria-modal="true" aria-labelledby="support-title">
    <h2 id="support-title">${t('s_support')}</h2>
    <p class="hint">${t('support_body')}</p>
    <a class="btn wide" href="${SUPPORT_URL}" target="_blank" rel="noopener">${t('support_open')}</a>
    <button class="text-btn wide-text" data-close-support>${t('support_later')}</button></div>`;
  d.onclick = (e) => { if (e.target === d || e.target.closest('[data-close-support]') || e.target.closest('a')) d.remove(); };
  document.body.appendChild(d);
}

// ------------------------------------------------------------------ first-run privacy question

function maybeAskPrivacy() {
  if (store.get('privacyAsked', false) || $('#privacy')) return;
  const d = document.createElement('div');
  d.id = 'privacy';
  d.className = 'dialog-backdrop';
  d.innerHTML = `<div class="dialog" role="dialog" aria-modal="true" aria-labelledby="privacy-title">
    <h2 id="privacy-title">${t('privacy_title')}</h2>
    <ul class="list">
      <li class="row" style="flex-direction:column;align-items:stretch"><span class="row-title">${t('privacy_community_title')}</span><span class="row-sub">${t('privacy_community_body')}</span>
        <div class="chips" style="margin:8px 0 0">${MODES.map(([value, title], i) => `<label class="chip"><input type="radio" name="p-mode" value="${value}" ${i === 0 ? 'checked' : ''}>${t(title)}</label>`).join('')}</div></li>
      ${switchRow('p-catch', t('privacy_catch_title'), t('privacy_catch_body'), false)}
    </ul>
    <p class="hint small">${t('privacy_hint')}</p>
    <button class="btn wide" data-done>${t('done')}</button></div>`;
  document.body.appendChild(d);
  $('[data-done]', d).onclick = () => {
    const mode = $('input[name="p-mode"]:checked', d)?.value || 'off';
    store.set('communityMode', mode);
    store.set('catchOn', $('#p-catch', d).checked);
    store.set('privacyAsked', true);
    if ($('#p-catch', d).checked || mode === 'auto') navigator.geolocation?.getCurrentPosition(() => {}, () => {}, { enableHighAccuracy: true });
    d.remove();
  };
}

// ------------------------------------------------------------------ boot

if ('serviceWorker' in navigator) navigator.serviceWorker.register('/sw.js').catch(() => {});
applyTheme();
applyI18n();
$('#install-btn').onclick = async () => { if (installPrompt) { installPrompt.prompt(); await installPrompt.userChoice.catch(() => {}); installPrompt = null; } };

/**
 * Opened from a notification: "?show=follow" shows the followed bus's stops over the home tab;
 * "?ride=<id>&dep=<ms>" starts following that day's bus of a regular ride here too.
 */
async function handleLaunch(params) {
  const ride = rides().find((r) => r.id === params.get('ride'));
  if (params.get('show') === 'follow' || ride) { selectTab('ticket'); live.openFollowed(); }
  if (!ride) return;
  const dep = Number(params.get('dep')) || nextRideDeparture(ride, Date.now() - 30 * 60000);
  if (!dep) return;
  const day = todayLocal(dep);
  const at = new Date(dep - 2 * 60000).toLocaleTimeString('sk-SK', { hour: '2-digit', minute: '2-digit', timeZone: 'Europe/Bratislava' });
  const r = await apiJson('/api/cp/search', { city: ride.route.city, from: ride.route.from, to: ride.route.to, fromSug: ride.route.fromSug, toSug: ride.route.toSug,
    time: at, date: `${day.day}.${day.month}.${day.year}`, directOnly: false }).catch(() => null);
  const c = r && pickRideConnection(r.connections, ride, dep);
  if (!c) return toast(t('rides_missing'));
  live.startJourney(c.segments.filter((x) => x.routeUrl).map((x) => ({ ref: live.scheduledRef(x), boardingPlatformIds: [], alightOrder: null })));
  live.openFollowed();
}
navigator.serviceWorker?.addEventListener('message', (e) => {
  if (e.data?.type === 'open') handleLaunch(new URL(e.data.url, location.origin).searchParams);
});

(async function boot() {
  if (enforceInstallGate()) return;
  await loadConfig();
  log('I', 'boot', VERSION);
  live.resumeFollow();
  const params = new URLSearchParams(location.search);
  if (params.size) history.replaceState(null, '', '/');
  const plain = store.get('creds');
  if (plain?.email && plain?.password && !hasPin()) {
    // Saved before 2.8 without a PIN: sign in, then the PIN is set and the password encrypted.
    s.creds = plain;
    if (!(await signIn(plain))) showPinSetup();
    return;
  }
  if (!hasAccount() && !store.get('onboarded', false)) return showLogin({ welcome: true });
  showApp(); // with an account: locked until the PIN, the rest works right away
  handleLaunch(params);
})();
