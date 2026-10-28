# 0010. Show why a name was blocked, and allow it in one tap

- **Status:** Proposed
- **Date:** 2026-10-28
- **Commits:** none yet

## Context

When a site or app breaks, the user needs to know **what** blocked it and to undo it
quickly. Today:

- The connection detail shows the **layer** that stopped the connection ("Stopped
  by": DNS, SNI, QUIC, DoH, DoT; `ConnectionsScreen.kt:318-325`).
- It does **not** show the **reason**: user rule, malware list, ad list or ad-SDK
  policy. The decision code computes it (`DnsBlockDecision.Reason`,
  `DnsBlockDecision.kt:17-31`), but the service drops it: `onSniBlocked = { _, _ ->
  recordSniBlock() }` (`PrivacyVpnService.kt:529`). DNS rows store only
  `wasBlocked` (`DnsQueryEntity.kt:19-33`).
- It never names the **list**. The list is known only in the database: each entry
  has a `source` (`BlocklistEntity.kt:22-24`), indexed by `(domain, source)`
  (`BlocklistEntity.kt:16`). The in-memory filter has no per-name source
  (`DomainFilter.kt:22`).
- The only action is **Block / Unblock**, which adds or removes a user **Block** rule
  (`ConnectionsScreen.kt:225-232`, `ConnectionsViewModel.kt:382-398`, action `DENY`
  only). If a list blocked the name, "Unblock" does nothing useful; the user must go to
  Rules or Ads and type an Allow rule by hand.

The "Site or app broken" issue form already asks for "Stopped by"; without the reason
and list, reports are slower to act on.

## Decision

1. **Reason and list at display time, no schema change.** When the detail sheet opens
   for a blocked row, a background query finds why:
   - user Block rule → the rule's label (`FilterEngine.domainRuleFor`);
   - otherwise the list entry for the name or its nearest parent (walk the labels,
     `getEntry` per step, at most 127 steps, normally 2–4) → the list's display name
     and category (malware or ads);
   - otherwise ad-SDK policy.
   This is the current reason, not the reason at the time of the block; the sheet says
   "Blocked now by …". Storing the reason per row would need a migration and a column
   in a table that grows; not worth it while the display-time answer is the same in
   nearly every case.
2. **"Allow this name" button** next to Block, for blocked rows. It creates a user
   **Allow** rule with `HIGH_PRIORITY` for the **exact name by default** (owner's
   decision, 2026-10-28); the parent domain (`*.parent`) is a second, explicit
   option. Same code path as the Rules screen
   (`RulesViewModel.kt:141`).
   - If the name is on a **malware/phishing** list, the existing security warning is
     shown first and `overridesSecurity` is set only on confirmation (as in
     `AdsViewModel.kt:177`). No shortcut around it.
   - If a user **Block** rule is the reason, the button says "Remove my block rule"
     instead, since an Allow would lose to it (Block wins, CLAUDE.md §5.3).
3. **Keep the `Reason` in memory** for the notification and Diagnostics counters
   (split SNI blocks by reason), instead of discarding it at
   `PrivacyVpnService.kt:529`. No per-packet allocation: the reason is an enum.
4. **Issue form:** add an optional "Blocked by (list)" field to
   `site_or_app_broken.yml`.

## Consequences

- Users can fix a broken site from the place they found it, with the same safety
  warning for malware entries.
- Reports name the list, so we know which upstream to contact.
- New strings in four languages; one extra indexed query per opened detail sheet
  (off the main thread).
- The shown reason can differ from the original one if lists changed since; the
  wording says so.

## Alternatives considered

- **Store reason and list on every row.** Exact history, but a migration on two
  growing tables and more data kept about the user's browsing (CLAUDE.md §4.2 data
  minimisation).
- **Keep the per-name source in memory.** Costs heap on every name (backlog item 3
  goes the other way).
- **Only link to the Rules screen.** Leaves the user to type the name and choose
  options; the friction this record removes.

## Open questions

- **Resolved (owner, 2026-10-28):** "Allow this name" defaults to the exact name.
- Open: should the Apps screen get the same reason line for per-app blocks?
