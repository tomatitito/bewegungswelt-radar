# Project direction and agent instructions

## Product scope

This is a **native Android app built with Kotlin and Jetpack Compose**, not a web
app. The first use case is visualizing Bewegungswelt Waldau calendar and
availability data through Locaboo. Start read-only. The current foundation shows
synthetic fixtures; a live adapter and availability engine are implemented but
not connected to the UI yet. See [PLAN.md](PLAN.md) for remaining integration.

Newly available slot notifications are a required use case, not a speculative
extension. After reliable live availability is in place, support opt-in Android
notifications when a monitored future interval becomes bookable, including a
cancellation or a newly released booking date. Reuse the same source contract and
availability rules as the calendar. Android scheduling, notification permissions,
and minimal persisted comparison/deduplication state belong in `app/`, not the
provider. Background checks are best-effort, not guaranteed real-time alerts.
Establish a baseline without an initial notification flood; failed, incomplete,
or unknown observations must not reset it or produce false new-slot alerts.
Persist no personal booking data or raw responses. See [PLAN.md](PLAN.md) for the
notification milestone and remaining product decisions.

Keep these possible next use cases in mind without implementing them prematurely:

- Booking through the app in the near future, if the upstream system supports it
  and feasibility, authorization, and safe write behavior have been established.
- Enriching the Bewegungswelt calendar with information from other sources.
- Displaying separate calendars, such as children's vaccination appointments or
  event listings from other sources, preferably with a JSON API.

These are architectural directions, not existing features or commitments to a
particular integration. New use cases should be easy to add when concrete needs
arise; do not assume every calendar describes bookable facilities.

## Self-extension model

Follow the principles from Quincy's `qui-dnab` self-extension plan, adapted to
Android:

- Keep a small recovery-oriented core usable when an extension fails.
- Use narrow extension contracts, versioned at external boundaries.
- For runtime providers, use the lifecycle `discover -> validate -> start -> activate -> stop`.
- Make extension-owned resources disposable.
- Preserve the previous working source when activation fails.
- Expose concise, sanitized diagnostics.
- Add capabilities only when a real extension needs them.
- Never let providers access private UI or core internals.

Do not download and execute arbitrary Kotlin/Dex inside the main app; this
conflicts with Android security and store policies. Use two extension levels:

1. **Bundled source modules**, compiled with the app. Locaboo/Bewegungswelt is the
   first implementation and must prove the contract before external loading.
2. **Trusted provider APKs**, only in a later phase: separate Android packages
   discovered and called through a versioned Binder/AIDL contract. Installation
   and trust remain explicit and user-confirmed.

Self-extension means an agent can implement and validate a new adapter against
documented contracts, not that the app silently installs or executes generated
code. Keep one deployable application initially. Split further only when real
use cases or runtime plugin work demonstrate the need.

## Ownership and dependency boundaries

- `app/`: Android composition root, `CalendarViewModel` when needed, source
  selection/composition, and Compose calendar rendering.
- `core/model/`: Android-independent calendar domain, intervals, slots, statuses,
  and diagnostics.
- `core/availability/`: source contract and availability calculation, including
  slot rules and timezone handling.
- `provider-locaboo/`: remote session, HTTP requests, provider IDs (manager,
  widget, location), remote DTOs, and translation into core contracts.

The call path is Compose UI → view model → source contract → provider → client.
Providers depend on core contracts; core never depends on providers. Core must
not know Locaboo URLs, cookies, IDs, or response shapes. Keep provider-specific
location IDs private; do not add a domain `locationId` while only Bewegungswelt
needs it.

The existing read-only contract is `AvailabilitySource`, with a `descriptor` and
`suspend fun loadCalendar(range: CalendarRange): CalendarSnapshot`. Its source of
truth is `core/availability/src/main/kotlin/de/bewegungswelt/radar/availability/AvailabilitySource.kt`;
the models are in `core/model/src/main/kotlin/de/bewegungswelt/radar/model/Calendar.kt`.
`CalendarSnapshot` contains the range, opening windows, bookings, closures, and
fetch timestamp, plus optional booking rules and non-occupancy unavailable windows.
`calculateAvailability` in `core/availability` generates half-hour display slots;
`BOOKABLE` denotes a start where the full minimum booking duration fits, not
permission to book a standalone 30-minute interval. Missing rules leave otherwise
free starts `UNKNOWN`. Source load failures use sanitized
`AvailabilityLoadException`; never replace a failed load with an empty successful
snapshot. `UNKNOWN` must prevent missing or failed upstream data from appearing
bookable.

## Evolving beyond availability

- Keep fetching, domain interpretation, and rendering separate so another source
  can be added without rewriting the calendar UI or the slot engine.
- Availability is one calendar use case, not the universal event model. Do not
  force vaccination appointments or event listings into 30-minute availability
  slots or label them `BOOKABLE`/`BOOKED`. Introduce an appropriate core-neutral
  event contract when the first concrete event source needs it, while preserving
  availability-specific rules in the availability domain.
- Allow future sources to be shown together or as separate calendars. When
  implementing this, preserve source identity and provenance so entries,
  diagnostics, and refresh failures remain attributable to their source. A
  failed optional source must not make other calendars unusable or imply free
  availability.
- Prefer documented JSON APIs where available. Provider-specific formats and
  authentication stay inside adapters, not UI or shared calendar logic.
- Keep read-only sources usable without booking support. Add booking as an
  explicit optional capability when feasible, not as a mandatory method on every
  calendar source. Require user confirmation for writes, handle upstream failures
  and duplicate-submission risks, and refresh authoritative availability after a
  booking. Do not infer booking permission from a displayed free slot.
- The current Bewegungswelt adapter must not parse, log, persist, or display
  personal booking data. Future personal calendars need an explicit privacy and
  access design; their existence must not relax this provider's data minimization.
  Never log raw responses or sensitive appointment details; use sanitized fixtures.
- Prove new contracts with a real use case and contract tests before generalizing.
  Do not build speculative capability registries, a universal calendar schema,
  or an external plugin framework for the read-only MVP.

## Trusted external providers — later phase

Only build this after bundled providers have demonstrated the required contracts.
The provider APK declares manifest metadata and a permission-guarded discovery
service with a versioned AIDL implementation. The host discovers candidates by
intent, verifies package/signature/trust, validates API version and capabilities,
binds and health-checks the candidate, then activates it atomically. Dispose of
resources when stopping or rejecting a provider; failed activation must preserve
the working source.

Return core-neutral calendar data, never Compose components or provider DTOs.
The host renders all UI. Keep read access separate from any future write
capability; no unrestricted filesystem/network capability API, arbitrary runtime
Compose plugins, or silent installation.

The agent extension loop should be: scaffold a provider from a template,
implement the remote adapter, add sanitized contract tests, build the provider
APK, run compatibility checks, let the user install/trust it, then discover and
activate it in the app. Add the template and compatibility command when this
phase is actually implemented.

## Validation

Follow [README.md](README.md) for the pinned toolchain and development setup.
For code changes, run `./gradlew --no-daemon test lintDebug assembleDebug`.
Keep core algorithms Android-independent and cover interval boundaries,
closures, lead time, and DST with deterministic tests. Use sanitized source
fixtures and ensure upstream failures never yield false `Buchbar` slots.
