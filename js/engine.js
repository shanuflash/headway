import { TIMETABLE, HOLIDAYS } from './timetable.js';
import { STATIONS, ROUTES, LINES } from './network.js';

const MIN = 60_000;
// Trains after midnight (extended service) still belong to the previous service day.
const SERVICE_DAY_ROLLOVER_HOUR = 3;

export const hm = (s) => {
  const [h, m] = s.split(':').map(Number);
  return h * 60 + m;
};

const pad = (n) => String(n).padStart(2, '0');
export const dateKey = (d) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
const keyToDate = (key) => {
  const [y, m, d] = key.split('-').map(Number);
  return new Date(y, m - 1, d);
};
const addDays = (key, n) => {
  const d = keyToDate(key);
  d.setDate(d.getDate() + n);
  return dateKey(d);
};

export function serviceDateKey(nowMs) {
  const d = new Date(nowMs);
  if (d.getHours() < SERVICE_DAY_ROLLOVER_HOUR) d.setDate(d.getDate() - 1);
  return dateKey(d);
}

/** Resolves which timetable a service day runs, and why. */
export function dayTypeFor(key, { feed, override } = {}) {
  if (override && override.date === key) return { type: override.type, reason: 'Set by you' };
  const fromFeed = feed?.adjustments?.find((a) => a.date === key && a.dayType);
  if (fromFeed) return { type: fromFeed.dayType, reason: fromFeed.reason || 'CMRL advisory' };
  const holiday = feed?.holidays?.[key] ?? HOLIDAYS[key];
  if (holiday) return { type: 'sunday', reason: holiday };
  const dow = keyToDate(key).getDay();
  if (dow === 0) return { type: 'sunday', reason: 'Sunday' };
  if (dow === 6) return { type: 'saturday', reason: 'Saturday' };
  return { type: 'weekday', reason: 'Weekday' };
}

export function bandAt(bands, t) {
  for (const b of bands) if (t >= hm(b[0]) && t < hm(b[1])) return b;
  return t < hm(bands[0][0]) ? bands[0] : bands[bands.length - 1];
}

/** Origin departure minutes for one route direction, per the first/last/headway rules. */
function originTimes(spec, dir) {
  const first = hm(spec.first[dir]);
  const last = hm(spec.last[dir]);
  const out = [];
  let t = first;
  while (t < last) {
    out.push(t);
    t += bandAt(spec.bands, t)[2];
  }
  // Land exactly on the published last train; drop a generated train that would crowd it.
  const prev = out[out.length - 1];
  if (prev !== undefined && last - prev < bandAt(spec.bands, prev)[2] / 2) out.pop();
  out.push(last);
  return out;
}

function stopsFor(route, dir, dep, from, to) {
  let seq = route[dir];
  if (from) seq = seq.slice(seq.findIndex(([s]) => s === from), seq.findIndex(([s]) => s === to) + 1);
  const base = seq[0][1];
  return seq.map(([s, cum]) => ({ s, t: dep + cum - base }));
}

function makeTrip(id, routeId, dir, stops, extra = {}) {
  const route = ROUTES[routeId];
  return {
    id,
    route: routeId,
    line: route.line,
    dir,
    stops,
    origin: stops[0].s,
    dest: stops[stops.length - 1].s,
    ...extra,
  };
}

/** Builds every trip for one service day, in minutes after that day's midnight. */
function buildDay(key, ctx) {
  const { type } = dayTypeFor(key, ctx);
  const day = TIMETABLE[type];
  const trips = [];

  for (const routeId of ['blue', 'green', 'inter', 'yellow']) {
    const spec = day[routeId];
    const route = ROUTES[routeId];
    if (!spec || !route) continue;
    for (const dir of ['fwd', 'rev']) {
      const times = [...originTimes(spec, dir), ...extensionTimes(key, routeId, spec, dir, ctx.feed)];
      times.forEach((dep, i) => {
        trips.push(makeTrip(`${key}:${routeId}:${dir}:${i}`, routeId, dir, stopsFor(route, dir, dep), {
          band: bandAt(spec.bands, dep),
          extended: dep > hm(spec.last[dir]),
        }));
      });
    }
  }

  if (day.loop) trips.push(...loopTrips(key, day, trips));
  applyDelays(key, trips, ctx.feed);
  return { type, trips };
}

