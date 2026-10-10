// Plan&Ride web version (testEM internally): entry point. Home, account, history, settings,
// PIN lock, bug reports and suggestions. The account is optional, like in the Android app.
import { t, lang, setLang, applyI18n } from './js/i18n.js';
import { $, $$, esc, api, apiJson, store, config, loadConfig, clock, dateText, money, toMs, toast, openPage, closeOverlay, switchRow, collectLogs, log, plate, countdown, VERSION } from './js/core.js';
import { historyInsights, historyToCsv, nextRideDeparture, pickRideConnection, readableStop, lineNumber, todayLocal, walletForLine } from './js/logic.js';
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
  // IDS BBSK (eshop.idsbbsk.sk, EM-0023): its session on our server, account, history and static QR code.
  ids: { sessionId: null, account: null, history: [], qr: null, loading: false, signedOut: false },
  // Ticket shown by hand (null: by the followed bus or the default); the line that last decided it.
  wallet: null, ruledLine: '', accountWallet: 'sadzv',
};

// ------------------------------------------------------------------ theme

/** Built-in themes, as in the Android app (ThemePreset.kt): a main colour plus single colours for parts of the app. */
const PRESETS = [
  { id: 'planride', name: 'Plan&Ride', color: '#157A38', roles: { background: '#F2F5F3', highlight: '#157A38', bar: '#14181D' } },
  { id: 'sage', name: 'Sage', color: '#157A38', roles: { background: '#E2ECE4', highlight: '#0B3D1C', bar: '#157A38', plate: '#0B3D1C' } },
  { id: 'darkgreen', name: 'Dark green', color: '#157A38', roles: { background: '#0F1D17', cards: '#172A21', highlight: '#157A38', bar: '#6EE7A8', plate: '#2A4A3A' } },
  { id: 'inkmint', name: 'Ink & mint', color: '#157A38', roles: { background: '#FFFFFF', cards: '#EEF3F0', highlight: '#14181D', bar: '#14181D' } },
  { id: 'stop', name: 'Zastávka', color: '#1D4FB8', roles: {} },
  { id: 'ocean', name: 'Ocean', color: '#136F63', roles: {} },
];
/** Every colour a rider can set, in the order Appearance lists them ("primary" is the accent). */
const ROLES = ['primary', 'background', 'cards', 'highlight', 'bar', 'live', 'delay', 'late', 'text', 'plate'];
/** Relative brightness of "#rrggbb", 0 (black) to 1 (white). */
function luminance(hex) {
  const [r, g, b] = [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16) / 255).map((c) => (c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4));
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}
/** Ready background colours, as in the Android app. */
const BACKGROUNDS = ['#F3EEE4', '#E8F0E6', '#E6EEF7', '#F6E8EC', '#1C2433', '#1E2A22', '#2B1F2E', '#000000'];
const onColor = (hex) => (luminance(hex) > 0.45 ? '#14181D' : '#FFFFFF');

/** The selected theme with the rider's own changes to it: { id, accent, roles }. */
function currentTheme() {
  let id = store.get('themeId', 'planride');
  if (id === 'sunset') id = 'planride';
  const own = (store.get('themeRoles', {}) || {})[id] || {};
  if (id === 'custom') return { id, accent: store.get('themeColor', '#157A38'), roles: own };
  const preset = PRESETS.find((x) => x.id === id) || PRESETS[0];
  const roles = { ...preset.roles, ...own };
  return { id: preset.id, accent: roles.primary || preset.color, roles };
}

/** One colour of the selected theme; null goes back to the theme's own. */
function setThemeColor(role, hex) {
  const id = currentTheme().id;
  const all = { ...(store.get('themeRoles', {}) || {}) };
  const own = { ...(all[id] || {}) };
  if (hex) own[role] = hex; else delete own[role];
  all[id] = own;
  store.set('themeRoles', all);
}

const THEME_VARS = ['--bg', '--surface', '--surface-2', '--line', '--text', '--muted', '--highlight', '--on-highlight',
  '--bar', '--on-bar', '--live', '--late', '--bad', '--plate', '--on-plate'];

function applyTheme() {
  const { accent, roles } = currentTheme();
  const root = document.documentElement;
  root.style.setProperty('--accent', accent);
  root.style.setProperty('--on-accent', onColor(accent));
  for (const v of THEME_VARS) root.style.removeProperty(v);
  const mode = store.get('themeMode', 'auto');
  // EM-0042: an own background colour; light or dark text then follows its brightness. A dark theme does the same.
  const own = store.get('bgColor', null);
  // A dark theme's own background, or any background the rider picked, decides light or dark.
  const shipped = (PRESETS.find((x) => x.id === currentTheme().id) || {}).roles || {};
  const picked = (role) => roles[role] && roles[role] !== shipped[role];
  const themeDark = roles.background && (luminance(roles.background) < 0.2 || picked('background')) ? roles.background : null;
  if (own || themeDark) root.dataset.theme = luminance(own || themeDark) < 0.5 ? 'dark' : 'light';
  else if (mode === 'auto') delete root.dataset.theme; else root.dataset.theme = mode;
  root.dataset.amoled = store.get('amoled', false) ? '1' : '';
  const dark = root.dataset.theme ? root.dataset.theme === 'dark' : matchMedia('(prefers-color-scheme: dark)').matches;
  // A theme's background and cards are drawn for light or dark: in the other mode the app works them out itself.
  // Colours the rider picked are always used; a theme's own ones only where they suit the mode.
  const fits = (role) => (roles[role] && ((luminance(roles[role]) < 0.5) === dark || picked(role)) ? roles[role] : null);
  const visible = (role) => (roles[role] && !(dark && luminance(roles[role]) < 0.15 && !picked(role)) ? roles[role] : null);
  const bg = own || fits('background');
  if (bg) {
    const mix = (to, pct) => `color-mix(in srgb, ${bg} ${100 - pct}%, ${to})`;
    root.style.setProperty('--bg', bg);
    root.style.setProperty('--surface', mix('#fff', dark ? 7 : 50));
    root.style.setProperty('--surface-2', mix(dark ? '#fff' : '#000', 8));
    root.style.setProperty('--line', mix(dark ? '#fff' : '#000', 18));
    root.style.setProperty('--text', dark ? '#E8ECF1' : '#14181D');
    root.style.setProperty('--muted', mix(dark ? '#E8ECF1' : '#14181D', 70));
  }
  const set = (v, hex) => hex && root.style.setProperty(v, hex);
  set('--surface', fits('cards'));
  if (roles.text) { set('--text', roles.text); root.style.setProperty('--muted', `color-mix(in srgb, ${roles.text} 70%, var(--surface))`); }
  const highlight = roles.highlight || accent;
  set('--highlight', highlight); set('--on-highlight', onColor(highlight));
  const bar = visible('bar');
  if (bar) { set('--bar', bar); set('--on-bar', onColor(bar)); }
  const plate = visible('plate');
  if (plate) { set('--plate', plate); set('--on-plate', onColor(plate)); }
  set('--live', roles.live); set('--late', roles.delay); set('--bad', roles.late);
  $('meta[name="theme-color"]').content = getComputedStyle(root).getPropertyValue('--bg').trim() || '#101317';
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
/** Which accounts are saved: sadzv.qrbus.me (every account before 2.13) and/or eshop.idsbbsk.sk. */
const hasSadzv = () => hasAccount() && store.get('sadzvAccount', true);
const hasIds = () => hasAccount() && store.get('idsAccount', false);
const both = () => hasSadzv() && hasIds();
/** The email filled in reports: the primary account's when the two differ. */
const contactEmail = () => (both() && store.get('primaryAccount') === 'ids' ? s.creds?.ids?.email : s.creds?.email || s.creds?.ids?.email) || '';
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
  // A shared bus opens right in the browser: the person it was sent to needn't install anything to see it.
  if (new URLSearchParams(location.search).has('follow')) return false;
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
  afterUnlock();
};
$('#pin-cancel').onclick = () => removeAccount();

