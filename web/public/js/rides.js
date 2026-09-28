// Regular rides on the web version. A browser can't run anything while the app is closed,
// so the rides are also kept on our server with this phone's push subscription: before each
// departure the server finds that day's bus, starts the alerts for it and tells the phone.
import { t, lang } from './i18n.js';
import { apiJson, store, config, toast, log } from './core.js';
import { communityMode } from './live.js';
import { todayLocal, addDays } from './logic.js';

export const rides = () => store.get('rides', []);

const b64ToBytes = (s) => Uint8Array.from(atob((s + '='.repeat((4 - (s.length % 4)) % 4)).replace(/-/g, '+').replace(/_/g, '/')), (c) => c.charCodeAt(0));

async function pushSubscription() {
  if (!('serviceWorker' in navigator) || !('PushManager' in window) || !config.vapidPublicKey) return null;
  if (await Notification.requestPermission() !== 'granted') return null;
  const reg = await navigator.serviceWorker.ready;
  return await reg.pushManager.getSubscription() ||
    reg.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: b64ToBytes(config.vapidPublicKey) });
}

/** Saves the list here and on the server. False when notifications aren't possible (then nothing is saved). */
export async function saveRides(list) {
  try {
    const subscription = await pushSubscription();
    if (!subscription && list.length) return false;
    if (subscription) {
      await apiJson('/api/rides', {
        subscription: subscription.toJSON(), rides: list, lang: lang(),
        arriveLeadMinutes: store.get('arriveLead', 2), community: communityMode() !== 'off',
      });
    }
    store.set('rides', list);
    return true;
  } catch (err) {
    log('W', 'rides', err.message);
    toast(t('rides_sync_failed'));
    return false;
  }
}

/** Language or alert settings changed: the server's copy follows. */
export function resyncRides() {
  if (rides().length) saveRides(rides());
}

/** "Today", "Tomorrow" or "Mon 5.10." */
export function rideDayText(ms) {
  const day = todayLocal(ms), today = todayLocal();
  const same = (a, b) => a.year === b.year && a.month === b.month && a.day === b.day;
  if (same(day, today)) return t('day_today');
  if (same(day, addDays(today, 1))) return t('day_tomorrow');
  return new Date(ms).toLocaleDateString(lang() === 'en' ? 'en-GB' : 'sk-SK', { weekday: 'short', day: 'numeric', month: 'numeric', timeZone: 'Europe/Bratislava' });
}
