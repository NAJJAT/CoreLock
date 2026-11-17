# CoreLock documentation

Every document in the repository, with one line on what it is for. Start with the
[README](../README.md) if you are new to CoreLock.

## For users

| Document | What it is |
|---|---|
| [README](../README.md) | What CoreLock is, how to install and verify it, first setup, FAQ, permissions, network requests, privacy and known limitations. |
| [BLOCKING_POLICY.md](BLOCKING_POLICY.md) | What each protection level blocks, what is left open on purpose, what can't be blocked, how to allow a site and how to report a mistake. |
| [releases/CHANGELOG.md](releases/CHANGELOG.md) | One short entry per version, newest first. |
| [SECURITY.md](../SECURITY.md) | How to report a vulnerability privately. |

## How it works

| Document | What it is |
|---|---|
| [architecture/HOW_IT_WORKS.md](architecture/HOW_IT_WORKS.md) | What the code does: the path of one connection, the blocklists, every detector and its thresholds, how each number is calculated, what is stored and what leaves the phone, the known limits. |
| [architecture/FEATURES.md](architecture/FEATURES.md) | Feature reference: network protection, detection, ad privacy, Security systems, exports, HTTPS inspection with MDM and SIEM, languages. |
| [architecture/CODE_MAP.md](architecture/CODE_MAP.md) | Components, main data flows, databases and the project's folder structure. |
| [adr/README.md](adr/README.md) | Architecture Decision Records: why each significant decision was made, with its status. |

## Releases

| Document | What it is |
|---|---|
| [releases/RELEASE_NOTES_1.3.0.md](releases/RELEASE_NOTES_1.3.0.md) | Draft notes for 1.3.0 (not published yet). |
| [releases/RELEASE_NOTES_1.2.0.md](releases/RELEASE_NOTES_1.2.0.md) | Notes for 1.2.0, as published. |
| [releases/RELEASE_NOTES_1.0.md](releases/RELEASE_NOTES_1.0.md) | Notes for 1.0.0, as published (the app was still called PrivacyGuard). |
| [releases/RELEASE_CHECKLIST.md](releases/RELEASE_CHECKLIST.md) | Every step of a release, for the owner and the agent, and the decision on build attestations. |

## Testing

| Document | What it is |
|---|---|
| [testing/README.md](testing/README.md) | Which phone checklists and test reports exist and how they fit together. |
| [testing/PHONE_TEST_1.3.0.md](testing/PHONE_TEST_1.3.0.md) | Phone checklist for 1.3.0: the update from 1.2.0, the 1.3.0 changes, daily use, battery, other phones and networks. |
| [testing/SECURITY_TEST_2026-10-14.md](testing/SECURITY_TEST_2026-10-14.md) | Security test against OWASP MASVS: findings V-01 to V-18 and their status. |

## Design and translations

| Document | What it is |
|---|---|
| [ux/DESIGN_SPEC_1.1.md](ux/DESIGN_SPEC_1.1.md) | The design system and screen specification behind today's UI (accepted 2026-11-02, shipped in 1.2.0). |
| [ux/UX_AUDIT_1.1.md](ux/UX_AUDIT_1.1.md) | The audit of the 1.0 screens that led to that design. |
| [translations/TRANSLATION_REVIEW_1.3.md](translations/TRANSLATION_REVIEW_1.3.md) | Machine translations added in 1.3, for a native speaker to check. |
| [translations/README.md](translations/README.md) | Review status, where the older reviews are, and how to correct a translation. |

## Project

| Document | What it is |
|---|---|
| [project/DEVELOPMENT.md](project/DEVELOPMENT.md) | Building, signing, tests, CI, debug tools and the scripts in `tools/`. |
| [project/HOW_THIS_PROJECT_WAS_BUILT.md](project/HOW_THIS_PROJECT_WAS_BUILT.md) | How CoreLock was made, who decided what, and how the work was checked. |
| [project/GOOGLE_DEVELOPER_VERIFICATION.md](project/GOOGLE_DEVELOPER_VERIFICATION.md) | Decision brief on Google's developer verification for sideloaded apps (decision still open). |
| [CLAUDE.md](../CLAUDE.md) | The engineering handbook: rules, threat model, architecture and standards. AI coding agents read it first. |
| [archive/README.md](archive/README.md) | Superseded material kept for its history: working logs, the 1.1.0 and 1.2.0 phone checklists, the 1.0–1.2 translation reviews. |

## Scripts

`tools/` holds a GeoIP table generator and two repro scripts; each explains its use in its
header comment. A summary is in [project/DEVELOPMENT.md](project/DEVELOPMENT.md#scripts-in-tools).

## Conventions

- One topic per document; other documents link to it instead of repeating it.
- Protection levels are written **Minimal**, **Standard** and **Strict**. The four layers
  are DNS blocking, DNS-bypass prevention, server-name (SNI) filtering and the per-app
  firewall.
- A change that alters behaviour, permissions, network hosts, settings or limits updates
  the documents in the same change (CLAUDE.md §13).
- Accepted ADRs and published release notes are never rewritten.
