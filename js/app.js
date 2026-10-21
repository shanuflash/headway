import { Schedule, serviceDateKey, bandAt, hm } from './engine.js';
import { STATIONS, ROUTES, LINES, PICKER_GROUPS, directDestinations } from './network.js';
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
  coaches: {}, // route id -> 'front' | 'middle' | 'rear', set by the rider
};

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
    return `Inter-corridor · toward ${name(end)}`;
  }
  return `${lineName(g.line)} · toward ${g.terminals.map(name).join(' or ')}`;
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

function boardingTags(r) {
  const p = platformFor(r);
  const c = prefs.coaches?.[r.id];
  if (!p && !c) return '';
  return `<div class="boarding rc-pad">${p ? `<span>${esc(p)}</span>` : ''}${c ? `<span>${c[0].toUpperCase()}${c.slice(1)} coach</span>` : ''}</div>`;
}

/** Only inter-corridor trains serve this route (Green Line tracks, then Blue to the Airport). */
const interOnly = (m) => m.kind !== 'none' && m.deps.length > 0 && m.deps.every((d) => d.trip.route === 'inter');

function routeLine(r) {
  const g = directDestinations(r.from).find((x) => x.stations.some((s) => s.id === r.to));
  return g?.line ?? 'blue';
}

/* ---------- route model ---------- */

