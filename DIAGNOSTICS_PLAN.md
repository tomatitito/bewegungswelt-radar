# Locaboo query diagnostics plan

## Goal

Add a private, bounded diagnostics screen behind the calendar's three-dot menu.
Record when the app queries Locaboo and enough anonymous aggregate information to
estimate how availability changes between checks.

Query timestamps cannot establish exact booking times. Comparing consecutive
successful observations can only show that availability changed between those
checks.

## Data flow

```text
foreground refresh
  CalendarViewModel
    start diagnostic entry
    source.loadCalendar(range)
    record outcome and anonymous availability counts

background check
  AvailabilityWorker
    start diagnostic entry
    load booking-horizon batches
    record batch count, outcome and anonymous availability counts
```

Keep diagnostics in `app/`. Do not add logging or UI responsibilities to
`provider-locaboo/` or core modules.

## Persisted model

```kotlin
data class QueryLogEntry(
    val startedAt: Instant,
    val finishedAt: Instant,
    val trigger: QueryTrigger, // CALENDAR or BACKGROUND
    val range: CalendarRange,
    val batchCount: Int,
    val outcome: QueryOutcome, // SUCCESS, FAILURE or CANCELLED
    val bookableStarts: Int?,
    val bookedHalfHours: Int?,
    val bookingHorizonEndExclusive: Instant?,
)
```

Persist only:

- timestamps and elapsed duration
- foreground/background trigger
- requested date range
- number of logical source batches
- sanitized outcome category
- aggregate bookable-start and booked-half-hour counts
- verified booking horizon

Never persist:

- raw HTTP responses
- endpoint URLs or request bodies
- cookies
- exception messages or stack traces
- names, booking descriptions or other personal data

Retain at most 200 entries and delete entries older than 30 days. Store data in a
private app-owned `SharedPreferences` file with atomic writes. Invalid persisted
data must fail closed and remain deletable.

## File layout

```text
app/src/main/kotlin/de/bewegungswelt/radar/
├── QueryDiagnostics.kt       # models, sanitization and bounded private store
├── DiagnosticsScreen.kt      # diagnostics UI
├── CalendarViewModel.kt      # foreground query recording
├── AvailabilityWorker.kt     # background scan recording
├── CalendarScreen.kt         # three-dot overflow menu
└── MainActivity.kt           # menu and diagnostics-screen state
```

## Recording behavior

### Foreground calendar

Record one entry around each `AvailabilitySource.loadCalendar(range)` call.
Record cancelled navigation requests as `CANCELLED`, because an upstream query
may already have started. Do not treat cancellation as a user-visible failure.

```text
load(range)
  begin CALENDAR entry
  try source.loadCalendar(range)
    calculate aggregate counts
    finish SUCCESS
  catch cancellation
    finish CANCELLED
    rethrow
  catch failure
    finish sanitized FAILURE
```

### Background monitoring

Record one logical entry for each complete worker scan. Count every horizon batch
loaded by `monitorAvailability`. A failed or timed-out scan records its attempted
range and completed batch count without changing notification comparison state.

```text
monitorAvailability
  begin BACKGROUND entry
  load discovery day
  load remaining horizon batches
  if complete
    calculate aggregate counts
    finish SUCCESS
  else
    finish sanitized FAILURE or CANCELLED
```

Use fixed failure categories such as `NETWORK`, `TIMEOUT`, `INVALID_DATA`, and
`INTERNAL`. Never persist `Throwable.message`.

## Measuring availability changes

Successful entries provide coarse observations:

```text
09:42  date first observed inside booking horizon
       3 bookable starts
       13 booked half-hours

10:43  1 bookable start
       15 booked half-hours
```

This supports statements such as “two half-hours became booked between 09:42 and
10:43.” It cannot prove who booked them, whether they were reserved through
another access path, or the exact transition time. WorkManager delays and the
configured cadence determine measurement resolution.

Do not notify about these aggregate changes. Existing newly-available-slot
notification behavior remains unchanged.

## UI

```text
<CalendarScreen>
  <ThreeDotMenu>
    Neue freie Zeiten
    App-Update
    Diagnose

<DiagnosticsScreen>
  logging status and retention note
  entries grouped by day
  timestamp and elapsed duration
  foreground/background trigger
  range and batch count
  success/failure/cancelled state
  aggregate bookable/booked counts when successful
  [Kopieren] [Löschen]
```

Add a vertical three-dot button to the calendar header. Move secondary actions
for notification settings and app updates into its overflow menu. Keep calendar
refresh controls outside the menu.

Open diagnostics as an app-owned full-screen Compose view without adding a
navigation framework. Android back and the screen's back button return to the
calendar.

`Kopieren` copies the currently retained sanitized entries as plain text.
`Löschen` requires confirmation and clears only diagnostic entries. Do not add
file export, sharing permissions, or automatic uploads.

## Tests

### Store tests

- retains at most 200 entries
- removes entries older than 30 days
- uses deterministic newest-first ordering
- handles corrupted persisted data safely
- serializes concurrent foreground and background writes
- never persists raw exception text
- clears only diagnostic history

### Foreground tests

- successful load records one entry and aggregate counts
- failed load records sanitized failure category
- cancelled obsolete request records cancellation
- clock-controlled elapsed duration is deterministic

### Background tests

- complete scan records one logical entry
- multi-batch scan records correct batch count and complete range
- failure records completed batches without advancing notification history
- timeout and cancellation receive distinct outcomes
- disabled monitoring performs no Locaboo query and records no entry

### Compose tests

- three-dot button has accessible description
- menu exposes notification settings, app update, and diagnostics
- diagnostics entries are grouped by day
- success, failure, and cancellation remain distinguishable without color alone
- copy action contains only sanitized fields
- delete confirmation clears entries
- back navigation returns to calendar
- large text remains usable

## Documentation

Update `README.md` with:

- diagnostics location
- retained fields and limits
- privacy exclusions
- coarse measurement limitation
- copy/delete behavior

Keep `AGENTS.md` privacy and module boundaries unchanged.

## Validation

```sh
python3 -m unittest discover -s .github/scripts -p 'test_*.py' -v
./gradlew --no-daemon test lintDebug assembleDebug
```

Perform real-device checks for:

- foreground refresh entry
- delayed background worker entry
- multi-batch horizon scan
- process restart persistence
- 30-day/count retention behavior
- clipboard output
- delete confirmation
- Android back navigation
- no personal or raw provider data in diagnostics

## Delivery order

1. Add diagnostics model and bounded private store with tests.
2. Instrument foreground calendar loads.
3. Instrument background monitoring scans.
4. Add three-dot overflow menu.
5. Add diagnostics screen, copy, delete, and back handling.
6. Add Compose tests and README documentation.
7. Run full validation and real-device smoke test.
8. Bump version and publish a signed release after device validation.