/** Short loops shadow the Blue mainline by `offset` minutes inside the peak windows. */
function loopTrips(key, day, trips) {
  const blue = ROUTES.blue;
  const loop = ROUTES.loop;
  const windows = day.loop.windows.map(([f, t]) => [hm(f), hm(t)]);
  const inPeak = (t) => windows.some(([f, to]) => t >= f && t < to);
  const out = [];
  for (const trip of trips) {
    if (trip.route !== 'blue') continue;
    const [start, end] = trip.dir === 'fwd' ? [loop.from, loop.to] : [loop.to, loop.from];
    const at = trip.stops.find((x) => x.s === start);
    if (!at) continue;
    const dep = at.t + day.loop.offset;
    if (!inPeak(dep)) continue;
    out.push(makeTrip(`${key}:loop:${trip.dir}:${out.length}`, 'loop', trip.dir, stopsFor(blue, trip.dir, dep, start, end), {
      band: trip.band,
    }));
  }
  return out;
}

/** Feed `extend` adjustments add trains past the published last train (event nights). */
function extensionTimes(key, routeId, spec, dir, feed) {
  const ext = feed?.adjustments?.find(
    (a) => a.date === key && a.extendUntil && (!a.lines || a.lines.includes(ROUTES[routeId].line)),
  );
  if (!ext) return [];
  let until = hm(ext.extendUntil);
  if (until < SERVICE_DAY_ROLLOVER_HOUR * 60) until += 24 * 60;
  const step = ext.headway || 15;
  const out = [];
  for (let t = hm(spec.last[dir]) + step; t <= until; t += step) out.push(t);
  return out;
}

/** Feed `delay` adjustments shift trains running inside a window, per line. */
function applyDelays(key, trips, feed) {
  const base = keyToDate(key).getTime();
  for (const adj of feed?.adjustments ?? []) {
    if (!adj.delay || !adj.from || !adj.to) continue;
    const from = (Date.parse(adj.from) - base) / MIN;
    const to = (Date.parse(adj.to) - base) / MIN;
    for (const trip of trips) {
      if (adj.lines && !adj.lines.includes(trip.line)) continue;
      const start = trip.stops[0].t;
      const end = trip.stops[trip.stops.length - 1].t;
      if (end < from || start > to) continue;
      for (const st of trip.stops) st.t += adj.delay;
      trip.delay = adj.delay;
    }
  }
}

/** The most a trip listed for leaving sooner may leave before the quickest way, in minutes. */
const SOONER_MAX = 15;

export class Schedule {
  constructor(ctx = {}) {
    this.ctx = ctx;
    this.days = new Map();
    this.windows = new Map();
    this.lasts = new Map();
  }

  setContext(ctx) {
    this.ctx = ctx;
    this.days.clear();
    this.windows.clear();
    this.lasts.clear();
  }

  day(key) {
    if (!this.days.has(key)) {
      const built = buildDay(key, this.ctx);
      const base = keyToDate(key).getTime();
      for (const trip of built.trips) {
        trip.serviceDate = key;
        for (const st of trip.stops) st.at = base + st.t * MIN;
        trip.dep = trip.stops[0].at;
        trip.arr = trip.stops[trip.stops.length - 1].at;
      }
      this.days.set(key, built);
    }
    return this.days.get(key);
  }

  /** Trips for the current service day plus the next, with a sorted connection list for routing. */
  window(nowMs) {
    const key = serviceDateKey(nowMs);
    if (!this.windows.has(key)) {
      const trips = [...this.day(key).trips, ...this.day(addDays(key, 1)).trips];
      const byId = new Map(trips.map((t) => [t.id, t]));
      const conns = [];
      for (const trip of trips) {
        for (let i = 0; i < trip.stops.length - 1; i++) {
          const a = trip.stops[i];
          const b = trip.stops[i + 1];
          conns.push({ trip: trip.id, from: a.s, to: b.s, dep: a.at, arr: b.at });
        }
      }
      conns.sort((x, y) => x.dep - y.dep || x.arr - y.arr);
      this.windows.set(key, { key, trips, byId, conns });
    }
    return this.windows.get(key);
  }

  today(nowMs) {
    const key = serviceDateKey(nowMs);
    return { key, ...dayTypeFor(key, this.ctx), label: TIMETABLE[dayTypeFor(key, this.ctx).type].label };
  }

  /** Upcoming departures from a station, all directions. */
  departures(station, nowMs, limit = 12) {
    const { trips } = this.window(nowMs);
    const out = [];
    for (const trip of trips) {
      const idx = trip.stops.findIndex((x) => x.s === station);
      if (idx < 0 || idx === trip.stops.length - 1) continue;
      const at = trip.stops[idx].at;
      if (at < nowMs) continue;
      out.push({ trip, at, idx });
    }
    out.sort((a, b) => a.at - b.at);
    return out.slice(0, limit);
  }

  /** Direct trains from `from` that later stop at `to`, departing at or after `nowMs`. */
  routeDepartures(from, to, nowMs, limit = 12) {
    const { trips } = this.window(nowMs);
    const out = [];
    for (const trip of trips) {
      const i = trip.stops.findIndex((x) => x.s === from);
      if (i < 0) continue;
      const k = trip.stops.findIndex((x, n) => n > i && x.s === to);
      if (k < 0 || trip.stops[i].at < nowMs) continue;
      out.push({ trip, dep: trip.stops[i].at, arr: trip.stops[k].at });
    }
    out.sort((a, b) => a.dep - b.dep);
    return out.slice(0, limit);
  }