function routeModel(r, now) {
  const today = schedule.today(now).key;
  const deps = schedule.routeDepartures(r.from, r.to, now, prefs.count);
  const next = deps[0] ?? null;
  if (!next) return { r, kind: 'none' };
  const last = schedule.lastRouteDeparture(r.from, r.to, now);
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

function routesScreen(now) {
  const notices = activeNotices(feedState.feed, now).map(noticeCard).join('');
  const head = `
    ${topbar(`
      <span class="glass-group glass">
        <button data-act="add-route" aria-label="Add a route">${ic('add')}</button>
        <button data-act="settings" aria-label="Settings">${ic('more_horiz')}</button>
      </span>`)}
    <div class="title"><h1>Routes</h1></div>
    ${prefs.mock ? mockBanner() : ''}
    ${notices}`;

  const cards = [];
  if (preview) cards.push(routeCard(routeModel(preview, now), true));
  if (!prefs.routes.length && !preview) return head + emptyState();
  for (const r of prefs.routes) cards.push(routeCard(routeModel(r, now), false));
  return head + cards.join('');
}

function mockBanner() {
  return `<div class="strip mock"><span class="grow"><span class="t">Mock time: <span data-at="0" data-fmt="now">${clock(clockNow())}</span></span><span class="s">Not the real schedule. Real time is <span data-at="${prefs.mock.offset}" data-fmt="now">${clock(Date.now())}</span>.</span></span><button class="strip-btn mock-off" data-act="mock" data-v="off">Turn off</button></div>`;
}

function noticeCard(n) {
  return `<div class="strip info"><span class="grow"><span class="t">${esc(n.title)}</span><span class="s">${esc(n.body ?? '')}</span></span></div>`;
}

function emptyState() {
  return `
    <div class="card hero" style="padding-top: 20px; gap: 16px">
      <span style="font-size: 28px; line-height: 34px; font-weight: 700">Add your daily route</span>
      <span class="sub" style="font-size: 15px; line-height: 20px">Save where you board and where you get off. Headway lists the next trains on it, and one tap flips it for the ride home.</span>
      <button class="cta" data-act="add-route">${ic('add')}Add a route</button>
    </div>
    <span class="section-title">Or check any station</span>
    <button class="search-like card" style="background: var(--card); height: 52px" data-act="look">${ic('search')}Search stations</button>`;
}

const reminderOn = (r, dep) => !!(prefs.reminder && prefs.reminder.routeId === r.id && prefs.reminder.dep === dep && !prefs.reminder.fired);

// Picked train per route (departure ms). Falls back to the next train once the picked one leaves.
const picked = new Map();

function routeCard(m, isPreview) {
  const { r } = m;
  const line = routeLine(r);
  const head = `
    <div class="rc-head">
      <span class="grow">
        <span class="t">${esc(name(r.from))} → ${esc(name(r.to))}</span>
        <span class="s"><i class="dot ${interOnly(m) ? 'inter' : line}"></i>${esc(routeLabel(r, interOnly(m)))}</span>
      </span>
      <span class="pillgroup">
        <button data-act="swap" data-route="${r.id}" aria-label="Switch direction">${ic('swap_vert')}</button>
        ${
          isPreview
            ? `<button data-act="close-preview" aria-label="Close quick look">${ic('close')}</button>`
            : `<button data-act="route-menu" data-route="${r.id}" aria-label="Route options">${ic('more_horiz')}</button>`
        }
      </span>
    </div>`;

  let body = '';
  if (m.kind === 'none') {
    body = `<div class="rc-pad sub" style="font-size: 15px">No direct trains between these stations.</div>`;
  } else {
    const closed = m.kind === 'closed';
    const want = picked.get(r.id);
    const i = Math.max(0, m.deps.findIndex((d) => d.dep === want));
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
    const note = trainNote(d.trip) ? ` · ${trainNote(d.trip)}` : '';
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
        return `<button class="tchip ${sel ? 'on' : ''} ${tm && !closed ? 'dim' : ''}" data-act="pick" data-route="${r.id}" data-dep="${x.dep}" aria-pressed="${sel}"><span class="t">${clock(
          x.dep,
        )}</span><span class="s ${k === lastIdx ? 'warn' : ''}">${sub}</span></button>`;
      })
      .join('');
    body = `
      <div class="rc-hero rc-pad">
        <div class="grow">
          <span class="label ${tone}">${label}</span>
          ${big}
          <span class="line">${lineText}</span>
        </div>
        <button class="bell ${on ? 'on' : ''}" data-act="remind" data-route="${r.id}" data-dep="${d.dep}" aria-pressed="${on}" aria-label="${
          on ? 'Stop the countdown' : 'Count down to this train'
        }">${ic('notifications', on ? 'fill' : '')}</button>
      </div>
      ${boardingTags(r)}
      <div class="tchips">${chips}</div>
      ${m.late && m.last ? `<div class="note rc-pad">${ic('bedtime')}<span>The ${clock(m.last.dep)} is the last train tonight.</span></div>` : ''}`;
  }

  return `
    <div class="card rc">
      ${head}
      ${body}
      ${isPreview ? `<div class="rc-pad"><button class="cta" data-act="save-preview">${ic('add')}Save this route</button></div>` : ''}
    </div>`;
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
    <p class="footnote">Tap a station for its next trains each way. Map data © OpenStreetMap contributors.</p>`;
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
          <span class="dir"><i class="dot ${g.line}"></i>Toward ${esc(g.terminals.map(name).join(' or '))}${p ? ` · ${esc(p)}` : ''}</span>
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
      day.loop ? 'Weekday peaks add short-loop trains between Washermenpet and Alandur, so that stretch sees a train every 3 min. ' : ''
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
    <p class="footnote">Auto follows the calendar and public holidays (${esc(auto.reason)}). Pick Sunday or holiday if CMRL announces it for today.</p>

    <div class="card set">
      <span>Trains shown per route</span>
      ${seg('count', String(prefs.count), COUNT_CHOICES.map((n) => [String(n), String(n)]))}
      <div class="set-row">
        <span class="grow"><span class="t">Last train warning</span><span class="s">In the final hour of service</span></span>
        <button class="switch" role="switch" aria-checked="${prefs.lastWarn}" data-act="last-warn" aria-label="Last train warning"><i></i></button>
      </div>
    </div>

    <div class="card set">
      <span>Appearance</span>
      ${seg('theme', prefs.theme, [['light', 'Light'], ['dark', 'Dark'], ['auto', 'Automatic']])}
    </div>

    <div class="card set">
      <span>Mock time</span>
      ${seg('mock', prefs.mock ? String(prefs.mock.at) : 'off', [['off', 'Off'], ...MOCK_CHOICES.map(([m, l]) => [String(m), l])])}
    </div>
    <p class="footnote">For testing. The app runs as if it were that time now, and the clock keeps ticking from there.</p>

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
    <p class="footnote">First and last trains match CMRL's timetable. Times at stations in between are estimated, so allow a minute or two either way.${
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
    nextChange = Infinity;
    for (const r of [...prefs.routes, ...(preview ? [preview] : [])]) {
      const m = routeModel(r, now);
      if (m.next) nextChange = Math.min(nextChange, m.next.dep);
    }
    return routesScreen(now);
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
  if (now - lastRender > 15_000 || now >= nextChange) render();
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
  toast(`Counting down to the ${clock(dep)}`);
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
    ${sheetHead(mode === 'look' ? 'Check a station' : 'Where do you board?', null, null)}
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
  const mount = (sheet) => {
    const input = sheet.querySelector('input');
    const body = sheet.querySelector('.sheet-body');
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
    });
  };
  if (initial) openSheet(html, mount);
  else replaceSheet(html, mount);
}

function openDestinationStep(from) {
  const groups = directDestinations(from);
  const html = `
    ${sheetHead(`Where to from ${name(from)}?`, 'Direct trains only', 'pick-back')}
    <div class="sheet-body">
      ${groups
        .map(
          (g) => `
        <div style="display: flex; flex-direction: column; gap: 8px">
          <div class="dir-head"><span class="dot ${g.line}"></span><span class="t">Toward ${esc(g.terminals.map(name).join(' or '))}</span></div>
          <div class="card list">${g.stations
            .map((s) => {
              const st = STATIONS[s.id];
              const tags = st.lines
                .filter((l) => l !== g.line)
                .map((l) => `<span class="pill-tag ${l}">${LINES[l].name}</span>`)
                .join('');
              return `<button class="row tap plain" data-dest="${s.id}" data-from="${from}"><span class="grow"><span class="t">${esc(st.name)}</span></span>${tags}<span class="v light">${s.minutes} min</span></button>`;
            })
            .join('')}</div>
        </div>`,
        )
        .join('')}
    </div>`;
  replaceSheet(html);
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

function openRouteMenu(id) {
  const r = prefs.routes.find((x) => x.id === id);
  if (!r) return;
  openSheet(
    `${sheetHead(`${name(r.from)} → ${name(r.to)}`, null, null)}
    <div class="sheet-body" style="flex: none">
      <div class="card set">
        <span>Best coach to board</span>
        <div class="seg" role="radiogroup" aria-label="Best coach to board">${[['', 'Off'], ['front', 'Front'], ['middle', 'Middle'], ['rear', 'Rear']]
          .map(([v, l]) => `<button role="radio" aria-checked="${(prefs.coaches?.[id] ?? '') === v}" data-act="coach" data-route="${id}" data-v="${v}">${l}</button>`)
          .join('')}</div>
        <span class="sub" style="font-size: 13px; line-height: 18px">Where to stand so you get off near the exit or stairs you use. Shown on the route card.</span>
      </div>
      <div class="card list">
        <button class="row tap plain" data-act="reverse-route" data-route="${id}">${ic('swap_vert')}<span class="grow"><span class="t">Add the return trip</span><span class="s">${esc(name(r.to))} → ${esc(name(r.from))}</span></span></button>
        <button class="row tap plain" data-act="top-route" data-route="${id}">${ic('vertical_align_top')}<span class="grow"><span class="t">Move to the top</span></span></button>
        <button class="row tap plain" data-act="remove-route" data-route="${id}" style="color: #d70015">${ic('delete')}<span class="grow"><span class="t">Remove route</span></span></button>
      </div>
    </div>`,
  );
  sheetRoot.querySelector('.sheet').classList.add('short');
}

function closeSheet() {
  const scrim = sheetRoot.querySelector('.scrim');
  const sheet = sheetRoot.querySelector('.sheet');
  sheetOpen = false;
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
    picked.set(el.dataset.route, Number(el.dataset.dep));
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
  'reverse-route': (el) => {
    const r = routeOf(el);
    history.back();
    if (r) addRoute(r.to, r.from);
    render({ force: true, animate: true });
  },
  'top-route': (el) => {
    const r = routeOf(el);
    history.back();
    if (r) save({ routes: [r, ...prefs.routes.filter((x) => x.id !== r.id)] });
    render({ force: true, animate: true });
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
    openRouteMenu(el.dataset.route);
    render({ force: true });
  },
  'remove-route': (el) => {
    const r = routeOf(el);
    history.back();
    if (!r) return;
    save({ routes: prefs.routes.filter((x) => x.id !== r.id), reminder: prefs.reminder?.routeId === r.id ? null : prefs.reminder });
    toast(`Removed ${name(r.from)} → ${name(r.to)}`);
    render({ force: true, animate: true });
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
  } else if (el.id === 'feed-url') {
    const url = el.value.trim();
    save({ feedUrl: url });
    feed.setRemote(url);
    toast(url ? 'Checking advisory feed' : 'Advisory feed removed');
  }
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
