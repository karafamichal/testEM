// IDS BBSK (eshop.idsbbsk.sk) for the web version (EM-0023), as the Android app's IdsSession.kt: signs in like the
// e-shop's login form and reads its account pages (the shop has no API; the pages are HTML). Runs on the server, so the
// shop's session cookies never reach the browser.

const BASE = 'https://eshop.idsbbsk.sk/';
const USER_AGENT = 'Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/144.0.0.0 Mobile Safari/537.36';

class HttpError extends Error {
  constructor(status, message) { super(message); this.status = status; }
}

/** The shop's cookies, name -> value. */
export class IdsJar {
  constructor() { this.cookies = new Map(); }
  store(list) {
    for (const header of list || []) {
      const pair = header.split(';')[0];
      const eq = pair.indexOf('=');
      if (eq > 0) this.cookies.set(pair.slice(0, eq).trim(), pair.slice(eq + 1).trim());
    }
  }
  header() { return [...this.cookies].map(([k, v]) => `${k}=${v}`).join('; '); }
}

async function get(jar, path, accept = 'text/html') {
  const res = await fetch(BASE + path, {
    headers: { 'User-Agent': USER_AGENT, 'Accept-Language': 'sk-SK,sk;q=0.9', Accept: accept, Cookie: jar.header() },
    redirect: 'follow',
    signal: AbortSignal.timeout(20000),
  });
  jar.store(res.headers.getSetCookie?.());
  return res;
}

/** Signs in; throws 401 for a wrong email or password. */
export async function idsLogin(email, password) {
  const jar = new IdsJar();
  await get(jar, ''); // the landing page sets the session cookie the login then uses
  const body = new URLSearchParams({ controller: 'auth', email, password, SubmitLogin: 'true', ajax: '1' });
  const res = await fetch(BASE, {
    method: 'POST',
    headers: {
      'User-Agent': USER_AGENT, 'X-Requested-With': 'XMLHttpRequest', Accept: 'application/json, text/javascript, */*; q=0.01',
      'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8', Cookie: jar.header(),
    },
    body,
    signal: AbortSignal.timeout(20000),
  });
  jar.store(res.headers.getSetCookie?.());
  const json = await res.json().catch(() => null);
  if (!json) throw new HttpError(502, 'IDS BBSK login answer not understood');
  if (json.loginSuccess !== true) {
    // EM-0050: the shop sometimes asks to confirm "not a robot" (a CAPTCHA we can't and don't answer); that isn't a
    // wrong password, so say what it is.
    const said = (json.errors || []).map((e) => text(String(e))).join(' ');
    if (/robot|captcha/i.test(said)) throw new HttpError(403, 'robot');
    throw new HttpError(401, 'eshop.idsbbsk.sk did not accept this email or password');
  }
  return jar;
}

/** An account page; without its content the session ended (the shop shows the login form instead). */
async function page(jar, path) {
  const res = await get(jar, path);
  const html = await res.text();
  if (!res.ok) throw new HttpError(502, `IDS BBSK HTTP ${res.status}`);
  if (res.url.includes('prihlasenie') || !html.includes('class="my-')) throw new HttpError(401, 'Session expired');
  return html;
}

/** Credit, identifiers, tickets and top-ups; the name only while [knownName] is empty (its page is heavy). */
export async function idsAccount(jar, knownName = '') {
  const credit = await page(jar, 'praca-s-kreditom');
  const [identifiers, tickets, name] = await Promise.all([
    page(jar, 'moje-identifikatory').then(parseIdentifiers),
    page(jar, 'moje-listky').then(parseTickets),
    knownName ? Promise.resolve(knownName) : page(jar, 'moj-ucet').then(parseName),
  ]);
  return { name, credit: parseCredit(credit), creditNote: parseCreditNote(credit), identifiers, tickets, topUps: parseTopUps(credit) };
}

