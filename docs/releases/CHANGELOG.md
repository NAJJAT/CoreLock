# Changelog

One short entry per version, newest first. The release notes have the full story.
Download from [GitHub Releases](https://github.com/NAJJAT/CoreLock/releases); every
release is signed with the key whose fingerprint is in the [README](../../README.md#security).
Dates are those of the tagged commit.

## 1.2.1 (in preparation, not published)

A safety and reliability update. A Block rule now also covers subdomains (ADR 0017);
"Protection stopped" appears at once after a crash and Home warns while notifications
are off; unencrypted websites are checked by site name; open connections are checked
again when rules or lists change; fixed limits on the memory used to track connections;
security fixes in HTTPS inspection and SIEM sending; new counters in Diagnostics.
[Release notes (draft)](RELEASE_NOTES_1.2.1.md)

## 1.2.0 (2026-11-11, tag `v1.2.0`)

The app is now called CoreLock. A simpler app with four tabs (Home, Apps, Activity,
Settings) and a three-step setup; allow what was blocked from where it was blocked, or
pause protection for 5 minutes. Stronger protection: connections whose server name
can't be read are stopped at Standard and Strict, blocklists take far less memory, a
copy of the malware list ships in the app, and the HTTPS-inspection certificate is
renewed every 90 days. Updates 1.0.0 directly.
[Release notes](RELEASE_NOTES_1.2.0.md)

## 1.1.0 and 1.0.1

Never published. Their work shipped in 1.2.0.

## 1.0.0 (2026-10-26, tag `v1.0.0`)

First public release, still under the name PrivacyGuard. Local VPN with four blocking
layers (DNS, DNS-bypass prevention, server-name filtering, per-app firewall), three
protection levels, per-app traffic history, TLS and DNS anomaly detection, and optional
HTTPS inspection with consent.
[Release notes](RELEASE_NOTES_1.0.md)
