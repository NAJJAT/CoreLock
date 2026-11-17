# CoreLock 1.2.0

The first update since 1.0.0: a new, simpler app around the same protection (planned as
1.1), and protection that is harder to get around and lighter on memory (1.2). Install it
over 1.0.0: your rules, settings and history stay. One APK for Android 7.0 and newer (ARM
phones, 64- and 32-bit), published only on GitHub Releases.

Versions 1.0.1 and 1.1.0 were prepared but never published. Everything they contained is
in this version and listed below.

## The app is now called CoreLock

1.0.0 was named PrivacyGuard on the phone. From this version the app, its notifications,
the VPN name in Android's settings, the Quick Settings tile ("CoreLock VPN") and the IT
report all say **CoreLock**.

It is the same app, signed with the same key, and it updates 1.0.0 in place. Its package
name stays `com.privacyguard.app.enterprise`, because Android only installs an update over
an app with the same package name.

## What's new: a simpler app

The way CoreLock blocks did not change. What changed is how you see and control it.

- **Four tabs instead of six:** Home, Apps, Activity and Settings.
- **Home** says in one line whether you are protected, with one switch. Below it: your
  protection level and how many things were blocked today. Each opens its list.
- **"Something not working?"** on Home finds what an app needs in three taps, without
  typing: pick the app, see what was blocked for it (most likely cause first), allow it
  for that app or everywhere. Every change can be undone.
- **Pause protection for 5 minutes**, from the same place, when you just want to try
  without CoreLock. It always comes back on by itself, also if the app is closed or the
  phone restarts, and a notification counts down with "Resume now". It is only offered
  when Android lets CoreLock guarantee the restart.
- **Activity** shows what happened in plain words ("Google ads · Blocked · Ads and
  trackers list"), grouped by time, with repeats merged. Tap a row to see why, and to
  allow or block it for that app or everywhere. Allowing a name that is on a malware or
  phishing list still shows a warning first.
- **Apps** lists your apps with what each one is allowed to do, how much was blocked
  today and one plain risk word. An app's page shows its internet access (allowed,
  blocked in background, blocked) and what it connected to recently; all the technical
  details of 1.0 are under "Technical details".
- **Settings** is short and grouped: Protection, Notifications, Language, Appearance
  (light, dark or as the phone), Data, About. Expert settings are under **Advanced**.
- **Rules and allowed sites** shows each rule by its site, app or address, says what it
  does in words, and has a search. Adding a rule checks what you typed and turns a pasted
  web address into its site name.
- **Export and import of rules and settings** (Settings › Data). Move your rules and
  settings to another phone with a file you save where you choose. The file is private:
  anyone who has it can read your rules. Import shows what it will add and change before
  anything happens, never replaces your own rules, and never lets an imported rule open a
  malware or phishing site. It does not carry your history, passwords, a DNS server you
  typed yourself, proxy servers or HTTPS inspection. It is turned off on phones an
  organisation manages.
- **A shorter start.** A new install explains CoreLock in three screens: what it does and
  can't do, why Android asks about a VPN (with what leaves the phone), and which level to
  use. A battery warning appears only when Android restricts CoreLock.
- **New app icon and start screen:** a shield with a keyhole, in the app's colours, also
  as a themed icon on Android 13 and newer.
- **One "blocked today" number.** The notification, Home, Activity, Apps, Statistics and
  Security systems now count the same thing over the same time: since midnight. In 1.0
  some showed the last 24 hours, so the numbers could differ.
- **Clearer reasons.** A name blocked because it is an ad tracker built into apps now
  says so ("Blocked · An ad tracker built into the app") instead of a general reason. The
  Ads detail screen shows "Not used" for lists your level doesn't use.
- **Exports ask for your screen lock.** Exporting your history, the Statistics CSV and
  your rules and settings asks for your phone's PIN, pattern or fingerprint first, so
  someone holding your unlocked phone can't copy your history in two taps.
- **Light mode everywhere.** The expert screens (Statistics, Security systems, Security
  analysis, Alerts, the domain page and others) follow the light theme too, and their
  greyest text is easier to read.
- **Large text and right-to-left:** every screen was checked at 200 % text size and in
  Arabic, English, French and Dutch.

### Where things moved

