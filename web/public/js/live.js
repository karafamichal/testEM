// Departures, stop boards, trip sheet and "follow this bus" for the web client.
import { t, lang } from './i18n.js';
import { $, $$, esc, api, apiJson, store, config, clock, countdown, delayChip, delayInfo, plate, toast, openSheet, openPage, closeOverlay, log } from './core.js';
import { openSubmit } from './submit.js';
import { tripProgress, catchEstimate, communityKey, distanceMeters, readableStop, lineNumber, snapToRoute, referenceAt, delayAt, nextCheckMs, stopPassDetector, earliestBoardingMs, LIVE_GPS_BEFORE_MS, RIDER_GPS_MAX_M, plannerLeg } from './logic.js';

/** Helping with bus positions: 'off' | 'buttons' | 'auto' (earlier versions had an on/off switch). */
export const communityMode = () => store.get('communityMode', store.get('communityOn', false) ? 'buttons' : 'off');

const BOARD_REFRESH_MS = 15000;
const FOLLOW_REFRESH_MS = 15000;
// Material "directions walk" and "directions car" (outlined), for the on foot / by car toggle.
const WALK_SVG = '<svg viewBox="0 0 24 24" aria-hidden="true" width="18" height="18"><path fill="currentColor" d="M13.5 5.5c1.1 0 2-.9 2-2s-.9-2-2-2-2 .9-2 2 .9 2 2 2zM9.8 8.9 7 23h2.1l1.8-8 2.1 2v6h2v-7.5l-2.1-2 .6-3C14.8 12 16.8 13 19 13v-2c-1.9 0-3.5-1-4.3-2.4l-1-1.6c-.4-.6-1-1-1.7-1-.3 0-.5.1-.8.1L6 8.3V13h2V9.6l1.8-.7"/></svg>';
const CAR_SVG = '<svg viewBox="0 0 24 24" aria-hidden="true" width="18" height="18"><path fill="currentColor" d="M18.92 6.01C18.72 5.42 18.16 5 17.5 5h-11c-.66 0-1.21.42-1.42 1.01L3 12v8c0 .55.45 1 1 1h1c.55 0 1-.45 1-1v-1h12v1c0 .55.45 1 1 1h1c.55 0 1-.45 1-1v-8l-2.08-5.99zM6.85 7h10.29l1.04 3H5.81l1.04-3zM19 17H5v-5h14v5zM7.5 13a1.5 1.5 0 1 0 0 3 1.5 1.5 0 0 0 0-3zm9 0a1.5 1.5 0 1 0 0 3 1.5 1.5 0 0 0 0-3z"/></svg>';
const BUS_SVG = '<svg viewBox="0 0 24 24" aria-hidden="true"><path fill="currentColor" d="M4 16c0 .88.39 1.67 1 2.22V20a1 1 0 0 0 1 1h1a1 1 0 0 0 1-1v-1h8v1a1 1 0 0 0 1 1h1a1 1 0 0 0 1-1v-1.78c.61-.55 1-1.34 1-2.22V6c0-3.5-3.58-4-8-4S4 2.5 4 6v10Zm3.5 1a1.5 1.5 0 1 1 0-3 1.5 1.5 0 0 1 0 3Zm9 0a1.5 1.5 0 1 1 0-3 1.5 1.5 0 0 1 0 3ZM18 11H6V6h12v5Z"/></svg>';
const STAR = (on) => `<svg viewBox="0 0 24 24" aria-hidden="true"><path fill="${on ? 'currentColor' : 'none'}" stroke="currentColor" stroke-width="1.8" stroke-linejoin="round" d="m12 3 2.7 5.6 6.1.9-4.4 4.3 1 6.1L12 17l-5.4 2.9 1-6.1-4.4-4.3 6.1-.9L12 3Z"/></svg>`;

const live = {
  stops: null, stopsFailed: false, query: '', results: [], nearby: [], locStatus: 'idle',
  board: null, boardTimer: null,
  onChange: () => {}, // app.js re-renders the ticket tab preview etc.
};
export const liveState = live;

// ------------------------------------------------------------------ stops

export async function loadStops() {
  if (live.stops) return live.stops;
  try {
    live.stops = await apiJson('/api/live/stops');
    live.stopsFailed = false;
  } catch (err) {
    live.stopsFailed = true;
    log('W', 'stops', err.message);
  }
  return live.stops || [];
}
const normalize = (v) => String(v).normalize('NFD').replace(/\p{M}+/gu, '').toLowerCase().replace(/[^a-z0-9]+/g, ' ').trim();
const favIds = () => store.get('favourites', []);
const favourites = () => (live.stops || []).filter((s) => favIds().includes(s.id)).sort((a, b) => favIds().indexOf(a.id) - favIds().indexOf(b.id));
function toggleFavourite(id) {
  const ids = favIds();
  store.set('favourites', ids.includes(id) ? ids.filter((x) => x !== id) : [...ids, id]);
}

// ------------------------------------------------------------------ departures tab

let root = null;
export function renderDepartures(el) {
  root = el;
  if (live.board) return renderBoard();
  el.innerHTML = `
    <header class="screen-head"><h1>${t('departures_title')}</h1><button class="text-btn" data-share-code>${t('share_enter_button')}</button></header>
    <div id="follow-slot-dep"></div>
    <div id="journey-bar-dep">${journeyBarHtml()}</div>
    <div class="search"><input type="search" id="stop-q" placeholder="${esc(t('search_stop'))}" value="${esc(live.query)}" autocomplete="off" aria-label="${esc(t('search_stop'))}"></div>
    <div id="dep-results"></div>`;
  const q = $('#stop-q', el);
  q.oninput = () => { live.query = q.value; renderResults(); };
  $('[data-share-code]', el).onclick = enterShareCode;
  bindJourneyBar($('#journey-bar-dep', el));
  renderFollowSlot($('#follow-slot-dep', el));
  loadStops().then(renderResults);
}

function stopRow(stop, meters) {
  const fav = favIds().includes(stop.id);
  const platforms = stop.platforms.map((p) => p.number).filter(Boolean).join(', ');
  const sub = [meters != null ? t('distance_away', meters < 1000 ? `${meters} m` : `${(meters / 1000).toFixed(1)} km`) : '', platforms].filter(Boolean).join(', ');
  return `<li class="row stop-row"><button class="row-main" data-open="${stop.id}"><span class="row-title">${esc(stop.name)}</span>${sub ? `<span class="row-sub">${esc(sub)}</span>` : ''}</button>
    <button class="icon-btn star ${fav ? 'on' : ''}" data-fav="${stop.id}" aria-pressed="${fav}" aria-label="★">${STAR(fav)}</button></li>`;
}

function renderResults() {
  const box = root && $('#dep-results', root);
  if (!box) return;
  let htmlOut = '';
  if (live.stopsFailed) htmlOut += `<div class="notice error">${t('stops_failed')} <button class="text-btn" data-retry>${t('retry')}</button></div>`;
  const key = normalize(live.query);
  if (key) {
    const hits = (live.stops || []).filter((s) => s.key.includes(key))
      .sort((a, b) => (!a.key.startsWith(key)) - (!b.key.startsWith(key)) || a.name.localeCompare(b.name)).slice(0, 40);
    htmlOut += hits.length ? `<ul class="list">${hits.map((s) => stopRow(s)).join('')}</ul>`
      : `<div class="empty"><h3>${t('no_match_title')}</h3><p>${t('no_match_body')}</p></div>`;
  } else {
    const favs = favourites();
    htmlOut += `<h2 class="section-label">${t('favourites')}</h2>` +
      (favs.length ? `<ul class="list">${favs.map((s) => stopRow(s)).join('')}</ul>` : `<p class="hint">${t('favourites_empty')}</p>`);
    htmlOut += `<h2 class="section-label">${t('nearby')}${live.locStatus === 'ready' ? ` <button class="text-btn" data-nearby-refresh>${t('refresh')}</button>` : ''}</h2>`;
    if (live.locStatus === 'ready') htmlOut += `<ul class="list">${live.nearby.map(([s, m]) => stopRow(s, m)).join('')}</ul>`;
    else if (live.locStatus === 'locating') htmlOut += `<p class="hint"><span class="spinner"></span> ${t('locating')}</p>`;
    else {
      if (live.locStatus === 'denied') htmlOut += `<div class="notice">${t('location_denied')}</div>`;
      if (live.locStatus === 'unavailable') htmlOut += `<div class="notice">${t('location_unavailable')}</div>`;
      htmlOut += `<button class="btn outline wide" data-nearby>${t('find_nearby')}</button>`;
    }
  }
  box.innerHTML = htmlOut;
  box.onclick = (e) => {
    const open = e.target.closest('[data-open]');
    const fav = e.target.closest('[data-fav]');
    if (fav) { toggleFavourite(Number(fav.dataset.fav)); renderResults(); live.onChange(); return; }
    if (open) openStop((live.stops || []).find((s) => s.id === Number(open.dataset.open)));
    if (e.target.closest('[data-nearby]')) findNearby(false);
    if (e.target.closest('[data-nearby-refresh]')) findNearby(true);
    if (e.target.closest('[data-retry]')) { live.stops = null; loadStops().then(renderResults); }
  };
}

