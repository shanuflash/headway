import { Schedule, serviceDateKey, bandAt, hm } from './engine.js';
import { STATIONS, ROUTES, LINES, PICKER_GROUPS } from './network.js';
import { TIMETABLE, TIMETABLE_SOURCE } from './timetable.js';
import { createFeed, activeNotices } from './live.js';

const MIN = 60_000;
const STORE = 'headway:v2';
const view = document.getElementById('view');
const dock = document.getElementById('dock');
const sheetRoot = document.getElementById('sheet-root');
const toastEl = document.getElementById('toast');
const reduceMotion = matchMedia('(prefers-reduced-motion: reduce)');

/* ---------- preferences ---------- */

const DEFAULTS = {
  from: null,
  to: null,
  walk: 4,
  remindLead: 2,
  lastWarn: true,
  theme: 'auto',
  override: null,
  feedUrl: '',
  saved: [],
  recents: [],
  reminder: null,
  ride: null,
  rideAlert: true,
};

function load() {
  try {
    const saved = JSON.parse(localStorage.getItem(STORE) || 'null');
    if (saved) return { ...DEFAULTS, ...saved };
    // Carry over a trip from the first version of the app.
    const v1 = JSON.parse(localStorage.getItem('headway:v1') || 'null');
    if (v1?.from && v1?.to) return { ...DEFAULTS, from: v1.from, to: v1.to, walk: v1.walk ?? DEFAULTS.walk };
  } catch {}
  return { ...DEFAULTS };
}

let prefs = load();
if (prefs.from && !STATIONS[prefs.from]) prefs.from = null;
if (prefs.to && !STATIONS[prefs.to]) prefs.to = null;

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
  lastCache.clear();
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
const clock = (ms) => {
  const d = new Date(ms);
  return `${pad(d.getHours())}:${pad(d.getMinutes())}`;
};
const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => `&#${c.charCodeAt(0)};`);
const name = (id) => STATIONS[id]?.name ?? '';
const ic = (n, cls = '') => `<span class="ms ${cls}" aria-hidden="true">${n}</span>`;
const plural = (n, w) => `${n} ${w}${n === 1 ? '' : 's'}`;
const mins = (ms) => Math.max(0, Math.floor(ms / MIN));

function span(ms) {
  const m = Math.max(0, Math.round(ms / MIN));
  if (m < 60) return `${m} min`;
  return `${Math.floor(m / 60)} h ${m % 60} min`;
}

// Hero number: whole minutes, m:ss under two minutes, h:mm past an hour.
function bigParts(ms) {
  if (ms < 30_000) return ['Now', ''];
  if (ms < 2 * MIN) {
    const s = Math.floor(ms / 1000);
    return [`${Math.floor(s / 60)}:${pad(s % 60)}`, 'min'];
  }
  const m = mins(ms);
  if (m < 60) return [String(m), 'min'];
  return [`${Math.floor(m / 60)}:${pad(m % 60)}`, 'h'];
}

const FORMATS = {
  big: (ms) => bigParts(ms)[0],
  bigUnit: (ms) => bigParts(ms)[1],
  in: (ms) => (ms < 45_000 ? 'now' : `in ${span(ms)}`),
  mins: (ms) => `${mins(ms)}`,
  leave: (ms) => (ms < 30_000 ? 'Leave now' : `Leave in ${span(ms)}`),
};
const live = (at, fmt) => `<span data-at="${at}" data-fmt="${fmt}">${FORMATS[fmt](at - Date.now())}</span>`;

const lineName = (line) => LINES[line].name;
function legLabel(trip) {
  if (trip.route === 'loop') return `${lineName(trip.line)}, short loop`;
  if (trip.route === 'inter') return `${lineName(trip.line)} via CMBT`;
  return lineName(trip.line);
}

/* ---------- planning helpers ---------- */

const lastCache = new Map();

// Last departure of the service day that still reaches `to`: binary search on start time.
function lastJourney(from, to, nowMs) {
  const day = serviceDateKey(nowMs);
  const key = `${from}|${to}|${day}`;
  if (lastCache.has(key)) return lastCache.get(key);
  const first = schedule.plan(from, to, nowMs);
  let best = first && first.legs[0].trip.serviceDate === day ? first : null;
  if (best) {
    let lo = best.dep;
    let hi = nowMs + 30 * 60 * MIN;
    while (hi - lo > MIN) {
      const mid = (lo + hi) / 2;
      const j = schedule.plan(from, to, mid);
      if (j && j.legs[0].trip.serviceDate === day) {
        best = j;
        lo = Math.max(mid, j.dep);
      } else hi = mid;
    }
  }
  lastCache.set(key, best);
  return best;
}

function changesText(j) {
  const n = j.legs.length - 1;
  if (!n) return 'Direct';
  return `${plural(n, 'change')}, at ${j.legs
    .slice(1)
    .map((l) => name(l.from))
    .join(' and ')}`;
}

/* ---------- model ---------- */

let model = null;