| In 1.0 | Now |
|---|---|
| Stats tab | Settings › Advanced › Analysis › Statistics |
| Payload tab | Settings › Advanced › HTTPS inspection |
| Traffic tab | Activity tab; live connections under Activity › ⋮ › Live connections |
| Dashboard cards (security systems, night report) | Settings › Advanced › Analysis |
| DNS, encrypted DNS, QUIC, proxies (Dual VPN) | Settings › Advanced › Network |
| Diagnostics, battery, integrity, list details | Settings › Advanced › Diagnostics |
| Rules | Settings › Rules and allowed sites |

A one-time card on Home after the update links to Statistics and HTTPS inspection.
Notifications posted by 1.0 still open the right screen.

## What's new: stronger protection

- **Protected from the first start.** A copy of the HaGeZi malware and phishing list now
  ships inside the app, so malware and phishing sites are blocked even before the first
  list download. It is replaced by the live list as soon as that downloads.
- **Blocklists stay up to date.** In 1.0.0 the lists were downloaded only at first start
  and when you tapped "Refresh now". Now they refresh by themselves: weekly on Wi-Fi or
  another unmetered network, and on mobile data only when a list has had no successful
  download for 14 days. A list that fails to download is retried later instead of
  staying missing.
- **Safer list updates.** A downloaded list with far fewer entries than the one already
  stored is refused and the stored list is kept, so a broken or tampered download can't
  quietly switch off most of your protection.
- **Less memory for blocklists.** Lists are now stored in a compact form, about a tenth
  of the memory they used before. Strict profits most.
- **Harder to slip past.** At Standard and Strict, an encrypted connection whose start
  can't be read (cut off, malformed, too large, or with a server name that is not a
  name) is stopped instead of let through. Connections to intranet names (such as
  `wiki`) or to an IP address still work. The count is in Settings › Advanced ›
  Diagnostics.
- **HTTPS/SVCB answers are checked.** A DNS answer that points the browser to a blocked
  name now gets an empty answer, so the browser can't use that name, and the site itself
  keeps working.
- **New, optional at Strict: read names behind Encrypted Client Hello.** Some browsers
  hide the name of the site they connect to. With this switch on (Settings › Advanced ›
  Network, off by default), CoreLock removes the keys that make this possible from DNS
  answers, so it can see and block those names again. Your network can then see these
  names too. It doesn't reach browsers that use their own encrypted DNS.
- **Strict no longer blocks Google Safe Browsing.** Blocking it turned off Chrome's
  warnings for phishing and malware sites.

## HTTPS inspection (only if you use it)

- **It asks first, every time** you switch it on, and its consent and confirmation ignore
  taps that come through another app's window.
- **The certificate is renewed every 90 days, with your consent.** Your consent to
  inspection already lasted 90 days; the certificate now lasts as long (97 days) and is
  replaced when you renew consent, in the same step: Android asks you to install the new
  one. The old one stops working at once. This limits what a stolen key could ever be
  worth. On phones an organisation manages, the certificate is replaced two weeks before
  its end, and where CoreLock manages the phone the new certificate replaces the old one
  automatically.
- **New name: "CoreLock HTTPS Inspection CA".** After updating, CoreLock makes a new
  certificate once. **If you used inspection in 1.0.0, install the new certificate:**
  Home shows a card with an "Install certificate" button until you do. Then remove
  "PrivacyGuard Enterprise CA" from Android's list of user certificates: it no longer
  works.
- **More is hidden before anything is stored or sent.** Headers and text bodies are
  redacted, values in web addresses and forms are replaced by `[REDACTED]`, and content
  that is not text is kept only as its type and size. The SIEM always receives the
  redacted copy.

## Also fixed and improved

- **Security fixes.** Internal security reviews led to fixes in HTTPS inspection, in the
  VPN engine and in the app's screens: stricter checks that consent was given before
  anything is captured, safer handling of captured content, more robust rule updates,
  and faster display of large captured bodies. We publish no further detail, so as not
  to help anyone attack phones that have not updated yet.
- **Kill switch shows the real state.** The app no longer shows a switch for something
  only Android controls. Settings › Kill switch shows whether Android's "Always-on VPN"
  and "Block connections without VPN" are on, with a button to Android's VPN settings.
  The separate "VPN dropped" alert was removed: it could not warn you reliably once
  protection had already stopped.