/** fresh: the Refresh button wants a new fix, not a cached one from minutes ago. */
function findNearby(fresh) {
  if (!('geolocation' in navigator)) { live.locStatus = 'unavailable'; return renderResults(); }
  live.locStatus = 'locating';
  renderResults();
  navigator.geolocation.getCurrentPosition(async (pos) => {
    const stops = await loadStops();
    const { latitude, longitude } = pos.coords;
    live.nearby = stops.map((s) => [s, distanceMeters(latitude, longitude, s.lat, s.lon)]).sort((a, b) => a[1] - b[1]).slice(0, 8);
    live.locStatus = 'ready';
    renderResults();
  }, (err) => {
    live.locStatus = err.code === 1 ? 'denied' : 'unavailable';
    renderResults();
  }, { enableHighAccuracy: fresh, maximumAge: fresh ? 15000 : 600000, timeout: 15000 });
}

// ------------------------------------------------------------------ board

export function openStop(stop, highlight = null) {
  if (!stop) return;
  live.board = { stop, departures: [], loading: true, error: false, updatedAt: 0, highlight };
  if (root) renderBoard();
  refreshBoard();
}

export async function openStopByName(name, highlight) {
  const stop = await apiJson('/api/live/match?name=' + encodeURIComponent(name)).catch(() => null);
  if (stop) return openStop(stop, highlight);
  live.query = readableStop(name).split(', ').pop();
  live.board = null;
  if (root) renderDepartures(root);
}

function closeBoard() {
  clearTimeout(live.boardTimer);
  live.board = null;
  renderDepartures(root);
}

async function refreshBoard() {
  clearTimeout(live.boardTimer);
  const board = live.board;
  if (!board) return;
  const r = await api('/api/live/departures', { platformIds: board.stop.platforms.map((p) => p.id), stopId: board.stop.id });
  if (live.board !== board) return;
  Object.assign(board, r.ok ? { departures: r.json, error: false, updatedAt: Date.now() } : { error: true }, { loading: false });
  renderBoard();
  if (!document.hidden) live.boardTimer = setTimeout(refreshBoard, BOARD_REFRESH_MS);
}
document.addEventListener('visibilitychange', () => { if (!document.hidden && live.board) refreshBoard(); });

function renderBoard() {
  const b = live.board;
  if (!root || !b) return;
  const fav = favIds().includes(b.stop.id);
  const now = Date.now();
  const rows = b.departures.map((d, i) => {
    const secs = Math.round((d.departsAtMs - now) / 1000);
    const hl = b.highlight && d.line === b.highlight.line && clock(d.departsAtMs - (d.delaySeconds || 0) * 1000) === b.highlight.time;
    return `<li><button class="departure ${d.isCancelled ? 'cancelled' : ''} ${hl ? 'highlight' : ''}" data-dep="${i}">
      ${plate(d.line)}
      <span class="dep-main"><span class="dep-dest">${esc(d.destination)}</span>
        <span class="dep-sub">${d.platformNumber ? esc(t('platform', d.platformNumber)) : ''} ${d.isCancelled ? `<span class="delay very">${t('cancelled')}</span>` : d.fromTimetable ? `<span class="hint small">${esc(d.operator || t('other_carrier'))}</span>` : delayChip(d.delaySeconds)}</span></span>
      <span class="dep-time"><strong>${esc(countdown(secs))}</strong><span>${clock(d.departsAtMs)}${d.isRealtime ? ` <i class="live-dot" title="${esc(t('live'))}"></i>` : d.fromTimetable ? ` · ${esc(t('timetable'))}` : ''}</span></span>
    </button></li>`;
  }).join('');
  root.innerHTML = `
    <header class="screen-head board-head"><button class="icon-btn" data-back aria-label="${esc(t('back'))}">‹</button><h1>${esc(b.stop.name)}</h1>
      <button class="icon-btn star ${fav ? 'on' : ''}" data-fav aria-pressed="${fav}" aria-label="★">${STAR(fav)}</button></header>
    <div id="follow-slot-board"></div>
    ${b.loading ? `<p class="hint"><span class="spinner"></span></p>` : b.error && !b.departures.length
      ? `<div class="notice error">${t('board_failed')} <button class="text-btn" data-refresh>${t('retry')}</button></div>`
      : b.departures.length ? `<ul class="board">${rows}</ul>` : `<div class="empty"><p>${t('board_empty')}</p></div>`}
    ${b.updatedAt ? `<p class="hint small">${t('updated', clock(b.updatedAt))}</p>` : ''}
    ${config.hub ? `<button class="btn outline wide" data-report-stop>${t('submit_report_stop')}</button>` : ''}`;
  renderFollowSlot($('#follow-slot-board', root));
  root.onclick = (e) => {
    if (e.target.closest('[data-back]')) return closeBoard();
    if (e.target.closest('[data-fav]')) { toggleFavourite(b.stop.id); live.onChange(); return renderBoard(); }
    if (e.target.closest('[data-refresh]')) return refreshBoard();
    if (e.target.closest('[data-report-stop]')) return openSubmit('problem', { stopName: b.stop.name });
    const dep = e.target.closest('[data-dep]');
    if (dep) {
      const d = b.departures[Number(dep.dataset.dep)];
      openTrip({ line: d.line, lineId: d.lineId, routeNumber: d.routeNumber, tripNumber: d.tripNumber, destination: d.destination,
        scheduleUrl: d.scheduleUrl || '', serviceDate: d.serviceDate || '', fromStopId: b.stop.id, plannedSecondOfDay: d.plannedSecondOfDay },
        { boardingPlatformIds: b.stop.platforms.map((p) => p.id) });
    }
  };
}

// Countdowns move between refreshes.
setInterval(() => { if (live.board && !document.hidden && root?.isConnected && !$('#sheet')) renderBoard(); }, 20000);

/** Next three buses from each of the first three favourite stops, for the home tab. */
export async function renderPreview(el) {
  await loadStops();
  const stops = favourites().slice(0, 3);
  if (!stops.length) { el.innerHTML = `<h2 class="section-label">${t('favourites')}</h2><p class="hint pad">${t('ticket_next_hint')}</p>`; return; }
  const boards = await Promise.all(stops.map((stop) => api('/api/live/departures', { platformIds: stop.platforms.map((p) => p.id), stopId: stop.id })));
  // D1: the first favourite's next buses as cards (the first in the next-bus colour), the others as small boards.
  const first = boards[0].ok ? boards[0].json.filter((d) => !d.isCancelled).slice(0, 4) : [];
  const cards = `<div class="section-label"><span>${esc(stops[0].name)}</span><button class="text-btn" data-preview-stop="${stops[0].id}">${t('all_departures')}</button></div>
    <div class="next-cards">${first.map((d, i) => `<button class="next-card ${i === 0 ? 'first' : ''}" data-preview-stop="${stops[0].id}">${plate(d.line)}
      <span class="next-dest">${esc(d.destination)}</span>
      <strong class="next-time">${d.fromTimetable ? clock(d.departsAtMs) : esc(countdown((d.departsAtMs - Date.now()) / 1000))}</strong>
      <span class="next-sub">${d.fromTimetable ? esc(d.operator || t('timetable')) : delayChip(d.delaySeconds)}</span></button>`).join('') || `<p class="hint pad">${t('board_empty')}</p>`}</div>`;
  el.innerHTML = cards + (stops.length > 1 ? `<h2 class="section-label">${t('favourites')}</h2>` : '') + stops.slice(1).map((stop, n) => {
    const i = n + 1;
    const deps = boards[i].ok ? boards[i].json.slice(0, 3) : [];
    return `<button class="preview-board" data-preview-stop="${stop.id}"><span class="preview-name">${esc(stop.name)}</span>
      <ul class="board compact">${deps.map((d) => `<li><div class="departure">${plate(d.line)}<span class="dep-main"><span class="dep-dest">${esc(d.destination)}</span><span class="dep-sub">${d.fromTimetable ? `<span class="hint small">${esc(d.operator || t('timetable'))}</span>` : delayChip(d.delaySeconds)}</span></span>
      <span class="dep-time"><strong>${esc(countdown((d.departsAtMs - Date.now()) / 1000))}</strong><span>${clock(d.departsAtMs)}</span></span></div></li>`).join('') || `<li class="hint small">${t('board_empty')}</li>`}</ul></button>`;
  }).join('');
}

/** A favourite stop tapped on the home tab. */
export async function openStopById(id) {
  await loadStops();
  openStop((live.stops || []).find((s) => s.id === Number(id)));
}

/** The bus being followed, with the user's stops: after tapping its notification. */
export function openFollowed() {
  if (follow) openTrip(follow.ref, { boardingPlatformIds: follow.boardingPlatformIds, alightOrder: follow.alightOrder });
  return !!follow;
}

// ------------------------------------------------------------------ trip sheet

let sheetTrip = null; // { ref, boardingPlatformIds, alightOrder, detail, loading, error }

export function openTrip(ref, { boardingPlatformIds = [], alightOrder = null } = {}) {
  sheetTrip = { ref, boardingPlatformIds, alightOrder, detail: null, loading: true, error: false };
  const body = openSheet(ref.line + ' → ' + (ref.destination || ''), () => { sheetTrip = null; });
  renderTripSheet(body);
  loadTrip(ref).then((detail) => {
    if (!sheetTrip || sheetTrip.ref !== ref) return;
    Object.assign(sheetTrip, { detail, loading: false });
    if (detail.boardingOrder) sheetTrip.boardingPlatformIds = [detail.boardingOrder];
    if (detail.alightOrder && !detail.fromTimetable) sheetTrip.alightOrder = detail.alightOrder;
    renderTripSheet();
  }).catch(() => { if (sheetTrip) Object.assign(sheetTrip, { loading: false, error: true }); renderTripSheet(); });
}

