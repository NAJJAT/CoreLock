# 0009. Content filtering in browsers through HTTPS inspection

- **Status:** Proposed
- **Date:** 2026-10-28
- **Commits:** none yet
- **Depends on:** ADR 0007 (rule parser), ADR 0006 (inspection ships to every user).
- **Needs a security review before any code is written** (CLAUDE.md §4.2, §14.2).

## Context

CoreLock decides on host names only (ADR 0002). It cannot block a path such as
`example.com/ads/banner.js`, hide an empty ad box, or remove tracking parameters from a
link, because it never sees URLs or pages. Content blockers do that inside the browser,
or by decrypting HTTPS.

CoreLock already has HTTPS inspection (`app/src/enterprise/.../vpn/mitm/`), shipped to
every user since ADR 0006. What it does today:

- It is off by default and needs both the switch and a recorded consent
  (`TlsInterceptionFactory.kt:36`, `MitmTlsInterception.kt:93-96`). The organisation's
  MDM can give consent (`ManagedConfigurationProvider.kt:62-63`). The VPN notification
  and the dashboard show when it is on (`TlsInterceptionFactory.kt:38-44`,
  `PrivacyVpnService.kt:283-286`).
- The name decision happens **before** inspection, so decrypting a connection never
  lets a blocked name through (`TcpForwarder.kt:405-424`).
- It skips CoreLock's own traffic, pinned apps and domains, a bypass list, and domains
  whose handshake failed earlier (`PinningDetector.kt:110-121`, `:134`, `:142-145`).
- It applies to **every app** that is not on those lists; it is not limited to
  browsers (`MitmTlsInterception.kt:93-107`).
- The relay passes bytes through unchanged, except that it asks servers for
  gzip/deflate so bodies can be shown (`MitmEngine.kt:299-333`, rewrite at `:316`). It
  records payloads; it never blocks or changes a request.
- The CA key is RSA-2048 in the Android Keystore, not exportable, valid 365 days, and
  may only sign leaf certificates (`CaManager.kt:63`, `:245-293`, `pathLen 0` at
  `:286-287`). It has no name constraints. It is deleted when the user switches
  inspection off (`MitmScreen.kt:448-451`) and, if MDM switched it off, at the next
  retention run (`FlavorRetention.kt:22-24`); deletion is `CaManager.kt:218-224`,
  `:344-347`.

**Who trusts the CA.** Since Android 7, apps trust user-installed CAs only if they opt
in (network security config) or target API 23 or lower. Firefox for Android trusts
them only after the user enables "Use third-party CA certificates" in its hidden
settings. Whether Chrome and Chromium browsers (Samsung Internet, Brave, Edge) accept
a user CA on current versions is **not verified** and must be checked on a device
before any decision depends on it.

**What it cannot do.** It cannot remove ads inside YouTube, Instagram, Snapchat,
TikTok or Facebook: those apps don't trust user CAs and pin their certificates.
AdGuard Support confirmed that blocking them needs root (AdGuard Support, written
reply, 2026-10-28). This design does not change that.

## Decision

Design only. Nothing is built until the security review below is done and the owner
accepts this record.

### 1. Browsers only

- A content rule runs only for connections owned by a package on a small, reviewed
  **browser list** in the repository (for example Firefox, Fennec, Mull, and Chromium
  browsers once verified). All other apps keep today's behaviour: inspected for
  metadata if inspection is on, never changed.
