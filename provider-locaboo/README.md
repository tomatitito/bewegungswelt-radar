# Locaboo provider boundary

This module implements the anonymous, read-only `LocabooAvailabilitySource`,
session handling, selective response decoding, and translation to
`CalendarSnapshot`. Core modules must never depend on this module. The Android
screen is still fixture-backed; connecting it is the next step.

Do not model, persist, or log personal booking details or raw HTTP responses.

## Implemented behavior

- Loads up to 62 days per call in `Europe/Berlin`, validating private-person
  resource/widget configuration on each refresh.
- Calls only the bootstrap page and the three calendar/configuration endpoints
  below; no cart, form, draft, reservation, or checkout endpoints.
- Uses an in-memory anonymous cookie jar, cancellable HTTP calls, 10-second
  connect/read timeouts and a 25-second per-attempt call timeout. Known reads
  retry once after 500 ms for transport failures and HTTP 502/503/504; other
  HTTP failures and invalid configuration fail closed. There is no response
  cache or body logger.
- Streams whitelisted calendar fields. `events`, closure descriptions, unrelated
  customer fields, and price badges are skipped rather than materialized.
- Custom opening dates replace regular weekday hours. Lead time, earliest start,
  current time, and the inclusive latest booking date become non-occupancy
  `unavailableWindows`, distinct from actual bookings.
- Missing required metadata/interval fields, restricted opening rows, and
  offset-free ambiguous/nonexistent DST times fail the entire load with a
  sanitized `AvailabilityLoadException`. Cancellation remains cancellation.
- Normalizes midnight end sentinels (`00:00`, `24:00`, `23:59:59`) to next-day
  midnight; other reversed intervals are rejected rather than guessed.

