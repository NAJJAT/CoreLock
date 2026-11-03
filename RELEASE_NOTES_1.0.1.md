# CoreLock 1.0.1

An update to 1.0.0. Install it over 1.0.0: your rules, settings and history stay.
One APK for Android 7.0 and newer (ARM phones, 64- and 32-bit), published only on
GitHub Releases.

## The app is now called CoreLock

1.0.0 was named PrivacyGuard on the phone. From this version the app, its
notifications, the VPN name in Android's settings, the Quick Settings tile
("CoreLock VPN") and the IT report all say **CoreLock**.

Nothing else about the app changes: it is the same app, signed with the same key,
and it updates 1.0.0 in place. Its package name stays
`com.privacyguard.app.enterprise`, because Android only installs an update over an
app with the same package name. The HTTPS-inspection certificate keeps its name,
"PrivacyGuard Enterprise CA", so if you installed it you do not need to install it
again.

## What's new and fixed

- **Blocklists now stay up to date.** In 1.0.0 the lists were downloaded only at
  first start and when you tapped "Refresh now". Now they refresh by themselves:
  weekly on Wi-Fi or another unmetered network, and on mobile data only when a list
  has had no successful download for 14 days. A list that fails to download is
  retried later instead of staying missing.
- **Safer list updates.** A downloaded list with far fewer entries than the one
  already stored is refused and the stored list is kept, so a broken or tampered
  download can't quietly switch off most of your protection.
- **Strict no longer blocks Google Safe Browsing.** Blocking it turned off Chrome's
  warnings for phishing and malware sites. The Strict description in Settings no
  longer lists it.
- **Security fixes.** An internal security review led to fixes in HTTPS inspection
  and in the VPN engine: stricter checks that consent was given before anything is
  captured, safer handling of captured content, and more robust rule updates. We
  publish no further detail, so as not to help anyone attack phones that have not
  updated yet.
- **Kill switch shows the real state.** Home and Settings no longer show a switch for
  something only Android controls. They show whether Android's "Always-on VPN" and
  "Block connections without VPN" are on, with a button that opens Android's VPN
  settings. The separate "VPN dropped" alert was removed: it could not warn you
  reliably once protection had already stopped.
- **Corrected Dual VPN texts.** The Dual VPN screens now say plainly that SOCKS5 is
  not encrypted: server 1 can see your IP address, every destination and your
  server 2 login, while websites see server 2's address. The content of HTTPS
  connections stays encrypted.
- **Clearer texts about limits.** The app now says that per-app rules can
  occasionally apply to the wrong app on Android 7 to 9, and what is and is not
  removed from HTTPS inspection events sent to an organisation's SIEM server.
- **Documentation.** The README, the blocking policy and the technical description
  were updated to match the app, including how often lists are downloaded and what
  CoreLock can and can't block.

## What leaves the phone

Unchanged from 1.0.0, except when blocklists are downloaded:

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
  events (headers and text bodies redacted; web addresses, form data and binary
  bodies not yet) to the organisation's own SIEM server.

The app does not check for updates itself. Settings › About shows your version and
opens the releases page.

## Install or update

1. On the phone, download `CoreLock-1.0.1.apk` from this release.
2. Before installing, check the signing certificate (below).
3. Open the APK. Android asks to allow installs from the app you used (browser or
   file manager): allow it for this install, then you can turn it off again.
4. **If 1.0.0 (PrivacyGuard) is installed,** Android installs 1.0.1 over it as an
   update. Your data stays; afterwards the app is called CoreLock. Android refuses an
   APK signed with a different key.
5. **New install:** open CoreLock and follow the setup. Android asks once to allow
   the VPN. Recommended: in Android's VPN settings turn on **Always-on VPN** for
   CoreLock (and optionally "Block connections without VPN"), and in
   Settings › Diagnostics check **Battery use** and **Private DNS**.

## Verify the download

Only install the APK if its signing certificate matches this fingerprint. It is the
same key as 1.0.0.

```
Signing certificate SHA-256: caa82092caf86f9ce7d94f5b7c4a4476a3eb1a5bfa238f7b1d18cd85809bc430
APK file SHA-256:            <APK_SHA256>
```

On a computer with the Android SDK build-tools:

```bash
apksigner verify --print-certs CoreLock-1.0.1.apk
# "Signer #1 certificate SHA-256 digest:" must equal the fingerprint above
```

The file itself:

```bash
sha256sum CoreLock-1.0.1.apk                      # Linux / macOS
certutil -hashfile CoreLock-1.0.1.apk SHA256      # Windows
```

(`keytool -printcert -jarfile` does not work here: the APK is signed with APK
Signature Scheme v2 only.) After installing, Settings › Runtime integrity also shows
whether the running app's signature is the expected one.

## Known limitations

- **Per-app rules may miss at DNS level.** Android sends most lookups through its
  own resolver, so the app behind a lookup is often not known; per-app Allow
  rules and ad-SDK exceptions then fall back to the every-app decision.
- **Per-app rules on Android 7 to 9.** On these versions a per-app rule can
  occasionally apply to the wrong app. Android 10 and newer are not affected.
- **Private DNS in hostname mode must be turned off.** With a hostname set
  ("strict"), lookups bypass the filter and fail while the VPN runs. Use "Off" or
  "Automatic". The app warns you on the Home screen and in Diagnostics.
- **Chrome's "Use secure DNS" must stay on automatic.** With a specific provider
  chosen (for example Cloudflare), Chrome sends lookups only to that encrypted
  server, which CoreLock blocks, and pages fail to load. In Chrome, open
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
- **The first start needs a network for malware protection.** The lists built into
  the app as a fallback do not yet contain a real malware list, so until the first
  download succeeds, malware and phishing sites are not blocked.
- **Always-on VPN is recommended.** CoreLock restarts itself after a reboot or
  a crash, but without Always-on Android or the phone maker's battery saver may
  still stop it in the background, and until it is back traffic goes out
  unfiltered. With "Block connections without VPN" nothing leaves unprotected.
- **In-app ads in YouTube, Instagram, Snapchat, TikTok and Facebook can't be
  blocked without root.** They come from the same servers as the content, so
  blocking them by name would break the app. What CoreLock blocks, and what it
  can't, is explained in
  [docs/BLOCKING_POLICY.md](https://github.com/NAJJAT/CoreLock/blob/main/docs/BLOCKING_POLICY.md).
