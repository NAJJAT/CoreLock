# 0006. Ship one app: the enterprise flavor

- **Status:** Accepted
- **Date:** 2026-10-23
- **Commits:** `ee13308` (consumer flavor removed), `1640e22`, `d2efecd`
  (HTTPS inspection available in the sole flavor)

## Context

The app had two flavors: `consumer`, and `enterprise` with consented HTTPS
inspection (MITM), MDM and SIEM export. Two flavors mean two builds to test, sign
and release, and two sets of source sets.

## Decision

Owner's decision: `enterprise` is the only flavor (application ID
`com.privacyguard.app.enterprise`, version shown without a suffix), and it is what
1.0.0 releases: one app to build, test and document.

## Alternatives

- **Keep both flavors.** Users who never want inspection would get an APK without
  the code, but two flavors doubled the test and release work for one developer.
- **Ship consumer only.** Drops MDM, SIEM export and inspection.

## Consequences

- HTTPS inspection code now ships to every user, so it is security-critical for
  everyone: off by default, consent only in the app or via MDM
  (`mitm_consent_given`), CA key never leaves the phone and is deleted when
  inspection is turned off (CLAUDE.md §3.1, §4.2).
- BouncyCastle ships to every user. The release grew from 7.13 MiB (consumer,
  2026-10-23) to 9.99 MiB (enterprise, 2026-10-25), mostly because a blanket R8 keep
  rule keeps all 2,730 BouncyCastle classes. Narrowing it is backlog item 9, after 1.0.
- Code stays split: inspection in `app/src/enterprise/`, the shared engine in
  `app/src/main/`.
- A second flavor needs the owner and a new record.
