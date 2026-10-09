// Run: node test.mjs
import assert from 'node:assert/strict';
import { tripProgress, catchEstimate, communityKey, lineNumber, historyInsights, historyToCsv, snapToRoute, delayAt, nextCheckMs, stopPassDetector, nextRideDeparture, pickRideConnection, earliestBoardingMs, WORKDAYS, BUS_GPS_MAX_M, RIDER_GPS_MAX_M } from './public/js/logic.js';
import { hubTrip, tripUrl, parseTripUrl, localToEpoch } from './transit.js';
import { plannerLeg } from './public/js/logic.js';

const T0 = Date.UTC(2026, 8, 24, 10, 0);
const stops = [0, 5, 10, 15].map((m, i) => ({ name: `S${i}`, platformId: i + 1, order: i + 1, scheduledMs: T0 + m * 60000, actualMs: null }));
const detail = { line: '507', stops, delaySeconds: 0 };

// Waiting at stop 2, bus between S0 and S1.
let p = tripProgress(detail, { boardingPlatformIds: [3], alightOrder: 4 }, T0 + 2.5 * 60000);
assert.equal(p.onBoard, false);
assert.equal(p.nextIndex, 1);
assert.ok(Math.abs(p.position - 0.5) < 1e-9);
assert.equal(p.secondsToBoarding, 450);

// A +5 min delay shifts everything.
p = tripProgress({ ...detail, delaySeconds: 300 }, { boardingPlatformIds: [3], alightOrder: 4 }, T0 + 12 * 60000);
assert.equal(p.onBoard, false, 'with delay the bus has not reached S2 yet');
assert.equal(p.nextIndex, 2);

// Past the alight stop -> finished.
assert.equal(tripProgress(detail, { boardingPlatformIds: [1], alightOrder: 2 }, T0 + 7 * 60000).finished, true);

assert.deepEqual(catchEstimate(600, 900), { walkSeconds: 600, marginSeconds: 300, verdict: 'leaveSoon' });
assert.equal(catchEstimate(600, 400).verdict, 'miss');
assert.equal(catchEstimate(30, 10).verdict, 'atStop');
// location checks before the stop: 3/4 of the walk, every 1 to 3 min
assert.equal(nextCheckMs(120), 90000);
assert.equal(nextCheckMs(10), 60000);
assert.equal(nextCheckMs(460), 180000);
// the moment the bus left or passed a stop
{
  const pass = stopPassDetector();
  assert.equal(pass(0.8, true, 0), null);
  assert.deepEqual(pass(1.2, true, 10000), [1, 5000, false]); // drove past
  const dwell = stopPassDetector();
  dwell(0.9, true, 0);
  assert.equal(dwell(1.01, false, 10000), null); // stopped just past the stop
  assert.equal(dwell(1.01, false, 40000), null);
  assert.deepEqual(dwell(1.2, true, 50000), [1, 45000, true]); // left when it moved again
  const before = stopPassDetector();
  before(0.9, true, 0);
  before(0.99, false, 10000, true); // standing at the stop, just before its point
  assert.equal(before(1.2, true, 50000)[2], true);
  // EM-0007: crosses the stop point moving, halts 20 m further on, drives on -> stopped, not passed
  const past = stopPassDetector();
  const fixes = [[0.8, true, false], [0.97, true, true], [1.03, true, true], [1.04, false, true], [1.04, false, true], [1.04, false, true], [1.08, true, true], [1.3, true, false]];
  const out = fixes.map(([i, m, a], n) => past(i, m, n * 5000, a)).filter(Boolean);
  assert.deepEqual(out, [[1, 27500, true]]);
  // drives through the stop's area without stopping -> passed, decided once out of the area
  const through = stopPassDetector();
  const got = [[0.8, true, false], [0.95, true, true], [1.05, true, true], [1.3, true, false]].map(([i, m, a], n) => through(i, m, n * 5000, a)).filter(Boolean);
  assert.equal(got.length, 1);
  assert.equal(got[0][2], false);
}

