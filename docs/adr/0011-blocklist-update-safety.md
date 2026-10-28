# 0011. Guard against bad list updates and show when lists last updated

- **Status:** Proposed
- **Date:** 2026-10-28
- **Commits:** none yet
- **Extends:** ADR 0003 (list selection) and the refresh schedule added for 1.0.0.

## Context

How lists are updated in 1.0.0 (`app/src/main/java/com/privacyguard/...`):

| What | Where |
|---|---|
| Two periodic jobs, scheduled at every app start with `KEEP`: weekly on unmetered network with battery not low, and daily on any network for lists with no success for 14 days | `BlocklistUpdateWorker.kt:54-90`, called from `PrivacyGuardApplication` |
| A one-time job on any network at first start (no malware rows yet) and from Settings › Refresh now, unique with `REPLACE` | `BlocklistManager.kt:53-54`, `SettingsScreen.kt:500`, `BlocklistUpdateWorker.kt:92-103` |
| Each list's last success stored on its own (SharedPreferences `blocklist_updates`); a run fetches only lists due, and a stored time in the future counts as due | `BlocklistUpdateTimes.kt:13-34`, `BlocklistManager.kt:123-147` |
| A failed list makes the run fail, so WorkManager retries (exponential from 1 h); successful lists keep their new time | `BlocklistManager.kt:112-113`, `BlocklistUpdateWorker.kt:76`, `:128-131` |
| HTTPS only, redirects off, final URL checked, 32 MB cap with a counting stream, timeouts, 3 attempts, jsDelivr mirror for HaGeZi | `BlocklistDownloader.kt:15-68`, `BlocklistSource.kt:5` |
| Lines validated (public suffixes, IP literals, look-alike IDNs, private IPs in the DoH-IP list refused); at most 1.5 M names | `ListEntryValidator`, `BlocklistManager.kt:198-230` |
| Old list replaced in one transaction only after the new one parsed with at least one name; otherwise last-known-good stays | `BlocklistManager.kt:188-196`, `BlocklistRepo.kt:23`, `BlocklistDao.kt:94` |

Gaps found:

1. **No shrink guard.** A list that downloads fine but holds a handful of valid names
   (a broken upstream build, a truncated mirror, an attacker who controls the source)
   replaces a list of 200,000 names. Only an **empty** result is refused
   (`BlocklistManager.kt:193`). Protection drops silently.
2. **No visible update time.** The success times are stored but no screen shows them
   (no reader under `app/ui`). A user, or a bug report, can't tell whether lists are
   fresh.
3. **Swallowed errors.** `BlocklistDownloader.kt:66-67` catches every exception and
   drops it: no count, no reason. CLAUDE.md §6.1 forbids empty catch blocks.
4. **No upstream signature.** HaGeZi and OISD don't sign their lists; integrity rests on
   HTTPS to GitHub, jsDelivr and oisd.nl. This is a limit, not something CoreLock can
   fix alone.
5. The HaGeZi mirror uses jsDelivr's `@latest` tag (`BlocklistSource.kt:5`), which
   jsDelivr caches; the mirror can lag the primary by hours. Acceptable for a fallback.

## Decision

1. **Shrink guard.** A new download replaces a list only if it has at least **50 %**
   of the names of the list it replaces, or at least that list's built-in floor (a
   fixed minimum per source, about a tenth of today's size). Otherwise the run counts
   it as a failure (`UpdateResult.Failure`, so it retries), keeps last-known-good, and
   records "rejected: too small" for Diagnostics. A user can force it from Diagnostics
   ("Accept smaller list") after seeing the numbers; that is the only override.
2. **Show update times.** Settings › Ads & Trackers and Diagnostics show, per list:
   last success (date), number of names, and the last failure reason if any. Read from
   `BlocklistUpdateTimes` and the row count; no new storage except the failure reason.
3. **Count download failures by reason** (timeout, HTTP status, too large, not
   HTTPS, other) instead of the empty catch, and show them in Diagnostics. No domain
   or URL in logs.
4. **No signature scheme now.** Document the limit in `docs/HOW_IT_WORKS.md`. If an
   upstream starts publishing signatures or checksums, verify them then.

## Consequences

- A broken or tampered list can't empty protection without the user seeing it.
- One more threshold to maintain: a list that legitimately shrinks by more than half
  (an upstream clean-up) is held until the user accepts it or the floor is adjusted in
  a release.
- New strings in four languages; Diagnostics grows.

## Alternatives considered

- **Compare to a fixed size only.** Simple, but goes stale as lists grow or shrink.
- **Reject any shrink over 10 %.** Real lists move by a few percent a week; 10 % is
  close enough to normal churn to cause false rejects.
- **Pin each list to a commit and update in releases.** Strong integrity, but lists
  would be weeks old, which defeats malware lists.

## Open questions

- Is 50 % the right threshold? Check against the size history of HaGeZi and OISD
  releases before deciding.
- Should a rejected list raise a notification, or only show in Diagnostics?
