# Signed APK releases

The updater uses stable GitHub Releases in **tomatitito/bewegungswelt-radar**.
Release `v<versionName>` must contain the signed asset
`bewegungswelt-radar-<versionCode>.apk`; it must not be a prerelease. The first
updater release uses `versionCode = 2`, `versionName = "0.2.0"`, tag `v0.2.0`,
and asset `bewegungswelt-radar-2.apk`. The accompanying `.apk.sha256` file is a
download-integrity check, not a substitute for Android signature verification.

Set both version fields in `app/build.gradle.kts` under `android.defaultConfig`:
`versionCode` is Android's strictly increasing update counter; `versionName` is
the displayed numeric version and supplies the `v<versionName>` tag. For example,
the next release after code 2 / `0.2.0` could be code 3 / `0.2.1`.

## Permanent signing identity

Use one permanent release signing key for this application. Losing it prevents
updates of existing installations; generating a new key for every release does
not work. Keep encrypted, offline backups of the keystore, alias, and passwords
in at least two independently recoverable locations. Limit access to release
maintainers, test recovery, and record the public certificate SHA-256 fingerprint.
GitHub Secrets are not the only backup. Never commit a keystore or passwords,
paste them in a chat/thread, or put passwords in command arguments or shell
history. Base64 is encoding, not encryption.

On a trusted machine with the README's JDK installed, create the key **outside
the checkout** (example paths below are placeholders):

```sh
umask 077
mkdir -p "$HOME/.private/radar-signing"
keytool -genkeypair -keystore "$HOME/.private/radar-signing/release.p12" \
  -storetype PKCS12 -alias radar-release -keyalg RSA -keysize 4096 -validity 10000
keytool -list -v -keystore "$HOME/.private/radar-signing/release.p12" \
  -alias radar-release
```

Answer keytool's password and certificate prompts locally. PKCS12 normally uses
the same store and key password: configure both secrets accordingly. If a release
key already exists, **reuse it**, securely copy its original keystore from backup,
and verify its alias and certificate fingerprint with `keytool -list` instead
of generating a replacement. Keep the certificate identity free of unnecessary
personal information. Do not use the Android debug key.

An authorized maintainer sets repository Actions secrets with GitHub CLI on that
trusted machine. These are writes to GitHub and require explicit authorization.
The password/alias commands below prompt interactively; do not add `--body` with
a secret literal. The base64 command uses GNU base64 (on macOS use `base64 -i
<path>` instead of `base64 --wrap=0 <path>`).

```sh
base64 --wrap=0 "$HOME/.private/radar-signing/release.p12" |
  gh secret set ANDROID_KEYSTORE_BASE64 --repo tomatitito/bewegungswelt-radar
gh secret set ANDROID_KEYSTORE_PASSWORD --repo tomatitito/bewegungswelt-radar
gh secret set ANDROID_KEY_ALIAS --repo tomatitito/bewegungswelt-radar
gh secret set ANDROID_KEY_PASSWORD --repo tomatitito/bewegungswelt-radar
```

Alternatively, supply each value from a protected local file with `gh secret set
NAME --repo tomatitito/bewegungswelt-radar < /secure/path/to/value`; avoid trailing
newlines in password/alias files. Protect and securely dispose of temporary
secret files according to the machine's storage policy. Never print secrets to
verify them. Restrict repository write access, protect release tags against
replacement/deletion, and review workflow/build changes before merging to `main`:
code running in a main-branch release can access signing secrets.

## Build and publish

`app/build.gradle.kts` consumes `RADAR_KEYSTORE_PATH`,
`RADAR_KEYSTORE_PASSWORD`, `RADAR_KEY_ALIAS`, and `RADAR_KEY_PASSWORD` for release
signing. Release builds must fail closed if any value is missing; debug builds
and ordinary PR checks must not need these secrets. The release workflow maps
the four repository secrets to these variables, decodes the keystore under the
runner's private temporary directory, verifies the APK's signer against that
keystore, and cleans signing material in an `always()` step. It never uploads
the keystore, and does not use the Gradle action cache for signing builds.

Signer verification requires an **exact certificate match** with the configured
keystore. This release path supports the same permanent signer only, not signing
key rotation or proof-of-rotation lineages. Do not change the configured key to
attempt rotation; that needs a separate, reviewed migration and device QA.

