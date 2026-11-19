import { Schedule, serviceDateKey, bandAt, hm } from './engine.js';
import { STATIONS, ROUTES, LINES, PICKER_GROUPS, directDestinations, transferDestinations } from './network.js';
import { TIMETABLE, TIMETABLE_SOURCE } from './timetable.js';
import { createFeed, activeNotices } from './live.js';
import { PLATFORMS, platformText } from './platforms.js';
import { MAP_SIZE, MAP_STATIONS, MAP_LINES, MAP_FEATURES } from './map-layout.js';

const MIN = 60_000;
const STORE = 'headway:v3';
const COUNT_CHOICES = [3, 5, 8];
const view = document.getElementById('view');
const dock = document.getElementById('dock');
const sheetRoot = document.getElementById('sheet-root');
const toastEl = document.getElementById('toast');
const reduceMotion = matchMedia('(prefers-reduced-motion: reduce)');

/* ---------- preferences ---------- */

const DEFAULTS = {
  routes: [],
  count: 5,
  lastWarn: true,
  theme: 'auto',
  override: null,
  feedUrl: '',
  reminder: null,
  mock: null,
  upiPaidAt: 0, // last UPI top-up; it still has to be written to the card in Airtel
  upi: '', // the metro card's own UPI ID (from its "Scan to recharge" QR); stays in this browser
  coaches: {}, // route id -> 'front' | 'middle' | 'rear', set by the rider
  delays: {}, // route id -> minutes the rider's trains run off the timetable, -10 (early) to +10 (late)
  trainDelays: {}, // route id -> { 'HH:MM' timetabled departure from the boarding station: minutes }, overrides `delays` for that train, daily
  flips: {}, // station pair -> { at, home, applied }: show the trip from `home` until `at`, the ride back after
};

const DELAY_MAX = 10;

const routeId = (from, to) => `${from}>${to}`;

/* ---------- clock (mockable for testing) ---------- */

const IST_MS = 5.5 * 3600_000;
const DAY_MS = 24 * 3600_000;
const MOCK_CHOICES = [[480, '8 AM'], [1080, '6 PM'], [1410, '11:30 PM']];

/** Now, shifted by the mock offset when mock time is on in Settings. */
function clockNow() {
  return Date.now() + (prefs?.mock?.offset ?? 0);
}

/** Offset that makes now read `minutes` past midnight IST on the current service day (rolls at 03:00). */
function mockOffsetFor(minutes) {
  const real = Date.now();
  const serviceDay = Math.floor((real + IST_MS - 3 * 3600_000) / DAY_MS) * DAY_MS;
  const target = serviceDay + minutes * MIN + (minutes < 180 ? DAY_MS : 0) - IST_MS;
  return target - real;
}

function load() {
  try {
    const saved = JSON.parse(localStorage.getItem(STORE) || 'null');
    if (saved) {
      const p = { ...DEFAULTS, ...saved };
      if (!COUNT_CHOICES.includes(p.count)) p.count = DEFAULTS.count;
      return p;
    }
    // Carry the last trip from the trip-planner versions over as a first route.
    const old = JSON.parse(localStorage.getItem('headway:v2') || localStorage.getItem('headway:v1') || 'null');
    if (old?.from && old?.to && STATIONS[old.from] && STATIONS[old.to]) {
      return { ...DEFAULTS, theme: old.theme ?? 'auto', routes: [{ id: routeId(old.from, old.to), from: old.from, to: old.to }] };
    }
  } catch {}
  return { ...DEFAULTS };
}

let prefs = load();
prefs.routes = prefs.routes.filter((r) => STATIONS[r.from] && STATIONS[r.to]);

function save(patch) {
  prefs = { ...prefs, ...patch };
  try {
    localStorage.setItem(STORE, JSON.stringify(prefs));
  } catch {}
}

/* ---------- data ---------- */

let feedState = { status: 'official', feed: null };
const schedule = new Schedule(scheduleContext());

function scheduleContext() {
  return { feed: feedState.feed, override: prefs.override };
}

function refreshSchedule() {
  schedule.setContext(scheduleContext());
  render({ force: true });
}

const feed = createFeed({
  remoteUrl: prefs.feedUrl,
  onChange(state) {
    feedState = state;
    refreshSchedule();
  },
});

/* ---------- formatting ---------- */

const pad = (n) => String(n).padStart(2, '0');
/** "8:13 AM": 12-hour times throughout. */
const clockTime = (ms) => {
  const d = new Date(ms);
  return `${d.getHours() % 12 || 12}:${pad(d.getMinutes())}`;
};
const ampm = (ms) => (new Date(ms).getHours() < 12 ? 'AM' : 'PM');
const clock = (ms) => `${clockTime(ms)} ${ampm(ms)}`;
/** "05:30" from the timetable as "5:30 AM". */
const hm12 = (s) => {
  const [h, m] = s.split(':').map(Number);
  return `${h % 12 || 12}:${pad(m)} ${h % 24 < 12 ? 'AM' : 'PM'}`;
};
const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => `&#${c.charCodeAt(0)};`);
const name = (id) => STATIONS[id]?.name ?? '';
const ic = (n, cls = '') => `<span class="ms ${cls}" aria-hidden="true">${n}</span>`;
const plural = (n, w) => `${n} ${w}${n === 1 ? '' : 's'}`;
// Rounds up: departures are on the minute, so this matches subtracting the clock times shown.
const mins = (ms) => Math.max(0, Math.ceil(ms / MIN));

function span(ms) {
  const m = Math.max(0, Math.round(ms / MIN));
  if (m < 60) return `${m} min`;
  return `${Math.floor(m / 60)} h ${m % 60} min`;
}

// Hero number: whole minutes, m:ss under two minutes, h:mm past an hour.
function bigParts(ms) {
  if (ms < 30_000) return ['Now', ''];
  if (ms <= 2 * MIN) {
    const s = Math.ceil(ms / 1000);
    return [`${Math.floor(s / 60)}:${pad(s % 60)}`, 'min'];
  }
  const m = mins(ms);
  if (m < 60) return [String(m), 'min'];
  return [`${Math.floor(m / 60)}:${pad(m % 60)}`, 'h'];
}

const FORMATS = {
  big: (ms) => bigParts(ms)[0],
  bigUnit: (ms) => bigParts(ms)[1],
  // Same rounding as the hero number, so "in 7 min" and a big "7" always agree.
  chip: (ms) => (ms < 30_000 ? 'now' : ms < 60 * MIN ? `${mins(ms)} min` : `${Math.floor(mins(ms) / 60)} h ${mins(ms) % 60}`),
  // Banner clocks: data-at is 0 for the mocked clock, the offset for real time.
  now: (ms) => clock(-ms),
  in: (ms) => (ms < 30_000 ? 'now' : ms < 60 * MIN ? `in ${mins(ms)} min` : `in ${span(ms)}`),
};
const live = (at, fmt) => `<span data-at="${at}" data-fmt="${fmt}">${FORMATS[fmt](at - clockNow())}</span>`;

const lineName = (line) => LINES[line].name;

function trainNote(trip) {
  if (trip.route === 'loop') return 'short loop';
  if (trip.route === 'inter') return 'via CMBT';
  return '';
}

/** "Blue Line toward Wimco Nagar Depot", from the routes that serve this pair. */
function routeLabel(r, inter = false) {
  const groups = directDestinations(r.from);
  const g = groups.find((x) => x.stations.some((s) => s.id === r.to));
  if (!g) return '';
  if (inter) {
    // The inter-corridor runs Chennai Central <-> Airport; name the end this route heads to.
    const stops = ROUTES.inter.fwd.map(([id]) => id);
    const end = stops.indexOf(r.from) < stops.indexOf(r.to) ? stops.at(-1) : stops[0];
    return `Inter-corridor · towards ${name(end)}`;
  }
  return `${lineName(g.line)} · towards ${g.terminals.map(name).join(' or ')}`;
}

/** Platform at the boarding station, from the direction the train heads off in. */
function platformFor(r) {
  const g = directDestinations(r.from).find((x) => x.stations.some((s) => s.id === r.to));
  return g ? platformAt(r.from, g) : null;
}

/** Platform text at `from` for a direction group (its line and next stop), e.g. "Platform 2". */
function platformAt(from, g) {
  if (!PLATFORMS[from]?.[g.line]) return null;
  const order = ROUTES[g.line].fwd.map(([id]) => id);
  const i = order.indexOf(from);
  const j = order.indexOf(g.next);
  if (i < 0 || j < 0) return null;
  const p = PLATFORMS[from][g.line][j > i ? order.at(-1) : order[0]];
  return p ? platformText(p) : null;
}

function boardingTags(r, shown) {
  const p = shown?.legs ? legPlatform(shown.legs[0]) : platformFor(r);
  const c = prefs.coaches?.[r.id];
  // The shown train's delay: tap to set this train's own (saved routes only).
  const saved = r !== preview && prefs.routes.some((x) => x.id === r.id);
  const d = shown?.delay ?? 0;
  const own = shown?.timetabled != null && trainDelayOf(r.id, shown.timetabled) != null;
  const tag =
    saved && shown?.timetabled != null
      ? `<button class="delay ${d > 0 ? 'late' : d < 0 ? 'early' : 'none'}" data-act="train-delay" data-route="${r.id}" data-train="${shown.timetabled}" aria-label="${delayText(
          d,
        )}${own ? ', this train' : ''}. Change">${ic(d > 0 ? 'more_time' : d < 0 ? 'history' : 'schedule')}${delayText(d)}${own ? '<em>this train</em>' : ''}</button>`
      : '';
  if (!p && !c && !tag) return '';
  return `<div class="boarding rc-pad">${p ? `<span>${esc(p)}</span>` : ''}${c ? `<span>${c[0].toUpperCase()}${c.slice(1)} coach</span>` : ''}${tag}</div>`;
}

/* ----- trips with changes ----- */

/** The colour key of a leg: its line, or the inter-corridor service. */
const legLine = (leg) => (leg.trip.route === 'inter' ? 'inter' : leg.trip.line);
const legColor = (leg) => (legLine(leg) === 'inter' ? 'var(--green)' : lineColor[leg.trip.line]);
/** A leg's rail: the inter-corridor runs on Green Line track and then Blue, so it fades green to blue. */
const legRail = (leg) => (legLine(leg) === 'inter' ? 'linear-gradient(var(--green), var(--blue))' : lineColor[leg.trip.line]);
const legShort = (leg) => (legLine(leg) === 'inter' ? 'Inter-corridor' : lineName(leg.trip.line).replace(/ Line$/, ''));
const legLineName = (leg) => (legLine(leg) === 'inter' ? 'Inter-corridor' : lineName(leg.trip.line));

/** A dot that shows every line of the trip, in order. */
function legsGradient(legs) {
  const cols = legs.flatMap((l) => (legLine(l) === 'inter' ? ['var(--green)', 'var(--blue)'] : [legColor(l)]));
  return cols.length > 1 ? `linear-gradient(135deg, ${cols.join(', ')})` : cols[0];
}

/** "1 change at Alandur", "2 changes at Alandur and Vadapalani". */
function changesLabel(d) {
  const at = d.legs.slice(1).map((l) => name(l.from));
  return `${plural(at.length, 'change')} at ${at.length > 1 ? `${at.slice(0, -1).join(', ')} and ${at.at(-1)}` : at[0]}`;
}

/** "Blue → Inter-corridor", each in its colour: the trains of the shown trip, in order. */
function legsLine(d) {
  return `<span class="line legs">${d.legs
    .map((l) => `<span><i class="dot" style="background: ${legRail(l).replace('linear-gradient(', 'linear-gradient(135deg, ')}"></i>${esc(legShort(l))}</span>`)
    .join('<span class="arrow">→</span>')}</span>`;
}

/** A thin bar on a chip with each train's colour, by its share of the ride, so options read at a glance. */
function legBar(d) {
  if (!d.legs) return '';
  return `<span class="legbar" aria-label="${esc(d.legs.map(legShort).join(', then '))}">${d.legs
    .map((l) => `<i style="flex: ${Math.max(1, (l.arr - l.dep) / MIN)}; background: ${legRail(l).replace('linear-gradient(', 'linear-gradient(90deg, ')}"></i>`)
    .join('')}</span>`;
}

