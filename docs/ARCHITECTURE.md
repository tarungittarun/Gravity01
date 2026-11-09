# Architecture

Decision record for OpenTrack India. Each entry states the constraint, the option that the "obvious"
choice would have produced, and why this one was chosen instead. Code comments carry the local *how*;
this file carries the global *why*.

## Constraints in force

| # | Requirement | Where it bites |
| --- | --- | --- |
| C1 | Offline-first: search + timetable with no network | reference DB in the APK, `syncRefDb` at build time |
| C2 | Ultra-lightweight | 6 AndroidX deps, no Compose/Maps/RxJava/Gson/Room, `resConfigs "en"`, R8 + `shrinkResources` |
| C3 | No API keys, no proxy server of ours | one direct HTTPS call to a public endpoint, authenticated only by constants inside the official app; recovery via on-device Settings |
| C4 | −24 h … +24 h search around a chosen instant | `TrainFinder.runsBetween` is pure and unit-tested |
| C5 | Live position vs schedule, never faked | `PositionEngine` rules + provenance labels on every screen |
| C6 | APK built by GitHub Actions, nothing installed locally | `.github/workflows/android.yml`, `tools/ci/*` static checks |

## Data path

```
upstream public dump (schedules.json, stations.json, trains.json)
        │  tools/build_refdb.py   (deterministic ETL; verifies as it builds)
        ▼
data/ref/refdb.db  ──gzip──►  app/src/main/assets/ref/refdb.dbgz   (5.52 MB)
        │  RefDb.prepare(): extract once into filesDir/ref/refdb.db,
        │  schema-version gated, validated by a PRAGMA quick_check + meta probe
        ▼
RefQueries (read-only SQL, no ORM)     ──►  TrainFinder (pure window filter)  ──► search results
        │
        └─► TrainRoute (stops + segments)  ◄──┐
                                              │  PositionEngine.locate()
ErailWire (public feed → rows) ─► SnapshotCodec ◄── cache file / user endpoint
                                              │
                                       TrainPosition + per-stop delay profile
                                              │
                              TrackInsightView (Canvas) + StopAdapter
```

The timetable never leaves SQLite-as-data on the way to the UI: raw JSON is never parsed at runtime.
That is C1 and C2 in the same decision — 141 908 schedule rows are queryable in ~10 ms from disk, and
a JSON parse of the same would cost an order of magnitude more memory per search.

## Reference DB, schema v3

`meta(k, v)` · `stations` · `trains` · `stops` · `segments` · `station_trains`. Details in
`tools/build_refdb.py`; the app must match it exactly, and `REFDB_SCHEMA_VERSION` in `app/build.gradle`
is asserted equal to `SCHEMA_VERSION` by CI so the two cannot drift silently.

Three properties of the schema carry the whole app:

* **Rail minutes.** Every time is *minutes since midnight of the run's origin date*, unbounded
  (`1900` = 07:40 on day 2). This is what makes an overnight train comparable without a timezone or a
  calendar round-trip, and it is why `RailTime.formatRailMinute` prints `(+1d)` unconditionally: a row
  that reads "23:50 → 04:00" looks like a 4-hour journey when it is 5.
* **`stops` is `WITHOUT ROWID` with `PRIMARY KEY (train_no, seq)`**, plus `idx_stops_station`. The
  clustered layout means a whole route is one contiguous range read; the secondary index is what makes
  "trains at station X" possible at all. No other indexes exist — on 9k/7k rows the query planner's
  scan beats the space and the write cost.
* **`run_days` is a nullable bitmap** (bit 0 = Sunday). `null` means the dump had no calendar and the
  train is treated as running; `0` means "no day" and is filtered out. The distinction is the
  difference between hiding a valid train and offering a cancelled one.

### Why a generated asset and not a download on first run

A first-run download needs a host, a resume path, integrity checking, storage-permission handling and a
failure UI — a small product of its own, and it would break the one claim that matters (works on a
train with no signal). Generating it at build time instead gives: deterministic bytes for a given
commit, the file inside the signed APK, and zero runtime infrastructure. `syncRefDb` in
`app/build.gradle` implements the ladder *asset already in the working tree → local ETL → pinned
release asset*, and fails the build rather than producing an APK that cannot search offline. The ETL
writes to `app/src/main/assets/ref/` itself — the same path the merger packages and the same path this
file documents — because `src/main/assets` is the *only* declared asset root. An earlier revision added
`build/generated/refdb` as a second root for the fallback to write into, and any build where both
copies existed died in `mergeDebugAssets` with `Error: Duplicate resources` on `ref/refdb.db`. Two
roots that can each supply one asset name is a shape that check has no way to catch statically, so the
design avoids it instead.