// ---- regular rides (same rules as the Android app)
{
  const ride = { id: 'r', route: {}, time: '07:25', lines: ['101'], days: WORKDAYS, dates: [], leadMinutes: 10, enabled: true };
  const at = (y, mo, d, h, mi) => localToEpoch(y, mo, d, h, mi);
  // Friday 2026-10-02: after 7:15 the next one is Monday
  assert.equal(nextRideDeparture(ride, at(2026, 10, 2, 8, 0)), at(2026, 10, 5, 7, 25));
  assert.equal(nextRideDeparture(ride, at(2026, 10, 2, 7, 16)), at(2026, 10, 5, 7, 25));
  assert.equal(nextRideDeparture(ride, at(2026, 10, 2, 7, 14)), at(2026, 10, 2, 7, 25));
  const ranged = { ...ride, time: '18:00', days: [6], dates: ['2026-10-07'], startDate: '2026-10-10', endDate: '2026-10-17' };
  assert.equal(nextRideDeparture(ranged, at(2026, 10, 1, 12, 0)), at(2026, 10, 7, 18, 0));
  assert.equal(nextRideDeparture(ranged, at(2026, 10, 8, 12, 0)), at(2026, 10, 10, 18, 0));
  assert.equal(nextRideDeparture(ranged, at(2026, 10, 18, 0, 0)), null);
  assert.equal(nextRideDeparture({ ...ride, enabled: false }, at(2026, 10, 1, 0, 0)), null);
  const conn = (id, time, ...lines) => ({ id, segments: lines.map((l) => ({ line: 'Bus ' + l, departureTime: time, serviceDate: '2026-10-05' })) });
  const dep = at(2026, 10, 5, 7, 25);
  assert.equal(pickRideConnection([conn('a', '7:20', '106'), conn('b', '7:25', '101'), conn('c', '7:40', '101')], ride, dep).id, 'b');
  assert.equal(pickRideConnection([conn('a', '7:24', '106'), conn('c', '7:30', '101')], ride, dep).id, 'c');
  assert.equal(pickRideConnection([conn('x', '9:00', '5')], ride, dep), null);
  // earliest boarding: a bus that usually leaves 3 min early (nothing live known) counts as 3 min earlier
  const d = { stops: [{ scheduledMs: 1_000_000 }], delaySeconds: 0, history: { delaySeconds: -180 } };
  assert.equal(earliestBoardingMs(d, { boardingIndex: 0, boardingExpectedMs: 1_000_000 }, false), 1_000_000 - 180_000);
  assert.equal(earliestBoardingMs(d, { boardingIndex: 0, boardingExpectedMs: 1_000_000 }, true), 1_000_000);
}
assert.equal(communityKey(detail), `507|S0|${T0}`);
assert.equal(communityKey(detail, { lineId: 910009139, tripNumber: 123, line: '1' }), 'sadzv|910009139|123|2026-09-24|1');

// GPS on the route (same cases as the Android RoutePositionTest)
const route = [[48.570, 19.125], [48.580, 19.125], [48.590, 19.125], [48.600, 19.125]];
assert.ok(Math.abs(snapToRoute(48.5825, 19.1252, route, BUS_GPS_MAX_M).index - 1.25) < 0.03);
assert.equal(snapToRoute(48.73, 19.15, route, BUS_GPS_MAX_M), null, 'far off the route: wrong trip');
assert.equal(snapToRoute(48.5825, 19.1272, route, RIDER_GPS_MAX_M), null, 'rider beside the road is not on the bus');
const loop = [[48.570, 19.125], [48.580, 19.125], [48.590, 19.125], [48.580, 19.125], [48.570, 19.125]];
assert.ok(Math.abs(snapToRoute(48.575, 19.125, loop, 300, 0.4).index - 0.5) < 0.05);
assert.ok(Math.abs(snapToRoute(48.575, 19.125, loop, 300, 3.6).index - 3.5) < 0.05);
assert.ok(Math.abs(snapToRoute(48.65, 19.1268, [[48.70, 19.12], [48.60, 19.12]], RIDER_GPS_MAX_M).index - 0.5) < 0.05); // 500 m off an 11 km hop: a bending road
const sched = [0, 3, 6, 9].map((m) => T0 + m * 60000);
assert.equal(delayAt(1.5, sched, T0 + 6 * 60000), 90);
assert.equal(delayAt(2, sched, T0 + 5 * 60000), 0); // waiting at a stop before its departure: on time
assert.equal(delayAt(1.5, sched, T0 + 2 * 60000), -60); // left stop 1 a minute early
assert.equal(lineNumber('Bus 507105'), '507105');