/** Platform for a leg at the station it's boarded, from the direction its train heads off in. */
function legPlatform(leg) {
  const g = directDestinations(leg.from).find((x) => x.stations.some((s) => s.id === leg.to));
  return g ? platformAt(leg.from, g) : null;
}

/**
 * The trip step by step: board, ride, get off, walk to the next line, and so on to arrival.
 * A coloured rail per train, dashed while walking between lines.
 */
function timelineHtml(d) {
  const stop = (time, station, sub, cls, color) =>
    `<li class="tl-stop ${cls}" style="--c: ${color}"><span class="tl-t">${time}</span><span class="tl-dot"></span><span class="tl-x"><span class="t">${esc(
      name(station),
    )}</span>${sub ? `<span class="s">${sub}</span>` : ''}</span></li>`;
  const seg = (text, cls, color, rail) =>
    `<li class="tl-seg ${cls}" style="--c: ${color}${rail ? `; --rail: ${rail}` : ''}"><span class="tl-t"></span><span class="tl-rail"></span><span class="tl-x">${text}</span></li>`;
  const out = [];
  d.legs.forEach((leg, i) => {
    const color = legColor(leg);
    if (i > 0) {
      const prev = d.legs[i - 1];
      const walk = STATIONS[leg.from].transfer ?? 4;
      const spare = Math.round((leg.dep - prev.arr) / MIN) - walk;
      out.push(
        seg(
          `${ic('directions_walk')}<span>Change to ${legLine(leg) === 'inter' ? 'an Inter-corridor train' : `the ${esc(legLineName(leg))}`}, ${walk} min walk${spare > 0 ? ` · ${spare} min to spare` : ''}</span>`,
          'walk',
          'var(--sub)',
        ),
      );
    }
    const plat = legPlatform(leg);
    out.push(stop(clock(leg.dep), leg.from, `${esc(legLineName(leg))} towards ${esc(name(leg.trip.dest))}${plat ? ` · ${esc(plat)}` : ''}`, 'board', color));
    out.push(seg(`${plural(leg.stops.length - 1, 'stop')} · ${span(leg.arr - leg.dep)}`, 'ride', color, legRail(leg)));
    const lastLeg = i === d.legs.length - 1;
    out.push(stop(clock(leg.arr), leg.to, lastLeg ? 'Arrive' : 'Get off', lastLeg ? 'end' : 'off', color));
  });
  return `<ol class="tl rc-pad" aria-label="Trip steps">${out.join('')}</ol>`;
}

/** Only inter-corridor trains serve this route (Green Line tracks, then Blue to the Airport). */
const interOnly = (m) => m.kind !== 'none' && m.deps.length > 0 && m.deps.every((d) => d.trip.route === 'inter' && !hasChange(d));

function routeLine(r) {
  const g = directDestinations(r.from).find((x) => x.stations.some((s) => s.id === r.to));
  return g?.line ?? 'blue';
}

/* ---------- route model ---------- */

/** The rider's static delay for a saved route, in whole minutes within ±DELAY_MAX. */
function delayOf(id) {
  const d = Math.round(Number(prefs.delays?.[id]) || 0);
  return Math.max(-DELAY_MAX, Math.min(DELAY_MAX, d));
}

const delayText = (d) => (d > 0 ? `${d} min late` : d < 0 ? `${-d} min early` : 'On time');

/** "08:14": a train's timetabled departure as a daily key. */
const timeKey = (ms) => {
  const d = new Date(ms);
  return `${pad(d.getHours())}:${pad(d.getMinutes())}`;
};

/** The delay set for one train (by its timetabled departure), or null when it follows the route's. */
function trainDelayOf(id, timetabled) {
  const v = prefs.trainDelays?.[id]?.[timeKey(timetabled)];
  return v == null ? null : Math.max(-DELAY_MAX, Math.min(DELAY_MAX, Math.round(Number(v) || 0)));
}

/** The delay a train runs with: its own if set, else the route's. */
const delayFor = (id, timetabled) => trainDelayOf(id, timetabled) ?? delayOf(id);

/**
 * A shown departure's timetabled time, from the delays now in force. Trains with their own delay
 * are checked first (a shown 8:15 is the 8:13 with its own +2 before it's an 8:15 that follows
 * the route); otherwise it's the route's delay.
 */
function timetabledOf(id, shown) {
  for (const [key, d] of Object.entries(prefs.trainDelays?.[id] ?? {})) {
    const t = shown - d * MIN;
    if (timeKey(t) === key) return t;
  }
  return shown - delayOf(id) * MIN;
}

/** A departure (or a trip's legs) moved by `mins`, remembering its timetabled time and delay. */
function withDelay(x, mins) {
  if (!x) return null;
  const off = mins * MIN;
  return {
    ...x,
    dep: x.dep + off,
    arr: x.arr + off,
    timetabled: x.dep,
    delay: mins,
    ...(x.legs ? { legs: x.legs.map((l) => ({ ...l, dep: l.dep + off, arr: l.arr + off })) } : {}),
  };
}

/** A trip that takes more than one train. */
const hasChange = (d) => (d?.legs?.length ?? 1) > 1;

/**
 * A planned journey as a route departure: the first train's trip (for the service day), the
 * whole journey's dep and arr, and its legs. The delay moves every leg.
 */
function fromJourney(j, off) {
  if (!j) return null;
  return { trip: j.legs[0].trip, dep: j.dep + off, arr: j.arr + off, legs: j.legs.map((l) => ({ ...l, dep: l.dep + off, arr: l.arr + off })) };
}

/** A departure moved by the route's delay; the trip itself is shared, so only dep and arr change. */
const shifted = (x, off) => (x && off ? { ...x, dep: x.dep + off, arr: x.arr + off } : x);

/* ---------- switch direction by time ---------- */

const FLIP_DEFAULT = 17 * 60; // 5 PM
const FLIP_MIN = 6 * 60;
const FLIP_MAX = 23 * 60 + 30;
const FLIP_STEP = 30;

/** One key for both directions of a route, so the setting survives a swap. */
const pairKey = (r) => [r.from, r.to].sort().join('|');
const flipOf = (r) => prefs.flips?.[pairKey(r)] ?? null;
/** Both directions saved as separate cards: each already has its own, so nothing flips. */
const bothSaved = (r) => prefs.routes.some((x) => x.from === r.to && x.to === r.from);
/** "5:00 PM" from minutes past midnight. */
const minutesLabel = (m) => hm12(`${Math.floor(m / 60) % 24}:${pad(m % 60)}`);

/** Whether `now` is past the switch time in its service day (which runs to 3 AM), and that half's key. */
function flipPeriod(now, at) {
  const d = new Date(now);
  const m = d.getHours() * 60 + d.getMinutes() + (d.getHours() < 3 ? 24 * 60 : 0);
  const pm = m >= at;
  return { pm, key: `${serviceDateKey(now)}:${pm ? 'pm' : 'am'}` };
}

/** The way a route should face: from `home` before the switch time, back to it after. */
function facing(r, f, pm) {
  const other = r.from === f.home ? r.to : r.from;
  return pm ? { from: other, to: f.home } : { from: f.home, to: other };
}

/**
 * Turns routes that switch by time to face the right way, once per change-over, so a swap by
 * hand lasts until the next one. Returns true when a route flipped.
 */
function applyFlips(now) {
  const flips = prefs.flips ?? {};
  if (!Object.keys(flips).length) return false;
  let routes = prefs.routes;
  let reminder = prefs.reminder;
  const next = { ...flips };
  let touched = false;
  let flipped = false;
  for (const r of prefs.routes) {
    const k = pairKey(r);
    const f = flips[k];
    if (!f || (f.home !== r.from && f.home !== r.to)) continue;
    const { pm, key } = flipPeriod(now, f.at);
    if (f.applied === key) continue;
    next[k] = { ...f, applied: key };
    touched = true;
    const want = facing(r, f, pm);
    if (want.from === r.from || bothSaved(r)) continue;
    const id = routeId(want.from, want.to);
    routes = routes.map((x) => (x.id === r.id ? { id, ...want } : x));
    if (reminder?.routeId === r.id) reminder = null;
    flipped = true;
  }
  if (touched) save({ flips: next, routes, reminder });
  return flipped;
}

function flipCard(r) {
  const f = flipOf(r);
  const both = bothSaved(r);
  const on = !!f && !both;
  const head = `
        <div class="dl-head">
          <span class="dl-icon">${ic('swap_vert')}</span>
          <span class="grow"><span class="t">Reverse automatically</span><span class="s">Show the return trip after a set time</span></span>
          ${both ? '' : `<button class="switch" role="switch" aria-checked="${on}" data-act="flip-toggle" data-route="${esc(r.id)}" aria-label="Reverse automatically"><i></i></button>`}
        </div>`;
  if (both) return `<div class="card fl">${head}<span class="fl-foot">You've saved both directions as separate routes, so each keeps its own card.</span></div>`;
  if (!on) return `<div class="card fl">${head}</div>`;
  const home = f.home;
  const other = r.from === home ? r.to : r.from;
  const { pm } = flipPeriod(clockNow(), f.at);
  const t = minutesLabel(f.at);
  const row = (active, icon, when, a, b) =>
    `<div class="fl-row ${active ? 'on' : ''}"><span class="fl-ic">${ic(icon)}</span><span class="grow"><span class="k">${when}${
      active ? '<em>Now</em>' : ''
    }</span><span class="v">${esc(name(a))} → ${esc(name(b))}</span></span></div>`;
  return `
      <div class="card fl">
        ${head}
        <div class="fl-trips">
          <div class="fl-rows">
            ${row(!pm, 'light_mode', `Until ${t}`, home, other)}
            ${row(pm, 'bedtime', `From ${t}`, other, home)}
          </div>
          <button class="fl-swap" data-act="flip-home" data-route="${esc(r.id)}" aria-label="Swap morning and evening">${ic('swap_vert')}</button>
        </div>
        <div class="fl-time">
          <span class="grow">Reverse at</span>
          <button class="dl-step" data-act="flip-time" data-route="${esc(r.id)}" data-d="${-FLIP_STEP}" aria-label="30 minutes earlier" ${f.at <= FLIP_MIN ? 'disabled' : ''}>${ic('remove')}</button>
          <output class="fl-at" aria-live="polite">${t}</output>
          <button class="dl-step" data-act="flip-time" data-route="${esc(r.id)}" data-d="${FLIP_STEP}" aria-label="30 minutes later" ${f.at >= FLIP_MAX ? 'disabled' : ''}>${ic('add')}</button>
        </div>
        <span class="fl-foot">Tap ⇅ on the card to reverse it any time.</span>
      </div>`;
}

/** Changes a route's switch-by-time setting; `apply` turns the card to match it right away. */
function setFlip(r, f, apply) {
  const flips = { ...(prefs.flips ?? {}) };
  const k = pairKey(r);
  if (f) flips[k] = f;
  else delete flips[k];
  save({ flips });
  if (apply) applyFlips(clockNow());
  const now = prefs.routes.find((x) => pairKey(x) === k);
  if (now) refreshRouteMenu(now.id);
  render({ force: true, animate: true });
}

/* ---------- plan for another time (quick look) ---------- */

/**
 * Trains for a chosen time instead of now. "Leave at": the first ones from then. "Arrive by":
 * the latest ones that still get there in time, found by probing the trip's length and looking
 * back from the deadline. Works for direct trains and trips with changes alike.
 */
function planOptions(r, plan, count) {
  const list = (start, n) => schedule.journeys(r.from, r.to, start, n).map((j) => fromJourney(j, 0));
  if (plan.mode === 'leave') return list(plan.at, count);
  const probe = list(plan.at - 3 * 60 * MIN, 1)[0];
  if (!probe) return [];
  const start = plan.at - (probe.arr - probe.dep) - 45 * MIN;
  return list(start, 40)
    .filter((x) => x.arr <= plan.at)
    .slice(-count);
}

