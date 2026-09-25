// testEM web client: entry point. Ticket, account, history, settings, PIN lock, bug reports.
import { t, lang, setLang, applyI18n } from './js/i18n.js';
import { $, $$, esc, api, apiJson, store, config, loadConfig, clock, dateText, money, toMs, toast, openPage, closeOverlay, switchRow, collectLogs, log, VERSION } from './js/core.js';
import { historyInsights, historyToCsv } from './js/logic.js';
import * as live from './js/live.js';
import { renderPlanner } from './js/planner.js';

const POLL_MS = 25000;
const RETRY_MS = 3000;

const s = {
  creds: null, // in memory only once decrypted
  sessionId: null, serial: '', account: null,
  lastBase64: null, lastTokenAt: 0, pollTimer: null, polling: false, reauthInFlight: false,
  history: null, historyError: false, guest: false, tab: 'ticket', wakeLock: null, hiddenAt: 0,
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
/** With a PIN the password is only ever stored encrypted. */
async function saveCreds(creds) {
  s.creds = creds;
  if (!hasPin()) store.set('creds', creds);
  else if (s.pin) await encryptCreds(creds, s.pin);
}

// ------------------------------------------------------------------ views

function show(id) {
  for (const v of ['install-gate', 'lock-view', 'login-view', 'app-view']) $('#' + v).hidden = v !== id;
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

// ---- PIN lock

let lockTries = 0, lockUntil = 0;
function showLock() {
  stopPolling();
  show('lock-view');
  const input = $('#lock-pin');
  input.value = '';
  $('#lock-error').hidden = true;
  setTimeout(() => input.focus(), 50);
}
$('#lock-form').onsubmit = async (e) => {
  e.preventDefault();
  const err = $('#lock-error');
  if (Date.now() < lockUntil) { err.textContent = t('lock_wait', Math.ceil((lockUntil - Date.now()) / 1000)); err.hidden = false; return; }
  const pin = $('#lock-pin').value;
  const creds = await decryptCreds(pin);
  if (!creds) {
    lockTries++;
    if (lockTries >= 5) { lockUntil = Date.now() + 30000; lockTries = 0; err.textContent = t('lock_wait', 30); }
    else err.textContent = t('lock_wrong', 5 - lockTries);
    err.hidden = false;
    $('#lock-pin').value = '';
    return;
  }
  lockTries = 0;
  s.creds = creds;
  s.pin = pin;
  if (s.sessionId) { showApp(); startPolling(); } else signIn(creds);
};
$('#lock-forgot').onclick = () => { if (confirm(t('logout_confirm'))) logout(); };

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
$('#browse-btn').onclick = () => { s.guest = true; showApp(); };

async function signIn(creds, { fromForm } = {}) {
  const r = await api('/api/login', { ...creds, serial: store.get('serial', '') });
  if (!r.ok || !r.json.success) {
    if (fromForm) {
      $('#login-error').textContent = r.json.error === 'offline' ? t('ticket_offline_no_code') : r.json.error || t('login_failed', r.status);
      $('#login-error').hidden = false;
    } else { showLogin(); }
    return false;
  }
  s.guest = false;
  s.sessionId = r.json.sessionId;
  await saveCreds(creds);
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

function showLogin() {
  s.guest = false;
  show('login-view');
  applyI18n($('#login-view'));
  $('#email').value = s.creds?.email || store.get('creds')?.email || '';
}

function logout() {
  if (s.sessionId) api('/api/logout', { sessionId: s.sessionId });
  stopPolling();
  Object.assign(s, { creds: null, pin: null, sessionId: null, account: null, lastBase64: null, history: null, serial: '' });
  store.set('creds', null);
  store.set('credsEnc', null);
  store.set('serial', null);
  closeOverlay('page');
  showLogin();
}

// ------------------------------------------------------------------ app shell & tabs

const TABS = ['ticket', 'departures', 'planner', 'history', 'account'];
function showApp() {
  show('app-view');
  const allowed = s.guest ? ['departures', 'planner'] : TABS;
  $$('#tabbar [data-tab]').forEach((b) => (b.hidden = !allowed.includes(b.dataset.tab)));
  if (!allowed.includes(s.tab)) s.tab = allowed[0];
  applyI18n($('#tabbar'));
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
  if (tab === 'departures') live.renderDepartures(el, { guest: s.guest });
  if (tab === 'planner') renderPlanner(el, { openStop: openStopFromPlanner, guest: s.guest });
  if (tab === 'history') renderHistory(el);
  if (tab === 'account') renderAccount(el);
  el.scrollTop = 0;
  window.scrollTo(0, 0);
}
$('#tabbar').onclick = (e) => { const b = e.target.closest('[data-tab]'); if (b) selectTab(b.dataset.tab); };
document.addEventListener('click', (e) => { if (e.target.closest('[data-signin]')) showLogin(); });

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
  el.innerHTML = `
    <header class="screen-head"><div><h1>${esc(s.account?.userName || t('tab_ticket'))}</h1><p class="hint small" id="card-type"></p></div></header>
    <div id="banners"></div>
    ${cards.length > 1 ? `<div class="segmented card-switch" role="radiogroup" aria-label="${esc(t('ticket_card'))}">${cards.map((c) => `<button role="radio" aria-checked="${c.serialNumber === s.serial}" data-card="${esc(c.serialNumber)}">${esc(c.cardTypeName || c.serialNumber)}</button>`).join('')}</div>` : ''}
    <section class="ticket">
      <button class="qr-wrap" id="qr-btn" aria-label="${esc(t('ticket_fullscreen'))}"><div id="qr" class="qr"></div><span id="qr-placeholder" class="qr-placeholder">${t('ticket_loading')}</span></button>
      <div class="stub">
        <div class="refresh-bar"><span id="refresh-fill"></span></div>
        <p id="status" class="status" role="status">${t('ticket_connecting')}</p>
        <p id="validity" class="hint small"></p>
      </div>
    </section>
    <div id="follow-slot-ticket"></div>
    <div id="preview"></div>`;
  el.onclick = (e) => {
    const c = e.target.closest('[data-card]');
    if (c) { selectCard(c.dataset.card); renderTicket(el); }
    if (e.target.closest('#qr-btn') && s.lastBase64) openFullscreenQr();
  };
  if (s.lastBase64) renderQR(s.lastBase64);
  renderBanners();
  live.renderFollowSlot($('#follow-slot-ticket', el));
  renderPreview();
  updateStatus();
}

function renderPreview() {
  const el = $('#preview');
  if (el) live.renderPreview(el);
}

function renderBanners() {
  const el = $('#banners');
  const card = selectedCard();
  if (!el || !card) return;
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
  let msg = statusOverride?.text || t('ticket_ready'), err = statusOverride?.isError;
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
  if (hasPin() && s.creds && Date.now() - s.hiddenAt >= store.get('lockAfter', 0) * 1000) { s.creds = null; return showLock(); }
  requestWakeLock();
  if (s.polling) pollOnce();
  live.resumeFollow();
});

async function requestWakeLock() {
  try { if ('wakeLock' in navigator && s.tab === 'ticket') s.wakeLock = await navigator.wakeLock.request('screen'); } catch { /* not allowed */ }
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
  const card = selectedCard();
  const cards = s.account?.cards || [];
  const days = (to) => Math.floor((toMs(to) - Date.now()) / 86400000);
  const validity = (label, from, to) => `<li class="row"><span class="row-text"><span class="row-title">${esc(label)}</span><span class="row-sub">${to ? `${dateText(toMs(from))} – ${dateText(toMs(to))}` : t('not_set')}</span></span>
    ${to ? `<b class="days ${days(to) < 0 ? 'neg' : days(to) <= 7 ? 'warn' : ''}">${days(to) < 0 ? '—' : t('days_left', days(to))}</b>` : ''}</li>`;
  const link = (page, title, hint) => `<li><button class="row link" data-page="${page}"><span class="row-text"><span class="row-title">${esc(title)}</span>${hint ? `<span class="row-sub">${esc(hint)}</span>` : ''}</span><span aria-hidden="true">›</span></button></li>`;
  el.innerHTML = `
    <header class="screen-head"><div><h1>${esc(s.account?.userName || t('account_title'))}</h1><p class="hint small">${esc(s.creds?.email || '')}</p></div>
      <button class="text-btn" data-refresh-account>${t('refresh')}</button></header>
    ${card?.cardTemplateBase64 ? `<img class="card-image" alt="" src="data:image/png;base64,${esc(card.cardTemplateBase64.replace(/[^A-Za-z0-9+/=_-]/g, ''))}">` : ''}
    ${card?.creditLastBalance != null ? `<div class="big-stat"><span class="hint">${t('balance')}</span><strong>${money(card.creditLastBalance, card.currencySymbol)}</strong><span class="hint small">${esc(card.cardTypeName || '')}</span></div>` : ''}
    ${card ? `<h2 class="section-label">${t('validity')}</h2><ul class="list">${validity(t('ticket'), card.ticketValidFrom, card.ticketValidTo)}${validity(t('card'), card.cardValidFrom, card.cardValidTo)}${card.discountValidTo ? validity(t('discount'), card.discountValidFrom, card.discountValidTo) : ''}</ul>` : ''}
    ${cards.length > 1 ? `<h2 class="section-label">${t('cards')}</h2><ul class="list">${cards.map((c) => `<li><label class="row"><span class="row-text"><span class="row-title">${esc(c.cardTypeName || c.serialNumber)}</span><span class="row-sub">${esc([c.organizationName, c.creditLastBalance != null ? money(c.creditLastBalance, c.currencySymbol) : ''].filter(Boolean).join(', '))}</span></span><input type="radio" name="card" value="${esc(c.serialNumber)}" ${c.serialNumber === s.serial ? 'checked' : ''}></label></li>`).join('')}</ul>` : ''}
    ${card ? `<h2 class="section-label">${t('details')}</h2><ul class="list">${card.organizationName ? `<li class="row"><span class="row-text"><span class="row-title">${t('organization')}</span><span class="row-sub">${esc(card.organizationName)}</span></span></li>` : ''}<li class="row"><span class="row-text"><span class="row-title">${t('card_number')}</span><span class="row-sub">${esc(card.serialNumber || s.serial)}</span></span></li></ul>` : ''}
    <h2 class="section-label">${t('settings')}</h2>
    <ul class="list">
      ${link('reminders', t('s_reminders'), t('s_reminders_hint'))}
      ${link('security', t('s_security'), t('s_security_hint'))}
      ${link('appearance', t('s_appearance'), t('s_appearance_hint'))}
      ${link('language', t('s_language'), lang() === 'en' ? t('lang_en') : t('lang_sk'))}
      ${link('community', t('s_community'), t('s_community_hint'))}
      ${link('about', t('s_about'), t('version', VERSION))}
    </ul>
    <ul class="list">${link('bug', t('s_bug'), t('s_bug_hint'))}<li><button class="row link" data-support><span class="row-text"><span class="row-title">${t('s_support')}</span><span class="row-sub">${t('s_support_hint')}</span></span><span aria-hidden="true">›</span></button></li></ul>
    <ul class="list"><li><button class="row link danger" data-logout><span class="row-title">${t('logout')}</span></button></li></ul>`;
  el.onclick = async (e) => {
    const page = e.target.closest('[data-page]');
    if (page) return openSettings(page.dataset.page);
    if (e.target.closest('[data-logout]') && confirm(t('logout_confirm'))) return logout();
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
  const titles = { reminders: 's_reminders', security: 's_security', appearance: 's_appearance', language: 's_language', community: 's_community', about: 's_about', bug: 's_bug' };
  const body = openPage(t(titles[page]), () => { if (s.tab === 'account') renderAccount($('#tab-account')); });
  ({ reminders, security, appearance, language, community: communitySettings, about, bug: bugReport })[page](body);
}

const ARRIVE_CHOICES = [1, 2, 3, 5, 10];
function reminders(body) {
  const lead = store.get('arriveLead', 2);
  const custom = !ARRIVE_CHOICES.includes(lead);
  body.innerHTML = `
    <label class="field">${t('threshold')}<input type="number" step="0.10" min="0" inputmode="decimal" id="threshold" value="${store.get('threshold', 1).toFixed(2)}"></label>
    <ul class="list">${switchRow('expiry', t('expiry_warn'), t('expiry_warn_hint'), store.get('expiryWarn', true))}</ul>
    <h2 class="section-label">${t('arrive_title')}</h2>
    <p class="hint">${t('arrive_label')}</p>
    <div class="chips" role="radiogroup" id="arrive">
      ${ARRIVE_CHOICES.map((m) => `<label class="chip"><input type="radio" name="arrive" value="${m}" ${lead === m ? 'checked' : ''}>${m} min</label>`).join('')}
      <label class="chip"><input type="radio" name="arrive" value="custom" ${custom ? 'checked' : ''}>${t('arrive_custom')}</label>
    </div>
    <label class="field" id="arrive-custom" ${custom ? '' : 'hidden'}>${t('arrive_custom_label')}<input type="number" min="1" max="60" step="1" inputmode="numeric" value="${lead}"></label>`;
  $('#threshold', body).onchange = (e) => { const v = parseFloat(e.target.value); if (v >= 0) store.set('threshold', v); };
  const customBox = $('#arrive-custom', body);
  $('#arrive', body).onchange = (e) => {
    customBox.hidden = e.target.value !== 'custom';
    if (e.target.value !== 'custom') store.set('arriveLead', Number(e.target.value));
    else $('input', customBox).focus();
  };
  $('input', customBox).onchange = (e) => {
    const v = Math.round(Number(e.target.value));
    if (v >= 1 && v <= 60) store.set('arriveLead', v); else e.target.value = store.get('arriveLead', 2);
  };
  $('#expiry', body).onchange = (e) => store.set('expiryWarn', e.target.checked);
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
    ${hasPin()
      ? `<h2 class="section-label">${t('pin_change')}</h2>${pinForm(true)}
         <h2 class="section-label">${t('lock_after')}</h2><ul class="list">${timeouts.map(([sec, key]) => `<li><label class="row"><span class="row-title">${t(key)}</span><input type="radio" name="lockAfter" value="${sec}" ${store.get('lockAfter', 0) === sec ? 'checked' : ''}></label></li>`).join('')}</ul>
         <button class="btn outline danger wide" data-remove-pin>${t('pin_remove')}</button>`
      : `<h2 class="section-label">${t('pin_set')}</h2>${pinForm(false)}`}`;
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
  const remove = $('[data-remove-pin]', body);
  if (remove) remove.onclick = () => { store.set('credsEnc', null); store.set('creds', s.creds); s.pin = null; toast(t('pin_removed')); security(body); };
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
    <button class="btn outline wide" data-update>${t('check_updates')}</button>
    <p class="hint small">${t('login_disclaimer')}</p>`;
  $('[data-update]', body).onclick = async () => {
    const regs = await navigator.serviceWorker?.getRegistrations() || [];
    await Promise.all(regs.map((r) => r.update()));
    location.reload();
  };
}

const CATEGORIES = ['crash', 'ticket', 'departures', 'planner', 'tracking', 'account', 'other'];
function bugReport(body) {
  if (!config.hub) { body.innerHTML = `<div class="notice warn">${t('bug_off')}</div>`; return; }
  const logs = collectLogs();
  body.innerHTML = `<form class="stack" id="bug-form">
    <fieldset class="chips"><legend class="section-label">${t('bug_category')}</legend>
      ${CATEGORIES.map((c, i) => `<label class="chip"><input type="radio" name="category" value="${c}" ${i === CATEGORIES.length - 1 ? 'checked' : ''}>${t('cat_' + c)}</label>`).join('')}</fieldset>
    <label class="field">${t('bug_summary')}<input name="title" maxlength="140"></label>
    <label class="field">${t('bug_description')}<textarea name="description" rows="5" maxlength="10000"></textarea></label>
    <label class="field">${t('bug_name')}<input name="name" maxlength="120" value="${esc(s.account?.userName || '')}" autocomplete="name"></label>
    <label class="field">${t('bug_email')}<input name="email" type="email" maxlength="200" value="${esc(s.creds?.email || '')}" autocomplete="email"><small class="hint">${t('bug_email_hint')}</small></label>
    <div class="notice warn" id="no-email" ${s.creds?.email ? 'hidden' : ''}>${t('bug_no_email')}</div>
    <ul class="list">${switchRow('attach-logs', t('bug_logs'), t('bug_logs_hint'), true)}</ul>
    <details><summary>${t('bug_logs_show')}</summary><pre class="logs">${esc(logs)}</pre></details>
    <label class="consent"><input type="checkbox" name="consent" required> <span>${t('bug_consent')}</span></label>
    <p class="error-text" id="bug-error" role="alert" hidden></p>
    <button class="btn" type="submit" id="bug-send" disabled>${t('bug_send')}</button></form>`;
  const form = $('#bug-form', body), send = $('#bug-send', body);
  // Only logs are essential: a description is needed only when no logs are attached.
  const hasContent = () => $('#attach-logs', body).checked || form.description.value.trim();
  form.oninput = form.onchange = () => {
    $('#no-email', body).hidden = !!form.email.value.trim();
    send.disabled = !form.checkValidity() || !hasContent();
  };
  form.onsubmit = async (e) => {
    e.preventDefault();
    const f = Object.fromEntries(new FormData(form));
    const err = $('#bug-error', body);
    send.disabled = true;
    send.textContent = t('bug_sending');
    try {
      const r = await apiJson('/api/hub/bugs', {
        category: f.category, title: f.title.trim(), description: f.description.trim(), name: f.name.trim(), email: f.email.trim(),
        appVersion: VERSION, device: navigator.userAgent, logs: $('#attach-logs', body).checked ? logs : '', consent: true,
      });
      closeOverlay('page');
      toast(t('bug_sent', r.code));
    } catch (error) {
      err.textContent = t('bug_failed', error.message);
      err.hidden = false;
      send.disabled = false;
      send.textContent = t('bug_send');
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

(async function boot() {
  if (enforceInstallGate()) return;
  await loadConfig();
  log('I', 'boot', VERSION);
  live.resumeFollow();
  if (hasPin()) return showLock();
  const creds = store.get('creds');
  if (creds?.email && creds?.password) {
    s.creds = creds;
    showApp();
    if (!(await signIn(creds))) showLogin();
  } else {
    showLogin();
  }
})();
