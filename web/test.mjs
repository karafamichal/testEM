// Run: node test.mjs
import assert from 'node:assert/strict';
import { tripProgress, catchEstimate, communityKey, lineNumber, historyInsights, historyToCsv } from './public/js/logic.js';
import { parseScheduledTrip, localToEpoch } from './cp.js';

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
assert.equal(communityKey(detail), `507|S0|${T0}`);
assert.equal(lineNumber('Bus 507105'), '507105');

// cp.sk route page: boarding at the first active stop, day rollover after midnight.
const html = `<ul class="line-itinerary">
  <li class="item inactive"><strong class="name">Zvolen,,AS</strong><span class="departure">23:40</span></li>
  <li class="item"><strong class="name">Sliač,,kúpele</strong><span class="departure">23:55</span><span class="fixed-codes"><span><span title="nástupište">3</span></span></span></li>
  <li class="item"><strong class="name">B.Bystrica,,AS</strong><span class="arrival">0:15</span><span class="departure">0:20</span></li>
  <li class="item inactive"><strong class="name">Brezno,,AS</strong><span class="arrival">1:05</span></li>
</ul>`;
const trip = parseScheduledTrip(html, { line: '507', destination: '', serviceDate: '2026-09-24' });
assert.equal(trip.stops.length, 4);
assert.equal(trip.boardingOrder, 2);
assert.equal(trip.alightOrder, 3);
assert.equal(trip.stops[1].name, 'Sliač, kúpele');
assert.deepEqual(trip.stops.map((s) => s.platform), ['', '3', '', '']);
assert.equal(trip.stops[1].scheduledMs, localToEpoch(2026, 9, 24, 23, 55));
assert.equal(trip.stops[2].scheduledMs, localToEpoch(2026, 9, 25, 0, 15), 'alight uses arrival, next day');
assert.equal(trip.stops[0].scheduledMs, localToEpoch(2026, 9, 24, 23, 40));
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