// Timetable trips from emhub: boarding and alighting come from the trip link; where you get off, the arrival counts.
const link = parseTripUrl(tripUrl('sad-zvolen-as:T-5:20260924', 1, 2));
assert.deepEqual(link, { id: 'sad-zvolen-as:T-5:20260924', from: 1, to: 2 });
assert.equal(parseTripUrl('https://cp.sk/vlakbusmhd/draha/?x=1'), null, 'old cp.sk links are not ours');
const at = (h, m, d = 24) => localToEpoch(2026, 9, d, h, m);
const trip = hubTrip({ headsign: '', stops: [
  { name: 'Zvolen, AS', arrival: at(23, 40), departure: at(23, 40), lat: 48.57, lon: 19.12 },
  { name: 'Sliač, kúpele', arrival: at(23, 55), departure: at(23, 55), platform: '3', lat: 48.6, lon: 19.14 },
  { name: 'Banská Bystrica, AS', arrival: at(0, 15, 25), departure: at(0, 20, 25), lat: null, lon: null },
  { name: 'Brezno, AS', arrival: at(1, 5, 25), departure: at(1, 5, 25) },
] }, { line: '507', destination: '' }, link.from, link.to);
assert.equal(trip.stops.length, 4);
assert.equal(trip.boardingOrder, 2);
assert.equal(trip.alightOrder, 3);
assert.deepEqual(trip.stops.map((s) => s.platform), ['', '3', '', '']);
assert.equal(trip.stops[1].scheduledMs, at(23, 55));
assert.equal(trip.stops[2].scheduledMs, at(0, 15, 25), 'alight uses arrival');
assert.equal(trip.stops[2].lat, null);
assert.equal(trip.destination, 'Brezno, AS');
// A city bus from SAD Zvolen's live data is followed live, from the stop where the user gets on.
const liveLeg = plannerLeg({ line: 'Bus 9', arrivalStop: 'Lipovec, Potraviny', serviceDate: '2026-09-29',
  routeUrl: 'sadzv:trip?line=9&lineId=910009195&route=9004&trip=67&board=1296&alight=12&stop=4913&planned=78180' });
assert.deepEqual(liveLeg, { ref: { line: '9', lineId: 910009195, routeNumber: '9004', tripNumber: 67, destination: 'Lipovec, Potraviny',
  scheduleUrl: '', serviceDate: '2026-09-29', fromStopId: 4913, plannedSecondOfDay: 78180 }, boardingPlatformIds: [1296], alightOrder: 12 });
// On another day a city bus opens from the timetable (sadzv only knows today's run).
const tomorrowLeg = plannerLeg({ line: 'Bus 9', arrivalStop: 'Lipovec, Potraviny', serviceDate: '2099-01-01',
  routeUrl: 'sadzv:trip?line=9&lineId=1&route=9004&trip=67&board=1296&alight=12&stop=4913&planned=78180&tt=' + encodeURIComponent(tripUrl('sad-zvolen-as-city:x:20990101', 6, 10)) });
