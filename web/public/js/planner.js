// cp.sk planner for the web client (same flow as the Android Planner tab).
import { t } from './i18n.js';
import { $, esc, apiJson, store, plate, toast } from './core.js';
import { readableStop, lineNumber } from './logic.js';
import { openScheduledTrip } from './live.js';

const CITIES = [['slovensko', () => t('city_slovakia')], ['banskabystrica', () => 'B. Bystrica'], ['zvolen', () => 'Zvolen']];
const SWAP = '<svg viewBox="0 0 24 24" aria-hidden="true"><path fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" d="M7 4v16M7 20l-3-3M7 20l3-3M17 20V4M17 4l-3 3M17 4l3 3"/></svg>';

const p = {
  city: store.get('plannerCity', 'slovensko'), from: '', to: '', fromSug: null, toSug: null, time: '', direct: false,
  results: [], cursor: null, loading: false, loadingMore: false, error: '', searched: false,
};
let root = null;
let onOpenStop = () => {};

const routes = (key) => store.get(key, []);
const sameRoute = (a, b) => a.from.toLowerCase() === b.from.toLowerCase() && a.to.toLowerCase() === b.to.toLowerCase();

export function renderPlanner(el, { openStop, guest } = {}) {
  root = el;
  onOpenStop = openStop || onOpenStop;
  const saved = routes('savedRoutes');
  const isSaved = p.from && p.to && saved.some((r) => sameRoute(r, { from: p.from.trim(), to: p.to.trim() }));
  el.innerHTML = `
    <header class="screen-head"><div><h1>${t('planner_title')}</h1><p class="hint small">${t('planner_sub')}</p></div>${guest ? `<button class="text-btn" data-signin>${t('sign_in')}</button>` : ''}</header>
    <div class="segmented" role="radiogroup">${CITIES.map(([slug, label]) => `<button role="radio" aria-checked="${p.city === slug}" data-city="${slug}">${esc(label())}</button>`).join('')}</div>
    <form class="planner-form" autocomplete="off">
      <div class="od">
        <label class="od-field"><span>${t('from')}</span><input id="pl-from" value="${esc(p.from)}" required></label>
        <label class="od-field"><span>${t('to')}</span><input id="pl-to" value="${esc(p.to)}" required></label>
        <button type="button" class="icon-btn swap" data-swap aria-label="${esc(t('swap'))}">${SWAP}</button>
        <ul class="suggest" id="pl-suggest" role="listbox" hidden></ul>
      </div>
      <div class="planner-opts">
        <div class="segmented small"><button type="button" aria-pressed="${!p.time}" data-now>${t('leave_now')}</button>
          <label class="time-pick ${p.time ? 'on' : ''}">${t('at_time')} <input type="time" id="pl-time" value="${esc(p.time)}"></label></div>
        <label class="mini-switch"><input type="checkbox" id="pl-direct" ${p.direct ? 'checked' : ''}> ${t('direct_only')}</label>
      </div>
      <div class="planner-actions">
        <button class="btn" type="submit" ${p.loading ? 'disabled' : ''}>${p.loading ? '<span class="spinner"></span>' : t('search')}</button>
        <button class="icon-btn star ${isSaved ? 'on' : ''}" type="button" data-save aria-pressed="${!!isSaved}" aria-label="${esc(t(isSaved ? 'unsave_route' : 'save_route'))}" ${p.from && p.to ? '' : 'disabled'}>★</button>
      </div>
    </form>
    <div id="pl-body"></div>`;
  bind(el);
  renderBody();
}

