# Bewegungswelt Radar plan

## Goal

Build read-only Android app with Jetpack Compose that displays Bewegungswelt Waldau availability in calendar form.

- `Buchbar` for eligible booking starts on a 30-minute display grid
- `Gebucht` for occupied slots
- closed or unknown periods shown separately, never reported as bookable
- no person-related data parsed, logged, persisted, or displayed
- architecture supports adding availability sources without changing core calendar code
- opt-in notifications when monitored future slots become newly bookable

Product direction, self-extension instructions, module boundaries, and contract
evolution rules live in [AGENTS.md](AGENTS.md). This file tracks delivery of the
initial read-only Bewegungswelt use case and subsequent milestones.

## Locaboo integration

```text
initialize session
  GET booking page
  retain cookies
  discover/validate provider constants

load calendar
  fetch opening hours, custom opening days, holidays, and lead-time rules
  POST booking interval request
  decode only required fields
  map response to CalendarSnapshot
```

Privacy rules:

- DTOs define only required fields.
- JSON decoder ignores unknown fields.
- `events` payload is not modeled if `booked_locations` is sufficient.
- raw responses are not logged or cached.
- HTTP body logging is disabled in release and tests use sanitized fixtures.

## Availability calculation

```text
for each date
  create slots inside opening windows
  apply custom opening days and closures
  apply booking lead time
  mark overlapping booking intervals as BOOKED
  mark remaining valid intervals as BOOKABLE
  mark missing/inconsistent source data as UNKNOWN
```

Rules:

- slot size: 30 minutes
- a bookable start must fit the full minimum booking duration; private-person
  offers currently start at 60 minutes, not 30
- timezone: `Europe/Berlin`
- interval semantics: start inclusive, end exclusive
- DST transitions covered by tests
- adjacent and overlapping booking intervals normalized before slot generation

## Compose UI

```text
<CalendarScreen>
  <WeekMonthSwitch />
  <PeriodNavigator />
  <MonthGrid /> or <WeekStrip />
  <SelectedDayTimetable />
  <ReadOnlySlotDetails />
```

The live UI implements the dark forest-green design, start-time-only rows, and
month-to-week selection. Green dates have at least one eligible bookable start;
warm gray indicates selection. Loading and failures clear confirmed starts and
show unconfirmed dates, a sanitized diagnostic, and the previous fetch time when
available. There is no separate status legend or persistent offline cache.

Initial behavior:

- current week on launch
- previous/next week navigation
- pull-to-refresh
- loading, stale-data, empty, and error states
- color plus text/icon; status never communicated by color alone
- booking details contain only date, start, minimum duration, and status;
  the half-hour display interval must not be presented as the booking duration

On narrow screens, show the selected day's timetable with a week strip instead
of shrinking seven timetable columns beyond readability. Tapping a month date
opens week mode with that date selected. Slot details remain read-only.

## Newly available slot notifications

This is a required read-only feature, to follow reliable live availability.
Notify when a monitored future interval becomes bookable because a booking was
cancelled, a closure was lifted, or a new date was released for booking.

- Reuse `AvailabilitySource` and the calendar's availability calculation; do not
  infer availability from occupancy alone or duplicate Locaboo rules in a worker.
- Establish the first successful check as a baseline without alerting for all
  existing free slots. A newly enabled or changed monitoring range also needs a
  baseline for previously unobserved intervals.
- Compare fresh, complete observations with the last confirmed state for the same
  source and interval. Alert for confirmed unavailable-to-bookable transitions,
  including dates previously blocked by the verified booking horizon. Unknown,
  failed, or incomplete observations are not evidence of a transition and must
  not overwrite the last confirmed state. Recovery alone must not cause alerts.
- Persist only the minimal source/interval/status, freshness, and deduplication
  state needed across process restarts, never raw responses or personal data.
- Avoid repeat alerts for unchanged availability across refreshes, retries, and
  app restarts. Group adjacent newly available slots to avoid notification floods.
- Use Android WorkManager for network-constrained, best-effort periodic checks,
  with bounded requests and failure backoff. No exact-time or instant-delivery
  promise: Android may defer checks, and short-lived openings may be missed.
- Keep scheduling, monitoring preferences, persistence, notification channels,
  and notification-permission handling in `app/`. Core comparison rules stay
  Android-independent; the provider only supplies data.
- Monitoring is opt-in and can be disabled. Explain permission denial or blocked
  notifications without breaking the calendar. Tapping an alert opens the
  relevant date and refreshes it; an alert never reserves or guarantees a slot.

Accepted preferences: all opening days/times across the full verified booking
horizon, including future date releases rather than a fixed 60-day range. Require
at least 60 continuous free minutes, configurable as 60/90/120; do not sum disjoint
periods or issue separate duration-threshold alerts. Check roughly hourly,
configurable as 15/30/60/120/360 minutes. Follow Android notification/DND settings;
notify again only after confirmed unavailability and reopening. Multi-date checks
use one audible group summary and at most seven date cards to bound floods.
Start with on-device checks; a backend or upstream push integration remains a
separate decision if faster delivery is required.

## Delivery phases

### Phase 1 — project foundation