## Search

`TrainFinder.runsBetween(pairs, targetEpochDay, targetMinuteOfDay, …)` is a pure function over
`List<PairStop>` — no Android, no SQLite, fully unit-tested. `RefQueries.pairStops` supplies the rows
from `station_trains` for the two codes; the finder then does all date reasoning.

For each candidate (train, boarding stop, alighting stop) triple:

1. reject rows where the alighting minute is not strictly after the boarding minute (a reverse-direction
   or corrupt pair) — silently, since counting them as "outside the window" would make the hint lie;
2. compute the origin date that puts the boarding on the target day, then consider `−1 … +1`;
3. keep a candidate when `|boardEpoch − target| ≤ 1440` **and** the train runs on that origin date;
4. choose the closest boarding, breaking an exact tie towards the run still to come;
5. de-duplicate by train number, then order by |Δ| → boarding minute → train number.

Step 4 is why `break` on the first accepted candidate day was replaced: with three candidate origins
in range, taking the first is taking *yesterday's* run whenever it happens to be in the window, which
is wrong for a midnight search. `availability()` (the −2 d … +2 d date chips) probes at 12:00 rather
than 00:00 through the same function, so a chip can never promise a day the list then fails to deliver.

## Live status

```
ProviderChain.build(prefs, http, queries)
   ├─ OverrideProvider   user URL from Settings  →  SourceKind.PROVIDER_OVERRIDE
   ├─ ErailProvider      erail.in getTrains.aspx?TrainNo=…&DataSource=1&Flag=1
   └─ CacheProvider      filesDir/live/<trainNo>.json, ≤ 24 h old  →  SourceKind.CACHED
```

The chain is ordered by *trustworthiness of intent*: an explicit user endpoint first (they configured
it because the default stopped working), then the built-in source, then the cache. Every provider
returns the same `LiveSnapshot`, and `SnapshotCodec` is the single codec for all three paths — the
cache file, the offline record, and the JSON a custom endpoint is documented to return. One codec is a
deliberate constraint: an earlier draft had a second mapper for the "JSON path", which meant the cache
and the wire could drift and the UI would silently show nothing after a reload.

`ErailWire` classifies each field by shape — station code, `HH:MM`, signed integer, platform, status
word — because the servers **drop empty fields**, so a positional parser fails on exactly the
interesting rows (a source station has no arrival). Two consequences worth naming:

* a bare small integer is never read as a delay (`+14` is; `1` is a row index or a day number);
* a bare digit is read as a platform only *after* the times in the row, or when prefixed (`PF 3`).

Both were caught by tests, and both are the kind of bug that shows a confidently wrong number.

`columnHint` in Settings is the escape hatch for the day the columns do change: `1:code,2:name,3:sa,
4:sd,5:aa,6:ad` maps indices explicitly and bypasses the heuristics. It is a supported configuration,
not a debug leftover — it is what turns "the app died in March" into a 10-second fix on a phone.

### Fetching

`core/Http.kt` is ~150 lines over `HttpURLConnection`: one GET, 12 s timeout, one retry, gzip-aware,
and a disk cache in `cacheDir/http/<sha1(url)>` holding `<fetchMillis>\n<body>` for 24 h. No OkHttp,
no Retrofit, no cache library — a 480 kB dependency to do one request would be dishonest against C2,
and hand-rolling keeps the failure taxonomy (`OFFLINE`, `TIMEOUT`, `HTTP`, `EMPTY`, `PARSE`) exactly as
the UI needs it. Cleartext is disabled in the manifest, so an override URL must be HTTPS.

## Position model

`PositionEngine.locate(Request)` is pure: route + optional snapshot + clock (+ optional previous
position and GPS evidence) in, `Answer` out. Rules in priority order, each of which exists because its
absence produces a visible lie:

1. **A confirmed event pins the position.** The marker stays "at X" until the elapsed time exceeds
   X's scheduled halt length; otherwise every refresh flips the label between "at X" and "between X and
   Y" and the train looks like it is stuttering.
