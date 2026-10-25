# 0005. Remove Flutter, Rust, iOS and the IPv6 proxy

- **Status:** Accepted
- **Date:** 2026-10-21
- **Commits:** `0e606a5` (removal, 7,389 lines deleted), `ef2d69f` (32-bit ARM back)

## Context

`d694083` (2026-04-28) added a Flutter UI, a Rust native engine and an IPv6 proxy
next to the Kotlin app. There was also iOS code, a test app and NativeBloom. By
October 2026 the commit message calls them unused: the Android app processed
packets on the Kotlin path, and the extra code still had to build, pass CI and be
fuzzed (`6d1f8a1` added a Rust fuzz harness).

## Decision

Delete all of it, with its build, CI and UI integration. CoreLock is an Android
app with one Kotlin packet engine. The old code is kept on branch
`archive/before-cleanup`, created from `0e606a5^`.

## Alternatives

- **Keep it for later.** Unused code still costs build time, CI time, dependency
  scanning and review, and every line in the VPN process is attack surface.
- **Move the engine to Rust.** The app is Android only. A Rust engine meant a JNI
  bridge, a native toolchain in CI and a bigger APK, for a speed gain we did not need:
  the Kotlin SNI check costs ≈ 4 µs per connection.

## Consequences

- One language and one engine to test, measure and fuzz.
- Hot-path speed must come from Kotlin: no per-packet allocation, reused buffers
  (CLAUDE.md §6.2).
- The removal first limited releases to `arm64-v8a`; `ef2d69f` added `armeabi-v7a`
  back so 32-bit phones keep working (no Play, so no per-device splits; see 0001).
- None of these components may be reintroduced without a new record.
