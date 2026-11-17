# 0017. User Block rules cover subdomains; Allow rules stay exact

- **Status:** Accepted (owner, 2026-11-16; decided 2026-11-11; ships in 1.2.1)
- **Date:** 2026-11-13
- **Commits:** none yet

## Context

A user rule for a site matches the name exactly unless it is written with a wildcard
(`*.example.com`). Blocking `example.com` therefore did not block `www.example.com`
or `cdn.example.com`, while the blocklists always cover subdomains. Users expect
"Block example.com" to block the site, and most sites load from subdomains, so an
exact Block often did nothing visible. The same matching code serves DNS, CNAME and
HTTPS/SVCB targets, TLS and QUIC server names and the HTTP site name
(`NameBlockPolicy` → `FilterRule.domainMatches`), so one change applies everywhere.

## Decision

A user **Block** rule for a name (`Source.USER`, `Action.DENY`, no wildcard) also
blocks every subdomain of that name, as `*.name` would. **Allow** rules stay exact:
allowing `example.com` does not allow `ads.example.com`. Allowing more is the riskier
direction, and a user who wants it can still write `*.example.com`.

Rule precedence (CLAUDE.md §5.3) is unchanged. A rule's rank still comes from how it
is written: an exact Block keeps the exact rank. So:

- Block `example.com` + Allow `www.example.com`: the Allow is longer, so `www` is
  allowed and every other subdomain is blocked (a way to keep one subdomain open).
- Block `example.com` + Allow `*.example.com`: the Block ranks as exact and wins for
  subdomains too. Both rules name the same site; Block wins, as it does between
  equally specific rules.

## Alternatives

- **Keep exact matching and explain it in the UI.** Users kept creating rules that
  did nothing; the explanation does not fix the expectation.
- **Both Block and Allow cover subdomains.** An Allow for a site would then also open
  its ad and tracker subdomains (`ads.example.com`) that the lists block.
- **Rank a Block's subdomain matches as a wildcard.** The engine orders rules once,
  not per name; ranking per name would need a second ordering path (§5.3 "never add
  a second matching path"). The one case it changes is the contrived pair above.

## Consequences

- Existing exact Block rules now block more. This is a behaviour change for current
  users and is stated in the 1.2.1 release notes and in `docs/BLOCKING_POLICY.md`.
- A Block for a broad name (`google.com`) now also blocks its many subdomains; the
  rule screen's wording and the policy document say so.
- The property test's model of the precedence (fixed seed `20261021`) describes the
  new rule; the seed stays.
- Imported rule files (ADR 0013) carry the same meaning: an exact Block from an older
  export now covers subdomains too.
