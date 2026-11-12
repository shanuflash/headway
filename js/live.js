// Service advisories feed. CMRL publishes no machine-readable delay or disruption data, and
// ONDC (TRV11) carries tickets, not train timings, so "live" here means a small JSON feed of
// adjustments: the bundled advisories.json, optionally merged with a remote URL you control.
//
// Feed shape:
// {
//   "updated": ISO,
//   "holidays": { "YYYY-MM-DD": "Name" },
//   "notices": [{ "id", "level": "info|warn|alert", "title", "body", "lines": [], "from": ISO, "to": ISO }],
//   "adjustments": [
//     { "date": "YYYY-MM-DD", "dayType": "weekday|saturday|sunday", "reason" },
//     { "date": "YYYY-MM-DD", "extendUntil": "HH:MM", "headway": 15, "lines": [], "reason" },
//     { "from": ISO, "to": ISO, "delay": 5, "lines": [], "reason" }
//   ]
// }

const REFRESH_MS = 5 * 60_000;
const CACHE_KEY = 'headway:feed';

async function fetchJson(url) {
  const res = await fetch(url, { cache: 'no-cache' });
  if (!res.ok) throw new Error(`${res.status} ${url}`);
  return res.json();
}

/**
 * The always-current feed: advisories.json as committed on main, which a scheduled job keeps up
 * to date from CMRL (tools/advisories.mjs). The copy bundled with the site can lag behind it.
 */
export const LIVE_FEED_URL = 'https://raw.githubusercontent.com/shanuflash/headway/main/advisories.json';

function merge(feeds) {
  const out = { updated: null, holidays: {}, notices: [], adjustments: [], sources: [] };
  // The bundled and live copies are usually the same file: take each entry once, or a delay
  // would be applied twice.
  const seenNotices = new Set();
  const seenAdjustments = new Set();
  for (const f of feeds) {
    if (!f) continue;
    Object.assign(out.holidays, f.holidays);
    for (const n of f.notices ?? []) {
      const key = n.id ?? JSON.stringify(n);
      if (!seenNotices.has(key)) seenNotices.add(key) && out.notices.push(n);
    }
    for (const a of f.adjustments ?? []) {
      const { source, auto, ...rest } = a;
      const key = JSON.stringify(rest);
      if (!seenAdjustments.has(key)) seenAdjustments.add(key) && out.adjustments.push(a);
    }
    if (f.updated && (!out.updated || f.updated > out.updated)) out.updated = f.updated;
    if (f.source) out.sources.push(f.source);
  }
  return out;
}

export function createFeed({ remoteUrl, onChange }) {
  let timer = null;
  let state = { status: 'official', feed: readCache(), error: null };

  function readCache() {
    try {
      return JSON.parse(localStorage.getItem(CACHE_KEY)) ?? null;
    } catch {
      return null;
    }
  }

  async function refresh() {
    const urls = ['./advisories.json', LIVE_FEED_URL, remoteUrl].filter(Boolean);
    const results = await Promise.allSettled(urls.map(fetchJson));
    const ok = results.filter((r) => r.status === 'fulfilled').map((r) => r.value);
    if (!ok.length) {
      state = { ...state, status: state.feed ? 'cached' : 'official', error: results[0]?.reason?.message };
    } else {
      const feed = merge(ok);
      try {
        localStorage.setItem(CACHE_KEY, JSON.stringify(feed));
      } catch {}
      // Live: the always-current copy (or your own feed) came through, not just the bundled one.
      const remoteOk = results.slice(1).some((r) => r.status === 'fulfilled');
      state = { status: remoteOk ? 'live' : 'official', feed, error: null };
    }
    onChange(state);
  }

  function start() {
    refresh();
    clearInterval(timer);
    timer = setInterval(() => document.visibilityState === 'visible' && refresh(), REFRESH_MS);
    document.addEventListener('visibilitychange', () => document.visibilityState === 'visible' && refresh());
  }

  return {
    start,
    refresh,
    get state() {
      return state;
    },
    setRemote(url) {
      remoteUrl = url;
      refresh();
    },
  };
}

export function activeNotices(feed, nowMs) {
  return (feed?.notices ?? []).filter((n) => {
    const from = n.from ? Date.parse(n.from) : -Infinity;
    const to = n.to ? Date.parse(n.to) : Infinity;
    return nowMs >= from && nowMs <= to;
  });
}
