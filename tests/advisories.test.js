import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  adjustmentsFrom,
  classify,
  datesIn,
  nextFeed,
  noticeFrom,
  postsFromRss,
  postsFromWp,
  strip,
  timetableFromMedia,
  untilTimeIn,
} from '../tools/advisories-lib.mjs';
import { Schedule } from '../js/engine.js';

// Example posts written for these tests (not real CMRL announcements).
const post = (id, date, title, text = '') => ({ id: String(id), date, link: `https://example.org/?p=${id}`, title, text });
const extension = post(
  101,
  '2026-11-13T10:00:00Z',
  'Extension of Metro Services on 15.11.2026',
  'Metro train services will be extended till 12 midnight on 15.11.2026 on account of the cricket match. Trains will run every 10 min after 11 PM.',
);
const holiday = post(102, '2026-11-13T09:00:00Z', 'Train timings on Diwali', 'Sunday timetable will be followed on 8th November 2026 on all lines.');
const tender = post(103, '2026-11-13T08:00:00Z', 'Tender for station maintenance work', 'Bids are invited for maintenance work at stations.');
const glitch = post(104, '2026-11-13T07:00:00Z', 'Blue Line services delayed', 'Due to a technical glitch, train services on the Blue Line are delayed by 10 minutes.');
const NOW = Date.parse('2026-11-13T12:00:00Z');
const empty = { updated: null, source: 'test', holidays: {}, notices: [], adjustments: [] };

test('posts about running trains are kept, tenders are not', () => {
  assert.deepEqual(classify(extension), { relevant: true, level: 'warn', lines: null });
  assert.equal(classify(tender).relevant, false);
  assert.deepEqual(classify(glitch), { relevant: true, level: 'alert', lines: ['blue'] });
});

test('dates and times read the way CMRL writes them', () => {
  assert.deepEqual(datesIn('on 15.11.2026 and 16/11/26', '2026-11-13T00:00:00Z'), ['2026-11-15', '2026-11-16']);
  assert.deepEqual(datesIn('from 8th November 2026', '2026-11-01T00:00:00Z'), ['2026-11-08']);
  assert.deepEqual(datesIn('on January 2', '2026-12-20T00:00:00Z'), ['2027-01-02'], 'a date without a year is the next one');
  assert.equal(untilTimeIn('extended till 12 midnight'), '00:00');
  assert.equal(untilTimeIn('until 11.30 p.m. tonight'), '23:30');
  assert.equal(untilTimeIn('up to 23:00 hrs'), '23:00');
  assert.equal(untilTimeIn('till 11 tonight'), null, 'too vague');
});

test('clear-cut announcements become timetable adjustments', () => {
  assert.deepEqual(adjustmentsFrom(extension), [
    { date: '2026-11-15', extendUntil: '00:00', headway: 10, reason: extension.title, auto: true, source: extension.link },
  ]);
  assert.deepEqual(adjustmentsFrom(holiday), [{ date: '2026-11-08', dayType: 'sunday', reason: holiday.title, auto: true, source: holiday.link }]);
  assert.deepEqual(adjustmentsFrom(glitch), [], 'a delay with no window stays a notice');
});

test('a notice stays up until the end of the last day it names', () => {
  const n = noticeFrom(extension);
  assert.equal(n.id, 'cmrl-101');
  assert.equal(n.to, '2026-11-15T21:30:00.000Z', '3 AM IST on the 16th');
  assert.equal(noticeFrom(tender), null);
});

test('the feed keeps hand-written entries and only changes when something does', () => {
  const manual = { ...empty, notices: [{ id: 'mine', level: 'info', title: 'Hand-written' }] };
  const first = nextFeed(manual, { posts: [extension, tender, glitch], timetable: null }, NOW);
  assert.ok(first.changed);
  assert.deepEqual(first.feed.notices.map((n) => n.id), ['mine', 'cmrl-101', 'cmrl-104']);
  assert.equal(first.feed.adjustments.length, 1);
  const again = nextFeed(first.feed, { posts: [extension, tender, glitch], timetable: null }, NOW + 3600_000);
  assert.equal(again.changed, false, 'same posts, no rewrite');
  // A source that couldn't be read keeps what the feed has; past entries drop off.
  const later = nextFeed(first.feed, { posts: null, timetable: null }, Date.parse('2026-11-17T00:00:00Z'));
  assert.deepEqual(later.feed.notices.map((n) => n.id), ['mine']);
  assert.equal(later.feed.adjustments.length, 0);
});

test('a changed timetable PDF raises a notice once; reading it another way does not', () => {
  const a = { url: 'https://example.org/Time-Table.pdf', sha256: 'a'.repeat(64), bytes: 10 };
  const base = nextFeed(empty, { posts: [], timetable: a }, NOW);
  assert.equal(base.timetableChanged, false, 'the first fingerprint is the baseline');
  const same = nextFeed(base.feed, { posts: [], timetable: a }, NOW);
  assert.equal(same.changed, false);
  const other = nextFeed(base.feed, { posts: [], timetable: { ...a, url: 'https://example.org/old.pdf', sha256: 'c'.repeat(64) } }, NOW);
  assert.equal(other.timetableChanged, false, 'a different address read without the library is not news');
  const edited = nextFeed(base.feed, { posts: [], timetable: { ...a, sha256: 'b'.repeat(64) } }, NOW);
  assert.ok(edited.timetableChanged);
  assert.ok(edited.feed.notices.some((n) => n.id === 'timetable-bbbbbbbb'));
  const upload = nextFeed(base.feed, { posts: [], timetable: { url: 'https://example.org/Time-Table-1.pdf', sha256: 'd'.repeat(64), bytes: 9, fromLibrary: true } }, NOW);
  assert.ok(upload.timetableChanged, 'a new upload in the media library');
});

