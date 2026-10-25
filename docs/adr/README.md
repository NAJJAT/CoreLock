# Architecture Decision Records

One file per significant decision: why it was made, what else was considered, and
what it costs. A record is never rewritten when the decision changes; a new record
replaces it and the old one is marked **Superseded by NNNN**.

| # | Decision | Status |
|---|---|---|
| [0001](0001-github-releases-not-google-play.md) | Distribute on GitHub Releases, not Google Play | Accepted |
| [0002](0002-four-blocking-layers.md) | Block in four layers with one name decision | Accepted |
| [0003](0003-blocklist-selection.md) | HaGeZi and OISD small as the blocklists | Accepted |
| [0004](0004-quic-gate-limits.md) | Hold QUIC until the ClientHello is readable, with hard limits | Accepted |
| [0005](0005-remove-flutter-rust-ios.md) | Remove Flutter, Rust, iOS and the IPv6 proxy | Accepted |
| [0006](0006-single-enterprise-flavor.md) | Ship one app: the enterprise flavor | Accepted |

Where a record says *not recorded*, the reason was not written down at the time.
The owner fills it in; nobody guesses it.

## Template

Copy this into `NNNN-short-title.md` (next free number, lowercase, hyphens).

```markdown
# NNNN. Title

- **Status:** Proposed | Accepted | Superseded by NNNN
- **Date:** YYYY-MM-DD
- **Commits:** `abc1234`

## Context
What problem forced a decision, and the facts that shaped it.

## Decision
What we do, in one or two short paragraphs.

## Alternatives
Each option considered and why it lost.

## Consequences
What gets better, what gets worse, and what we must keep doing because of it.
```
