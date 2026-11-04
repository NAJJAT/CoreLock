# 0013. Export and import of rules and settings

- **Status:** Proposed (needs the owner's decision before any code)
- **Date:** 2026-11-03
- **Commits:** none

## Context

Decision D2 of the 1.1 redesign (`docs/ux/DESIGN_SPEC_1.1.md` §10) approved exporting
and importing the user's rules and settings, on two conditions: the export warns that
the file is private, and the import "reuses the existing config-import limits and
validation; no second parser".

While preparing Phase 6, the code was checked for that parser. There is none:

- `ManagedConfigurationProvider` (enterprise) reads typed values that Android's
  managed-configuration API has already parsed; it never reads a file.
- `LegacyConfigImporter` copies tables from the old database into `config.db` on first
  open; its input is our own database, not a user file.
- `BlocklistManager.parseLines` has size and line limits, but it reads one name or
  address per line. A rule has more fields (app, action, port, protocol, time, the
  security-override flag), so it can't carry a rule.

So the condition D2 was approved on can't be met as written. An import would add a new
parser for a file that may come from anywhere (a chat, a download), which is untrusted
input (CLAUDE.md §3.3) and a way to change protection silently: an imported Allow rule
with `overridesSecurity` would open a phishing site without the warning the app
normally shows (§5.3).

## Decision (proposed)

Nothing is built in 1.1. When the owner decides, this is the proposed design:

1. **Export** writes one JSON file through the system file picker
   (`ACTION_CREATE_DOCUMENT`): format version, the user's rules (`source = USER` only),
   and the settings the user chose. Never the history, payloads, keys, the inspection
   CA or MDM-managed values. The export screen says: "This file contains your rules and
   settings. Keep it private."
2. **Import** reads the file through the picker (`ACTION_OPEN_DOCUMENT`) with hard
   limits before parsing: file size (for example 1 MiB), rule count (for example
   5,000), field lengths, and the format version. It uses Android's own JSON parser
   (no new dependency) and validates every field with the same checks the add-rule
   dialog uses. Invalid entries are skipped and counted.
3. **Nothing changes until the user confirms** a summary: "12 rules (3 Block, 9 Allow)
   and 4 settings will be added." Rules are added, never replaced; existing rules stay.
4. An imported rule never carries `overridesSecurity`. A rule that would open a name on
   the malware list is imported without it, so the normal warning still applies.
5. Import is refused while MDM manages the device's settings, so a file can't override
   the organisation.
6. Tests: malformed, oversized, deeply nested and wrong-version files; a round trip;
   `overridesSecurity` dropped; formula-like strings in labels.

## Alternatives

- **Export only** (no import): small and low risk, but half the value; users mostly
  want to move rules to a new phone.
- **Android backup** of `config.db`: backups are off on purpose (CLAUDE.md §4.2) because
  the same mechanism would copy history.
- **Plain text, one rule per line**: easy to edit by hand, but needs its own parser and
  escaping, which is what the JSON plan avoids.

## Consequences

- 1.1.0 ships without export or import. The Settings › Data section has no row for it,
  so nothing promises it.
- If accepted, the work is about 2–3 days with tests and needs a security review
  (input parsing, rule precedence, MDM).
