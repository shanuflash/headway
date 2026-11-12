// Chennai Metro network. Route stop lists carry cumulative minutes from the route's first
// stop (running + dwell). The PDF gives only terminal times and headways, so intermediate
// times are estimates and are labelled as such in the UI.

export const LINES = {
  blue: { id: 'blue', name: 'Blue Line', corridor: 'Corridor 1' },
  green: { id: 'green', name: 'Green Line', corridor: 'Corridor 2' },
  yellow: { id: 'yellow', name: 'Yellow Line', corridor: 'Corridor 4' },
};

// `transfer`: minutes to change trains here (platform to platform).
export const STATIONS = {
  'wimco-nagar-depot': { name: 'Wimco Nagar Depot', lines: ['blue'] },
  'wimco-nagar': { name: 'Wimco Nagar', lines: ['blue'] },
  tiruvottiyur: { name: 'Tiruvottiyur', lines: ['blue'] },
  'tiruvottiyur-theradi': { name: 'Tiruvottiyur Theradi', lines: ['blue'] },
  kaladipet: { name: 'Kaladipet', lines: ['blue'] },
  tollgate: { name: 'Tollgate', lines: ['blue'] },
  'new-washermenpet': { name: 'New Washermenpet', lines: ['blue'] },
  tondiarpet: { name: 'Tondiarpet', lines: ['blue'] },
  'theagaraya-college': { name: 'Sir Theagaraya College', lines: ['blue'] },
  washermenpet: { name: 'Washermenpet', lines: ['blue'] },
  mannadi: { name: 'Mannadi', lines: ['blue'] },
  'high-court': { name: 'High Court', lines: ['blue'] },
  central: {
    name: 'Chennai Central',
    long: 'Puratchi Thalaivar Dr. M.G. Ramachandran Central',
    lines: ['blue', 'green'],
    transfer: 5,
  },
  'government-estate': { name: 'Government Estate', lines: ['blue'] },
  lic: { name: 'LIC', lines: ['blue'] },
  'thousand-lights': { name: 'Thousand Lights', lines: ['blue'] },
  agdms: { name: 'AG-DMS', lines: ['blue'] },
  teynampet: { name: 'Teynampet', lines: ['blue'] },
  nandanam: { name: 'Nandanam', lines: ['blue'] },
  saidapet: { name: 'Saidapet', lines: ['blue'] },
  'little-mount': { name: 'Little Mount', lines: ['blue'] },
  guindy: { name: 'Guindy', lines: ['blue'] },
  alandur: { name: 'Alandur', long: 'Arignar Anna Alandur', lines: ['blue', 'green'], transfer: 3 },
  'nanganallur-road': { name: 'Nanganallur Road', long: 'OTA – Nanganallur Road', lines: ['blue'] },
  meenambakkam: { name: 'Meenambakkam', lines: ['blue'] },
  airport: { name: 'Airport', long: 'Chennai International Airport', lines: ['blue'] },

  egmore: { name: 'Egmore', lines: ['green'] },
  'nehru-park': { name: 'Nehru Park', lines: ['green'] },
  kilpauk: { name: 'Kilpauk', lines: ['green'] },
  'pachaiyappas-college': { name: "Pachaiyappa's College", lines: ['green'] },
  'shenoy-nagar': { name: 'Shenoy Nagar', lines: ['green'] },
  'anna-nagar-east': { name: 'Anna Nagar East', lines: ['green'] },
  'anna-nagar-tower': { name: 'Anna Nagar Tower', lines: ['green'] },
  thirumangalam: { name: 'Thirumangalam', lines: ['green'] },
  koyambedu: { name: 'Koyambedu', lines: ['green'] },
  cmbt: { name: 'CMBT', long: 'Puratchi Thalaivi Dr. J. Jayalalithaa CMBT', lines: ['green'] },
  arumbakkam: { name: 'Arumbakkam', lines: ['green'] },
  vadapalani: { name: 'Vadapalani', lines: ['green', 'yellow'], transfer: 8 },
  'ashok-nagar': { name: 'Ashok Nagar', lines: ['green'] },
  ekkattuthangal: { name: 'Ekkattuthangal', lines: ['green'] },
  'st-thomas-mount': { name: 'St. Thomas Mount', lines: ['green'] },

  'poonamallee-bypass': { name: 'Poonamallee Bypass', lines: ['yellow'] },
  poonamallee: { name: 'Poonamallee', long: 'Poonamallee Bus Stand', lines: ['yellow'] },
  mullaithottam: { name: 'Mullaithottam', long: 'Poonamallee Government Hospital', lines: ['yellow'] },
  karayanchavadi: { name: 'Karayanchavadi', lines: ['yellow'] },
  kumananchavadi: { name: 'Kumananchavadi', lines: ['yellow'] },
  kattupakkam: { name: 'Kattupakkam', lines: ['yellow'] },
  iyyappanthangal: { name: 'Iyyappanthangal', lines: ['yellow'] },
  thelliyaragaram: { name: 'Thelliyaragaram', lines: ['yellow'] },
  'porur-bypass': { name: 'Porur Bypass', lines: ['yellow'] },
  'porur-junction': { name: 'Porur Junction', lines: ['yellow'] },
};