/** The passwords are decrypted: sign in to each saved account (or resume). */
function afterUnlock() {
  if (s.creds?.password) { if (s.sessionId) startPolling(); else signIn(s.creds); }
  if (s.creds?.ids && !s.ids.sessionId) idsSignIn(s.creds.ids);
}

/** PIN (the dialog) -> the password is decrypted -> signed in -> the ticket shows. */
function unlockDialog() {
  if (!hasPin() || unlocked() || $('#unlock')) return;
  const d = document.createElement('div');
  d.id = 'unlock';
  d.className = 'dialog-backdrop';
  d.innerHTML = `<form class="dialog pin-dialog stack" role="dialog" aria-modal="true" aria-labelledby="unlock-title">
    <h2 id="unlock-title">${t('lock_title')}</h2>
    <p class="hint small">${t('unlock_hint')}</p>
    <input name="pin" class="pin-input" type="password" inputmode="numeric" pattern="[0-9]*" maxlength="8" autocomplete="off" aria-labelledby="unlock-title" required>
    <p class="error-text" role="alert" hidden></p>
    <div class="pin-actions"><button type="button" class="text-btn" data-cancel>${t('cancel')}</button><button class="btn" type="submit">${t('unlock')}</button></div>
    <button type="button" class="text-btn pin-forgot" data-forgot>${t('lock_forgot')}</button></form>`;
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
    afterUnlock();
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
  const ok = loginKind === 'ids' ? await idsSignIn(creds, { fromForm: true }) : await signIn(creds, { fromForm: true });
  btn.disabled = false;
  btn.textContent = t('login');
  if (ok) $('#password').value = '';
};
let loginKind = 'sadzv';
function setLoginKind(kind) {
  loginKind = kind;
  $$('#login-kind [data-kind]').forEach((b) => b.setAttribute('aria-checked', String(b.dataset.kind === kind)));
  $('#login-sub').textContent = t(kind === 'ids' ? 'login_sub_ids' : 'login_sub');
  $('#email').value = (kind === 'ids' ? s.creds?.ids?.email : s.creds?.email) || '';
}
$('#login-kind').onclick = (e) => { const b = e.target.closest('[data-kind]'); if (b) setLoginKind(b.dataset.kind); };
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
  store.set('sadzvAccount', true);
  s.sessionId = r.json.sessionId;
  if (!hasPin()) {
    // A new account: the password is saved only once a PIN encrypts it.
    s.creds = { ...(s.creds || {}), ...creds };
    setAccount(r.json.account);
    s.serial = r.json.serial || '';
    showPinSetup();
    return true;
  }
  // A new password from the form is encrypted again; after an unlock it is unchanged.
  if (fromForm) await saveCreds({ ...(s.creds || {}), email: creds.email, password: creds.password }); else s.creds = { ...(s.creds || {}), ...creds };
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
  if (s.reauthInFlight || !s.creds?.password) return false;
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
function showLogin({ welcome = false, kind = null } = {}) {
  show('login-view');
  applyI18n($('#login-view'));
  $('#login-title').textContent = welcome ? t('welcome_title') : t('login');
  $('#login-sub').textContent = welcome ? t('welcome_body') : t('login_sub');
  $('#browse-btn').textContent = welcome ? t('welcome_skip') : t('cancel');
  $('#welcome-hint').hidden = !welcome;
  $('#login-error').hidden = true;
  setLoginKind(kind || (hasSadzv() && !hasIds() ? 'ids' : 'sadzv'));
  if (welcome) $('#login-sub').textContent = t('welcome_body');
}

