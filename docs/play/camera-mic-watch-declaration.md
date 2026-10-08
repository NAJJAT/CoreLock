# Camera & Mic Watch — Play Console declarations

## Usage Access (PACKAGE_USAGE_STATS)

**Feature that uses it:** Camera & Mic Watch (Settings → Camera & Mic Watch).

**Declaration text:**

> CoreLock uses Usage Access only to identify which app was on screen when the
> camera or microphone switched on, so that its privacy alerts can name the app
> instead of saying "an app", and to tell whether the user had opened that app
> in the last five minutes. CoreLock reads app open/close times only; it does
> not read screen contents, messages or keystrokes. The data is processed on the
> device, kept for at most 30 days and never uploaded or shared.

**User-facing disclosure (shown before the system setting):** the "Which app
was it?" screen (`UsageAccessScreen`) explains what is read and what is not, and
the feature works without it (alerts then say "an app" and list candidates).

**Prominent disclosure placement:** inside the app, before the user is sent to
`Settings.ACTION_USAGE_ACCESS_SETTINGS`; never requested at install or start-up.

## Not in the Play (consumer) build

`GET_APP_OPS_STATS` (exact attribution, "Advanced mode") is a system permission
granted only with `adb shell pm grant`. It is declared in the **enterprise**
flavor manifest only, for managed devices whose administrator grants it. The
consumer build must not declare it.

## Product copy

Use: "Detects camera and microphone use through Android's system signals."

Also state: "It cannot see spyware that works around Android itself (rooted or
kernel-level tools)."

Do not claim detection of Pegasus-class, rooted or kernel-level spyware: those
can use the camera and microphone without passing through the signals CoreLock
watches.

## Data safety form

- Data collected: App activity → "Other actions" (which app was in the
  foreground, camera/mic use times). Processed on device only.
- Shared: no. Encrypted at rest: yes (SQLCipher). Deletable: yes (Settings →
  Data management → Clear all connection data; auto-deleted after 30 days).
