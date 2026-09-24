// Departures, stop boards, trip sheet and "follow this bus" for the web client.
import { t, lang } from './i18n.js';
import { $, $$, esc, api, apiJson, store, config, clock, countdown, delayChip, delayInfo, plate, toast, openSheet, closeOverlay, log } from './core.js';
import { tripProgress, catchEstimate, communityKey, distanceMeters, readableStop, lineNumber } from './logic.js';

const BOARD_REFRESH_MS = 15000;
const FOLLOW_REFRESH_MS = 15000;
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
export function renderDepartures(el, { guest } = {}) {
  root = el;
  if (live.board) return renderBoard();
  el.innerHTML = `
    <header class="screen-head"><h1>${t('departures_title')}</h1>${guest ? `<button class="text-btn" data-signin>${t('sign_in')}</button>` : ''}</header>
    <div id="follow-slot-dep"></div>
    <div class="search"><input type="search" id="stop-q" placeholder="${esc(t('search_stop'))}" value="${esc(live.query)}" autocomplete="off" aria-label="${esc(t('search_stop'))}"></div>
    <div id="dep-results"></div>`;
  const q = $('#stop-q', el);
  q.oninput = () => { live.query = q.value; renderResults(); };
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
  const r = await api('/api/live/departures', { platformIds: board.stop.platforms.map((p) => p.id) });
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
        <span class="dep-sub">${d.platformNumber ? esc(t('platform', d.platformNumber)) : ''} ${d.isCancelled ? `<span class="delay very">${t('cancelled')}</span>` : delayChip(d.delaySeconds)}</span></span>
      <span class="dep-time"><strong>${esc(countdown(secs))}</strong><span>${clock(d.departsAtMs)}${d.isRealtime ? ` <i class="live-dot" title="${esc(t('live'))}"></i>` : ''}</span></span>
    </button></li>`;
  }).join('');
  root.innerHTML = `
    <header class="screen-head board-head"><button class="icon-btn" data-back aria-label="${esc(t('back'))}">‹</button><h1>${esc(b.stop.name)}</h1>
      <button class="icon-btn star ${fav ? 'on' : ''}" data-fav aria-pressed="${fav}" aria-label="★">${STAR(fav)}</button></header>
    <div id="follow-slot-board"></div>
    ${b.loading ? `<p class="hint"><span class="spinner"></span></p>` : b.error && !b.departures.length
      ? `<div class="notice error">${t('board_failed')} <button class="text-btn" data-refresh>${t('retry')}</button></div>`
      : b.departures.length ? `<ul class="board">${rows}</ul>` : `<div class="empty"><p>${t('board_empty')}</p></div>`}
    ${b.updatedAt ? `<p class="hint small">${t('updated', clock(b.updatedAt))}</p>` : ''}`;
  renderFollowSlot($('#follow-slot-board', root));
  root.onclick = (e) => {
    if (e.target.closest('[data-back]')) return closeBoard();
    if (e.target.closest('[data-fav]')) { toggleFavourite(b.stop.id); live.onChange(); return renderBoard(); }
    if (e.target.closest('[data-refresh]')) return refreshBoard();
    const dep = e.target.closest('[data-dep]');
    if (dep) {
      const d = b.departures[Number(dep.dataset.dep)];
      openTrip({ line: d.line, lineId: d.lineId, routeNumber: d.routeNumber, tripNumber: d.tripNumber, destination: d.destination },
        { boardingPlatformIds: b.stop.platforms.map((p) => p.id) });
    }
  };
}

// Countdowns move between refreshes.
setInterval(() => { if (live.board && !document.hidden && root?.isConnected && !$('#sheet')) renderBoard(); }, 20000);

/** Next three buses from the first favourite stop, for the ticket tab. */
export async function renderPreview(el) {
  await loadStops();
  const stop = favourites()[0];
  if (!stop) { el.innerHTML = `<p class="hint">${t('ticket_next_hint')}</p>`; return; }
  const r = await api('/api/live/departures', { platformIds: stop.platforms.map((p) => p.id) });
  const deps = r.ok ? r.json.slice(0, 3) : [];
  el.innerHTML = `<h2 class="section-label">${esc(t('ticket_next_from', stop.name))}</h2>
    <ul class="board compact">${deps.map((d) => `<li><div class="departure">${plate(d.line)}<span class="dep-main"><span class="dep-dest">${esc(d.destination)}</span><span class="dep-sub">${delayChip(d.delaySeconds)}</span></span>
      <span class="dep-time"><strong>${esc(countdown((d.departsAtMs - Date.now()) / 1000))}</strong><span>${clock(d.departsAtMs)}</span></span></div></li>`).join('')}</ul>`;
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
    if (detail.alightOrder) sheetTrip.alightOrder = detail.alightOrder;
    renderTripSheet();
  }).catch(() => { if (sheetTrip) Object.assign(sheetTrip, { loading: false, error: true }); renderTripSheet(); });
}

/** A bus from a planner result: stops and times from cp.sk. */
export function openScheduledTrip(segment) {
  if (!segment.routeUrl) return;
  openTrip({
    line: lineNumber(segment.line), lineId: 0, routeNumber: '', tripNumber: 0,
    destination: readableStop(segment.arrivalStop), scheduleUrl: segment.routeUrl, serviceDate: segment.serviceDate,
  });
}

/** Trip + community delay for timetable-only buses (only for riders who opted in). */
async function loadTrip(ref) {
  const detail = await apiJson('/api/live/trip', ref);
  const key = ref.scheduleUrl && store.get('communityOn', false) && config.hub ? communityKey(detail) : null;
  if (key) {
    const pooled = await apiJson('/api/hub/delay?trip=' + encodeURIComponent(key)).catch(() => null);
    if (pooled?.delay) { detail.delaySeconds = pooled.delay.delaySeconds; detail.community = pooled.delay; }
  }
  return detail;
}

const sameRef = (a, b) => a && b && JSON.stringify(a) === JSON.stringify(b);

function renderTripSheet(body = $('#sheet .sheet-body')) {
  if (!body || !sheetTrip) return;
  const s = sheetTrip;
  const following = sameRef(follow?.ref, s.ref);
  const detail = following && follow.detail ? effectiveDetail(follow) : s.detail;
  const alight = following ? follow.alightOrder : s.alightOrder;
  const status = s.ref.scheduleUrl
    ? (detail?.community ? `${delayChip(detail.delaySeconds)} <span class="hint small">${esc(ridersText(detail.community.reporters))}</span>` : `<span class="hint small">${t('schedule_only')}</span>`)
    : delayChip(detail?.delaySeconds);
  let timeline = '';
  if (s.loading) timeline = '<p class="hint"><span class="spinner"></span></p>';
  else if (s.error || !detail?.stops?.length) timeline = `<div class="empty"><p>${t('trip_unavailable')}</p></div>`;
  else {
    const p = tripProgress(detail, { boardingPlatformIds: s.boardingPlatformIds, alightOrder: alight });
    timeline = `<ol class="timeline">${detail.stops.map((stop, i) => {
      const passed = i < p.nextIndex, next = i === p.nextIndex, isAlight = stop.order === alight;
      const exp = p.expected[i];
      const shift = (stop.actualMs != null || detail.delaySeconds != null) && clock(exp) !== clock(stop.scheduledMs);
      return `<li class="${passed ? 'passed' : ''} ${next ? 'next' : ''} ${isAlight ? 'alight' : ''}">
        <button ${following && !passed ? `data-alight="${stop.order}"` : 'disabled'}>
          <span class="dot"></span><span class="stop-name">${esc(stop.name)}${isAlight ? `<small>${t('your_stop')}</small>` : ''}${stop.platform ? `<small class="stop-pf">${esc(t('platform', stop.platform))}</small>` : ''}</span>
          <span class="stop-time">${shift ? `<s>${clock(stop.scheduledMs)}</s>${clock(exp)}` : clock(stop.scheduledMs)}</span></button></li>`;
    }).join('')}</ol>`;
  }
  body.innerHTML = `
    <div class="trip-head">${plate(s.ref.line, 'big')}<div><h3>${esc(detail?.destination || s.ref.destination)}</h3><div>${status}</div></div></div>
    ${following
      ? `<p class="hint">${t('following_hint')}</p><button class="btn outline wide" data-unfollow>${t('unfollow')}</button>`
      : `<button class="btn wide" data-follow ${detail?.stops?.length ? '' : 'disabled'}>${BUS_SVG}${t('follow')}</button>`}
    ${timeline}`;
  body.onclick = (e) => {
    if (e.target.closest('[data-follow]')) {
      startFollow({ ref: s.ref, boardingPlatformIds: s.boardingPlatformIds, alightOrder: s.alightOrder, detail: s.detail });
    }
    if (e.target.closest('[data-unfollow]')) stopFollow();
    const a = e.target.closest('[data-alight]');
    if (a) setAlight(Number(a.dataset.alight));
    renderTripSheet(body);
  };
  const next = $('.timeline li.next', body);
  if (next && !body.dataset.scrolled) { body.dataset.scrolled = '1'; next.scrollIntoView({ block: 'center' }); }
}

const ridersText = (n) => (n === 1 ? t('community_rider') : t('community_riders', n));

// ------------------------------------------------------------------ follow a bus

let follow = store.get('follow', null); // { ref, boardingPlatformIds, alightOrder, reporter, localDelay, pushId, startedAt }
let followTimer = null;
let fix = null; // latest position (stays on the phone)
let geoWatch = null;
let stopCoords = null;

const communityOn = () => follow?.ref.scheduleUrl && store.get('communityOn', false) && config.hub;
const catchOn = () => store.get('catchOn', false);

function saveFollow() {
  if (!follow) return store.set('follow', null);
  const { detail, ...persist } = follow;
  store.set('follow', persist);
}

export function startFollow({ ref, boardingPlatformIds, alightOrder, detail }) {
  if (follow?.pushId) api('/api/unfollow', { id: follow.pushId });
  follow = {
    ref, boardingPlatformIds, alightOrder, detail,
    // New random id per bus, so reports can't be linked across trips. (randomUUID is missing on older iOS.)
    reporter: [...crypto.getRandomValues(new Uint8Array(16))].map((b) => b.toString(16).padStart(2, '0')).join(''),
    localDelay: null, pushId: null, startedAt: Date.now(), note: '',
  };
  stopCoords = null;
  saveFollow();
  followTick();
  live.onChange();
}

export function stopFollow() {
  if (follow?.pushId) api('/api/unfollow', { id: follow.pushId });
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
    const detail = await loadTrip(follow.ref);
    if (follow && detail.stops.length) follow.detail = detail;
  } catch (err) { log('W', 'follow', err.message); }
  if (!follow) return;
  const d = effectiveDetail(follow);
  if (d?.stops.length) {
    const p = tripProgress(d, follow);
    if (p.finished) {
      toast(t('follow_arrived', p.lastStopName));
      return stopFollow();
    }
    updateGeo(p);
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

function updateGeo(p) {
  if (!catchOn() || p.onBoard || !('geolocation' in navigator)) return stopGeo();
  if (geoWatch == null) {
    geoWatch = navigator.geolocation.watchPosition((pos) => { fix = pos.coords; renderAllFollowSlots(); },
      (err) => { if (err.code === 1) fix = { denied: true }; renderAllFollowSlots(); },
      { enableHighAccuracy: true, maximumAge: 30000, timeout: 20000 });
  }
  if (!stopCoords) {
    stopCoords = { pending: true };
    const name = p.boardingStopName;
    const fromPlatforms = !follow.ref.scheduleUrl && live.stops
      ? live.stops.flatMap((s) => s.platforms).find((pl) => follow.boardingPlatformIds.includes(pl.id)) : null;
    (fromPlatforms ? Promise.resolve(fromPlatforms) : loadStops().then(() => {
      const pl = !follow?.ref.scheduleUrl && live.stops.flatMap((s) => s.platforms).find((x) => follow.boardingPlatformIds.includes(x.id));
      return pl || apiJson('/api/live/match?name=' + encodeURIComponent(name)).catch(() => null);
    })).then((c) => { stopCoords = c ? { lat: c.lat, lon: c.lon } : { none: true }; renderAllFollowSlots(); });
  }
}
function stopGeo() {
  if (geoWatch != null) navigator.geolocation.clearWatch(geoWatch);
  geoWatch = null;
}

function catchLine(d, p) {
  if (!catchOn() || p.onBoard) return '';
  if (fix?.denied) return `<p class="catch muted">${t('catch_location_off')}</p>`;
  if (!fix || fix.accuracy > 300 || !stopCoords?.lat) return '';
  const known = follow.ref.scheduleUrl ? !!(d.community || follow.localDelay) : d.delaySeconds != null;
  if (!known && !store.get('catchTimetable', false)) return '';
  const e = catchEstimate(distanceMeters(fix.latitude, fix.longitude, stopCoords.lat, stopCoords.lon), p.secondsToBoarding);
  const walk = Math.max(1, Math.round(e.walkSeconds / 60));
  const text = { atStop: t('catch_at_stop'), relaxed: t('catch_leave_in', walk, Math.round(e.marginSeconds / 60)), leaveSoon: t('catch_leave_in', walk, Math.round(e.marginSeconds / 60)), leaveNow: t('catch_leave_now', walk), miss: t('catch_miss', walk) }[e.verdict];
  return `<p class="catch ${e.verdict}">${esc(known ? text : t('catch_by_timetable', text))}</p>`;
}

// ---- community reports

async function report(index, leaving = false) {
  const d = follow?.detail;
  const stop = d?.stops[index];
  const key = d && communityKey(d);
  if (!stop || !key || !communityOn()) return;
  if (leaving) follow.leavingAt = Date.now(); else follow.hereAt = Date.now();
  // At the stop before departure time: it waits, so that's on time, not early.
  const arrival = !leaving && index === tripProgress(d, follow).boardingIndex;
  const seconds = Math.round((Date.now() - stop.scheduledMs) / 1000);
  follow.localDelay = { seconds: arrival ? Math.max(0, seconds) : seconds, at: Date.now() };
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
      alightOrder: follow.alightOrder, lang: lang(), community: !!communityOn(),
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

function followCardHtml() {
  const d = follow && effectiveDetail(follow);
  if (!d?.stops?.length) return follow ? `<section class="follow"><p class="hint"><span class="spinner"></span> ${plate(follow.ref.line)} ${esc(follow.ref.destination)}</p></section>` : '';
  const p = tripProgress(d, follow);
  const f = follow;
  let delay;
  if (!f.ref.scheduleUrl) delay = delayChip(d.delaySeconds);
  else if (f.localDelay && d.delaySeconds === f.localDelay.seconds) delay = `${delayChip(d.delaySeconds)} <span class="hint small">${t('community_yours')}</span>`;
  else if (d.community) delay = `${delayChip(d.delaySeconds)} <span class="hint small">${esc(ridersText(d.community.reporters))}</span>`;
  else delay = `<span class="hint small">${t('schedule_only')}</span>`;

  const title = p.onBoard
    ? t('follow_next', p.nextStopName || d.destination, clock(p.nextExpectedMs))
    : t('follow_waiting', clock(p.boardingExpectedMs), p.boardingStopName);
  const sub = p.onBoard
    ? [p.currentStopName && t('follow_now_at', p.currentStopName), p.alightStopName && t('follow_get_off', p.alightStopName)].filter(Boolean).join(' · ')
    : '';
  const big = countdown(p.onBoard ? p.secondsToNext : p.secondsToBoarding);
  // Whole name ("Detva, aut.st."), shortened only when it would not fit on the button.
  const short = (n) => (n.length > 18 ? n.slice(0, 17).replace(/[, ]+$/, '') + '…' : n);
  let actions = '';
  // Only when the bus can actually be seen: from 30 min before it is due at the user's stop.
  if (communityOn() && (p.onBoard || p.secondsToBoarding <= 30 * 60)) {
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
    <div class="follow-delay">${delay}</div>
    ${catchLine(d, p)}
    ${progressTrack(d, p)}
    ${f.note ? `<p class="hint small">${esc(f.note)}</p>` : ''}
    ${actions ? `<div class="follow-actions">${actions}</div>` : ''}
    <div class="follow-foot">
      <label class="mini-switch"><input type="checkbox" data-push ${f.pushId ? 'checked' : ''}> ${t('follow_push')}</label>
      <span><button class="text-btn" data-open-trip>${t('show_stops')}</button><button class="text-btn danger" data-stop>${t('unfollow')}</button></span>
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
    if (e.target.closest('[data-stop]')) return stopFollow();
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
