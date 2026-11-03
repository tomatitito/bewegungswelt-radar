# Bewegungswelt Radar

Read-only Android calendar for Bewegungswelt Waldau, using anonymous live
Locaboo data, with opt-in newly-available-slot notifications. See [PLAN.md](PLAN.md)
for remaining hardening and device validation. There are no booking or payment actions.

The dark calendar opens on the current week in `Europe/Berlin`. Switch to
**Monat** to browse dates; tapping a date opens its week with that day selected.
Forest green means at least one bookable slot; muted charcoal gray means a
confirmed day with no bookable starts, while warm light gray marks selection.
Unconfirmed days retain the neutral dark background. All dates remain tappable.
The timetable shows possible booking starts on a 30-minute grid inside opening
windows, not 30-minute bookings. `Buchbar` requires at least the published minimum
duration (currently 60 minutes) to remain free; `Nicht buchbar` also covers past
starts, restrictions, and insufficient remaining time, not just closed doors.
Tap a row for read-only details, and recheck rules and availability in Locaboo
before booking. Server-side booking acceptance is not guaranteed by this display.

Navigation loads the visible week or month. Pull down or tap **Aktualisieren**
to refresh; returning to the foreground also refreshes the current period.
The fetch time is shown in venue time. While loading or after a failure, dates
are unconfirmed and old green slots are hidden. A failed refresh retains only
the previous snapshot in memory for its timestamp/rules, not confirmed slots;
there is no disk cache. Navigation cancels obsolete requests. Past starts are
re-evaluated every 30 seconds while foregrounded, without background polling.
Selection and view mode survive activity recreation. Large text uses more space
for times and omits decorative status icons, keeping the status labels visible.

## Newly available times

Open **Neue freie Zeiten · Benachrichtigungen** to opt in. Monitor all opening
days/times over the full verified Locaboo booking horizon, including new date
releases beyond 60 days. The minimum continuous free duration defaults to
**60 minutes**, selectable as 60/90/120; this is one minimum, not three threshold
alerts. The venue's minimum and allowed start times still apply. Disjoint free
periods never add up to a qualifying interval.

Checks default to roughly **hourly**, selectable as 15/30/60/120/360 minutes.
WorkManager requires a network connection and uses failure backoff. Android may
delay checks, including after reboot or while idle; brief openings can be missed.
There is no real-time guarantee. Android notification permission is requested
only on activation (API 33+); channel settings and Do Not Disturb govern delivery.
Denial leaves the calendar usable. Disable monitoring from the same dialog.

The first complete scan is silent. Cancellations, lifted restrictions, and newly
released dates can then alert; unchanged availability does not repeat across
restarts. Duration/booking-rule changes or re-enabling create a silent baseline;
cadence changes retain it. Adjacent new booking **starts** are grouped per date.
Multi-date releases use one audible Android group summary and up to seven date
cards; all dates remain accessible in the calendar. Tapping opens a date and
refreshes it. An alert neither reserves nor guarantees a slot.

Only anonymous future start/status comparisons, source/rule identity, the verified
horizon, preferences and check time are persisted privately. No snapshots, raw
responses, cookies or personal booking data are persisted. Failed/incomplete or
unknown scans preserve the last confirmed state and release boundary. Blocked
notifications do not queue a backlog for later permission restoration. Comparison
state is saved before notification dispatch to favor at-most-once delivery: a
process death between saving and posting may lose an alert rather than repeat it.
Scans use at most 62 days per request and an eight-minute work budget. An
implausible horizon over ten years fails closed instead of silently truncating.

Validation is offline JVM/Compose native rendering on APIs 26/36. A real-device
smoke test remains required for background timing, runtime permissions, reboot,
notification taps and DND behavior; no Android device is connected in the orb.

## Build and check

- Temurin JDK **25.0.3+9 LTS** (build and test runtime)
- Android SDK platform **37.2**, build tools **37.0.0**, and platform-tools
- Gradle **9.8.2** via the checked-in wrapper (no global Gradle required)
- Kotlin **2.4.21**, Android Gradle Plugin **9.5.0**; dependencies are pinned in
  `gradle/libs.versions.toml`
- Compose BOM **2026.09.00**, Activity **1.13.0**, Lifecycle **2.11.0**,
  WorkManager **2.12.0**, Robolectric **4.17**
- Android **8.0 / API 26** or newer to run the app

