# Headway

A next-train timer for Chennai Metro, built on the official CMRL timetable. It is a mobile-first PWA in plain HTML, CSS and ES modules, with no build step.

## What it does

Headway is for people who ride the same route every day. Save where you board and where you get off, then tell it how soon you'll reach the station. It shows the trains you can actually make.

- **Routes:** one card per saved route. "I'll be at Guindy in Now / 5 / 10 / 15 / 20 / 30 min" picks the arrival time, and the card remembers it.
  - The first train you can make is shown big with a countdown and how long you'll wait on the platform.
  - The next two options come with their waits, and the train you'll just miss is faded.
  - Only direct trains count. Short loops and Green Line trains via CMBT are included when they stop at your destination.
- **States:** last trains (a badge, plus the first train tomorrow), "No more trains tonight", "Metro is closed", and "You'll miss the last train".
- **Reminders:** one tap per train. It fires when you need to set off: departure, minus your arrival time, minus the lead time you choose in Settings.
- **Quick look:** the search button checks any pair of stations without saving them.
- **Lines:** today's official first and last trains and frequency bands.
- **Settings:** today's timetable override, reminder lead, last-train warning, and appearance.

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

- **Routes and reminders:** the same screens as the web app. A reminder posts a live countdown notification straight away, with "I'll take the next one" and "Stop" buttons, and sends an alarm when it's time to set off, even with the app closed.
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