test('WordPress, RSS and media listings are read', () => {
  const wp = postsFromWp([{ id: 7, date_gmt: '2026-11-13T05:00:00', link: 'https://example.org/x', title: { rendered: 'Train timings &#8211; Diwali' }, content: { rendered: '<p>Sunday&nbsp;timetable</p>' } }]);
  assert.deepEqual(wp, [{ id: '7', date: '2026-11-13T05:00:00Z', link: 'https://example.org/x', title: 'Train timings – Diwali', text: 'Sunday timetable' }]);
  const rss = postsFromRss(
    '<rss><channel><item><title><![CDATA[Metro services extended]]></title><link>https://example.org/y</link><guid>https://example.org/?p=9</guid><pubDate>Fri, 13 Nov 2026 05:00:00 +0000</pubDate><description><![CDATA[<p>Till 11 PM</p>]]></description></item></channel></rss>',
  );
  assert.deepEqual(rss, [{ id: '9', date: '2026-11-13T05:00:00.000Z', link: 'https://example.org/y', title: 'Metro services extended', text: 'Till 11 PM' }]);
  assert.equal(strip('a<br>b &amp; c'), 'a\nb & c');
  const media = timetableFromMedia([
    { source_url: 'https://example.org/2026/10/Time-Table.pdf', modified_gmt: '2026-10-09T00:00:00' },
    { source_url: 'https://example.org/2026/12/Time-Table.pdf', modified_gmt: '2026-12-01T00:00:00' },
    { source_url: 'https://example.org/2026/12/tender.pdf', modified_gmt: '2026-12-02T00:00:00' },
  ]);
  assert.equal(media, 'https://example.org/2026/12/Time-Table.pdf');
});

test('the engine runs extra trains on a night the feed extends', () => {
  const feed = nextFeed(empty, { posts: [extension], timetable: null }, NOW).feed;
  const plain = new Schedule().day('2026-11-15').trips.length;
  const extended = new Schedule({ feed }).day('2026-11-15').trips.length;
  assert.ok(extended > plain, `${extended} > ${plain}`);
});

test('dates are whole words, and "may" the verb is not May', () => {
  const P = '2026-11-13T00:00:00Z';
  assert.deepEqual(datesIn('Gate 3 marked for exit; 2 separate queues at exit 2 junction', P), []);
  assert.deepEqual(datesIn('Line 2 may be affected for may 15 minutes', P), []);
  assert.deepEqual(datesIn('on 2nd May and on 3 May 2027', P), ['2027-05-02', '2027-05-03']);
  assert.deepEqual(datesIn('closed on 31st December', '2027-01-02T00:00:00Z'), ['2026-12-31'], 'the year nearest the post');
});

test('a date is never read as the time services run till', () => {
  assert.equal(untilTimeIn('from 21.11.2026 till 23.11.2026, trains until 11 PM'), '23:00');
  assert.equal(untilTimeIn('till 22.11.2026'), null);
});

test('a change applies only to the dates it is announced with', () => {
  const p = post(201, '2026-11-13T00:00:00Z', 'Train timings', 'Sunday timetable on 15.11.2026. Regular weekday timetable resumes on 16.11.2026.');
  assert.deepEqual(adjustmentsFrom(p).map((a) => `${a.date}:${a.dayType}`), ['2026-11-15:sunday']);
});

test('only posts about running trains change the timetable; disruptions always count', () => {
  const tender = post(202, '2026-11-13T00:00:00Z', 'Tender: bid submission extended', 'Bid submission extended till 11.30 PM on 20.11.2026. Holiday schedule on 21.11.2026.');
  assert.deepEqual(adjustmentsFrom(tender), []);
  assert.equal(classify(post(203, '2026-11-13T00:00:00Z', 'Train services suspended due to contract staff strike')).relevant, true);
  assert.equal(classify(post(204, '2026-11-13T00:00:00Z', 'Blue Line trains delayed; exam results day rush')).relevant, true);
  assert.equal(classify(post(205, '2026-11-13T00:00:00Z', 'Deadline extended for student travel card applications')).relevant, false);
});

test('the same PDF at a new address is not a new timetable', () => {
  const a = { url: 'https://example.org/Time-Table.pdf', sha256: 'a'.repeat(64), bytes: 10 };
  const base = nextFeed(empty, { posts: [], timetable: a }, NOW);
  const moved = nextFeed(base.feed, { posts: [], timetable: { ...a, url: 'https://example.org/2026/12/Time-Table.pdf', fromLibrary: true } }, NOW);
  assert.equal(moved.timetableChanged, false);
  assert.equal(Object.keys(moved.feed.watch.timetables).at(-1), 'https://example.org/2026/12/Time-Table.pdf');
});

test('the timetable PDF comes from the Time Table page, not other PDFs on it', async () => {
  const { timetableFromPage } = await import('../tools/advisories-lib.mjs');
  const page = 'https://chennaimetrorail.org/time-table/';
  const html = `
    <a href="/wp-content/uploads/2023/11/Line-Map.pdf">Map</a>
    <a href="/wp-content/uploads/2026/10/first-and-last-train-timings_terminal_phase1-and-Phase2-dated-23.10.2026.pdf">Timings</a>
    <a href="/wp-content/uploads/2023/12/CMRL-Brochrue.pdf">Brochure</a>`;
  assert.equal(
    timetableFromPage(html, page),
    'https://chennaimetrorail.org/wp-content/uploads/2026/10/first-and-last-train-timings_terminal_phase1-and-Phase2-dated-23.10.2026.pdf',
  );
  assert.equal(timetableFromPage('<a href="/x/Line-Map.pdf">Map</a>', page), null);
  assert.equal(timetableFromPage(null, page), null);
});
