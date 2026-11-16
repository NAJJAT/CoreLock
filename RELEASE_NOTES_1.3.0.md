# CoreLock 1.3.0

> **Draft (2026-11-13), proposed by the bug-fix round on branch `bug-fixes`.** The version
> is not bumped yet. The owner fills in the checksum, size and date when building and
> signing the release.

A safety and reliability update. Install it over 1.2.0: your rules, settings and history
stay. One APK for Android 7.0 and newer (ARM phones, 64- and 32-bit), published only on
GitHub Releases.

## Changed: a Block rule now covers subdomains

If you block a name such as `example.com`, CoreLock now also blocks `ads.example.com`,
`cdn.example.com` and every other name under it, the way the blocklists already did.
Before 1.3 a Block rule matched only the exact name you typed.

- **Your existing Block rules change too.** A rule for `example.com` now also stops its
  subdomains. If something you need stops working, add an Allow rule for that subdomain:
  the more exact rule wins.
- **Allow rules don't change.** They still match only the exact name. Write
  `*.example.com` to allow every subdomain.

## Stronger protection

- **Unencrypted websites are checked by name.** Before, a site reached over plain
  `http://` without a DNS lookup was only checked by address. Now its site name is checked
  against your rules and the lists, like encrypted connections. "Stopped by" says *Site
  name in an unencrypted request*. At Standard and Strict, a request whose site name can't
  be read is stopped.
- **Open connections are checked again when rules or lists change**, by their site name
  as well as by their address and app. A new Block rule now also ends connections that
  were already open to that site.
- **Limits on how many connections CoreLock tracks at once**, so a misbehaving app can't
  slow down or stop protection for the whole phone. Idle connections are closed first.
  Normal use stays far below the limits.
- **Packets are read more strictly.** Malformed or contradictory network data is refused
  instead of guessed at, in the parts that read site names (TLS, QUIC and DNS).

## HTTPS inspection and SIEM (only if your organisation uses them)

- At most 32 connections are inspected at the same time. Above that, a connection goes to
  its site without being decrypted; blocking by site name still applies. Counted in
  Diagnostics.
- Before sending to the SIEM server, CoreLock checks again that inspection is on, consent
  is valid and sending is enabled; if not, the waiting events are deleted, not sent. Events
  are only sent to the server they were collected for. The waiting queue is limited in
  size, and after a failed upload CoreLock waits longer before trying again (from 30
  seconds up to 30 minutes). "Ship now" still tries at once.
- When the inspection certificate is renewed on a managed phone where CoreLock can't move
  Android's trust by itself, Home shows the card to install the new certificate again, so
  inspection never stops without telling you.
- Certificate serial numbers are now always positive and random over their full length.

## Also fixed and improved

- **Settings › Advanced › Diagnostics** shows new counts: connection limits, connections
  from an app that could not be identified (still allowed; your per-app rules can't apply
  to them), the inspection limit, unencrypted site-name blocks, and handled errors (failed
  list downloads, which are retried; failed app lookups; inspection connections that did
  not close cleanly). Before, these errors were silently ignored.
- Stored TLS fingerprints (used to notice when an app's connections change) no longer grow
  forever: entries not seen for 180 days are removed, with at most 50 per app.
- The HTTPS inspection screen's status texts are shown in your language.
- **If CoreLock crashes, you are told at once.** "Protection stopped" now appears as soon
  as the crash happens, and goes away by itself when automatic restart brings protection
  back (about 6 seconds in our tests). Before, it only appeared if the restart failed in a
  way the app could see. After several crashes in a short time, Android itself stops
  restarting an app until you open it; the notification then stays up instead of nothing
  happening. Tap it, or open CoreLock, to turn protection back on.
- A part of the DNS anomaly check kept every name looked up since protection started, for
  a count no screen showed. An app looking up many random names could make it grow without
  limit. It is gone, and the anomaly check's own memory is now capped.

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
APK file SHA-256:            <filled in by the owner>
APK file size:               <filled in by the owner>
```

```bash
apksigner verify --print-certs CoreLock-1.3.0.apk
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
- **HTTPS inspection on a managed phone without device-owner rights:** after the
  certificate is renewed, inspection passes connections through undecrypted until the new
  certificate is installed (Home shows a card).
- In-app ads in YouTube, Instagram, Snapchat, TikTok and Facebook still can't be blocked
  without root (`docs/BLOCKING_POLICY.md`).