2. **Between stations, interpolate on time along the leg**, using `elapsed / (legMin + delay)`, capped
   at **0.97**. Arrival at the next stop is the feed's word to say, not ours.
3. **Never move backwards.** `Request.previous` is compared to the fresh anchor; if the feed re-reported
   an earlier stop (routine on relayed feeds) the last position is held, `held = true`, and the note
   says so.
4. **A measurement outranks a relay**, but only when it disagrees by more than one segment, the fix is
   ≤ 120 m accurate, and the station it names is on this route. Then `DERIVED_GPS`.
5. **Staleness changes provenance.** Beyond 10 minutes a live snapshot is served as `CACHED` and the
   marker is drawn hollow; beyond 6 hours it is history and still shown (that is what offline-first is
   for) with the age on screen.
6. **No invented precision.** An ETA exists only when the remaining leg is ≤ 90 minutes and the result
   is still in the future; otherwise `etaNextMinute = null` and the copy reads "between X and Y".
7. **A report off the schedule is unresolved**, not snapped to the nearest plausible stop — that is how
   these apps generate wrong-train complaints.

`delayProfile` carries the anchor's delay *forward* onto upcoming stops (how station boards phrase it)
and never backwards: a stop the train has left either has its own confirmed number or none. "Unknown"
renders as `—`, never `0`, because `0` on a board means "confirmed on time".

## Sensors

`SensorPositioner` takes a single `LocationManager` fix, on demand, never a stream, and caps its
influence: accuracy > 250 m contributes nothing, and the correction is limited to 45 km along the
route. `ACCESS_COARSE_LOCATION` only — a network fix is enough to answer "which station am I near", and
requesting `FINE_LOCATION` would need a runtime permission the feature does not justify. The setting
defaults to off.

## UI

Three screens, ViewBinding, no navigation component, no fragments:

* `MainActivity` — mode toggle (station pair / train number-name), date chips, results list.
* `TrainActivity` — headline position, provenance chip, `TrackInsightView`, stop table.
* `SettingsActivity` — override URL, column map, refresh interval, cache clear, data state.

`TrackInsightView` is the app's one piece of custom drawing: a time–distance plot with the schedule
polyline in grey and the live trace in green, filled up to the marker, hour gridlines and km on the
axes, an amber "now" rule, and the delay band shaded between the two lines. The reason for drawing a
chart instead of a map is that the traveller's question is a relation between time and distance, not a
place: the horizontal gap between the two lines *is* the delay, and it survives a tunnel, a dead SIM and
a missing tile provider. The view holds no state and draws a fixed dark card, so the colour semantics
cannot silently become unreadable under a light system theme.

ViewModels resolve the repository through `App.repository()` inside coroutines rather than taking it
as a constructor argument. That is what makes first-launch extraction a suspend step instead of a crash:
the screen is drawn while the DB is still being installed, and `Phase` drives the progress line.

## Size budget (C2)

| | |
| --- | --- |
| `refdb.dbgz` asset | 5 520 974 B — the dominant term, and the thing that makes C1 possible |
| Kotlin + 6 AndroidX artifacts | R8-shrunk release classes; debug is larger and is not what users install |
| Resources | ~15 XML files, one vector icon, `resConfigs "en"` (drops ~700 kB of AndroidX translations) |
| Deliberately absent | Compose runtime (~2.5 MB), Maps SDK + Play services (~9 MB with tiles), OkHttp+Retrofit+Gson (~1.4 MB), Room (~1 MB + KSP), Coil/Glide, RxJava, Firebase |

The rule applied throughout: a dependency has to remove code we would otherwise *struggle* to write,
not merely code we would rather not write. `HttpURLConnection`, `SQLiteDatabase`, `MiniJson` and a
`Canvas` are the boring, permanent APIs — they will still compile in 2033, which for an app whose
users are on decade-old phones in tunnels is the point.

## Build and CI (C6)

`gradle/wrapper/gradle-wrapper.properties` pins Gradle 8.7; the wrapper jar is committed, and `gradlew`
is the standard script, so a clone plus JDK 17 is a complete toolchain. `android.yml` then runs:

* **checks** — three Python static analyses over the sources (`tools/ci/`): brace/string-aware lexical
  integrity, cross-file member references, and every `R.*` / ViewBinding field against the layouts.
  These exist because the development loop here has no local compiler: a file truncated by a tool, or a
  call to a helper that was never written, has to be caught in seconds rather than in a 12-minute Gradle
  run. `check_resources.py` was verified against deliberately injected typos.