function renderBody() {
  const box = $('#pl-body', root);
  if (!box) return;
  if (p.error) { box.innerHTML = `<div class="notice error">${esc(p.error)}</div>`; return; }
  if (p.results.length) {
    box.innerHTML = `<ul class="connections">${p.results.map(connectionHtml).join('')}</ul>
      ${p.cursor ? `<button class="btn outline wide" data-more ${p.loadingMore ? 'disabled' : ''}>${p.loadingMore ? '<span class="spinner"></span>' : t('load_more')}</button>` : ''}`;
    return;
  }
  if (p.searched && !p.loading) { box.innerHTML = `<div class="empty"><p>${t('no_connections')}</p></div>`; return; }
  const chips = (list, key) => list.map((r, i) => `<button class="chip route" data-route="${key}:${i}">${esc(readableStop(r.from))} – ${esc(readableStop(r.to))}</button>`).join('');
  const saved = routes('savedRoutes'), recent = routes('recentRoutes');
  box.innerHTML = (saved.length ? `<h2 class="section-label">${t('saved_routes')}</h2><div class="chips">${chips(saved, 'savedRoutes')}</div>` : '') +
    (recent.length ? `<h2 class="section-label">${t('recent')}</h2><div class="chips">${chips(recent, 'recentRoutes')}</div>` : '');
}

function connectionHtml(c, ci) {
  const segs = c.segments.map((s, si) => `
    <li class="seg">
      <div class="seg-line">${plate(lineNumber(s.line) || '·')}<span class="hint small">${esc(s.operatorName)}</span></div>
      <div class="seg-stops">
        <button class="seg-stop" data-stop="${ci}:${si}:dep"><b>${esc(s.departureTime)}</b> ${esc(readableStop(s.departureStop))}${s.departurePlatform ? ` <span class="pf">${esc(t('platform', s.departurePlatform))}</span>` : ''}</button>
        <button class="seg-stop" data-stop="${ci}:${si}:arr"><b>${esc(s.arrivalTime)}</b> ${esc(readableStop(s.arrivalStop))}${s.arrivalPlatform ? ` <span class="pf">${esc(t('platform', s.arrivalPlatform))}</span>` : ''}</button>
      </div>
      ${s.routeUrl ? `<button class="text-btn" data-trip="${ci}:${si}">${t('show_stops')}</button>` : ''}
    </li>`).join('');
  return `<li class="connection">
    <div class="conn-head"><span class="conn-times">${esc(c.departureTime)} – ${esc(c.arrivalTime)}</span><span class="hint">${esc(c.totalDuration)}</span>
      <button class="icon-btn" data-share="${ci}" aria-label="${esc(t('share'))}">⤴</button></div>
    <ol class="segs">${segs}</ol></li>`;
}

const shareText = (c) => `${c.departureTime} – ${c.arrivalTime} (${c.totalDuration})\n` +
  c.segments.map((s) => `${s.line}: ${s.departureTime} ${readableStop(s.departureStop)} → ${s.arrivalTime} ${readableStop(s.arrivalStop)}`).join('\n');

