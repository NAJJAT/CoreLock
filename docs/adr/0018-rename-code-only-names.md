# 0018. Rename the code-only "PrivacyGuard" names in a future major version

- **Status:** Proposed (not before a major version; nothing is renamed now)
- **Date:** 2026-11-17
- **Commits:** none yet

## Context

CoreLock was first developed as PrivacyGuard. Every name a user sees now says CoreLock
(`UserVisibleTextTest`, `BrandNameTest`). What is left falls into two groups
([CODE_MAP.md, Internal names](../architecture/CODE_MAP.md#internal-names)):

- **Stored on phones or relied on by others:** the application ID, database and
  SharedPreferences file names, the Keystore alias, notification channel IDs, intent
  actions, the SIEM signature header, the old CA name, the signing property names. These
  stay for good: renaming them loses user data, breaks updates or breaks organisations'
  setups. This ADR does not touch them.
- **Code-only:** the `com.privacyguard` packages, class names (`PrivacyGuardApplication`,
  `PrivacyGuardTileService`, `PrivacyGuardTheme`, `isPrivacyGuardTraffic` …), the Gradle
  `namespace` (`com.privacyguard.app`), `rootProject.name`, the `Theme.Privacyguard` style
  and the Gradle properties `privacyguard.fuzz.*`. Users never see them, but contributors
  do, and the mix of two names costs a little attention in every review.

Measured on 2026-11-17: 507 Kotlin files, every one with a `package com.privacyguard…`
line, and 1,478 `import com.privacyguard…` lines; 24 R8 rules name `com.privacyguard`
classes (`app/proguard-rules.pro`).

"Code-only" is not quite true for some of these names. Android and libraries store a few
class names on the phone, so moving those classes has effects after an update:

| What | Where the class name is stored | Effect of moving the class |
|---|---|---|
| WorkManager workers (`BlocklistUpdateWorker`, `PruneWorker`, `WeeklyReportWorker`) | WorkManager's database, with every scheduled job | Stored jobs fail to start (class not found) until they are scheduled again. |
| Quick Settings tile (`PrivacyGuardTileService`) | System UI's list of tiles the user added | The tile disappears from the user's Quick Settings panel. |
| Device admin receiver (`com.privacyguard.mdm.DeviceAdminReceiver`) | The system's list of active device admins; MDM profiles that name the component | Device admin is no longer active; on managed phones the MDM must set it up again. |
| Launcher activity (`MainActivity`) | Home-screen icons and shortcuts on some launchers | Pinned icons can vanish or stop working. |
| Explicit `PendingIntent`s (notifications, the crash-restart alarm) | Already-posted notifications and set alarms | They point to a class that no longer exists until they are replaced. |
| Room databases (`AppDatabase`, `ConfigDatabase`) | Not on the phone: the exported schema folders are named after the class (`app/schemas/com.privacyguard.app.data.db.AppDatabase/`) | `MigrationTestHelper` can't find the old schemas, so the migration tests stop testing anything. Table names are fixed by `tableName` on every entity, so user data is not affected. |

## Decision

Proposed: do not rename now. If the owner decides to, do it once, in a major version
(2.0.0), as its own change with no other work in it, in these steps:

1. **Packages and classes.** Move `com.privacyguard.*` to `app.corelock.*` (or another
   root the owner picks) with the IDE's package refactoring; rename the classes and
   functions that carry the old name. Change `namespace` (this moves `R` and
   `BuildConfig`), `rootProject.name`, the style and the fuzz properties (and
   `nightly.yml`). **Keep** `applicationId = "com.privacyguard.app"` and everything in
   the stored group.
2. **Components that the system stores by name.** Keep the old component names alive:
   an `activity-alias` with the old `MainActivity` name; a thin subclass at the old
   `PrivacyGuardTileService` and `com.privacyguard.mdm.DeviceAdminReceiver` names that
   the manifest still declares (or keep those two classes where they are). Removing them
   later needs its own decision.
3. **WorkManager.** On the first start after the update, schedule every unique job again
   with `ExistingPeriodicWorkPolicy.UPDATE` / `REPLACE` so stored jobs carry the new class
   names; add a `WorkerFactory` that maps the old class names to the new workers for any
   job that starts before that. Test it with a WorkManager database from 1.x.
4. **R8.** Rewrite the 24 keep rules; then check the release (R8) build, not only debug:
   service, receivers, tile, workers and Room start, and certificate creation works.
5. **Room.** Move the exported schema folders to the new class names (`git mv`), keep
   every old version file, and check that `AppDatabaseMigrationTest` still runs every
   path on an emulator.
6. **Tests and docs.** Update the tests that read source files by path, the `-keep`
   comments, `CODE_MAP.md`, `HOW_IT_WORKS.md`, `DEVELOPMENT.md` and CLAUDE.md in the
   same change.

## Alternatives

- **Rename now, in a 1.x version.** Rejected: the effects in the table above would reach
  users in a minor update, and the work would mix with the 1.3 plans.
- **Rename only the class names, keep the packages.** Smaller (no import churn), but the
  packages are what contributors see most, so most of the cost of two names stays.
- **Never rename.** Costs nothing and is safe; the old name stays as a historical
  oddity explained in CODE_MAP.md. This remains the default until the owner decides.

## Consequences

- **Better:** one name in code and in the product; less to explain to contributors.
- **Risks:** a lost Quick Settings tile, deactivated device admin on managed phones,
  failed background jobs or dead notifications after the update if steps 2 and 3 miss
  something; a release build that crashes if an R8 rule is missed (step 4); migration
  tests that silently test nothing if the schema folders are not moved (step 5). Every
  open branch and pull request will conflict with the move.
- **Effort (estimate, not measured):** about 1 day for the mechanical move and build
  fixes, 1–2 days for the component aliases, the WorkManager migration and R8, and 1–2
  days of checks (unit and instrumented tests, an update from the last 1.x release on an
  emulator and on a phone with the tile, device admin and scheduled jobs in place, and a
  managed phone if one is available). About 3–5 days in total.
- The stored group in CODE_MAP.md keeps the old name in every case; this ADR never
  changes it.
