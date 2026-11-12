// Turns what CMRL publishes into Headway's advisory feed (advisories.json; its shape is in
// js/live.js). Pure functions only, so they can be tested without the network; the runner in
// tools/advisories.mjs does the fetching and writing.
//
// What it does with each source:
// - Posts (news, press releases, announcements): service-related ones become notices. Clear-cut
//   ones also become timetable adjustments: "services extended till 12 midnight on 15.11.2026"
//   and "Sunday timetable on <date>".
// - The timetable PDF: when its bytes change, a notice says so (and the runner opens an issue,
//   because the times themselves are encoded in js/timetable.js and need a person to update).
//
// Everything it adds is marked `auto: true`. Entries without it were written by hand and are
// left alone.

const DAY = 24 * 3600_000;

/** How long a notice from a post stays up when the post names no date of its own. */
export const NOTICE_DAYS = 3;
/** Posts older than this are not looked at (so an old post doesn't come back as new). */
export const LOOKBACK_DAYS = 10;

const RELEVANT =
  /\b(trains?|train services?|metro (rail )?services?|services will|timings?|time ?table|extended|extension of (metro )?services|frequency|holiday|sunday (time ?table|timings?)|disrupt\w*|suspend\w*|delay\w*|closed|closure|technical (glitch|issue|snag|fault)|maintenance work|shuttle|first train|last train)\b/i;
const IRRELEVANT =
  /\b(tenders?|recruit\w*|careers?|vacanc\w*|corrigendum|e-?auction|awards?|csr|bids?|rfp|eoi|walk-?in|results?|examination|interview|empanelment|quotation|procurement|contract|deadline|applications?|registration)\b/i;
/** A title that is plainly about trains running (or not): relevant whatever else it mentions. */
const SERVICE_TITLE =
  /\b(trains?|train services?|metro (rail )?services?|services)\b[^.]*\b(delay\w*|suspend\w*|extend\w*|resum\w*|disrupt\w*|cancel\w*|halted|stopped|curtail\w*|short[- ]terminat\w*)\b|\b(delay\w*|suspend\w*|extend\w*|disrupt\w*|cancel\w*|halted|stopped)\b[^.]*\b(trains?|services?)\b/i;
const ALERT = /\b(suspend\w*|disrupt\w*|cancel\w*|closed|closure|not (be )?(available|operational)|technical (glitch|issue|snag|fault)|halted|stopped)\b/i;
const WARN = /\b(extend\w*|delay\w*|holiday|sunday (time ?table|timings?|schedule)|revised|maintenance|divert\w*|reduced|change in|modified)\b/i;

const MONTHS = ['jan', 'feb', 'mar', 'apr', 'may', 'jun', 'jul', 'aug', 'sep', 'oct', 'nov', 'dec'];

const pad = (n) => String(n).padStart(2, '0');
const isoDate = (y, m, d) => `${y}-${pad(m)}-${pad(d)}`;