/** A bus from a planner result: live for SAD Zvolen city buses, else from the timetable. */
export function openScheduledTrip(segment) {
  if (!segment.routeUrl) return;
  const { ref, boardingPlatformIds, alightOrder } = plannerLeg(segment);
  openTrip(ref, { boardingPlatformIds, alightOrder });
}

// Keep an open trip sheet fresh: riders' reports and the bus's position change all the time.
setInterval(() => {
  const s = sheetTrip;
  if (!s || s.loading || s.error || document.hidden || sameRef(follow?.ref, s.ref)) return;
  loadTrip(s.ref).then((detail) => {
    if (sheetTrip !== s) return;
    s.detail = detail;
    renderTripSheet();
  }).catch(() => {});
}, 15000);

/**
 * Trip with the best known position: a rider's phone on the bus, the bus's own GPS
 * (placed by the server), riders' taps, sadzv's delay, the timetable.
 */
async function loadTrip(ref, { withCoordinates = false } = {}) {
  const detail = await apiJson('/api/live/trip', { ...ref, withCoordinates });
  detail.communityKey = communityKey(detail, ref);
  if (communityMode() === 'off' || !config.hub || !detail.communityKey) return detail;
  const res = await apiJson('/api/hub/delay?trip=' + encodeURIComponent(detail.communityKey)).catch(() => null);
  const pooled = res?.delay;
  detail.history = res?.history || null;
  detail.crowd = res?.crowd || null;
  if (!pooled) {
    // No one reported today: optionally shift the timetable by how late this trip usually is.
    if (detail.history && detail.positionSource === 'timetable' && store.get('predictShift', false)) {
      Object.assign(detail, { delaySeconds: detail.history.delaySeconds, positionSource: 'history' });
    }
    return detail;
  }
  detail.community = pooled;
  if (pooled.source === 'gps' && Date.now() - pooled.updatedAt < 3 * 60000) {
    Object.assign(detail, { delaySeconds: pooled.delaySeconds, positionSource: 'riderGps' });
  } else if (detail.positionSource !== 'busGps') {
    Object.assign(detail, { delaySeconds: pooled.delaySeconds, positionSource: 'riders' });
  }
  return detail;
}
/** "Usually ~3 min late (late 7 of the last 10 workdays)", or '' without history. */
function historyText(d) {
  const h = d?.history;
  if (!h || (d.positionSource !== 'timetable' && d.positionSource !== 'history')) return '';
  const m = Math.round(h.delaySeconds / 60);
  const usually = m >= 1 ? t('history_late', m) : m <= -1 ? t('history_early', -m) : t('history_on_time');
  return `${usually} ${t('history_runs', h.lateRuns, h.runs, t('history_' + h.dayType))}`;
}
const historyHint = (d) => (historyText(d) ? `<span class="hint small">${esc(historyText(d))}</span>` : '');

const isLive = (d) => d?.positionSource === 'busGps' || d?.positionSource === 'riderGps';

const sameRef = (a, b) => a && b && JSON.stringify(a) === JSON.stringify(b);

const MAP_SVG = '<svg viewBox="0 0 24 24" width="22" height="22" aria-hidden="true"><path fill="currentColor" d="m15 5.1-6-2.1-6 2.3v15.6l6-2.3 6 2.1 6-2.3V2.8l-6 2.3Zm-1 13.5-4-1.4V5.4l4 1.4v11.8Z"/></svg>';
const SHARE_SVG = '<svg viewBox="0 0 24 24" width="22" height="22" aria-hidden="true"><path fill="currentColor" d="M18 16.1c-.8 0-1.4.3-2 .8l-7.1-4.2c.1-.2.1-.5.1-.7s0-.5-.1-.7l7-4.1c.5.5 1.2.8 2.1.8 1.7 0 3-1.3 3-3s-1.3-3-3-3-3 1.3-3 3c0 .2 0 .5.1.7l-7 4.1c-.5-.5-1.2-.8-2.1-.8-1.7 0-3 1.3-3 3s1.3 3 3 3c.8 0 1.6-.3 2.1-.8l7.1 4.2c-.1.2-.1.4-.1.6 0 1.6 1.3 2.9 2.9 2.9s2.9-1.3 2.9-2.9-1.3-2.9-2.9-2.9Z"/></svg>';

/** The bus as the share payload (the same as the Android app's): only the bus and where it's ridden, nothing about the sharer. */
const sharePayload = (trip) => btoa(String.fromCharCode(...new TextEncoder().encode(JSON.stringify(
  { ref: trip.ref, board: trip.boardingPlatformIds || [], alight: trip.alightOrder ?? null })))).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');

/**
 * One side of a share over a WebSocket to emhub: status news and the other side's positions as they happen.
 * Reconnects (2 s … 30 s) until closed.
 */
function shareSocket(side, id, token, onMessage) {
  let ws = null, closed = false, wait = 2000;
  const open = () => {
    ws = new WebSocket(`${config.hubWs}/api/v1/shares/live?side=${side}&id=${encodeURIComponent(id)}&token=${encodeURIComponent(token)}`);
    ws.onopen = () => { wait = 2000; };
    ws.onmessage = (e) => { try { onMessage(JSON.parse(e.data)); } catch { /* not ours */ } };
    ws.onclose = () => { if (!closed) { setTimeout(open, wait); wait = Math.min(wait * 2, 30000); } };
  };
  open();
  return {
    send: (obj) => { if (ws?.readyState === 1) ws.send(JSON.stringify(obj)); },
    close: () => { closed = true; ws?.close(); },
  };
}
const outSockets = {}; // share id -> socket, while following

/** Who enters a share code: the name from the signed-in account (app.js sets it), editable. */
export const shareName = { get: () => '' };

/** Share a bus: a link anyone can open, or a one-time code; whoever enters it sends their name and the sharer accepts or declines. */
function shareTrip(trip, destination) {
  const body = openPage(t('share_trip'));
  let made = null, timer = null;
  const stop = () => clearInterval(timer);
  const render = (state) => {
    if (!made) {
      body.innerHTML = `<p class="hint">${t('share_how')}</p><button class="btn wide" data-link>${t('share_link')}</button>
        <button class="btn outline wide" data-code ${config.hub ? '' : 'disabled'}>${t('share_code')}</button><p class="error-text" hidden></p>`;
      return;
    }
    const st = state?.status || 'waiting';
    body.innerHTML = st === 'asked'
      ? `<h3>${esc(t('share_asked', state.name))}</h3><p class="hint small">${t('share_asked_hint')}</p>
         <button class="btn wide" data-answer="accept">${t('share_accept')}</button><button class="btn outline wide" data-answer="decline">${t('share_decline')}</button>`
      : st === 'accepted' ? `<p>${esc(t('share_accepted', state.name))}</p>`
      : st === 'declined' ? `<p>${t('share_declined_self')}</p>`
      : st === 'expired' ? `<p>${t('share_expired')}</p>`
      : `<p class="hint">${t('share_code_tell')}</p><p class="share-code">${esc(made.code.slice(0, 3) + ' ' + made.code.slice(3))}</p><p class="hint small">${t('share_code_valid')}</p>`;
  };
  render();
  body.onclick = async (e) => {
    if (e.target.closest('[data-link]')) {
      const text = t('share_trip_text', trip.ref.line, destination, 'https://par.karafa.net/f?t=' + sharePayload(trip));
      if (navigator.share) return navigator.share({ text }).catch(() => {});
      await navigator.clipboard?.writeText(text).catch(() => {});
      return toast(t('share_copied'));
    }
    if (e.target.closest('[data-code]')) {
      const r = await api('/api/hub/shares', { trip: sharePayload(trip), label: `${trip.ref.line} → ${destination}` });
      if (!r.ok) { const err = $('.error-text', body); err.textContent = t('share_code_failed'); err.hidden = false; return; }
      made = r.json;
      render();
      const sock = shareSocket('owner', made.id, made.token, (m) => {
        if (m.type !== 'status' || m.status === 'waiting') return;
        if (!body.querySelector('[data-answer]') || m.status !== 'asked') render(m);
      });
      // Closed without an answer: the code is taken back.
      timer = setInterval(() => {
        if (document.body.contains(body)) return;
        stop(); sock.close();
        if (made && !body.dataset.answered) api(`/api/hub/shares/${made.id}/cancel`, { token: made.token });
      }, 1000);
    }
    const a = e.target.closest('[data-answer]');
    if (a && made) {
      await api(`/api/hub/shares/${made.id}/${a.dataset.answer}`, { token: made.token });
      body.dataset.answered = '1';
      if (a.dataset.answer === 'accept') store.set('sharesOut', [...store.get('sharesOut', []), { id: made.id, token: made.token, name: $('h3', body)?.textContent || '' }]);
      stop();
      render({ status: a.dataset.answer === 'accept' ? 'accepted' : 'declined', name: $('h3', body)?.textContent || '' });
      if (a.dataset.answer === 'decline') made = null;
    }
  };
}