`release.yml` runs on every push to **`main`**, not on tag pushes, feature branches,
or pull requests. `.github/scripts/release.py` reads Gradle's version and checks
GitHub before requesting signing secrets or installing build tools. A complete
stable release for the same version/code is a no-op; ordinary commits need not
bump the version. An incomplete release, reused name with a different code, or
unpublished tag pointing to another commit fails visibly rather than being
silently skipped or overwritten.

For a new release, the workflow runs
`./gradlew --no-daemon test lintDebug assembleDebug assembleRelease`, verifies the
signer, and stages the APK/checksum. Only then does it create a lightweight tag
at the **exact tested commit** and publish its release in the same job. Failed
builds/signature checks create no tag or release. GitHub's built-in token needs
`contents: write`; repository/tag rules must permit this workflow to create tags
and releases. No extra personal access token is needed: tags created with
`GITHUB_TOKEN` normally do not trigger other workflows, so this job publishes
directly rather than depending on a second tag-triggered workflow.

All versions share a repository-wide release concurrency group. The release
decision is checked again immediately before publishing: a new version's code
must exceed stable latest's code. Genuine GitHub `Not Found` 404 responses allow
the first release; other API failures and malformed responses fail closed.
GitHub concurrency is not FIFO and can replace pending runs, so not every rapid
push is guaranteed a separate release. The next queued version must still pass
the monotonic code check. Do not bypass these safeguards through concurrent
manual/API edits, moving tags, changing latest, or replacing published APKs.

1. Confirm the four signing secrets and permanent key are configured, and review
   the intended code. Run the README checks and real-device QA below.
2. When ready to release, increase `versionCode` and set a new stable numeric
   `versionName` in `app/build.gradle.kts`. Review/commit the version bump with the
   intended app changes. **Pushing/merging this version to `main` authorizes its
   automatic publication.** Do not create or push a tag manually.
3. Inspect the Release APK workflow and generated GitHub Release. It must be
   published, non-draft, non-prerelease, and contain the expected signed APK and
   checksum. The tag must resolve to the commit tested by that workflow run.

The first main-branch push after installing this automation will publish the
current Gradle version if it is not released yet, even without an additional
version bump. Subsequent pushes of an already released version skip signing and
publication while the ordinary Android CI checks still run.

If publication fails **after tag creation**, inspect the remote state first. If
there is no release, rerun the original workflow at the same commit: an existing
tag may be reused only when it resolves to that exact SHA. A different commit
must use a new version/code; never move a tag to retry changed code. A partially
published release (including a draft or missing assets) is refused, not repaired
or overwritten automatically. Prefer a new reviewed version over deleting or
replacing a release that users may already have downloaded.

Release-decision tests run offline in both workflows and can be run locally:

```sh
python3 -m unittest discover -s .github/scripts -p 'test_*.py' -v
```

Only the release job has `contents: write`. Signing secrets are not used by the
PR workflow. Do not publish from unreviewed commits or untrusted forks. A local
debug APK is not a releasable substitute for the signed release APK.

## Device QA and installation constraints

Native installation/update QA needs a real Android device or connected Android
runner; an orb's browser emulation is not Android testing.

- Verify the downloaded APK checksum and signing certificate; confirm package
  `de.bewegungswelt.radar`, expected version name/code, and the permanent key.
- An existing debug installation **cannot update** to an APK signed with a
  different release key. Uninstall the debug app and install the release once;
  this **loses local app data/settings**. Warn testers before uninstalling.
  Thereafter test release-to-release updates with the same signing key and a
  strictly higher version code, preserving local state.
- On Android 8/API 26 and a current supported device, open the app and explicitly
  request an update. Confirm a newer stable release is offered with its version,
  while the current version, older versions, prereleases, missing/malformed
  assets, and network failures do not offer an unsafe update or crash the app.
- Check download cancellation/failure, interrupted downloads, invalid APKs, and
  retry behavior. Errors must not expose response bodies or sensitive data.
- Deny the per-app “install unknown apps” permission, then grant it through
  Settings and retry. `REQUEST_INSTALL_PACKAGES` only permits asking the Android
  package installer: it cannot silently install. The user must confirm the
  system installation dialog and may cancel. Test both paths.
- Install the newer same-key release through the updater, reopen it, confirm
  its version and retained state, and smoke-test live calendar refresh/navigation
  and offline error handling. Confirm there is no automatic background install.

Key provisioning and device QA remain maintainer actions. Once this workflow is
on `main`, an unreleased Gradle version on a main-branch push is published
automatically; no further manual tag or release action is required.