/** The virtual card's static QR code as a data URL: the shop answers with the picture, or a page showing it. */
export async function idsQr(jar, qrId) {
  const res = await get(jar, `moje-identifikatory?id_token=${encodeURIComponent(qrId)}&action=qr`, 'image/*,text/html');
  if (res.url.includes('prihlasenie')) throw new HttpError(401, 'Session expired');
  const type = res.headers.get('content-type') || '';
  if (type.startsWith('image/')) return `data:${type.split(';')[0]};base64,${Buffer.from(await res.arrayBuffer()).toString('base64')}`;
  const html = await res.text();
  const inline = /<img[^>]+src="(data:image\/(?:png|jpeg|gif|svg\+xml);base64,[A-Za-z0-9+/=\s]+)"/.exec(html);
  if (inline) return inline[1].replace(/\s/g, '');
  const linked = [...html.matchAll(/<img[^>]+src="([^"]+)"/g)].map((m) => m[1].replace(/&amp;/g, '&'))
    .find((src) => !src.startsWith('data:') && /qr/i.test(src) && !src.endsWith('.svg'));
  if (!linked) return null;
  const img = await get(jar, new URL(linked, res.url).href.replace(BASE, ''), 'image/*');
  const imgType = img.headers.get('content-type') || '';
  return imgType.startsWith('image/') ? `data:${imgType.split(';')[0]};base64,${Buffer.from(await img.arrayBuffer()).toString('base64')}` : null;
}

// ---------------------------------------------------------------------------- parsing (the shop's HTML, Oct 2026)

const MARKS = { acute: '́', caron: '̌', circ: '̂', uml: '̈', grave: '̀', ring: '̊', tilde: '̃' };

