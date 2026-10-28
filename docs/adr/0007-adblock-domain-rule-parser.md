# 0007. Read the domain-level subset of adblock rule syntax

- **Status:** Proposed
- **Date:** 2026-10-28
- **Commits:** none yet

## Context

CoreLock reads only plain domain lists and IP lists (`ListEntryValidator`,
`BlocklistManager.parseLines`, `app/src/main/java/com/privacyguard/app/core/blocklist/BlocklistManager.kt`).
ADR 0003 dropped the EasyList parser because EasyList is mostly page-content rules that
a DNS/SNI blocker cannot apply.

Two things now need adblock syntax:

1. **Lists published only in that syntax**, such as the AdGuard DNS filter (ADR 0008).
   It is written as `||domain^` rules, not as a hosts file.
2. **User rules written in the same syntax**, which people already know from other
   blockers, including a per-app option.

What CoreLock can apply is fixed by what it sees: a server name (DNS question, CNAME
target, TLS or QUIC SNI) and, at the connection layer, the owning app. It never sees
a URL path, a page or a script (ADR 0002). So only rules that are fully decided by a
host name, and optionally an app, can be applied.

Measured on the AdGuard DNS filter, version of 2026-10-28 (186,605 lines, 4.52 MB),
read-only, with a classifier that splits each line by shape (method at the end):

| Rule shape | Lines | CoreLock can apply? |
|---|---|---|
| `\|\|domain^` (domain and its subdomains) | 184,047 | Yes |
| `\|\|domain^$important` | 3 | Yes (treated as a block that list exceptions cannot undo) |
| `@@\|\|domain^` and `@@\|\|domain^\|` (exceptions) | 181 | Yes, inside the same list only (see Decision) |
| `.domain^` (any name ending in `.domain`) | 23 | Yes (subdomains only, not `domain` itself) |
| `...$badfilter` (cancels another rule) | 5 | Yes (remove the matching rule before loading) |
| Wildcards inside a name (`\|\|ads*.example^`) | 373 | No: dropped and counted |
| Regular expressions (`/.../`) | 22 | No: dropped and counted |
| Other shapes (e.g. `.n.2.1.js^`, malformed hosts, URL fragments, other exceptions) | ~284 | No: dropped and counted |
| Comments and blank lines | 1,668 | Skipped |

So about **99.6 % of the filter's 184,937 rule lines** are applicable at domain level,
and **about 680 rules (0.37 %)** would be dropped. For comparison, the AdGuard Mobile ads filter (9,139 lines) is the opposite
case: 6,415 of its lines are cosmetic or scriptlet rules and only 927 are plain domain
rules (see ADR 0008).

## Decision

Add one parser for the **domain-level subset** of adblock syntax, used both for
downloaded lists and for user rules. Everything outside the subset is skipped, never
guessed at, and counted per reason so Settings › Diagnostics can show "N rules ignored
(needs page content)".

**Accepted forms** (after trimming; lower-cased; names normalized and validated by the
existing `ListEntryValidator.normalize`, so public suffixes, IP literals and look-alike
IDNs are refused as today):

| Form | Meaning in CoreLock |
|---|---|
| `\|\|example.com^` | Block `example.com` and every name under it (same as a list entry today, `DomainFilter.kt:89-104`) |
| `example.com` | Same, for plain domain lines |
| `.example.com^` | Block names under `example.com`, not `example.com` itself |
| `@@\|\|example.com^` | Exception for `example.com` and every name under it, **in this list only** |
| `@@\|\|example.com^\|` | Exception for exactly `example.com`, in this list only |
| `$important` on a block | The block ignores this list's exceptions |
| `$badfilter` | Removes the identical rule without `$badfilter` before loading |
| `$app=<package>` (user rules only) | Per-app rule, see below |

**Everything else is dropped and counted:** URL paths, `/regex/`, wildcards inside a
name, cosmetic (`##`, `#@#`), scriptlet (`#%#`, `#$#`), HTML filtering, and any
modifier not listed above (`$third-party`, `$domain=`, `$redirect=`, `$replace=`,
`$removeparam=`, `$dnstype=`, `$dnsrewrite=`, `$denyallow=`, `$client=`, `$ctag=`).
A rule with one unsupported modifier is dropped whole: applying it without the
modifier would block more than its author meant.

**Exceptions stay inside their own list.** A downloaded list's `@@` rule may only undo
that list's own blocks. It must never unblock a name that HaGeZi, OISD, the malware
list or a user Block rule blocks. Measured: 81 of the filter's 181 exceptions undo its
own blocks; 21 of them would also unblock names on the Strict HaGeZi/OISD lists if
they were applied globally. Implementation rule: at load time an exception is kept
only if no other enabled list blocks that name or a parent of it; the kept exceptions
go into a small set that `DomainFilter.isBlocked` checks at each step of its existing
suffix walk, before the block sets, so the most specific entry wins.

**User rules in this syntax** are converted into ordinary `FilterRule`s, so there is
still only one matching path (ADR 0002, CLAUDE.md §5.3):