function planModel(r) {
  const deps = planOptions(r, r.plan, prefs.count);
  // Arriving by a time, the best train is the last one that makes it.
  return { r, kind: deps.length ? 'plan' : 'plan-none', plan: r.plan, deps, best: r.plan.mode === 'arrive' ? deps.length - 1 : 0 };
}

/** "Leave at 8:00 AM tomorrow", "Arrive by 9:30 AM", or "Depart now". */
function whenText(plan) {
  if (!plan) return 'Depart now';
  return `${plan.mode === 'arrive' ? 'Arrive by' : 'Depart at'} ${clock(plan.at)}${dayWord(plan.at)}`;
}

/** "", " tomorrow" or " on Friday", against the current (possibly mocked) date. */
function dayWord(ms) {
  const day = (t) => {
    const d = new Date(t);
    return new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime();
  };
  const diff = Math.round((day(ms) - day(clockNow())) / (24 * 60 * MIN));
  if (diff === 0) return '';
  if (diff === 1) return ' tomorrow';
  return ` on ${new Date(ms).toLocaleDateString('en-IN', { weekday: 'long' })}`;
}

function routeModel(r, now) {
  if (r.plan) return planModel(r);
  const today = schedule.today(now).key;
  // Each train runs with its own delay (or the route's). A late train leaves after its
  // timetabled time, so look back as far as a delay can go and keep the ones still to come.
  // Every route is a journey: the planner's earliest way there for each departure, a direct
  // train or one with changes, whichever arrives first.
  const want = prefs.count + 6;
  const raw = schedule.journeys(r.from, r.to, now - DELAY_MAX * MIN, want).map((j) => fromJourney(j, 0));
  const deps = raw
    .map((x) => withDelay(x, delayFor(r.id, x.dep)))
    .filter((x) => x.dep >= now)
    .sort((a, b) => a.dep - b.dep)
    .slice(0, prefs.count);
  const next = deps[0] ?? null;
  if (!next) return { r, kind: 'none' };
  const lastRaw = fromJourney(schedule.lastJourney(r.from, r.to, now - DELAY_MAX * MIN), 0);
  const last = lastRaw && withDelay(lastRaw, delayFor(r.id, lastRaw.dep));
  const closed = next.trip.serviceDate !== today;
  return {
    r,
    kind: closed ? 'closed' : 'open',
    now,
    deps,
    next,
    last,
    late: !closed && !!last && prefs.lastWarn && last.dep - now < 60 * MIN,
    remaining: deps.filter((d) => d.trip.serviceDate === today).length,
    // Index of the first departure on the next service day, or -1.
    tomorrowFrom: closed ? 0 : deps.findIndex((d) => d.trip.serviceDate !== today),
    running: !!last && last.dep >= now,
  };
}

/* ---------- views ---------- */

function topbar(right, left = '') {
  return `<div class="topbar">${left}<span class="spacer"></span>${right}</div>`;
}

function routesScreen(now, models) {
  const notices = activeNotices(feedState.feed, now).map(noticeCard).join('');
  const head = `
    ${topbar(`
      <span class="glass-group glass">
        ${
          prefs.upi
            ? `<button data-act="top-up" aria-label="Top up metro card">${ic('credit_card')}</button>`
            : `<a href="${AIRTEL_TOPUP}" target="_blank" rel="noopener" aria-label="Top up metro card with Airtel">${ic('credit_card')}</a>`
        }
        <button data-act="add-route" aria-label="Add a route">${ic('add')}</button>
        <button data-act="settings" aria-label="Settings">${ic('more_horiz')}</button>
      </span>`)}
    <div class="title"><h1>Routes</h1></div>
    ${prefs.mock ? mockBanner() : ''}
    ${Date.now() - (prefs.upiPaidAt || 0) < 30 * MIN ? loadCardBanner() : ''}
    ${notices}`;

  const cards = [];
  if (preview) cards.push(routeCard(models.get(preview), true));
  if (!prefs.routes.length && !preview) return head + emptyState();
  for (const r of prefs.routes) cards.push(routeCard(models.get(r), false));
  return head + cards.join('');
}

/** After a UPI top-up: the money is paid in, but only the Airtel screen writes it to the card. */
function loadCardBanner() {
  return `<div class="strip load"><span class="grow"><span class="t">Top-up pending</span><span class="s">Tap the card in Airtel to add the balance.</span></span><a class="strip-btn load-go" href="${AIRTEL_TOPUP}" target="_blank" rel="noopener" data-act="card-loaded">Open Airtel</a><button class="mini" data-act="card-loaded" aria-label="Dismiss">${ic('close')}</button></div>`;
}

function mockBanner() {
  return `<div class="strip mock"><span class="grow"><span class="t">Mock time · <span data-at="0" data-fmt="now">${clock(clockNow())}</span></span><span class="s">Real time: <span data-at="${prefs.mock.offset}" data-fmt="now">${clock(Date.now())}</span></span></span><button class="strip-btn mock-off" data-act="mock" data-v="off">Turn off</button></div>`;
}

function noticeCard(n) {
  return `<div class="strip info"><span class="grow"><span class="t">${esc(n.title)}</span><span class="s">${esc(n.body ?? '')}</span></span></div>`;
}

function emptyState() {
  return `
    <div class="card hero" style="padding-top: 20px; gap: 16px">
      <span style="font-size: 28px; line-height: 34px; font-weight: 700">No saved routes</span>
      <span class="sub" style="font-size: 15px; line-height: 20px">Save a route to see its next trains. Tap ⇅ to reverse it for the trip back.</span>
      <button class="cta" data-act="add-route">${ic('add')}Add a route</button>
    </div>
    <span class="section-title">Or search a station</span>
    <button class="search-like card" style="background: var(--card); height: 52px" data-act="look">${ic('search')}Search stations</button>`;
}

const reminderOn = (r, dep) => !!(prefs.reminder && prefs.reminder.routeId === r.id && prefs.reminder.dep === dep && !prefs.reminder.fired);

// Picked train per route, by its timetabled departure (delays don't move it). Falls back to the
// next train once the picked one leaves.
const pickKey = (x) => x.timetabled ?? x.dep;
const picked = new Map();

function routeCard(m, isPreview) {
  const { r } = m;
  const line = routeLine(r);
  const lead = m.next ?? m.deps?.[0];
  const head = `
    <div class="rc-head">
      <span class="grow">
        <span class="t">${esc(name(r.from))} → ${esc(name(r.to))}</span>
        ${
          // From the next trip itself: where you change, or its train and where that's heading.
          lead?.legs
            ? `<span class="s"><i class="dot" style="background: ${legsGradient(lead.legs)}"></i>${esc(
                hasChange(lead) ? changesLabel(lead) : `${legLineName(lead.legs[0])} · towards ${name(lead.legs[0].trip.dest)}`,
              )}</span>`
            : `<span class="s"><i class="dot ${line}"></i>${esc(routeLabel(r))}</span>`
        }
      </span>
      <span class="pillgroup">
        <button data-act="swap" data-route="${r.id}" class="${!isPreview && flipOf(r) && !bothSaved(r) ? 'auto' : ''}" aria-label="${
          !isPreview && flipOf(r) && !bothSaved(r) ? `Reverse (automatic at ${minutesLabel(flipOf(r).at)})` : 'Reverse'
        }">${ic('swap_vert')}</button>
        ${
          isPreview
            ? `<button data-act="close-preview" aria-label="Close quick look">${ic('close')}</button>`
            : `<button data-act="route-menu" data-route="${r.id}" aria-label="Route options">${ic('more_horiz')}</button>`
        }
      </span>
    </div>`;

  // A quick look can be planned for another time.
  const when = isPreview
    ? `<div class="rc-pad when-row"><button class="when ${r.plan ? 'on' : ''}" data-act="when">${ic('schedule')}<span>${esc(whenText(r.plan))}</span>${ic(
        'unfold_more',
      )}</button></div>`
    : '';

  let body = '';
  if (m.kind === 'plan-none') {
    body = `<div class="rc-pad sub" style="font-size: 15px">${m.plan.mode === 'arrive' ? 'No trains arrive by then.' : 'No trains after then.'}</div>`;
  } else if (m.kind === 'plan') {
    const want = picked.get(r.id);
    const found = m.deps.findIndex((x) => pickKey(x) === want);
    const i = found >= 0 ? found : m.best;
    const d = m.deps[i];
    const label =
      m.plan.mode === 'arrive'
        ? i === m.best
          ? `Latest arriving by ${clock(m.plan.at)}`
          : 'Earlier train'
        : i === 0
          ? `First after ${clock(m.plan.at)}`
          : 'Later train';
    const note = hasChange(d) ? '' : trainNote(d.trip) ? ` · ${trainNote(d.trip)}` : '';
    const big = `<div class="big clock"><span class="n flow" data-flow-at="${d.dep}" data-flow-mode="clock" data-flow-key="${r.id}"><number-flow-group><number-flow class="a"></number-flow><span class="sep">:</span><number-flow class="b"></number-flow></number-flow-group><span class="now">Now</span></span><span class="u"></span></div>`;
    const chips = m.deps
      .map((x, k) => {
        const sel = k === i;
        return `<button class="tchip ${sel ? 'on' : ''}" data-act="pick" data-route="${r.id}" data-pick="${pickKey(x)}" aria-pressed="${sel}"><span class="t">${clock(
          x.dep,
        )}</span><span class="s">to ${clockTime(x.arr)}</span>${m.deps.some(hasChange) ? legBar(x) : ''}</button>`;
      })
      .join('');
    body = `
      <div class="rc-hero rc-pad">
        <div class="grow">
          <span class="label tint">${esc(label)}</span>
          ${big}
          <span class="line">Arrives ${clock(d.arr)} · ${span(d.arr - d.dep)}${note}</span>
          ${hasChange(d) ? legsLine(d) : ''}
        </div>
      </div>
      ${boardingTags(r, d)}
      <div class="tchips" data-show-on>${chips}</div>
      ${hasChange(d) ? timelineHtml(d) : ''}`;
  } else if (m.kind === 'none') {
    body = `<div class="rc-pad sub" style="font-size: 15px">No direct trains between these stations.</div>`;
  } else {
    const closed = m.kind === 'closed';
    const want = picked.get(r.id);
    const i = Math.max(0, m.deps.findIndex((d) => pickKey(d) === want));
    const d = m.deps[i];
    const lastIdx = closed ? -1 : m.deps.findIndex((x) => m.last && x.dep === m.last.dep);
    const isTomorrow = (k) => closed || (m.tomorrowFrom >= 0 && k >= m.tomorrowFrom);
    const tomorrow = isTomorrow(i);
    let label = i === 0 ? 'Next train' : 'Later train';
    let tone = 'tint';
    if (closed && i === 0) [label, tone] = [m.running ? 'No more trains tonight' : 'Metro is closed', 'warn'];
    else if (closed) [label, tone] = ['Tomorrow', 'sub'];
    else if (tomorrow) [label, tone] = [i === m.tomorrowFrom ? 'First train tomorrow' : 'Tomorrow', 'sub'];
    else if (i === lastIdx) [label, tone] = ['Last train', 'warn'];
    else if (m.late && lastIdx > i) [label, tone] = [`Last ${lastIdx - i + 1} trains`, 'warn'];
    const note = hasChange(d) ? ` · ${span(d.arr - d.dep)}` : trainNote(d.trip) ? ` · ${trainNote(d.trip)}` : '';
    const lineText =
      closed && i === 0
        ? `First train · arrives ${clock(d.arr)}`
        : tomorrow
          ? `Arrives ${clock(d.arr)}${note}`
          : `Leaves ${clock(d.dep)} · arrives ${clock(d.arr)}${note}`;
    // One rolling-digit hero for every state, so switching trains or modes animates digit by digit.
    const big = `<div class="big ${tomorrow ? 'clock' : ''}"><span class="n flow" data-flow-at="${d.dep}" data-flow-mode="${
      tomorrow ? 'clock' : 'count'
    }" data-flow-key="${r.id}"><number-flow-group><number-flow class="a"></number-flow><span class="sep">:</span><number-flow class="b"></number-flow></number-flow-group><span class="now">Now</span></span><span class="u"></span></div>`;
    const on = reminderOn(r, d.dep);
    const chips = m.deps
      .map((x, k) => {
        const sel = k === i;
        const tm = isTomorrow(k);
        const sub = closed && k === 0 ? 'first' : tm ? 'tomorrow' : `<span data-at="${x.dep}" data-fmt="chip">${FORMATS.chip(x.dep - m.now)}</span>`;
        return `<button class="tchip ${sel ? 'on' : ''} ${tm && !closed ? 'dim' : ''}" data-act="pick" data-route="${r.id}" data-pick="${pickKey(x)}" aria-pressed="${sel}"><span class="t">${clock(
          x.dep,
        )}</span><span class="s ${k === lastIdx ? 'warn' : ''}">${sub}</span>${m.deps.some(hasChange) ? legBar(x) : ''}</button>`;
      })
      .join('');
    body = `
      <div class="rc-hero rc-pad">
        <div class="grow">
          <span class="label ${tone}">${label}</span>
          ${big}
          <span class="line">${lineText}</span>${(() => {
            // Leaves sooner than a later train that gets there just as early: say which.
            const later = m.deps.slice(i + 1).find((x) => x.arr === d.arr);
            return later ? `<span class="line">Same arrival as ${clock(later.dep)}</span>` : '';
          })()}
          ${hasChange(d) ? legsLine(d) : ''}
        </div>
        <button class="bell ${on ? 'on' : ''}" data-act="remind" data-route="${r.id}" data-dep="${d.dep}" aria-pressed="${on}" aria-label="${
          on ? 'Stop countdown' : 'Start countdown'
        }">${ic('notifications', on ? 'fill' : '')}</button>
      </div>
      ${boardingTags(r, d)}
      <div class="tchips">${chips}</div>
      ${hasChange(d) ? timelineHtml(d) : ''}
      ${m.late && m.last ? `<div class="note rc-pad">${ic('bedtime')}<span>The ${clock(m.last.dep)} is the last train tonight.</span></div>` : ''}`;
  }

  return `
    <div class="card rc">
      ${head}
      ${when}
      ${body}
      ${
        isPreview && !prefs.routes.some((x) => x.from === r.from && x.to === r.to)
          ? `<div class="rc-pad"><button class="cta" data-act="save-preview">${ic('add')}Save this route</button></div>`
          : ''
      }
    </div>`;
}