function computeNow(now) {
  const today = schedule.today(now);
  const notices = activeNotices(feedState.feed, now);
  if (!prefs.from || !prefs.to) return { kind: 'empty', today, notices };

  const walk = prefs.walk * MIN;
  const js = schedule.journeys(prefs.from, prefs.to, now + walk, 4);
  const board = schedule.departures(prefs.from, now, 80);
  if (!js.length) return { kind: 'none', today, notices, board };

  const j = js[0];
  const day = j.legs[0].trip.serviceDate;
  const closed = day !== today.key;
  const last = closed ? null : lastJourney(prefs.from, prefs.to, now);
  const sameDay = js.filter((x) => x.legs[0].trip.serviceDate === day);
  const late = !closed && !!last && prefs.lastWarn && last.dep - now < 60 * MIN;
  const nextDay = late ? schedule.plan(prefs.from, prefs.to, last.dep + 1) : null;

  return {
    kind: closed ? 'closed' : 'trip',
    today,
    notices,
    j,
    later: js.slice(1, 4),
    leaveAt: j.dep - walk,
    last,
    late,
    remaining: sameDay.length < js.length ? sameDay.length : null,
    nextDay,
    board,
    running: board.some((d) => d.trip.serviceDate === today.key),
  };
}

/* ---------- views ---------- */

function topbar(right, left = '') {
  return `<div class="topbar">${left}<span class="spacer"></span>${right}</div>`;
}

function nowScreen(m) {
  const head = `
    ${topbar(`
      <button class="round" data-act="new-trip" aria-label="New trip">${ic('add')}</button>
      <button class="round" data-act="settings" aria-label="Settings">${ic('more_horiz')}</button>`)}
    <div class="title">
      <h1>Now</h1>
      <button class="link" data-act="pick" data-field="from">${
        prefs.from ? `From ${esc(name(prefs.from))}` : 'Set a starting station'
      }${ic('expand_more')}</button>
    </div>
    ${prefs.ride ? rideBanner() : ''}
    ${m.notices.map(noticeCard).join('')}`;

  if (m.kind === 'empty') return head + emptyHero();
  if (m.kind === 'none') {
    return `${head}<div class="card hero"><div class="hero-head"><div class="grow"><span class="t">No trains found</span><span class="s">There's no scheduled service between these stations.</span></div></div></div>`;
  }
  return head + (m.kind === 'closed' ? closedHero(m) : tripHero(m)) + departures(m);
}

function rideBanner() {
  const r = prefs.ride;
  return `
    <button class="card row tap" data-act="open-ride" style="border-radius: 20px">
      <span class="badge-icon">${ic('directions_subway')}</span>
      <span class="grow"><span class="t strong">On board to ${esc(name(r.legs.at(-1).to))}</span><span class="s">Arriving ${clock(r.arr)}, ${live(r.arr, 'in')}</span></span>
      ${ic('chevron_right', 'chev')}
    </button>`;
}

function noticeCard(n) {
  return `<div class="strip info"><span class="grow"><span class="t">${esc(n.title)}</span><span class="s">${esc(n.body ?? '')}</span></span></div>`;
}

function emptyHero() {
  return `
    <div class="card hero" style="padding-top: 20px">
      <span style="font-size: 28px; line-height: 34px; font-weight: 700">Where to?</span>
      <span class="sub" style="font-size: 15px; line-height: 20px">Pick where you start and where you're going. Headway shows your next train, when to leave, and the last train home.</span>
      <button class="search-like" data-act="pick" data-field="to">${ic('search')}Search stations</button>
      <div class="quick">
        <button data-act="quick" data-id="central">Chennai Central</button>
        <button data-act="quick" data-id="airport">Airport</button>
        <button data-act="quick" data-id="koyambedu">Koyambedu</button>
        <button data-act="quick" data-id="vadapalani">Vadapalani</button>
      </div>
    </div>`;
}

const reminderOn = (dep) => !!(prefs.reminder && prefs.reminder.dep === dep && !prefs.reminder.fired);

function tripHero(m) {
  const { j } = m;
  const leg = j.legs[0];
  const [n, u] = bigParts(j.dep - Date.now());
  const on = reminderOn(j.dep);
  const badge = m.late
    ? `<span class="tag">${m.remaining && m.remaining <= 2 ? `Last ${plural(m.remaining, 'train')}` : 'Last hour'}</span>`
    : ic('chevron_right', 'chev');
  const strip = prefs.walk
    ? `<span class="grow"><span class="t">${live(m.leaveAt, 'leave')}</span><span class="s">${prefs.walk} min walk to the platform</span></span>`
    : `<span class="grow"><span class="t">Be on the platform by ${clock(j.dep)}</span><span class="s">Set your walk time in Settings</span></span>`;
  const chips = m.later
    .map((x) => {
      const note = x.legs[0].trip.route === 'loop' ? 'short loop' : `arr ${clock(x.arr)}`;
      return `<button class="chip" data-act="trip" data-dep="${x.dep}"><span class="t">${clock(x.dep)}</span><span class="s">${note}</span></button>`;
    })
    .join('');
  const footnote =
    m.late && m.nextDay
      ? `<div class="note">${ic('bedtime')}<span>After ${clock(m.last.dep)}, the next train to ${esc(name(prefs.to))} is at ${clock(m.nextDay.dep)} tomorrow.</span></div>`
      : '';
  return `
    <div class="card hero">
      <button class="hero-head" data-act="trip" data-dep="${j.dep}">
        <span class="badge-icon">${ic('subway')}</span>
        <span class="grow"><span class="t">To ${esc(name(prefs.to))}</span><span class="s">${esc(legLabel(leg.trip))} toward ${esc(name(leg.trip.dest))}${
          j.legs.length > 1 ? `, ${plural(j.legs.length - 1, 'change')}` : ''
        }</span></span>
        ${badge}
      </button>
      <div class="big-row">
        <div class="big" role="timer"><span class="n" data-at="${j.dep}" data-fmt="big">${n}</span><span class="u" data-at="${j.dep}" data-fmt="bigUnit">${u}</span></div>
        <div class="big-side"><span class="t">${clock(j.dep)}</span><span class="s">arrives ${clock(j.arr)}</span></div>
      </div>
      <div class="strip">
        ${ic('directions_walk')}
        ${strip}
        <button class="strip-btn ${on ? 'on' : ''}" data-act="remind" data-dep="${j.dep}" aria-pressed="${on}" aria-label="Remind me when to leave">${ic('notifications', on ? 'fill' : '')}</button>
      </div>
      ${chips ? `<div class="chips">${chips}</div>` : ''}
      ${footnote}
    </div>`;
}