| Written | Stored as |
|---|---|
| `\|\|example.com^` | Block, `matchDomain = "*.example.com"` (covers the name and its subdomains, `FilterRule.kt:136-148`) |
| `@@\|\|example.com^` | Allow, `matchDomain = "*.example.com"` |
| `\|\|example.com^$app=com.example.app` | Block, `matchDomain = "*.example.com"`, `matchPackage = "com.example.app"` |
| `@@\|\|example.com^\|` | Allow, `matchDomain = "example.com"` (exact) |

`FilterRule` already supports an app and a domain in one rule (`matchPackage` and
`matchDomain`, `FilterRule.kt:26-29`; such a rule ranks most specific,
`FilterRule.kt:112-121`), and `NameBlockPolicy` passes the owning app to
`FilterEngine.domainRuleFor` (`NameBlockPolicy.kt:28`, `FilterEngine.kt:124-131`). The
Rules screen cannot create an app-plus-domain rule today (`RulesScreen.kt:239-241`
offers Domain, App or IP), so the syntax adds that capability. Every created rule uses
`FilterRule.HIGH_PRIORITY` (CLAUDE.md §5.3).

**Precedence does not change.** CoreLock's order stays: user Block → malware lists
(unless a confirmed `overridesSecurity` Allow) → user Allow → level lists and ad-SDK
policy (`DnsBlockDecision.kt:5-32`); between equally specific user rules Block wins
(`FilterEngine.kt:236-247`). This differs from adblock syntax, where `@@` beats a block
of the same name. When a user pastes a block and an exception for the same name, the
import screen says that the block wins in CoreLock and shows the rule that will apply.
`$important` on a user rule is accepted but has no extra meaning.

A `$app` exception does not take an app out of the malware list: a user Allow only
overrides the malware list after the existing warning (`overridesSecurity`).

**Limits (CLAUDE.md §6.3):**

| Limit | Value | Why |
|---|---|---|
| Line length | 1,024 characters; longer lines are dropped | Longest line in the AdGuard DNS filter: 236; in the Mobile ads filter: 2,330 (a cosmetic rule we drop anyway) |
| Name length | 253 total, 63 per label (existing validator) | DNS limits |
| Rules per list | `ListEntryValidator.MAX_ENTRIES_PER_SOURCE` (1,500,000) | Existing cap |
| Download size | `MAX_LIST_BYTES` (32 MB) | Existing cap |
| User rules per import | 5,000 | Rules are scanned in order on every lookup (`FilterEngine.kt:124-131`); a long user list slows every decision |
| Modifiers per rule | 8 | Bounds the split; real rules use 1–3 |
| Encoding | UTF-8; invalid bytes make the line invalid; IDNs converted to punycode by the existing validator | |

No regular expressions run on input lines (the classifier used one for measurement;
the production parser uses the same character-by-character checks as
`ListEntryValidator`). The parser never throws on input; it returns "invalid" and the
reason is counted.

**Performance.** Parsing runs in the update worker, never on the packet path. The
measuring classifier (which does more work than the parser, including regular
expressions) read all 186,605 lines in 394 ms on a desktop JVM, cold. Assuming a
mid-range phone is 3–5 times slower, expect about 1.2–2 s per refresh, in the
background. Memory during parsing is the same streaming pattern as today
(`BlocklistManager.parseLines`); held memory is covered in ADR 0008. The only hot-path
change is the exception check in `DomainFilter.isBlocked`: one extra hash lookup per
label, and only when the set is non-empty. It must be measured against the reference
in CLAUDE.md §6.2 before merging.

## Consequences

- Lists in adblock syntax become usable without any page-content support.
- One more parser to fuzz: malformed-input tests (truncated, oversized, binary,
  mixed encodings, every rejected shape) and a property test that a list's exception
  never unblocks a name blocked by another list or by a user Block rule.
- The user-rule syntax gives people a way to write per-app domain rules, which the UI
  does not offer today.
- People who know adblock syntax will expect `@@` to win. The import screen must say
  that it does not, against the user's own Block.
- Strings for the import screen and the "ignored rules" counter are needed in all
  four languages.

## Alternatives considered

- **Convert lists to hosts format at build time, or on a server.** CoreLock has no
  server, and a build-time copy would go stale between releases.
- **Support path and cosmetic rules too.** Impossible without decrypted content; see
  ADR 0009 for the browser-only case.
- **Apply list exceptions globally.** Measured above: 21 exceptions would silently
  unblock names that HaGeZi or OISD block.
- **Keep CoreLock's own rule format only.** Works, but people cannot reuse rules they
  already have, and there is still no way to write an app-plus-domain rule.

## Open questions

- Should imported user rules be shown with their original text, so an export gives
  back the same syntax?
- Should the 5,000 user-rule cap wait for the planned compact storage (CLAUDE.md
  §16.2 item 3), which would let user domain rules move into a hashed set?

**Method of measurement.** A small Java classifier (outside the repository, deleted
afterwards) read the filter downloaded on 2026-10-28 from
`https://adguardteam.github.io/AdGuardSDNSFilter/Filters/filter.txt`, split each line by
the shapes in the table above, and counted modifiers. Exception overlap: each
exception name was checked against the filter's own block names, and against the
HaGeZi TIF mini, HaGeZi Pro and OISD small lists of the same day, counting a name as
covered when it or any parent is listed (the `DomainFilter` rule).
