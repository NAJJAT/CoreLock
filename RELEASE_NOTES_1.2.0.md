# CoreLock 1.2.0

<!-- DRAFT prepared on branch hardening-1.2 (2026-11-05). Before publishing:
     - fill in <APK_SHA256> and the APK size;
     - remove this comment. -->

An update to 1.1.0 that makes protection harder to get around and lighter on memory. Install
it over 1.1.0: your rules, settings and history stay. One APK for Android 7.0 and newer
(ARM phones, 64- and 32-bit), published only on GitHub Releases.

## What's new

- **Protected from the first start.** A copy of the HaGeZi malware and phishing list now
  ships inside the app, so malware and phishing sites are blocked even before the first
  list download. It is replaced by the live list as soon as that downloads.
- **Less memory for blocklists.** Lists are now stored in a compact form, about a tenth of
  the memory they used before. Strict profits most.
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

## HTTPS inspection (only if you use it)

- **The certificate is renewed every 90 days, with your consent.** Your consent to
  inspection already lasted 90 days; the certificate now lasts as long (97 days) and is
  replaced when you renew consent, in the same step: Android asks you to install the new
  one. The old one stops working at once. This limits what a stolen key could ever be
  worth. On phones an organisation manages, the certificate is replaced two weeks before
  its end, and where CoreLock manages the phone the new certificate replaces the old one
  automatically.
- **New name: "CoreLock HTTPS Inspection CA".** After updating, CoreLock makes a new
  certificate once. **If you used inspection in 1.1, install the new certificate:** Home
  shows a card with an "Install certificate" button until you do. Then remove "PrivacyGuard Enterprise CA" from
  Android's list of user certificates: it no longer works.
- **More is hidden before anything is stored or sent.** Values in web addresses and forms
  are replaced by `[REDACTED]`, and content that is not text is kept only as its type
  and size. The SIEM always receives the redacted copy.

## What's fixed

- Stored names with uncommon capital letters are matched the same way they are looked up.
- Failures to write the start-up list cache are now logged instead of hidden.

## How to update

1. On the phone, download `CoreLock-1.2.0.apk` from this release.
2. Before installing, check the signing certificate and the checksum (below).
3. Open the APK and install it over 1.1.0. Your rules, settings and history stay.

## Verify the download

Only install the APK if its signing certificate matches this fingerprint. It is the
same key as 1.0.0 and 1.1.0.

```
Signing certificate SHA-256: caa82092caf86f9ce7d94f5b7c4a4476a3eb1a5bfa238f7b1d18cd85809bc430
APK file SHA-256:            <APK_SHA256>
```

```bash
apksigner verify --print-certs CoreLock-1.2.0.apk
# "Signer #1 certificate SHA-256 digest:" must equal the fingerprint above
sha256sum -c SHA256SUMS.txt                       # with SHA256SUMS.txt from this release
certutil -hashfile CoreLock-1.2.0.apk SHA256      # Windows
```

An automatic check on this release confirmed the APK is signed with this key.

## Known limitations

The limits of 1.1.0 still apply, except that malware protection no longer needs a first
download. In addition:

- **Encrypted Client Hello:** the new setting works only at Strict, only for lookups
  that go through CoreLock, and not for keys a browser cached before you turned it on.
- **Intranet names and IP addresses in the TLS start** pass at Standard and Strict
  (with an alert), as they did before; they can't hide another name.
- **In-app ads in YouTube, Instagram, Snapchat, TikTok and Facebook can't be
  blocked without root.** See
  [docs/BLOCKING_POLICY.md](https://github.com/NAJJAT/CoreLock/blob/main/docs/BLOCKING_POLICY.md).
