# Google developer verification: what it means for CoreLock

Brief for the team decision on developer verification (hardening plan, task 0.5). **Sources
checked on 2026-11-05**: Google's official pages listed at the end. Google changes these pages
often; check them again before acting, and treat anything marked *not stated* as unknown,
not as "no".

This is a decision brief, not a decision. The choice is reserved for the team.

## What it is

Google is adding a check to certified Android devices (phones with Google Play services):
an app can only be installed or updated if its package name and signing key are registered
to a developer whose identity Google has verified. The check is done by Android itself,
not by the app; CoreLock's code does not change, and CoreLock contacts no new host.

## Timeline, as Google states it

| When | What |
|---|---|
| March 2026 | Verification open to all developers (Play Console and the new Android Developer Console). |
| August 2026 | Limited distribution accounts, a Console API, and the "advanced flow" for users. |
| 30 September 2026 | Enforced for apps installed **from participating stores** (Google Play, Galaxy Store, Xiaomi GetApps, OPPO, vivo, HONOR, Transsion) in Brazil, Indonesia, Singapore and Thailand. |
| 2027 | "Verification capability will be expanded globally for all apps on certified Android devices." No date. |

Android versions: the developer pages say Android 7 and newer; Google's user help page says
Android 8 and newer. The pages disagree.

## What it means for CoreLock today

- CoreLock ships only as an APK on GitHub Releases. Google's FAQ (updated 15 July 2026):
  "if users sideload your app directly, these new verification requirements won't apply to
  your app yet." **So nothing changes for CoreLock users now.**
- Google adds: "we still recommend that you plan to complete your verification before the
  global rollout begins in 2027."
- *Not stated:* whether, and from when, an APK downloaded from a website will be blocked in
  2027. "All apps" suggests it will be; that is an inference.
- If it is: an unregistered CoreLock could then only be installed or **updated** through
  the advanced flow or adb. Google's FAQ: "if the advanced flow is disabled updates to
  unregistered apps will fail." For a security app that must be updated, this matters most.
- AOSP builds and devices without Google certification are exempt.

## The options

| Option | What it takes | For users | Cost and risk |
|---|---|---|---|
| **A. Register (full account)** | Android Developer Console account, identity verification ($25 fee; one-time or yearly *not stated*), then register `com.privacyguard.app.enterprise` with the SHA-256 fingerprint of the release signing certificate (the one in the README). | Install and update as today. | Ties the developer's verified identity to the app at Google. What data is required, and whether any is shown to users, is *not stated* on the pages read. "If you lose your signing key you won't be able to register your packages." If the package name were claimed by someone else first, Google may refuse it. |
| **B. Don't register** | Nothing. | From 2027 (probably): install and every update through the advanced flow (developer options, a "not being coached" check, restart, a one-day wait, then PIN or biometric; can stay enabled "indefinitely") or adb. | Much harder installs for non-technical users; looks like malware to them; some will be unable to update a security app. |
| **C. Limited distribution account** | Free, no government ID. | Only up to **20 devices**. | Not usable for a public release. Can be upgraded to full later, not back. |

Google's open-source guide says registering "will let your users continue with the same
install experience they have today"; not registering means users use the advanced flow.

## What does not change in any option

- The release signing key stays the owner's; Google is given only its public certificate
  fingerprint (for a self-signed app the guide asks for the SHA-256 fingerprint, not the
  key and not an APK).
- The app ID `com.privacyguard.app.enterprise` stays.
- No code change, no new host contacted by CoreLock, no telemetry. The privacy promise in
  the README is about CoreLock; Android's own verifier talking to Google is part of the
  phone, as Play Protect already is. The README should say this in one sentence once the
  team decides.

## If the team chooses A: steps (owner only)

1. Create an Android Developer Console account (outside Play) and complete identity
   verification.
2. Add the package `com.privacyguard.app.enterprise` and its signing-certificate SHA-256
   fingerprint (as published in the README). The agent never handles the key.
3. Check the package shows *Registered*.
4. Record in `RELEASE_NOTES_X.Y.md` and the README Install section that the app is
   registered, and what the user sees if it isn't (for builds from source with another key).
5. Builds signed with another key (people building from source) can't use our
   registration: the README Building section should point them to adb or the advanced flow.

## Sources (read 2026-11-05)

- [Android developer verification (overview)](https://developer.android.com/developer-verification)
- [Frequently asked questions](https://developer.android.com/developer-verification/guides/faq?hl=en)
- [Open-source app registration](https://developer.android.com/developer-verification/guides/open-source-app-registration)
- [Android Developers Blog, 18 June 2026](https://android-developers.googleblog.com/2026/06/android-developer-verification.html)
- [Understanding Android developer verification (Console Help)](https://support.google.com/android-developer-console/answer/16561738?hl=en)
- [Learn about Android developer verification (Android Help)](https://support.google.com/android/answer/17065026)

## Open question for the team

Register CoreLock (option A) before the 2027 global rollout, accepting that the
developer's verified identity is linked to the app at Google, or stay unregistered
(option B) and accept that, probably from 2027, users must use the advanced flow or adb
to install and update? If A: who holds the account, and which identity (person or
organisation, which needs a D-U-N-S number)?