  /** The last direct train of the current service day from `from` to `to`. */
  lastRouteDeparture(from, to, nowMs) {
    const key = serviceDateKey(nowMs);
    let best = null;
    for (const trip of this.day(key).trips) {
      const i = trip.stops.findIndex((x) => x.s === from);
      if (i < 0) continue;
      const k = trip.stops.findIndex((x, n) => n > i && x.s === to);
      if (k < 0) continue;
      if (!best || trip.stops[i].at > best.dep) best = { trip, dep: trip.stops[i].at, arr: trip.stops[k].at };
    }
    return best;
  }

  /** Last departure from a station toward each destination for the current service day. */
  lastTrains(station, nowMs) {
    const key = serviceDateKey(nowMs);
    const lasts = new Map();
    for (const trip of this.day(key).trips) {
      if (trip.route === 'loop') continue;
      const idx = trip.stops.findIndex((x) => x.s === station);
      if (idx < 0 || idx === trip.stops.length - 1) continue;
      const at = trip.stops[idx].at;
      const k = `${trip.route}:${trip.dir}`;
      if (!lasts.has(k) || lasts.get(k).at < at) lasts.set(k, { trip, at });
    }
    return [...lasts.values()].sort((a, b) => a.at - b.at);
  }

  /**
   * Earliest arrival leaving no earlier than `startMs`. When nothing reaches `to` tonight (a
   * line has closed), plan again from the start of the next service day.
   */
  plan(from, to, startMs) {
    if (from === to) return null;
    const tonight = this.scan(from, to, startMs);
    if (tonight) return tonight;
    const tomorrow = keyToDate(addDays(serviceDateKey(startMs), 1)).getTime() + SERVICE_DAY_ROLLOVER_HOUR * 60 * MIN;
    return tomorrow > startMs ? this.scan(from, to, tomorrow) : null;
  }

  /** Connection Scan over the trips of the service day containing `startMs` and the next. */
  scan(from, to, startMs, boardFirst = null) {
    const { conns, byId } = this.window(startMs);
    const earliest = new Map([[from, startMs]]);
    const legCount = new Map([[from, 0]]);
    const enter = new Map();
    const ptr = new Map();
    let i = lowerBound(conns, startMs);
    for (; i < conns.length; i++) {
      const c = conns[i];
      const best = earliest.get(to);
      if (best !== undefined && best <= c.dep) break;
      // Board each trip where it costs the fewest legs; on a tie take the later stop, so a
      // route never rides away from an interchange to catch the same train further back.
      const e = earliest.get(c.from);
      const arrivedBy = ptr.get(c.from)?.exit.trip;
      // With `boardFirst`, the trip has to start on that train: no other boards at the start.
      const startsElsewhere = boardFirst !== null && c.from === from && c.trip !== boardFirst;
      if (e !== undefined && arrivedBy !== c.trip && !startsElsewhere) {
        const ready = c.from === from ? e : e + (STATIONS[c.from].transfer ?? 4) * MIN;
        const legs = legCount.get(c.from);
        const cur = enter.get(c.trip);
        // Nobody waits at an interchange overnight: a change must stay within one service day.
        const sameDay = c.from === from || byId.get(arrivedBy).serviceDate === byId.get(c.trip).serviceDate;
        if (ready <= c.dep && sameDay && (!cur || legs <= cur.legs)) {
          enter.set(c.trip, { conn: c, legs });
        }
      }
      const en = enter.get(c.trip);
      if (!en) continue;
      // Earliest arrival first; arriving at the same time, the way with fewer changes.
      const cur = earliest.get(c.to);
      if (cur === undefined || c.arr < cur || (c.arr === cur && en.legs + 1 < legCount.get(c.to))) {
        earliest.set(c.to, c.arr);
        legCount.set(c.to, en.legs + 1);
        ptr.set(c.to, { enter: en.conn, exit: c });
      }
    }
    if (!ptr.has(to)) return null;

    const legs = [];
    let s = to;
    for (let guard = 0; s !== from && guard < 8; guard++) {
      const { enter: en, exit: ex } = ptr.get(s);
      const trip = byId.get(en.trip);
      const a = trip.stops.findIndex((x) => x.s === en.from && x.at === en.dep);
      const b = trip.stops.findIndex((x) => x.s === ex.to && x.at === ex.arr);
      legs.unshift({ trip, from: en.from, to: ex.to, dep: en.dep, arr: ex.arr, stops: trip.stops.slice(a, b + 1) });
      s = en.from;
    }
    if (s !== from) return null;
    return { from, to, legs, dep: legs[0].dep, arr: legs[legs.length - 1].arr };
  }

