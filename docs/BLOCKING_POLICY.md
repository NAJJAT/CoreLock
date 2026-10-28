# Blocking policy

What CoreLock blocks, what it leaves alone on purpose, what it cannot block, and how
to report a mistake. On the phone the app is called **PrivacyGuard**.

This page describes version 1.0.0. For the technical detail behind it, see
[HOW_IT_WORKS.md](HOW_IT_WORKS.md).

---

## 1. Purpose

CoreLock is an open-source privacy firewall for Android. It runs a local VPN on the
phone, shows which app talks to which server, and blocks connections by name: malware
and phishing sites, and, at the higher protection levels, ads and trackers that come
from their own, separate ad servers. It does not read or change the content of web
pages or apps. This policy explains, in plain words, what that means in practice, so
you know what to expect before you rely on it.

## 2. How CoreLock blocks

CoreLock decides by **server name** (for example `ads.example.com`), never by the page
or file being loaded. It checks names at four points, and all of them use the same
decision, so a name blocked at one point cannot slip through another:

1. **DNS.** When an app looks up a name, CoreLock answers "this name does not exist"
   if the name is blocked. No connection is made. If the answer is an alias (CNAME)
   that points to a blocked name, that is blocked too.
2. **No way around the filter's DNS.** Some apps use their own encrypted DNS to skip
   the phone's DNS. CoreLock drops DNS over TLS (port 853) and stops known encrypted-DNS
   (DoH) servers, from a built-in list plus HaGeZi's DoH lists. The app then falls
   back to the DNS that CoreLock sees.
3. **Server name of each connection (SNI).** A secure connection starts by naming the
   server it wants. CoreLock reads that name, on normal connections (TCP) and on QUIC
   (HTTP/3, over UDP), and resets the connection if the name is blocked. This catches
   apps that reach a blocked server without asking DNS first.
   - At Standard and Strict, a new QUIC connection is held until its server name can
     be read. QUIC that cannot be read is dropped; the app then retries over TCP,
     where the name can be checked.
   - Other traffic on the same port (video calls, VPNs) is never touched.
4. **Per-app rules.** You can block an app completely, or only while it is in the
   background.

## 3. What each protection level blocks

You choose the level in Settings. **Standard** is the default. The lists are
downloaded from their authors and refreshed about once a week (on Wi-Fi or another
unmetered network); the sizes below were counted on 2026-10-28 and change with every
update.

