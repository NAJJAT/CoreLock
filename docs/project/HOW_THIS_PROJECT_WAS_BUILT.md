# How this project was built

An account of how CoreLock was made, who decided what, and how the work was checked.
Facts come from this repository (git history, `CLAUDE.md`, the ADRs in `docs/adr/`, the
progress logs, test records and release notes). Places only the owner can fill in are
marked **[OWNER: …]**.

## Summary

CoreLock is an Android firewall and privacy app that works without root, using Android's
VPN service to filter traffic on the phone itself. It was built by me, Ammar Najjar, with
AI assistance, between 21 April 2026 (first commit) and November 2026. I decided what to
build, reviewed the AI's work, tested it and released it; AI tools were used for the
implementation, tests and documents, under rules I set. [OWNER: if you want, say roughly
how much of the code the AI wrote; the repository does not record it.] The repository has 186 commits; 180 are mine
and 6 are automatic dependency updates (Dependabot). Two versions are published (tags
`v1.0.0` and `v1.2.0`); 1.2.1 is prepared.

## My role

- **Decisions.** I made the product and engineering decisions recorded in `CLAUDE.md` and
  the ADRs. Examples:
  - the GPL-3.0 license (2026-10-25);
  - a single app flavor instead of two (ADR 0006, 2026-10-23);
  - publishing on GitHub instead of Google Play (ADR 0001);
  - which blocklists to use (ADR 0003);
  - the fail-closed design: when CoreLock can't read where a connection goes, it blocks
    it rather than letting it through (`CLAUDE.md` §4.1);
  - which audit findings to fix, document or postpone, and when to release.

  Of the 17 ADRs, 10 are accepted and 7 are proposed and still waiting for my decision.
- **Reviews.** I read every report the coding agent wrote before acting on it, and I
  asked for changes when something was wrong or unclear. [OWNER: give one or two
  examples of problems you caught yourself.]
- **Outside contact.** I contacted AdGuard Support myself and got a written confirmation
  that their DNS filter may be used in a GPL-3.0 project (ADR 0008, 2026-10-28).
- **Testing on my phone.** Device tests ran on my own phone, a Samsung Note20 with
  Android 13 (`docs/testing/SECURITY_TEST_2026-10-14.md`, `docs/archive/testing/PHONE_TEST_1.2.0.md`).
- **Releases.** I build and sign every release with my own key, which never enters the
  repository (`CLAUDE.md` §1.2, §12). I made every commit and every merge.

## AI tools used, and for what

- **Claude (chat):** planning, architecture and code reviews, writing the prompts for the
  coding agent, and the phone-test checklists.
- **Claude Code (coding agent):** implementation, tests, security audits, documentation
  and emulator checks. 43 of the 186 commits carry a `Co-Authored-By` line for a Claude
  model (24 Claude Sonnet 4.6, 19 Claude Opus 5.5). [OWNER: say whether other commits
  were also AI-assisted without that line.]
- **Skills** (instruction packs the agent loads):
  - my own `senior-android-engineer` skill;
  - Google's Android skills;
  - gstack review commands.

## How I kept control

- **Written rules.** `CLAUDE.md` is the project's engineering handbook and also the
  instruction file the agent reads first.
- **Git.** Its first section forbids the agent to commit, push, merge, rebase, switch
  branches or change GitHub settings; it may only propose commits, and I made them myself
  after reviewing the changes (§1.1).
- **Secrets.** The agent may not read, copy or print the signing key or any password,
  and a release can't be built without my real key (§1.2).
- **My phone.** Tests on my personal phone follow written rules (added in commit
  `0e81385`); the agent used an emulator otherwise.
- **Stop and ask.** The agent must stop and ask before schema changes, new permissions,
  new network hosts, new dependencies, or changes to the HTTPS inspection code (§14.2).
- **Tests first, with proof.** Every bug fix must come with a test that fails without the
  fix: the agent removes the fix, shows the failure, restores it, and reports it (§9.2).
  The progress log records this for each fix (`docs/archive/progress/BUGFIX_PROGRESS.md`).
- **Independent audits.** Two security audits were done as separate tasks before any
  fixing (October and November 2026).

## How the work was verified

- **Unit tests:** 1,296, with 0 failures at the end of the 1.2.1 work (1,202 when that
  round started).
- **Instrumented tests:** 22 on an emulator, in 7 files, including Room database
  migration tests (`AppDatabaseMigrationTest` alone has 9).
- **Continuous integration:** on every pull request, CI runs the unit tests, lint, the
  release shrinking step (R8), a dependency vulnerability scan (OSV), the instrumented
  tests on an emulator, and a secret scan. A nightly job runs longer fuzz tests and a
  test against the live blocklists.
- **Security audit 1 (2026-10-14, OWASP MASVS checklist):** 18 findings. 3 were High and
  none Critical. 12 are fixed, 4 partly fixed with the rest planned, and 1 was left open
  by design (V-17, Block rules not covering subdomains; changed in 1.2.1, ADR 0017); the
  last one (V-14, a dependency scan) is now part of CI
  (`docs/testing/SECURITY_TEST_2026-10-14.md`).
- **Security audit 2 (2026-11-11):** 18 findings (F-1 to F-18); the details stayed with
  me until they were fixed. Fixes are listed with their tests in
  `docs/archive/progress/BUGFIX_PROGRESS.md`. [OWNER: add the severity summary if you want it here; it is
  not written in the repository.]
- **Upgrade test 1.0.0 → 1.2.0:** a checklist on a real phone (`docs/archive/testing/PHONE_TEST_1.2.0.md`,
  check 0). [OWNER: write the result; it is not recorded in the repository.]
- **Crash recovery (V-18), emulator, 2026-11-16:**
  - After a crash, protection was back in about 6–7 seconds.
  - With Android's "Block connections without VPN" on, 0 of 45 test connections got out
    while it restarted.
  - The run found a case where the user got no warning at all; it was fixed.
- **Speed of the hot path** (the code that runs for every connection): the name check
  takes about 3.4 µs per connection and the QUIC check about 9.5–9.9 µs per new flow. Both
  were measured before and after each change, with no slowdown in the 1.2.1 work.

## What I learned

[OWNER: write this section yourself.]

## Limitations and honesty notes

- **Translations.**
  - The Arabic, French and Dutch texts were machine-translated by the AI and are listed
    for review in `docs/translations/` and `docs/archive/translations/`.
  - Arabic is reviewed by me; French and Dutch still need native speakers.
  - [OWNER: say which reviews are done.]
- **Not tested on a real phone.**
  - The 1.2.1 changes were checked with tests and on the emulator only, including the
    crash-recovery work and the new Home warning.
  - Also untested on a phone: an overnight battery test, and two fixes in the 1.2.1 work
    (F-9 and F-16) that were checked by reading the code.
- **Known limitations of the app** are listed in the README and in `CLAUDE.md` §16.1.
  Examples:
  - ads inside apps such as YouTube or Instagram can't be blocked without root;
  - Encrypted Client Hello hides some site names;
  - the kill switch depends on Android's Always-on VPN.
- **Commit messages.** Some early commit messages are informal or have typos; the history
  was kept as it is and not rewritten.