  /**
   * The next `count` journeys, by departure. Each is the earliest arrival from its start, and the
   * latest start for that arrival, so the quickest. As well, when a train leaving up to
   * SOONER_MAX min earlier still arrives as early (often with a change), the soonest such trip is
   * listed too, so a rider already at the platform can take it instead of waiting.
   */
  journeys(from, to, startMs, count = 4) {
    // By arrival: the quickest way (latest start) and the soonest start.
    const groups = new Map();
    const add = (j) => {
      const g = groups.get(j.arr);
      if (!g) groups.set(j.arr, { quick: j, soon: j });
      else {
        if (j.dep > g.quick.dep) g.quick = j;
        if (j.dep < g.soon.dep) g.soon = j;
      }
    };
    let t = startMs;
    for (let n = 0; n < count * 20; n++) {
      const j = this.plan(from, to, t);
      if (!j) break;
      // Settled: enough arrivals listed, and this one arrives after them all.
      if (groups.size >= count && j.arr > Math.max(...groups.keys())) break;
      for (const e of this.leavesSooner(from, to, t, j)) add(e);
      add(j);
      t = j.dep + 1;
    }
    const out = [];
    for (const g of groups.values()) {
      out.push(g.quick);
      // Leaving sooner is only worth it a little sooner, not an hour early to wait at a change.
      if (g.soon !== g.quick && g.quick.dep - g.soon.dep <= SOONER_MAX * MIN) out.push(g.soon);
    }
    return out.sort((a, b) => a.dep - b.dep).slice(0, count);
  }

  /** Trips that leave `from` in [t, j.dep) and still arrive by `j.arr`, each on its own first train. */
  leavesSooner(from, to, t, j) {
    const out = [];
    const seen = new Set();
    for (const { trip, at } of this.departures(from, Math.max(t, j.dep - SOONER_MAX * MIN), 40)) {
      if (at >= j.dep) break;
      if (seen.has(trip.id)) continue;
      seen.add(trip.id);
      const alt = this.scan(from, to, at, trip.id);
      // Never change onto a train that could be boarded at the start instead.
      const pointless = alt?.legs.slice(1).some((l) => l.trip.stops.some((x) => x.s === from && x.at >= at));
      if (alt && !pointless && alt.dep < j.dep && alt.arr <= j.arr) out.push(alt);
    }
    return out;
  }


  /**
   * The last journey of the current service day from `from` to `to`, with changes if needed:
   * the latest start that still gets there tonight. Binary search over plan(), cached per day.
   */
  lastJourney(from, to, nowMs) {
    const date = serviceDateKey(nowMs);
    const key = `${from}|${to}|${date}`;
    if (this.lasts.has(key)) return this.lasts.get(key);
    let best = this.plan(from, to, nowMs);
    if (best && best.legs[0].trip.serviceDate !== date) best = null;
    if (best) {
      let lo = best.dep;
      let hi = nowMs + 30 * 60 * MIN;
      while (hi - lo > MIN) {
        const mid = Math.floor((lo + hi) / 2);
        const j = this.plan(from, to, mid);
        if (j && j.legs[0].trip.serviceDate === date) {
          best = j;
          lo = Math.max(mid, j.dep);
        } else hi = mid;
      }
    }
    this.lasts.set(key, best);
    return best;
  }

  /** Previous departure of the same service pattern at a station, for the headway dial. */
  previousDeparture(trip, station, beforeMs) {
    const { trips } = this.window(beforeMs);
    let best = null;
    for (const t of trips) {
      if (t.route !== trip.route || t.dir !== trip.dir) continue;
      const st = t.stops.find((x) => x.s === station);
      if (st && st.at < beforeMs && (!best || st.at > best)) best = st.at;
    }
    return best;
  }

  /** Where a trip is at `nowMs`: before departure, between two stops, or arrived. */
  position(stops, nowMs) {
    if (nowMs < stops[0].at) return { state: 'waiting', next: 0 };
    for (let i = 0; i < stops.length - 1; i++) {
      const a = stops[i];
      const b = stops[i + 1];
      if (nowMs < b.at) {
        return { state: 'moving', prev: i, next: i + 1, frac: (nowMs - a.at) / (b.at - a.at) };
      }
    }
    return { state: 'arrived', next: stops.length - 1 };
  }
}

function lowerBound(conns, t) {
  let lo = 0;
  let hi = conns.length;
  while (lo < hi) {
    const mid = (lo + hi) >> 1;
    if (conns[mid].dep < t) lo = mid + 1;
    else hi = mid;
  }
  return lo;
}

export const lineOf = (id) => LINES[id];