/** A code someone told you: sent with your name; their bus opens once they accept. */
function enterShareCode() {
  const body = openPage(t('share_enter_title'));
  body.innerHTML = `<label class="field"><span>${t('share_enter_code')}</span><input id="share-code" inputmode="numeric" maxlength="6" autocomplete="one-time-code"></label>
    <label class="field"><span>${t('share_your_name')}</span><input id="share-name" maxlength="80" value="${esc(shareName.get())}"></label>
    <p class="hint small">${t('share_enter_hint')}</p><button class="btn wide" id="share-send">${t('share_send')}</button><p class="error-text" hidden></p>`;
  $('#share-send', body).onclick = async () => {
    const code = $('#share-code', body).value.replace(/\D/g, ''), name = $('#share-name', body).value.trim();
    const err = $('.error-text', body);
    if (code.length !== 6 || !name) { err.textContent = t('share_code_wrong'); err.hidden = false; return; }
    const r = await api('/api/hub/shares/claim', { code, name });
    if (!r.ok) { err.textContent = t('share_code_wrong'); err.hidden = false; return; }
    const claim = r.json;
    body.innerHTML = `<p>${esc(t('share_waiting', claim.label))}</p><p class="hint"><span class="spinner"></span></p>`;
    const sock = shareSocket('claim', claim.claimId, claim.token, (s) => {
      if (s.type !== 'status' || s.status === 'asked') return;
      sock.close();
      if (s.status !== 'accepted') { body.innerHTML = `<p>${t('share_declined')}</p>`; return; }
      try {
        const p = JSON.parse(new TextDecoder().decode(Uint8Array.from(atob(s.trip.replace(/-/g, '+').replace(/_/g, '/')), (c) => c.charCodeAt(0))));
        closeOverlay('page');
        store.set('sharesIn', [...store.get('sharesIn', []), { claimId: claim.claimId, token: claim.token, label: claim.label, line: p.ref.line, trip: p.ref.tripNumber }]);
        openTrip(p.ref, { boardingPlatformIds: p.board || [], alightOrder: p.alight ?? null });
      } catch { body.innerHTML = `<p>${t('share_bad_link')}</p>`; }
    });
  };
}

/**
 * Where the bus is, on OpenStreetMap: the map's tiles around the route, its stops joined in order (passed ones dim), and
 * the bus between the two stops it's between by its expected times. A plain picture (no map library): drag to move.
 */
async function openBusMap(ref, detail, alightOrder) {
  const body = openPage(`${ref.line} → ${detail.destination || ref.destination}`);
  body.innerHTML = '<p class="hint"><span class="spinner"></span></p>';
  const full = detail.stops.every((x) => x.lat != null) ? detail : await loadTrip(ref, { withCoordinates: true }).catch(() => null);
  const stops = (full?.stops || []).filter((x) => x.lat != null);
  if (stops.length < 2) { body.innerHTML = `<div class="empty"><p>${t('map_unavailable')}</p></div>`; return; }
  const incoming = store.get('sharesIn', []).findLast?.((x) => x.line === ref.line && x.trip === ref.tripNumber);
  body.innerHTML = (incoming ? `<label class="row"><span class="row-title">${t('share_back')}</span><input type="checkbox" id="share-back"></label>` : '') +
    '<div class="busmap"><div class="busmap-tiles"></div><svg class="busmap-route"></svg><p class="busmap-credit">© OpenStreetMap</p></div>';
  const box = $('.busmap', body), tiles = $('.busmap-tiles', box), svg = $('.busmap-route', box);
  const W = box.clientWidth, H = box.clientHeight;
  const px = (lat, lon, z) => {
    const n = 256 * 2 ** z, r = (lat * Math.PI) / 180;
    return [((lon + 180) / 360) * n, ((1 - Math.log(Math.tan(r) + 1 / Math.cos(r)) / Math.PI) / 2) * n];
  };
  // EM-0064: the roads between the stops, as drawn or snapped in emHUB (a hop without one stays straight).
  const roads = await apiJson('/api/live/path?stops=' + encodeURIComponent(stops.map((x) => `${x.lat.toFixed(6)},${x.lon.toFixed(6)}`).join(';')))
    .catch(() => []);
  const lats = stops.map((x) => x.lat), lons = stops.map((x) => x.lon);
  let z = 17;
  while (z > 8) {
    const [x1, y1] = px(Math.max(...lats), Math.min(...lons), z), [x2, y2] = px(Math.min(...lats), Math.max(...lons), z);
    if (x2 - x1 < W * 0.85 && y2 - y1 < H * 0.85) break;
    z--;
  }
  const [cx, cy] = px((Math.max(...lats) + Math.min(...lats)) / 2, (Math.max(...lons) + Math.min(...lons)) / 2, z);
  let ox = cx - W / 2, oy = cy - H / 2; // the map's top-left corner, in world pixels
  const draw = () => {
    const tx0 = Math.floor(ox / 256), ty0 = Math.floor(oy / 256), tx1 = Math.floor((ox + W) / 256), ty1 = Math.floor((oy + H) / 256);
    let html = '';
    for (let x = tx0; x <= tx1; x++) for (let y = ty0; y <= ty1; y++) {
      // referrerpolicy: OpenStreetMap's tile servers refuse requests without a Referer (the page's policy sends none).
      html += `<img src="https://tile.openstreetmap.org/${z}/${x}/${y}.png" referrerpolicy="strict-origin-when-cross-origin" style="left:${x * 256 - ox}px;top:${y * 256 - oy}px" alt="">`;
    }
    tiles.innerHTML = html;
    const p = tripProgress(full, { boardingPlatformIds: [], alightOrder });
    const at = stops.map((x) => px(x.lat, x.lon, z)).map(([x, y]) => [x - ox, y - oy]);
    const pos = Math.min(p.position, stops.length - 1), i = Math.floor(pos), f = pos - i, j = Math.min(i + 1, stops.length - 1);
    const road = (k) => (roads[k] || []).map(([lat, lon]) => px(lat, lon, z)).map(([x, y]) => [x - ox, y - oy]);
    const bus = along([at[i], ...(j > i ? road(i) : []), at[j]], f);
    svg.setAttribute('viewBox', `0 0 ${W} ${H}`);
    svg.innerHTML = `<polyline points="${at.flatMap((q, k) => [q, ...road(k)]).map((q) => q.join(',')).join(' ')}" />
      ${at.map((q, k) => `<circle class="${k <= pos ? 'passed' : ''} ${stops[k].order === alightOrder ? 'alight' : ''}" cx="${q[0]}" cy="${q[1]}" r="${stops[k].order === alightOrder ? 8 : 5}"><title>${esc(stops[k].name)}</title></circle>`).join('')}
      <g class="bus" transform="translate(${bus[0]},${bus[1]})"><circle r="14"/><text y="5">${esc(ref.line)}</text></g>
      ${people().map(([name, q]) => { const [x, y] = px(q.lat, q.lon, z); return `<g class="person" transform="translate(${x - ox},${y - oy})"><circle r="9"/><text y="-14">${esc(name)}</text></g>`; }).join('')}`;
  };
  // People sharing by a code: the sharer (here as the receiver), or those this phone shares its followed bus with.
  const people = () => {
    const fresh = (q) => q && Date.now() - q.at * 1000 < 5 * 60000;
    const list = [];
    if (incoming && fresh(sharePositions[incoming.claimId])) list.push([incoming.label.split(' · ')[0], sharePositions[incoming.claimId]]);
    if (sameRef(follow?.ref, ref)) for (const sh of store.get('sharesOut', [])) if (fresh(sharePositions[sh.id])) list.push([sh.name, sharePositions[sh.id]]);
    return list;
  };
  // The sharer's position live over a WebSocket while the map is open; ours back only with the switch on.
  const sock = incoming && shareSocket('claim', incoming.claimId, incoming.token, (m) => {
    if (m.type === 'position') { sharePositions[incoming.claimId] = m.position; draw(); }
  });
  const pollShare = () => {
    if (!sock) return;
    if (!document.body.contains(box)) return sock.close();
    if ($('#share-back', body)?.checked) {
      navigator.geolocation?.getCurrentPosition((pos) => sock.send({ type: 'position', lat: pos.coords.latitude, lon: pos.coords.longitude,
        accuracy: Math.round(pos.coords.accuracy || 0) }), () => {}, { maximumAge: 20000 });
    }
    setTimeout(pollShare, 20000);
  };
  draw();
  pollShare();
  // Drag to look around; the bus moves on by the clock.
  let drag = null;
  box.onpointerdown = (e) => { drag = [e.clientX, e.clientY]; box.setPointerCapture(e.pointerId); };
  box.onpointermove = (e) => { if (!drag) return; ox -= e.clientX - drag[0]; oy -= e.clientY - drag[1]; drag = [e.clientX, e.clientY]; draw(); };
  box.onpointerup = () => { drag = null; };
  const timer = setInterval(() => (document.body.contains(box) ? draw() : clearInterval(timer)), 15000);
}

/** The point a fraction f of the way along a path of [x, y], by length. */
function along(path, f) {
  const lengths = path.slice(1).map((q, k) => Math.hypot(q[0] - path[k][0], q[1] - path[k][1]));
  let left = lengths.reduce((a, b) => a + b, 0) * f;
  for (let k = 0; k < lengths.length; k++) {
    if (left <= lengths[k] && lengths[k] > 0) {
      const t2 = left / lengths[k];
      return [path[k][0] + (path[k + 1][0] - path[k][0]) * t2, path[k][1] + (path[k + 1][1] - path[k][1]) * t2];
    }
    left -= lengths[k];
  }
  return path[path.length - 1];
}

