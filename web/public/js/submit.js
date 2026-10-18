// 2.17.0: a rider tells us what the timetable lacks (a connection, a stop) or what's wrong at a stop. An admin checks
// every one in emHUB before the timetable changes (same as the Android app's form).
import { t } from './i18n.js';
import { esc, apiJson, store, toast } from './core.js';

const HUB_STOP = 'hub';

/** kind: 'trip' | 'stop' | 'problem'; from/to: the planner's picked stops; stopName: the board's stop (for a problem). */
export function openSubmit(kind, { from = null, to = null, stopName = '' } = {}) {
  document.querySelector('#submit')?.remove();
  const tripReady = from?.value2 === HUB_STOP && to?.value2 === HUB_STOP;
  const kinds = ['trip', 'stop', ...(stopName ? ['problem'] : [])];
  const d = document.createElement('div');
  d.id = 'submit';
  d.className = 'dialog-backdrop';
  const days = [0, 1, 2, 3, 4, 5, 6].map((i) => new Date(Date.UTC(2024, 0, i + 1)).toLocaleDateString(document.documentElement.lang === 'en' ? 'en-GB' : 'sk-SK', { weekday: 'short', timeZone: 'UTC' }));
  const today = (new Date().getDay() + 6) % 7;
  const draw = (k) => {
    d.innerHTML = `<form class="dialog stack" role="dialog" aria-modal="true" aria-labelledby="submit-title">
      <h2 id="submit-title">${t('submit_title')}</h2>
      <div class="segmented small">${kinds.map((x) => `<button type="button" data-kind="${x}" aria-pressed="${x === k}">${t('submit_kind_' + x)}</button>`).join('')}</div>
      ${k === 'trip' ? (tripReady ? `<p>${esc(t('submit_trip_between', from.text, to.text))}</p>
        <label class="field">${t('submit_line')}<input name="line" maxlength="12" required></label>
        <div class="row"><label class="field">${t('submit_leaves')}<input name="leaves" type="time" required></label>
          <label class="field">${t('submit_arrives')}<input name="arrives" type="time" required></label></div>
        <fieldset class="chips"><legend class="hint small">${t('submit_days')}</legend>${days.map((n, i) => `<label class="chip"><input type="checkbox" name="day" value="${i}" ${i === today ? 'checked' : ''}> ${esc(n)}</label>`).join('')}</fieldset>`
        : `<p class="hint">${t('submit_trip_pick')}</p>`) : ''}
      ${k === 'stop' ? `<p class="hint small">${t('submit_stop_here')}</p><label class="field">${t('submit_stop_name')}<input name="stopName" maxlength="80" required></label>` : ''}
      ${k === 'problem' ? `<p><b>${esc(stopName)}</b></p><label class="field">${t('submit_problem')}<textarea name="text" rows="3" maxlength="2000" required></textarea></label>` : ''}
      <label class="field">${t('submit_name')}<input name="name" maxlength="60" required value="${esc(store.get('submitName', ''))}"></label>
      <p class="hint small">${t('submit_review_note')}</p>
      <div class="row"><button class="btn" type="submit" ${k === 'trip' && !tripReady ? 'disabled' : ''}>${t('submit_send')}</button>
        <button class="btn outline" type="button" data-close>${t('cancel')}</button></div></form>`;
    const form = d.querySelector('form');
    form.onsubmit = async (e) => {
      e.preventDefault();
      const f = new FormData(form);
      const name = String(f.get('name') || '').trim();
      store.set('submitName', name);
      const body = { kind: k, name };
      if (k === 'trip') Object.assign(body, { line: String(f.get('line')).trim(), weekdays: f.getAll('day').map(Number), stops: [[from.value, f.get('leaves')], [to.value, f.get('arrives')]] });
      if (k === 'problem') Object.assign(body, { station: stopName, text: String(f.get('text')).trim() });
      if (k === 'stop') {
        const pos = await new Promise((ok) => navigator.geolocation ? navigator.geolocation.getCurrentPosition(ok, () => ok(null), { maximumAge: 60000, timeout: 15000 }) : ok(null));
        if (!pos) return toast(t('submit_no_location'));
        Object.assign(body, { stopName: String(f.get('stopName')).trim(), lat: pos.coords.latitude, lon: pos.coords.longitude });
      }
      form.querySelector('[type=submit]').disabled = true;
      try {
        await apiJson('/api/hub/submissions', body);
        d.remove();
        toast(t('submit_thanks'));
      } catch (err) {
        form.querySelector('[type=submit]').disabled = false;
        toast(err.message || t('submit_failed'));
      }
    };
  };
  d.onclick = (e) => {
    if (e.target === d || e.target.closest('[data-close]')) return d.remove();
    const k = e.target.closest('[data-kind]');
    if (k) draw(k.dataset.kind);
  };
  draw(kind);
  document.body.appendChild(d);
}