/* ----- metro card top-up ----- */

// Airtel Payments Bank's own card top-up screen (free; can write the balance to the card).
// The short link redirects to myairtel://apb_cards?card=ncmc when Airtel Thanks is installed.
const AIRTEL_TOPUP = 'https://i.airtel.in/AIRBNK/transit-card';
const UPI_ID = /^[A-Za-z0-9._-]{3,}@[A-Za-z0-9]{2,}$/;
// The ID is validated to safe characters; '@' stays raw because some UPI apps reject %40.
/** The UPI ID from a card's "Scan to recharge" QR (upi://pay?pa=…), or the text itself if it already is one. */
function upiIdFromQr(raw) {
  const text = (raw || '').trim();
  const id = /^upi:/i.test(text) ? new URLSearchParams(text.split('?')[1] || '').get('pa') : text;
  return id && UPI_ID.test(id.trim()) ? id.trim() : null;
}

/** Reads the QR in a photo of the card. Needs the BarcodeDetector API (Chrome on Android has it). */
async function scanCardQr(file) {
  if (!('BarcodeDetector' in window)) {
    toast("QR scanning isn’t supported in this browser. Enter the ID instead.");
    return;
  }
  try {
    const codes = await new BarcodeDetector({ formats: ['qr_code'] }).detect(await createImageBitmap(file));
    const id = codes.map((c) => upiIdFromQr(c.rawValue)).find(Boolean);
    if (!id) {
      toast("No metro card top-up QR found in the photo");
      return;
    }
    save({ upi: id });
    const input = document.getElementById('upi-id');
    if (input) input.value = id;
    toast('UPI ID saved');
  } catch {
    toast("Couldn’t read the photo. Enter the ID instead.");
  }
}

const upiLink = (id) => `upi://pay?pa=${id}&pn=Airtel%20NCMC%20Topup&mc=7412&cu=INR`;

function openTopUp() {
  openSheet(
    `${sheetHead('Top up metro card', null, null)}
    <div class="sheet-body" style="flex: none">
      <div class="card list">
        <a class="row tap plain" href="${upiLink(prefs.upi)}" data-act="upi-paid" style="color: inherit; text-decoration: none">${ic('qr_code_2')}<span class="grow"><span class="t">Pay by UPI</span><span class="s">Any UPI app · No fee · Add to card in Airtel</span></span></a>
        <a class="row tap plain" href="${AIRTEL_TOPUP}" target="_blank" rel="noopener" style="color: inherit; text-decoration: none">${ic('credit_card')}<span class="grow"><span class="t">Airtel Thanks</span><span class="s">No fee · Adds the balance to the card</span></span></a>
      </div>
    </div>`,
  );
  sheetRoot.querySelector('.sheet').classList.add('short');
}

/* ----- map tab ----- */

const MAP_ZOOMS = [1, 1.6, 2.4, 3.2];
let mapZoom = 0;
const lineColor = { blue: 'var(--blue)', green: 'var(--green)', yellow: 'var(--yellow)' };

function mapLabel(text, x, y, side) {
  const t = esc(text);
  if (side === 'l') return `<text x="${x - 18}" y="${y + 7}" text-anchor="end">${t}</text>`;
  if (side === 'd') return `<text x="${x + 10}" y="${y + 30}">${t}</text>`;
  if (side === 'a') return `<text x="${x + 10}" y="${y - 16}">${t}</text>`;
  if (side === 'A') return `<text x="${x - 10}" y="${y - 16}" text-anchor="end">${t}</text>`;
  if (side === 'D') return `<text x="${x - 10}" y="${y + 30}" text-anchor="end">${t}</text>`;
  return `<text x="${x + 18}" y="${y + 7}">${t}</text>`;
}

function mapScreen() {
  const { w, h } = MAP_SIZE;
  const water = MAP_FEATURES.map(([k, pts]) => {
    const d = pts.map(([x, y]) => `${x},${y}`).join(' ');
    return k === 'sea' || k === 'lake' ? `<polygon class="w-${k}" points="${d}"/>` : `<polyline class="w-${k}" points="${d}"/>`;
  }).join('');
  const lines = Object.entries(MAP_LINES)
    .map(
      ([l, ids]) =>
        `<polyline points="${ids.map((id) => MAP_STATIONS[id].slice(0, 2).join(',')).join(' ')}" fill="none" stroke="${lineColor[l]}" stroke-width="12" stroke-linecap="round" stroke-linejoin="round"/>`,
    )
    .join('');
  const stations = Object.entries(MAP_STATIONS)
    .map(([id, [x, y, side]]) => {
      const st = STATIONS[id];
      const dot =
        st.lines.length > 1
          ? `<circle cx="${x}" cy="${y}" r="13" fill="var(--card)" stroke="var(--text)" stroke-width="5"/>`
          : `<circle cx="${x}" cy="${y}" r="8" fill="var(--card)" stroke="${lineColor[st.lines[0]]}" stroke-width="5"/>`;
      return `<g class="mst" data-act="station" data-id="${id}" role="button" aria-label="${esc(name(id))}"><circle cx="${x}" cy="${y}" r="28" fill="transparent"/>${dot}${mapLabel(name(id), x, y, side)}</g>`;
    })
    .join('');
  return `
    ${topbar(`<button class="round" data-act="settings" aria-label="Settings">${ic('more_horiz')}</button>`)}
    <div class="title"><h1>Map</h1></div>
    <div class="card map-card">
      <div class="map-wrap" id="map-wrap">
        <svg class="metro-map" viewBox="0 0 ${w} ${h}" style="width: ${MAP_ZOOMS[mapZoom] * 100}%" role="img" aria-label="Chennai Metro network map">${water}${lines}${stations}</svg>
      </div>
      <span class="map-zoom glass-group glass">
        <button data-act="zoom" data-v="-1" aria-label="Zoom out" ${mapZoom === 0 ? 'disabled' : ''}>${ic('remove')}</button>
        <button data-act="zoom" data-v="1" aria-label="Zoom in" ${mapZoom === MAP_ZOOMS.length - 1 ? 'disabled' : ''}>${ic('add')}</button>
      </span>
    </div>
    <p class="footnote">Tap a station for departures. Map data © OpenStreetMap contributors.</p>`;
}

function zoomMap(step) {
  const wrap = document.getElementById('map-wrap');
  const before = MAP_ZOOMS[mapZoom];
  mapZoom = Math.max(0, Math.min(MAP_ZOOMS.length - 1, mapZoom + step));
  const k = MAP_ZOOMS[mapZoom] / before;
  // Keep the middle of the view where it was.
  const cx = wrap ? wrap.scrollLeft + wrap.clientWidth / 2 : 0;
  const cy = wrap ? wrap.scrollTop + wrap.clientHeight / 2 : 0;
  render({ force: true });
  const next = document.getElementById('map-wrap');
  if (next) {
    next.scrollLeft = cx * k - next.clientWidth / 2;
    next.scrollTop = cy * k - next.clientHeight / 2;
  }
}

/** Next trains each way from a station, with the platform for each direction. */
function openStationSheet(id) {
  const now = clockNow();
  const rows = directDestinations(id)
    .map((g) => {
      const deps = g.terminals
        .flatMap((t) => schedule.routeDepartures(id, t, now, 4).map((d) => ({ ...d, to: t })))
        .sort((a, b) => a.dep - b.dep)
        .filter((d, k, all) => k === 0 || d.dep !== all[k - 1].dep)
        .slice(0, 4);
      const p = platformAt(id, g);
      const many = g.terminals.length > 1;
      const chips = deps
        .map(
          (d) =>
            `<span class="tchip"><span class="t">${clock(d.dep)}</span><span class="s">${
              many ? esc(name(d.to)) : `<span data-at="${d.dep}" data-fmt="chip">${FORMATS.chip(d.dep - now)}</span>`
            }</span></span>`,
        )
        .join('');
      return `
        <div class="card set">
          <span class="dir"><i class="dot ${g.line}"></i>Towards ${esc(g.terminals.map(name).join(' or '))}${p ? ` · ${esc(p)}` : ''}</span>
          ${deps.length ? `<div class="st-deps">${chips}</div>` : '<span class="sub" style="font-size: 15px">No more trains today</span>'}
        </div>`;
    })
    .join('');
  openSheet(
    `${sheetHead(name(id), STATIONS[id].lines.map(lineName).join(' · '), null)}
    <div class="sheet-body" style="flex: none; display: flex; flex-direction: column; gap: 12px">
      ${rows}
      <button class="cta" data-act="route-from" data-id="${id}">${ic('add')}Save a route from here</button>
    </div>`,
  );
  sheetRoot.querySelector('.sheet').classList.add('short');
}

/* ----- lines tab ----- */

