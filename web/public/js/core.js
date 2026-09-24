// Shared helpers for the web client: DOM, storage, API, formatting, sheets, logs.
import { t, lang } from './i18n.js';

export const VERSION = '2.5.0';
export const $ = (sel, root = document) => root.querySelector(sel);
export const $$ = (sel, root = document) => [...root.querySelectorAll(sel)];
export const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

// ------------------------------------------------------------------ app log (for bug reports)
const logLines = [];
export function log(level, ...parts) {
  const line = `${new Date().toISOString()} ${level} ${parts.map((p) => (p instanceof Error ? p.stack || p.message : typeof p === 'string' ? p : JSON.stringify(p))).join(' ')}`;
  logLines.push(line.slice(0, 2000));
  if (logLines.length > 400) logLines.shift();
}
addEventListener('error', (e) => log('E', 'uncaught', e.error || e.message));
addEventListener('unhandledrejection', (e) => log('E', 'unhandled rejection', e.reason));
export const collectLogs = () =>
  `testEM web ${VERSION}\n${navigator.userAgent}\nlanguage ${lang()}, standalone ${matchMedia('(display-mode: standalone)').matches || navigator.standalone === true}\n\n` +
  logLines.join('\n').replace(/("?(password|pin|sessionId)"?\s*[:=]\s*)"?[^",\s]+"?/gi, '$1[removed]');

// ------------------------------------------------------------------ storage (always safe)
export const store = {
  get(key, fallback = null) {
    try { const v = localStorage.getItem('testem.' + key); return v == null ? fallback : JSON.parse(v); } catch { return fallback; }
  },
  set(key, value) {
    try { value == null ? localStorage.removeItem('testem.' + key) : localStorage.setItem('testem.' + key, JSON.stringify(value)); } catch { /* full or blocked */ }
  },
};

// ------------------------------------------------------------------ API
export async function api(path, body, { method } = {}) {
  let res;
  try {
    res = await fetch(path, body === undefined && !method
      ? {}
      : { method: method || 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body ?? {}) });
  } catch (err) {
    log('W', 'network', path, err.message);
    return { ok: false, status: 0, json: { error: 'offline' } };
  }
  let json = {};
  try { json = await res.json(); } catch { /* empty */ }
  if (!res.ok) log('W', path, res.status, json.error || '');
  return { ok: res.ok, status: res.status, json };
}
/** Like api() but returns the JSON or throws with the server's message. */
export async function apiJson(path, body, opts) {
  const r = await api(path, body, opts);
  if (!r.ok) throw Object.assign(new Error(r.json.error || `HTTP ${r.status}`), { status: r.status });
  return r.json;
}

export const config = { hub: false, vapidPublicKey: '' };
export async function loadConfig() {
  const r = await api('/api/config');
  if (r.ok) Object.assign(config, r.json);
}

// ------------------------------------------------------------------ formatting
const locale = () => (lang() === 'en' ? 'en-GB' : 'sk-SK');
export const clock = (ms) => new Date(ms).toLocaleTimeString(locale(), { hour: '2-digit', minute: '2-digit' });
export const dateText = (ms) => new Date(ms).toLocaleDateString(locale(), { day: 'numeric', month: 'numeric', year: 'numeric' });
export const money = (v, cur = '€') => `${Number(v).toFixed(2).replace('.', lang() === 'en' ? '.' : ',')} ${cur || '€'}`;
export function countdown(seconds) {
  const m = Math.round(seconds / 60);
  if (m <= 0) return t('countdown_now');
  if (m < 60) return t('countdown_min', m);
  return t('countdown_h', Math.floor(m / 60), m % 60);
}
export function delayInfo(seconds) {
  if (seconds == null) return null;
  const m = Math.round(seconds / 60);
  if (m >= 1) return { text: t('delay_late', m), cls: m >= 5 ? 'late very' : 'late' };
  if (m <= -1) return { text: t('delay_early', -m), cls: 'early' };
  return { text: t('delay_on_time'), cls: 'ontime' };
}
export const delayChip = (seconds) => {
  const d = delayInfo(seconds);
  return d ? `<span class="delay ${d.cls}">${esc(d.text)}</span>` : '';
};
export const plate = (line, extra = '') => `<span class="plate ${extra}">${esc(line)}</span>`;
/** Account timestamps are seconds or ms. */
export const toMs = (v) => (!v ? 0 : v < 1e12 ? v * 1000 : v);

// ------------------------------------------------------------------ toast
export function toast(text) {
  const el = $('#toast');
  el.textContent = text;
  el.hidden = false;
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => (el.hidden = true), 3500);
}

// ------------------------------------------------------------------ sheets & pages
/**
 * Bottom sheet (trip details) or full page (settings). Returns the body element.
 * Only one of each at a time; closing runs onClose.
 */
function overlay(id, cls, title, onClose) {
  closeOverlay(id);
  const el = document.createElement('div');
  el.id = id;
  el.className = cls;
  el.innerHTML = `<div class="${cls}-panel" role="dialog" aria-modal="true" aria-label="${esc(title)}">
      <header class="${cls}-head"><button class="icon-btn" data-close aria-label="${esc(t(cls === 'page' ? 'back' : 'close'))}">${cls === 'page' ? '‹' : '✕'}</button><h2>${esc(title)}</h2></header>
      <div class="${cls}-body"></div></div>`;
  el._onClose = onClose;
  el.addEventListener('click', (e) => { if (e.target === el || e.target.closest('[data-close]')) closeOverlay(id); });
  document.body.appendChild(el);
  $('[data-close]', el).focus({ preventScroll: true });
  return $(`.${cls}-body`, el);
}
export const openSheet = (title, onClose) => overlay('sheet', 'sheet', title, onClose);
export const openPage = (title, onClose) => overlay('page', 'page', title, onClose);
export function closeOverlay(id) {
  const el = document.getElementById(id);
  if (!el) return;
  el.remove();
  el._onClose?.();
}
addEventListener('keydown', (e) => {
  if (e.key !== 'Escape') return;
  if (document.getElementById('sheet')) closeOverlay('sheet');
  else closeOverlay('page');
});

export const switchRow = (id, title, hint, checked, disabled = false) => `
  <label class="row switch-row"><span class="row-text"><span class="row-title">${esc(title)}</span>${hint ? `<span class="row-sub">${esc(hint)}</span>` : ''}</span>
  <input type="checkbox" role="switch" id="${id}" ${checked ? 'checked' : ''} ${disabled ? 'disabled' : ''}></label>`;