| Level | What it blocks | Lists used |
|---|---|---|
| **Minimal** | Malware, phishing and scam sites only. Ads and trackers load normally. | HaGeZi Threat Intelligence Feeds *mini* (about 213,000 names) |
| **Standard** (default) | Minimal, plus ads and trackers | + HaGeZi *Pro mini* (about 88,000 names), plus about 60 ad and analytics servers built into the app (including Snapchat's separate ad and analytics servers). About 287,000 names in total. |
| **Strict** | Standard, plus telemetry: diagnostics sent by operating systems and vendors | The full HaGeZi *Pro* (about 211,000 names) instead of Pro mini, + OISD *small* (about 60,000 names), plus 10 built-in telemetry servers. About 342,000 names in total. QUIC is blocked by default. |

At every level:

- **Your own rules** apply.
- **Ad-SDK blocking** (on by default) stops the servers of known ad and attribution
  kits (for example AppLovin, Unity Ads, AppsFlyer, Adjust) inside other apps.
- **Encrypted-DNS servers** are stopped, so apps cannot skip the filter.
- A list or category you switched off in **Settings › Ads & Trackers** stays off.

Things to know about Strict:

- The telemetry list includes `safebrowsing.googleapis.com`, which Chrome and other
  Google apps use to warn about dangerous sites. At Strict those warnings may stop
  working. Malware and phishing blocking by CoreLock itself stays on.
- Blocking telemetry can break features that depend on it. That is why Strict is not
  the default.

A blocklist entry blocks the name **and every name under it**: blocking
`ads.example.com` also blocks `x.ads.example.com`.

## 4. What CoreLock deliberately does not block, and why

| Not blocked | Why |
|---|---|
| **Login servers** (`graph.facebook.com`, `m.facebook.com`, `www.facebook.com`, `accounts.google.com`) | Blocking them breaks "Continue with Facebook" and Google sign-in. They stay open even if a downloaded ad list contains them. This also means Meta app events, which use the same server as Facebook login, are not blocked. |
| **Push notification servers** (`firebaseinstallations.googleapis.com`, `fcm.googleapis.com`, `mtalk.google.com`) | Without them apps stop receiving notifications. |
| **Deep-link servers** (`branch.io`, `api2.branch.io`, `app.link`, `onelink.me`) | They open links inside apps; blocking them breaks shared links. |
| **Ad-SDK blocking inside the kit maker's own apps** (for example Google's ad kit inside Google apps, Meta's inside Facebook or Instagram) | There, the kit is part of the app itself; blocking it breaks the app. Ad-SDK blocking only applies to *other* apps. The blocklists of your level still apply everywhere. |
| **Ads and trackers at Minimal** | Minimal is for people who want malware and phishing protection only. |
| **Telemetry at Minimal and Standard** | Blocking OS and vendor diagnostics can break features; it is opt-in through Strict. |
| **Non-QUIC traffic on UDP port 443** (video calls, WireGuard and other VPNs) | Blocking it would break calls and VPNs. Only real QUIC is held or blocked. |
| **Shared server addresses on encrypted-DNS lists** | Many encrypted-DNS addresses also serve ordinary websites. A connection to such an address is only stopped when it names an encrypted-DNS server, or names no server at all. |
| **Servers you allowed** | Your Allow rule beats the ad, tracker and telemetry lists (see section 7). |

Exceptions to these exceptions: the login, push and deep-link servers above are only
shielded from the ad, tracker and telemetry lists. Your own Block rule still blocks
them, and so does the malware and phishing list.

## 5. Limits of domain-level blocking

CoreLock sees the name of the server, not what is asked of it. That has three
consequences:

- **It cannot block one address, file or element inside a page.** It cannot block
  `example.com/ads/banner.js` and keep the rest of `example.com`. It cannot hide a
  banner, a "sponsored" post or an empty ad frame.
- **Blocking a name affects every app and site that uses it.** If a news site and its
  app both load from `cdn.example.com`, blocking that name breaks both.
- **When ads and content come from the same server, it is all or nothing.** Either
  both are blocked (and the app or site breaks) or neither is. CoreLock chooses not to
  break the app.

## 6. What CoreLock cannot block

- **Ads inside YouTube, Instagram, Snapchat, TikTok and Facebook.** These apps send
  their ads from the same servers as the videos, photos and posts. Blocking those
  servers would break the app. CoreLock does block known *separate* ad and analytics
  servers (for example Snapchat's own ad servers at Standard and Strict), but the ads
  most people see in these apps do not come from those. Without root, no blocker can
  separate these ads from the content: removing them needs the app's encrypted traffic
  to be opened and filtered, and these apps only trust certificates in the phone's
  system store, which needs root. AdGuard's support team confirmed in writing
  (2026-10-28) that this applies to every blocker without root, including their own.
- **Sites that use Encrypted Client Hello (ECH).** ECH hides the real server name.
  CoreLock only sees the outer, shared name and cannot tell sites behind it apart.
- **Ads placed in the page by scripts, or shown with page styling** (cosmetic ads).
  This needs a content blocker inside the browser.
- **Anything that needs the content of the connection**: tracking parameters in links,
  ads served from the site's own domain, pop-ups drawn by a page.
- **Apps that connect to a fixed IP address without naming a server.** These can only
  be stopped by blocking the app, or by an IP rule.
- **Traffic while protection is off.** If the tunnel stops (for example after a
  crash), traffic is not filtered unless Android's *Always-on VPN* with *Block
  connections without VPN* is on.

If you want fewer ads on websites, use a browser with a content blocker, for example
Firefox with uBlock Origin, alongside CoreLock.

## 7. Quality principles

- **Don't break things.** A list that breaks common sites or apps is worse than a
  slightly shorter list. That is why Standard uses the smaller *Pro mini* list, why
  telemetry is Strict-only, and why login, push and deep-link servers are kept open
  (section 4).
- **Allow as narrowly as possible.** When something breaks, allow the one name that
  is needed (`api.example.com`), not the whole domain (`*.example.com`). A rule for a
  plain name matches only that exact name; `*.example.com` matches `example.com` and
  every name under it.
- **One decision, in a fixed order.** For every name, at DNS and at the connection:
  1. Your **Block** rule: blocked.
  2. **Malware and phishing list**: blocked, unless your Allow rule was created after
     you confirmed the warning the app shows for these names.
  3. Your **Allow** rule: allowed.
  4. **Ad, tracker and telemetry lists, and ad-SDK blocking**: blocked.
  5. Otherwise: allowed.
- **When two of your rules conflict, the more specific one wins**: a rule for one app
  before a rule for every app, an exact name before a wildcard, a longer wildcard
  before a shorter one. **Between two equally specific rules, Block wins.**
- **Every block says why.** In the connection details, "Stopped by" shows which check
  stopped it.

## 8. How to allow-list a domain or an app

### Allow a domain

1. Find the name that is blocked: on **Home**, tap the *Blocked (24 h)* number to see
   the blocked lookups and connections, or open **Traffic** and tap a blocked
   connection to read its server name and "Stopped by".
2. Open the **Settings** tab.
3. Tap **Firewall Rules**.
4. Tap the **+** button.
5. Leave the type on **Domain** and type the name, for example `api.example.com`
   (or `*.example.com` for the domain and everything under it).
6. Tap **Allow** (the dialog starts on Block).
7. Tap **Add**.

The rule works at once, without restarting protection. If the name is on the malware
and phishing list, the app first shows a warning; the Allow only overrides that list
if you confirm it.

You can also add an Allow rule in **Settings › Ads & Trackers › Custom rules**.

### Allow an app

In version 1.0.0 an app cannot be taken out of the blocklists as a whole, and it
cannot be excluded from the VPN. An Allow rule for an app (Firewall Rules, type
**App**) only affects your own connection rules, for example an IP rule for every
app; it does not exempt the app from the ad, tracker or malware lists, and it does
not override a Block rule for the same app. Allow the specific names the app needs
instead (see above).

To remove a block you placed on an app: open **Apps**, tap the app, and turn off its
block.

## 9. How to report a problem

Please use one of the two forms on GitHub:

- **[Site or app broken](https://github.com/NAJJAT/CoreLock/issues/new?template=site_or_app_broken.yml)**:
  something stopped working because CoreLock blocked it.
- **[Ad or tracker not blocked](https://github.com/NAJJAT/CoreLock/issues/new?template=ad_or_tracker_not_blocked.yml)**:
  an ad or tracker got through. Please read section 6 first: ads inside YouTube,
  Instagram, Snapchat, TikTok and Facebook cannot be blocked.

The forms ask for the app or site, your protection level, Android and CoreLock
versions, and a few numbers from Settings › Diagnostics. **Never paste passwords,
personal data, or full logs or exports**: issues on GitHub are public.

Security vulnerabilities go through private reporting instead; see
[SECURITY.md](../SECURITY.md).

If the wrong block comes from a downloaded list, it may also need fixing upstream,
with the list's author (HaGeZi or OISD). We will tell you when that is the case.

## 10. No promise of complete blocking

CoreLock does not promise to block all ads or all trackers, and it never will. It
blocks what it can identify by server name, at the level you chose, and it tells you
what it stopped and why. Some ads and trackers will always get through, and the
sections above explain which ones.