- At run time a browser is used only after a successful handshake with our
  certificate. If the handshake fails (the browser doesn't trust the CA), the
  existing failure path marks the domain pinned and passes it through
  (`PinningDetector.kt:142-145`). The UI says "This browser does not trust CoreLock's
  certificate" with steps for Firefox.
- No new permission. The package comes from the UID lookup CoreLock already does.

### 2. Never-decrypt list (mandatory)

Some traffic must never be decrypted, even in a browser and even with consent:
banking and payment, health, government and tax, password managers, and anything
pinned. Today `BYPASS_DOMAINS` and `PINNED_DOMAINS` cover infrastructure and a few apps
(PayPal is the only payment entry); there is no banking, health or government list.

- A **category list in the repository** (`never_decrypt.txt`, data, not code), with a
  source for each entry. It changes **only through reviewed commits** (owner's
  decision, 2026-10-28); it is never downloaded or updated at run time.
- Category rules where a name is unambiguous: government suffixes (`gov`, `gov.uk`,
  `gouv.fr`, `overheid.nl`, `gov.sa`, …), checked against the Public Suffix List.
- Users can add domains; they can never remove a built-in entry.
- Checked before `shouldIntercept` returns true, together with the pinning checks,
  so a never-decrypt name is passed through untouched.
- Missing entries are the main risk; the list is a best effort, and the UI says so.

### 3. Rule engine placement and order

The engine sits in the relay after `MitmEngine` decrypted the request, in the
`OUTBOUND` direction (`MitmEngine.kt:316`), and reuses the ADR 0007 parser, extended
with the URL-rule subset.

Order of work, each step shipped and measured before the next:

1. **Network rules on the request** (`||example.com/ads/`, `$third-party`,
   `$domain=`): parse the HTTP/1.1 request line and `Host` with hard limits (header
   block ≤ 16 KB, line ≤ 8 KB); on a match, answer locally with an empty response and
   close the stream. **The first version offers only `http/1.1` in ALPN for inspected
   browser connections** (owner's decision, 2026-10-28), so every inspected request
   can be read; the cost is losing HTTP/2 multiplexing on those connections. HTTP/2
   (frame parser and HPACK) is a later, separate step.
2. **`$removeparam`** (below).
3. **Cosmetic rules** (`##selector`): needs the response, decompressed, with an
   injected `<style>` in HTML. It changes the page, needs a strict size cap and
   charset handling, and breaks with Content-Security-Policy nonces. Last, and only if
   step 1 proved stable.

**Out of scope:**

- **Scriptlets** (`#%#//scriptlet(...)`, `#$#`): they inject code into pages. A
  wrong or hostile rule from a list runs JavaScript on every page, including logged-in
  sessions. CoreLock would become a code-execution channel, and lists are data, never
  code (CLAUDE.md §5.6).
- **`$replace`**: rewrites response bodies with regular expressions. It costs CPU on
  every response, breaks integrity checks, and a hostile rule can change what a page
  says. The same reason as scriptlets.

### 4. `$removeparam`: removing tracking parameters (B7)

A separate rule type. It deletes named query parameters (`utm_source`, `fbclid`,
`gclid`, …) from request URLs before they are sent.

- **Scope:** browsers only, top-level page requests only (`Sec-Fetch-Mode:
  navigate`), parameters named in the rule. No regular-expression parameters in the
  first version.
- **How:** rewrite the request line in place and send it upstream. If the request
  line cannot be parsed safely, leave it unchanged.
- **Risks:** some sites need the parameter (sign-in links, payment returns, signed
  URLs where the signature covers the query: the request then fails); the page's
  own JavaScript can still read the original link before the request; a redirect
  chain can re-add the parameter. The user can turn a rule off per site, and the
  removed parameters are shown in the payload inspector.
- **Not done:** removing parameters from links inside pages, or in non-browser apps.

### 5. Performance budget

References (CLAUDE.md §6.2; release build on the emulator, release verification,
October 2026): **SNI check ≈ 3.46 µs per connection**, **QUIC gate ≈ 9.3 µs per new
flow**. Request rules run per HTTP request, not per packet, and only for inspected
browser connections, which already pay for TLS decryption and re-encryption (far more
than the rule check). Budget: **≤ 20 µs per request for network rules** at the
default list size (about twice the QUIC gate's per-flow cost, and paid per request
only on inspected browser connections), and no allocation per packet. Decrypted
relays must not slow the packet thread: the relay runs on its own threads today
(`MitmEngine`). Measure before and after on the same setup, per CLAUDE.md §6.2.

### 6. Threat model changes (CLAUDE.md §3)

- **New asset:** content rule lists that can change requests. A poisoned list could
  break sites or, with `$removeparam`, change what is sent. Same supply-chain rules as
  blocklists; no code-like rules.
- **CA key, today:** in the Keystore, not exportable, so another app or a backup
  cannot copy it. Code running inside CoreLock's process can still use it to sign
  certificates. If it ever leaked, an attacker on the same network could decrypt that
  phone's HTTPS for every app that trusts user CAs, for up to 365 days.
- **CA lifetime: 90 days with rotation** (owner's decision, 2026-10-28), instead of
  today's 365 days (`CaManager.kt:63`). Before expiry a new CA is created, the user is
  prompted to install it and to remove the old one, and the old key is deleted. This
  limits a leaked key to at most 90 days.
- **Further hardening, decided in the review:** name constraints are not usable (the
  CA must sign any host); delete the key immediately when MDM switches inspection
  off, not at the next retention run; show the CA fingerprint so the user can check it.
- **New attacker capability:** a hostile page cannot reach the CA key, but a
  hostile list could try to make CoreLock rewrite traffic. That is why rules are data
  with a fixed small grammar, and why scriptlets and `$replace` are out.

### 7. Consent and UX

- Off by default. Content filtering needs inspection on, and inspection needs the
  existing consent dialog (`MitmScreen.kt:179-180`); there is no other way to switch
  it on.
- A separate switch, "Filter content in supported browsers", off by default, with
  plain text: which browsers, what changes, the never-decrypt list, and that it does
  nothing for in-app ads.
- The notification and dashboard indicator already exist; they add "content
  filtering on" when it is.

## Consequences

- Browser users can get path-level blocking and cleaner links.
- CoreLock starts **changing** traffic, not only reading it. That is a bigger
  responsibility than inspection: every bug is a broken site, and every parser is
  attack surface on decrypted data.
- More code in the most sensitive part of the app; more tests (malformed HTTP,
  oversized headers, h2 later), more review.
- Reach is small: few browsers trust user CAs without hidden settings, and in-app ads
  stay out of reach.

## Alternatives considered

- **Inspect and filter all apps.** Breaks pinned apps, reaches few others, and
  changes traffic users never meant to touch.
- **A browser extension or a separate browser.** Outside this app, and Chrome on
  Android has no extensions.
- **Scriptlets and `$replace` too.** Rejected above: code execution and body rewrites
  from downloaded data.
- **Do nothing.** Keeps CoreLock simple; content filtering stays the job of browsers
  with built-in blockers (Firefox with uBlock Origin, Brave). A valid choice, and the
  default until this record is accepted.

## Open questions

- Open: do Chrome and other Chromium browsers on current Android trust a user CA?
  Device test after 1.0 (owner, 2026-10-28).
- **Resolved (owner, 2026-10-28):** HTTP/1.1-only ALPN for inspected connections is
  acceptable for the first version (Decision, step 3.1).
- **Resolved (owner, 2026-10-28):** the never-decrypt list lives in the repository and
  changes only through reviewed commits (Decision, step 2).
- **Resolved (owner, 2026-10-28):** 90-day CA lifetime with rotation (Decision,
  step 6).
- **Resolved (owner, 2026-10-28):** the 3.46 µs (SNI) and 9.3 µs (QUIC) figures come
  from the release verification on the emulator, release build, October 2026; now
  recorded in CLAUDE.md §6.2 (Decision, step 5).
