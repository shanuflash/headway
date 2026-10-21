"""Generate the network map layout (web + Android) from real station coordinates.

Input: a saved copy of India Metro's Chennai data bundle (metro.reclaimchennai.city), whose station
coordinates and water features come from OpenStreetMap (ODbL, (c) OpenStreetMap contributors).
Usage: python tools/gen_map.py .tmp/chennai.js
"""
import json
import math
import re
import sys

src = open(sys.argv[1], encoding='utf8').read()

RENAME = {'ag_dms': 'agdms', 'thiyagaraya_college': 'theagaraya-college',
          'thiruvottriyur_theradi': 'tiruvottiyur-theradi', 'thiruvotriyur': 'tiruvottiyur'}
LINES = {
    'blue': ['wimco-nagar-depot', 'wimco-nagar', 'tiruvottiyur', 'tiruvottiyur-theradi', 'kaladipet', 'tollgate',
             'new-washermenpet', 'tondiarpet', 'theagaraya-college', 'washermenpet', 'mannadi', 'high-court', 'central',
             'government-estate', 'lic', 'thousand-lights', 'agdms', 'teynampet', 'nandanam', 'saidapet', 'little-mount',
             'guindy', 'alandur', 'nanganallur-road', 'meenambakkam', 'airport'],
    'green': ['central', 'egmore', 'nehru-park', 'kilpauk', 'pachaiyappas-college', 'shenoy-nagar', 'anna-nagar-east',
              'anna-nagar-tower', 'thirumangalam', 'koyambedu', 'cmbt', 'arumbakkam', 'vadapalani', 'ashok-nagar',
              'ekkattuthangal', 'alandur', 'st-thomas-mount'],
    'yellow': ['vadapalani', 'porur-junction', 'porur-bypass', 'thelliyaragaram', 'iyyappanthangal', 'kattupakkam',
               'kumananchavadi', 'karayanchavadi', 'mullaithottam', 'poonamallee', 'poonamallee-bypass'],
}
NAMES = {}
for m in re.finditer(r"^  '?([a-z-]+)'?: \{ name: '([^']+)'", open('js/network.js', encoding='utf8').read(), re.M):
    NAMES[m.group(1)] = m.group(2)
NAMES.setdefault('central', 'Chennai Central')

# ---- stations
geo = {}
for m in re.finditer(r'a\("([a-z0-9_]+)","[^"]*","[^"]*","[^"]*",(1[23]\.\d+),(80\.\d+),', src):
    geo[RENAME.get(m.group(1), m.group(1).replace('_', '-'))] = (float(m.group(2)), float(m.group(3)))
ours = sorted({s for ids in LINES.values() for s in ids})
missing = [s for s in ours if s not in geo]
assert not missing, missing

# ---- projection: equirectangular around the network's middle latitude
lats = [geo[s][0] for s in ours]
lons = [geo[s][1] for s in ours]
lat0 = (min(lats) + max(lats)) / 2
kx = math.cos(math.radians(lat0))
SCALE = 6400  # units per degree of latitude
PAD_L, PAD_R, PAD_T, PAD_B = 60, 230, 70, 60


def proj(lat, lon):
    return ((lon - min(lons)) * kx * SCALE + PAD_L, (max(lats) - lat) * SCALE + PAD_T)


W = round((max(lons) - min(lons)) * kx * SCALE + PAD_L + PAD_R)
H = round((max(lats) - min(lats)) * SCALE + PAD_T + PAD_B)
P = {s: proj(*geo[s]) for s in ours}

# ---- water features, clipped to the canvas and simplified


def simplify(pts, tol):
    if len(pts) < 3:
        return pts
    (x1, y1), (x2, y2) = pts[0], pts[-1]
    dx, dy = x2 - x1, y2 - y1
    norm = math.hypot(dx, dy) or 1e-9
    far, idx = 0, 0
    for i in range(1, len(pts) - 1):
        d = abs(dy * pts[i][0] - dx * pts[i][1] + x2 * y1 - y2 * x1) / norm
        if d > far:
            far, idx = d, i
    if far <= tol:
        return [pts[0], pts[-1]]
    return simplify(pts[:idx + 1], tol)[:-1] + simplify(pts[idx:], tol)


features = []
pat = re.compile(r'\{kind:"(\w+)",name:(?:"([^"]*)"|\{en:"([^"]*)"[^}]*\})?,?pts:(\[\[[^\]]*\](?:,\[[^\]]*\])*\])')
WANT_RIVERS = ('Cooum', 'Adyar', 'Buckingham')
WANT_LAKES = ('Porur', 'Chembarambakkam', 'Ambattur', 'Korattur', 'Madhavaram', 'Puzhal')
for m in pat.finditer(src):
    kind, name = m.group(1), m.group(2) or m.group(3) or ''
    if kind == 'river' and not name.startswith(WANT_RIVERS):
        continue
    if kind == 'lake' and not name.startswith(WANT_LAKES):
        continue
    if kind not in ('sea', 'coast', 'river', 'lake'):
        continue
    pts = [proj(la, lo) for la, lo in json.loads(m.group(4))]
    if kind == 'sea':
        # Clamp the sea polygon to the canvas; it is filled.
        pts = [(min(max(x, -20), W + 20), min(max(y, -20), H + 20)) for x, y in pts]
        features.append(('sea', simplify(pts, 1.5)))
        continue
    run = []
    for x, y in pts + [(-1e9, -1e9)]:
        if -40 <= x <= W + 40 and -40 <= y <= H + 40:
            run.append((x, y))
        else:
            if len(run) > 1:
                features.append((kind, simplify(run, 1.5)))
            run = []

# ---- labels: try sides in order, keep the first that clears other labels, dots and lines
FONT = 20
boxes = []