// Run times: ChennaiGTFS (CUMTA) stop_times, medians per direction. Wimco Nagar Depot and
// Tiruvottiyur Theradi are missing from that feed, so their offsets are interpolated.
// Yellow Line: only the ~30 min end-to-end is published; trains run non-stop
// Porur Junction -> Vadapalani, so that leg takes the largest share.
const BLUE_FWD = [
  ['wimco-nagar-depot', 0], ['wimco-nagar', 2.0], ['tiruvottiyur', 3.3], ['tiruvottiyur-theradi', 5.0],
  ['kaladipet', 7.6], ['tollgate', 9.4], ['new-washermenpet', 11.3], ['tondiarpet', 13.3],
  ['theagaraya-college', 15.1], ['washermenpet', 17.0], ['mannadi', 19.7], ['high-court', 21.4],
  ['central', 23.8], ['government-estate', 26.8], ['lic', 28.5], ['thousand-lights', 30.4],
  ['agdms', 33.5], ['teynampet', 35.4], ['nandanam', 37.2], ['saidapet', 39.9],
  ['little-mount', 42.3], ['guindy', 44.6], ['alandur', 47.3], ['nanganallur-road', 49.2],
  ['meenambakkam', 52.5], ['airport', 54.8],
];

const BLUE_REV = [
  ['airport', 0], ['meenambakkam', 2.2], ['nanganallur-road', 5.4], ['alandur', 7.3],
  ['guindy', 10.1], ['little-mount', 12.3], ['saidapet', 14.4], ['nandanam', 17.1],
  ['teynampet', 19.0], ['agdms', 21.0], ['thousand-lights', 23.9], ['lic', 25.8],
  ['government-estate', 27.6], ['central', 31.1], ['high-court', 34.0], ['mannadi', 35.8],
  ['washermenpet', 38.6], ['theagaraya-college', 40.5], ['tondiarpet', 42.3], ['new-washermenpet', 44.4],
  ['tollgate', 46.2], ['kaladipet', 48.0], ['tiruvottiyur-theradi', 49.7], ['tiruvottiyur', 52.3],
  ['wimco-nagar', 54.0], ['wimco-nagar-depot', 56.0],
];

const GREEN_FWD = [
  ['central', 0], ['egmore', 2.1], ['nehru-park', 4.1], ['kilpauk', 5.7],
  ['pachaiyappas-college', 7.6], ['shenoy-nagar', 9.8], ['anna-nagar-east', 11.9], ['anna-nagar-tower', 13.9],
  ['thirumangalam', 15.5], ['koyambedu', 18.8], ['cmbt', 21.4], ['arumbakkam', 23.7],
  ['vadapalani', 25.8], ['ashok-nagar', 28.6], ['ekkattuthangal', 31.8], ['alandur', 34.1],
  ['st-thomas-mount', 36.7],
];

const GREEN_REV = [
  ['st-thomas-mount', 0], ['alandur', 2.2], ['ekkattuthangal', 4.4], ['ashok-nagar', 7.8],
  ['vadapalani', 10.4], ['arumbakkam', 12.5], ['cmbt', 14.8], ['koyambedu', 17.4],
  ['thirumangalam', 20.7], ['anna-nagar-tower', 22.4], ['anna-nagar-east', 24.3], ['shenoy-nagar', 26.3],
  ['pachaiyappas-college', 28.6], ['kilpauk', 30.5], ['nehru-park', 32.1], ['egmore', 34.1],
  ['central', 37.1],
];

const INTER_FWD = [
  ['central', 0], ['egmore', 2.3], ['nehru-park', 4.3], ['kilpauk', 5.9],
  ['pachaiyappas-college', 7.8], ['shenoy-nagar', 10.0], ['anna-nagar-east', 12.1], ['anna-nagar-tower', 14.0],
  ['thirumangalam', 15.7], ['koyambedu', 19.0], ['cmbt', 21.5], ['arumbakkam', 23.8],
  ['vadapalani', 26.0], ['ashok-nagar', 28.8], ['ekkattuthangal', 32.0], ['alandur', 34.2],
  ['nanganallur-road', 36.6], ['meenambakkam', 39.9], ['airport', 42.5],
];