function linesScreen() {
  const today = schedule.today(clockNow());
  const day = TIMETABLE[today.type];
  const d = new Date(clockNow());
  const nowMin = d.getHours() * 60 + d.getMinutes();
  const card = (routeId, title, line, extra = '') => {
    const spec = day[routeId];
    const r = ROUTES[routeId];
    const a = r.fwd[0][0];
    const b = r.fwd.at(-1)[0];
    const open = nowMin >= Math.min(hm(spec.first.fwd), hm(spec.first.rev)) && nowMin <= Math.max(hm(spec.last.fwd), hm(spec.last.rev));
    const current = open ? bandAt(spec.bands, nowMin) : null;
    return `
      <div class="card tt">
        <div class="tt-head"><span class="bar ${line}"></span><span class="grow"><span class="t">${title}</span><span class="s">${esc(name(a))} to ${esc(name(b))}${extra}</span></span></div>
        <div class="tt-grid">
          <span class="h">From</span><span class="h r">First</span><span class="h r">Last</span>
          <span>${esc(name(a))}</span><span class="r">${hm12(spec.first.fwd)}</span><span class="r">${hm12(spec.last.fwd)}</span>
          <span>${esc(name(b))}</span><span class="r">${hm12(spec.first.rev)}</span><span class="r">${hm12(spec.last.rev)}</span>
        </div>
        <div class="bands">${spec.bands
          .map(
            (bnd) =>
              `<div class="band ${bnd === current ? 'now' : ''}"><span>${hm12(bnd[0])} to ${bnd[1] === '24:00' ? 'close' : hm12(bnd[1])}${
                bnd[3] === 'peak' ? ', peak' : ''
              }</span><span>every ${bnd[2]} min</span></div>`,
          )
          .join('')}</div>
      </div>`;
  };
  const reason = today.reason !== today.label && today.reason.toLowerCase() !== today.type ? `, ${esc(today.reason)}` : '';
  return `
    ${topbar(`<button class="round" data-act="settings" aria-label="Settings">${ic('more_horiz')}</button>`)}
    <div class="title"><h1>Lines</h1><span class="sub">${esc(today.label)} timetable${reason}</span></div>
    ${card('blue', 'Blue Line', 'blue')}
    ${card('green', 'Green Line', 'green')}
    ${card('inter', 'Inter-corridor', 'inter', ' via CMBT')}
    ${card('yellow', 'Yellow Line', 'yellow')}
    <p class="footnote" style="margin-top: 0">${
      day.loop ? 'On weekday peaks, short-loop trains run every 3 min between Washermenpet and Alandur. ' : ''
    }Source: <a href="${TIMETABLE_SOURCE.url}" target="_blank" rel="noopener">CMRL timetable</a>, ${TIMETABLE_SOURCE.published}.</p>`;
}

/* ----- settings ----- */

function settingsScreen() {
  const now = clockNow();
  const key = serviceDateKey(now);
  const auto = schedule.today(now);
  const override = prefs.override?.date === key ? prefs.override.type : 'auto';
  const autoName = { weekday: 'Weekday', saturday: 'Saturday', sunday: 'Holiday' }[auto.type];
  const select = (act, label, value, options) => `
    <span class="select-wrap"><select data-act="${act}" aria-label="${label}">${options
      .map(([v, l]) => `<option value="${v}" ${v === value ? 'selected' : ''}>${l}</option>`)
      .join('')}</select>${ic('unfold_more')}</span>`;
  const seg = (act, value, options) => `
    <div class="seg" role="radiogroup">${options
      .map(([v, l]) => `<button role="radio" aria-checked="${v === value}" data-act="${act}" data-v="${v}">${l}</button>`)
      .join('')}</div>`;
  return `
    ${topbar(`<button class="round tinted" data-act="back" aria-label="Done">${ic('check')}</button>`)}
    <div class="title"><h1>Settings</h1></div>

    <div class="card list">
      <div class="row">
        <span class="grow"><span class="t">Today's timetable</span></span>
        ${select('override', "Today's timetable", override, [
          ['auto', `Auto, ${autoName}`],
          ['weekday', 'Weekday'],
          ['saturday', 'Saturday'],
          ['sunday', 'Sunday or holiday'],
        ])}
      </div>
    </div>
    <p class="footnote">Auto follows the calendar and public holidays (today: ${esc(auto.reason)}). Choose Sunday or holiday when CMRL runs a Sunday timetable.</p>

    <div class="card set">
      <span>Departures per route</span>
      ${seg('count', String(prefs.count), COUNT_CHOICES.map((n) => [String(n), String(n)]))}
      <div class="set-row">
        <span class="grow"><span class="t">Last-train warning</span><span class="s">Shown in the final hour of service</span></span>
        <button class="switch" role="switch" aria-checked="${prefs.lastWarn}" data-act="last-warn" aria-label="Last-train warning"><i></i></button>
      </div>
    </div>

    <div class="card set">
      <span>Appearance</span>
      ${seg('theme', prefs.theme, [['light', 'Light'], ['dark', 'Dark'], ['auto', 'System']])}
    </div>

    <div class="card set">
      <span>Mock time</span>
      ${seg('mock', prefs.mock ? String(prefs.mock.at) : 'off', [['off', 'Off'], ...MOCK_CHOICES.map(([m, l]) => [String(m), l])])}
    </div>
    <p class="footnote">For testing. Runs the app from the chosen time.</p>

    <div class="card list">
      <div class="row" style="flex-direction: column; align-items: stretch; gap: 8px">
        <label for="upi-id" class="t">Metro card UPI ID</label>
        <div class="upi-row">
          <input class="text-input" id="upi-id" type="text" autocomplete="off" autocapitalize="off" spellcheck="false" placeholder="ncmc.1234…@mairtel" value="${esc(prefs.upi)}">
          <label class="cta small scan">Scan QR<input id="upi-scan" type="file" accept="image/*" capture="environment" hidden></label>
        </div>
      </div>
    </div>
    <p class="footnote">From the “Scan to recharge” QR on the card. Enables top-up by UPI.</p>

    <div class="card list">
      <a class="row tap" href="${TIMETABLE_SOURCE.url}" target="_blank" rel="noopener" style="color: inherit; text-decoration: none">
        <span class="grow"><span class="t">Official timetable</span></span>
        <span class="v light">${TIMETABLE_SOURCE.published}</span>
        ${ic('chevron_right', 'chev')}
      </a>
      <div class="row" style="flex-direction: column; align-items: stretch; gap: 8px; --inset: 16px">
        <label for="feed-url" class="t">Advisory feed</label>
        <input class="text-input" id="feed-url" type="url" inputmode="url" placeholder="https://…/advisories.json" value="${esc(prefs.feedUrl)}">
      </div>
    </div>
    <p class="footnote">First and last trains are from CMRL’s timetable. Times at other stations are estimates (±2 min).${
      feedState.status === 'live' ? ' Advisory feed connected.' : ''
    }</p>`;
}

/* ---------- dock ---------- */

let dockMode = '';

function renderDock(screen) {
  const mode = screen === 'settings' ? 'none' : 'tabs';
  if (mode !== dockMode) {
    dockMode = mode;
    dock.innerHTML =
      mode === 'none'
        ? ''
        : `
      <nav class="tabs glass" aria-label="Sections">
        <span class="tab-pill"></span>
        <a class="tab" href="#routes" data-tab="routes">${ic('commute')}Routes</a>
        <a class="tab" href="#lines" data-tab="lines">${ic('schedule')}Lines</a>
        <a class="tab" href="#map" data-tab="map">${ic('map')}Map</a>
      </nav>
      <button class="orb glass" data-act="look" aria-label="Check any station">${ic('search')}</button>`;
  }
  if (mode === 'tabs') {
    const i = { routes: 0, lines: 1, map: 2 }[nav.tab] ?? 0;
    dock.querySelector('.tab-pill').style.transform = `translateX(${i * 100}%)`;
    dock.querySelectorAll('.tab').forEach((t) => {
      if (t.dataset.tab === nav.tab) t.setAttribute('aria-current', 'page');
      else t.removeAttribute('aria-current');
    });
  }
}

/* ---------- navigation + render ---------- */

const nav = { tab: 'routes', stack: [] };
const screenNow = () => nav.stack.at(-1) ?? nav.tab;
let preview = null;
let nextChange = Infinity;
let lastKey = '';
let lastRender = 0;

function build(now) {
  const screen = screenNow();
  if (screen === 'routes') {
    // Re-render when a train leaves or stops being catchable.
    // Each route's model is built once per render, and reused for the cards.
    const models = new Map([...prefs.routes, ...(preview ? [preview] : [])].map((r) => [r, routeModel(r, now)]));
    nextChange = Infinity;
    for (const m of models.values()) if (m.next) nextChange = Math.min(nextChange, m.next.dep);
    return routesScreen(now, models);
  }
  nextChange = Infinity;
  if (screen === 'lines') return linesScreen();
  if (screen === 'map') return mapScreen();
  if (screen === 'settings') return settingsScreen();
  return '';
}

// Live countdown text is excluded from the diff, so a ticking clock never rebuilds the DOM.
const stableKey = (html) => html.replace(/(data-at="\d+" data-fmt="\w+">)[^<]*/g, '$1');

function render({ force = false, animate = false } = {}) {
  const now = clockNow();
  const html = build(now);
  const key = `${screenNow()}|${stableKey(html)}`;
  lastRender = now;
  renderDock(screenNow());
  if (!force && key === lastKey) return;
  lastKey = key;
  const focused = document.activeElement?.id;
  view.innerHTML = html;
  // Planned cards may pick a chip off to the right (arrive by: the last one); bring it into view.
  for (const row of view.querySelectorAll('.tchips[data-show-on]')) {
    const on = row.querySelector('.tchip.on');
    if (on) row.scrollLeft = Math.max(0, on.offsetLeft - row.offsetLeft - row.clientWidth / 2 + on.offsetWidth / 2);
  }
  paintFlows(now);
  view.classList.toggle('enter', animate);
  if (focused) document.getElementById(focused)?.focus();
}

function transition(dir, fn) {
  const run = () => {
    fn();
    render({ force: true, animate: !document.startViewTransition || reduceMotion.matches });
    if (dir !== 'tab') window.scrollTo(0, 0);
  };
  if (document.startViewTransition && !reduceMotion.matches) {
    document.documentElement.dataset.nav = dir;
    document.startViewTransition(run);
  } else run();
}

function push(screen) {
  history.pushState({ screen }, '');
  transition('push', () => nav.stack.push(screen));
}

function back() {
  if (sheetOpen || nav.stack.length) history.back();
}

window.addEventListener('popstate', () => {
  if (sheetOpen) {
    closeSheet();
    return;
  }
  if (nav.stack.length) transition('pop', () => nav.stack.pop());
});

function switchTab(tab) {
  if (nav.tab === tab && !nav.stack.length) {
    window.scrollTo({ top: 0, behavior: reduceMotion.matches ? 'auto' : 'smooth' });
    return;
  }
  const depth = nav.stack.length;
  transition('tab', () => {
    nav.tab = tab;
    nav.stack = [];
  });
  if (depth) history.go(-depth);
}

/* ---------- clock ---------- */

function tick() {
  const now = clockNow();
  if (applyFlips(now)) {
    const r = menuPair && prefs.routes.find((x) => pairKey(x) === menuPair);
    if (r) refreshRouteMenu(r.id);
    render({ force: true, animate: true });
  } else if (now - lastRender > 15_000 || now >= nextChange) render();
  for (const el of view.querySelectorAll('[data-at]')) {
    const text = FORMATS[el.dataset.fmt](Number(el.dataset.at) - now);
    if (el.textContent === text) continue;
    el.textContent = text;
    if (el.dataset.fmt === 'big' && !reduceMotion.matches) {
      el.classList.remove('roll');
      void el.offsetWidth;
      el.classList.add('roll');
    }
  }
  paintFlows(now);
  checkReminder(now);
}

/* ---------- rolling digits (number-flow) ---------- */

let flowReady = false;
// Loaded from a CDN; if it can't load, the same elements just show plain text.
Promise.all([import('https://esm.sh/number-flow'), import('https://esm.sh/number-flow/group')])
  .then(() => {
    flowReady = true;
    paintFlows(clockNow());
  })
  .catch(() => {});

/** Hero digits: { a, b (two digits) or null, unit }, or null for "Now". */
function flowParts(mode, at, now) {
  if (mode === 'clock') {
    const d = new Date(at);
    return { a: d.getHours() % 12 || 12, b: d.getMinutes(), unit: ampm(at) };
  }
  const ms = at - now;
  if (ms < 30_000) return null;
  if (ms <= 2 * MIN) {
    const s = Math.ceil(ms / 1000);
    return { a: Math.floor(s / 60), b: s % 60, unit: 'min' };
  }
  const m = mins(ms);
  return m < 60 ? { a: m, b: null, unit: 'min' } : { a: Math.floor(m / 60), b: m % 60, unit: 'h' };
}

const byChange = (old, value) => Math.sign(value - old);

/** Ticks always roll down (we count down); picking another train rolls the way the value moved. */
function setFlow(el, value, twoDigits, trend = -1) {
  if (flowReady && el.update) {
    if (!el.dataset.ready) {
      el.dataset.ready = '1';
      if (twoDigits) {
        el.format = { minimumIntegerDigits: 2 };
        el.digits = { 1: { max: 5 } }; // 00 -> 59
      }
    }
    el.trend = trend;
    el.update(value);
  } else {
    el.textContent = twoDigits ? String(value).padStart(2, '0') : String(value);
  }
}

