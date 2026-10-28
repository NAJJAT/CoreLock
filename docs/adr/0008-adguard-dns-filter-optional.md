# 0008. AdGuard DNS filter as an optional blocklist

- **Status:** Proposed
- **Date:** 2026-10-28
- **Commits:** none yet
- **Depends on:** ADR 0007 (it cannot ship before the parser). Extends ADR 0003; does
  not replace it.

## Context

CoreLock's ad and tracker lists are HaGeZi Pro mini (Standard), HaGeZi Pro and OISD
small (Strict) (ADR 0003, `ProtectionLevels.kt:24-43`). The AdGuard DNS filter is a
large list built for DNS-level blocking. It is compiled from about 70 sources:
AdGuard's own filters (Base, Tracking Protection, Mobile ads, regional filters) and
third-party lists such as EasyList, EasyPrivacy, hostsVN, ABPindo and AdblockID (the
upstream README lists them). AdGuard says it does not replace its content filters.

AdGuard Support confirmed in writing that this filter may be included in a GPL-3.0
project such as CoreLock, on three conditions: keep the license text and copyright
notice; mark and share any modification under GPL-3.0; credit with a link to the
upstream repository (AdGuard Support, written reply, 2026-10-28). The confirmation
covered the DNS filter only.

### License, verified 2026-10-28

- Repository `github.com/AdguardTeam/AdGuardSDNSFilter`, default branch `master`.
  GitHub reports the license as **GPL-3.0**.
- The `LICENSE` file is the unmodified text of the **GNU General Public License,
  version 3, 29 June 2007**. Compared with CoreLock's `LICENSE` it differs only in the
  FSF address line (`http://fsf.org/` instead of `https://fsf.org/`) and in how the
  "How to apply" template at the end is written: the same license.
- The `LICENSE` file has **no project-specific copyright line**. The filter's header
  gives its title, a homepage link to the repository and a link to that `LICENSE`, and
  also has no copyright line.

### Measurements (read-only, 2026-10-28)

| | Value |
|---|---|
| Filter size | 186,605 lines, 4.52 MB (1.52 MB with gzip) |
| Domain rules CoreLock can apply | 184,047 distinct names (ADR 0007) |
| Of those, already blocked by the **Standard** lists (TIF mini + Pro mini, 286,971 names) | 51,268 |
| **New names at Standard** | **132,779** |
| Of those, already blocked by the **Strict** lists (TIF mini + Pro + OISD small, 342,004 names) | 80,602 |
| **New names at Strict** | **103,445** |

A name counts as "already blocked" when it or any parent is on a list, which is how
CoreLock's lists match (`DomainFilter.kt:89-104`).

**Expected heap cost.** The measured heap (ADR 0003) is MINIMAL ≈ 23 MB, STANDARD
≈ 31 MB, STRICT ≈ 44 MB. With today's list sizes that works out to about 110 bytes
per extra name (Minimal → Standard) and about 240 bytes per extra name (Standard →
Strict). Applied to the new names:

| Level | Today | With the filter (estimate) |
|---|---|---|
| Standard | ≈ 31 MB | ≈ 46–62 MB |
| Strict | ≈ 44 MB | ≈ 55–69 MB |

That is well below the 134 MB that made us reject OISD big (ADR 0003), but it is a
large increase for a phone-wide VPN process. These are estimates from per-name
averages, not a device measurement. With the planned compact storage (64-bit
fingerprints in a sorted `LongArray`, CLAUDE.md §16.2 item 3), 103k–133k extra names
would cost about 1 MB.

**Download.** The filter is also published on the repository's `gh-pages` branch,
reachable at
`https://raw.githubusercontent.com/AdguardTeam/AdGuardSDNSFilter/gh-pages/Filters/filter.txt`
and through jsDelivr at
`https://cdn.jsdelivr.net/gh/AdguardTeam/AdGuardSDNSFilter@gh-pages/Filters/filter.txt`
(both answered 200 with the full file on 2026-10-28). Both hosts are already in the
network disclosure, so **no new outbound host** is needed. The project's own page
(`adguardteam.github.io`) would be a new host and is not used. A full refresh grows
from about 4.1 MB to about 5.7 MB (gzip).

## Decision

1. **Optional, off by default.** A switch under Settings › Ads & Trackers,
   "AdGuard DNS filter (more ad and tracker domains, uses more memory)", available at
   Standard and Strict. Minimal stays malware-only. Stored as category `ads`, like OISD
   (`BlocklistManager.sourceMetadata`), so the existing category and source switches
   apply.
2. **Not the default until** compact storage lands and a two-week device trial at
   Standard shows no breakage beyond what HaGeZi Pro mini causes. Then we decide
   again, in a new record.
3. **Parsed with ADR 0007.** Rules CoreLock cannot apply are dropped and counted; the
   filter's exceptions apply only to its own rules.
