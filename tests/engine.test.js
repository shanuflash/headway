import { test } from 'node:test';
import assert from 'node:assert/strict';
import { Schedule, dayTypeFor } from '../js/engine.js';
import { STATIONS, directDestinations, transferDestinations } from '../js/network.js';

const at = (y, mo, d, h, mi) => new Date(y, mo - 1, d, h, mi).getTime();
const hhmm = (ms) => new Date(ms).toTimeString().slice(0, 5);
const legsOf = (j) => j.legs.map((l) => `${l.trip.route}:${l.from}>${l.to}`);

test('day types follow the calendar, holidays and overrides', () => {
  assert.equal(dayTypeFor('2026-10-12').type, 'weekday');
  assert.equal(dayTypeFor('2026-10-17').type, 'saturday');
  assert.equal(dayTypeFor('2026-10-18').type, 'sunday');
  assert.equal(dayTypeFor('2026-10-20').type, 'sunday');
  assert.equal(dayTypeFor('2026-10-12', { override: { date: '2026-10-12', type: 'sunday' } }).type, 'sunday');
  const feed = { adjustments: [{ date: '2026-10-13', dayType: 'saturday', reason: 'Test' }] };
  assert.deepEqual(dayTypeFor('2026-10-13', { feed }), { type: 'saturday', reason: 'Test' });
});

test('terminal first and last trains match the official timetable', () => {
  const s = new Schedule();
  const trips = s.day('2026-10-12').trips;
  const from = (route, dir) => trips.filter((t) => t.route === route && t.dir === dir).map((t) => hhmm(t.dep));
  const check = (route, dir, first, last) => {
    const deps = from(route, dir);
    assert.equal(deps[0], first, `${route} ${dir} first`);
    assert.equal(deps.at(-1), last, `${route} ${dir} last`);
  };
  check('blue', 'fwd', '04:56', '23:00');
  check('blue', 'rev', '04:51', '23:00');
  check('green', 'fwd', '05:02', '23:00');
  check('inter', 'fwd', '04:55', '23:17');
  check('inter', 'rev', '05:02', '23:08');
  check('yellow', 'fwd', '07:00', '21:00');
});

test('headways follow the peak bands', () => {
  const s = new Schedule();
  const deps = s
    .day('2026-10-12')
    .trips.filter((t) => t.route === 'blue' && t.dir === 'fwd')
    .map((t) => t.dep);
  const gapAt = (h, m) => {
    const i = deps.findIndex((d) => d >= at(2026, 10, 12, h, m));
    return (deps[i + 1] - deps[i]) / 60_000;
  };
  assert.equal(gapAt(9, 0), 6);
  assert.equal(gapAt(13, 0), 7);
  assert.equal(gapAt(22, 15), 15);
});

test('short loops run on weekday peaks only', () => {
  const s = new Schedule();
  assert.ok(s.day('2026-10-12').trips.some((t) => t.route === 'loop'));
  assert.ok(!s.day('2026-10-17').trips.some((t) => t.route === 'loop'));
});

test('journeys pick sensible interchanges', () => {
  const s = new Schedule();
  const j = s.plan('guindy', 'porur-junction', at(2026, 10, 12, 20, 2));
  assert.deepEqual(legsOf(j), ['blue:guindy>alandur', 'inter:alandur>vadapalani', 'yellow:vadapalani>porur-junction']);
  const direct = s.journeys('airport', 'vadapalani', at(2026, 10, 12, 8, 15), 3);
  assert.ok(direct.some((x) => x.legs.length === 1 && x.legs[0].trip.route === 'inter'));
  for (const x of direct) assert.ok(!(x.legs.length > 1 && x.legs[1].trip.route === 'inter'), 'never transfers onto a train it could board at origin');
});

test('late at night the next journey is the first train, not an overnight wait', () => {
  const s = new Schedule();
  const j = s.plan('guindy', 'central', at(2026, 10, 12, 23, 30));
  assert.equal(j.legs.length, 1);
  assert.equal(hhmm(j.dep), '05:01');
});