// What each route's hero last showed, so a re-render can roll from it instead of jumping.
const lastFlow = new Map();

function paintFlows(now) {
  for (const box of view.querySelectorAll('[data-flow-at]')) {
    const p = flowParts(box.dataset.flowMode, Number(box.dataset.flowAt), now);
    box.classList.toggle('is-now', !p);
    const unit = box.nextElementSibling;
    if (unit && unit.textContent !== (p ? p.unit : '')) unit.textContent = p ? p.unit : '';
    if (!p) continue;
    box.classList.toggle('pair', p.b !== null);
    const A = box.querySelector('.a');
    const B = box.querySelector('.b');
    const key = box.dataset.flowKey;
    const prev = lastFlow.get(key);
    const fresh = !box.dataset.painted;
    box.dataset.painted = '1';
    lastFlow.set(key, p);
    if (fresh && prev && flowReady) {
      // A new hero (another train picked, or the shown one left): start from the old digits.
      setFlow(A, prev.a, false);
      setFlow(B, prev.b ?? p.b ?? 0, true);
      requestAnimationFrame(() => {
        setFlow(A, p.a, false, byChange);
        if (p.b !== null) setFlow(B, p.b, true, byChange);
      });
      continue;
    }
    setFlow(A, p.a, false, box.dataset.flowMode === 'clock' ? byChange : -1);
    if (p.b !== null) setFlow(B, p.b, true, box.dataset.flowMode === 'clock' ? byChange : -1);
  }
}

function startClock() {
  tick();
  setTimeout(() => {
    tick();
    setInterval(tick, 1000);
  }, 1000 - (clockNow() % 1000));
}

/* ---------- reminders ---------- */

const buzz = (p = 10) => {
  try {
    navigator.vibrate?.(p);
  } catch {}
};

let toastTimer;
function toast(msg) {
  toastEl.textContent = msg;
  toastEl.classList.add('show');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => toastEl.classList.remove('show'), 3200);
}

async function notify(title, body) {
  buzz([90, 60, 90]);
  toast(`${title}. ${body}`);
  if (!('Notification' in window) || Notification.permission !== 'granted') return;
  try {
    const reg = await navigator.serviceWorker?.getRegistration();
    if (reg) reg.showNotification(title, { body, tag: 'headway', icon: 'icons/icon-192.png' });
    else new Notification(title, { body });
  } catch {}
}

function checkReminder(now) {
  const rem = prefs.reminder;
  const r = rem && !rem.fired ? prefs.routes.find((x) => x.id === rem.routeId) : null;
  document.title = r ? `${FORMATS.chip(rem.dep - now)} · the ${clock(rem.dep)} from ${name(r.from)}` : 'Headway';
  if (!rem || rem.fired || now < rem.dep) return;
  save({ reminder: { ...rem, fired: true } });
  document.title = 'Headway';
  notify(`The ${clock(rem.dep)} is leaving`, r ? `${name(r.from)} to ${name(r.to)}.` : '');
  render({ force: true });
}

async function toggleReminder(r, dep) {
  if (reminderOn(r, dep)) {
    save({ reminder: null });
    document.title = 'Headway';
    toast('Countdown off');
    render({ force: true });
    return;
  }
  if ('Notification' in window && Notification.permission === 'default') {
    try {
      await Notification.requestPermission();
    } catch {}
  }
  save({ reminder: { routeId: r.id, dep, at: dep, fired: false } });
  toast(`Countdown on · ${clock(dep)}`);
  render({ force: true });
}

/* ---------- route picker sheet ---------- */

let sheetOpen = false;

function openSheet(html, onMount) {
  sheetRoot.innerHTML = `<div class="scrim"></div><div class="sheet" role="dialog" aria-modal="true">${html}</div>`;
  const scrim = sheetRoot.querySelector('.scrim');
  const sheet = sheetRoot.querySelector('.sheet');
  if (!sheetOpen) history.pushState({ sheet: true }, '');
  sheetOpen = true;
  requestAnimationFrame(() => {
    scrim.classList.add('open');
    sheet.classList.add('open');
  });
  scrim.addEventListener('click', () => history.back());
  dragToDismiss(sheet);
  onMount?.(sheet);
}

/** Swaps the sheet's contents in place, for the station → destination step. */
function replaceSheet(html, onMount) {
  const sheet = sheetRoot.querySelector('.sheet');
  if (!sheet) return openSheet(html, onMount);
  sheet.innerHTML = html;
  dragToDismiss(sheet);
  onMount?.(sheet);
}

function sheetHead(title, sub, backAct) {
  return `
    <div class="grab"><i></i></div>
    <div class="sheet-head">
      ${
        backAct
          ? `<button class="round" data-act="${backAct}" aria-label="Back">${ic('chevron_left')}</button>`
          : `<button class="round" data-act="close-sheet" aria-label="Close">${ic('close')}</button>`
      }
      <div class="mid"><span class="t">${esc(title)}</span>${sub ? `<span class="s">${esc(sub)}</span>` : ''}</div>
      <span></span>
    </div>`;
}

let pickMode = 'save';

function openStationStep(mode, initial = false) {
  pickMode = mode;
  const html = `
    ${sheetHead(mode === 'look' ? 'Search' : 'Choose starting station', null, null)}
    <label class="search">${ic('search')}<input type="search" placeholder="Search stations" autocomplete="off" enterkeyhint="search" aria-label="Search stations"></label>
    <div class="sheet-body">
      ${PICKER_GROUPS.map(
        (g) => `
        <div class="group" style="display: flex; flex-direction: column; gap: 14px">
          <span class="section-title">${LINES[g.line].name}</span>
          <div class="card list">${g.stations
            .map((id) => {
              const st = STATIONS[id];
              const tags = st.lines
                .filter((l) => l !== g.line)
                .map((l) => `<span class="pill-tag ${l}">${LINES[l].name}</span>`)
                .join('');
              return `<button class="row tap plain" data-board="${id}" data-search="${esc(`${st.name} ${st.long ?? ''}`.toLowerCase())}">
                <span class="grow"><span class="t">${esc(st.name)}</span>${st.long ? `<span class="s">${esc(st.long)}</span>` : ''}</span>${tags}</button>`;
            })
            .join('')}</div>
        </div>`,
      ).join('')}
    </div>`;
  if (initial) openSheet(html, mountSearch);
  else replaceSheet(html, mountSearch);
}

/** Filters a picker's rows (data-search) as you type, hiding groups and sections left empty. */
function mountSearch(sheet) {
  const input = sheet.querySelector('.search input');
  const body = sheet.querySelector('.sheet-body');
  if (!input || !body) return;
  input.addEventListener('input', () => {
    const q = input.value.trim().toLowerCase();
    body.querySelectorAll('.group').forEach((g) => {
      let any = false;
      g.querySelectorAll('[data-search]').forEach((row) => {
        const hit = !q || row.dataset.search.includes(q);
        row.hidden = !hit;
        any ||= hit;
      });
      g.hidden = !any;
    });
    body.querySelectorAll('.pick-section').forEach((sec) => {
      sec.hidden = ![...sec.querySelectorAll('.group')].some((g) => !g.hidden);
    });
  });
}

function openDestinationStep(from) {
  const groups = directDestinations(from);
  const changes = transferDestinations(from);
  const searchOf = (st) => esc(`${st.name} ${st.long ?? ''}`.toLowerCase());
  const tagsOf = (st, line) =>
    st.lines
      .filter((l) => l !== line)
      .map((l) => `<span class="pill-tag ${l}">${LINES[l].name}</span>`)
      .join('');
  const direct = groups
    .map(
      (g) => `
        <div class="group" style="display: flex; flex-direction: column; gap: 8px">
          <div class="dir-head"><span class="dot ${g.line}"></span><span class="t">Towards ${esc(g.terminals.map(name).join(' or '))}</span></div>
          <div class="card list">${g.stations
            .map((x) => {
              const st = STATIONS[x.id];
              return `<button class="row tap plain" data-dest="${x.id}" data-from="${from}" data-search="${searchOf(st)}"><span class="grow"><span class="t">${esc(
                st.name,
              )}</span></span>${tagsOf(st, g.line)}<span class="v light">${x.minutes} min</span></button>`;
            })
            .join('')}</div>
        </div>`,
    )
    .join('');
  // Everything else, by line, with where you'd change.
  const shown = new Set();
  const viaText = (c) => `<span class="via">${ic('transfer_within_a_station')}Change at ${c.via.map(name).map(esc).join(', then ')}</span>`;
  const others = PICKER_GROUPS.map((g) => {
    const rows = g.stations
      .filter((id) => changes.has(id) && !shown.has(id) && shown.add(id))
      .map((id) => {
        const st = STATIONS[id];
        return `<button class="row tap plain" data-dest="${id}" data-from="${from}" data-search="${searchOf(st)}"><span class="grow"><span class="t">${esc(
          st.name,
        )}</span>${viaText(changes.get(id))}</span>${tagsOf(st, g.line)}</button>`;
      })
      .join('');
    return rows
      ? `<div class="group" style="display: flex; flex-direction: column; gap: 8px"><div class="dir-head"><span class="dot ${g.line}"></span><span class="t">${LINES[g.line].name}</span></div><div class="card list">${rows}</div></div>`
      : '';
  }).join('');
  const html = `
    ${sheetHead('Choose destination', `From ${name(from)}`, 'pick-back')}
    <label class="search">${ic('search')}<input type="search" placeholder="Search stations" autocomplete="off" enterkeyhint="search" aria-label="Search stations"></label>
    <div class="sheet-body">
      <div class="pick-section" style="display: flex; flex-direction: column; gap: 20px">${direct}</div>
      ${
        others
          ? `<div class="pick-section" style="display: flex; flex-direction: column; gap: 14px"><span class="section-title">With a change</span>${others}</div>`
          : ''
      }
    </div>`;
  replaceSheet(html, mountSearch);
}

sheetRoot.addEventListener('click', (e) => {
  const board = e.target.closest('[data-board]');
  if (board) {
    buzz();
    openDestinationStep(board.dataset.board);
    return;
  }
  const dest = e.target.closest('[data-dest]');
  if (dest) {
    buzz();
    const from = dest.dataset.from;
    const to = dest.dataset.dest;
    history.back();
    if (pickMode === 'look') {
      preview = { id: 'preview', from, to };
      toast(`${name(from)} to ${name(to)}`);
    } else addRoute(from, to);
    if (nav.tab !== 'routes' || nav.stack.length) switchTab('routes');
    else render({ force: true, animate: true });
  }
});

function addRoute(from, to) {
  const id = routeId(from, to);
  if (!prefs.routes.some((r) => r.id === id)) save({ routes: [{ id, from, to }, ...prefs.routes] });
  toast(`Saved ${name(from)} → ${name(to)}`);
}

// Station pair of the route whose menu is open, so a flip at a change-over can redraw it.
let menuPair = null;

function openRouteMenu(id) {
  const r = prefs.routes.find((x) => x.id === id);
  if (!r) return;
  menuPair = pairKey(r);
  openSheet(routeMenuHtml(r));
  sheetRoot.querySelector('.sheet').classList.add('short', 'fit');
}

/** Redraws the open route menu in place (no slide-in), keeping its scroll position. */
function refreshRouteMenu(id) {
  const r = prefs.routes.find((x) => x.id === id);
  const body = sheetRoot.querySelector('.sheet-body');
  if (!r || !sheetOpen || !body) return;
  const top = body.scrollTop;
  menuPair = pairKey(r);
  replaceSheet(routeMenuHtml(r));
  const nb = sheetRoot.querySelector('.sheet-body');
  if (nb) nb.scrollTop = top;
}