function closedHero(m) {
  const { j } = m;
  const remindAt = j.dep - prefs.walk * MIN - prefs.remindLead * MIN;
  const on = reminderOn(j.dep);
  // Other lines can still be running when only part of this trip has closed (Yellow Line ends at 21:00).
  const { running } = m;
  const yellow = j.legs.some((l) => l.trip.line === 'yellow');
  const spec = TIMETABLE[schedule.today(j.dep).type]?.yellow;
  const note =
    yellow && spec
      ? `The Yellow Line runs ${spec.first.fwd} to ${spec.last.fwd}.`
      : 'Trains run from about 05:00 to 23:00.';
  return `
    <div class="card hero">
      <div class="hero-head">
        <span class="badge-icon muted">${ic('bedtime', 'fill')}</span>
        <span class="grow"><span class="t">${running ? 'No more trains tonight' : 'Metro is closed'}</span><span class="s">First trip to ${esc(name(prefs.to))}</span></span>
      </div>
      <div class="big-row">
        <div class="big"><span class="n">${clock(j.dep)}</span></div>
        <div class="big-side"><span class="t">${live(j.dep, 'in')}</span><span class="s">arrives ${clock(j.arr)}</span></div>
      </div>
      <button class="wide-btn ${on ? 'on' : ''}" data-act="remind" data-dep="${j.dep}">${ic('alarm')}${
        on ? `Reminder set for ${clock(remindAt)}` : `Remind me at ${clock(remindAt)}`
      }</button>
      <span class="sub" style="font-size: 13px; line-height: 18px; padding: 0 2px">${note}</span>
    </div>`;
}

function departures(m) {
  const groups = new Map();
  for (const d of m.board) {
    const k = `${d.trip.route}:${d.trip.dir}`;
    if (!groups.has(k)) groups.set(k, { trip: d.trip, at: [] });
    groups.get(k).at.push(d);
  }
  if (!groups.size) return '';
  const today = m.today.key;
  const overnight = m.kind === 'closed' && !m.running;
  const rows = [...groups.values()]
    // While trains are running, a pattern with nothing left today (peak short loops) drops out.
    .filter((g) => overnight || g.at.some((d) => d.trip.serviceDate === today))
    .sort((a, b) => a.at[0].at - b.at[0].at)
    .slice(0, 5)
    .map((g) => {
      const todays = g.at.filter((d) => d.trip.serviceDate === today);
      const warn = m.kind === 'trip' && prefs.lastWarn && todays.length > 0 && todays.length <= 2;
      const shown = (overnight || !todays.length ? g.at : todays).slice(0, 2);
      const times =
        overnight
          ? shown.map((d) => clock(d.at)).join(', ')
          : `${shown.map((d) => live(d.at, 'mins')).join(', ')} min`;
      const sub = warn
        ? `${legLabel(g.trip)}, last ${clock(todays.at(-1).at)}`
        : g.trip.route === 'loop'
          ? 'Short loop'
          : g.trip.route === 'inter'
            ? 'Green Line via CMBT'
            : lineName(g.trip.line);
      return `
        <div class="row">
          <span class="bar ${g.trip.line} ${g.trip.route === 'loop' ? 'dim' : ''}"></span>
          <span class="grow"><span class="t">${esc(name(g.trip.dest))}</span><span class="s">${esc(sub)}</span></span>
          <span class="v ${warn ? 'warn' : ''}">${times}</span>
        </div>`;
    });
  if (m.kind === 'trip' && m.last && !m.late) {
    rows.push(`
      <div class="row">
        <span class="bar neutral"></span>
        <span class="grow"><span class="t">Last train to ${esc(name(prefs.to))}</span><span class="s">Tonight, arrives ${clock(m.last.arr)}</span></span>
        <span class="v">${clock(m.last.dep)}</span>
      </div>`);
  }
  return `
    <span class="section-title">${overnight ? 'First trains' : m.late ? 'Last departures' : 'Departures'}</span>
    <div class="card list">${rows.join('')}</div>`;
}

/* ----- trips tab ----- */

function tripsScreen() {
  const now = Date.now();
  const item = (t) => {
    const j = schedule.plan(t.from, t.to, now + prefs.walk * MIN);
    const line = j ? j.legs[0].trip.line : 'blue';
    return `
      <button class="row tap" data-act="use-trip" data-from="${t.from}" data-to="${t.to}">
        <span class="bar ${line}"></span>
        <span class="grow"><span class="t">${esc(name(t.to))}</span><span class="s">From ${esc(name(t.from))}${
          j ? `, ${esc(changesText(j).toLowerCase())}` : ''
        }</span></span>
        <span class="v">${j ? `${live(j.dep, 'mins')} min` : ''}</span>
      </button>`;
  };
  const valid = (t) => STATIONS[t.from] && STATIONS[t.to];
  const saved = prefs.saved.filter(valid);
  const recents = prefs.recents.filter((t) => valid(t) && !saved.some((s) => s.from === t.from && s.to === t.to));
  return `
    ${topbar(`<button class="round" data-act="new-trip" aria-label="New trip">${ic('add')}</button>`)}
    <div class="title"><h1>Trips</h1></div>
    <span class="section-title">Saved</span>
    ${
      saved.length
        ? `<div class="card list">${saved.map(item).join('')}</div>`
        : `<div class="card empty">Save a trip from its detail screen and it shows up here with its next train.</div>`
    }
    ${recents.length ? `<span class="section-title">Recent</span><div class="card list">${recents.slice(0, 6).map(item).join('')}</div>` : ''}`;
}

