# Archive

Superseded and finished material, kept for its history. Nothing here describes the app as
it is today; for that, start at the [documentation index](../README.md). Files here are
not updated when the app changes, except for links.

## Working logs (`progress/`)

| File | What it is | Why it is kept |
|---|---|---|
| [REDESIGN_PROGRESS.md](progress/REDESIGN_PROGRESS.md) | Working log of the 1.1 redesign (phases, emulator checks, CPU measurements), 2026-11-02 to 2026-11-07. | Records how each screen of today's UI was built and checked, with the measurements; ADR 0013 refers to it. |
| [BUGFIX_PROGRESS.md](progress/BUGFIX_PROGRESS.md) | Working log of the 1.2.1 bug-fix round, then called 1.3 (audit findings F-1 to F-18 and N-items, the tests that prove each fix), 2026-11-11 to 2026-11-16. | Proof that each fix has a test that fails without it (CLAUDE.md §9.2), and the measurements before and after. |

## Phone checklists (`testing/`)

| File | What it is | Why it is kept |
|---|---|---|
| [PHONE_TEST_1.1.0.md](testing/PHONE_TEST_1.1.0.md) | The two-day phone checklist written for 1.1.0, which was never published. | The 1.2.0 test ran it from its section 2 on; its steps were carried into the 1.2.1 checklist. |
| [PHONE_TEST_1.2.0.md](testing/PHONE_TEST_1.2.0.md) | Phone checks for 1.2.0: the update from 1.0.0, networks, vendors, battery, start-up wait. | Record of how 1.2.0 was checked; replaced by [PHONE_TEST_1.2.1.md](../testing/PHONE_TEST_1.2.1.md). |

## Translation reviews (`translations/`)

| File | Strings from | Why it is kept |
|---|---|---|
| [TRANSLATION_REVIEW_1.0.md](translations/TRANSLATION_REVIEW_1.0.md) | Everything up to 1.0.0 | No review is recorded as done: these strings may still need a native speaker. |
| [TRANSLATION_REVIEW_1.1.md](translations/TRANSLATION_REVIEW_1.1.md) | The 1.1 redesign (shipped in 1.2.0); the string files point here | Same. |
| [TRANSLATION_REVIEW_1.2.md](translations/TRANSLATION_REVIEW_1.2.md) | The 1.2 hardening work | Same. |

The current review is in [translations/](../translations/README.md).

Security details that were not fixed are not written in these files; they use generic
wording.
