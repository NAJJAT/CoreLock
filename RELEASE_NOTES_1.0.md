# PrivacyGuard 1.0.0

First public release. One APK for Android 7.0 and newer (ARM phones, 64- and
32-bit), published only on GitHub Releases. No root, no account, no backend:
analysis runs on the phone.

## What it does

PrivacyGuard runs a **local VPN** on your phone. All traffic passes through it, so
the app can show, per app, which servers and companies it talks to, how much, and
when (screen on or off, app open or in the background), and block what you don't
want. Nothing is sent to the makers of PrivacyGuard. It comes in English, Dutch,
French and Arabic.

Blocking works in four layers:

1. **Name blocking.** Every DNS lookup is checked against your own rules, the
   blocklists for your protection level and an ad-SDK policy. Blocked names get
   "not found", so no connection is made. A name that is an alias (CNAME) of a
   blocked name is blocked too. One shared decision is used for DNS, CNAME
   targets and server names (layer 3), so they never disagree.
2. **No way around the filter's DNS.** DNS over TLS (port 853) is dropped, plain
   DNS to any server goes through the filter, and encrypted DNS (DoH) servers, from
   a built-in list plus HaGeZi's regularly updated DoH lists, are stopped. Apps
   then fall back to the DNS the filter sees.
3. **Server-name blocking.** The TLS and QUIC server name (SNI) of each connection
   gets the same decision as DNS, so a blocked name is stopped even when an app
   reaches it without DNS. At Standard and Strict, a QUIC connection is held until
   its server name is known; QUIC that can't be read is dropped (apps then use
   TCP), and at Strict QUIC is off by default.