4. **Downloaded from the `gh-pages` branch** on `raw.githubusercontent.com`, with
   jsDelivr as the mirror, through the existing pipeline: HTTPS only, size limits,
   per-list success times, retries and last-known-good (ADR 0003, ADR 0011).
5. **No built-in copy in the APK.** The phone downloads the filter from AdGuard's own
   repository, so CoreLock does not redistribute it. The built-in seed stays the
   offline fallback for the ad category. If a built-in copy is ever added, it ships
   with the GPL text, the upstream header and a note of what was changed.
6. **Compliance:**
   - **License text:** the GPL-3.0 text CoreLock already shows in Settings › About ›
     Open-source licenses (checked against `LICENSE` by `OpenSourceNoticesTest`)
     covers it, because it is the same license.
   - **Copyright notice:** the upstream has no copyright line to copy. The credit
     names the project and its authors the way the upstream names itself: "AdGuard DNS
     filter, by the AdGuard team", with the link. We ask AdGuard whether they want a
     specific line (open question).
   - **Changes:** CoreLock does not modify the file. It applies a subset at run time.
     The licenses screen and the README say so plainly: "CoreLock uses only the
     domain rules of this filter; rules that need page content are ignored." If
     CoreLock ever publishes a changed copy, the changes are listed in that copy's
     header and published in this repository under GPL-3.0.
   - **Credit, in the same change** (CLAUDE.md §2, enforced by
     `OpenSourceNoticesTest`): a row in the README "Data" table and an entry in
     `OpenSourceNotices.kt`, each with the link
     `https://github.com/AdguardTeam/AdGuardSDNSFilter` and "GPL-3.0". Because the
     filter is a compilation, the credit adds "compiled from AdGuard filters and other
     lists, including EasyList and EasyPrivacy; see the upstream README".
   - **Branding:** the name is used only to say which list it is. No logo, colours or
     wording that suggests AdGuard made, endorses or supports CoreLock. The README
     already says "CoreLock is not affiliated with or endorsed by AdGuard."
7. **Docs in the same change:** `docs/BLOCKING_POLICY.md` §3 (the new option and its
   memory cost), the README levels and network tables, `docs/HOW_IT_WORKS.md`.

### AdGuard Mobile ads filter: not added

- **License, verified separately:** it lives in `github.com/AdguardTeam/AdguardFilters`.
  GitHub reports GPL-3.0, the `LICENSE` file is the GPL v3 text, and the filter header
  links to it. AdGuard's written confirmation did **not** cover this filter.
- **Measured:** of its 9,139 lines, 6,415 are cosmetic or scriptlet rules and 342 are
  URL rules, none of which CoreLock can apply. Only 927 are plain domain rules. Of
  those, 660 are already on the Standard lists (267 new); with the Strict lists plus the
  AdGuard DNS filter, 921 are covered (6 new).
- **Decision:** don't add it. The DNS filter already includes it (its header says it
  is built from the Mobile ads filter), and on its own it adds little at domain level.
  It is also served from `filters.adtidy.org`, which would be a new outbound host.

## Consequences

- Users who want more blocking get it, and pay with memory they can see.
- Another upstream maintainer we depend on, and a list compiled from many sources
  whose quality we don't control. Wrong blocks are reported through the "Site or app
  broken" form and, where needed, upstream.
- The first source in adblock syntax; ADR 0007 must ship first, with its tests.
- `OutboundHostsDisclosureTest` stays green with no new host, as long as the
  `gh-pages` URLs are used.

## Alternatives considered

- **Add it to Standard by default now.** +15–31 MB at the default level, before compact
  storage and before any breakage data.
- **Add it at Strict only.** Strict users already accept breakage; but the filter is
  aimed at ads and trackers, which Standard users want too. The opt-in at both levels
  lets each user decide.
- **Download from `adguardteam.github.io`.** The official address, but a new host in
  the network disclosure for the same file.
- **Bundle a snapshot in the APK.** Brings GPL redistribution duties (text, notice,
  marked changes), grows the APK, and goes stale between releases.

## Open questions

- Does AdGuard want a specific copyright line in the credit, given that the
  repository has none?
- AdGuard publishes the compilation under GPL-3.0. Are we comfortable relying on that
  for the third-party lists it includes, or do we ask AdGuard to confirm?
- The heap estimate must be replaced by a measurement on the owner's phone
  (`adb shell dumpsys meminfo com.privacyguard.app.enterprise`, filter on and off).

**Method.** All files were downloaded on 2026-10-28 to a temporary folder outside the
repository and deleted afterwards. Domain names were taken with the ADR 0007
classifier; overlap was computed by checking each name and its parents against the
union of the HaGeZi TIF mini, Pro mini, Pro and OISD small lists of the same day.
