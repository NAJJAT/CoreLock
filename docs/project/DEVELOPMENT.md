# Building and testing CoreLock

For contributors: how to build the app, run the tests, and use the debug tools. The
engineering rules every change follows are in [CLAUDE.md](../../CLAUDE.md).

## Requirements

- Android Studio (recent stable) with JDK 17
- Android SDK: compile and target API 36, minimum API 24

## Debug build

```bash
./gradlew :app:assembleEnterpriseDebug
```

On Windows use `gradlew.bat`.

## Release build

Release builds **refuse to sign with the debug key**. CI checks that an unsigned release
fails.

1. Create a keystore (one time), outside the repository:

   ```bash
   keytool -genkey -v -keystore privacyguard-release.jks -alias privacyguard \
     -keyalg RSA -keysize 4096 -validity 10000 \
     -dname "CN=CoreLock, OU=Mobile, O=YourOrg, L=City, S=State, C=US"
   ```

2. Get the SHA-256 fingerprint (used by the integrity check):

   ```bash
   keytool -list -v -keystore privacyguard-release.jks -alias privacyguard \
     | grep "SHA256:" | awk '{print $2}' | tr -d ':'
   ```

3. Copy `release-signing.properties.template` to `release-signing.local.properties`
   (git-ignored). Fill in `PRIVACYGUARD_RELEASE_STORE_FILE`, `…_STORE_PASSWORD`,
   `…_KEY_ALIAS`, `…_KEY_PASSWORD` and `…_SIGNATURE_SHA256`. Gradle properties or
   environment variables with the same names also work. `…_SIGNATURE_SHA256` is the
   64-digit hex value from step 2; case and colons don't matter, and the release build
   stops if it is anything else.

4. Build:

   ```bash
   ./gradlew :app:assembleEnterpriseRelease   # the APK published on GitHub Releases
   ```

R8 shrinking and obfuscation run on release builds (`app/proguard-rules.pro`). An APK
signed with your own key is a different app to Android: it can't update the official
CoreLock, and the official one can't update it.

How the official release is made and checked: [releases/RELEASE_CHECKLIST.md](../releases/RELEASE_CHECKLIST.md).

## Build variants

| Variant | Application ID | Notes |
|---|---|---|
| `enterpriseDebug` | `com.privacyguard.app.enterprise` | Debuggable, debug hooks |
| `enterpriseRelease` | `com.privacyguard.app.enterprise` | R8, release keystore required; the published APK |

There is one flavor (ADR 0006). It is still called `enterprise` so the application ID and
task names stay stable for installed phones. The consumer flavor was removed on
2026-10-23; its history is in git (`0fd01e5` added it). HTTPS inspection, CA manager,
payload UI, MDM and SIEM code live in `src/enterprise`, the shared engine in `src/main`.

## Tests

```bash
./gradlew --no-daemon :app:testEnterpriseDebugUnitTest          # JVM, no device
./gradlew --no-daemon :app:lintEnterpriseDebug
./gradlew --no-daemon :app:minifyEnterpriseReleaseWithR8        # R8 / shrinking
./gradlew --no-daemon :app:connectedEnterpriseDebugAndroidTest  # emulator only: it uninstalls the app
```

- **JVM unit tests** (1,296 on 2026-11-17, 0 failures) cover, among others:
  - the TCP forwarder end to end against a loopback server (lost segment, small window,
    RSTs), UDP reply fragmentation, resolver address validation;
  - packet parsing, IPv6 address formatting, checksums;
  - ClientHello parsing and reassembly, JA4 (including the FoxIO reference vector), JA3,
    cipher analysis, the TLS baseline engine;
  - the name decision (`NameBlockPolicy`): a property test with a fixed seed checks DNS and
    SNI against a model of the decision order, and `ProtectionLevelMatrixTest` checks every
    level against pinned excerpts of the real lists (`app/src/test/resources/levels/`);
  - session table, filter engine, domain filter, DNS anomalies, behavior DNA, permission
    mismatch, stalkerware, ad-SDK policy, PII redaction;
  - security tests (`sectest/`): CSV formula injection, outbound hosts named in
    onboarding (`OutboundHostsDisclosureTest`), exported components;
  - fuzz targets with fixed seeds (`fuzz/`, including QUIC): a few seconds on every pull
    request, 50 times longer each night with a new seed.
