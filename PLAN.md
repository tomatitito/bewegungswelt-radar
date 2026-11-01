# Bewegungswelt Radar plan

## Goal

Build read-only Android app with Jetpack Compose that displays Bewegungswelt Waldau availability in calendar form.

- `Buchbar` for bookable 30-minute slots
- `Gebucht` for occupied slots
- closed or unknown periods shown separately, never reported as bookable
- no person-related data parsed, logged, persisted, or displayed
- architecture supports adding availability sources without changing core calendar code

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
- timezone: `Europe/Berlin`
- interval semantics: start inclusive, end exclusive
- DST transitions covered by tests
- adjacent and overlapping booking intervals normalized before slot generation

## Compose UI

```text
<CalendarScreen>
  <TopAppBar />
  <WeekNavigator />
  <CalendarGrid>
    day columns
    30-minute rows
    slot cells
  <Legend />
  <SourceDiagnostics />
```

Initial behavior:

- current week on launch
- previous/next week navigation
- pull-to-refresh
- loading, stale-data, empty, and error states
- color plus text/icon; status never communicated by color alone
- booking details contain only date, start, end, and status

On narrow screens, show day pager with week strip instead of shrinking seven columns beyond readability.

## Delivery phases

### Phase 1 — project foundation

- create Android project using Kotlin, Gradle Kotlin DSL, and Jetpack Compose
- add `app`, `core`, and `provider-locaboo` modules
- configure lint, unit tests, and CI-ready Gradle tasks
- define core models and `AvailabilitySource`

**Exit:** app builds and renders fixture-backed calendar.

### Phase 2 — availability domain

- implement interval normalization and 30-minute slot generation
- add opening, booking, closure, unknown, lead-time, and DST tests
- keep algorithm Android-independent for JVM unit tests

**Exit:** sanitized fixtures produce deterministic statuses.

### Phase 3 — Locaboo read-only adapter

- implement cookie-preserving HTTP client
- reproduce opening-hours and bookings requests
- map only required data into `CalendarSnapshot`
- add timeouts, retry policy, and concise diagnostics

**Exit:** app loads real Bewegungswelt calendar without person-related data.

### Phase 4 — production calendar UI

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

### Phase 6 — prove second source

- implement one small fake or second bundled source
- migrate without modifying calendar UI or slot engine
- revise contract based on demonstrated needs

**Exit:** extension boundary is proven by migration, not speculation.

### Phase 7 — trusted external providers

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
- app passes unit tests, Android lint, and build

## Explicit non-goals for MVP

- booking or payment
- authentication
- notifications
- multiple selectable venues
- arbitrary runtime Compose UI plugins
- silent APK installation
- background polling
- long-term booking history