- **Corrected proxy texts.** The two-proxy screens now say plainly that SOCKS5 is not
  encrypted: server 1 can see your IP address, every destination and your server 2
  login, while websites see server 2's address. The content of HTTPS connections stays
  encrypted.
- **Clearer texts about limits.** The app says that per-app rules can occasionally apply
  to the wrong app on Android 7 to 9, and what is and is not removed from HTTPS
  inspection events sent to an organisation's SIEM server.
- **No more false "Signature mismatch".** 1.0.0 could report its own genuine signature as
  a mismatch in Settings › Advanced › Diagnostics and then refused to export or import
  rules, settings and history, or to clear history. The check now accepts the
  fingerprint in any common format, and a release APK can no longer be built without a
  valid one.
- **Wi-Fi counts as Wi-Fi again while protection is on.** On Android 10 and newer, 1.0.0
  made Android treat every network as mobile data while protection ran, so apps that
  wait for Wi-Fi (backups, photo uploads, app updates) did not start. Now the network
  behind CoreLock decides: Wi-Fi is unmetered, mobile data stays metered.
- **Screens update by themselves** instead of re-reading the database every few seconds,
  and errors are shown instead of hidden.
- Stored names with uncommon capital letters are matched the same way they are looked up.
- Failures to write the start-up list cache are now logged instead of hidden.
- **Documentation.** The README, the blocking policy and the technical description were
  updated to match the app.

## What leaves the phone

Unchanged from 1.0.0, except when blocklists are downloaded:

- Your DNS lookups, to the resolver you choose (Cloudflare 1.1.1.1 by default;
  encrypted to cloudflare-dns.com, dns.google or dns.quad9.net if you turn on DoH).
- Blocklist downloads: raw.githubusercontent.com (cdn.jsdelivr.net if GitHub can't be
  reached) and small.oisd.nl. The first download happens at first start, on any network.
  After that, weekly on Wi-Fi or another unmetered network; on mobile data only once a
  list has had no successful download for 14 days. A list that fails is retried later.
  "Refresh now" (Settings › Blocklists) downloads them at any time. All lists together
  are about 4 MB.
- The Exodus tracker list, at most weekly: reports.exodus-privacy.eu.org.
- Only if you turn them on: Certificate Transparency checks (crt.sh) and the public-IP
  check in Diagnostics (api.ipify.org).
- Only on a company-managed phone, if the administrator turns it on: redacted inspection
  events to the organisation's own SIEM server.

The export file of rules and settings is written only where you choose; CoreLock sends
it nowhere. The app does not check for updates itself. Settings › About shows your
version and opens the releases page.

## How to update from 1.0.0

1. On the phone, download `CoreLock-1.2.0.apk` from this release.
2. Before installing, check the signing certificate and the checksum (below).
3. Open the APK. Android asks to allow installs from the app you used (browser or file
   manager): allow it for this install, then you can turn it off again.
4. Android installs 1.2.0 over 1.0.0 (PrivacyGuard) as an update. Your rules, settings
   and history stay; afterwards the app is called CoreLock and opens on the new Home.
   Android refuses an APK signed with a different key.
5. Turn protection back on if it is off. **The first start after the update takes a few
   seconds longer than usual** (see Known limitations): CoreLock reads the lists 1.0.0
   kept once, checks them like a download, and lets nothing through until then.
6. **If you used HTTPS inspection,** install the new certificate from the card on Home,
   then remove "PrivacyGuard Enterprise CA" in Android's settings.

**New install:** open CoreLock and follow the setup. Android asks once to allow the VPN.
Recommended: in Android's VPN settings turn on **Always-on VPN** for CoreLock (and
optionally "Block connections without VPN"), and in Settings › Advanced › Diagnostics
check **Battery** and **Private DNS**.

## Verify the download

Only install the APK if its signing certificate matches this fingerprint. It is the same
key as 1.0.0.

```
Signing certificate SHA-256: caa82092caf86f9ce7d94f5b7c4a4476a3eb1a5bfa238f7b1d18cd85809bc430
APK file SHA-256:            299663f8e7119656630572c3e67d7c22b1a7153b0bebb19de4a9c3acca10b26e
APK file size:               12,234,900 bytes (11.7 MiB)
```

On a computer with the Android SDK build-tools:

