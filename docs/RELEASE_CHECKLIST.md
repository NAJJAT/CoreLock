# Release and deploy checklist

For every CoreLock release (CLAUDE.md §12). The owner builds, signs, tags and publishes;
the agent prepares everything else. Tick each line, or write why it does not apply.

## 1. Before building

- [ ] `main` green on all four required checks (unit tests + lint + R8, OSV scan,
      instrumented tests, gitleaks). The last nightly run is green too, or its failure
      is understood (fuzz, live lists).
- [ ] `versionCode` is higher than the last release's; `versionName` follows semantic
      versioning (`app/build.gradle.kts`).
- [ ] `RELEASE_NOTES_X.Y.Z.md`: what's new, what's fixed, what can't be blocked
      (CLAUDE.md §16.1), how to update, and anything users must do after updating.
- [ ] Translations reviewed (`docs/TRANSLATION_REVIEW_X.Y.md`): Arabic by the owner,
      French and Dutch by native speakers, or the release notes say which are machine-made.
- [ ] Phone test done (`docs/PHONE_TEST_X.Y.Z.md`) on a real phone, including an update
      over the previous release with data kept.
- [ ] README: latest version, Install section and the network table match the code
      (`OutboundHostsDisclosureTest` passes).

## 2. Building and signing (owner only)

- [ ] `./gradlew :app:assembleEnterpriseRelease` with the real key. The build must fail
      without it; there is no debug-key fallback.
- [ ] `apksigner verify --print-certs` shows exactly one signer, whose SHA-256 equals the
      README fingerprint.
- [ ] APK renamed `CoreLock-X.Y.Z.apk`; its SHA-256 written into the release notes.
- [ ] APK size noted (CLAUDE.md §6.2) and compared with the last release.

## 3. Publishing (owner only)

- [ ] Tag `vX.Y.Z` on the commit that was built.
- [ ] Release page: the APK, its SHA-256, the signing fingerprint (same as the README),
      the release notes and the known limits.
- [ ] After publishing, the **Release checks** workflow (`.github/workflows/release-checks.yml`)
      is green. It checks the signer against the README at the tag and that the release
      text shows each APK's SHA-256, and adds `SHA256SUMS.txt`. A red run means: don't
      announce the release; fix the release text or the APK first.

## 4. Deploy checks (after publishing)

- [ ] Download the APK from the release page on a phone (not the local file) and install
      it over the previous release: data kept, protection starts, Home shows the true state.
- [ ] `sha256sum -c SHA256SUMS.txt` on the downloaded file passes.
- [ ] Settings › Runtime integrity shows the signature matches.
- [ ] Settings › About shows the new version and opens the releases page.
- [ ] First blocklist download succeeds on that phone (Settings › Advanced › Diagnostics).
- [ ] Watch new issues for a week; a broken update is a reason for a quick X.Y.Z+1.

## Attestations (decision for the team)

GitHub artifact attestations (Sigstore, `actions/attest-build-provenance`) state that a
file was **built** by a given workflow from a given commit. CoreLock's APK is built and
signed on the owner's machine, so a workflow can't truthfully attest that it built it.
The options:

| Option | What it gives users | Cost and risk |
|---|---|---|
| **A. No attestations** (today) | Checksum + signing fingerprint + `SHA256SUMS.txt`, checked by the release workflow. | Nothing new. Users trust the owner's machine and key. |
| **B. Build in Actions, sign in Actions** | A real build provenance: this APK came from this commit, built by GitHub. | The signing key moves into GitHub secrets: a stolen repository token or a compromised action could sign a "CoreLock update". Against CLAUDE.md §1.2 today; needs the owner's explicit decision. New action (Sigstore) as a supply-chain dependency. |
| **C. Build unsigned in Actions, attest that; owner signs the same bytes** | Provenance for the unsigned APK; the signed one differs only by the signature block. | Needs a reproducible build (same output on the owner's machine and in CI) and a check that the signed APK's contents equal the attested one. Not verified that the build is reproducible today. New action as in B. |

Recommendation: **A for 1.2**, and test whether the build is reproducible (C) after it.
Option B weakens the strongest control the app has (who can sign an update).

## Sideloading and Google developer verification

The README Install section is the user guide (download, check checksum and fingerprint,
allow the install once, update over the old version). What changes when Google's developer
verification reaches sideloaded apps (announced for 2027) depends on the team's decision;
the facts are in [GOOGLE_DEVELOPER_VERIFICATION.md](GOOGLE_DEVELOPER_VERIFICATION.md).
When it is decided, update the README Install section in the same change.
