#!/usr/bin/env node
// Keeps advisories.json current from what CMRL publishes. Run by .github/workflows/advisories.yml
// every couple of hours; safe to run by hand: `node tools/advisories.mjs`.
//
// Every source is optional. One that can't be read (site down, page moved, blocked) is skipped
// and what the feed already has from it is kept, so a bad fetch never wipes good advisories.
// The logic lives in tools/advisories-lib.mjs and is tested in tests/advisories.test.js.

import { createHash } from 'node:crypto';
import { readFile, writeFile, appendFile } from 'node:fs/promises';
import { TIMETABLE_SOURCE } from '../js/timetable.js';
import { nextFeed, postsFromRss, postsFromWp, timetableFromMedia } from './advisories-lib.mjs';

const SITE = 'https://chennaimetrorail.org';
const FEED_FILE = new URL('../advisories.json', import.meta.url);
const HEADERS = { 'user-agent': 'HeadwayAdvisories/1.0 (timetable app; checks for service updates every few hours)' };
const log = [];

async function get(url, as = 'json') {
  const res = await fetch(url, { headers: HEADERS, signal: AbortSignal.timeout(30_000), redirect: 'follow' });
  if (!res.ok) throw new Error(`${res.status} ${res.statusText}`);
  if (as === 'json') return res.json();
  if (as === 'text') return res.text();
  return Buffer.from(await res.arrayBuffer());
}

/** Tries each way to read something in turn; logs what worked. Null when none did. */
async function first(what, attempts) {
  for (const [label, run] of attempts) {
    try {
      const out = await run();
      log.push(`- ${what}: ${label} ✓`);
      return out;
    } catch (e) {
      log.push(`- ${what}: ${label} ✗ (${e.message})`);
    }
  }
  return null;
}

const posts = await first('Announcements', [
  ['WordPress posts API', async () => postsFromWp(await get(`${SITE}/wp-json/wp/v2/posts?per_page=30&_fields=id,date,date_gmt,link,title,content,excerpt`))],
  [
    'RSS feed',
    async () => {
      const items = postsFromRss(await get(`${SITE}/feed/`, 'text'));
      if (!items.length) throw new Error('no items');
      return items;
    },
  ],
]);

const timetable = await first('Timetable PDF', [
  [
    'newest timetable in the media library',
    async () => {
      const url = timetableFromMedia(await get(`${SITE}/wp-json/wp/v2/media?search=time&per_page=50&_fields=id,date,modified_gmt,source_url,title`));
      if (!url) throw new Error('none found');
      return { ...(await fingerprint(url)), fromLibrary: true };
    },
  ],
  ['the PDF the app is built on', () => fingerprint(TIMETABLE_SOURCE.url)],
]);

async function fingerprint(url) {
  const bytes = await get(url, 'buffer');
  if (bytes.subarray(0, 4).toString() !== '%PDF') throw new Error('not a PDF');
  return { url, sha256: createHash('sha256').update(bytes).digest('hex'), bytes: bytes.length };
}

const prev = JSON.parse(await readFile(FEED_FILE, 'utf8'));
const { feed, changed, timetableChanged } = nextFeed(prev, { posts, timetable }, Date.now());

if (changed) await writeFile(FEED_FILE, `${JSON.stringify(feed, null, 2)}\n`);

const auto = feed.notices.filter((n) => n.auto);
const summary = [
  '## Advisories',
  ...log,
  '',
  changed ? '**advisories.json updated.**' : 'No change.',
  `${auto.length} notice(s) from CMRL, ${feed.adjustments.filter((a) => a.auto).length} timetable adjustment(s).`,
  ...auto.map((n) => `- [${n.level}] ${n.title} (until ${n.to?.slice(0, 10) ?? 'further notice'})`),
].join('\n');
console.log(summary);
if (process.env.GITHUB_STEP_SUMMARY) await appendFile(process.env.GITHUB_STEP_SUMMARY, `${summary}\n`);

// The workflow opens an issue from this: the times live in js/timetable.js and the generated
// Android timetable, and need a person to re-read the PDF.
if (timetableChanged && process.env.RUNNER_TEMP) {
  await writeFile(
    `${process.env.RUNNER_TEMP}/timetable-issue.md`,
    [
      `CMRL's timetable PDF changed: ${timetable.url}`,
      '',
      `New SHA-256: \`${timetable.sha256}\` (${timetable.bytes} bytes).`,
      '',
      'Headway shows riders a notice about it. To update the times:',
      '- [ ] Read the new first/last trains and headway bands into `js/timetable.js` (and `TIMETABLE_SOURCE`).',
      '- [ ] Regenerate the Android timetable (`android/app/src/main/java/com/flash/headway/data/Timetable.kt`).',
      '- [ ] `npm test`, then push; the Android build runs its tests too.',
    ].join('\n'),
  );
}

// A run that could read nothing at all should show up as failed, so it gets noticed.
if (!posts && !timetable) {
  console.error('Could not read any CMRL source.');
  process.exit(1);
}
