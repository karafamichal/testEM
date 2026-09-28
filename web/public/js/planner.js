// cp.sk planner for the web client (same flow as the Android Planner tab).
import { t, lang } from './i18n.js';
import { $, esc, apiJson, store, plate, toast, clock } from './core.js';
import { readableStop, lineNumber, nextRideDeparture, localToEpoch, WORKDAYS, EVERY_DAY } from './logic.js';
import { openScheduledTrip, scheduledRef, startJourney } from './live.js';
import { rides, saveRides, rideDayText } from './rides.js';

// "All of Slovakia" is cp.sk's combined timetable (trains, intercity and city buses), so
// connections that change from a city bus to an intercity one are found.
const ALL = 'vlakbusmhd';
const CITIES = [[ALL, () => t('city_slovakia')], ['banskabystrica', () => 'B. Bystrica'], ['zvolen', () => 'Zvolen']];
// Before 2.6 "Slovakia" was the intercity-only timetable; its stop ids differ, so saved stops are looked up again.
const upgrade = (r) => (r.city === 'slovensko' ? { ...r, city: ALL, fromSug: null, toSug: null } : r);
const SWAP = '<svg viewBox="0 0 24 24" aria-hidden="true"><path fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" d="M7 4v16M7 20l-3-3M7 20l3-3M17 20V4M17 4l-3 3M17 4l3 3"/></svg>';

const p = {
  city: upgrade({ city: store.get('plannerCity', ALL) }).city, from: '', to: '', fromSug: null, toSug: null, time: '', date: '', direct: false,
  searchedRoute: null, // the route of the results on screen (the fields may have been edited since)
  results: [], cursor: null, loading: false, loadingMore: false, error: '', searched: false,
};
let root = null;
let onOpenStop = () => {};

const routes = (key) => store.get(key, []).map(upgrade);
const sameRoute = (a, b) => a.from.toLowerCase() === b.from.toLowerCase() && a.to.toLowerCase() === b.to.toLowerCase();