function renderTripSheet(body = $('#sheet .sheet-body')) {
  if (!body || !sheetTrip) return;
  const s = sheetTrip;
  const following = sameRef(follow?.ref, s.ref);
  const detail = following && follow.detail ? effectiveDetail(follow) : s.detail;
  const alight = following ? follow.alightOrder : s.alightOrder;
  const status = s.ref.scheduleUrl
    ? (detail?.community && detail.positionSource !== 'timetable' ? `${delayChip(detail.delaySeconds)} <span class="hint small">${esc(ridersText(detail.community.reporters))}</span>` : `<span class="hint small">${t('schedule_only')}</span>`)
    : delayChip(detail?.delaySeconds);
  const statusLine = historyText(detail) ? `${status} ${historyHint(detail)}` : status;
  let timeline = '';
  if (s.loading) timeline = '<p class="hint"><span class="spinner"></span></p>';
  else if (s.error || !detail?.stops?.length) timeline = `<div class="empty"><p>${t('trip_unavailable')}</p></div>`;
  else {
    const p = tripProgress(detail, { boardingPlatformIds: s.boardingPlatformIds, alightOrder: alight });
    // EM-0057: where you get on is marked too (the timetable says it, or the platform the board was opened at).
    const board = detail.boardingOrder ?? detail.stops.find((x) => (s.boardingPlatformIds || []).includes(x.platformId))?.order;
    timeline = `<ol class="timeline">${detail.stops.map((stop, i) => {
      const passed = i < p.nextIndex, next = i === p.nextIndex, isAlight = stop.order === alight, isBoard = !isAlight && stop.order === board;
      const exp = p.expected[i];
      const shift = (stop.actualMs != null || detail.delaySeconds != null) && clock(exp) !== clock(stop.scheduledMs);
      return `<li class="${passed ? 'passed' : ''} ${next ? 'next' : ''} ${isAlight ? 'alight' : ''} ${isBoard ? 'board' : ''}">
        <button ${!passed ? `data-alight="${stop.order}"` : 'disabled'}>
          <span class="dot"></span><span class="stop-name">${esc(stop.name)}${isAlight ? `<small>${t('your_stop')}</small>` : ''}${isBoard ? `<small>${t('your_board')}</small>` : ''}${stop.platform ? `<small class="stop-pf">${esc(t('platform', stop.platform))}</small>` : ''}</span>
          <span class="stop-time">${shift ? `<s>${clock(stop.scheduledMs)}</s>${clock(exp)}` : clock(stop.scheduledMs)}</span></button></li>`;
    }).join('')}</ol>`;
  }
  body.innerHTML = `
    <div class="trip-head">${plate(s.ref.line, 'big')}<div class="trip-title"><h3>${esc(detail?.destination || s.ref.destination)}</h3><div>${statusLine}</div></div>
      ${detail?.stops?.length ? `<button class="icon-btn" data-map aria-label="${esc(t('map_open'))}">${MAP_SVG}</button>` : ''}
      <button class="icon-btn" data-share aria-label="${esc(t('share_trip'))}">${SHARE_SVG}</button></div>
    ${following
      ? `<p class="hint">${t('following_hint')}</p><button class="btn outline wide" data-unfollow>${t('unfollow')}</button>`
      : `<button class="btn wide" data-follow ${detail?.stops?.length ? '' : 'disabled'}>${BUS_SVG}${t('follow')}</button>
         <button class="btn outline wide" data-add-journey ${detail?.stops?.length ? '' : 'disabled'}>${t('journey_add')}</button>
         ${alight == null ? `<p class="hint small">${t('journey_pick_alight')}</p>` : ''}`}
    <div data-journey-bar>${journeyBarHtml()}</div>
    ${timeline}`;
  bindJourneyBar($('[data-journey-bar]', body));
  body.onclick = (e) => {
    if (e.target.closest('[data-follow]')) {
      startFollow({ ref: s.ref, boardingPlatformIds: s.boardingPlatformIds, alightOrder: s.alightOrder, detail: s.detail });
    }
    if (e.target.closest('[data-unfollow]')) stopFollow();
    if (e.target.closest('[data-map]')) return openBusMap(s.ref, detail, alight);
    if (e.target.closest('[data-share]')) return shareTrip({ ref: s.ref, boardingPlatformIds: following ? follow.boardingPlatformIds : s.boardingPlatformIds, alightOrder: alight }, detail?.destination || s.ref.destination);
    if (e.target.closest('[data-add-journey]')) addToDraft({ ref: s.ref, boardingPlatformIds: s.boardingPlatformIds, alightOrder: s.alightOrder, ...legStops(s, detail, alight) });
    const a = e.target.closest('[data-alight]');
    if (a && following) setAlight(Number(a.dataset.alight));
    else if (a) s.alightOrder = s.alightOrder === Number(a.dataset.alight) ? null : Number(a.dataset.alight);
    renderTripSheet(body);
  };
  const next = $('.timeline li.next', body);
  if (next && !body.dataset.scrolled) { body.dataset.scrolled = '1'; next.scrollIntoView({ block: 'center' }); }
}

const ridersText = (n) => (n === 1 ? t('community_rider') : t('community_riders', n));

// ------------------------------------------------------------------ follow a bus

let follow = store.get('follow', null); // { ref, boardingPlatformIds, alightOrder, nextLegs, reporter, localDelay, pushId, startedAt }
let followTimer = null;
let fix = null; // latest position (stays on the phone)
let stopCoords = null;

/** Buttons for riders who chose them, on any bus whose position nobody knows live. */
const buttonsOn = () => communityMode() === 'buttons' && config.hub && follow?.detail?.communityKey && !isLive(follow.detail);
const catchOn = () => store.get('catchOn', false);

function saveFollow() {
  if (!follow) return store.set('follow', null);
  const { detail, ...persist } = follow;
  store.set('follow', persist);
}

// ---- journeys: several buses followed one after another (planner connections or built by hand)

// EM-0072: the drive to the stop from the router (position rounded to ~100 m), asked again after 300 m or 3 min.
const drive = { seconds: null, at: 0, from: null };
function askDrive(fix) {
  const moved = !drive.from || distanceMeters(fix.latitude, fix.longitude, drive.from.latitude, drive.from.longitude) > 300;
  if (!moved && Date.now() - drive.at < 180000) return;
  drive.from = fix;
  drive.at = Date.now();
  apiJson(`/api/live/drive?from=${fix.latitude.toFixed(3)},${fix.longitude.toFixed(3)}&to=${stopCoords.lat.toFixed(4)},${stopCoords.lon.toFixed(4)}`)
    .then((r) => { drive.seconds = r.seconds; }).catch(() => {});
}

let draft = store.get('journeyDraft', []); // [{ ref, boardingPlatformIds, alightOrder }]
function setDraft(list) { draft = list; store.set('journeyDraft', list); renderJourneyBars(); }
function addToDraft(leg) { setDraft([...draft.filter((x) => !sameRef(x.ref, leg.ref)), leg]); }

/** Where the user gets on and off an opened bus and when, for the journey's list (EM-0066, EM-0067). */
function legStops(s, detail, alight) {
  const stops = detail?.stops || [];
  if (stops.length < 2) return {};
  let b = stops.findIndex((x) => (detail.boardingOrder != null ? x.order === detail.boardingOrder : (s.boardingPlatformIds || []).includes(x.platformId)));
  if (b < 0) b = 0;
  let a = stops.findIndex((x) => x.order === (alight ?? detail.alightOrder));
  if (a <= b) a = stops.length - 1;
  return { fromStop: stops[b].name, toStop: stops[a].name, leaves: clock(stops[b].scheduledMs), arrives: clock(stops[a].scheduledMs) };
}

/** Follows [legs] as one journey: each bus until the user's stop, then the next one. */
export function startJourney(legs) {
  if (!legs.length) return;
  const [first, ...rest] = legs;
  startFollow({ ...first, nextLegs: rest });
}

function journeyBarHtml() {
  if (!draft.length) return '';
  const legs = draft.filter((l) => l.fromStop).map((l) => `<li>${esc(`${l.ref.line}  ${l.leaves} ${l.fromStop} → ${l.arrives} ${l.toStop}`)}</li>`).join('');
  return `<div class="journey-bar"><details><summary>${esc(t('journey_draft', draft.map((l) => l.ref.line).join(' → ')))}</summary>
    ${legs ? `<ul class="hint small">${legs}</ul>` : ''}</details>
    <button class="text-btn" data-journey-clear>${t('journey_clear')}</button><button class="btn" data-journey-start>${t('journey_start')}</button></div>`;
}
/** The journey being put together, on Home too (EM-0066). */
export function renderJourneyBar(el) {
  if (!el) return;
  el.innerHTML = journeyBarHtml();
  bindJourneyBar(el);
}
function bindJourneyBar(el) {
  if (!el) return;
  el.onclick = (e) => {
    if (e.target.closest('[data-journey-clear]')) setDraft([]);
    if (e.target.closest('[data-journey-start]')) { startJourney(draft); setDraft([]); }
  };
}
function renderJourneyBars() {
  document.querySelectorAll('#journey-bar-dep, [data-journey-bar]').forEach((el) => { el.innerHTML = journeyBarHtml(); bindJourneyBar(el); });
}