* **build** — ETL, ETL verification, unit tests, `assembleDebug`, an APK size line in the summary, and
  the artifact.
* **release** — tag-only: `assembleRelease` (R8 + `shrinkResources`) with a transient keystore when no
  signing secret is configured, attached to the release.

The ETL is a build step rather than a committed blob *in CI* so the asset can be regenerated and
re-verified from source at any commit; the `--profile halts` output is reproducible (fixed gzip
timestamp, deterministic ordering), which is what makes "same commit → same bytes" checkable.

## Testing

Unit tests are the whole strategy, on purpose: they run in seconds on any machine, need no emulator
image, and cover every rule that can fail silently. Emulator/UI tests were rejected rather than
postponed — they would need a device image, network stubs and a maintenance bill, in exchange for
coverage of `RecyclerView` scrolling. The Android-only surface (`RefDb` extraction, `SQLiteDatabase`
queries, the location callback) is instead covered by *verification at build time* in the ETL and by the
`Phase.Broken` path in the UI, which states the failure in words.

Deliberate test choices worth keeping:

* `RailTime`'s calendar maths is compared against `java.time` over ±60 years rather than asserted on a
  few dates; a one-day error moves every search and every running-day answer.
* `ErailWire`'s fixtures are hand-built from the documented framing, not captured from the site: a
  captured body starts failing the day the site changes, and the property under test is "tolerates
  changed column order".
* `SnapshotCodecTest` round-trips. A one-directional test cannot see an encode/decode key mismatch, and
  this codec is read back from disk on every cold start; an earlier draft lost the anchor station
  exactly that way.
* `PositionEngineTest` asserts the *bounds* (`fraction == 0.97` at saturation, `held` on a rewind,
  `CACHED` after `STALE_MS`, `null` ETA for a long leg) instead of snapshotting prose.

## Rejected alternatives

| Option | Why not |
| --- | --- |
| Room + KSP | a code generator, ~1 MB, and a schema language, to run read-only queries over a file we generate ourselves |
| Parsing the upstream JSON at runtime | 100× the memory per search, and it cannot index "trains at station X" |
| Retrofit/OkHttp/Gson | 1.4 MB for one GET with a cache we need to size ourselves anyway |
| Google Maps + a marker | a key, a tile provider, network in tunnels, and it still cannot express "14 min late at RTM" |
| A proxy server aggregating third-party APIs | exactly the fragile, key-bearing, always-on dependency C3 forbids |
| ~~NTES as the primary source~~ **now the primary source** | an earlier reading of this problem was wrong, and it is worth keeping as the reason the app once showed timetables: the *web* endpoints of NTES/NPRTI do need a session or a key, but the **mobile** API (`enquiry.indianrail.gov.in/crisns/AppServAnd`) needs neither. It takes a POST whose body is `MD5(plain+secret)#HEX(BASE64(AES-128-CBC(plain)))` under a Dalvik user agent, with the AES key, IV and signature secret compiled into the official app and shared by every client — constants, not credentials, so C3 still holds. It answers per-stop actual times, platforms, delays and a last-updated stamp, which is the difference between a tracker and a timetable. `remote/NtesWire.kt` implements the framing; `NtesProvider` is tried before the timetable source and falls back to it, labelled, if upstream ever rotates the crypto. |
| Compose | 2.5 MB and a lifecycle model that buys nothing for three linear screens |
| A `train_geo` table of route polylines | the drawn line is schedule-vs-live, not geography; ~2 MB for a shape nobody looks at |
| Raw-JSON cache instead of SQLite | no `station_trains` index, so "trains at this station" becomes a full scan per keystroke |

## Known gaps

* `SensorPositioner` takes one fix per screen visit; there is no continuous ride mode (by design for v1,
  and the interval policy in `Prefs` is where a future one would live).
* No notifications: the app is a pull-based tracker. A "train is 20 min from your station" alert needs
  a foreground service and a battery conversation this project has not had yet.
* Train-name search relies on the ETL's `search_token`; names the dump spells differently will need the
  same alias table `STATION_SYNONYMS` provides for stations.
* The live path is untested against the real endpoint from CI (no network in unit tests, and no
  contract-test harness for a source that has no documented schema). The provider's degradation modes are
  tested; its liveness is an operational property, monitored by the provenance label the user sees.
