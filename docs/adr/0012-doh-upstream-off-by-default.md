# 0012. Keep encrypted upstream DNS (DoH) off by default

- **Status:** Accepted (owner's decision, 2026-10-28)
- **Date:** 2026-10-28
- **Commits:** none yet

## Context

CoreLock answers DNS itself and forwards allowed questions upstream. By default the
upstream is plain UDP to 1.1.1.1 (`DnsHandler.kt:42`, `:564`). DNS over HTTPS to
Cloudflare, Google or Quad9 already exists as an option and is off by default
(`DnsHandler.kt:45`, `:603-605`; `SettingsPreferences.kt:132`). Upstream sockets are
protected from the tunnel (`DnsHandler.kt:47-49`, `:235-240`).

When a DoH request fails, CoreLock answers SERVFAIL and does not fall back to plain DNS
(`DnsHandler.kt:378`). That is the right choice once the user asked for encryption: a
silent fallback would send names in plain text without them knowing. But it means
that wherever DoH can't get through, no name resolves at all.

## Decision

DoH stays **off by default**. Revisit when CoreLock has a fallback the user agreed to,
or handles captive portals (for example: plain DNS only for the portal check until the
user has logged in).

## Consequences

- By default, the upstream resolver and the network path can see the names that
  CoreLock lets through. Users who want that hidden can switch DoH on.
- Captive portals (hotels, airports) and networks that block DoH keep working by
  default, which CLAUDE.md §5.7 requires.

## Alternatives considered

- **DoH on by default, SERVFAIL on failure.** Breaks captive portals: the user can't
  reach the login page, so nothing works. Also breaks networks that block DoH
  resolvers.
- **DoH on by default with silent plain fallback.** Works everywhere but gives a
  false sense of privacy, and an attacker who blocks DoH forces plain DNS.

## Open questions

- None now. Backlog: CLAUDE.md §16.2.