export function renderPlanner(el, { openStop } = {}) {
  root = el;
  onOpenStop = openStop || onOpenStop;
  const saved = routes('savedRoutes');
  const isSaved = p.from && p.to && saved.some((r) => sameRoute(r, { from: p.from.trim(), to: p.to.trim() }));
  el.innerHTML = `
    <header class="screen-head"><div><h1>${t('planner_title')}</h1><p class="hint small">${t('planner_sub')}</p></div></header>
    <div class="segmented" role="radiogroup">${CITIES.map(([slug, label]) => `<button role="radio" aria-checked="${p.city === slug}" data-city="${slug}">${esc(label())}</button>`).join('')}</div>
    <form class="planner-form" autocomplete="off">
      <div class="od">
        <label class="od-field"><span>${t('from')}</span><input id="pl-from" value="${esc(p.from)}" required></label>
        <label class="od-field"><span>${t('to')}</span><input id="pl-to" value="${esc(p.to)}" required></label>
        <button type="button" class="icon-btn swap" data-swap aria-label="${esc(t('swap'))}">${SWAP}</button>
        <ul class="suggest" id="pl-suggest" role="listbox" hidden></ul>
      </div>
      <div class="planner-opts">
        <div class="segmented small"><button type="button" aria-pressed="${!p.time && !p.date}" data-now>${t('leave_now')}</button>
          <label class="time-pick ${p.time ? 'on' : ''}">${t('at_time')} <input type="time" id="pl-time" value="${esc(p.time)}"></label>
          <label class="time-pick ${p.date ? 'on' : ''}"><input type="date" id="pl-date" value="${esc(p.date)}" min="${todayIso()}" aria-label="${esc(t('date'))}"></label></div>
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
    (recent.length ? `<h2 class="section-label">${t('recent')}</h2><div class="chips">${chips(recent, 'recentRoutes')}</div>` : '') + ridesListHtml();
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
  const saved = savedJourneys().some((j) => sameJourney(j.connection, c));
  return `<li class="connection">
    <div class="conn-head"><span class="conn-times">${esc(c.departureTime)} – ${esc(c.arrivalTime)}</span><span class="hint">${esc(c.totalDuration)}</span>
      <button class="icon-btn star ${saved ? 'on' : ''}" data-save-journey="${ci}" aria-pressed="${saved}" aria-label="${esc(t(saved ? 'unsave_journey' : 'save_journey'))}">★</button>
      <button class="icon-btn" data-repeat="${ci}" aria-label="${esc(t('rides_repeat'))}">↻</button>
      <button class="icon-btn" data-share="${ci}" aria-label="${esc(t('share'))}">⤴</button></div>
    <ol class="segs">${segs}</ol>
    ${c.segments.filter((s) => s.routeUrl).length > 1 ? `<button class="btn wide" data-journey="${ci}">${t('journey_follow')}</button>` : ''}</li>`;
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
  $('#pl-date', el).onchange = (e) => { p.date = e.target.value === todayIso() ? '' : e.target.value; renderPlanner(root); };
  $('#pl-direct', el).onchange = (e) => (p.direct = e.target.checked);
  $('form', el).onsubmit = (e) => { e.preventDefault(); search(); };
  el.onclick = (e) => {
    const city = e.target.closest('[data-city]');
    if (city) { p.city = city.dataset.city; store.set('plannerCity', p.city); p.fromSug = p.toSug = null; return renderPlanner(root); }
    if (e.target.closest('[data-swap]')) {
      [p.from, p.to, p.fromSug, p.toSug] = [p.to, p.from, p.toSug, p.fromSug];
      return renderPlanner(root);
    }
    if (e.target.closest('[data-now]')) { p.time = ''; p.date = ''; return renderPlanner(root); }
    const sj = e.target.closest('[data-save-journey]');
    if (sj) { toggleJourney(p.results[Number(sj.dataset.saveJourney)]); return renderBody(); }
    const rep = e.target.closest('[data-repeat]');
    if (rep) return editRide(rideFrom(p.results[Number(rep.dataset.repeat)]), true);
    const rideRow = e.target.closest('[data-ride]');
    if (rideRow) return editRide(rides().find((r) => r.id === rideRow.dataset.ride), false);
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
    const journey = e.target.closest('[data-journey]');
    if (journey) {
      // Every bus of the connection, one after another: get-off alert, then the next bus.
      const legs = p.results[Number(journey.dataset.journey)].segments.filter((s) => s.routeUrl)
        .map((s) => ({ ref: scheduledRef(s), boardingPlatformIds: [], alightOrder: null }));
      startJourney(legs);
      return toast(t('journey_started'));
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
      city: p.city, from: p.from.trim(), to: p.to.trim(), fromSug: p.fromSug, toSug: p.toSug, time: p.time, date: cpDate(p.date), directOnly: p.direct,
    });
    Object.assign(p, { results: r.connections, cursor: r.cursor, fromSug: r.from, toSug: r.to });
    p.searchedRoute = { city: p.city, from: r.from.text, to: r.to.text, fromSug: r.from, toSug: r.to };
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

// ---------------------------------------------------------------- date

const todayIso = () => new Intl.DateTimeFormat('sv-SE', { timeZone: 'Europe/Bratislava' }).format(new Date());
/** cp.sk's form wants "3.10.2026"; "" means today. */
const cpDate = (iso) => (/^\d{4}-\d{2}-\d{2}$/.test(iso || '') ? iso.split('-').reverse().map(Number).join('.') : '');

/** Opens the planner on a route at a time and date and searches (a saved journey from the home tab). */
export function searchAt(route, time, date, el) {
  Object.assign(p, { city: route.city || p.city, from: route.from, to: route.to, fromSug: route.fromSug || null, toSug: route.toSug || null, time: time || '', date: date && date !== todayIso() ? date : '' });
  root = el || root;
  search();
}

// ---------------------------------------------------------------- saved journeys (shown on the home tab)

export const savedJourneys = () => store.get('savedJourneys', []);
const sameJourney = (a, b) => JSON.stringify(a.segments.map((s) => [s.line, s.departureTime, s.serviceDate])) === JSON.stringify(b.segments.map((s) => [s.line, s.departureTime, s.serviceDate]));
const epoch = (iso, time) => {
  const d = /^(\d{4})-(\d{2})-(\d{2})$/.exec(iso || ''), m = /(\d{1,2}):(\d{2})/.exec(time || '');
  return d && m ? localToEpoch(+d[1], +d[2], +d[3], +m[1], +m[2]) : 0;
};
/** When the journey ends (0 = unknown); the date rolls over when the clock goes backwards. */
export function journeyTimes(j) {
  const first = j.connection.segments[0], last = j.connection.segments.at(-1);
  const dep = epoch(first.serviceDate, first.departureTime);
  let arr = epoch(last.serviceDate, last.arrivalTime);
  if (arr && arr < epoch(last.serviceDate, last.departureTime)) arr += 86400000;
  return { dep, arr };
}
function toggleJourney(c) {
  const list = savedJourneys();
  if (list.some((j) => sameJourney(j.connection, c))) store.set('savedJourneys', list.filter((j) => !sameJourney(j.connection, c)));
  else {
    const route = p.searchedRoute || { city: p.city, from: p.from, to: p.to, fromSug: p.fromSug, toSug: p.toSug };
    const now = Date.now();
    const kept = list.filter((j) => { const { arr } = journeyTimes(j); return !arr || arr > now; }); // ended ones are of no use
    store.set('savedJourneys', [...kept, { id: String(now), route, connection: c }].sort((a, b) => journeyTimes(a).dep - journeyTimes(b).dep).slice(-20));
    toast(t('journey_saved'));
  }
}
export function removeJourney(id) { store.set('savedJourneys', savedJourneys().filter((j) => j.id !== id)); }

// ---------------------------------------------------------------- regular rides

/** A ride from a planner result, prefilled for the editor: same stops, time and lines, workdays. */
function rideFrom(c) {
  const m = /(\d{1,2}):(\d{2})/.exec(c.segments[0].departureTime);
  return {
    id: String(Date.now()), route: p.searchedRoute || { city: p.city, from: p.from, to: p.to, fromSug: p.fromSug, toSug: p.toSug },
    time: `${m[1].padStart(2, '0')}:${m[2]}`, lines: c.segments.map((s) => lineNumber(s.line)),
    days: WORKDAYS, dates: [], startDate: null, endDate: null, leadMinutes: 10, enabled: true,
  };
}

const dayName = (iso) => new Date(Date.UTC(2024, 0, iso)).toLocaleDateString(lang() === 'en' ? 'en-GB' : 'sk-SK', { weekday: 'short', timeZone: 'UTC' }); // 2024-01-01 was a Monday
const daysText = (r) => {
  const d = [...r.days].sort().join();
  if (d === EVERY_DAY.join()) return t('rides_every_day');
  if (d === WORKDAYS.join()) return t('rides_workdays');
  if (d === '6,7') return t('rides_weekends');
  if (!r.days.length) return t('rides_dates_only');
  return [...r.days].sort().map(dayName).join(' ');
};
const rideLabel = (r) => `${readableStop(r.route.from)} → ${readableStop(r.route.to)}`;

function ridesListHtml() {
  const list = rides();
  return `<h2 class="section-label">${t('rides_title')}</h2>` + (list.length
    ? `<ul class="list">${list.map((r) => {
      const next = nextRideDeparture(r);
      return `<li><button class="row link" data-ride="${esc(r.id)}"><span class="row-text"><span class="row-title">${esc(rideLabel(r))}</span>
        <span class="row-sub">${esc(r.time)} · ${esc(daysText(r))}<br>${next ? esc(t('rides_next', rideDayText(next), clock(next))) : esc(t(r.enabled ? 'rides_no_next' : 'rides_paused'))}</span></span><span aria-hidden="true">›</span></button></li>`;
    }).join('')}</ul>`
    : `<p class="hint pad">${t('rides_empty')}</p>`);
}

/** Time, days, a date range, extra dates and how early following starts. */
function editRide(ride, isNew) {
  if (!ride) return;
  let r = { ...ride, days: [...ride.days], dates: [...ride.dates] };
  const d = document.createElement('div');
  d.className = 'dialog-backdrop';
  const draw = () => {
    d.innerHTML = `<form class="dialog stack ride-editor" role="dialog" aria-modal="true" aria-labelledby="ride-title">
      <h2 id="ride-title">${esc(rideLabel(r))}</h2>
      <p class="hint small">${t('rides_intro')}</p>
      ${r.lines.length ? `<p class="small">${esc(t('rides_lines', r.lines.join(' → ')))}</p>` : ''}
      <label class="field">${t('rides_departs')}<input type="time" name="time" value="${esc(r.time)}" required></label>
      <fieldset class="chips"><legend class="section-label">${t('rides_days')}</legend>
        ${EVERY_DAY.map((n) => `<label class="chip"><input type="checkbox" name="day" value="${n}" ${r.days.includes(n) ? 'checked' : ''}>${esc(dayName(n))}</label>`).join('')}</fieldset>
      <div class="chips"><button type="button" class="chip" data-days="1,2,3,4,5">${t('rides_workdays')}</button><button type="button" class="chip" data-days="6,7">${t('rides_weekends')}</button><button type="button" class="chip" data-days="1,2,3,4,5,6,7">${t('rides_every_day')}</button></div>
      <div class="two"><label class="field">${t('rides_from')}<input type="date" name="startDate" value="${esc(r.startDate || '')}"></label>
        <label class="field">${t('rides_until')}<input type="date" name="endDate" value="${esc(r.endDate || '')}"></label></div>
      <fieldset class="chips"><legend class="section-label">${t('rides_dates')}</legend>
        ${r.dates.sort().map((x) => `<button type="button" class="chip" data-drop-date="${x}">${esc(x.split('-').reverse().join('.'))} ✕</button>`).join('')}
      </fieldset>
      <label class="field">${t('rides_add_date')}<input type="date" name="addDate"></label>
      <label class="field">${t('rides_lead')}<select name="lead">${[5, 10, 15, 20, 30, 45].map((m) => `<option value="${m}" ${r.leadMinutes === m ? 'selected' : ''}>${m} min</option>`).join('')}</select></label>
      <ul class="list"><li><label class="row switch-row"><span class="row-title">${t('rides_enabled')}</span><input type="checkbox" role="switch" name="enabled" ${r.enabled ? 'checked' : ''}></label></li></ul>
      <p class="hint small">${t('rides_web_hint')}</p>
      <p class="error-text" id="ride-error" hidden></p>
      <div class="dialog-actions">${isNew ? '' : `<button type="button" class="text-btn danger" data-delete>${t('rides_delete')}</button>`}
        <button type="button" class="text-btn" data-cancel>${t('cancel')}</button><button class="btn" type="submit">${t('save')}</button></div></form>`;
  };
  const read = () => {
    const f = $('form', d);
    r = { ...r, time: f.time.value || r.time, days: [...f.querySelectorAll('[name=day]:checked')].map((x) => Number(x.value)),
      startDate: f.startDate.value || null, endDate: f.endDate.value || null, leadMinutes: Number(f.lead.value), enabled: f.enabled.checked };
  };
  draw();
  d.onchange = (e) => { read(); if (e.target.name === 'addDate' && e.target.value && !r.dates.includes(e.target.value)) { r.dates.push(e.target.value); draw(); } };
  d.onclick = async (e) => {
    if (e.target === d || e.target.closest('[data-cancel]')) return d.remove();
    const days = e.target.closest('[data-days]');
    if (days) { read(); r.days = days.dataset.days.split(',').map(Number); return draw(); }
    const drop = e.target.closest('[data-drop-date]');
    if (drop) { read(); r.dates = r.dates.filter((x) => x !== drop.dataset.dropDate); return draw(); }
    if (e.target.closest('[data-delete]')) { await saveRides(rides().filter((x) => x.id !== r.id)); d.remove(); return renderBody(); }
  };
  d.onsubmit = async (e) => {
    e.preventDefault();
    read();
    const err = $('#ride-error', d);
    if (!r.days.length && !r.dates.length) { err.textContent = t('rides_pick_days'); err.hidden = false; return; }
    const ok = await saveRides([...rides().filter((x) => x.id !== r.id), r]);
    if (!ok) { err.textContent = t('rides_push_needed'); err.hidden = false; return; }
    d.remove();
    renderBody();
  };
  document.body.appendChild(d);
}