function routeMenuHtml(r) {
  const id = r.id;
  return (
    `${sheetHead(`${name(r.from)} → ${name(r.to)}`, null, null)}
    <div class="sheet-body" style="flex: 0 1 auto">
      <div class="card set">
        <span>Where to board</span>
        <div class="seg" role="radiogroup" aria-label="Where to board">${[['', 'Off'], ['front', 'Front'], ['middle', 'Middle'], ['rear', 'Rear']]
          .map(([v, l]) => `<button role="radio" aria-checked="${(prefs.coaches?.[id] ?? '') === v}" data-act="coach" data-route="${id}" data-v="${v}">${l}</button>`)
          .join('')}</div>
        <span class="sub" style="font-size: 13px; line-height: 18px">Board the coach nearest your exit. Shown on the card.</span>
      </div>
      ${delayCard(r)}
      ${flipCard(r)}
      <div class="card list">
        <button class="row tap plain" data-act="plan-route" data-route="${id}">${ic('schedule')}<span class="grow"><span class="t">Plan a trip</span><span class="s">Depart at or arrive by a set time</span></span></button>
        ${prefs.routes[0]?.id === id ? '' : `<button class="row tap plain" data-act="top-route" data-route="${id}">${ic('vertical_align_top')}<span class="grow"><span class="t">Move to top</span></span></button>`}
        <button class="row tap plain" data-act="remove-route" data-route="${id}" style="color: #d70015">${ic('delete')}<span class="grow"><span class="t">Remove route</span></span></button>
      </div>
    </div>`
  );
}

const delayTone = (d) => (d > 0 ? 'late' : d < 0 ? 'early' : 'zero');

/** The next train on `r` at delay `d`: its timetabled time and the time the card will show. */
function delayPreview(r, d) {
  const now = clockNow();
  const x = schedule.routeDepartures(r.from, r.to, now - d * MIN, 1)[0];
  return x ? { timetabled: x.dep, shown: x.dep + d * MIN } : null;
}

function delayPreviewHtml(r, d) {
  const p = delayPreview(r, d);
  if (!p) return '<span class="sub">No upcoming trains</span>';
  if (!d) return `<span class="dl-cell"><span class="k">Next train</span><span class="v">${clock(p.shown)}</span></span><span class="dl-note">On schedule</span>`;
  return `<span class="dl-cell"><span class="k">Scheduled</span><span class="v was">${clock(p.timetabled)}</span></span>${ic(
    'arrow_forward',
    'dl-arrow',
  )}<span class="dl-cell"><span class="k">Expected</span><span class="v now">${clock(p.shown)}</span></span>`;
}

/** Route-menu card: how early or late this route's trains run, from DELAY_MAX early to DELAY_MAX late. */
function delayCard(r) {
  const d = delayOf(r.id);
  // A notch a minute; on time is tallest, and every 5 min a little taller to count by.
  const ticks = Array.from({ length: DELAY_MAX * 2 + 1 }, (_, k) => {
    const cls = k === DELAY_MAX ? 'mid' : (k - DELAY_MAX) % 5 === 0 ? 'five' : '';
    return `<i class="${cls}" style="--k:${k}"></i>`;
  }).join('');
  return `
      <div class="card dl" data-delay-card="${esc(r.id)}" data-tone="${delayTone(d)}" style="--v:${d}; --max:${DELAY_MAX}">
        <div class="dl-head">
          <span class="dl-icon">${ic('schedule')}</span>
          <span class="grow"><span class="t">Delay</span><span class="s">Applies to every train on this route</span></span>
        </div>
        <div class="dl-read" aria-hidden="true"><span class="dl-num">${d ? Math.abs(d) : ''}</span><span class="dl-unit">${d ? `min ${d > 0 ? 'late' : 'early'}` : 'On time'}</span></div>
        <div class="dl-ctrl">
          <button class="dl-step" data-act="delay-step" data-route="${esc(r.id)}" data-d="-1" aria-label="1 min earlier" ${d <= -DELAY_MAX ? 'disabled' : ''}>${ic('remove')}</button>
          <div class="dl-slider">
            <div class="dl-track" aria-hidden="true"><b class="dl-fill"></b>${ticks}</div>
            <input type="range" data-act="delay" data-route="${esc(r.id)}" min="${-DELAY_MAX}" max="${DELAY_MAX}" step="1" value="${d}" aria-label="Delay in minutes" aria-valuetext="${delayText(d)}" />
          </div>
          <button class="dl-step" data-act="delay-step" data-route="${esc(r.id)}" data-d="1" aria-label="1 min later" ${d >= DELAY_MAX ? 'disabled' : ''}>${ic('add')}</button>
        </div>
        <div class="dl-scale"><span>Early</span><button data-act="delay-reset" data-route="${esc(r.id)}" ${d ? '' : 'disabled'}>On time</button><span>Late</span></div>
        <div class="dl-preview">${delayPreviewHtml(r, d)}</div>
      </div>`;
}

/** Redraws the delay card in place, so the slider under the finger is never replaced. */
function paintDelayCard(id) {
  const card = sheetRoot.querySelector(`[data-delay-card="${CSS.escape(id)}"]`);
  const r = prefs.routes.find((x) => x.id === id);
  if (!card || !r) return;
  const d = delayOf(id);
  card.dataset.tone = delayTone(d);
  card.style.setProperty('--v', d);
  card.querySelector('.dl-num').textContent = d ? Math.abs(d) : '';
  card.querySelector('.dl-unit').textContent = d ? `min ${d > 0 ? 'late' : 'early'}` : 'On time';
  const input = card.querySelector('input');
  if (Number(input.value) !== d) input.value = d;
  input.setAttribute('aria-valuetext', delayText(d));
  const [less, more] = card.querySelectorAll('.dl-step');
  less.disabled = d <= -DELAY_MAX;
  more.disabled = d >= DELAY_MAX;
  card.querySelector('.dl-scale button').disabled = !d;
  card.querySelector('.dl-preview').innerHTML = delayPreviewHtml(r, d);
}

/** Saves a route's delay, keeping an active countdown on the same train it was set for. */
function setDelay(id, d) {
  if (d === delayOf(id)) return;
  keepingTrains(id, () => {
    const delays = { ...(prefs.delays ?? {}) };
    if (d) delays[id] = d;
    else delete delays[id];
    return { delays };
  });
  paintDelayCard(id);
  render({ force: true });
}

/** Sets (or with null, clears) one train's own delay, by its timetabled departure. */
function setTrainDelay(id, timetabled, d) {
  keepingTrains(id, () => {
    const all = { ...(prefs.trainDelays ?? {}) };
    const mine = { ...(all[id] ?? {}) };
    if (d == null) delete mine[timeKey(timetabled)];
    else mine[timeKey(timetabled)] = d;
    if (Object.keys(mine).length) all[id] = mine;
    else delete all[id];
    return { trainDelays: all };
  });
  render({ force: true });
}

/**
 * Applies a delay change while keeping the countdown on the same train: it keys on a shown time,
 * which moves by that train's own change. (Picked chips key on the timetabled time, which doesn't.)
 */
function keepingTrains(id, change) {
  const rem = prefs.reminder;
  const remBase = rem && rem.routeId === id && !rem.fired ? timetabledOf(id, rem.dep) : null;
  save(change());
  const after = (base) => base + delayFor(id, base) * MIN;
  if (remBase != null) save({ reminder: { ...rem, dep: after(remBase), at: after(remBase) } });
}

/* ----- one train's own delay ----- */

let trainDelayTarget = null;

function trainDelayHtml() {
  const { id, at } = trainDelayTarget;
  const r = prefs.routes.find((x) => x.id === id);
  const own = trainDelayOf(id, at);
  const d = own ?? delayOf(id);
  const tone = d > 0 ? 'late' : d < 0 ? 'early' : 'zero';
  return `${sheetHead(`The ${clock(at)} train`, r ? `${name(r.from)} → ${name(r.to)} · every day` : null, null)}
    <div class="sheet-body" style="flex: 0 1 auto">
      <div class="card dl" data-tone="${tone}" style="--v:${d}; --max:${DELAY_MAX}">
        <div class="dl-read"><span class="dl-num">${d ? Math.abs(d) : ''}</span><span class="dl-unit">${d ? `min ${d > 0 ? 'late' : 'early'}` : 'On time'}</span></div>
        <div class="dl-ctrl">
          <button class="dl-step" data-act="tdelay-step" data-d="-1" aria-label="1 min earlier" ${d <= -DELAY_MAX ? 'disabled' : ''}>${ic('remove')}</button>
          <div class="dl-slider"><div class="dl-track" aria-hidden="true"><b class="dl-fill"></b></div>
            <input type="range" data-act="tdelay" min="${-DELAY_MAX}" max="${DELAY_MAX}" step="1" value="${d}" aria-label="Delay for this train, in minutes" aria-valuetext="${delayText(d)}" /></div>
          <button class="dl-step" data-act="tdelay-step" data-d="1" aria-label="1 min later" ${d >= DELAY_MAX ? 'disabled' : ''}>${ic('add')}</button>
        </div>
        <span class="fl-foot">${
          own != null
            ? `This train only. Route delay: ${delayText(delayOf(id)).toLowerCase()}.`
            : `Uses the route delay (${delayText(delayOf(id)).toLowerCase()}).`
        }</span>
        ${own != null ? `<button class="cta small" data-act="tdelay-reset" style="align-self: flex-start">${ic('history')}Use route delay</button>` : ''}
      </div>
    </div>`;
}

function openTrainDelay(id, at) {
  trainDelayTarget = { id, at };
  openSheet(trainDelayHtml());
  sheetRoot.querySelector('.sheet').classList.add('short', 'fit');
}

function changeTrainDelay(d) {
  const { id, at } = trainDelayTarget;
  setTrainDelay(id, at, d == null ? null : Math.max(-DELAY_MAX, Math.min(DELAY_MAX, d)));
  replaceSheet(trainDelayHtml());
}

/* ----- "When?" sheet: plan a quick look for another time ----- */

let whenDraft = null;
const pad2 = (n) => String(n).padStart(2, '0');

function startWhen() {
  const plan = preview?.plan;
  const now = new Date(clockNow());
  const base = plan ? new Date(plan.at) : new Date(Math.ceil((now.getTime() + 15 * MIN) / (15 * MIN)) * 15 * MIN);
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime();
  const baseDay = new Date(base.getFullYear(), base.getMonth(), base.getDate()).getTime();
  whenDraft = {
    mode: plan?.mode ?? 'leave',
    day: baseDay > today ? 1 : 0,
    hm: `${pad2(base.getHours())}:${pad2(base.getMinutes())}`,
  };
}

/** The draft's time as a timestamp: that day (today or tomorrow) at that clock time. */
function whenAt(draft) {
  const now = new Date(clockNow());
  const [h, m] = draft.hm.split(':').map(Number);
  return new Date(now.getFullYear(), now.getMonth(), now.getDate() + draft.day, h, m).getTime();
}

function whenHtml() {
  const d = whenDraft;
  const seg = (act, value, opts) =>
    `<div class="seg" role="radiogroup">${opts
      .map(([v, l]) => `<button role="radio" aria-checked="${value === v}" data-act="${act}" data-v="${v}">${l}</button>`)
      .join('')}</div>`;
  return `${sheetHead('When?', preview ? `${name(preview.from)} → ${name(preview.to)}` : null, null)}
    <div class="sheet-body" style="flex: 0 1 auto">
      <div class="card set when-card">
        ${seg('when-mode', d.mode, [['now', 'Now'], ['leave', 'Depart at'], ['arrive', 'Arrive by']])}
        ${
          d.mode === 'now'
            ? '<span class="sub" style="font-size: 13px; line-height: 18px">Live departures from now.</span>'
            : `${seg('when-day', String(d.day), [['0', 'Today'], ['1', 'Tomorrow']])}
        <label class="when-time"><span>${d.mode === 'arrive' ? 'Arrive by' : 'Depart at'}</span><input type="time" value="${d.hm}" step="300" data-act="when-time" aria-label="Time"></label>`
        }
      </div>
      <button class="cta" data-act="when-done">${ic(d.mode === 'now' ? 'schedule' : 'search')}Show trains</button>
    </div>`;
}

function openWhen(replace) {
  startWhen();
  if (replace) replaceSheet(whenHtml());
  else openSheet(whenHtml());
  sheetRoot.querySelector('.sheet').classList.add('short', 'fit');
}

