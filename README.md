# Headway

A next-train timer for Chennai Metro, built on the official CMRL timetable. It is a mobile-first PWA in plain HTML, CSS and ES modules, with no build step.

## What it does

The interface follows the iOS-style design chosen on the design canvas: grouped background, 26px cards, and a floating glass tab bar. It supports light and dark mode.

- **Now:** the next train you can still catch, as a big countdown. Shows when to leave (walk time is in Settings), a one-tap leave reminder, the next three options, departures from your station, and the last train home.
  - **States:** first run ("Where to?"), last trains (badge, amber times, the next train tomorrow), and closed (no more trains tonight, or the metro is shut).
- **Trip detail:** each leg and change with walk time and minutes to spare, a bar showing ride vs. waiting time, save to Trips, and Start trip.
- **On board:** stops to go, progress segments, the next stop, the stops coming up, and an alert one stop before your change or destination.
- **Trips:** saved and recent trips with their next train.
- **Lines:** today's official first and last trains and frequency bands, with the current band highlighted.
- **Station picker:** a sheet showing travel time from your station on every row. Supports search, recents, swipe to dismiss and the back gesture.
- **Settings:** today's timetable override, walk time, reminder lead, last-train warning, appearance, and an advisory feed URL.
- **Motion:** screens slide using the View Transitions API, sheets and toggles move with spring easing, and countdowns tick in place without redrawing the screen.

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

`android/` is a native Kotlin and Jetpack Compose app with the same design and the same timetable engine.

- **Data:** `data/Network.kt` and `data/Timetable.kt` are generated from `js/network.js` and `js/timetable.js`, so both apps use identical numbers.
- **Engine:** `engine/Schedule.kt` ports `js/engine.js`: trip generation, Connection Scan routing, last-train search, and the overnight rules. It always works in IST.
- **Screens:** Now (normal, first run, last trains and closed states), trip detail, on board, Trips, Lines, Settings, and a station picker sheet. They use light and dark themes and spring-based motion.
- **Alerts:** leave reminders use `AlarmManager`, so they fire with the app closed. On board, an ongoing notification counts down to arrival, and alerts fire before each change and before the final stop.

GitHub Actions (`.github/workflows/android.yml`) runs the engine unit tests and builds a debug APK on every push that touches `android/`. Download the APK from the run's artifacts. No local Android tooling is needed. To build locally, open `android/` in Android Studio.

## Data and accuracy

| What | Source | Confidence |
| --- | --- | --- |
| First and last trains, headway bands, day types | [CMRL Time-Table.pdf](https://chennaimetrorail.org/wp-content/uploads/2026/10/Time-Table.pdf), published 9 Oct 2026 | Official |
| Run times between stations, Blue and Green | ChennaiGTFS (CUMTA) `stop_times`, median per direction | Good |
| Wimco Nagar Depot and Tiruvottiyur Theradi offsets | Interpolated, because they are missing from the GTFS feed | Estimate |
| Yellow Line run times | End-to-end of about 30 min (press reports), split by hand | Estimate |
| Festival holidays in `HOLIDAYS` | Check against the Tamil Nadu holiday G.O. | Verify yearly |

Terminal departures are exact to the timetable. Times at intermediate stations are estimates, so treat them as ±1–2 min.

## Live data: what exists and what doesn't

- **ONDC (TRV11 metro spec).** This is ticketing only (`search` → `confirm` → `status`). `on_search` returns routes, stops and fares, but no per-stop times, frequencies or delays.
  - Calling the network needs a registered participant, Ed25519 request signing and an async callback server.
  - It is not usable from a static site, and it would not give timings anyway.
- **chennaimetrorail.org.** The `wp-json` API exposes only station posts; there are no timetable or fare endpoints.
- **GTFS-realtime.** None is published. The CMRL and Chennai One apps claim live updates, but neither has a public API.

So "live" here means the advisory feed. A small scheduled worker can maintain that feed by watching the timetable PDF and CMRL's announcements, then write `advisories.json`. Its format is documented in `js/live.js`.

## Roadmap to native

1. **Package the PWA.** Wrap it with a TWA (Android) or Capacitor. Then use local notifications for reminders that fire with the app closed.
2. **Nearest station.** Use GPS with the station coordinates from GTFS `stops.txt` to suggest the closest station.
3. **Home-screen widget.** Show the next train, the same way NightShift's Glance widget does.
4. **Advisory worker.** Run it as a Cloudflare Worker or GitHub Action that diffs the timetable PDF and publishes `advisories.json`.