The official [FAQ](https://www.stuttgart-bewegt-sich.de/entdecke/bewegungswelt-waldau/bwwfragen)
lists 60, 90, 120, 150, and 180-minute offers. The adapter uses the shortest
published offer (60 minutes) and validates the page's half-hour start grid.
`calculateAvailability` therefore marks eligible **booking starts**, not
standalone 30-minute bookings. It checks that the full minimum duration is free.
This is a conservative read-only interpretation of published offers, not proof
of server-side booking acceptance or authorization; no booking was submitted.
Changing the displayed booking duration later must preserve this distinction.

Usage from an app-owned coroutine (not directly from Compose rendering):

```kotlin
val source = LocabooAvailabilitySource()
val snapshot = source.loadCalendar(range)
val slots = calculateAvailability(range, snapshot, clock.instant())
```

On `AvailabilityLoadException`, surface its sanitized `diagnostic` and use
`calculateAvailability(range, null, now)` for unknown data rather than fabricating
an empty successful snapshot. Offline snapshots and notification scheduling are
not implemented. The app still needs ViewModel wiring and `INTERNET` permission.

## Public calendar discovery — 2026-11-01

The official [Stuttgart booking information](https://www.stuttgart-bewegt-sich.de/entdecke/bewegungswelt-waldau/bbwbuchungprivat)
links to Locaboo. The private-person calendar is at
`https://booking.locaboo.com/de/bewegungsweltwaldau/privatpersonen`.
These are observed website endpoints, not a documented or versioned public API.
No bookings, reservations, checkout actions, or login were performed.

### Bootstrap and identity

GET the private-person page and read only the relevant hidden configuration:

| Page field | Observed value |
| --- | --- |
| `manager_id` | `12578` |
| `platform_widget_id` / `widget_id` | `424` |
| `allowed_location_array` | `13001` |
| `calender_snap_timing` | `half-hour` |

Resource `13001` is Bewegungswelt für Privatpersonen. The separate school/club
calendar is not interchangeable with this one. Keep these identifiers inside the
provider, validate discovered configuration, and fail closed if it changes.
Do not decode unrelated page fields, which can contain customer-related IDs.

The bootstrap returned two cookies. Independent Python HTTP replays without
browser headers, authentication, or a CSRF token succeeded. Fresh requests without
bootstrap cookies also succeeded for all three read endpoints below. Cookies are
therefore not proven mandatory for these public reads; preserving an anonymous
session remains compatible with the website flow. Do not persist cookies or
assume they grant booking permission.

### Verified read requests

All paths below are relative to `https://booking.locaboo.com`.

1. `POST /booking/direct_booking_flags`
   - Form body: `manager_id=12578`.
   - Website prerequisite for loading resources.
   - Returned `success: 1` and `flags["13001"].direct_booking: 2`.
   - This is website configuration, not authorization to make app bookings.

2. `POST /de/calendar/load_locations`
   - The browser sends JSON with **`Content-Type: text/plain`**:

     ```json
     {
       "manager_id": "12578",
       "booking_mode": "true",
       "widget_mode": "true",
       "category": "",
       "locations": [13001],
       "platform_resource_part_id": null,
       "platform_resource_id": null
     }
     ```

   - Response is JSON despite an observed `text/html` MIME type.
   - Required fields: `cal_locations` (validate resource identity),
     `cal_meta.location_opentime`, `cal_meta.custom_days`, `lead_times`,
     `allowed_start_dates`, and `max_end_dates`.
   - Metadata includes other resources despite the location filter. Filter every
     relevant collection to `13001` before mapping into core models.

3. `POST /calendar/load_events`
   - `Content-Type: application/x-www-form-urlencoded; charset=UTF-8`.
   - Verified example body (line breaks here are for readability only):

     ```text
     from=2026-11-06&to=2026-11-08&mode=Week_single&date=2026-11-06
     &manager_id=12578&season_mode=live&widget=true
     &locations%5B%5D=13001&platform_widget_id=424
     ```

   - Response keys: `events`, `holidays`, `booked_locations`, `price_badges`.
   - Occupancy is available in `booked_locations` using only `location_id`,
     `date`, `from_hour`, and `to_hour`. Do not model `events` or price badges.
   - `holidays` was `[]` when empty and an object keyed by resource ID when
     populated. Decode only `from`, `to`, `is_closing_time`, and `is_holiday`;
     ignore titles and descriptions.

### Rules and evidence

- Regular opening rows use Sunday = `0`, Friday = `5`, Saturday = `6`.
  The private resource returned Friday 15:00–19:00 and Saturday/Sunday
  10:00–19:00, matching the official private-person information page.
- Custom dates use `day` as an ISO date plus `from_hour` and `to_hour`.
  Website code replaces regular hours on a date with custom rows when present.
  One observed future custom date was 2027-01-06, 10:00–19:00.
- `lead_times` contains resource/date-specific blocked intervals with
  `lead_time_from_hour` and `lead_time_to_hour` in `HH:mm` format. The website
  adds these to displayed occupied intervals. Do not label these as actual
  bookings in our domain. Fetch fresh restrictions on refresh.
- `allowed_start_dates` was an empty array in the observed response. Website code
  supports a resource-keyed earliest timestamp and otherwise uses current time.
  A populated example is still needed to verify decoding and boundary semantics.
- `max_end_dates["13001"]` was `2027-04-01`. Website code blocks dates after
  this date. This is dynamic configuration, not a constant to embed in the app.
- The November 6–8 replay returned **10 occupied intervals**, including intervals
  on November 8, showing that the request's `to` date includes that day's data.
  Convert core's exclusive end date to the preceding date for this request.
- The April 2–4, 2027 replay returned **zero occupied intervals**, despite being
  beyond the configured booking horizon. Empty occupancy alone is not evidence
  of bookability.
- The December 24–31 replay returned closure entries for December 24, 25, 26,
  and 31, with `is_closing_time: true`. Full-day entries ended at `23:59:59`;
  handle that upstream convention deliberately rather than losing the final
  second or creating a spurious free interval.
- Returned dates/times have no UTC offset. The website compares venue-local
  strings; the adapter must resolve them in `Europe/Berlin`, with deterministic
  DST tests rather than treating them as UTC.

### Implementation implications and remaining checks

`CalendarSnapshot` now represents booking rules and non-occupancy restrictions
explicitly, in addition to opening windows, bookings, and closures. Absent booking
rules leave otherwise free starts unknown. Missing required provider metadata
fails the load and must never imply free availability.

Server-side duration acceptance and a live populated earliest-start response
remain unverified. The earliest-start string shape is covered by a synthetic
contract case, not a captured populated response. Ambiguous DST timestamps are
rejected conservatively instead of assuming an upstream offset convention.

During follow-up form inspection, merely opening the booking sidebar triggered
automatic draft-check requests; both returned `success: 0` with date validation
errors. Nothing was manually submitted, and the form was closed. Do not use the
sidebar for read-only checks: its conflict validation can attempt draft writes.
The implemented source never calls it.

Evidence came from the rendered anonymous calendar, its captured request methods
and bodies, the public `events-calendar-booking.js` / `events-booking.js` scripts,
and independent standard-library HTTP replays. No raw HTTP response, cookie value,
or personal booking detail is included in this repository. Synthetic HTTP
contract tests cover the adapter, and temporary live validation exercised the
Kotlin source and engine against ordinary, Christmas, and beyond-horizon dates.
Live requests are not part of the permanent test suite or Android UI yet.