function closeSheet() {
  const scrim = sheetRoot.querySelector('.scrim');
  const sheet = sheetRoot.querySelector('.sheet');
  sheetOpen = false;
  menuPair = null;
  if (!sheet) return;
  sheet.style.transform = '';
  sheet.classList.remove('open');
  scrim.classList.remove('open');
  setTimeout(() => {
    if (!sheetOpen) sheetRoot.innerHTML = '';
  }, 500);
}

function dragToDismiss(sheet) {
  for (const h of [sheet.querySelector('.grab'), sheet.querySelector('.sheet-head')]) {
    if (!h) continue;
    let y0 = null;
    let t0 = 0;
    h.addEventListener('pointerdown', (e) => {
      if (e.target.closest('button')) return;
      y0 = e.clientY;
      t0 = performance.now();
      h.setPointerCapture(e.pointerId);
      sheet.style.transition = 'none';
    });
    h.addEventListener('pointermove', (e) => {
      if (y0 !== null) sheet.style.transform = `translateY(${Math.max(0, e.clientY - y0)}px)`;
    });
    const end = (e) => {
      if (y0 === null) return;
      const dy = e.clientY - y0;
      const v = dy / Math.max(1, performance.now() - t0);
      y0 = null;
      sheet.style.transition = '';
      if (dy > 120 || v > 0.6) history.back();
      else sheet.style.transform = '';
    };
    h.addEventListener('pointerup', end);
    h.addEventListener('pointercancel', end);
  }
}

/* ---------- events ---------- */

const routeOf = (el) => (el.dataset.route === 'preview' ? preview : prefs.routes.find((r) => r.id === el.dataset.route));

const ACTIONS = {
  'add-route': () => openStationStep('save', true),
  look: () => openStationStep('look', true),
  'pick-back': () => openStationStep(pickMode),
  'close-sheet': () => history.back(),
  settings: () => push('settings'),
  back: () => back(),
  swap: (el) => {
    buzz();
    if (el.dataset.route === 'preview') {
      preview = { ...preview, from: preview.to, to: preview.from };
    } else {
      const r = prefs.routes.find((x) => x.id === el.dataset.route);
      if (!r) return;
      const id = routeId(r.to, r.from);
      // Flip in place; drop a saved copy of the other direction so the list has no duplicates.
      const routes = prefs.routes.filter((x) => x.id !== id).map((x) => (x.id === r.id ? { id, from: r.to, to: r.from } : x));
      const stale = prefs.reminder?.routeId === r.id || prefs.reminder?.routeId === id;
      save({ routes, reminder: stale ? null : prefs.reminder });
    }
    render({ force: true, animate: true });
  },
  pick: (el) => {
    buzz();
    picked.set(el.dataset.route, Number(el.dataset.pick));
    render({ force: true });
  },
  mock: (el) => {
    buzz();
    const v = el.dataset.v;
    // A countdown set on the other clock would point at the wrong train.
    save({ mock: v === 'off' ? null : { at: Number(v), offset: mockOffsetFor(Number(v)) }, reminder: null });
    document.title = 'Headway';
    toast(v === 'off' ? 'Back to real time' : `Mock time on`);
    render({ force: true, animate: true });
  },
  count: (el) => {
    buzz();
    save({ count: Number(el.dataset.v) });
    render({ force: true });
  },
  remind: (el) => {
    buzz(14);
    const r = routeOf(el);
    if (r && r !== preview) toggleReminder(r, Number(el.dataset.dep));
    else if (r) toast('Save the route to start a countdown');
  },
  'route-menu': (el) => openRouteMenu(el.dataset.route),
  'top-route': (el) => {
    const r = routeOf(el);
    history.back();
    if (r) save({ routes: [r, ...prefs.routes.filter((x) => x.id !== r.id)] });
    render({ force: true, animate: true });
  },
  'upi-paid': () => {
    save({ upiPaidAt: Date.now() });
    history.back();
    render({ force: true });
  },
  'card-loaded': () => {
    save({ upiPaidAt: 0 });
    render({ force: true });
  },
  'top-up': () => {
    buzz();
    openTopUp();
  },
  station: (el) => {
    buzz();
    openStationSheet(el.dataset.id);
  },
  zoom: (el) => {
    buzz();
    zoomMap(Number(el.dataset.v));
  },
  'route-from': (el) => {
    pickMode = 'save';
    openDestinationStep(el.dataset.id);
  },
  coach: (el) => {
    buzz();
    const coaches = { ...(prefs.coaches ?? {}) };
    if (el.dataset.v) coaches[el.dataset.route] = el.dataset.v;
    else delete coaches[el.dataset.route];
    save({ coaches });
    refreshRouteMenu(el.dataset.route);
    render({ force: true });
  },
  'flip-toggle': (el) => {
    buzz();
    const r = routeOf(el);
    if (!r) return;
    if (flipOf(r)) return setFlip(r, null, false);
    // Start with the card as it is: this direction is the morning trip unless it's already evening.
    const { pm, key } = flipPeriod(clockNow(), FLIP_DEFAULT);
    setFlip(r, { at: FLIP_DEFAULT, home: pm ? r.to : r.from, applied: key }, false);
  },
  'flip-time': (el) => {
    buzz();
    const r = routeOf(el);
    const f = r && flipOf(r);
    if (!f) return;
    const at = Math.max(FLIP_MIN, Math.min(FLIP_MAX, f.at + Number(el.dataset.d)));
    setFlip(r, { ...f, at, applied: null }, true);
  },
  'flip-home': (el) => {
    buzz();
    const r = routeOf(el);
    const f = r && flipOf(r);
    if (!f) return;
    setFlip(r, { ...f, home: r.from === f.home ? r.to : r.from, applied: null }, true);
  },
  'train-delay': (el) => {
    buzz();
    openTrainDelay(el.dataset.route, Number(el.dataset.train));
  },
  'tdelay-step': (el) => {
    buzz();
    const { id, at } = trainDelayTarget;
    changeTrainDelay(delayFor(id, at) + Number(el.dataset.d));
  },
  'tdelay-reset': () => {
    buzz();
    changeTrainDelay(null);
  },
  'delay-step': (el) => {
    buzz();
    const id = el.dataset.route;
    setDelay(id, Math.max(-DELAY_MAX, Math.min(DELAY_MAX, delayOf(id) + Number(el.dataset.d))));
  },
  'delay-reset': (el) => {
    buzz();
    setDelay(el.dataset.route, 0);
  },
  'remove-route': (el) => {
    const r = routeOf(el);
    history.back();
    if (!r) return;
    const routes = prefs.routes.filter((x) => x.id !== r.id);
    const flips = { ...(prefs.flips ?? {}) };
    if (!routes.some((x) => pairKey(x) === pairKey(r))) delete flips[pairKey(r)];
    save({ routes, flips, reminder: prefs.reminder?.routeId === r.id ? null : prefs.reminder });
    toast('Route removed');
    render({ force: true, animate: true });
  },
  when: () => {
    buzz();
    openWhen(false);
  },
  'when-mode': (el) => {
    buzz();
    whenDraft.mode = el.dataset.v;
    replaceSheet(whenHtml());
  },
  'when-day': (el) => {
    buzz();
    whenDraft.day = Number(el.dataset.v);
    replaceSheet(whenHtml());
  },
  'when-done': () => {
    if (!preview) return history.back();
    const plan = whenDraft.mode === 'now' ? null : { mode: whenDraft.mode, at: whenAt(whenDraft) };
    preview = { ...preview, plan };
    picked.delete('preview');
    history.back();
    render({ force: true, animate: true });
  },
  'plan-route': (el) => {
    const r = routeOf(el);
    if (!r) return;
    buzz();
    preview = { id: 'preview', from: r.from, to: r.to };
    menuPair = null;
    if (nav.tab !== 'routes' || nav.stack.length) switchTab('routes');
    else render({ force: true, animate: true });
    openWhen(true);
  },
  'close-preview': () => {
    preview = null;
    render({ force: true, animate: true });
  },
  'save-preview': () => {
    if (!preview) return;
    const { from, to } = preview;
    preview = null;
    addRoute(from, to);
    render({ force: true, animate: true });
  },
  'last-warn': (el) => {
    save({ lastWarn: !prefs.lastWarn });
    el.setAttribute('aria-checked', String(prefs.lastWarn));
    buzz();
  },
  theme: (el) => {
    buzz();
    save({ theme: el.dataset.v });
    applyTheme();
    render({ force: true });
  },
};

document.addEventListener('click', (e) => {
  const tab = e.target.closest('[data-tab]');
  if (tab) {
    e.preventDefault();
    buzz();
    switchTab(tab.dataset.tab);
    return;
  }
  const el = e.target.closest('[data-act]');
  if (el && el.tagName !== 'SELECT' && ACTIONS[el.dataset.act]) ACTIONS[el.dataset.act](el);
});

document.addEventListener('change', (e) => {
  const el = e.target;
  if (el.dataset.act === 'override') {
    const key = serviceDateKey(clockNow());
    save({ override: el.value === 'auto' ? null : { date: key, type: el.value } });
    refreshSchedule();
  } else if (el.dataset.act === 'when-time' && whenDraft) {
    if (!el.value) return;
    whenDraft.hm = el.value;
    // A time that's already gone today means tomorrow.
    if (whenDraft.day === 0 && whenAt(whenDraft) < clockNow()) {
      whenDraft.day = 1;
      replaceSheet(whenHtml());
    }
  } else if (el.id === 'upi-scan') {
    if (el.files?.[0]) scanCardQr(el.files[0]);
    el.value = '';
  } else if (el.id === 'upi-id') {
    const id = el.value.trim();
    if (id && !UPI_ID.test(id)) {
      toast('Enter a valid UPI ID');
      return;
    }
    save({ upi: id });
    toast(id ? 'UPI ID saved' : 'UPI ID removed');
  } else if (el.id === 'feed-url') {
    const url = el.value.trim();
    save({ feedUrl: url });
    feed.setRemote(url);
    toast(url ? 'Advisory feed added' : 'Advisory feed removed');
  }
});

// The delay slider updates the card behind the sheet as it moves.
document.addEventListener('input', (e) => {
  const el = e.target;
  if (el.dataset?.act === 'tdelay' && trainDelayTarget) {
    const d = Number(el.value);
    if (d !== delayFor(trainDelayTarget.id, trainDelayTarget.at)) buzz(6);
    // Update the train but not the sheet, so the slider under the finger isn't replaced.
    setTrainDelay(trainDelayTarget.id, trainDelayTarget.at, d);
    const card = el.closest('.card.dl');
    card.style.setProperty('--v', d);
    card.dataset.tone = d > 0 ? 'late' : d < 0 ? 'early' : 'zero';
    card.querySelector('.dl-num').textContent = d ? Math.abs(d) : '';
    card.querySelector('.dl-unit').textContent = d ? `min ${d > 0 ? 'late' : 'early'}` : 'On time';
    return;
  }
  if (el.dataset?.act !== 'delay') return;
  const d = Number(el.value);
  if (d !== delayOf(el.dataset.route)) buzz(6);
  setDelay(el.dataset.route, d);
});

document.addEventListener('keydown', (e) => {
  if (e.key === 'Escape' && sheetOpen) history.back();
});

function applyTheme() {
  const root = document.documentElement;
  if (prefs.theme === 'auto') delete root.dataset.theme;
  else root.dataset.theme = prefs.theme;
  const dark = prefs.theme === 'dark' || (prefs.theme === 'auto' && matchMedia('(prefers-color-scheme: dark)').matches);
  document.querySelector('meta[name="theme-color"]')?.setAttribute('content', dark ? '#0b0b0d' : '#f2f2f7');
}

/* ---------- boot ---------- */

applyTheme();
matchMedia('(prefers-color-scheme: dark)').addEventListener('change', applyTheme);
history.replaceState({ screen: 'root' }, '');
render({ force: true, animate: true });
startClock();
feed.start();
document.addEventListener('visibilitychange', () => document.visibilityState === 'visible' && render({ force: true }));

// Skipped on localhost so a cache-first worker never serves stale files during development.
if ('serviceWorker' in navigator && location.protocol === 'https:') {
  navigator.serviceWorker.register('sw.js').catch(() => {});
}