- **Instrumented tests:** every Room migration (`AppDatabaseMigrationTest`,
  `ConfigSplitMigrationTest`) against the exported schemas, workers, and the nightly
  `LiveListsLevelTest` (the only test that uses the network, against the hosts the app
  already contacts).
- **Tests never touch the real network** otherwise; they use fixtures.

## CI

`.github/workflows/ci.yml` runs on every push and pull request to `main`. Required checks:

1. Unit tests, lint and R8, plus an "unsigned release must fail" check
2. OSV dependency scan of the libraries in the release APK
3. Room migration and instrumented tests on an API 34 emulator
4. Secret scan (gitleaks)

Other workflows: `nightly.yml` (long fuzz runs and the live-list test),
`malware-snapshot.yml` (a monthly pull request with a fresh copy of the bundled malware
list) and `release-checks.yml` (after each release: the APK's signer equals the README
fingerprint and the release text shows each APK's SHA-256). All actions are pinned to
commit SHAs; Gradle dependency verification (`gradle/verification-metadata.xml`) is
mandatory. Dependabot proposes dependency updates, which the owner reviews one at a time.

## Device testing

Phone checklists are in [testing/](../testing/README.md). Device tests so far ran on a
Samsung Galaxy Note20 (Android 13) with Facebook, Instagram, a game and a Samsung system
app.

## Debug tooling

Debug builds only (`src/debug`, never in release):

- **`DebugHooksReceiver`**, protected by `android.permission.DUMP`, so only adb can call
  it:

  ```bash
  # end the 7-day TLS learning period for one app (to test alerts)
  adb shell am broadcast -a com.privacyguard.debug.TLS_END_LEARNING \
      --es package <pkg> -p com.privacyguard.app.enterprise
  # list (and with --ez apply true, delete) alerts that tests created for <pkg>
  adb shell am broadcast -a com.privacyguard.debug.DELETE_TEST_ALERTS \
      --es package <pkg> -p com.privacyguard.app.enterprise
  ```

  Debug builds never set FLAG_SECURE, so screens can be captured for documentation
  without a hook.

- **`ComponentCatalogActivity`** shows every 1.1 UI component for screenshots
  ([DESIGN_SPEC_1.1.md](../ux/DESIGN_SPEC_1.1.md) §3).

- **Open a screen directly:**

  ```bash
  adb shell am start -n com.privacyguard.app.enterprise/com.privacyguard.app.MainActivity \
      --es pg_nav_route "appTraffic/com.instagram.android"
  ```

  Routes include `dashboard`, `apps`, `connections`, `statistics`, `settings`, `alerts`,
  `sleepReport`, `adPrivacy`, `crypto`, `rules`, `ads`, `payloads`, `evidence/<KIND>`,
  `appTraffic/<package>?from=&to=&idle=1` and `domain/<host>`.

- **The debug build's integrity check** expects a specific debug certificate; on another
  machine it reports "Signature mismatch" and blocks protected actions in debug builds.
  Set the debug SHA-256 in `app/build.gradle.kts` or use a release build.

## Scripts in `tools/`

| Script | What it does |
|---|---|
| `tools/geoip/GenerateGeoIp.java` | Builds `app/src/main/assets/geoip_v4.bin` from the five regional internet registries' public "delegated-extended" files. |
| `tools/repro/protection-paused.sh` | Repro on an emulator for Home briefly showing "Protection is paused" while the tunnel came back by itself. |
| `tools/repro/startup-dns-wait.sh` | Runs on the phone: measures how long lookups wait while CoreLock starts ([PHONE_TEST_1.2.1.md](../testing/PHONE_TEST_1.2.1.md), check G). |

Each script explains its use in its header comment.