def label_box(x, y, side, text):
    w, h = len(text) * FONT * 0.56, FONT * 1.1
    if side == 'r':
        return (x + 14, y - h / 2, x + 14 + w, y + h / 2)
    if side == 'l':
        return (x - 14 - w, y - h / 2, x - 14, y + h / 2)
    if side == 'a':
        return (x + 10, y - 12 - h, x + 10 + w, y - 12)
    if side == 'd':
        return (x + 10, y + 12, x + 10 + w, y + 12 + h)
    if side == 'A':
        return (x - 10 - w, y - 12 - h, x - 10, y - 12)
    if side == 'D':
        return (x - 10 - w, y + 12, x - 10, y + 12 + h)
    return None


segs = []
for ids in LINES.values():
    for a, b in zip(ids, ids[1:]):
        segs.append((P[a], P[b]))


def hits(box):
    x0, y0, x1, y1 = box
    if x0 < 4 or x1 > W - 4 or y0 < 4 or y1 > H - 4:
        return 1e6
    score = 0
    for b in boxes:
        if not (x1 < b[0] or x0 > b[2] or y1 < b[1] or y0 > b[3]):
            score += 1000
    for (px, py) in P.values():
        if x0 - 9 < px < x1 + 9 and y0 - 9 < py < y1 + 9:
            score += 200
    for (ax, ay), (bx, by) in segs:
        for t in (0.1, 0.3, 0.5, 0.7, 0.9):
            sx, sy = ax + (bx - ax) * t, ay + (by - ay) * t
            if x0 - 5 < sx < x1 + 5 and y0 - 5 < sy < y1 + 5:
                score += 50
    return score


# Place interchanges and line ends first, they matter most.
order = sorted(ours, key=lambda s: (0 if sum(s in ids for ids in LINES.values()) > 1 else 1, s))
SIDE = {}
for s in order:
    x, y = P[s]
    text = NAMES.get(s, s)
    best = min(('r', 'l', 'a', 'd', 'A', 'D'), key=lambda side: hits(label_box(x, y, side, text)))
    SIDE[s] = best
    boxes.append(label_box(x, y, best, text))

# ---- write
fmt = lambda v: round(v)
js = ['// Network map in real geography (generated by tools/gen_map.py; do not edit by hand).',
      '// Station positions and water: (c) OpenStreetMap contributors (ODbL), via India Metro.',
      '// Label sides: l/r = left/right of the dot, a/d = above/below right, A/D = above/below left.',
      f'export const MAP_SIZE = {{ w: {W}, h: {H} }};',
      'export const MAP_STATIONS = {']
for s in ours:
    js.append(f"  '{s}': [{fmt(P[s][0])}, {fmt(P[s][1])}, '{SIDE[s]}'],")
js.append('};')
js.append('export const MAP_LINES = {')
for l, ids in LINES.items():
    js.append(f"  {l}: [{', '.join(repr(i) for i in ids)}],".replace('"', "'"))
js.append('};')
js.append('// Water: sea (filled), coast, river (stroked), lake (filled). Points as [x, y].')
js.append('export const MAP_FEATURES = [')
for k, pts in features:
    js.append(f"  ['{k}', [{', '.join(f'[{fmt(x)}, {fmt(y)}]' for x, y in pts)}]],")
js.append('];')
open('js/map-layout.js', 'w', encoding='utf8').write('\n'.join(js) + '\n')

enc = ';'.join(k + ':' + ' '.join(f'{fmt(x)},{fmt(y)}' for x, y in pts) for k, pts in features)
kt = ['package com.flash.headway.data', '',
      '/**',
      ' * Network map in real geography (generated by tools/gen_map.py; do not edit by hand).',
      ' * Station positions and water: (c) OpenStreetMap contributors (ODbL), via India Metro.',
      ' */',
      'object MapLayout {',
      f'    const val WIDTH = {W}f',
      f'    const val HEIGHT = {H}f', '',
      '    /** Station id to (x, y, label side): l/r = left/right, a/d = above/below right, A/D = above/below left. */',
      '    val stations: Map<String, Triple<Float, Float, Char>> = mapOf(']
for s in ours:
    kt.append(f'        "{s}" to Triple({fmt(P[s][0])}f, {fmt(P[s][1])}f, \'{SIDE[s]}\'),')
kt += ['    )', '', '    val lines: Map<Line, List<String>> = mapOf(']
for l, ids in LINES.items():
    kt.append(f'        Line.{l.upper()} to listOf({", ".join(chr(34) + i + chr(34) for i in ids)}),')
kt += ['    )', '',
       '    /** Water features as (kind, points): sea and lake are filled, coast and river are stroked. */',
       '    val features: List<Pair<String, List<Pair<Float, Float>>>> by lazy {',
       '        WATER.split(\';\').map { f ->',
       '            val (kind, pts) = f.split(\':\')',
       '            kind to pts.split(\' \').map { p -> p.split(\',\').let { it[0].toFloat() to it[1].toFloat() } }',
       '        }',
       '    }', '',
       f'    private const val WATER = "{enc}"',
       '}', '']
open('android/app/src/main/java/com/flash/headway/data/MapLayout.kt', 'w', encoding='utf8').write('\n'.join(kt))
clash = sum(1 for i, a in enumerate(boxes) for b in boxes[i + 1:] if not (a[2] < b[0] or a[0] > b[2] or a[3] < b[1] or a[1] > b[3]))
print(f'{clash} label overlaps')
print(f'{W}x{H}, {len(ours)} stations, {len(features)} features, {sum(len(p) for _, p in features)} points, water string {len(enc)} chars')
print({s: SIDE[s] for s in ours})