4. **Per-app rules.** Block an app completely, or only while it is in the
   background (needs Usage Access; the app tells you when it's missing).

Protection levels: **Minimal** (malware and phishing only), **Standard** (default:
plus ads and trackers, HaGeZi Pro mini) and **Strict** (plus telemetry, the full
HaGeZi Pro and OISD small). Your own rules apply at every level; when two of your
rules conflict, the more specific one wins, and Block wins a tie.

### HTTPS inspection (optional, off by default)

PrivacyGuard can also show the content of HTTPS traffic, for apps that allow it.

- **Off by default.** It only starts after you turn it on and confirm a consent
  screen, and after you install the app's certificate in Android's settings
  yourself. Turning it off deletes the app's signing key.
- **On a company-managed phone**, your organisation's MDM can turn it on and give
  consent for you. Nothing else can: only your own choice in the app, or the MDM
  of a phone your organisation manages.
- **Never silent.** While it decrypts, the VPN notification and the Home screen
  say so, including when your organisation turned it on, and you get a
  notification the moment it does.
- **Most apps ignore it.** Since Android 7, apps only trust certificates you
  install if their developer allows it, and most do not. Their traffic stays
  encrypted, and PrivacyGuard shows only the app, server, size and time, and says
  why. In practice it mostly reaches some browsers and apps built to allow it.
- What it captures stays on the phone in the encrypted database, unless an
  administrator turns on SIEM shipping (always with personal data redacted).

### What leaves the phone

- Your DNS lookups, to the resolver you choose (Cloudflare 1.1.1.1 by default;
  encrypted to cloudflare-dns.com, dns.google or dns.quad9.net if you turn on DoH).
- Blocklist downloads: raw.githubusercontent.com (cdn.jsdelivr.net if GitHub can't
  be reached) and small.oisd.nl. The first download happens at first start, on any
  network. After that, weekly on Wi-Fi or another unmetered network; on mobile data
  only once a list has had no successful download for 14 days. A list that fails is
  retried later. "Refresh now" (Settings › Blocklists) downloads them at any time.
  All lists together are about 4 MB.
- The Exodus tracker list, at most weekly: reports.exodus-privacy.eu.org.
- Only if you turn them on: Certificate Transparency checks (crt.sh) and the
  public-IP check in Diagnostics (api.ipify.org).
- Only on a company-managed phone, if the administrator turns it on: inspection
  events (redacted) to the organisation's own SIEM server.

The app does not check for updates itself. Settings › About shows your version and
opens this releases page.

## Install

1. On the phone, download `PrivacyGuard-1.0.0.apk` from this release.
2. Before installing, check the signing certificate (below).
3. Open the APK. Android asks to allow installs from the app you used (browser or
   file manager): allow it for this install, then you can turn it off again.
4. Open PrivacyGuard and follow the setup. Android asks once to allow the VPN.
5. Recommended: in Android's VPN settings turn on **Always-on VPN** for
   PrivacyGuard (and optionally "Block connections without VPN"), and in
   Settings › Diagnostics check **Battery use** and **Private DNS**.

Updating later: install the newer APK over this one. Your data stays; Android
refuses an APK signed with a different key.

## Verify the download

Only install the APK if its signing certificate matches this fingerprint:

```
Signing certificate SHA-256: caa82092caf86f9ce7d94f5b7c4a4476a3eb1a5bfa238f7b1d18cd85809bc430
APK file SHA-256:            23752350bf36dbd9bb9d11503f615df627f9dd1cdba201c7693d34b2b10e94dd
```

On a computer with the Android SDK build-tools:

```bash
apksigner verify --print-certs PrivacyGuard-1.0.0.apk
# "Signer #1 certificate SHA-256 digest:" must equal the fingerprint above
```

The file itself:

```bash
sha256sum PrivacyGuard-1.0.0.apk                      # Linux / macOS
certutil -hashfile PrivacyGuard-1.0.0.apk SHA256      # Windows
```

(`keytool -printcert -jarfile` does not work here: the APK is signed with APK
Signature Scheme v2 only.) After installing, Settings › Runtime integrity also shows
whether the running app's signature is the expected one.

## Permissions

Network access and VPN; a foreground service for the VPN; notifications; starting
after a reboot; exact alarms (to restart protection after a crash); the list of
installed apps (to name the app behind each connection); optional Usage Access
(foreground/background, for background-only rules).

For HTTPS inspection and company phones only: storage on Android 9 and older (to
save the certificate to Downloads so you can install it; asked only then), and a
device admin entry for company MDM, which does nothing unless it is activated.

No location, contacts, camera or microphone access.

## Known limitations

- **Per-app rules may miss at DNS level.** Android sends most lookups through its
  own resolver, so the app behind a lookup is often not known; per-app Allow
  rules and ad-SDK exceptions then fall back to the every-app decision.
- **Private DNS in hostname mode must be turned off.** With a hostname set
  ("strict"), lookups bypass the filter and fail while the VPN runs. Use "Off" or
  "Automatic". The app warns you on the Home screen and in Diagnostics.
- **Chrome's "Use secure DNS" must stay on automatic.** With a specific provider
  chosen (for example Cloudflare), Chrome sends lookups only to that encrypted
  server, which PrivacyGuard blocks, and pages fail to load. In Chrome, open
  Settings › Privacy and security › Use secure DNS and choose "Use your current
  service provider".
- **QUIC at Strict.** QUIC is blocked by default at Strict, and unreadable QUIC
  is dropped at Standard and Strict. Apps fall back to TCP, which can make some
  connections a little slower to start.
- **HTTPS inspection reaches few apps.** Most apps on Android 7 and newer ignore
  user-installed certificates or pin their own, so their content stays encrypted
  and only metadata is shown.
- **No Wi-Fi-only or mobile-only rules.** Rules apply on every network.
- **Blocklists are held in memory** while protection runs (Strict uses the
  most), which counts toward the app's memory use.
- **Always-on VPN is recommended.** PrivacyGuard restarts itself after a reboot or
  a crash, but without Always-on Android or the phone maker's battery saver may
  still stop it in the background, and until it is back traffic goes out
  unfiltered. With "Block connections without VPN" nothing leaves unprotected.
- **In-app ads in YouTube, Instagram, Snapchat, TikTok and Facebook can't be
  blocked without root.** They come from the same servers as the content, so
  blocking them by name would break the app. What CoreLock blocks, and what it
  can't, is explained in
  [docs/BLOCKING_POLICY.md](https://github.com/NAJJAT/CoreLock/blob/main/docs/BLOCKING_POLICY.md).