/** Visible text of an HTML fragment, on one line. */
export function text(html) {
  return html.replace(/<[^>]+>/g, ' ')
    .replace(/&([A-Za-z])(acute|caron|circ|uml|grave|ring|tilde);/g, (_, c, m) => c + MARKS[m])
    .replace(/&#(\d+);/g, (_, n) => String.fromCharCode(Number(n)))
    .replace(/&#x([0-9a-fA-F]+);/g, (_, n) => String.fromCharCode(parseInt(n, 16)))
    .replace(/&nbsp;/g, ' ').replace(/&quot;/g, '"').replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&amp;/g, '&')
    .normalize('NFC').replace(/ /g, ' ').replace(/\s+/g, ' ').trim();
}

const rows = (html, cls) => html.split(`<tr class="${cls}"`).slice(1).map((r) => r.split('</tr>')[0]);

/** Epoch ms of a wall-clock time in Slovakia. */
export function skMs(y, mo, d, h = 0, mi = 0) {
  const guess = Date.UTC(y, mo - 1, d, h, mi);
  const p = Object.fromEntries(new Intl.DateTimeFormat('en-GB', {
    timeZone: 'Europe/Bratislava', hourCycle: 'h23', year: 'numeric', month: 'numeric', day: 'numeric', hour: 'numeric', minute: 'numeric',
  }).formatToParts(new Date(guess)).map((x) => [x.type, Number(x.value)]));
  return guess - (Date.UTC(p.year, p.month - 1, p.day, p.hour, p.minute) - guess);
}

export function parseName(html) {
  return ['firstname', 'lastname'].map((f) => new RegExp(`name="${f}"[^>]*?value="([^"]*)"`).exec(html)?.[1])
    .filter(Boolean).map(text).filter(Boolean).join(' ');
}

export function parseCredit(html) {
  const m = /my-credit__credit-amount">\s*([\d\s.,]+?)\s*</.exec(html);
  return m ? Number(m[1].replace(/\s/g, '').replace(',', '.')) : null;
}

export function parseCreditNote(html) {
  const m = /my-credit__header_note">([\s\S]*?)<\/div>/.exec(html);
  return m ? text(m[1]) : '';
}

export function parseIdentifiers(html) {
  return rows(html, 'my-tokens__table-row').map((row) => {
    const t = text(row);
    const number = /Identifikátor č\.\s*(\d+)/.exec(t)?.[1];
    if (!number) return null;
    // "Platné lístky: 1" is a link to the tickets, the rest status boxes.
    const products = [...t.matchAll(/Kredit aktívny|Žiadne produkty|Platné lístky:\s*\d+/g)].map((m) => m[0]);
    return {
      number: Number(number),
      alias: text(/token-alias-input"\s*value="([^"]*)"/.exec(row)?.[1] || ''),
      cardNumber: /Číslo karty:\s*(\S+)/.exec(t)?.[1] || '',
      products,
      hasProducts: /Platné lístky:\s*[1-9]/.test(t),
      // Not \b: in JavaScript it doesn't count "ý" as a letter.
      valid: /(?<!\p{L})Platný(?!\p{L})/u.test(t),
      qrId: /id_token=(\d+)&(?:amp;)?action=qr/.exec(row)?.[1] || null,
    };
  }).filter(Boolean);
}

export function parseTickets(html) {
  return rows(html, 'my-tickets__table-row').map((row) => {
    const name = /my-tickets__table-highlight breakable">([\s\S]*?)<\/div>/.exec(row);
    if (!name) return null;
    const t = text(row);
    const dates = [...t.matchAll(/(\d{1,2})\.\s*(\d{1,2})\.\s*(\d{4})\s+(\d{1,2}):(\d{2})/g)].map((m) => skMs(+m[3], +m[2], +m[1], +m[4], +m[5]));
    // Digits only: the card number can stand right before the price ("01C55703 399,00 €").
    const prices = [...t.matchAll(/(?<![\d\w])(\d+,\d{2})\s*€/g)];
    return {
      name: text(name[1]),
      valid: t.includes('Platný') && !t.includes('vypršala'),
      validFromMs: dates[0] || 0,
      validToMs: dates[1] || 0,
      identifier: /identifikátor č\.\s*\d+.*?(?=\s+(?:Preniesť|\d+,\d{2}\s*€))/.exec(t)?.[0].trim() || '',
      identifierNumber: Number(/identifikátor č\.\s*(\d+)/.exec(t)?.[1] || 0),
      price: prices.length ? `${prices.at(-1)[1]} €` : '',
    };
  }).filter(Boolean);
}

export function parseTopUps(html) {
  return rows(html, 'my-credit__table-row').map((row) => {
    const cells = [...row.matchAll(/<td class="my-credit__table-column">([\s\S]*?)(?=<td|<\/tr|$)/g)].map((m) => text(m[1]));
    if (cells.length < 4) return null;
    const d = /(\d{1,2})\.\s*(\d{1,2})\.\s*(\d{4})/.exec(cells[0]);
    if (!d) return null;
    const amount = /(\d+),(\d{2})/.exec(cells[3]);
    return {
      id: /data-id-order-detail="(\d+)"/.exec(row)?.[1] || '',
      timestampMs: skMs(+d[3], +d[2], +d[1]),
      title: cells[1],
      status: cells[2],
      amountCents: amount ? Number(amount[1]) * 100 + Number(amount[2]) : null,
    };
  }).filter(Boolean);
}

/** History rows as the web app shows them: top-ups (+) and passes bought (spending, not rides). */
export function idsHistory(account) {
  const bought = (account.tickets || []).filter((t) => t.validFromMs).map((t) => {
    const m = /(\d+),(\d{2})/.exec(t.price.replace(/ /g, ''));
    const cents = m ? -(Number(m[1]) * 100 + Number(m[2])) : null;
    return { id: `ids-t-${t.validFromMs}-${t.name.length}`, sourceType: 'TRANSACTION', timestampMs: t.validFromMs, title: t.name,
      subtitle: t.identifier, amountText: cents == null ? '' : `-${t.price}`, amountCents: cents, isTopUp: false, stopName: '', wallet: 'ids' };
  });
  const credit = (account.topUps || []).map((u) => ({
    id: `ids-c-${u.id}`, sourceType: 'TRANSACTION', timestampMs: u.timestampMs, title: u.title, subtitle: u.status,
    amountText: u.amountCents == null ? '' : `+${(u.amountCents / 100).toFixed(2).replace('.', ',')} €`, amountCents: u.amountCents,
    isTopUp: true, stopName: '', wallet: 'ids',
  }));
  return [...bought, ...credit].sort((a, b) => b.timestampMs - a.timestampMs);
}