/** Removes the account from this device: password, PIN, card; the app keeps working without it. */
function removeAccount() {
  if (s.sessionId) api('/api/logout', { sessionId: s.sessionId });
  if (s.ids.sessionId) api('/api/ids/logout', { sessionId: s.ids.sessionId });
  s.ids = { sessionId: null, account: null, history: [], qr: null, loading: false, signedOut: false };
  store.set('sadzvAccount', null);
  store.set('idsAccount', null);
  store.set('primaryAccount', null);
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

// ------------------------------------------------------------------ IDS BBSK (EM-0023)

async function idsSignIn(creds, { fromForm } = {}) {
  s.ids.loading = true;
  const r = await api('/api/ids/login', creds);
  s.ids.loading = false;
  if (!r.ok || !r.json.success) {
    if (fromForm) {
      $('#login-error').textContent = r.json.error === 'offline' ? t('ticket_offline_no_code') : r.json.error === 'robot' ? t('ids_robot_check')
        : r.status === 401 ? t('ids_login_rejected') : r.json.error || t('login_failed', r.status);
      $('#login-error').hidden = false;
    }
    else if (r.status === 401) s.ids.signedOut = true;
    if (s.tab === 'ticket' || s.tab === 'account') selectTab(s.tab);
    return false;
  }
  store.set('onboarded', true);
  store.set('idsAccount', true);
  Object.assign(s.ids, { sessionId: r.json.sessionId, account: r.json.account, history: r.json.history || [], signedOut: false });
  if (!hasPin()) {
    // The first account is IDS BBSK only: the password is saved once a PIN encrypts it.
    store.set('sadzvAccount', false);
    s.creds = { ...(s.creds || {}), ids: creds };
    showPinSetup();
  } else {
    if (fromForm) await saveCreds({ ...(s.creds || {}), ids: creds });
    if (fromForm) showApp(); else if (['ticket', 'account', 'history'].includes(s.tab)) selectTab(s.tab);
  }
  loadIdsQr();
  return true;
}

/** The virtual card's QR code: static, so once per sign-in (kept in memory only). */
async function loadIdsQr() {
  const qrId = s.ids.account?.identifiers?.find((i) => i.qrId)?.qrId;
  if (!qrId || s.ids.qr || !s.ids.sessionId) return;
  const r = await api('/api/ids/qr', { sessionId: s.ids.sessionId, qrId });
  if (r.ok && r.json.image) s.ids.qr = r.json.image;
  if (s.tab === 'ticket') renderTicket($('#tab-ticket'));
}

async function refreshIds() {
  if (!s.ids.sessionId) return s.creds?.ids && idsSignIn(s.creds.ids);
  const r = await api('/api/ids/account', { sessionId: s.ids.sessionId });
  if (r.status === 401) { s.ids.sessionId = null; return s.creds?.ids && idsSignIn(s.creds.ids); }
  if (r.ok) Object.assign(s.ids, { account: r.json.account, history: r.json.history || [] });
  loadIdsQr();
}

/** Removes one of two accounts; the last one goes with the PIN (removeAccount). */
async function removeOne(wallet) {
  if (!both()) return removeAccount();
  if (wallet === 'ids') {
    if (s.ids.sessionId) api('/api/ids/logout', { sessionId: s.ids.sessionId });
    s.ids = { sessionId: null, account: null, history: [], qr: null, loading: false, signedOut: false };
    store.set('idsAccount', false);
    const { ids, ...rest } = s.creds || {};
    await saveCreds(rest);
  } else {
    if (s.sessionId) api('/api/logout', { sessionId: s.sessionId });
    stopPolling();
    Object.assign(s, { sessionId: null, account: null, lastBase64: null, history: null, serial: '' });
    store.set('sadzvAccount', false);
    await saveCreds({ ids: s.creds?.ids });
  }
  store.set('primaryAccount', null);
  s.accountWallet = hasIds() ? 'ids' : 'sadzv';
  showApp();
}

/** Two accounts under different emails: which one goes on reports (asked once). */
function askPrimary(force = false) {
  const a = s.creds?.email, b = s.creds?.ids?.email;
  if (!both() || !a || !b || $('#primary') || (!force && (store.get('primaryAccount') || a.toLowerCase() === b.toLowerCase()))) return;
  const d = document.createElement('div');
  d.id = 'primary';
  d.className = 'dialog-backdrop';
  d.innerHTML = `<div class="dialog stack" role="dialog" aria-modal="true"><h2>${t('primary_title')}</h2><p class="hint small">${t('primary_body')}</p>
    <button class="btn outline wide" data-primary="sadzv">${esc(a)}<br><small>SAD Zvolen</small></button>
    <button class="btn outline wide" data-primary="ids">${esc(b)}<br><small>IDS BBSK</small></button></div>`;
  d.onclick = (e) => { const x = e.target.closest('[data-primary]'); if (x) { store.set('primaryAccount', x.dataset.primary); d.remove(); if (s.tab === 'account') renderAccount($('#tab-account')); } };
  document.body.appendChild(d);
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
  if (unlocked()) askPrimary();
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

/** The tickets on the slider, and which one shows: the followed bus decides (city bus: SAD Zvolen, IDS line: IDS BBSK). */
const wallets = () => [hasSadzv() && 'sadzv', hasIds() && 'ids'].filter(Boolean);
function shownWallet() {
  const list = wallets();
  const line = live.followedLine();
  const ruled = store.get('ticketAuto', true) ? walletForLine(line) : null;
  // A new deciding bus overrides a hand-picked ticket; swiping still works until the next one.
  if (ruled && line !== s.ruledLine) { s.ruledLine = line; s.wallet = null; }
  const pick = s.wallet || (ruled && list.includes(ruled) ? ruled : store.get('ticketDefault', 'sadzv'));
  return list.includes(pick) ? pick : list[0];
}

function idsPassHtml() {
  const card = s.ids.account?.identifiers?.find((i) => i.qrId);
  const body = s.ids.qr
    ? `<img class="ids-qr" alt="${esc(t('ticket_fullscreen'))}" src="${esc(s.ids.qr)}">`
    : `<span class="qr-placeholder">${s.ids.signedOut ? t('ids_signed_out') : !s.ids.account ? t('ticket_loading') : !card ? t('ids_no_virtual_card') : t('ids_qr_missing')}</span>`;
  return `<section class="ticket"><button class="qr-wrap" id="ids-qr-btn" aria-label="${esc(t('ticket_fullscreen'))}">${body}</button>
    <div class="stub"><p class="status">${esc(card?.alias || 'IDS BBSK')}</p></div></section>`;
}

function renderTicket(el) {
  const cards = s.account?.cards || [];
  const open = unlocked();
  const list = wallets(), shown = shownWallet();
  const lip = (w) => (list.length > 1 ? `<span class="lip ${w}">${w === 'ids' ? 'IDS BBSK' : 'SAD Zvolen'}</span>` : '');
  el.innerHTML = `
    <header class="screen-head"><div><h1>${esc(open && s.account?.userName ? s.account.userName.split(' ')[0] : hasAccount() ? t('tab_ticket') : 'Plan&Ride')}</h1><p class="hint small" id="card-type"></p></div></header>
    <div id="banners"></div>
    ${!hasAccount() ? '' : !open
      ? `<button class="locked-stub" data-unlock><span class="locked-icon" aria-hidden="true">▦</span><span class="row-text"><span class="row-title">${t('ticket_locked_title')}</span><span class="row-sub">${t('ticket_locked_body')}</span></span><span aria-hidden="true">🔒</span></button>`
      : `${list.length > 1 ? `<div class="segmented" id="wallet-switch" role="radiogroup">${list.map((w) => `<button role="radio" aria-checked="${w === shown}" data-wallet="${w}">${w === 'ids' ? 'IDS BBSK' : 'SAD Zvolen'}</button>`).join('')}</div>
      <p class="hint small pad">${walletForLine(live.followedLine()) === shown && store.get('ticketAuto', true) ? esc(t('ticket_auto_for_line', live.followedLine())) : t('ticket_swipe_hint')}</p>` : ''}
    <div class="passes" id="passes">
    ${hasSadzv() ? `<div class="pass" data-pass="sadzv">${lip('sadzv')}
    ${cards.length > 1 ? `<div class="segmented card-switch" role="radiogroup" aria-label="${esc(t('ticket_card'))}">${cards.map((c) => `<button role="radio" aria-checked="${c.serialNumber === s.serial}" data-card="${esc(c.serialNumber)}">${esc(c.cardTypeName || c.serialNumber)}</button>`).join('')}</div>` : ''}
    <section class="ticket">
      <button class="qr-wrap" id="qr-btn" aria-label="${esc(t('ticket_fullscreen'))}"><div id="qr" class="qr"></div><span id="qr-placeholder" class="qr-placeholder">${t('ticket_loading')}</span></button>
      <div class="stub">
        <div class="refresh-bar"><span id="refresh-fill"></span></div>
        <p id="status" class="status" role="status">${t('ticket_connecting')}</p>
        <p id="validity" class="hint small"></p>
      </div>
    </section></div>` : ''}
    ${hasIds() ? `<div class="pass" data-pass="ids">${lip('ids')}${idsPassHtml()}</div>` : ''}
    </div>`}
    <div id="follow-slot-ticket"></div>
    <div id="journeys">${journeysHtml()}</div>
    <div id="rides-next">${ridesHtml()}</div>
    <div id="preview"></div>
    ${hasAccount() ? '' : `<div class="notice">${t('home_add_account_hint')} <button class="text-btn" data-add-account>${t('account_add')}</button></div>`}`;
  el.onclick = (e) => {
    const c = e.target.closest('[data-card]');
    if (c) { selectCard(c.dataset.card); renderTicket(el); }
    if (e.target.closest('#qr-btn') && s.lastBase64) openFullscreenQr();
    if (e.target.closest('#ids-qr-btn') && s.ids.qr) openFullscreenQr(s.ids.qr);
    const w = e.target.closest('[data-wallet]');
    if (w) { s.wallet = w.dataset.wallet; scrollToPass(el, true); }
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
  if (open) { renderBanners(); updateStatus(); scrollToPass(el, false); }
  // Swiping the passes: the switch follows (a hand-picked ticket until the next deciding bus).
  const passes = $('#passes', el);
  if (passes && list.length > 1) {
    passes.onscroll = () => {
      clearTimeout(passes._t);
      passes._t = setTimeout(() => {
        const w = list[Math.round(passes.scrollLeft / passes.clientWidth)] || list[0];
        if (w !== shownWallet()) s.wallet = w;
        $$('#wallet-switch [data-wallet]', el).forEach((b) => b.setAttribute('aria-checked', String(b.dataset.wallet === w)));
      }, 120);
    };
  }
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
  const low = card.creditLastBalance != null && card.creditLastBalance < threshold;
  if (store.get('expiryWarn', true)) {
    for (const [label, to] of [[t('ticket'), card.ticketValidTo], [t('card'), card.cardValidTo], [t('discount'), card.discountValidTo]]) {
      if (!to) continue;
      const days = Math.floor((toMs(to) - Date.now()) / 86400000);
      if (days < 0) out.push(t('expired', label));
      else if (days <= 7) out.push(t('expires_soon', label, days === 0 ? t('today') : t('days_left', days)));
    }
  }
  el.innerHTML = (low ? `<div class="notice warn">${esc(t('low_credit', money(card.creditLastBalance, card.currencySymbol)))}
      <button class="text-btn" data-topup-now>${t('topup')}</button></div>` : '') +
    out.map((m) => `<div class="notice warn">${esc(m)}</div>`).join('');
  el.onclick = (e) => { if (e.target.closest('[data-topup-now]')) openTopUp(); };
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

function scrollToPass(el, smooth) {
  const passes = $('#passes', el);
  const i = wallets().indexOf(shownWallet());
  if (passes && i >= 0) passes.scrollTo({ left: i * passes.clientWidth, behavior: smooth ? 'smooth' : 'instant' });
}

/** A ticket's code over the whole screen: the SAD Zvolen one drawn here, or the IDS BBSK picture ([image]). */
function openFullscreenQr(image) {
  const o = document.createElement('div');
  o.className = 'qr-full';
  o.innerHTML = `<div class="qr-full-code"></div><p>${t('ticket_fullscreen_close')}</p>`;
  if (image) {
    $('.qr-full-code', o).innerHTML = `<img class="ids-qr" alt="" src="${esc(image)}">`;
    o.onclick = () => o.remove();
    document.body.appendChild(o);
    return;
  }
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

/** The history shown: SAD Zvolen, IDS BBSK, or both merged (the selector on top with two accounts). */
function historyItems() {
  const which = both() ? store.get('historyWallet', null) : hasIds() && !hasSadzv() ? 'ids' : 'sadzv';
  const sad = hasSadzv() ? s.history || [] : [];
  const ids = (s.ids.history || []).map((x) => ({ ...x }));
  if (which === 'sadzv') return sad;
  if (which === 'ids') return ids;
  return [...sad.map((x) => ({ ...x, wallet: 'sadzv' })), ...ids].sort((a, b) => b.timestampMs - a.timestampMs);
}

async function renderHistory(el) {
  const which = both() ? store.get('historyWallet', null) : null;
  const items = historyItems();
  el.innerHTML = `<header class="screen-head"><h1>${t('history_title')}</h1>
    <span><button class="text-btn" data-csv ${items.length ? '' : 'disabled'}>${t('export_csv')}</button><button class="text-btn" data-reload>${t('refresh')}</button></span></header>
    ${both() ? `<div class="segmented" role="radiogroup">${[[null, t('wallet_both')], ['sadzv', 'SAD Zvolen'], ['ids', 'IDS BBSK']].map(([w, label]) => `<button role="radio" aria-checked="${w === which}" data-hist="${w ?? ''}">${esc(label)}</button>`).join('')}</div>` : ''}
    <div id="hist-body">${s.history || !hasSadzv() ? '' : '<p class="hint"><span class="spinner"></span></p>'}</div>`;
  el.onclick = (e) => {
    if (e.target.closest('[data-reload]')) { s.history = null; refreshIds(); renderHistory(el); }
    if (e.target.closest('[data-csv]')) exportCsv();
    const h = e.target.closest('[data-hist]');
    if (h) { store.set('historyWallet', h.dataset.hist || null); renderHistory(el); }
  };
  if (!s.history && hasSadzv()) {
    const r = await api('/api/history', { sessionId: s.sessionId, limit: 100 });
    if (r.status === 401 && await reauth()) return renderHistory(el);
    s.historyError = !r.ok;
    s.history = r.ok ? r.json.items || [] : null;
    if (s.tab === 'history') renderHistory(el);
    return;
  }
  const body = $('#hist-body', el);
  if (s.historyError && which !== 'ids') { body.innerHTML = `<div class="notice error">${t('history_failed')}</div>`; return; }
  if (!items.length) { body.innerHTML = `<div class="empty"><p>${t('history_empty')}</p></div>`; return; }
  const card = selectedCard();
  const balance = which === 'ids' ? s.ids.account?.credit ?? null : which === 'sadzv' || !hasIds() ? card?.creditLastBalance ?? null
    : (card?.creditLastBalance ?? 0) + (s.ids.account?.credit ?? 0);
  const ins = historyInsights(items, balance);
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
    ${monthGroups(items).map(([key, list]) => `<h2 class="section-label">${esc(monthLabel(key))}</h2>${monthLine(list)}
    <ul class="list history">${list.map((it) => {
      const amt = (it.amountText || '').trim();
      const cls = amt.startsWith('+') ? 'pos' : amt.startsWith('-') ? 'neg' : '';
      // Real UTC timestamps, shown in Slovak time like the Android app.
      const when = `${dateText(it.timestampMs)} ${clock(it.timestampMs)}`;
      // Merged: which account each line is from.
      const tag = which === null && both() ? ` · ${it.wallet === 'ids' ? 'IDS BBSK' : 'SAD Zvolen'}` : '';
      return `<li class="row"><span class="row-text"><span class="row-title">${esc(it.title)}</span><span class="row-sub">${esc(when)}${it.subtitle ? ' · ' + esc(it.subtitle) : ''}${esc(tag)}</span></span>${amt ? `<b class="amount ${cls}">${esc(amt)}</b>` : ''}</li>`;
    }).join('')}</ul>`).join('')}`;
}

/** History split by month (Slovak time), newest first: [["2026-10", items], …]. */
function monthGroups(items) {
  const groups = new Map();
  for (const it of items) {
    const key = new Intl.DateTimeFormat('en-CA', { timeZone: 'Europe/Bratislava', year: 'numeric', month: '2-digit' }).format(new Date(it.timestampMs)).slice(0, 7);
    if (!groups.has(key)) groups.set(key, []);
    groups.get(key).push(it);
  }
  return [...groups];
}
function monthLabel(key) {
  const [y, m] = key.split('-').map(Number);
  const name = new Date(y, m - 1, 1).toLocaleDateString(lang() === 'en' ? 'en-GB' : 'sk-SK', { month: 'long' });
  return (name.charAt(0).toUpperCase() + name.slice(1)) + (y === new Date().getFullYear() ? '' : ` ${y}`);
}
/** "12 rides · spent 18,40 € · added 20,00 €" for a month. */
function monthLine(list) {
  const rides = list.filter((x) => x.sourceType === 'TICKET' && !x.isTopUp && (x.amountCents ?? 0) < 0).length;
  const spent = list.filter((x) => !x.isTopUp && (x.amountCents ?? 0) < 0).reduce((a, x) => a - x.amountCents, 0);
  const added = list.filter((x) => x.isTopUp && (x.amountCents ?? 0) > 0).reduce((a, x) => a + x.amountCents, 0);
  const parts = [rides ? (rides === 1 ? t('trip_count_1') : t('trips_count', rides)) : '', spent ? t('month_spent', money(spent / 100)) : '',
    added ? t('month_added', money(added / 100)) : ''].filter(Boolean);
  return parts.length ? `<p class="hint small month-line">${esc(parts.join(' · '))}</p>` : '';
}

function exportCsv() {
  const blob = new Blob(['﻿' + historyToCsv(historyItems())], { type: 'text/csv' });
  const file = new File([blob], `testem-history-${new Date().toISOString().slice(0, 10)}.csv`, { type: 'text/csv' });
  if (navigator.canShare?.({ files: [file] })) { navigator.share({ files: [file] }).catch(() => {}); return; }
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = file.name;
  a.click();
  toast(t('exported'));
}

// ------------------------------------------------------------------ account tab

function idsAccountHtml() {
  const a = s.ids.account;
  if (!a) return `<div class="notice">${s.ids.signedOut ? t('ids_signed_out') : '<span class="spinner"></span>'}</div>`;
  const now = Date.now();
  const valid = (a.tickets || []).filter((x) => x.valid && (!x.validToMs || x.validToMs > now));
  const days = (to) => Math.floor((to - now) / 86400000);
  return `<div class="big-stat"><span class="hint">${t('balance')}</span><strong>${a.credit != null ? money(a.credit) : '—'}</strong>
      <span class="hint small">${esc(a.creditNote || '')}</span></div>
    <p class="pad"><a class="btn outline" href="https://eshop.idsbbsk.sk/praca-s-kreditom" target="_blank" rel="noopener">${t('ids_open_shop')}</a></p>
    <h2 class="section-label">${t('validity')}</h2><ul class="list">${valid.length ? valid.map((x) => `<li class="row"><span class="row-text"><span class="row-title">${esc(x.name)}</span>
      <span class="row-sub">${dateText(x.validFromMs)} – ${dateText(x.validToMs)} · ${esc(x.identifier)}</span></span><b class="days">${t('days_left', days(x.validToMs))}</b></li>`).join('')
      : `<li class="row"><span class="row-title">${t('ids_no_ticket')}</span></li>`}</ul>
    <h2 class="section-label">${t('ids_identifiers')}</h2><ul class="list">${(a.identifiers || []).map((i) => `<li class="row"><span class="row-text">
      <span class="row-title">${esc(i.alias || t('ids_identifier', i.number))}${i.qrId ? ' ▦' : ''}</span><span class="row-sub">${esc([i.cardNumber, i.products.join(', ')].filter(Boolean).join(' · '))}</span></span></li>`).join('')}</ul>`;
}

/** Credit top-up (SAD Zvolen): the amount here, the payment on the carrier's gateway; the money never goes through us. */
async function openTopUp() {
  const body = openPage(t('topup_title'));
  body.innerHTML = '<p class="hint pad"><span class="spinner"></span></p>';
  const r = await api('/api/topup/limits', { sessionId: s.sessionId });
  if (!r.ok) { body.innerHTML = `<div class="notice error">${t('topup_error')}</div>`; return; }
  const { tariffId, minCents, maxCents, defaultCents } = r.json;
  const chips = [5, 10, 20, 50].filter((e) => e * 100 >= minCents && e * 100 <= maxCents);
  body.innerHTML = `<div class="stack pad">
      <div class="chips">${chips.map((e) => `<button class="chip" data-eur="${e}">${e} €</button>`).join('')}</div>
      <label class="field"><span>${t('topup_amount')}</span><input id="topup-eur" type="number" inputmode="numeric" min="${minCents / 100}" max="${maxCents / 100}" value="${defaultCents / 100}"></label>
      <p class="hint small">${t('topup_range', minCents / 100, maxCents / 100)}</p>
      <p class="hint small">🔒 ${t('topup_who')}</p>
      <p class="hint small">${t('topup_after')}</p>
      <button class="btn wide" id="topup-pay"></button><p class="error-text" hidden></p></div>`;
  const input = $('#topup-eur', body), pay = $('#topup-pay', body);
  const update = () => { const v = Number(input.value); pay.disabled = !(v * 100 >= minCents && v * 100 <= maxCents); pay.textContent = t('topup_pay', money(v || 0)); };
  update();
  input.oninput = update;
  body.onclick = async (e) => {
    const chip = e.target.closest('[data-eur]');
    if (chip) { input.value = chip.dataset.eur; update(); }
    if (e.target !== pay || pay.disabled) return;
    pay.disabled = true;
    const start = await api('/api/topup/start', { sessionId: s.sessionId, tariffId, cents: Math.round(Number(input.value) * 100) });
    if (!start.ok) { $('.error-text', body).textContent = t('topup_error'); $('.error-text', body).hidden = false; pay.disabled = false; return; }
    // The carrier's signed order to its gateway, as its website does.
    const form = document.createElement('form');
    form.method = 'post';
    form.action = start.json.gatewayUrl;
    for (const f of start.json.fields) { const i = document.createElement('input'); i.type = 'hidden'; i.name = f.name; i.value = f.value; form.appendChild(i); }
    document.body.appendChild(form);
    form.submit();
  };
}

/** Settings, the other account, ticket settings and removal: under either account's view. */
function accountRestHtml(open, view) {
  const link = (page, title, hint) => `<li><button class="row link" data-page="${page}"><span class="row-text"><span class="row-title">${esc(title)}</span>${hint ? `<span class="row-sub">${esc(hint)}</span>` : ''}</span><span aria-hidden="true">›</span></button></li>`;
  const other = hasAccount() && open && !both() ? (hasSadzv() ? 'ids' : 'sadzv') : null;
  const name = (w) => (w === 'ids' ? 'IDS BBSK' : 'SAD Zvolen');
  return `${other ? `<h2 class="section-label">${t('account_add_other')}</h2><ul class="list"><li><button class="row link" data-add-other="${other}"><span class="row-text"><span class="row-title">${name(other)}</span>
      <span class="row-sub">${t(other === 'ids' ? 'wallet_ids_covers' : 'wallet_sadzv_covers')}</span></span><span aria-hidden="true">›</span></button></li></ul>` : ''}
    ${open && both() ? `<h2 class="section-label">${t('tickets_settings')}</h2><ul class="list">${switchRow('ticket-auto', t('ticket_auto_switch'), t('ticket_auto_switch_hint'), store.get('ticketAuto', true))}
      <li><button class="row link" data-ticket-default><span class="row-text"><span class="row-title">${t('ticket_default')}</span><span class="row-sub">${name(store.get('ticketDefault', 'sadzv'))}</span></span><span aria-hidden="true">⇄</span></button></li>
      ${s.creds?.email && s.creds?.ids?.email && s.creds.email.toLowerCase() !== s.creds.ids.email.toLowerCase() ? `<li><button class="row link" data-primary-ask><span class="row-text"><span class="row-title">${t('primary_setting')}</span><span class="row-sub">${esc(contactEmail())}</span></span><span aria-hidden="true">›</span></button></li>` : ''}</ul>` : ''}
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
    ${hasAccount() ? `<ul class="list">${open ? `<li><button class="row link" data-lock><span class="row-title">${t('lock_now')}</span></button></li>` : ''}<li><button class="row link danger" data-remove-account="${view}"><span class="row-text"><span class="row-title">${both() && open ? esc(t('account_remove_which', name(view))) : t('account_remove')}</span><span class="row-sub">${t('account_remove_hint')}</span></span></button></li></ul>` : ''}`;
}

/** Clicks in accountRestHtml; true when handled. */
function accountRestClick(e, el) {
  const open = unlocked();
  const page = e.target.closest('[data-page]');
  // Security needs the PIN first.
  if (page) { page.dataset.page === 'security' && !open ? unlockDialog() : openSettings(page.dataset.page); return true; }
  if (e.target.closest('[data-add-account]')) { showLogin(); return true; }
  const other = e.target.closest('[data-add-other]');
  if (other) { showLogin({ kind: other.dataset.addOther }); return true; }
  if (e.target.closest('[data-unlock]')) { unlockDialog(); return true; }
  if (e.target.closest('[data-lock]')) { lockNow(); return true; }
  // Removing needs the PIN too, so someone holding the phone can't wipe it.
  const remove = e.target.closest('[data-remove-account]');
  if (remove) { if (!open) unlockDialog(); else if (confirm(t('remove_confirm'))) removeOne(remove.dataset.removeAccount); return true; }
  if (e.target.closest('[data-support]')) { showSupport(); return true; }
  if (e.target.closest('[data-ticket-default]')) { store.set('ticketDefault', store.get('ticketDefault', 'sadzv') === 'ids' ? 'sadzv' : 'ids'); renderAccount(el); return true; }
  if (e.target.closest('[data-primary-ask]')) { askPrimary(true); return true; }
  if (e.target.id === 'ticket-auto') { store.set('ticketAuto', e.target.checked); return true; }
  return false;
}

function renderAccount(el) {
  const open = unlocked();
  const view = both() ? s.accountWallet : hasIds() && !hasSadzv() ? 'ids' : 'sadzv';
  if (open && view === 'ids') {
    const name = s.ids.account?.name || 'IDS BBSK';
    el.innerHTML = `<header class="screen-head"><div><h1>${esc(name)}</h1><p class="hint small">${esc(s.creds?.ids?.email || '')}</p></div>
      <button class="text-btn" data-refresh-ids>${t('refresh')}</button></header>
      ${both() ? `<div class="segmented" role="radiogroup">${['sadzv', 'ids'].map((w) => `<button role="radio" aria-checked="${w === view}" data-awallet="${w}">${w === 'ids' ? 'IDS BBSK' : 'SAD Zvolen'}</button>`).join('')}</div>` : ''}
      ${idsAccountHtml()}<div id="acct-rest"></div>`;
    el.onclick = (e) => {
      const w = e.target.closest('[data-awallet]');
      if (w) { s.accountWallet = w.dataset.awallet; renderAccount(el); }
      if (e.target.closest('[data-refresh-ids]')) refreshIds().then(() => renderAccount(el));
      accountRestClick(e, el);
    };
    $('#acct-rest', el).innerHTML = accountRestHtml(open, 'ids');
    return;
  }
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
    ${card?.creditLastBalance != null ? `<div class="big-stat"><span class="hint">${t('balance')}</span><strong>${money(card.creditLastBalance, card.currencySymbol)}</strong><span class="hint small">${esc(card.cardTypeName || '')}</span>
      <span><button class="btn" data-topup>${t('topup')}</button></span></div>` : ''}
    ${card ? `<h2 class="section-label">${t('validity')}</h2><ul class="list">${validity(t('ticket'), card.ticketValidFrom, card.ticketValidTo)}${validity(t('card'), card.cardValidFrom, card.cardValidTo)}${card.discountValidTo ? validity(t('discount'), card.discountValidFrom, card.discountValidTo) : ''}</ul>` : ''}
    ${cards.length > 1 ? `<h2 class="section-label">${t('cards')}</h2><ul class="list">${cards.map((c) => `<li><label class="row"><span class="row-text"><span class="row-title">${esc(c.cardTypeName || c.serialNumber)}</span><span class="row-sub">${esc([c.organizationName, c.creditLastBalance != null ? money(c.creditLastBalance, c.currencySymbol) : ''].filter(Boolean).join(', '))}</span></span><input type="radio" name="card" value="${esc(c.serialNumber)}" ${c.serialNumber === s.serial ? 'checked' : ''}></label></li>`).join('')}</ul>` : ''}
    ${card ? `<h2 class="section-label">${t('details')}</h2><ul class="list">${card.organizationName ? `<li class="row"><span class="row-text"><span class="row-title">${t('organization')}</span><span class="row-sub">${esc(card.organizationName)}</span></span></li>` : ''}<li class="row"><span class="row-text"><span class="row-title">${t('card_number')}</span><span class="row-sub">${esc(card.serialNumber || s.serial)}</span></span></li></ul>` : ''}`;
  el.innerHTML = `
    <header class="screen-head"><div><h1>${esc(open ? s.account?.userName || t('account_title') : hasAccount() ? t('account_title') : t('settings'))}</h1><p class="hint small">${esc(open ? s.creds?.email || '' : '')}</p></div>
      ${open ? `<button class="text-btn" data-refresh-account>${t('refresh')}</button>` : ''}</header>
    ${open && both() ? `<div class="segmented" role="radiogroup">${['sadzv', 'ids'].map((w) => `<button role="radio" aria-checked="${w === view}" data-awallet="${w}">${w === 'ids' ? 'IDS BBSK' : 'SAD Zvolen'}</button>`).join('')}</div>` : ''}
    ${account}
    ${accountRestHtml(open, 'sadzv')}
`;
  el.onclick = async (e) => {
    const w = e.target.closest('[data-awallet]');
    if (w) { s.accountWallet = w.dataset.awallet; return renderAccount(el); }
    if (e.target.closest('[data-topup]')) return openTopUp();
    if (accountRestClick(e, el)) return;
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
  const theme = currentTheme();
  const id = theme.id;
  const preset = PRESETS.find((x) => x.id === id);
  const own = (store.get('themeRoles', {}) || {})[id] || {};
  const colourOf = (role) => (role === 'primary' ? theme.accent : theme.roles[role] || null);
  // Every single colour of the selected theme; a tap opens the picker, the cross goes back to the theme's own.
  const everyColour = `<h2 class="section-label">${t('colors_every_title')}</h2>
    <ul class="list">${ROLES.map((role) => {
      const c = colourOf(role);
      return `<li><div class="row"><button type="button" class="swatch-btn" data-role="${role}" style="background:${c ? esc(c) : 'transparent'}" aria-label="${esc(t('color_role_' + role))}"></button>
        <span class="row-main"><span class="row-title">${t('color_role_' + role)}</span><span class="row-sub">${c ? esc(c.toUpperCase()) : t('color_auto')}</span></span>
        ${role in own ? `<button type="button" class="icon-btn" data-role-reset="${role}" aria-label="${esc(t('color_role_reset'))}">✕</button>` : ''}</div></li>`;
    }).join('')}</ul>
    <p class="hint small pad">${t('colors_every_hint')} ${preset && Object.keys(own).length ? `<button class="text-btn" data-theme-reset>${t('theme_reset')}</button>` : ''}</p>`;
  const bgNow = store.get('bgColor', null);
  body.innerHTML = `<h2 class="section-label">${t('theme_colours')}</h2>
    <ul class="list">${PRESETS.map((p) => `<li><label class="row"><span class="swatch" style="background:${p.color}"></span><span class="row-title">${esc(p.name)}</span><input type="radio" name="theme" value="${p.id}" ${id === p.id ? 'checked' : ''}></label></li>`).join('')}
      <li><label class="row"><button type="button" class="swatch-btn" data-pick-theme style="background:${esc(store.get('themeColor', '#157A38'))}" aria-label="${esc(t('theme_custom'))}"></button><span class="row-title">${t('theme_custom')}</span><input type="radio" name="theme" value="custom" ${id === 'custom' ? 'checked' : ''}></label></li></ul>
    ${everyColour}
    <h2 class="section-label">${t('theme_mode')}</h2>
    <ul class="list">${['auto', 'light', 'dark'].map((m) => `<li><label class="row"><span class="row-title">${t('mode_' + m)}</span><input type="radio" name="mode" value="${m}" ${store.get('themeMode', 'auto') === m ? 'checked' : ''} ${bgNow ? 'disabled' : ''}></label></li>`).join('')}</ul>
    <h2 class="section-label">${t('background_title')}</h2>
    <div class="bg-choices">${BACKGROUNDS.map((c) => `<button class="swatch-btn ${bgNow === c ? 'on' : ''}" data-bg="${c}" style="background:${c}" aria-label="${c}"></button>`).join('')}
      <button type="button" class="swatch-btn custom ${bgNow && !BACKGROUNDS.includes(bgNow) ? 'on' : ''}" data-pick-bg aria-label="${esc(t('theme_custom'))}">${bgNow && !BACKGROUNDS.includes(bgNow) ? `<i style="background:${esc(bgNow)}"></i>` : ''}</button></div>
    <p class="hint small">${t('background_hint')} ${bgNow ? `<button class="text-btn" data-bg-reset>${t('background_reset')}</button>` : ''}</p>
    <ul class="list">${switchRow('amoled', t('amoled'), t('amoled_hint'), store.get('amoled', false))}</ul>`;
  body.onchange = (e) => {
    if (e.target.name === 'theme') store.set('themeId', e.target.value);
    if (e.target.name === 'mode') store.set('themeMode', e.target.value);
    if (e.target.id === 'amoled') store.set('amoled', e.target.checked);
    applyTheme();
    if (e.target.name === 'theme') appearance(body);
  };
  body.onclick = async (e) => {
    // EM-0049: our own colour picker; nothing changes until "Use" (iPhone's applied every slider move).
    const role = e.target.closest('[data-role]')?.dataset.role;
    if (role) {
      const c = await pickColor(colourOf(role) || theme.accent, t('color_role_' + role));
      if (c) { setThemeColor(role, c); applyTheme(); appearance(body); }
      return;
    }
    const resetRole = e.target.closest('[data-role-reset]')?.dataset.roleReset;
    if (resetRole) { setThemeColor(resetRole, null); applyTheme(); appearance(body); return; }
    if (e.target.closest('[data-theme-reset]')) {
      const all = { ...(store.get('themeRoles', {}) || {}) };
      delete all[id];
      store.set('themeRoles', all); applyTheme(); appearance(body); return;
    }
    if (e.target.closest('[data-pick-bg]')) {
      const c = await pickColor(bgNow || '#1C2433', t('background_title'));
      if (c) { store.set('bgColor', c); applyTheme(); appearance(body); }
      return;
    }
    if (e.target.closest('[data-pick-theme]')) {
      e.preventDefault();
      const c = await pickColor(store.get('themeColor', '#157A38'), t('theme_custom'));
      if (c) { store.set('themeColor', c); store.set('themeId', 'custom'); applyTheme(); appearance(body); }
      return;
    }
    const b = e.target.closest('[data-bg]');
    if (b) store.set('bgColor', b.dataset.bg);
    if (e.target.closest('[data-bg-reset]')) store.set('bgColor', null);
    if (b || e.target.closest('[data-bg-reset]')) { applyTheme(); appearance(body); }
  };
}

/**
 * A colour picker of our own (EM-0049): ready colours, a saturation/brightness square and a hue bar to drag, and the
 * code as hex or R, G, B. Resolves with "#rrggbb" on "Use", null on cancel; nothing is applied before.
 */
function pickColor(initial, title) {
  const READY = ['#FFFFFF', '#F3EEE4', '#E8F0E6', '#E6EEF7', '#F6E8EC', '#EDE7F6', '#1C2433', '#1E2A22', '#2B1F2E', '#263238', '#121212', '#000000',
    '#1D4FB8', '#0F766E', '#15803D', '#E0A100', '#EA580C', '#B91C1C', '#BE185D', '#7C3AED'];
  const toHsv = (hex) => {
    const [r, g, b] = [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16) / 255);
    const max = Math.max(r, g, b), d = max - Math.min(r, g, b);
    const h = d === 0 ? 0 : max === r ? ((g - b) / d) % 6 : max === g ? (b - r) / d + 2 : (r - g) / d + 4;
    return [(h * 60 + 360) % 360, max ? d / max : 0, max];
  };
  const toHex = (h, sat, v) => {
    const f = (n) => { const k = (n + h / 60) % 6; return v - v * sat * Math.max(0, Math.min(k, 4 - k, 1)); };
    return '#' + [f(5), f(3), f(1)].map((x) => Math.round(x * 255).toString(16).padStart(2, '0')).join('').toUpperCase();
  };
  const valid = (x) => /^#?[0-9a-f]{6}$/i.test(x.trim());
  let [h, sat, v] = toHsv(valid(initial) ? initial : '#1D4FB8');
  return new Promise((resolve) => {
    const d = document.createElement('div');
    d.className = 'dialog-backdrop';
    d.innerHTML = `<div class="dialog color-dialog" role="dialog" aria-modal="true" aria-labelledby="cp-title">
      <h2 id="cp-title">${esc(title)}</h2>
      <div class="cp-ready">${READY.map((c) => `<button type="button" class="swatch-btn" data-c="${c}" style="background:${c}" aria-label="${c}"></button>`).join('')}</div>
      <div class="cp-sv"><i class="cp-dot"></i></div>
      <div class="cp-hue"><i class="cp-dot"></i></div>
      <div class="cp-row"><span class="cp-preview"></span><label class="field"><span>${t('color_hex')}</span><input id="cp-hex" maxlength="7" autocomplete="off" autocapitalize="characters"></label></div>
      <div class="cp-row">${['R', 'G', 'B'].map((x) => `<label class="field"><span>${x}</span><input data-rgb="${x}" inputmode="numeric" maxlength="3"></label>`).join('')}</div>
      <div class="pin-actions"><button type="button" class="text-btn" data-cancel>${t('cancel')}</button><button type="button" class="btn" data-use>${t('color_use')}</button></div></div>`;
    document.body.appendChild(d);
    const sv = $('.cp-sv', d), hue = $('.cp-hue', d), hex = $('#cp-hex', d);
    const draw = (from) => {
      const c = toHex(h, sat, v);
      sv.style.background = `linear-gradient(to top, #000, transparent), linear-gradient(to right, #fff, ${toHex(h, 1, 1)})`;
      $('.cp-dot', sv).style.cssText = `left:${sat * 100}%;top:${(1 - v) * 100}%`;
      $('.cp-dot', hue).style.cssText = `left:${(h / 360) * 100}%`;
      $('.cp-preview', d).style.background = c;
      if (from !== 'hex') hex.value = c;
      if (from !== 'rgb') d.querySelectorAll('[data-rgb]').forEach((el, i) => (el.value = parseInt(c.slice(1 + i * 2, 3 + i * 2), 16)));
    };
    const drag = (el, move) => {
      const at = (e) => { const r = el.getBoundingClientRect(); move(Math.min(1, Math.max(0, (e.clientX - r.left) / r.width)), Math.min(1, Math.max(0, (e.clientY - r.top) / r.height))); draw(); };
      el.onpointerdown = (e) => { el.setPointerCapture(e.pointerId); at(e); el.onpointermove = at; };
      el.onpointerup = el.onpointercancel = () => { el.onpointermove = null; };
    };
    drag(sv, (x, y) => { sat = x; v = 1 - y; });
    drag(hue, (x) => { h = x * 359.9; });
    hex.oninput = () => { if (valid(hex.value)) { [h, sat, v] = toHsv('#' + hex.value.trim().replace('#', '')); draw('hex'); } };
    d.querySelectorAll('[data-rgb]').forEach((el) => (el.oninput = () => {
      const rgb = [...d.querySelectorAll('[data-rgb]')].map((x) => Math.min(255, Math.max(0, Number(x.value.replace(/\D/g, '')) || 0)));
      [h, sat, v] = toHsv('#' + rgb.map((x) => x.toString(16).padStart(2, '0')).join('')); draw('rgb');
    }));
    const close = (value) => { d.remove(); resolve(value); };
    d.onclick = (e) => {
      const ready = e.target.closest('[data-c]');
      if (ready) { [h, sat, v] = toHsv(ready.dataset.c); draw(); }
      if (e.target === d || e.target.closest('[data-cancel]')) close(null);
      if (e.target.closest('[data-use]')) close(toHex(h, sat, v));
    };
    draw();
  });
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
  const name = unlocked() ? s.account?.userName || s.ids.account?.name || '' : '', email = unlocked() ? contactEmail() : '';
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
  // A bus shared from Plan&Ride (par.karafa.net/f?t=… sends it here): show it, following is the opener's choice.
  const shared = params.get('follow');
  if (shared) {
    try {
      const p = JSON.parse(new TextDecoder().decode(Uint8Array.from(atob(shared.replace(/-/g, '+').replace(/_/g, '/')), (c) => c.charCodeAt(0))));
      if (p?.ref?.line) { selectTab('departures'); live.openTrip(p.ref, { boardingPlatformIds: p.board || [], alightOrder: p.alight ?? null }); }
    } catch { toast(t('share_bad_link')); }
    return;
  }
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

live.shareName.get = () => (unlocked() ? s.account?.userName || s.ids.account?.name || '' : '');

// EM-0048: iOS still pinch-zooms a home-screen app despite the viewport; and after the keyboard closes it can leave the
// page shifted up (a black band under the tab bar). Neither, here.
document.addEventListener('gesturestart', (e) => e.preventDefault());
document.addEventListener('focusout', () => setTimeout(() => { if (!document.activeElement?.matches('input, textarea, select')) window.scrollTo(0, window.scrollY); }, 50));

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
  if (!hasAccount() && !store.get('onboarded', false) && !params.has('follow')) return showLogin({ welcome: true });
  showApp(); // with an account: locked until the PIN, the rest works right away
  handleLaunch(params);
})();