- create Android project using Kotlin, Gradle Kotlin DSL, and Jetpack Compose
- add `app`, `core`, and `provider-locaboo` modules
- configure lint, unit tests, and CI-ready Gradle tasks
- define core models and `AvailabilitySource`

**Exit:** app builds and renders fixture-backed calendar.

### Phase 2 — availability domain

**Implemented:** `calculateAvailability`, interval normalization, minimum-duration
and start-grid rules, unavailable windows, unknown coverage, and deterministic
boundary/closure/lead-time/DST tests. No provider-specific rules live in core.

- implement interval normalization and 30-minute slot generation
- add opening, booking, closure, unknown, lead-time, and DST tests
- keep algorithm Android-independent for JVM unit tests

**Exit:** sanitized fixtures produce deterministic statuses.

### Phase 3 — Locaboo read-only adapter

**Adapter implemented and live-read validated:** anonymous configuration/calendar
requests, selective streaming decoding, custom hours, closures, lead time and
booking horizon, bounded HTTP retry/timeouts, and sanitized failures. Synthetic
HTTP contract tests run offline. Published offers inform the 60-minute minimum;
server-side booking acceptance and a populated live earliest-start response are
not verified. See [provider notes](provider-locaboo/README.md).

**App integration implemented:** lifecycle-aware `CalendarViewModel`, Android
network permission, navigation-driven requests, pull/button refresh and foreground
refresh, fail-closed loading/error states, and in-memory fetch timestamps.
Production no longer uses synthetic slots. Notifications are implemented in phase 6.

- implement cookie-preserving HTTP client
- reproduce opening-hours and bookings requests
- map only required data into `CalendarSnapshot`
- add timeouts, retry policy, and concise diagnostics

**Exit:** app loads real Bewegungswelt calendar without person-related data.

### Phase 4 — production calendar UI

**Implemented:** live week/day and month navigation, refresh, unknown/error and
last-fetch states, minimum-duration explanation, accessible status labels,
offline Compose tests on API 26/36, and device-free native renders. A real Android
device interaction check remains necessary; no device is connected in the orb.

- implement responsive week/day calendar
- add navigation, refresh, accessibility, stale state, and errors
- add sanitized screenshot/UI tests for key states

**Exit:** usable read-only Android MVP.

### Phase 5 — hardening

- add response contract tests against sanitized captured fixtures
- detect upstream schema/config changes
- disable body logging and verify privacy behavior
- add cache for last successful snapshot only if offline/stale display is needed

**Exit:** upstream failures cannot produce false `Buchbar` slots.

### Phase 6 — newly available slot notifications

**Implemented, device validation pending:** opt-in preferences, configurable
continuous minimum and cadence, network-constrained unique WorkManager checks,
private anonymous baseline/deduplication persistence, full-horizon batched scans
and verified release-boundary comparison, grouped Android notifications,
permission/blocking feedback, date navigation and refresh on taps. Complete scans
commit atomically; failures/unknowns retain confirmed history. JVM tests cover
duration gaps, allowed starts, baselines, releases beyond 60 days, retries/restarts,
failures/recovery, grouping, preference changes, opt-out and scheduling. Native
Compose tests cover APIs 26/36. See README for delivery/crash semantics and limits.

- Remaining: real-device calendar and notification smoke test, including runtime
  permission grant/denial, delayed work, reboot, actual tap refresh, DND and opt-out.

- define monitoring preferences and comparison semantics described above
- persist a minimal baseline and deduplication state
- add opt-in WorkManager checks and Android notification/permission handling
- open the relevant calendar date from an alert and refresh availability
- test first-run baselines, unchanged results, cancellations, newly released
  dates, failures/recovery, range changes, restart deduplication, and opt-out

**Exit:** newly bookable monitored intervals produce useful, non-duplicated
notifications; failed checks never produce false availability alerts.

### Phase 7 — prove second source

- implement one small fake or second bundled source
- migrate without modifying calendar UI or slot engine
- revise contract based on demonstrated needs

**Exit:** extension boundary is proven by migration, not speculation.

### Phase 8 — trusted external providers

Implement the later-phase external provider model documented in
[AGENTS.md](AGENTS.md#trusted-external-providers--later-phase), after bundled
sources have proven the contracts.

**Exit:** separately installed trusted provider APK can supply calendar data without core changes.

## MVP acceptance criteria

- app displays current and adjacent weeks
- valid slots show `Buchbar` or `Gebucht`
- closed and unknown periods cannot appear as `Buchbar`
- refresh uses Locaboo session correctly
- provider location ID is absent from core domain model
- no person-related fields are modeled, logged, stored, or displayed
- Locaboo code depends on core contract; core does not depend on Locaboo
- fixture tests cover interval boundaries, closures, lead time, and DST
- UI remains functional with provider error and shows concise diagnostic
- opt-in monitoring notifies about newly bookable slots without duplicate alerts
- failed/unknown checks and first-run baselines do not generate new-slot alerts
- monitoring can be disabled; notification denial leaves the calendar usable
- app passes unit tests, Android lint, and build

## Explicit non-goals for MVP

- booking or payment
- authentication
- multiple selectable venues
- arbitrary runtime Compose UI plugins
- silent APK installation
- guaranteed real-time alerts or a continuously running monitoring backend
- long-term booking history
