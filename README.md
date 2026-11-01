# Bewegungswelt Radar

Read-only Android calendar for Bewegungswelt Waldau. This foundation displays a
**synthetic example calendar**, not live availability. See [PLAN.md](PLAN.md) for
the remaining availability engine, Locaboo integration, and production UI work.

The dark calendar opens on the current week in `Europe/Berlin`. Switch to
**Monat** to browse dates; tapping a date opens its week with that day selected.
Forest green means at least one bookable slot, while warm gray marks selection.
The timetable shows start times for 30-minute slots; tap a row for the full time
range and status. Details are read-only, with no booking action. All availability
is synthetic, including the illustrative green dates repeated in each month.
Selection and view mode survive activity recreation. Large text uses more space
for times and omits decorative status icons, keeping the status labels visible.

## Build and check

- Temurin JDK **25.0.5+8 LTS** (build and test runtime)
- Android SDK platform **37.2**, build tools **37.0.0**, and platform-tools
- Gradle **9.8.2** via the checked-in wrapper (no global Gradle required)
- Kotlin **2.4.21**, Android Gradle Plugin **9.5.0**; dependencies are pinned in
  `gradle/libs.versions.toml`
- Compose BOM **2026.09.00**, Activity **1.13.0**, Lifecycle **2.11.0**,
  Robolectric **4.17**
- Android **8.0 / API 26** or newer to run the app

Set `JAVA_HOME` and `ANDROID_HOME` to the installed toolchains, or open the project
in Android Studio and select JDK 25. Java/Kotlin bytecode remains targeted to Java
17; upgrading the build JDK does not raise the minimum supported Android version.
AGP's built-in Kotlin support replaces the separate Kotlin Android plugin.
No secrets, environment templates, accounts, databases, or network permissions
are required by the app.

```sh
./gradlew --no-daemon test lintDebug assembleDebug
```

The debug APK is at `app/build/outputs/apk/debug/app-debug.apk`. Install it on a
connected Android device with `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
`CalendarPreview` in `CalendarScreen.kt` provides an Android Studio Compose preview.

## Modules

- `app`: Android composition root and fixture-backed Compose screen.
- `core:model`: Android-independent calendar intervals, slots, statuses, diagnostics.
- `core:availability`: `AvailabilitySource` contract; depends only on the model.
- `provider-locaboo`: reserved JVM module for the future adapter; depends on core,
  never the other way around. No live requests are implemented yet.

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
25.0.5+8 and Android command-line tools **23.0**, accepts Android SDK licenses,
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