```bash
apksigner verify --print-certs CoreLock-1.2.0.apk
# "Signer #1 certificate SHA-256 digest:" must equal the fingerprint above
sha256sum -c SHA256SUMS.txt                       # with SHA256SUMS.txt from this release
certutil -hashfile CoreLock-1.2.0.apk SHA256      # Windows
```

(`keytool -printcert -jarfile` does not work here: the APK is signed with APK Signature
Scheme v2 only.) After installing, Settings › Advanced › Diagnostics › App integrity also
shows whether the running app's signature is the expected one.

An automatic check on this release confirmed the APK is signed with this key.

## Known limitations

- **The first start after the update takes longer.** CoreLock keeps a quick-start copy of
  the lists for every level. 1.0.0 kept its copy in an older form, which the first start
  after the update reads once and checks like a downloaded list. Until then nothing is
  let through, so the internet waits for a few seconds (about 8 seconds on a test
  emulator, with a test build; it can be longer on a slow phone). Later starts, also after
  changing the level while protection was off, took about 2 seconds there. Without any
  usable copy CoreLock reads the lists from its encrypted database first (about 10
  seconds on the same emulator).
- **Per-app rules may miss at DNS level.** Android sends most lookups through its own
  resolver, so the app behind a lookup is often not known; per-app Allow rules and
  ad-SDK exceptions then fall back to the every-app decision.
- **Per-app rules on Android 7 to 9.** On these versions a per-app rule can occasionally
  apply to the wrong app. Android 10 and newer are not affected.
- **A new rule may take a moment for a name you just visited.** Android keeps recent DNS
  answers for a short time, so a site opened seconds before you block it can still load
  until that answer expires.
- **Private DNS in hostname mode must be turned off.** With a hostname set ("strict"),
  every lookup fails while the VPN runs: Android then uses only that encrypted server,
  which CoreLock blocks so the filter can't be skipped. Use "Off" or "Automatic". The
  app warns you on Home and in Diagnostics.
- **Chrome's "Use secure DNS" must stay on automatic.** With a specific provider chosen
  (for example Cloudflare), Chrome sends lookups only to that encrypted server, which
  CoreLock blocks, and pages fail to load. In Chrome, open Settings › Privacy and
  security › Use secure DNS and choose "Use your current service provider".
- **Some networks lose many lookups to the default DNS server.** CoreLock asks
  Cloudflare (1.1.1.1, then 1.0.0.1) and, by design, no other company. On some internet
  providers many of these lookups get lost, and pages load slowly or fail while
  protection is on, but work with it off. If that happens, open Settings › Advanced ›
  Network › DNS server and choose another server (Google or Quad9). A better fallback is
  planned for a later version.
- **QUIC at Strict.** QUIC is blocked by default at Strict, and unreadable QUIC is
  dropped at Standard and Strict. Apps fall back to TCP, which can make some connections
  a little slower to start.
- **Encrypted Client Hello:** the new setting works only at Strict, only for lookups that
  go through CoreLock, and not for keys a browser cached before you turned it on.
- **Intranet names and IP addresses in the TLS start** pass at Standard and Strict (with
  an alert); they can't hide another name.
- **HTTPS inspection reaches few apps.** Most apps on Android 7 and newer ignore
  user-installed certificates or pin their own, so their content stays encrypted and
  only metadata is shown.
- **No Wi-Fi-only or mobile-only rules.** Rules apply on every network.
- **Blocklists are held in memory** while protection runs (Strict uses the most), which
  counts toward the app's memory use, though far less than in 1.0.0.
- **Pause needs "Alarms & reminders"** on Android 12 and newer, and is not offered while
  Android's Always-on VPN is on (Android would restart the VPN at once).
- **Always-on VPN is recommended.** CoreLock restarts itself after a reboot or a crash,
  but without Always-on Android or the phone maker's battery saver may still stop it in
  the background, and until it is back traffic goes out unfiltered. With "Block
  connections without VPN" nothing leaves unprotected.
- **In-app ads in YouTube, Instagram, Snapchat, TikTok and Facebook can't be blocked
  without root.** They come from the same servers as the content, so blocking them by
  name would break the app. What CoreLock blocks, and what it can't, is explained in
  [docs/BLOCKING_POLICY.md](https://github.com/NAJJAT/CoreLock/blob/main/docs/BLOCKING_POLICY.md).
- French and Dutch texts are machine-translated; corrections are welcome through an issue.
