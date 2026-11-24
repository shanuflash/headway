// Official CMRL timetable, transcribed from chennaimetrorail.org's first and last train timings
// (Phase 1 and Phase 2, dated 23 Oct 2026).
// `fwd` = departures from the route's first stop, `rev` = from its last stop.
// Bands: [from, to, headwayMin, kind]. A departure uses the band its origin time falls in;
// departures before the first band reuse the first band's headway.

export const TIMETABLE_SOURCE = {
  title: 'CMRL first and last train timings',
  url: 'https://chennaimetrorail.org/wp-content/uploads/2026/10/first-and-last-train-timings_terminal_phase1-and-Phase2-dated-23.10.2026.pdf',
  published: '2026-10-23',
};

const WEEKDAY_BLUE = [
  ['05:00', '08:00', 7, 'offpeak'],
  ['08:00', '11:00', 6, 'peak'],
  ['11:00', '17:00', 7, 'offpeak'],
  ['17:00', '20:00', 6, 'peak'],
  ['20:00', '22:00', 7, 'offpeak'],
  ['22:00', '24:00', 15, 'late'],
];

const WEEKDAY_GREEN = [
  ['05:00', '08:00', 14, 'offpeak'],
  ['08:00', '11:00', 12, 'peak'],
  ['11:00', '17:00', 14, 'offpeak'],
  ['17:00', '20:00', 12, 'peak'],
  ['20:00', '22:00', 14, 'offpeak'],
  ['22:00', '24:00', 30, 'late'],
];

const WEEKDAY_YELLOW = [
  ['06:00', '08:00', 15, 'offpeak'],
  ['08:00', '11:00', 10, 'peak'],
  ['11:00', '17:00', 15, 'offpeak'],
  ['17:00', '20:00', 10, 'peak'],
  ['20:00', '24:00', 15, 'offpeak'],
];

const SUNDAY_BLUE = [
  ['05:00', '12:00', 10, 'offpeak'],
  ['12:00', '20:00', 7, 'peak'],
  ['20:00', '22:00', 10, 'offpeak'],
  ['22:00', '24:00', 15, 'late'],
];

const SUNDAY_GREEN = [
  ['05:00', '12:00', 20, 'offpeak'],
  ['12:00', '20:00', 14, 'peak'],
  ['20:00', '22:00', 20, 'offpeak'],
  ['22:00', '24:00', 30, 'late'],
];

const SUNDAY_YELLOW = [['06:00', '24:00', 15, 'offpeak']];

// Short-loop services Washermenpet <-> Alandur: weekday peaks only, interleaved with the
// Blue Line mainline to give a 3-minute headway on that section (timetable note 2).
const WEEKDAY_LOOP = { windows: [['08:00', '11:00'], ['17:00', '20:00']], offset: 3 };

export const TIMETABLE = {
  weekday: {
    label: 'Weekday',
    blue: { first: { fwd: '04:56', rev: '04:51' }, last: { fwd: '23:00', rev: '23:00' }, bands: WEEKDAY_BLUE },
    inter: { first: { fwd: '04:55', rev: '05:02' }, last: { fwd: '23:17', rev: '23:08' }, bands: WEEKDAY_GREEN },
    green: { first: { fwd: '05:02', rev: '05:01' }, last: { fwd: '23:00', rev: '23:00' }, bands: WEEKDAY_GREEN },
    yellow: { first: { fwd: '06:00', rev: '06:00' }, last: { fwd: '21:00', rev: '21:00' }, bands: WEEKDAY_YELLOW },
    loop: WEEKDAY_LOOP,
  },
  saturday: {
    label: 'Saturday',
    blue: { first: { fwd: '04:56', rev: '04:51' }, last: { fwd: '23:00', rev: '23:00' }, bands: WEEKDAY_BLUE },
    inter: { first: { fwd: '04:55', rev: '05:02' }, last: { fwd: '23:17', rev: '23:08' }, bands: WEEKDAY_GREEN },
    green: { first: { fwd: '05:02', rev: '05:01' }, last: { fwd: '23:00', rev: '23:00' }, bands: WEEKDAY_GREEN },
    yellow: { first: { fwd: '06:00', rev: '06:00' }, last: { fwd: '21:00', rev: '21:00' }, bands: WEEKDAY_YELLOW },
    loop: null,
  },
  sunday: {
    label: 'Sunday · Holiday',
    blue: { first: { fwd: '05:03', rev: '05:02' }, last: { fwd: '23:00', rev: '23:00' }, bands: SUNDAY_BLUE },
    inter: { first: { fwd: '05:01', rev: '04:55' }, last: { fwd: '23:07', rev: '23:08' }, bands: SUNDAY_GREEN },
    green: { first: { fwd: '04:51', rev: '04:51' }, last: { fwd: '23:17', rev: '23:07' }, bands: SUNDAY_GREEN },
    yellow: { first: { fwd: '06:00', rev: '06:00' }, last: { fwd: '21:00', rev: '21:00' }, bands: SUNDAY_YELLOW },
    loop: null,
  },
};

// Public holidays that run the Sunday timetable. Fixed national dates are certain;
// festival dates should be checked against the Tamil Nadu holiday G.O. each year.
export const HOLIDAYS = {
  '2026-01-01': 'New Year',
  '2026-01-15': 'Pongal',
  '2026-01-16': 'Thiruvalluvar Day',
  '2026-01-26': 'Republic Day',
  '2026-05-01': 'May Day',
  '2026-08-15': 'Independence Day',
  '2026-10-02': 'Gandhi Jayanti',
  '2026-10-19': 'Ayudha Pooja',
  '2026-10-20': 'Vijayadasami',
  '2026-12-25': 'Christmas',
  '2027-01-01': 'New Year',
  '2027-01-26': 'Republic Day',
};