const INTER_REV = [
  ['airport', 0], ['meenambakkam', 2.1], ['nanganallur-road', 5.2], ['alandur', 7.5],
  ['ekkattuthangal', 9.7], ['ashok-nagar', 13.1], ['vadapalani', 15.7], ['arumbakkam', 17.8],
  ['cmbt', 20.1], ['koyambedu', 22.6], ['thirumangalam', 26.0], ['anna-nagar-tower', 27.6],
  ['anna-nagar-east', 29.6], ['shenoy-nagar', 31.6], ['pachaiyappas-college', 33.9], ['kilpauk', 35.8],
  ['nehru-park', 37.4], ['egmore', 39.4], ['central', 42.2],
];

const YELLOW_FWD = [
  ['poonamallee-bypass', 0], ['poonamallee', 2], ['mullaithottam', 4], ['karayanchavadi', 6],
  ['kumananchavadi', 8], ['kattupakkam', 10], ['iyyappanthangal', 12], ['thelliyaragaram', 14],
  ['porur-bypass', 16], ['porur-junction', 18.5], ['vadapalani', 30],
];

const mirror = (fwd) => {
  const total = fwd[fwd.length - 1][1];
  return fwd.map(([s, t]) => [s, total - t]).reverse();
};

// `fwd` runs from the route's first stop, `rev` from its last; each lists cumulative minutes.
export const ROUTES = {
  blue: { line: 'blue', name: 'Blue Line', fwd: BLUE_FWD, rev: BLUE_REV },
  green: { line: 'green', name: 'Green Line', fwd: GREEN_FWD, rev: GREEN_REV },
  // Inter-corridor: Green Line tracks to Alandur, then Blue Line tracks to the Airport.
  inter: { line: 'green', name: 'Inter-corridor', via: 'CMBT', fwd: INTER_FWD, rev: INTER_REV },
  yellow: { line: 'yellow', name: 'Yellow Line', fwd: YELLOW_FWD, rev: mirror(YELLOW_FWD) },
  loop: { line: 'blue', name: 'Short loop', from: 'washermenpet', to: 'alandur' },
};

// Order for the station picker.
export const PICKER_GROUPS = [
  { line: 'blue', stations: BLUE_FWD.map(([s]) => s) },
  { line: 'green', stations: GREEN_FWD.map(([s]) => s) },
  { line: 'yellow', stations: YELLOW_FWD.map(([s]) => s) },
];

/**
 * Stations reachable from `from` without changing trains, grouped by direction of travel
 * (the next stop). Each group lists stations in travel order with typical ride minutes.
 */
export function directDestinations(from) {
  const groups = new Map();
  for (const id of ['blue', 'green', 'inter', 'yellow']) {
    const route = ROUTES[id];
    for (const dir of ['fwd', 'rev']) {
      const seq = route[dir];
      const i = seq.findIndex(([s]) => s === from);
      if (i < 0 || i === seq.length - 1) continue;
      const next = seq[i + 1][0];
      if (!groups.has(next)) groups.set(next, { next, line: route.line, terminals: new Set(), stations: new Map() });
      const g = groups.get(next);
      g.terminals.add(seq.at(-1)[0]);
      for (let k = i + 1; k < seq.length; k++) {
        const [s, cum] = seq[k];
        const min = Math.round(cum - seq[i][1]);
        if (!g.stations.has(s) || g.stations.get(s) > min) g.stations.set(s, min);
      }
    }
  }
  return [...groups.values()].map((g) => ({
    next: g.next,
    line: g.line,
    terminals: [...g.terminals],
    stations: [...g.stations.entries()].sort((a, b) => a[1] - b[1]).map(([id, minutes]) => ({ id, minutes })),
  }));
}

/**
 * Stations that need a change from `from`, with the fewest changes (up to 2) and, among those,
 * the least riding: id -> { changes, via: [interchange ids] }. Direct destinations are left out.
 * A quick structural guess for the picker; the timetable planner picks the real interchange.
 */
export function transferDestinations(from) {
  const best = new Map();
  for (const g of directDestinations(from)) for (const s of g.stations) best.set(s.id, { changes: 0, minutes: s.minutes, via: [] });
  let frontier = [...best.entries()].filter(([id]) => STATIONS[id].lines.length > 1).map(([id, b]) => ({ at: id, minutes: b.minutes, via: [id] }));
  for (let changes = 1; changes <= 2 && frontier.length; changes++) {
    const next = [];
    for (const f of frontier) {
      for (const g of directDestinations(f.at)) {
        for (const s of g.stations) {
          if (s.id === from) continue;
          const minutes = f.minutes + s.minutes;
          const cur = best.get(s.id);
          if (cur && (cur.changes < changes || (cur.changes === changes && cur.minutes <= minutes))) continue;
          best.set(s.id, { changes, minutes, via: f.via });
          if (STATIONS[s.id].lines.length > 1) next.push({ at: s.id, minutes, via: [...f.via, s.id] });
        }
      }
    }
    frontier = next;
  }
  const out = new Map();
  for (const [id, b] of best) if (b.changes > 0 && id !== from) out.set(id, { changes: b.changes, via: b.via });
  return out;
}