export function startFollow({ ref, boardingPlatformIds, alightOrder, detail, nextLegs = [], toStop = null, arrives = null }) {
  if (follow?.pushId) api('/api/unfollow', { id: follow.pushId });
  stopPass = stopPassDetector();
  lastArrivalStop = -1;
  nextFixAt = 0;
  lastRiderReportAt = 0;
  lastRiderIndex = -1;
  follow = {
    ref, boardingPlatformIds, alightOrder, detail, nextLegs, toStop, arrives,
    // New random id per bus, so reports can't be linked across trips. (randomUUID is missing on older iOS.)
    reporter: [...crypto.getRandomValues(new Uint8Array(16))].map((b) => b.toString(16).padStart(2, '0')).join(''),
    localDelay: null, pushId: null, startedAt: Date.now(), note: '',
  };
  stopCoords = null;
  saveFollow();
  followTick();
  live.onChange();
}

/** The followed bus's line ("" when none): the ticket shown switches by it. */
export const followedLine = () => follow?.ref?.line || '';

export function stopFollow() {
  if (follow?.pushId) api('/api/unfollow', { id: follow.pushId });
  if (follow?.eta) api(`/api/hub/eta/${follow.eta.id}/end`, { token: follow.eta.token }); // "Arrived" for whoever has the link
  // Sharing by a code ends with following.
  for (const sh of store.get('sharesOut', [])) { outSockets[sh.id]?.close(); delete outSockets[sh.id]; api(`/api/hub/shares/${sh.id}/cancel`, { token: sh.token }); }
  store.set('sharesOut', []);
  follow = null;
  saveFollow();
  clearTimeout(followTimer);
  stopGeo();
  live.onChange();
  renderAllFollowSlots();
}

function setAlight(order) {
  if (!follow) return;
  follow.alightOrder = follow.alightOrder === order ? null : order;
  saveFollow();
  if (follow.pushId) subscribePush(true);
  renderAllFollowSlots();
}

/** The rider's own report wins until the pooled delay is newer. */
function effectiveDetail(f) {
  const d = f.detail;
  if (!d) return null;
  if (f.localDelay && f.localDelay.at >= (d.community?.updatedAt || 0)) return { ...d, delaySeconds: f.localDelay.seconds };
  return d;
}

async function followTick() {
  clearTimeout(followTimer);
  if (!follow) return;
  if (Date.now() - follow.startedAt > 3 * 3600 * 1000) return stopFollow();
  try {
    const detail = await loadTrip(follow.ref, { withCoordinates: communityMode() === 'auto' });
    if (follow && detail.stops.length) follow.detail = detail;
    // Journey legs from the planner learn where the user gets on and off from their trip link.
    if (follow && !follow.boardingPlatformIds?.length && detail.boardingOrder) {
      follow.boardingPlatformIds = [detail.boardingOrder];
      follow.alightOrder ??= detail.alightOrder ?? null;
      saveFollow();
    } else if (follow && detail.boardingOrder && detail.fromTimetable && !detail.stops.some((x) => follow.boardingPlatformIds.includes(x.platformId))) {
      // sadzv had no stop list: stops come from the timetable with its own numbers. Without this the
      // boarding stop isn't found and the user counts as already on the bus (GPS on all the time).
      follow.boardingPlatformIds = [...follow.boardingPlatformIds, detail.boardingOrder];
      saveFollow();
    }
  } catch (err) { log('W', 'follow', err.message); }
  if (!follow) return;
  const d = effectiveDetail(follow);
  if (d?.stops.length) {
    const p = tripProgress(d, follow);
    if (p.finished) {
      const [next, ...rest] = follow.nextLegs || [];
      if (next) {
        // Changed buses: follow the next one (and move the push alerts over to it).
        const push = !!follow.pushId;
        toast(t('journey_change', next.ref.line, p.lastStopName));
        startFollow({ ...next, nextLegs: rest });
        if (push) subscribePush(true);
        return;
      }
      toast(t('follow_arrived', p.lastStopName));
      return stopFollow();
    }
    updateLocation(p);
    sendEta();
  }
  renderAllFollowSlots();
  if (sheetTrip && sameRef(sheetTrip.ref, follow.ref)) renderTripSheet();
  if (!document.hidden) followTimer = setTimeout(followTick, FOLLOW_REFRESH_MS);
}
document.addEventListener('visibilitychange', () => {
  if (document.hidden) { clearTimeout(followTimer); stopGeo(); } else if (follow) followTick();
});
setInterval(() => { if (follow && !document.hidden) renderAllFollowSlots(); }, 5000); // smooth bus movement

// ---- catch estimate: location only while waiting, only while visible, never sent.

/**
 * Before the user is at the stop: look at the location only every 1 to 3 minutes (nextCheckMs).
 * Live GPS only on the bus, or at the stop from 4 min before the bus can come (earlier when it
 * is or usually runs early). Same rules as the Android app.
 */
const NEAR_STOP_M = 150;
let nextFixAt = 0;
/** Those this bus is shared with by a code see where this phone is (at most every 20 s, only while the app is open). */
let lastShareAt = 0;
const sharePositions = {}; // the other side's last position, by share id / claim id
function publishShare(c) {
  const out = store.get('sharesOut', []);
  if (!out.length || Date.now() - lastShareAt < 20000 || c?.latitude == null) return;
  lastShareAt = Date.now();
  for (const sh of out) {
    outSockets[sh.id] ??= shareSocket('owner', sh.id, sh.token, (m) => {
      if (m.type === 'position') sharePositions[sh.id] = m.position;
      if (m.type === 'status' && ['cancelled', 'declined'].includes(m.status)) {
        outSockets[sh.id]?.close(); delete outSockets[sh.id];
        store.set('sharesOut', store.get('sharesOut', []).filter((x) => x.id !== sh.id));
      }
    });
    outSockets[sh.id].send({ type: 'position', lat: c.latitude, lon: c.longitude, accuracy: Math.round(c.accuracy || 0) });
  }
}
function shareTick() {
  if (!store.get('sharesOut', []).length || Date.now() - lastShareAt < 60000 || !('geolocation' in navigator)) return;
  navigator.geolocation.getCurrentPosition((pos) => publishShare(pos.coords), () => {}, { maximumAge: 30000, timeout: 20000 });
}

function updateLocation(p) {
  shareTick();
  const auto = communityMode() === 'auto' && config.hub && follow?.detail?.communityKey;
  if ((!auto && !catchOn()) || !('geolocation' in navigator)) return stopGeo();
  if (!p.onBoard) findStopCoords(p);
  const toStop = () => (fix?.latitude != null && stopCoords?.lat != null ? distanceMeters(fix.latitude, fix.longitude, stopCoords.lat, stopCoords.lon) : null);
  const nearStop = stopCoords?.none || (toStop() ?? Infinity) <= NEAR_STOP_M;
  const inWindow = Date.now() >= earliestBoarding(p) - LIVE_GPS_BEFORE_MS;
  if (auto && (p.onBoard || (inWindow && nearStop))) return startRiderWatch();
  if (riderWatch != null) { navigator.geolocation.clearWatch(riderWatch); riderWatch = null; }
  if (p.onBoard || Date.now() < nextFixAt) return;
  nextFixAt = Date.now() + 60000; // until this check answers
  navigator.geolocation.getCurrentPosition((pos) => {
    fix = pos.coords;
    publishShare(pos.coords);
    const m = toStop();
    nextFixAt = Date.now() + (m == null ? 60000 : nextCheckMs(m));
    renderAllFollowSlots();
  }, (err) => { if (err.code === 1) fix = { denied: true }; renderAllFollowSlots(); },
  { enableHighAccuracy: true, maximumAge: 20000, timeout: 20000 });
}
/** The follow's boarding time, counting a bus that is or usually runs early. */
const knownPosition = (d) => (follow.ref.scheduleUrl ? !!(d.community || follow.localDelay) : d.delaySeconds != null && d.positionSource !== 'history');
const earliestBoarding = (p) => earliestBoardingMs(effectiveDetail(follow), p, knownPosition(effectiveDetail(follow)));

function findStopCoords(p) {
  if (!stopCoords) {
    stopCoords = { pending: true };
    const name = p.boardingStopName;
    const byName = follow.ref.scheduleUrl || follow.detail?.fromTimetable; // timetable stops have made-up ids
    const fromPlatforms = !byName && live.stops
      ? live.stops.flatMap((s) => s.platforms).find((pl) => follow.boardingPlatformIds.includes(pl.id)) : null;
    (fromPlatforms ? Promise.resolve(fromPlatforms) : loadStops().then(() => {
      const pl = !byName && live.stops.flatMap((s) => s.platforms).find((x) => follow.boardingPlatformIds.includes(x.id));
      return pl || apiJson('/api/live/match?name=' + encodeURIComponent(name)).catch(() => null);
    })).then((c) => {
      // Timetable stops often aren't sadzv stops: use the route's own coordinates.
      const own = follow?.detail?.stops?.[p.boardingIndex];
      stopCoords = c ? { lat: c.lat, lon: c.lon } : own?.lat != null ? { lat: own.lat, lon: own.lon } : { none: true };
      renderAllFollowSlots();
    });
  }
}
function stopGeo() {
  if (riderWatch != null) navigator.geolocation.clearWatch(riderWatch);
  riderWatch = null;
}

// ---- automatic mode: the rider's phone on the bus marks where it is (only the stop is sent).
// A web app can use location only while it is open.

