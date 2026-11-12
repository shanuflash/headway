# Headway

A next-train timer for Chennai Metro, built on the official CMRL timetable. It is a mobile-first PWA in plain HTML, CSS and ES modules, with no build step.

## What it does

Headway is for people who ride the same route every day. Save where you board and where you get off, and it lists the next trains on that route.

- **Routes:** one card per saved route, always open.
  - The next train is shown big with a live countdown, then the next few departures (3, 5 or 8, set in Settings), each with its arrival time.
  - The swap button on a card flips the route in place, so the morning route becomes the ride home with one tap.
  - **Switch by time:** in the same menu, a route can flip itself to the ride home at a set time (5 PM by default, in 30-minute steps) and back for the morning trip. A swap by hand still works and lasts until the next change-over; the swap icon turns blue on routes that switch by themselves.
  - **Delay:** the route's ⋯ menu has a slider from −10 to +10 min. If your trains reliably leave early or late, it shifts that route's times, so the countdown, the times listed, the last-train warning and the Android widget all use the shifted times. A tag under the shown train gives its delay; tap it to give that one train its own, every day ("the 8:13 runs 3 min late, the 8:21 on time"), which overrides the route's delay for it.
  - Short loops and Green Line trains via CMBT are included when they stop at your destination.
  - **Trips with changes:** after picking where you board, direct trains come first, then every other station under "With a change", with where you'd change. A saved trip works like any route card: each chip is a whole journey, and the card shows its timeline, from boarding through each change (walk time and minutes to spare) to arrival, with lines, directions and platforms. Each chip has a thin bar in its trains' colours, so a trip using the Inter-corridor (green fading to blue) stands apart from one using the Green Line.
- **States:** last trains (an amber label, plus the first train tomorrow), "No more trains tonight" and "Metro is closed".
- **Countdown:** tap the bell on any train for a live countdown notification to its departure, with "I'll take the next one" and "Stop". It counts to the train itself; there's no walking-time offset.
- **Quick look:** the search button checks any pair of stations without saving them, with changes if needed. Its "Leaving now" pill plans it for another time instead: leave at, or arrive by, a time today or tomorrow (also from a route's menu: "Plan another time").
- **Lines:** today's official first and last trains and frequency bands.
- **Settings:** today's timetable override, trains shown per route, last-train warning, and appearance.

## Run locally

No build step and no dependencies. You need Python 3, plus Node 18+ for the tests.

```bash
npm start
```

That serves the app on http://127.0.0.1:5173 with caching off. To try it on a phone on the same Wi-Fi, run `npm run start:lan` instead.

```bash
npm test
```

## Deploy

`.github/workflows/pages.yml` runs the engine tests and publishes the site to GitHub Pages on every push to `main`. In the repo, set Settings → Pages → Source to **GitHub Actions**. Pages serves over HTTPS, which turns on the service worker: the app then works offline and can be installed to the home screen.

## Android app

`android/` is a native Kotlin and Jetpack Compose app with the same design and timetable engine. `data/` is generated from the web app's `js/network.js` and `js/timetable.js`, so both apps use identical numbers.

- **Routes and reminders:** the same screens as the web app. The bell posts a live countdown notification to departure, with "I'll take the next one" and "Stop" buttons, and it keeps running with the app closed.
- **1×1 widget:** shows one number, the minutes to the next train you can catch on a route, inside a ring in the line colour. The ring turns amber for the last trains and shows the first train time when the metro is closed. It refreshes every minute while the screen is on, using a non-waking alarm. Long-press the widget to pick its route or to show the destination instead of "min".

GitHub Actions (`.github/workflows/android.yml`) runs the engine unit tests and builds a debug APK on every push that touches `android/`.

## Data and accuracy

| What | Source | Confidence |
| --- | --- | --- |
| First and last trains, headway bands, day types | [CMRL Time-Table.pdf](https://chennaimetrorail.org/wp-content/uploads/2026/10/Time-Table.pdf), published 9 Oct 2026 | Official |
| Run times between stations, Blue and Green | ChennaiGTFS (CUMTA) `stop_times`, median per direction | Good |
| Wimco Nagar Depot and Tiruvottiyur Theradi offsets | Interpolated, because they are missing from the GTFS feed | Estimate |
| Yellow Line run times | End-to-end of about 30 min (press reports), split by hand | Estimate |
| Festival holidays in `HOLIDAYS` | Check against the Tamil Nadu holiday G.O. | Verify yearly |
| Interchange walk times (minimum time to change lines) | Vadapalani 6 min timed by a rider; Alandur 3 and Chennai Central 5 are estimates | Estimate |

Terminal departures are exact to the timetable. Times at intermediate stations are estimates, so treat them as ±1–2 min.

## Live data: what exists and what doesn't

- **ONDC (TRV11 metro spec).** This is ticketing only (`search` → `confirm` → `status`). `on_search` returns routes, stops and fares, but no per-stop times, frequencies or delays.
  - Calling the network needs a registered participant, Ed25519 request signing and an async callback server.
  - It is not usable from a static site, and it would not give timings anyway.
- **chennaimetrorail.org.** The `wp-json` API exposes only station posts; there are no timetable or fare endpoints.
- **GTFS-realtime.** None is published. The CMRL and Chennai One apps claim live updates, but neither has a public API.

So "live" here means the advisory feed, `advisories.json` (its format is documented in `js/live.js`). A scheduled GitHub Action keeps it current:

- `.github/workflows/advisories.yml` runs `tools/advisories.mjs` every 2 hours (and on demand from the Actions tab). It reads CMRL's announcements (WordPress posts, or the RSS feed) and the timetable PDF.
- Service announcements become notices on the Routes screen. Clear-cut ones also change the timetable: "services extended till 12 midnight on 15.11.2026" adds late trains that night, and "Sunday timetable on <date>" switches that day.
- When the timetable PDF changes, riders see a notice and the job opens an issue, because the times themselves live in `js/timetable.js` and the Android `Timetable.kt`.
- It commits only when something changed, and keeps entries written by hand (anything without `"auto": true`). A source it can't read is skipped, never wiped.
- The web app and the Android app (and its widget) download the committed file from `main`, so updates reach riders without an app release. That URL only works while the repository is public.

## Roadmap to native

1. **Package the PWA.** Wrap it with a TWA (Android) or Capacitor. Then use local notifications for reminders that fire with the app closed.
2. **Nearest station.** Use GPS with the station coordinates from GTFS `stops.txt` to suggest the closest station.
3. **Home-screen widget.** Show the next train, the same way NightShift's Glance widget does.
4. **Advisory worker.** Done: a scheduled GitHub Action (see above).
