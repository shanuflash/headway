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

function merge(feeds) {
  const out = { updated: null, holidays: {}, notices: [], adjustments: [], sources: [] };
  for (const f of feeds) {
    if (!f) continue;
    Object.assign(out.holidays, f.holidays);
    out.notices.push(...(f.notices ?? []));
    out.adjustments.push(...(f.adjustments ?? []));
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
    const urls = ['./advisories.json', remoteUrl].filter(Boolean);
    const results = await Promise.allSettled(urls.map(fetchJson));
    const ok = results.filter((r) => r.status === 'fulfilled').map((r) => r.value);
    if (!ok.length) {
      state = { ...state, status: state.feed ? 'cached' : 'official', error: results[0]?.reason?.message };
    } else {
      const feed = merge(ok);
      try {
        localStorage.setItem(CACHE_KEY, JSON.stringify(feed));
      } catch {}
      const remoteOk = remoteUrl && results[results.length - 1].status === 'fulfilled';
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