/* ----- lines tab ----- */

function linesScreen() {
  const today = schedule.today(Date.now());
  const day = TIMETABLE[today.type];
  const d = new Date();
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
          <span>${esc(name(a))}</span><span class="r">${spec.first.fwd}</span><span class="r">${spec.last.fwd}</span>
          <span>${esc(name(b))}</span><span class="r">${spec.first.rev}</span><span class="r">${spec.last.rev}</span>
        </div>
        <div class="bands">${spec.bands
          .map(
            (bnd) =>
              `<div class="band ${bnd === current ? 'now' : ''}"><span>${bnd[0]} to ${bnd[1] === '24:00' ? 'close' : bnd[1]}${
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
    ${card('inter', 'Green Line via CMBT', 'green', ', then on to the Airport')}
    ${card('yellow', 'Yellow Line', 'yellow')}
    <p class="footnote" style="margin-top: 0">${
      day.loop ? 'Weekday peaks add short-loop trains between Washermenpet and Alandur, so that stretch sees a train every 3 min. ' : ''
    }Source: <a href="${TIMETABLE_SOURCE.url}" target="_blank" rel="noopener">CMRL timetable</a>, ${TIMETABLE_SOURCE.published}.</p>`;
}

/* ----- trip detail ----- */

let tripDep = null;

function tripJourneys() {
  return schedule.journeys(prefs.from, prefs.to, Date.now() + prefs.walk * MIN, 4);
}

function selectedJourney() {
  const js = tripJourneys();
  return js.find((x) => x.dep === tripDep) ?? js[0] ?? null;
}

function tripScreen() {
  const j = selectedJourney();
  const isSaved = prefs.saved.some((t) => t.from === prefs.from && t.to === prefs.to);
  const head = topbar(
    `<button class="round ${isSaved ? 'on' : ''}" data-act="save-trip" aria-pressed="${isSaved}" aria-label="${
      isSaved ? 'Remove from saved' : 'Save trip'
    }">${ic('bookmark', isSaved ? 'fill' : '')}</button>`,
    `<button class="round" data-act="back" aria-label="Back">${ic('chevron_left')}</button>`,
  );
  if (!j) return `${head}<div class="card empty">No upcoming trains for this trip.</div>`;

  const [n, u] = bigParts(j.dep - Date.now());
  const leaveAt = j.dep - prefs.walk * MIN;
  const on = reminderOn(j.dep);
  const parts = [];
  const steps = [];
  j.legs.forEach((leg, i) => {
    if (i > 0) {
      const prev = j.legs[i - 1];
      const wait = (leg.dep - prev.arr) / MIN;
      const walkT = STATIONS[leg.from].transfer ?? 4;
      const spare = Math.round(wait) - walkT;
      parts.push(`<span class="wait" style="flex: ${wait}"></span>`);
      steps.push(`
        <div class="row walk">${ic('directions_walk')}<span class="grow">Change at ${esc(name(leg.from))}, ${walkT} min walk${
          spare > 0 ? `, ${spare} min spare` : ''
        }</span></div>`);
    }
    parts.push(`<span class="${leg.trip.line}" style="flex: ${(leg.arr - leg.dep) / MIN}"></span>`);
    const stops = leg.stops.length - 1;
    const detail = leg.trip.line === 'yellow' && stops === 1 ? `non-stop, ${span(leg.arr - leg.dep)}` : plural(stops, 'stop');
    steps.push(`
      <div class="row">
        <span class="bar ${leg.trip.line}"></span>
        <span class="grow"><span class="t">${esc(lineName(leg.trip.line))} to ${esc(name(leg.to))}</span><span class="s">Toward ${esc(name(leg.trip.dest))}, ${detail}</span></span>
        <span class="v">${clock(leg.dep)}</span>
      </div>`);
  });
  steps.push(`
    <div class="row">
      <span class="bar neutral"></span>
      <span class="grow"><span class="t strong">Arrive at ${esc(name(prefs.to))}</span><span class="s">${span(j.arr - j.dep)} from the platform</span></span>
      <span class="v">${clock(j.arr)}</span>
    </div>`);

  return `
    ${head}
    <div class="card hero">
      <div class="hero-head">
        <span class="badge-icon">${ic('route')}</span>
        <span class="grow"><span class="t">To ${esc(name(prefs.to))}</span><span class="s">From ${esc(name(prefs.from))}, ${esc(changesText(j).toLowerCase())}</span></span>
      </div>
      <div class="big-row">
        <div class="big"><span class="n" data-at="${j.dep}" data-fmt="big">${n}</span><span class="u" data-at="${j.dep}" data-fmt="bigUnit">${u}</span></div>
        <div class="big-side"><span class="t">${clock(j.dep)}</span><span class="s">arrives ${clock(j.arr)}</span></div>
      </div>
      <div class="segbar" aria-hidden="true">${parts.join('')}</div>
      <div class="strip">
        ${ic('directions_walk')}
        <span class="grow"><span class="t">${live(leaveAt, 'leave')}</span><span class="s">${prefs.walk} min walk to the platform</span></span>
        <button class="strip-btn ${on ? 'on' : ''}" data-act="remind" data-dep="${j.dep}" aria-pressed="${on}" aria-label="Remind me when to leave">${ic('notifications', on ? 'fill' : '')}</button>
      </div>
    </div>
    <span class="section-title">Steps</span>
    <div class="card list">${steps.join('')}</div>`;
}

/* ----- ride ----- */

function rideStops(ride) {
  const out = [];
  ride.legs.forEach((leg, li) => {
    leg.stops.forEach((s, si) => {
      if (li > 0 && si === 0) return;
      out.push({ ...s, line: leg.line, change: li < ride.legs.length - 1 && si === leg.stops.length - 1 });
    });
  });
  return out;
}

function rideScreen(now) {
  const ride = prefs.ride;
  if (!ride) return tripScreen();
  const stops = rideStops(ride);
  let idx = stops.findIndex((s) => s.at > now);
  if (idx === -1) idx = stops.length - 1;
  if (idx < 1) idx = 1;
  const next = stops[idx];
  const waiting = now < ride.dep;
  const done = stops.slice(1).filter((s) => s.at <= now).length;
  const left = stops.length - 1 - done;
  const segs = stops
    .slice(1)
    .map((s, i) => {
      const a = stops[i].at;
      if (now >= s.at) return '<span class="done"></span>';
      if (now > a) return `<span class="part" style="--p: ${Math.round(((now - a) / (s.at - a)) * 100)}%"></span>`;
      return '<span></span>';
    })
    .join('');
  const [n, u] = bigParts(ride.arr - now);
  const upcoming = waiting ? stops.slice(1, -1) : stops.slice(idx + 1, -1);
  const shown = upcoming.slice(0, 3);
  const first = ride.legs[0];

  const nextLine = waiting
    ? `Departs ${esc(name(first.from))}`
    : next.change
      ? `Change at ${esc(name(next.s))}`
      : `Next: ${esc(name(next.s))}`;

  return `
    ${topbar(
      `<button class="round pill" data-act="end-ride">End trip</button>`,
      `<button class="round" data-act="back" aria-label="Minimise">${ic('keyboard_arrow_down')}</button>`,
    )}
    <div class="title"><h1>On board</h1><span class="sub">${esc(lineName(first.line))} toward ${esc(name(first.dest))}</span></div>
    <div class="card hero">
      <div class="hero-head">
        <span class="badge-icon">${ic('flag')}</span>
        <span class="grow"><span class="t">Get off at ${esc(name(ride.legs.at(-1).to))}</span><span class="s">${plural(left, 'stop')} to go</span></span>
      </div>
      <div class="big-row">
        <div class="big"><span class="n" data-at="${ride.arr}" data-fmt="big">${n}</span><span class="u" data-at="${ride.arr}" data-fmt="bigUnit">${u}</span></div>
        <div class="big-side"><span class="t">${clock(ride.arr)}</span><span class="s">arrival</span></div>
      </div>
      <div class="stops-progress" style="grid-template-columns: repeat(${stops.length - 1}, minmax(0, 1fr))" role="img" aria-label="${done} of ${stops.length - 1} stops done">${segs}</div>
      <div class="strip info">
        ${ic(waiting ? 'schedule' : next.change ? 'transfer_within_a_station' : 'arrow_downward')}
        <span class="grow"><span class="t">${nextLine}</span></span>
        <span class="t">${live(waiting ? ride.dep : next.at, 'in')}</span>
      </div>
    </div>
    ${
      shown.length
        ? `<span class="section-title">Coming up</span>
      <div class="card list">
        ${shown
          .map(
            (s) => `<div class="row plain"><span class="grow"><span class="t">${esc(name(s.s))}</span></span>${
              s.change ? `<span class="pill-tag ${s.line}">Change</span>` : ''
            }<span class="v light">${clock(s.at)}</span></div>`,
          )
          .join('')}
        ${upcoming.length > 3 ? `<div class="row plain"><span class="grow"><span class="t sub">${plural(upcoming.length - 3, 'more stop')}</span></span></div>` : ''}
      </div>`
        : ''
    }`;
}

/* ----- settings ----- */

function settingsScreen() {
  const now = Date.now();
  const key = serviceDateKey(now);
  const auto = schedule.today(now);
  const override = prefs.override?.date === key ? prefs.override.type : 'auto';
  const autoName = { weekday: 'Weekday', saturday: 'Saturday', sunday: 'Holiday' }[auto.type];
  const select = (act, label, value, options) => `
    <span class="select-wrap"><select data-act="${act}" aria-label="${label}">${options
      .map(([v, l]) => `<option value="${v}" ${v === value ? 'selected' : ''}>${l}</option>`)
      .join('')}</select>${ic('unfold_more')}</span>`;
  const theme = (v, label, a, b) => `
    <button class="theme-opt" role="radio" aria-checked="${prefs.theme === v}" data-act="theme" data-v="${v}">
      <span class="pv" style="background: ${a}"><i style="background: ${b}"></i></span>${label}
    </button>`;
  return `
    ${topbar(`<button class="round" data-act="back" aria-label="Done" style="background: var(--tint); color: #fff; border-color: transparent">${ic('check')}</button>`)}
    <div class="title"><h1>Settings</h1></div>

    <div class="card list">
      <div class="row icon">
        <span class="icon-tile" style="background: var(--tint)">${ic('calendar_month')}</span>
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

    <div class="card list">
      <div class="row icon" style="padding-right: 8px">
        <span class="icon-tile" style="background: #ff9500">${ic('directions_walk')}</span>
        <span class="grow"><span class="t">Walk to platform</span></span>
        <span class="stepper"><button data-act="walk" data-d="-1" aria-label="Less">−</button><span class="v" id="walk-v">${prefs.walk} min</span><button data-act="walk" data-d="1" aria-label="More">+</button></span>
      </div>
      <div class="row icon">
        <span class="icon-tile" style="background: #ff3b30">${ic('notifications')}</span>
        <span class="grow"><span class="t">Leave reminder</span></span>
        ${select('lead', 'Leave reminder', String(prefs.remindLead), [
          ['0', 'On time'],
          ['1', '1 min early'],
          ['2', '2 min early'],
          ['5', '5 min early'],
        ])}
      </div>
      <div class="row icon" style="padding-right: 4px">
        <span class="icon-tile" style="background: #5856d6">${ic('bedtime')}</span>
        <span class="grow"><span class="t">Last train warning</span></span>
        <button class="switch" role="switch" aria-checked="${prefs.lastWarn}" data-act="last-warn" aria-label="Last train warning"><i></i></button>
      </div>
    </div>
    <p class="footnote">Trains you can't reach in time are skipped. The warning shows in the last hour before your last train.</p>

    <div class="card hero" style="gap: 12px">
      <span>Appearance</span>
      <div class="themes" role="radiogroup" aria-label="Appearance">
        ${theme('light', 'Light', '#f2f2f7', '#ffffff')}
        ${theme('dark', 'Dark', '#0b0b0d', '#2c2c2e')}
        ${theme('auto', 'Automatic', 'linear-gradient(90deg, #f2f2f7 0 50%, #0b0b0d 50%)', 'linear-gradient(90deg, #ffffff 0 50%, #2c2c2e 50%)')}
      </div>
    </div>

    <div class="card list">
      <a class="row icon tap" href="${TIMETABLE_SOURCE.url}" target="_blank" rel="noopener" style="color: inherit; text-decoration: none">
        <span class="icon-tile" style="background: #8e8e93">${ic('description')}</span>
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
  const mode = ['trip', 'ride', 'settings'].includes(screen) ? screen : 'tabs';
  if (mode !== dockMode) {
    dockMode = mode;
    if (mode === 'trip') {
      dock.innerHTML = `
        <button class="primary" data-act="start-ride">${ic('navigation')}Start trip</button>
        <button class="orb glass" data-act="later" aria-label="Show the next train">${ic('schedule')}</button>`;
    } else if (mode === 'ride') {
      dock.innerHTML = `
        <div class="bar-row glass">
          ${ic('notifications_active', 'fill')}
          <label for="ride-alert">Alert one stop before</label>
          <button class="switch" id="ride-alert" role="switch" aria-checked="${prefs.rideAlert}" data-act="ride-alert"><i></i></button>
        </div>`;
    } else if (mode === 'settings') {
      dock.innerHTML = '';
    } else {
      dock.innerHTML = `
        <nav class="tabs glass" aria-label="Sections">
          <span class="tab-pill"></span>
          <a class="tab" href="#now" data-tab="now">${ic('schedule')}Now</a>
          <a class="tab" href="#trips" data-tab="trips">${ic('route')}Trips</a>
          <a class="tab" href="#lines" data-tab="lines">${ic('map')}Lines</a>
        </nav>
        <button class="orb glass" data-act="new-trip" aria-label="Search stations">${ic('search')}</button>`;
    }
  }
  if (mode === 'tabs') {
    const i = Math.max(0, ['now', 'trips', 'lines'].indexOf(nav.tab));
    dock.querySelector('.tab-pill').style.transform = `translateX(${i * 100}%)`;
    dock.querySelectorAll('.tab').forEach((t) => {
      if (t.dataset.tab === nav.tab) t.setAttribute('aria-current', 'page');
      else t.removeAttribute('aria-current');
    });
  }
}

/* ---------- navigation + render ---------- */

const nav = { tab: 'now', stack: [] };
const screenNow = () => nav.stack.at(-1) ?? nav.tab;

let lastKey = '';
let lastRender = 0;

function build(now) {
  const screen = screenNow();
  model = null;
  if (screen === 'now') {
    model = computeNow(now);
    return nowScreen(model);
  }
  if (screen === 'trips') return tripsScreen();
  if (screen === 'lines') return linesScreen();
  if (screen === 'trip') return tripScreen();
  if (screen === 'ride') return rideScreen(now);
  if (screen === 'settings') return settingsScreen();
  return '';
}

// Live countdown text is excluded from the diff, so a ticking clock never rebuilds the DOM.
const stableKey = (html) => html.replace(/(data-at="\d+" data-fmt="\w+">)[^<]*/g, '$1').replace(/--p: \d+%/g, '');

function render({ force = false, animate = false } = {}) {
  const now = Date.now();
  const html = build(now);
  const key = `${screenNow()}|${stableKey(html)}`;
  lastRender = now;
  renderDock(screenNow());
  if (!force && key === lastKey) return;
  lastKey = key;
  const focused = document.activeElement?.id;
  view.innerHTML = html;
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
  const now = Date.now();
  const screen = screenNow();
  if (
    now - lastRender > 30_000 ||
    (screen === 'ride' && now - lastRender > 5_000) ||
    (model?.j && now >= model.j.dep)
  ) {
    render();
  }
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
  checkReminder(now);
  if (prefs.ride) rideAlerts(now);
}

function startClock() {
  tick();
  setTimeout(() => {
    tick();
    setInterval(tick, 1000);
  }, 1000 - (Date.now() % 1000));
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
  const r = prefs.reminder;
  if (!r || r.fired || now < r.at) return;
  save({ reminder: { ...r, fired: true } });
  notify(prefs.walk ? 'Time to leave' : 'Train soon', `${clock(r.dep)} from ${name(r.from)} to ${name(r.to)}`);
  render({ force: true });
}

async function toggleReminder(dep) {
  if (reminderOn(dep)) {
    save({ reminder: null });
    toast('Reminder off');
    render({ force: true });
    return;
  }
  if ('Notification' in window && Notification.permission === 'default') {
    try {
      await Notification.requestPermission();
    } catch {}
  }
  const at = Math.max(Date.now() + 5000, dep - prefs.walk * MIN - prefs.remindLead * MIN);
  save({ reminder: { at, dep, from: prefs.from, to: prefs.to, fired: false } });
  toast(`Reminder set for ${clock(at)}. Keep Headway open.`);
  render({ force: true });
}

function rideAlerts(now) {
  const ride = prefs.ride;
  if (now > ride.arr + 3 * MIN) {
    save({ ride: null });
    if (screenNow() === 'ride') back();
    else render({ force: true });
    return;
  }
  if (!prefs.rideAlert) return;
  for (const leg of ride.legs) {
    const at = leg.stops.at(-1).at;
    const key = `${leg.to}@${at}`;
    if (ride.alerted?.includes(key) || at <= now || at - now > 2.5 * MIN) continue;
    save({ ride: { ...ride, alerted: [...(ride.alerted ?? []), key] } });
    const final = leg === ride.legs.at(-1);
    notify(final ? 'Your stop is next' : 'Change coming up', `${name(leg.to)} at ${clock(at)}`);
  }
}

/* ---------- trips ---------- */

function setTrip(from, to) {
  const recents = [{ from, to }, ...prefs.recents.filter((t) => !(t.from === from && t.to === to))].slice(0, 10);
  save({ from, to, recents, reminder: null });
  lastCache.clear();
}

function startRide() {
  const j = selectedJourney();
  if (!j) return;
  buzz(14);
  save({
    ride: {
      dep: j.dep,
      arr: j.arr,
      legs: j.legs.map((l) => ({
        from: l.from,
        to: l.to,
        line: l.trip.line,
        dest: l.trip.dest,
        stops: l.stops.map((s) => ({ s: s.s, at: s.at })),
      })),
      alerted: [],
    },
    reminder: null,
  });
  history.replaceState({ screen: 'ride' }, '');
  transition('push', () => {
    nav.stack[nav.stack.length - 1] = 'ride';
  });
}

/* ---------- picker sheet ---------- */

let sheetOpen = false;

function openPicker(field) {
  const asking = field === 'from' || !prefs.from ? 'from' : 'to';
  const origin = asking === 'to' ? prefs.from : null;
  const title = asking === 'from' ? 'Starting from' : 'Going to';
  sheetRoot.innerHTML = `
    <div class="scrim"></div>
    <div class="sheet" role="dialog" aria-modal="true" aria-label="${title}">
      <div class="grab"><i></i></div>
      <div class="sheet-head">
        <button class="round" data-act="close-sheet" aria-label="Close">${ic('close')}</button>
        <div class="mid"><span class="t">${title}</span>${origin ? `<span class="s">from ${esc(name(origin))}</span>` : ''}</div>
        <span></span>
      </div>
      <label class="search">${ic('search')}<input type="search" placeholder="Search stations" autocomplete="off" enterkeyhint="search" aria-label="Search stations"></label>
      <div class="sheet-body"></div>
    </div>`;
  const scrim = sheetRoot.querySelector('.scrim');
  const sheet = sheetRoot.querySelector('.sheet');
  const body = sheet.querySelector('.sheet-body');
  const input = sheet.querySelector('input');
  sheetOpen = true;
  history.pushState({ sheet: true }, '');
  requestAnimationFrame(() => {
    scrim.classList.add('open');
    sheet.classList.add('open');
  });
  scrim.addEventListener('click', () => history.back());
  dragToDismiss(sheet);

  const now = Date.now();
  const plans = new Map();
  const planTo = (id) => {
    if (!origin || id === origin) return null;
    if (!plans.has(id)) plans.set(id, schedule.plan(origin, id, now + prefs.walk * MIN));
    return plans.get(id);
  };
  const current = asking === 'from' ? prefs.from : prefs.to;
  const stationRow = (id, line) => {
    const st = STATIONS[id];
    const j = planTo(id);
    const tags = st.lines
      .filter((l) => l !== line)
      .map((l) => `<span class="pill-tag ${l}">${LINES[l].name}</span>`)
      .join('');
    const sub = j ? changesText(j) : st.long ?? '';
    const right = j
      ? `<span class="v light">${span(j.arr - j.dep)}</span>`
      : id === origin
        ? '<span class="v light">Starting here</span>'
        : '';
    return `
      <button class="row tap plain" data-pick="${id}" data-search="${esc(`${st.name} ${st.long ?? ''}`.toLowerCase())}">
        <span class="grow"><span class="t ${id === current ? 'strong' : ''}" ${id === current ? 'style="color: var(--tint)"' : ''}>${esc(st.name)}</span>${
          sub ? `<span class="s">${esc(sub)}</span>` : ''
        }</span>
        ${tags}${right}
      </button>`;
  };

  const recentIds = [...new Set(prefs.recents.map((t) => (asking === 'from' ? t.from : t.to)))]
    .filter((id) => STATIONS[id] && id !== origin)
    .slice(0, 4);
  const shortcuts = [
    { id: 'central', icon: 'apartment', label: 'Central' },
    { id: 'airport', icon: 'flight', label: 'Airport' },
  ]
    .filter((s) => s.id !== origin)
    .map((s) => {
      const j = planTo(s.id);
      return `<button class="shortcut" data-pick="${s.id}"><span class="orb">${ic(s.icon, 'fill')}</span><span class="grow"><span class="t">${s.label}</span><span class="s">${
        j ? span(j.arr - j.dep) : esc(name(s.id))
      }</span></span></button>`;
    })
    .join('');

  // Build the long list a frame later so the sheet starts moving immediately.
  body.innerHTML = `<div class="shortcuts" data-block>${shortcuts}</div>`;
  setTimeout(() => {
    body.insertAdjacentHTML(
      'beforeend',
      `${
        recentIds.length
          ? `<div data-block style="display: contents"><span class="section-title">Recent</span><div class="card list">${recentIds
              .map((id) => stationRow(id, STATIONS[id].lines[0]))
              .join('')}</div></div>`
          : ''
      }
      ${PICKER_GROUPS.map(
        (g) => `
        <div class="group" style="display: flex; flex-direction: column; gap: 14px">
          <span class="section-title">${LINES[g.line].name}</span>
          <div class="card list">${g.stations.map((id) => stationRow(id, g.line)).join('')}</div>
        </div>`,
      ).join('')}`,
    );
  }, 60);

  input.addEventListener('input', () => {
    const q = input.value.trim().toLowerCase();
    body.querySelectorAll('[data-block]').forEach((el) => (el.hidden = !!q));
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

  sheet.addEventListener('click', (e) => {
    const el = e.target.closest('[data-pick]');
    if (!el) return;
    buzz();
    const id = el.dataset.pick;
    history.back();
    if (asking === 'from') {
      const to = prefs.to === id ? prefs.from : prefs.to;
      if (to) {
        setTrip(id, to);
        afterTripChange();
      } else {
        save({ from: id });
        render({ force: true });
        setTimeout(() => openPicker('to'), 450);
      }
    } else {
      setTrip(prefs.from, id);
      afterTripChange();
    }
  });
}

function afterTripChange() {
  if (nav.tab !== 'now' || nav.stack.length) switchTab('now');
  else render({ force: true, animate: true });
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

const ACTIONS = {
  pick: (el) => openPicker(el.dataset.field),
  'new-trip': () => openPicker('to'),
  quick: (el) => {
    if (!prefs.from) {
      save({ to: el.dataset.id });
      openPicker('from');
    } else {
      setTrip(prefs.from, el.dataset.id);
      render({ force: true, animate: true });
    }
  },
  settings: () => push('settings'),
  back: () => back(),
  'close-sheet': () => history.back(),
  trip: (el) => {
    tripDep = Number(el.dataset.dep);
    push('trip');
  },
  later: () => {
    const js = tripJourneys();
    const i = js.findIndex((x) => x.dep === tripDep);
    tripDep = js[(i + 1) % js.length]?.dep ?? null;
    buzz();
    render({ force: true });
    if (tripDep) toast(`Showing the ${clock(tripDep)}`);
  },
  remind: (el) => {
    buzz(14);
    const dep = Number(el.dataset.dep);
    if (dep) toggleReminder(dep);
  },
  'save-trip': () => {
    buzz();
    const exists = prefs.saved.some((t) => t.from === prefs.from && t.to === prefs.to);
    save({
      saved: exists
        ? prefs.saved.filter((t) => !(t.from === prefs.from && t.to === prefs.to))
        : [...prefs.saved, { from: prefs.from, to: prefs.to }],
    });
    toast(exists ? 'Removed from saved trips' : 'Saved to Trips');
    render({ force: true });
  },
  'use-trip': (el) => {
    setTrip(el.dataset.from, el.dataset.to);
    switchTab('now');
  },
  'start-ride': () => startRide(),
  'open-ride': () => push('ride'),
  'end-ride': () => {
    save({ ride: null });
    back();
  },
  'ride-alert': (el) => {
    save({ rideAlert: !prefs.rideAlert });
    el.setAttribute('aria-checked', String(prefs.rideAlert));
    buzz();
  },
  'last-warn': (el) => {
    save({ lastWarn: !prefs.lastWarn });
    el.setAttribute('aria-checked', String(prefs.lastWarn));
    buzz();
  },
  walk: (el) => {
    buzz();
    save({ walk: Math.max(0, Math.min(30, prefs.walk + Number(el.dataset.d))), reminder: null });
    document.getElementById('walk-v').textContent = `${prefs.walk} min`;
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
    const key = serviceDateKey(Date.now());
    save({ override: el.value === 'auto' ? null : { date: key, type: el.value } });
    refreshSchedule();
  } else if (el.dataset.act === 'lead') {
    save({ remindLead: Number(el.value) });
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
if (prefs.ride && prefs.ride.arr + 3 * MIN > Date.now()) {
  nav.stack = ['ride'];
  history.pushState({ screen: 'ride' }, '');
} else if (prefs.ride) {
  save({ ride: null });
}
render({ force: true, animate: true });
startClock();
feed.start();
document.addEventListener('visibilitychange', () => document.visibilityState === 'visible' && render({ force: true }));

// Skipped on localhost so a cache-first worker never serves stale files during development.
if ('serviceWorker' in navigator && location.protocol === 'https:') {
  navigator.serviceWorker.register('sw.js').catch(() => {});
}
