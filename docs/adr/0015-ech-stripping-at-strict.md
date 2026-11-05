# 0015. Optionally remove ECH keys from DNS answers at Strict

- **Status:** Proposed (the default is a team decision; built off)
- **Date:** 2026-11-05
- **Commits:** none yet (implemented on branch `hardening-1.2`, not committed)
- **Backlog:** hardening plan task 1.4; CLAUDE.md §16.1 (ECH limit).

## Context

Encrypted Client Hello (ECH, RFC 9849, March 2026) encrypts the real server name in the
TLS ClientHello. Only an outer, shared name (often the CDN's) stays readable. Chrome,
Edge, Firefox and Brave use ECH by default. A browser gets the server's ECH key from the
`ech` parameter (SvcParamKey 5) of the site's HTTPS (type 65) or SVCB (type 64) DNS record,
and it accepts that record over plain DNS too, which here means through CoreLock's resolver.

For CoreLock this means layer 3 (SNI filtering, CLAUDE.md §5.2) sees only the outer name
for those connections: a tracker reached by ECH from a hardcoded address or a DNS cache
hit is not caught. Layer 1 (DNS) still decides the name the browser looks up.

A browser without an ECH key sends a normal ClientHello: the real name is readable again.
RFC 9849 has no "ECH required" signal a client must honour; a client without a key simply
doesn't use ECH.

## Decision

1. **A setting at Strict, off until the team decides** (Advanced › Network): "Read names
   behind Encrypted Client Hello". It applies only while the protection level is Strict;
   at Minimal and Standard it has no effect and the screen says so.
2. **When it is on**, every DNS answer to an HTTPS or SVCB query (`EchStripper`) loses
   the `ech` parameter from each HTTPS and SVCB record. Every other parameter stays
   (alpn, port, the IPv4 and IPv6 hints, and so on), and each record's RDATA length and the
   message's compression pointers are rewritten to match.
3. **A record that can't be parsed safely is removed**, not passed on with its key:
   parameters out of order, lengths beyond the record, or a `mandatory` list that names
   `ech` (removing the key would break that record anyway, RFC 9460 §8). A whole answer
   that can't be parsed safely becomes an empty answer (NOERROR, no records), so the
   browser falls back to A/AAAA records and a normal ClientHello.
4. **Only answers to HTTPS and SVCB queries are touched**: those are the records a browser
   reads the key from. Answers to every other query pass unchanged and cost one type check.
5. **Honest text**: "Lets CoreLock see the names of encrypted browser connections. Your
   network can see these names too." Removing the key undoes ECH's privacy benefit
   against the network for those sites; the user decides.

## The default: a team decision

| Default | For | Against |
|---|---|---|
| **Off** (built) | Keeps ECH's privacy against the Wi-Fi operator and the ISP. Nothing changes for users who don't open the setting. | At Strict, connections using ECH are filtered by their outer name only. |
| **On at Strict** | Strict promises the most filtering; layer 3 sees real names again. | Every Strict user loses ECH's privacy against the network, without asking for it. |

**Review this default when encrypted upstream DNS ships as a default** (ADR 0012). Today
plain DNS already shows the network every name CoreLock resolves, so removing ECH reveals
little that DNS hasn't. Once the upstream is encrypted, ECH would be the only thing still
hiding the name from the network, and removing it costs much more privacy. The same note
is in `EchStripper`.

## Alternatives considered

- **Drop HTTPS/SVCB answers altogether:** simpler, but also removes alpn and the address
  hints, so HTTP/3 discovery breaks for every site, not only for ECH.
- **Block connections that use ECH:** the outer name doesn't say whether it is a tracker,
  so this would block real sites; and it breaks users' browsing for a filtering gain.
- **Do nothing:** keeps the §16.1 limit as it is. Remains the behaviour while the setting is off.

## Consequences

- With the setting on at Strict, browsers stop using ECH for sites they resolve through
  CoreLock; their real names are readable at layer 3.
- It doesn't reach browsers that use their own DoH (CoreLock blocks known DoH servers, layer 2),
  ECH keys a browser cached before the setting was turned on (until the record's TTL ends),
  or DNS over TCP port 53 (not rewritten).
- A server that requires ECH can't, by RFC 9849; a site that misbehaves without ECH would
  fail only with the setting on.
- The setting is not part of the settings export (ADR 0013) yet.
