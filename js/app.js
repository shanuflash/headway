import { Schedule, serviceDateKey, bandAt, hm } from './engine.js';
import { STATIONS, ROUTES, LINES, PICKER_GROUPS, directDestinations } from './network.js';
import { TIMETABLE, TIMETABLE_SOURCE } from './timetable.js';
import { createFeed, activeNotices } from './live.js';

const MIN = 60_000;
const STORE = 'headway:v3';
const COUNT_CHOICES = [3, 5, 8];
const LEAD_CHOICES = [2, 5, 10, 15];
const view = document.getElementById('view');
const dock = document.getElementById('dock');
const sheetRoot = document.getElementById('sheet-root');
const toastEl = document.getElementById('toast');
const reduceMotion = matchMedia('(prefers-reduced-motion: reduce)');

/* ---------- preferences ---------- */

const DEFAULTS = {
  routes: [],
  count: 5,
  remindLead: 5,
  lastWarn: true,
  theme: 'auto',
  override: null,
  feedUrl: '',
  reminder: null,
};

const routeId = (from, to) => `${from}>${to}`;

function load() {
  try {
    const saved = JSON.parse(localStorage.getItem(STORE) || 'null');
    if (saved) {
      const p = { ...DEFAULTS, ...saved };
      if (!COUNT_CHOICES.includes(p.count)) p.count = DEFAULTS.count;
      if (!LEAD_CHOICES.includes(p.remindLead)) p.remindLead = DEFAULTS.remindLead;
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
  // Floors like the hero number, so "in 7 min" and a big "7" always agree.
  in: (ms) => (ms < MIN ? 'now' : ms < 60 * MIN ? `in ${mins(ms)} min` : `in ${span(ms)}`),
};
const live = (at, fmt) => `<span data-at="${at}" data-fmt="${fmt}">${FORMATS[fmt](at - Date.now())}</span>`;

const lineName = (line) => LINES[line].name;

function trainNote(trip) {
  if (trip.route === 'loop') return 'short loop';
  if (trip.route === 'inter') return 'via CMBT';
  return '';
}

/** "Blue Line toward Wimco Nagar Depot", from the routes that serve this pair. */
function routeLabel(r) {
  const groups = directDestinations(r.from);
  const g = groups.find((x) => x.stations.some((s) => s.id === r.to));
  if (!g) return '';
  return `${lineName(g.line)} toward ${g.terminals.map(name).join(' or ')}`;
}

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
      <button class="round" data-act="add-route" aria-label="Add a route">${ic('add')}</button>
      <button class="round" data-act="settings" aria-label="Settings">${ic('more_horiz')}</button>`)}
    <div class="title"><h1>Routes</h1></div>
    ${notices}`;

  const cards = [];
  if (preview) cards.push(routeCard(routeModel(preview, now), true));
  if (!prefs.routes.length && !preview) return head + emptyState();
  for (const r of prefs.routes) cards.push(routeCard(routeModel(r, now), false));
  return head + cards.join('');
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

function routeCard(m, isPreview) {
  const { r } = m;
  const line = routeLine(r);
  const head = `
    <div class="route-head">
      <span class="bar ${line}"></span>
      <span class="grow">
        <span class="t">${esc(name(r.from))} → ${esc(name(r.to))}</span>
        <span class="s">${esc(routeLabel(r))}</span>
      </span>
      <button class="mini" data-act="swap" data-route="${r.id}" aria-label="Switch direction">${ic('swap_vert')}</button>
      ${
        isPreview
          ? `<button class="mini" data-act="close-preview" aria-label="Close quick look">${ic('close')}</button>`
          : `<button class="mini" data-act="route-menu" data-route="${r.id}" aria-label="Route options">${ic('more_horiz')}</button>`
      }
    </div>`;

  let body = '';
  if (m.kind === 'none') {
    body = `<div class="sub" style="font-size: 15px">No direct trains between these stations.</div>`;
  } else {
    const closed = m.kind === 'closed';
    const label = closed
      ? m.running
        ? 'No more trains tonight'
        : 'Metro is closed'
      : m.late && m.remaining <= 2
        ? m.remaining === 1
          ? 'Last train'
          : 'Last 2 trains'
        : 'Next train';
    const [n, u] = bigParts(m.next.dep - m.now);
    const on = reminderOn(r, m.next.dep);
    const big = closed
      ? `<div class="big clock"><span class="n">${clock(m.next.dep)}</span></div>`
      : `<div class="big"><span class="n" data-at="${m.next.dep}" data-fmt="big">${n}</span><span class="u" data-at="${m.next.dep}" data-fmt="bigUnit">${u}</span></div>`;
    const note = trainNote(m.next.trip);
    const rows = m.deps.slice(1).map((d, i) => {
      const index = i + 1;
      const extra = trainNote(d.trip);
      const isLast = m.last && d.dep === m.last.dep;
      const isTomorrow = m.tomorrowFrom >= 0 && index >= m.tomorrowFrom;
      const divider = !closed && index === m.tomorrowFrom ? '<div class="day-break">Tomorrow</div>' : '';
      return `${divider}<div class="train-row"><span class="time">${clock(d.dep)}</span><span class="note ${isLast ? 'warn' : ''}">${
        isLast ? 'last train, ' : extra ? `${extra}, ` : ''
      }arrives ${clock(d.arr)}</span><span class="right">${isTomorrow ? '' : live(d.dep, 'in')}</span></div>`;
    });
    const tomorrow = m.tomorrowFrom > 0 ? m.deps[m.tomorrowFrom] : null;
    const footnote =
      m.late && m.last && tomorrow
        ? `<div class="note">${ic('bedtime')}<span>The ${clock(m.last.dep)} is the last train tonight. The first one tomorrow is at ${clock(tomorrow.dep)}.</span></div>`
        : '';
    body = `
      <div class="big-row">
        <div class="catch">
          <span class="label ${closed || m.late ? 'warn' : 'tint'}">${label}</span>
          ${big}
        </div>
        <div class="big-side"><span class="t">${closed ? 'first train' : clock(m.next.dep)}</span><span class="s">${
          note ? `${note}, ` : ''
        }arrives ${clock(m.next.arr)}</span></div>
        <button class="bell ${on ? 'on' : ''}" data-act="remind" data-route="${r.id}" data-dep="${m.next.dep}" aria-pressed="${on}" aria-label="Remind me before this train">${ic(
          'notifications',
          on ? 'fill' : '',
        )}</button>
      </div>
      ${rows.length ? `<div class="train-list">${rows.join('')}</div>` : ''}
      ${footnote}`;
  }

  return `
    <div class="card hero route">
      ${head}
      ${body}
      ${isPreview ? `<button class="cta" data-act="save-preview">${ic('add')}Save this route</button>` : ''}
    </div>`;
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
      <div class="row icon">
        <span class="icon-tile" style="background: #ff3b30">${ic('notifications')}</span>
        <span class="grow"><span class="t">Remind me before a train</span></span>
        ${select('lead', 'Remind me before a train', String(prefs.remindLead), LEAD_CHOICES.map((m) => [String(m), `${m} min`]))}
      </div>
      <div class="row icon">
        <span class="icon-tile" style="background: #34c759">${ic('schedule')}</span>
        <span class="grow"><span class="t">Departures shown</span></span>
        ${select('count', 'Departures shown', String(prefs.count), COUNT_CHOICES.map((n) => [String(n), String(n)]))}
      </div>
      <div class="row icon" style="padding-right: 4px">
        <span class="icon-tile" style="background: #5856d6">${ic('bedtime')}</span>
        <span class="grow"><span class="t">Last train warning</span></span>
        <button class="switch" role="switch" aria-checked="${prefs.lastWarn}" data-act="last-warn" aria-label="Last train warning"><i></i></button>
      </div>
    </div>
    <p class="footnote">Reminders go off this long before the train leaves. The last-train warning shows in the final hour of service.</p>

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
  const mode = screen === 'settings' ? 'none' : 'tabs';
  if (mode !== dockMode) {
    dockMode = mode;
    dock.innerHTML =
      mode === 'none'
        ? ''
        : `
      <nav class="tabs glass two" aria-label="Sections">
        <span class="tab-pill"></span>
        <a class="tab" href="#routes" data-tab="routes">${ic('commute')}Routes</a>
        <a class="tab" href="#lines" data-tab="lines">${ic('map')}Lines</a>
      </nav>
      <button class="orb glass" data-act="look" aria-label="Check any station">${ic('search')}</button>`;
  }
  if (mode === 'tabs') {
    const i = nav.tab === 'lines' ? 1 : 0;
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
  if (screen === 'settings') return settingsScreen();
  return '';
}

// Live countdown text is excluded from the diff, so a ticking clock never rebuilds the DOM.
const stableKey = (html) => html.replace(/(data-at="\d+" data-fmt="\w+">)[^<]*/g, '$1');

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
  checkReminder(now);
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
  const rem = prefs.reminder;
  if (!rem || rem.fired || now < rem.at) return;
  save({ reminder: { ...rem, fired: true } });
  const r = prefs.routes.find((x) => x.id === rem.routeId);
  notify(`Catch the ${clock(rem.dep)}`, r ? `${name(r.from)} to ${name(r.to)}. Head to the platform now.` : 'Head to the platform now.');
  render({ force: true });
}

async function toggleReminder(r, dep) {
  if (reminderOn(r, dep)) {
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
  const at = Math.max(Date.now() + 5000, dep - prefs.remindLead * MIN);
  save({ reminder: { routeId: r.id, dep, at, fired: false } });
  toast(`Reminder set for ${clock(at)}. Keep Headway open.`);
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
  remind: (el) => {
    buzz(14);
    const r = routeOf(el);
    if (r && r !== preview) toggleReminder(r, Number(el.dataset.dep));
    else if (r) toast('Save the route to set a reminder');
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
    const key = serviceDateKey(Date.now());
    save({ override: el.value === 'auto' ? null : { date: key, type: el.value } });
    refreshSchedule();
  } else if (el.dataset.act === 'lead') {
    save({ remindLead: Number(el.value) });
  } else if (el.dataset.act === 'count') {
    save({ count: Number(el.value) });
    render({ force: true });
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