assert.deepEqual([tomorrowLeg.ref.lineId, tomorrowLeg.ref.scheduleUrl], [0, tripUrl('sad-zvolen-as-city:x:20990101', 6, 10)]);
const ttLeg = plannerLeg({ line: 'Bus 60107', arrivalStop: 'Banská Bystrica, AS', serviceDate: '2026-09-30', routeUrl: tripUrl('x:y:20260930', 0, 5) });
assert.equal(ttLeg.ref.scheduleUrl, tripUrl('x:y:20260930', 0, 5));
assert.deepEqual([ttLeg.ref.line, ttLeg.boardingPlatformIds, ttLeg.alightOrder], ['60107', [], null]);
// Europe/Bratislava is UTC+2 in September.
assert.equal(localToEpoch(2026, 9, 24, 12, 0), Date.UTC(2026, 8, 24, 10, 0));

const items = [
  { sourceType: 'TICKET', isTopUp: false, amountCents: -90, timestampMs: Date.UTC(2026, 8, 3, 8), stopName: 'Zvolen, AS', title: 'Jednorazový' },
  { sourceType: 'TICKET', isTopUp: false, amountCents: -110, timestampMs: Date.UTC(2026, 8, 4, 8), stopName: 'Zvolen, AS', title: 'Jednorazový, "x"' },
  { sourceType: 'TICKET', isTopUp: true, amountCents: 1000, timestampMs: Date.UTC(2026, 8, 1, 8), stopName: '', title: 'Vklad' },
];
const ins = historyInsights(items, 5.0, new Date(2026, 8, 24));
assert.equal(ins.months.at(-1).spentCents, 200);
assert.equal(ins.averageFareCents, 100);
assert.equal(ins.tripsLeft, 5);
assert.deepEqual(ins.topStops[0], ['Zvolen, AS', 2]);
assert.ok(historyToCsv(items).includes('"Jednorazový, ""x"""'));
// Times are real UTC: 08:00 UTC is 10:00 in Bratislava in September (run with TZ=Europe/Bratislava).
if (process.env.TZ === 'Europe/Bratislava') assert.ok(historyToCsv(items).includes('2026-09-03 10:00'));

console.log('ok');

// ---- 2.13: which ticket a bus takes, and the IDS BBSK e-shop pages (trimmed copies, made-up card numbers)
{
  const { walletForLine } = await import('./public/js/logic.js');
  assert.equal(walletForLine('34'), 'sadzv');
  assert.equal(walletForLine('60101'), 'ids');
  assert.equal(walletForLine('Bus 060400'), 'ids');
  assert.equal(walletForLine('609503'), null);
  const ids = await import('./ids.js');
  const ticket = `<tr class="my-tickets__table-row" data-id-order-detail="2">
    <td><div class="my-tickets__table-highlight breakable"> IDS Sieťovka 365 dní </div><div class="my-tickets__msg-alert"> Platnosť l&iacute;stka vypr&scaron;ala </div></td>
    <td> od: 29. 8. 2025 13:28 do: 28. 8. 2026 23:59 </td><td> identifikátor č. 5 <br>(NFC Karta) <br>01C55703 </td><td> 399,00 € </td></tr>`;
  const [t] = ids.parseTickets(ticket);
  assert.equal(t.price, '399,00 €'); // the card number right before the price isn't part of it
  assert.equal(t.valid, false);
  assert.equal(t.identifierNumber, 5);
  assert.equal(new Date(t.validFromMs).toISOString(), '2025-08-29T11:28:00.000Z'); // Slovak summer time
  assert.equal(ids.idsHistory({ tickets: [t], topUps: [] })[0].amountCents, -39900);
  const [card] = ids.parseIdentifiers(`<tr class="my-tokens__table-row" data-token="B"><td><div>Identifik&aacute;tor č. 5</div></td>
    <td><input class="my-tokens__card-info token-alias-input" value="NFC Karta" disabled /></td><td><a href="x">Platné lístky: 1</a></td>
    <td><div class="my-tokens__msg-success"> Platn&yacute; </div></td></tr>`);
  assert.ok(card.hasProducts && card.valid && card.alias === 'NFC Karta');
  assert.equal(ids.parseCredit('<div class="my-credit__credit-amount">\n 0,15\n</div>'), 0.15);
  console.log('ok 2.13: tickets by bus, IDS BBSK pages');
}
