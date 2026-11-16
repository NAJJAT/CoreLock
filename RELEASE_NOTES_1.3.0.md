# CoreLock 1.3.0

A safety and reliability update. Install it over 1.2.0: your rules, settings and history
stay. One APK for Android 7.0 and newer (ARM phones, 64- and 32-bit), published only on
GitHub Releases.

## Changed: a Block rule now covers subdomains

If you block a name such as `example.com`, CoreLock now also blocks `ads.example.com`,
`cdn.example.com` and every other name under it, the way the blocklists already do.
Before 1.3.0 a Block rule matched only the exact name you typed.

- **Your existing Block rules change too.** A rule for `example.com` now also stops its
  subdomains. If something you need stops working, add an Allow rule for that subdomain:
  the more exact rule wins.
- **Allow rules don't change.** They still match only the exact name. Write
  `*.example.com` to allow every subdomain.

## You are told when protection stops

- **If CoreLock crashes, "Protection stopped" appears at once**, and goes away by itself
  when automatic restart brings protection back (about 6 seconds in our tests). If Android
  doesn't allow the restart (it stops restarting an app that crashed several times in a
  short time), the notification stays up. Tap it, or open CoreLock, to turn protection back
  on.
- **Home warns you when notifications are off.** Without notifications CoreLock can't tell
  you that protection stopped. The card opens Android's notification settings for
  CoreLock.

## Stronger protection

- **Unencrypted websites are checked by site name too**, against your rules and the lists,
  like encrypted connections. "Stopped by" then says *Site name in an unencrypted request*.
  At Standard and Strict, a request whose site name can't be read is stopped.
- **Open connections are checked again when rules or lists change**, also by site name. A
  new Block rule now also ends connections that were already open to that site.
- **Security fixes in how CoreLock reads network traffic and keeps track of connections.**
  Unusual or malformed traffic is refused instead of guessed at, and the memory CoreLock
  uses for tracking stays within fixed limits, so one misbehaving app can't slow down or
  stop protection for the whole phone. Normal use stays far below the limits.

## HTTPS inspection and SIEM (only if your organisation uses them)

- **Security fixes in HTTPS inspection and SIEM sending.** CoreLock checks again, just
  before sending, that inspection is on, consent is valid and sending is enabled;
  otherwise the waiting events are deleted, not sent. The number of connections inspected
  at once is limited; above the limit a connection goes to its site without being
  decrypted, and blocking by site name still applies.
- After a failed upload CoreLock waits longer before trying again (from 30 seconds up to
  30 minutes). "Ship now" still tries at once.
- **"Clear all data" now also deletes events waiting to be sent to the SIEM server.**
- When the inspection certificate is renewed on a managed phone where CoreLock can't move
  Android's trust by itself, Home shows the card to install the new certificate again, so
  inspection never stops without telling you.

## Also fixed and improved

- **Settings › Advanced › Diagnostics** shows new counts: connection limits, connections
  from an app that could not be identified (still allowed; your per-app rules can't apply
  to them), the inspection limit, unencrypted site-name blocks, and handled errors (failed
  list downloads, which are retried; failed app lookups; inspection connections that did
  not close cleanly). Before, these errors were not shown anywhere.
- Stored TLS fingerprints (used to notice when an app's connections change) no longer grow
  forever: entries not seen for 180 days are removed, with at most 50 per app.
- The HTTPS inspection screen's status texts are shown in your language.

## What leaves the phone

Nothing new. CoreLock contacts the same hosts as 1.2.0 (README, *Network disclosure*). No
analytics, no crash reports, no update check.

## How to update from 1.2.0

1. On the phone, download `CoreLock-1.3.0.apk` from this release.
2. Before installing, check the signing certificate and the checksum (below).
3. Open the APK and allow the install. Android installs 1.3.0 over 1.2.0 as an update and
   refuses an APK signed with a different key.
4. Look at your Block rules once (Settings › Rules and allowed sites): they now cover
   subdomains.

## Verify the download

Only install the APK if its signing certificate matches this fingerprint. It is the same
key as 1.0.0 and 1.2.0.

```
Signing certificate SHA-256: caa82092caf86f9ce7d94f5b7c4a4476a3eb1a5bfa238f7b1d18cd85809bc430
APK file SHA-256:            <APK_SHA256>
APK file size:               <APK_SIZE>
```

```bash
apksigner verify --print-certs CoreLock-1.3.0.apk
# "Signer #1 certificate SHA-256 digest:" must equal the fingerprint above
sha256sum -c SHA256SUMS.txt                       # with SHA256SUMS.txt from this release
certutil -hashfile CoreLock-1.3.0.apk SHA256      # Windows
```

## Known limitations

Everything listed for 1.2.0 still applies (README, *Known limitations*). Written down for
the first time in this version:

- **Address rules work for IPv4 only.** A rule for an IPv6 address has no effect.
- **Fragmented UDP is dropped.** Apps that send UDP messages larger than the tunnel's
  packet size fail while protection is on; counted in Diagnostics.
- **Before the first unlock after a restart, CoreLock is not running.** Only Android's
  Always-on VPN with "Block connections without VPN" keeps traffic from leaving unfiltered
  until it starts.
- **With notifications off, a crash can't be reported to you.** Home shows a card until
  you turn them on.
- **HTTPS inspection on a managed phone without device-owner rights:** after the
  certificate is renewed, inspection passes connections through undecrypted until the new
  certificate is installed (Home shows a card).
- In-app ads in YouTube, Instagram, Snapchat, TikTok and Facebook still can't be blocked
  without root (`docs/BLOCKING_POLICY.md`).