test('feed adjustments extend service and delay trains', () => {
  const feed = {
    adjustments: [
      { date: '2026-10-12', extendUntil: '00:30', headway: 15, lines: ['blue'] },
      { from: '2026-10-12T09:00:00', to: '2026-10-12T10:00:00', delay: 5, lines: ['green'] },
    ],
  };
  const s = new Schedule({ feed });
  const trips = s.day('2026-10-12').trips;
  const blueLast = trips.filter((t) => t.route === 'blue' && t.dir === 'fwd').at(-1);
  assert.equal(hhmm(blueLast.dep), '00:30');
  assert.ok(trips.some((t) => t.line === 'green' && t.delay === 5));
});

test('after a line closes, trips that need it start from its first train tomorrow', () => {
  const s = new Schedule();
  const js = s.journeys('guindy', 'porur-junction', at(2026, 10, 12, 21, 45), 2);
  assert.ok(js.length > 0, 'finds a journey');
  const yellow = js[0].legs.find((l) => l.trip.route === 'yellow');
  assert.equal(hhmm(yellow.dep), '07:00');
  // Leaves as late as possible for that connection instead of waiting an hour at Vadapalani.
  const wait = (yellow.dep - js[0].legs[js[0].legs.indexOf(yellow) - 1].arr) / 60_000;
  assert.ok(wait < 20, `waits ${wait} min at Vadapalani`);
});

test('route departures list only direct trains that stop at the destination', () => {
  const s = new Schedule();
  const deps = s.routeDepartures('guindy', 'central', at(2026, 10, 12, 8, 38), 6);
  assert.equal(deps.length, 6);
  for (const d of deps) {
    assert.ok(d.arr > d.dep);
    assert.ok(['blue', 'loop'].includes(d.trip.route));
  }
  // Short loops end at Washermenpet, so they never serve stations beyond it.
  const far = s.routeDepartures('guindy', 'tollgate', at(2026, 10, 12, 8, 38), 6);
  assert.ok(far.every((d) => d.trip.route === 'blue'));
  assert.equal(hhmm(s.lastRouteDeparture('guindy', 'central', at(2026, 10, 12, 8, 38)).dep), '23:10');
});

test('direct destinations group stations by direction of travel', async () => {
  const { directDestinations } = await import('../js/network.js');
  const groups = directDestinations('alandur');
  const towardCentralViaCmbt = groups.find((g) => g.next === 'ekkattuthangal');
  assert.ok(towardCentralViaCmbt.stations.some((x) => x.id === 'koyambedu'));
  const south = groups.find((g) => g.next === 'nanganallur-road');
  assert.deepEqual(south.terminals.sort(), ['airport']);
});

test('the last journey with a change is the latest start that still arrives tonight', () => {
  const s = new Schedule();
  const now = at(2026, 10, 12, 20, 0);
  const last = s.lastJourney('guindy', 'porur-junction', now);
  assert.ok(last, 'there is a last journey tonight');
  assert.ok(last.legs.length >= 2, 'it needs a change');
  assert.equal(last.legs[0].trip.serviceDate, '2026-10-12');
  // A minute after it leaves, the next plan is tomorrow's.
  const after = s.plan('guindy', 'porur-junction', last.dep + 60_000);
  assert.ok(!after || after.legs[0].trip.serviceDate !== '2026-10-12');
  // Direct pairs agree with the direct last train.
  assert.equal(s.lastJourney('guindy', 'central', now).dep, s.lastRouteDeparture('guindy', 'central', now).dep);
});

test('every station is reachable, directly or with a change', () => {
  for (const from of Object.keys(STATIONS)) {
    const direct = new Set(directDestinations(from).flatMap((g) => g.stations.map((x) => x.id)));
    const changes = transferDestinations(from);
    for (const id of Object.keys(STATIONS)) {
      if (id === from) continue;
      assert.ok(direct.has(id) || changes.has(id), `${from} -> ${id}`);
      if (changes.has(id)) assert.ok(!direct.has(id) && changes.get(id).via.length === changes.get(id).changes);
    }
  }
  assert.deepEqual(transferDestinations('guindy').get('porur-junction'), { changes: 2, via: ['alandur', 'vadapalani'] });
});