Set `JAVA_HOME` and `ANDROID_HOME` to the installed toolchains, or open the project
in Android Studio and select JDK 25. Java/Kotlin bytecode remains targeted to Java
17; upgrading the build JDK does not raise the minimum supported Android version.
AGP's built-in Kotlin support replaces the separate Kotlin Android plugin.
No secrets, environment templates, accounts, or databases are required. The app
declares Android's normal `INTERNET` permission; it has no runtime permission
prompt for read-only calendar access.

**App-Update** opens a manual update check against this repository's stable
GitHub Releases. A newer APK downloads only after confirmation and must match
the app's package, advertised version, and installed signing certificate.
Android's package installer asks for installation confirmation. On first use,
allow this app to install apps in Android Settings, then return and tap
**Installieren**. There are no automatic checks or silent installations.
Downloads use a private temporary cache; if Android removes it or the process
is killed, check/download again. Closing the dialog does not cancel an active
download. Network/validation failures offer retry, never an unchecked install.
Release signing and publishing setup is documented in [RELEASE.md](RELEASE.md).
Existing debug installations need a one-time reinstall to switch to the
permanent release signing key; differently signed APKs cannot update each other.

```sh
./gradlew --no-daemon test lintDebug assembleDebug
```

The debug APK is at `app/build/outputs/apk/debug/app-debug.apk`. Install it on a
connected Android device with `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
`CalendarPreview` in `CalendarScreen.kt` provides an Android Studio Compose preview.

## Modules

- `app`: Android composition root, lifecycle-aware `CalendarViewModel`, and live
  Compose calendar. Synthetic data is limited to previews and offline tests.
- `core:model`: Android-independent calendar intervals, slots, statuses, diagnostics.
- `core:availability`: `AvailabilitySource` contract and deterministic half-hour
  booking-start calculation, including minimum duration, restrictions, and DST.
- `provider-locaboo`: anonymous read-only HTTP adapter; depends on core, never the
  other way around. See its [integration notes](provider-locaboo/README.md) for
  observed endpoints, privacy behavior, and limits of booking-rule verification.

All intervals are start-inclusive and end-exclusive. The venue time zone is
`Europe/Berlin`. No personal booking data is modeled or logged.

## Dependency policy

Prefer the newest mutually compatible stable toolchain and library releases,
using the current supported LTS JDK. Pin exact versions for reproducible builds;
do not use floating versions or alpha, beta, or RC releases by default. Verify
upgrades with the build/check command above, including the Compose smoke test on
the minimum and latest API supported by stable Espresso (currently APIs 26 and
36). Stable Espresso 3.7.0 still calls `InputManager.getInstance()`, removed in
API 37, so this Compose rule does not yet run on that API. This does not lower
the app's compile or target SDK. Robolectric test JVMs explicitly enable native
access and export its file-descriptor bridge's JDK-internal API for JDK 25;
these flags do not affect the Android app.
JUnit stays on the latest 4.x release because Robolectric's runner and the
Compose rule use JUnit 4.

## Amp orbs

`.agents/setup` installs missing Debian packages, checksum-verified Temurin JDK
25.0.3+9 and Android command-line tools **23.0**, accepts Android SDK licenses,
installs SDK 37.2, and runs the build/check command above to populate Gradle
caches. The JDK archive is pinned for Linux x64, the architecture of this project's
orbs. Running setup requires network access and passwordless sudo when system
packages are missing. SDK license acceptance is automatic in this development
environment.

Setup persists `JAVA_HOME`, `ANDROID_HOME`, and tool paths in
`~/.config/amp/bewegungswelt-radar.env`, loaded by `~/.bash_profile` and
`~/.bashrc` throughout the project orb. The hook does not depend on the initial
working directory: Amp starts its login shell before changing to the repository, and its
non-login child shells inherit that environment. Updating setup refreshes this
env file without duplicating the login hook. It checks existing installations
before downloading tools. Gradle handles incremental dependency resolution and
build caching; there is no separate cache marker. Worker count and
compiler/Gradle heaps are bounded so builds fit small orbs.

`.agents/resume` explicitly loads the saved environment before checking toolchain
readiness, even when invoked without a login shell; it never installs dependencies
or starts services. Temurin bundles its certificate store inside the JDK rather
than using Debian's `/etc/ssl/certs/java` link, so regenerating the orb's system
certificate directory does not remove the JDK trust store.
No emulator or Android system image is installed in orbs. Native UI interaction
needs a real Android device or an Android-capable runner. Build, lint, JVM unit
tests, and the Robolectric Compose smoke test run without one.

After these files reach the default branch, Amp can snapshot the installed tools
and caches for subsequent fresh orbs. Run setup manually in an existing orb with
`.agents/setup`, then open a new login shell to pick up its environment.
