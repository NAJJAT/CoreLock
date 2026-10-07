# Security Policy

CoreLock (PrivacyGuard) sits on every network connection of the device it runs on,
and the enterprise build can decrypt HTTPS traffic. Security reports get priority.

## Reporting a vulnerability

Please **do not open a public issue** for a security problem.

Report it privately through GitHub's
[private vulnerability reporting](https://github.com/NAJJAT/CoreLock/security/advisories/new)
("Security" tab → "Report a vulnerability").

Include what you can of:

- the affected build (consumer or enterprise, version, Android version)
- steps to reproduce, or a proof of concept
- the impact you expect (for example: traffic leak, code execution, data exposure)

You can expect an acknowledgement within 5 working days and a status update at
least every 14 days until the issue is resolved. Once a fix ships, we credit
reporters who want to be credited.

## Supported versions

Only the latest release receives security fixes.

## Scope

In scope:

- the Android app in `app/` (both flavors) and the Rust core in `rust/`
- anything that lets traffic bypass the tunnel, filter or kill switch
- exposure of captured data, the database, keys or the MITM CA
- the enterprise MITM engine, SIEM shipping and MDM configuration

Out of scope:

- `testapp/`, a debug-only harness that is never published
- findings that need a rooted device or an already-compromised Android OS
- apps that are passed through without decryption because they pin certificates
  (this is intended behaviour)