function bind(el) {
  const from = $('#pl-from', el), to = $('#pl-to', el), sug = $('#pl-suggest', el);
  let active = null, timer = null;
  const suggest = (input, which) => {
    p[which] = input.value;
    p[which + 'Sug'] = null;
    clearTimeout(timer);
    if (input.value.trim().length < 2) { sug.hidden = true; return; }
    timer = setTimeout(async () => {
      const list = await apiJson(`/api/cp/suggest?city=${p.city}&q=${encodeURIComponent(input.value)}`).catch(() => []);
      if (document.activeElement !== input) return;
      active = { input, which, list };
      sug.innerHTML = list.map((s, i) => `<li role="option"><button type="button" data-sug="${i}">${esc(s.text)}${s.description ? `<small>${esc(s.description)}</small>` : ''}</button></li>`).join('');
      sug.dataset.which = which;
      sug.hidden = !list.length;
    }, 250);
  };
  from.oninput = () => suggest(from, 'from');
  to.oninput = () => suggest(to, 'to');
  sug.onpointerdown = (e) => e.preventDefault(); // keep focus so the tap lands
  sug.onclick = (e) => {
    const b = e.target.closest('[data-sug]');
    if (!b || !active) return;
    const s = active.list[Number(b.dataset.sug)];
    active.input.value = s.text;
    p[active.which] = s.text;
    p[active.which + 'Sug'] = s;
    sug.hidden = true;
    (active.which === 'from' ? to : null)?.focus();
  };
  [from, to].forEach((i) => (i.onblur = () => setTimeout(() => (sug.hidden = true), 150)));
  $('#pl-time', el).onchange = (e) => { p.time = e.target.value; renderPlanner(root); };
  $('#pl-direct', el).onchange = (e) => (p.direct = e.target.checked);
  $('form', el).onsubmit = (e) => { e.preventDefault(); search(); };
  el.onclick = (e) => {
    const city = e.target.closest('[data-city]');
    if (city) { p.city = city.dataset.city; store.set('plannerCity', p.city); p.fromSug = p.toSug = null; return renderPlanner(root); }
    if (e.target.closest('[data-swap]')) {
      [p.from, p.to, p.fromSug, p.toSug] = [p.to, p.from, p.toSug, p.fromSug];
      return renderPlanner(root);
    }
    if (e.target.closest('[data-now]')) { p.time = ''; return renderPlanner(root); }
    if (e.target.closest('[data-save]')) return toggleSaved();
    if (e.target.closest('[data-more]')) return more();
    const route = e.target.closest('[data-route]');
    if (route) {
      const [key, i] = route.dataset.route.split(':');
      const r = routes(key)[Number(i)];
      Object.assign(p, { city: r.city || p.city, from: r.from, to: r.to, fromSug: r.fromSug || null, toSug: r.toSug || null });
      return search();
    }
    const share = e.target.closest('[data-share]');
    if (share) {
      const text = shareText(p.results[Number(share.dataset.share)]);
      if (navigator.share) navigator.share({ text }).catch(() => {});
      else navigator.clipboard?.writeText(text).then(() => toast(t('copied')));
      return;
    }
    const trip = e.target.closest('[data-trip]');
    if (trip) {
      const [ci, si] = trip.dataset.trip.split(':').map(Number);
      return openScheduledTrip(p.results[ci].segments[si]);
    }
    const stop = e.target.closest('[data-stop]');
    if (stop) {
      const [ci, si, end] = stop.dataset.stop.split(':');
      const s = p.results[Number(ci)].segments[Number(si)];
      onOpenStop(end === 'dep' ? s.departureStop : s.arrivalStop, { line: lineNumber(s.line), time: end === 'dep' ? s.departureTime : '' });
    }
  };
}

function toggleSaved() {
  const r = { city: p.city, from: p.from.trim(), to: p.to.trim(), fromSug: p.fromSug, toSug: p.toSug };
  const saved = routes('savedRoutes');
  store.set('savedRoutes', saved.some((x) => sameRoute(x, r)) ? saved.filter((x) => !sameRoute(x, r)) : [r, ...saved].slice(0, 20));
  renderPlanner(root);
}

async function search() {
  if (!p.from.trim() || !p.to.trim()) return;
  Object.assign(p, { loading: true, error: '', results: [], cursor: null, searched: true });
  renderPlanner(root);
  try {
    const r = await apiJson('/api/cp/search', {
      city: p.city, from: p.from.trim(), to: p.to.trim(), fromSug: p.fromSug, toSug: p.toSug, time: p.time, directOnly: p.direct,
    });
    Object.assign(p, { results: r.connections, cursor: r.cursor, fromSug: r.from, toSug: r.to });
    const recent = { city: p.city, from: r.from.text, to: r.to.text, fromSug: r.from, toSug: r.to };
    store.set('recentRoutes', [recent, ...routes('recentRoutes').filter((x) => !sameRoute(x, recent))].slice(0, 8));
  } catch {
    p.error = t('planner_failed');
  }
  p.loading = false;
  renderPlanner(root);
}

async function more() {
  p.loadingMore = true;
  renderBody();
  try {
    const r = await apiJson('/api/cp/more', { cursor: p.cursor });
    const ids = new Set(p.results.map((c) => c.id));
    const key = (c) => `${c.segments[0].serviceDate} ${c.segments[0].departureTime.padStart(5, '0')}`;
    p.results = [...p.results, ...r.connections.filter((c) => !ids.has(c.id))].sort((a, b) => key(a).localeCompare(key(b)));
    p.cursor = r.cursor;
  } catch {
    toast(t('planner_failed'));
  }
  p.loadingMore = false;
  renderBody();
}