/** Plain text from WordPress HTML: tags out, common entities decoded, whitespace squeezed. */
export function strip(html) {
  return String(html ?? '')
    .replace(/<(script|style)[\s\S]*?<\/\1>/gi, ' ')
    .replace(/<br\s*\/?>|<\/p>|<\/li>/gi, '\n')
    .replace(/<[^>]+>/g, ' ')
    .replace(/&#(\d+);/g, (_, n) => String.fromCodePoint(Number(n)))
    .replace(/&#x([0-9a-f]+);/gi, (_, n) => String.fromCodePoint(parseInt(n, 16)))
    .replace(/&nbsp;/g, ' ')
    .replace(/&amp;/g, '&')
    .replace(/&quot;/g, '"')
    .replace(/&(rsquo|lsquo|apos);/g, "'")
    .replace(/&(rdquo|ldquo);/g, '"')
    .replace(/&(ndash|mdash);/g, '-')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/[ \t\r\f\v]+/g, ' ')
    .replace(/\s*\n\s*/g, '\n')
    .trim();
}

/** Lines a post is about, or null for all of them. Only explicit names count. */
export function linesIn(text) {
  const out = [];
  if (/\bblue line\b|\b(line|corridor)[- ]?(1|i)\b/i.test(text)) out.push('blue');
  if (/\bgreen line\b|\b(line|corridor)[- ]?(2|ii)\b/i.test(text)) out.push('green');
  if (/\byellow line\b|\b(line|corridor)[- ]?(4|iv)\b|poonamallee/i.test(text)) out.push('yellow');
  return out.length ? out : null;
}

/** Whether a post is about running trains, and how serious it is. */
export function classify({ title, text }) {
  const all = `${title}\n${text}`;
  // A disruption in the title always counts ("services suspended due to contract staff strike");
  // otherwise tenders, recruitment and the like are out, and the rest must be about trains.
  const relevant = ALERT.test(title) || SERVICE_TITLE.test(title) || (!IRRELEVANT.test(title) && (RELEVANT.test(title) || RELEVANT.test(all)));
  if (!relevant) return { relevant: false };
  const level = ALERT.test(all) ? 'alert' : WARN.test(all) ? 'warn' : 'info';
  return { relevant: true, level, lines: linesIn(all) };
}

/**
 * Dates a post mentions, as YYYY-MM-DD. Understands 15.11.2026, 15/11/2026, 15-11-2026,
 * "15th November 2026", "November 15, 2026" and, without a year, the next such date from the
 * post (an announcement is about something coming up).
 */
export function datesIn(text, postIso) {
  const post = new Date(postIso);
  const out = new Set();
  const add = (y, m, d) => {
    if (m < 1 || m > 12 || d < 1 || d > 31) return;
    if (!y) {
      // No year: the one that puts the date nearest the post ("5 January" in a December post is
      // next year; "31 December" in a January post is last year).
      const py = post.getUTCFullYear();
      y = [py - 1, py, py + 1].reduce((best, c) =>
        Math.abs(Date.UTC(c, m - 1, d) - post.getTime()) < Math.abs(Date.UTC(best, m - 1, d) - post.getTime()) ? c : best,
      );
    }
    if (y < 100) y += 2000;
    out.add(isoDate(y, m, d));
  };
  for (const [, d, m, y] of text.matchAll(/\b(\d{1,2})[./-](\d{1,2})[./-](\d{2,4})\b/g)) add(Number(y), Number(m), Number(d));
  // Whole words only ("Gate 3 marked" is not 3 March). "may" is a month only with a year, an
  // ordinal or a capital M ("Line 2 may be affected" is not 2 May).
  const month = '(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)\\b';
  const real = (mo, ord, y) => mo.toLowerCase() !== 'may' || Boolean(y || ord) || mo[0] === 'M';
  for (const [, d, ord, mo, y] of text.matchAll(new RegExp(`\\b(\\d{1,2})(?!\\d)(st|nd|rd|th)?\\s+${month}\\.?,?\\s*(\\d{4})?`, 'gi'))) {
    if (real(mo, ord, y)) add(y ? Number(y) : 0, MONTHS.indexOf(mo.slice(0, 3).toLowerCase()) + 1, Number(d));
  }
  for (const [, mo, d, ord, y] of text.matchAll(new RegExp(`\\b${month}\\.?\\s+(\\d{1,2})(?!\\d)(st|nd|rd|th)?,?\\s*(\\d{4})?`, 'gi'))) {
    if (real(mo, ord, y)) add(y ? Number(y) : 0, MONTHS.indexOf(mo.slice(0, 3).toLowerCase()) + 1, Number(d));
  }
  return [...out].sort();
}

/**
 * The time services run until, as HH:MM: "till 12 midnight", "until 11 PM", "up to 23:30 hrs",
 * "till 11.30 p.m.". Midnight is 00:00 (the engine reads early hours as past midnight).
 */
export function untilTimeIn(text) {
  if (/\b(till|until|up ?to)\s+(12\s*)?midnight\b/i.test(text)) return '00:00';
  // Not part of a date ("till 23.11.2026"); of the rest, one marked am/pm/hrs beats a bare 23:30.
  const all = [...text.matchAll(/\b(?:till|until|up ?to)\s+(\d{1,2})(?:[.:](\d{2}))?(?![./-]?\d)\s*(a\.?\s?m\.?|p\.?\s?m\.?|hrs|hours)?/gi)];
  const m = all.find((x) => x[3]) ?? all.find((x) => x[2]);
  if (!m) return null;
  let h = Number(m[1]);
  const min = Number(m[2] ?? 0);
  const ap = (m[3] ?? '').toLowerCase().replace(/[^ap]/g, '');
  if (!ap && !m[2] && !/hrs|hours/i.test(m[3] ?? '')) return null; // a bare "till 11" is too vague
  if (ap === 'p' && h < 12) h += 12;
  if (ap === 'a' && h === 12) h = 0;
  if (h > 24 || min > 59) return null;
  return `${pad(h % 24)}:${pad(min)}`;
}

/** Timetable changes a post states plainly. Anything less clear stays a notice only. */
export function adjustmentsFrom(post) {
  const out = [];
  if (!classify(post).relevant) return out;
  const text = `${post.title}\n${post.text}`;
  const lines = linesIn(text);
  // Sentences, so a change applies only to the dates it's announced with ("Sunday timetable on
  // the 15th. Regular service resumes on the 16th" changes the 15th only).
  const sentences = text.split(/\n+|(?<=[.!?])\s+(?=[A-Z])/);
  const datesWith = (phrase) => {
    const own = sentences.filter((x) => phrase.test(x)).flatMap((x) => datesIn(x, post.date));
    if (own.length) return [...new Set(own)].sort();
    // The phrase is in the title and the date elsewhere: any date not about going back to normal.
    return [...new Set(sentences.filter((x) => !/\b(resum\w*|regular|normal|usual)\b/i.test(x)).flatMap((x) => datesIn(x, post.date)))].sort();
  };
  // Extended hours: "services will be extended till 12 midnight on 15.11.2026".
  if (/\bextend\w*\b/i.test(text) && /\b(trains?|services?|metro)\b/i.test(text)) {
    const until = untilTimeIn(text);
    const dates = datesWith(/\bextend\w*\b/i);
    const [h] = (until ?? '').split(':').map(Number);
    if (until && (h >= 21 || h < 3)) {
      const every = text.match(/\b(?:every|frequency of|interval of|headway of)\s+(\d{1,2})\s*min/i);
      for (const date of dates) {
        out.push({
          date,
          extendUntil: until,
          ...(every ? { headway: Number(every[1]) } : {}),
          ...(lines ? { lines } : {}),
          reason: post.title,
          auto: true,
          source: post.link,
        });
      }
    }
  }
  // Holiday running: "Sunday timetable will be followed on <date>".
  const holiday = /\b(sunday|holiday)\s+(time ?table|timings?|schedule)\b/i;
  if (holiday.test(text)) {
    for (const date of datesWith(holiday)) out.push({ date, dayType: 'sunday', reason: post.title, auto: true, source: post.link });
  }
  return out;
}

/** A notice for a service-related post, up until the last date it mentions (or a few days). */
export function noticeFrom(post) {
  const c = classify(post);
  if (!c.relevant) return null;
  const from = new Date(post.date).toISOString();
  const dates = datesIn(`${post.title}\n${post.text}`, post.date).filter((d) => d >= from.slice(0, 10));
  // Until the end of the last service day it names (3 AM the next morning, IST).
  const to = dates.length
    ? new Date(Date.parse(`${dates.at(-1)}T03:00:00+05:30`) + DAY).toISOString()
    : new Date(Date.parse(post.date) + NOTICE_DAYS * DAY).toISOString();
  const body = post.text.replace(/\s+/g, ' ').trim();
  return {
    id: `cmrl-${post.id}`,
    level: c.level,
    title: post.title.slice(0, 140),
    ...(body ? { body: body.length > 240 ? `${body.slice(0, 237).trimEnd()}...` : body } : {}),
    ...(c.lines ? { lines: c.lines } : {}),
    link: post.link,
    from,
    to,
    auto: true,
  };
}

/** WordPress REST posts, or RSS items, as { id, date, link, title, text }. */
export function postsFromWp(json) {
  if (!Array.isArray(json)) return [];
  return json.map((p) => ({
    id: String(p.id),
    date: p.date_gmt ? `${p.date_gmt}Z` : p.date,
    link: p.link,
    title: strip(p.title?.rendered ?? p.title),
    text: strip(p.content?.rendered ?? p.excerpt?.rendered ?? '').slice(0, 4000),
  }));
}

export function postsFromRss(xml) {
  const items = [];
  for (const [, item] of String(xml).matchAll(/<item\b[^>]*>([\s\S]*?)<\/item>/gi)) {
    const tag = (name) => {
      const m = item.match(new RegExp(`<${name}\\b[^>]*>([\\s\\S]*?)<\\/${name}>`, 'i'));
      return m ? m[1].replace(/^<!\[CDATA\[|\]\]>$/g, '').trim() : '';
    };
    const link = tag('link');
    const guid = tag('guid');
    const id = (guid.match(/[?&]p=(\d+)/) ?? link.match(/[?&]p=(\d+)/))?.[1] ?? link;
    const date = new Date(tag('pubDate'));
    if (Number.isNaN(date.getTime())) continue;
    items.push({ id: String(id), date: date.toISOString(), link, title: strip(tag('title')), text: strip(tag('content:encoded') || tag('description')).slice(0, 4000) });
  }
  return items;
}

/** The newest timetable PDF in the WordPress media library, if any. */
export function timetableFromMedia(json) {
  if (!Array.isArray(json)) return null;
  const pdfs = json
    .filter((m) => /\.pdf($|\?)/i.test(m.source_url ?? '') && /time[\s_-]*table/i.test(`${m.source_url} ${m.title?.rendered ?? ''}`))
    .sort((a, b) => String(b.modified_gmt ?? b.date).localeCompare(String(a.modified_gmt ?? a.date)));
  return pdfs[0]?.source_url ?? null;
}

const stable = (x) => JSON.stringify(x);

/**
 * The next feed from the last one and what was just read. `posts` and `timetable` are null when
 * that source couldn't be read: then what the feed already has from it is kept as is.
 * Returns { feed, changed, timetableChanged }.
 */
export function nextFeed(prev, { posts, timetable }, nowMs) {
  const feed = {
    updated: prev.updated ?? null,
    source: prev.source ?? 'Headway advisories',
    holidays: { ...(prev.holidays ?? {}) },
    notices: [...(prev.notices ?? [])],
    adjustments: [...(prev.adjustments ?? [])],
    ...(prev.watch ? { watch: prev.watch } : {}),
  };
  const now = new Date(nowMs);
  const today = now.toISOString().slice(0, 10);
  const live = (n) => !n.to || Date.parse(n.to) >= nowMs;

  // Posts: rebuild what came from posts still in the window, keep the rest of the auto entries
  // until they end (a post can drop out of the newest-N listing while still current).
  if (posts) {
    const recent = posts.filter((p) => Date.parse(p.date) >= nowMs - LOOKBACK_DAYS * DAY);
    const fromPosts = recent.map(noticeFrom).filter(Boolean);
    const ids = new Set(fromPosts.map((n) => n.id));
    feed.notices = [...feed.notices.filter((n) => !n.auto || (!ids.has(n.id) && live(n))), ...fromPosts];
    const adds = recent.flatMap(adjustmentsFrom);
    const sources = new Set(recent.map((p) => p.link));
    feed.adjustments = [
      ...feed.adjustments.filter((a) => !a.auto || (!sources.has(a.source) && (a.date ?? today) >= today)),
      ...adds.filter((a) => a.date >= today),
    ];
  } else {
    feed.notices = feed.notices.filter((n) => !n.auto || live(n));
    feed.adjustments = feed.adjustments.filter((a) => !a.auto || (a.date ?? today) >= today);
  }

  // Timetable PDF: new times when a PDF it has seen before changes, or a new one appears in the
  // media library. Fingerprints are kept per address, so reading the PDF one way on one run and
  // another way on the next doesn't look like a change.
  let timetableChanged = false;
  if (timetable) {
    const seen = prev.watch?.timetables ?? {};
    const was = seen[timetable.url];
    const newUpload = !was && Object.keys(seen).length > 0 && timetable.fromLibrary && !Object.values(seen).includes(timetable.sha256);
    if ((was && was !== timetable.sha256) || newUpload) {
      timetableChanged = true;
      feed.notices = feed.notices.filter((n) => !n.id.startsWith('timetable-'));
      feed.notices.push({
        id: `timetable-${timetable.sha256.slice(0, 8)}`,
        level: 'warn',
        title: 'CMRL has published a new timetable',
        body: 'Some train times may change. Headway will update to it soon; until then, check the timetable for first and last trains.',
        link: timetable.url,
        from: now.toISOString(),
        to: new Date(nowMs + 14 * DAY).toISOString(),
        auto: true,
      });
    }
    // The newest few addresses are enough.
    const rest = Object.entries(seen).filter(([url]) => url !== timetable.url);
    const kept = [...rest, [timetable.url, timetable.sha256]].slice(-5);
    feed.watch = { ...(feed.watch ?? {}), timetables: Object.fromEntries(kept) };
  }

  const before = stable({ h: prev.holidays ?? {}, n: prev.notices ?? [], a: prev.adjustments ?? [], w: prev.watch ?? null });
  const after = stable({ h: feed.holidays, n: feed.notices, a: feed.adjustments, w: feed.watch ?? null });
  const changed = before !== after;
  if (changed) feed.updated = now.toISOString();
  return { feed, changed, timetableChanged };
}
