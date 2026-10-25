# 0001. Distribute on GitHub Releases, not Google Play

- **Status:** Accepted
- **Date:** not recorded (written down 2026-10-25)
- **Commits:** none single; see `README.md` › Install

## Context

CoreLock blocks ads for every app on the phone, at the DNS and connection level.
Google Play's policies forbid apps that interfere with other apps, including their
ads, and limit what `VpnService` apps may do. A Play build would have to weaken
exactly the protection users install it for. (Policy summary from general knowledge,
not re-checked against the current Play policy text on 2026-10-25.)

## Decision

Signed APKs are published on GitHub Releases only. There is no Play listing and no
update server: Settings › About opens the GitHub releases page.

## Alternatives

- **Google Play.** Automatic updates and wider reach, but ad blocking across apps
  would have to be removed or reduced to stay within policy.
- **F-Droid.** Not for 1.0: F-Droid builds the app itself from source, signs it with
  its own key, and adds a review delay to every release. Can be reconsidered after 1.0.
- **Built-in update check.** Rejected: it would contact a server on every check,
  against the privacy promise (CLAUDE.md §1.3).

## Consequences

- Full blocking is allowed; no policy workarounds in the code.
- Users must allow installing from unknown sources and check updates themselves.
- Trust rests entirely on our signing key. The README and every release page show
  the certificate SHA-256 fingerprint and the APK checksum (CLAUDE.md §12). Losing
  the key means no user can update; leaking it lets anyone ship a fake update.
- No per-device splits (no Play), so one APK carries both `arm64-v8a` and
  `armeabi-v7a` (see 0005).
