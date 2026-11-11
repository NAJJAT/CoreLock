# 0016. Fall back when the upstream DNS server doesn't answer

- **Status:** Proposed (for 1.3)
- **Date:** 2026-11-11
- **Commits:** none yet

## Context

CoreLock answers DNS itself and forwards allowed questions upstream over plain UDP.
The default upstream is Cloudflare, 1.1.1.1 (`DnsHandler.kt`, `DEFAULT_UPSTREAM_DNS`).
For a lookup, CoreLock asks the chosen server and then the same operator's second
address (`SECONDARY_ADDRESS`: 1.1.1.1 → 1.0.0.1), one after the other, each with a
3-second timeout (`upstreamTimeoutMs`, `DnsHandler.kt:46`). If both stay silent it
answers SERVFAIL (`DnsHandler.kt:226-228`). It never asks another company's server:
the comment at `DnsHandler.kt:219` says so on purpose, because that company would
then see every name without the user ever choosing it.

On the owner's phone test (2026-11-10, Samsung Note20, home ISP), 1.1.1.1 and 1.0.0.1
each lost about half of all queries. The PC on the same network saw the same loss, so
this is the ISP's path to Cloudflare, not CoreLock. If the losses are independent,
about one lookup in four failed on both addresses: the app waited 6 seconds, got
SERVFAIL, and Android's resolver then retried. Pages loaded slowly or not at all,
while the same lookups with protection off (the network's own resolver) were fine.

Some ISPs and home routers block, throttle or hijack 1.1.1.1. The user's only fix today
is to find Settings › Advanced › Network › DNS server and pick another server; nothing
in the app says that this is the cause. Diagnostics' "Upstream DNS server" check makes
one test lookup, which passes half the time on such a network.

With encrypted DNS (DoH) on, a failed lookup is answered with SERVFAIL and never falls
back to plain DNS (ADR 0012, `DnsHandler.kt:423`). That stays as it is: the user asked
for encryption, and a silent fallback would let an attacker force plain DNS by
blocking DoH.

## Decision (proposed)

In three steps, cheapest and most private first. Each can ship alone.

1. **Retry smarter, same provider (no privacy cost).** Ask both addresses of the chosen
   provider at once (or the second one after a short delay, about 500 ms, instead of
   after 3 s), take the first valid answer, and resend once on a short timeout before
   giving up. Same operator, same privacy terms, so nothing changes for the user's
   privacy. On a lossy path this turns most 6-second failures into answers within a
   second. It also frees the forwarding threads sooner (`MAX_FORWARD_THREADS = 32`).
2. **Show the cause.** Count upstream timeouts per server (counters, no names) and show
   them in Settings › Advanced › Diagnostics. When many lookups time out, say so on the
   DNS server screen and in Diagnostics: "Your network loses many lookups to this
   server. Choose another DNS server." The user decides; nothing is switched for them.
3. **Opt-in fallback to the network's own resolver.** A new setting under DNS server:
   "If this server doesn't answer, use the network's DNS". **Off by default.** When on,
   and only with plain DNS (never with DoH), a lookup that timed out on the chosen
   provider is asked again of the DNS server Android got from the real network (Wi-Fi
   or mobile, read from that network's `LinkProperties`, on a protected socket). The
   answer goes through the same blocking as every other answer (CNAME and HTTPS/SVCB
   checks, `NameBlockPolicy`), so the fallback changes who answers, never what is
   blocked.

A **second provider** (for example Quad9 when Cloudflare fails) is not added as an
automatic fallback. It is offered only as something the user chooses by name, in the
same setting as step 3 if the owner wants it.

## Privacy trade-off

| Option | Who sees the names that fail over | Notes |
|---|---|---|
| Today | Only the chosen provider | Lookups fail on lossy paths. |
| Step 1 | Only the chosen provider | No change to who sees what. |
| Step 3, network resolver | The ISP or the Wi-Fi owner (hotel, employer) for the names that timed out | The ISP already sees the IP addresses and, without ECH, the TLS server names of the connections that follow, so it learns little new. A hostile Wi-Fi can answer falsely; it can already drop or reset the traffic. CoreLock's blocking applies to its answers too. |
| Second provider | Another company the user didn't pick, for every name that fails over | Only with the user's explicit choice of that company. |

Any fallback is a leak the user must agree to; that's why step 3 is off by default and
is never used with DoH. The README network table needs no new host for step 3 (the
network's resolver is not a host we choose), but the README and
`docs/HOW_IT_WORKS.md` must say that, with the setting on, names can go to the
network's DNS. A second provider would be a new outbound host and must be added to the
network tables (`OutboundHostsDisclosureTest`).

## Alternatives

- **Do nothing.** Honest, but users on such networks get slow or broken browsing and
  no hint why. Many will uninstall instead of finding the DNS setting.
- **Automatic fallback to the network resolver, on by default.** Always works, but
  sends names to the ISP without the user knowing, and a network that drops traffic to
  Cloudflare on purpose then gets every name. Against the privacy promise (CLAUDE.md
  §1.3 spirit: the user decides who sees their names).
- **Automatic fallback to a second provider.** Sends names to a company the user never
  chose. Rejected for the same reason the current code rejects it.
- **Switch the default provider.** Any single provider can be lossy on some ISP; it
  moves the problem.
- **DoH by default.** Separate decision (ADR 0012); it doesn't help when the path to
  the provider is lossy, and it breaks captive portals.

## Consequences

- Step 1 touches only the forwarding code (not the packet thread). It needs tests for
  "first valid answer wins", "a reply from the wrong address is ignored" (as today,
  `DnsHandler.kt`, check on `recv.address`), and the thread count under loss.
- Step 3 adds a setting in four languages, a README and HOW_IT_WORKS paragraph, and a
  security review of where the network's resolver address comes from (only the real
  network's `LinkProperties`, never a value from a packet).
- Captive portals: step 3 helps there too (the portal's own DNS answers), which ADR
  0012 lists as a condition for revisiting DoH.
- To decide: whether step 3 should be offered at all, and the wording of the setting.