let riderWatch = null, lastRiderIndex = -1, lastRiderReportAt = 0, stopPass = stopPassDetector(), lastArrivalStop = -1;
const STOP_RADIUS_M = 60; // a bus standing this close to a stop is stopped there (timetable stop positions can be tens of metres off)
const MOVING_MPS = 2.5; // slower than this (about 9 km/h) the bus counts as stopped

function startRiderWatch() {
  if (riderWatch == null) {
    riderWatch = navigator.geolocation.watchPosition(onRiderFix, () => {}, { enableHighAccuracy: true, maximumAge: 5000, timeout: 20000 });
  }
}

/** It says where the bus is only once the rider is on it: on the route, past their stop, moving. */
function onRiderFix(pos) {
  fix = pos.coords;
  publishShare(pos.coords);
  const d = follow?.detail;
  if (!d?.stops?.length || d.stops.every((s) => s.lat == null)) return; // coordinates not loaded yet
  if (pos.coords.accuracy > 50) return;
  const p = tripProgress(effectiveDetail(follow), follow);
  const hit = snapToRoute(pos.coords.latitude, pos.coords.longitude, d.stops.map((s) => (s.lat != null ? [s.lat, s.lon] : null)), RIDER_GPS_MAX_M, p.position);
  if (!hit) return;
  const moving = pos.coords.speed != null ? pos.coords.speed >= MOVING_MPS : lastRiderIndex >= 0 && hit.index > lastRiderIndex + 0.05;
  lastRiderIndex = Math.max(lastRiderIndex, hit.index);
  const boarding = p.boardingIndex ?? 0;
  const now = Date.now();
  const scheduled = d.stops.map((s) => s.scheduledMs);
  const send = (stopIndex, scheduledMs, kind = 'gps') => {
    lastRiderReportAt = now;
    api('/api/hub/report', { tripKey: d.communityKey, line: follow.ref.line, stopIndex, stopName: d.stops[stopIndex].name,
      scheduledMs, reporter: follow.reporter, kind });
  };
  // Standing within a few tens of metres of a stop: the bus is stopped there.
  const nearest = Math.min(Math.max(Math.round(hit.index), 0), d.stops.length - 1);
  const ns = d.stops[nearest];
  const atStop = ns.lat != null && distanceMeters(pos.coords.latitude, pos.coords.longitude, ns.lat, ns.lon) <= STOP_RADIUS_M;
  // Left (after standing there) or drove past a stop: the exact delay there (timetable times are departures).
  const left = stopPass(hit.index, moving, now, atStop);
  if (left && left[0] >= boarding && left[0] < d.stops.length) {
    const [stop, at, stopped] = left;
    follow.localDelay = { seconds: Math.round((at - scheduled[stop]) / 1000), at: now, fromGps: true };
    renderAllFollowSlots();
    // The hub takes "now" from its own clock, so shift the stop time by how long ago it left.
    return send(stop, scheduled[stop] + (now - at), stopped ? 'departed' : 'passed');
  }
  // Stopped at a stop after the boarding one (so the rider is on the bus): it arrived there.
  // The hub counts an early arrival as on time: the bus waits for its departure.
  if (!moving && atStop && nearest > boarding && nearest > lastArrivalStop) {
    lastArrivalStop = nearest;
    return send(nearest, scheduled[nearest], 'arrival');
  }
  if (hit.index < boarding + 0.05 || !moving) return;
  follow.localDelay = { seconds: delayAt(hit.index, scheduled, now), at: now, fromGps: true };
  renderAllFollowSlots();
  if (now - lastRiderReportAt < 15000) return; // between stops: every 15 s
  send(Math.min(Math.floor(hit.index), d.stops.length - 1), referenceAt(hit.index, scheduled, now));
}

function catchLine(d, p) {
  if (!catchOn() || p.onBoard) return '';
  if (fix?.denied) return `<p class="catch muted">${t('catch_location_off')}</p>`;
  if (!fix || fix.accuracy > 300 || !stopCoords?.lat) return '';
  const known = knownPosition(d);
  if (!known && !store.get('catchTimetable', false)) return '';
  const seconds = Math.trunc((earliestBoarding(p) - Date.now()) / 1000);
  const meters = distanceMeters(fix.latitude, fix.longitude, stopCoords.lat, stopCoords.lon);
  const car = store.get('catchCar', false) && meters > 300;
  if (car) askDrive(fix);
  const e = catchEstimate(meters, seconds, car, car ? drive.seconds : null);
  const walk = Math.max(1, Math.round(e.walkSeconds / 60));
  const k = (key) => (car ? key.replace('catch_', 'catch_car_') : key);
  const text = { atStop: t('catch_at_stop'), relaxed: t(k('catch_leave_in'), walk, Math.round(e.marginSeconds / 60)), leaveSoon: t(k('catch_leave_in'), walk, Math.round(e.marginSeconds / 60)), leaveNow: t(k('catch_leave_now'), walk), miss: t(k('catch_miss'), walk) }[e.verdict];
  const byCar = store.get('catchCar', false);
  return `<p class="catch ${e.verdict}">${esc(known ? text : t('catch_by_timetable', text))}
    <button type="button" class="chip mode-toggle" data-car aria-label="${esc(t(byCar ? 'catch_mode_car' : 'catch_mode_walk'))}">${byCar ? CAR_SVG : WALK_SVG}</button></p>`;
}

// ---- community reports

async function report(index, leaving = false) {
  const d = follow?.detail;
  const stop = d?.stops[index];
  const key = d?.communityKey;
  if (!stop || !key || communityMode() !== 'buttons' || !config.hub) return;
  if (leaving) follow.leavingAt = Date.now(); else follow.hereAt = Date.now();
  // At the stop before departure time: it waits, so that's on time, not early.
  const arrival = !leaving && index === tripProgress(d, follow).boardingIndex;
  const seconds = Math.round((Date.now() - stop.scheduledMs) / 1000);
  follow.localDelay = { seconds: arrival ? Math.max(0, seconds) : seconds, at: Date.now(), fromGps: false };
  follow.note = t('community_thanks');
  saveFollow();
  renderAllFollowSlots();
  try {
    const r = await apiJson('/api/hub/report', { tripKey: key, line: follow.ref.line, stopIndex: index, stopName: stop.name, scheduledMs: stop.scheduledMs, reporter: follow.reporter, kind: arrival ? 'arrival' : 'position' });
    if (r.delay && follow?.detail) follow.detail.community = r.delay;
  } catch {
    if (follow) follow.note = t('community_failed');
  }
  renderAllFollowSlots();
}

// ---- push alerts

const b64ToBytes = (s) => Uint8Array.from(atob((s + '='.repeat((4 - (s.length % 4)) % 4)).replace(/-/g, '+').replace(/_/g, '/')), (c) => c.charCodeAt(0));

async function subscribePush(silent) {
  if (!follow) return;
  if (!('serviceWorker' in navigator) || !('PushManager' in window) || !config.vapidPublicKey) {
    if (!silent) toast(t('follow_push_ios'));
    return;
  }
  const permission = await Notification.requestPermission();
  if (permission !== 'granted') { if (!silent) toast(t('follow_push_denied')); return; }
  try {
    const reg = await navigator.serviceWorker.ready;
    const subscription = await reg.pushManager.getSubscription() || await reg.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: b64ToBytes(config.vapidPublicKey) });
    const r = await apiJson('/api/follow', {
      subscription: subscription.toJSON(), ref: follow.ref, boardingPlatformIds: follow.boardingPlatformIds,
      alightOrder: follow.alightOrder, lang: lang(), community: communityMode() !== 'off',
      arriveLeadMinutes: store.get('arriveLead', 2),
    });
    follow.pushId = r.id;
    saveFollow();
  } catch (err) {
    log('W', 'push', err.message);
    if (!silent) toast(t('follow_push_ios'));
  }
  renderAllFollowSlots();
}

// ---- follow card (ticket tab, departures, board)

function progressTrack(d, p) {
  const hops = Math.max(1, d.stops.length - 1);
  const pct = (i) => `${(i / hops) * 100}%`;
  const points = [p.boardingIndex, p.alightIndex].filter((i) => i != null && i >= 0 && i < d.stops.length);
  return `<div class="track" role="img" aria-label="${esc(p.currentStopName || p.nextStopName || '')}">
    <div class="track-segs">${'<span></span>'.repeat(hops)}</div>
    <div class="track-fill" style="width:${pct(p.position)}"></div>
    ${[...new Set(points)].map((i) => `<i class="track-point ${i <= p.position ? 'done' : ''}" style="left:${pct(i)}"></i>`).join('')}
    <span class="track-bus" style="left:${pct(p.position)}">${BUS_SVG}</span></div>`;
}

const CROWD = ['crowd_1', 'crowd_2', 'crowd_3'];
/**
 * 2.17.0: how full the bus is, from riders on it; said in one tap once the bus has left your stop with you on it (again
 * whenever it changes). Not before: a bus coming or standing at the stop doesn't count.
 */
function crowdHtml(d, p) {
  const c = d.crowd;
  const canReport = config.hub && d.communityKey && p.onBoard && !p.finished;
  if (!c && !canReport) return '';
  return `<div class="crowd">${c ? `<p><b>${esc(t('crowd_now', t(CROWD[Math.min(Math.max(c.level, 1), 3) - 1]), c.minutesAgo))}</b></p>` : ''}
    ${canReport ? `<p class="hint small">${t(Date.now() - (follow.crowdAt || 0) < 300000 ? 'crowd_thanks' : 'crowd_title')}</p>
      <div class="follow-actions">${CROWD.map((k, i) => `<button class="chip ${c?.level === i + 1 ? 'on' : ''}" data-crowd="${i + 1}">${t(k)}</button>`).join('')}</div>` : ''}</div>`;
}

async function reportCrowd(level) {
  const d = follow?.detail;
  if (!d?.communityKey || Date.now() - (follow.crowdAt || 0) < 20000) return; // a double tap is one report
  follow.crowdAt = Date.now();
  const r = await apiJson('/api/hub/crowd', { tripKey: d.communityKey, reporter: follow.reporter, level }).catch(() => null);
  if (follow?.detail) follow.detail.crowd = r?.crowd || { level, minutesAgo: 0 };
  saveFollow();
  renderAllFollowSlots();
}

/** 2.17.0: a link with your arrival, live, for anyone (no app needed); kept current while following, ended on arrival. */
async function shareEta() {
  if (!follow) return;
  if (!follow.eta) {
    const d = follow.detail;
    const stop = follow.nextLegs?.at(-1)?.toStop || follow.toStop || (d && tripProgress(d, follow).alightStopName) || d?.destination || '';
    follow.eta = await apiJson('/api/hub/eta', { stop }).catch(() => null);
    if (!follow.eta) return toast(t('eta_failed'));
    follow.etaSent = '';
    saveFollow();
    sendEta();
  }
  const text = t('eta_share_text', follow.eta.url);
  if (navigator.share) navigator.share({ text }).catch(() => {});
  else navigator.clipboard?.writeText(text).then(() => toast(t('copied')));
  renderAllFollowSlots();
}
function sendEta() {
  const f = follow, d = f && effectiveDetail(f);
  if (!f?.eta || !d?.stops?.length) return;
  const p = tripProgress(d, f);
  const s = d.stops[p.alightIndex ?? d.stops.length - 1];
  // ponytail: a journey's later buses have only a clock time here; the link follows the bus ridden now
  const arrivalMs = s.actualMs || s.scheduledMs + (d.delaySeconds || 0) * 1000;
  const key = `${arrivalMs}|${d.delaySeconds || 0}`;
  if (key === f.etaSent && Date.now() - (f.etaAt || 0) < 120000) return;
  Object.assign(f, { etaSent: key, etaAt: Date.now() });
  api(`/api/hub/eta/${f.eta.id}`, { token: f.eta.token, arrivalMs, delaySeconds: d.delaySeconds || 0, line: f.ref.line });
}

function followCardHtml() {
  const d = follow && effectiveDetail(follow);
  if (!d?.stops?.length) return follow ? `<section class="follow"><p class="hint"><span class="spinner"></span> ${plate(follow.ref.line)} ${esc(follow.ref.destination)}</p></section>` : '';
  const p = tripProgress(d, follow);
  const f = follow;
  let delay;
  if (f.localDelay && d.delaySeconds === f.localDelay.seconds) delay = `${delayChip(d.delaySeconds)} <span class="hint small">${t(f.localDelay.fromGps ? 'community_your_phone' : 'community_yours')}</span>`;
  else if (d.community && (d.positionSource === 'riderGps' || d.positionSource === 'riders')) delay = `${delayChip(d.delaySeconds)} <span class="hint small">${esc(ridersText(d.community.reporters))}</span>`;
  else if (d.positionSource === 'history') delay = `${delayChip(d.delaySeconds)} <span class="hint small">${t('history_estimate')}</span>`;
  else if (d.positionSource === 'timetable' && d.history) delay = historyHint(d);
  else if (d.positionSource === 'timetable' && f.ref.scheduleUrl) delay = `<span class="hint small">${t('schedule_only')}</span>`;
  else delay = delayChip(d.delaySeconds);

  const title = p.onBoard
    ? t('follow_next', p.nextStopName || d.destination, clock(p.nextExpectedMs))
    : t('follow_waiting', clock(p.boardingExpectedMs), p.boardingStopName);
  const then = f.nextLegs?.[0] ? t('journey_then', f.nextLegs[0].ref.line, f.nextLegs[0].ref.destination) : '';
  const sub = [
    p.onBoard && p.currentStopName && t('follow_now_at', p.currentStopName),
    p.onBoard && p.alightStopName && t('follow_get_off', p.alightStopName),
    then,
  ].filter(Boolean).join(' · ');
  const big = countdown(p.onBoard ? p.secondsToNext : p.secondsToBoarding);
  // Whole name ("Detva, aut.st."), shortened only when it would not fit on the button.
  const short = (n) => (n.length > 18 ? n.slice(0, 17).replace(/[, ]+$/, '') + '…' : n);
  let actions = '';
  // Only when the bus can actually be seen: from 30 min before it is due at the user's stop.
  if (buttonsOn() && (p.onBoard || p.secondsToBoarding <= 30 * 60)) {
    // "Bus is here", "Bus is leaving" (the departure moment), then "At <next stop>".
    const after = d.stops[p.boardingIndex + 1];
    if (!p.onBoard && p.boardingIndex != null && f.leavingAt && after) {
      actions = `<button class="chip" data-report="${p.boardingIndex + 1}">${esc(t('community_at', short(after.name)))}</button>`;
    } else if (!p.onBoard && p.boardingIndex != null && !f.leavingAt) {
      actions = f.hereAt
        ? `<button class="chip" data-report="${p.boardingIndex}" data-leaving="1">${t('community_leaving')}</button>`
        : `<button class="chip" data-report="${p.boardingIndex}">${t('community_here')}</button>`;
    }
    else if (p.onBoard) {
      if (d.stops[p.nextIndex]) actions += `<button class="chip" data-report="${p.nextIndex}">${esc(t('community_at', short(d.stops[p.nextIndex].name)))}</button>`;
      if (d.stops[p.nextIndex - 1]) actions += `<button class="chip" data-report="${p.nextIndex - 1}">${esc(t('community_still_at', short(d.stops[p.nextIndex - 1].name)))}</button>`;
    }
  }
  return `<section class="follow" aria-live="polite">
    <div class="follow-top">${plate(f.ref.line)}<span class="follow-dest">${esc(d.destination)}</span><strong class="follow-big">${esc(big)}</strong></div>
    <p class="follow-title">${esc(title)}</p>
    ${sub ? `<p class="follow-sub">${esc(sub)}</p>` : ''}
    ${f.nextLegs?.length ? `<ul class="follow-legs">${[
      f.toStop && f.arrives ? `<li><strong>${esc(t('journey_get_off', f.toStop, f.arrives))}</strong></li>` : '',
      ...f.nextLegs.map((l) => `<li>${esc(t('journey_next_leg', l.ref.line, [l.leaves && `${l.leaves} ${l.fromStop || ''}`.trim(), l.toStop && `${l.toStop} ${l.arrives || ''}`.trim()].filter(Boolean).join(' → ')))}</li>`),
    ].join('')}</ul>` : ''}
    <div class="follow-delay">${delay}</div>
    ${catchLine(d, p)}
    ${progressTrack(d, p)}
    ${f.note ? `<p class="hint small">${esc(f.note)}</p>` : ''}
    ${actions ? `<div class="follow-actions">${actions}</div>` : ''}
    ${crowdHtml(d, p)}
    <div class="follow-foot">
      <label class="mini-switch"><input type="checkbox" data-push ${f.pushId ? 'checked' : ''}> ${t('follow_push')}</label>
      <span>${config.hub ? `<button class="text-btn" data-eta>${t(f.eta ? 'eta_sharing' : 'eta_share')}</button>` : ''}<button class="text-btn" data-open-trip>${t('show_stops')}</button><button class="text-btn danger" data-stop>${t('unfollow')}</button></span>
    </div></section>`;
}

const slots = new Set();
export function renderFollowSlot(el) {
  if (!el) return;
  slots.add(el);
  el.innerHTML = followCardHtml();
  el.onclick = (e) => {
    const r = e.target.closest('[data-report]');
    if (r) return report(Number(r.dataset.report), !!r.dataset.leaving);
    const crowd = e.target.closest('[data-crowd]');
    if (crowd) return reportCrowd(Number(crowd.dataset.crowd));
    if (e.target.closest('[data-eta]')) return shareEta();
    if (e.target.closest('[data-stop]')) return stopFollow();
    // EM-0072: on foot or by car today, one tap.
    if (e.target.closest('[data-car]')) { store.set('catchCar', !store.get('catchCar', false)); drive.at = 0; return renderAllFollowSlots(); }
    if (e.target.closest('[data-open-trip]') && follow) return openTrip(follow.ref, { boardingPlatformIds: follow.boardingPlatformIds, alightOrder: follow.alightOrder });
  };
  el.onchange = (e) => {
    if (!e.target.matches('[data-push]')) return;
    if (e.target.checked) subscribePush(false);
    else { api('/api/unfollow', { id: follow.pushId }); follow.pushId = null; saveFollow(); }
  };
}
function renderAllFollowSlots() {
  for (const el of slots) {
    if (!el.isConnected) { slots.delete(el); continue; }
    if (el.contains(document.activeElement) && document.activeElement.matches('input')) continue;
    el.innerHTML = followCardHtml();
  }
}

export function resumeFollow() {
  if (follow) followTick();
}
