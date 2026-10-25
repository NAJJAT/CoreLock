# 0002. Block in four layers with one name decision

- **Status:** Accepted
- **Date:** SNI layer 2026-10-14; one decision path 2026-10-23
- **Commits:** `f978f47` (TLS/QUIC SNI and CNAME), `367f2f8` (QUIC gate),
  `1a35f1e` (rule precedence)

## Context

DNS blocking alone is cheap but easy to evade: apps use encrypted DNS (DoH, DoT),
hardcoded IPs, cached answers or QUIC. Each evasion needs a different check, and
checks that disagree are a leak: a name blocked by DNS but allowed by SNI gets
through.

## Decision

Four layers, each catching what the one before misses:

1. **DNS blocking** (`DnsHandler`, `DnsBlockDecision`): NXDOMAIN, CNAME chains checked.
2. **DNS-bypass prevention** (`DnsBypassGuard`): drop DoT (port 853), block known
   DoH servers by name and IP, so apps fall back to DNS we see.
3. **Connection filtering by SNI** (`TcpForwarder`, `QuicGate`): read the server name
   from the TLS ClientHello and the QUIC Initial.
4. **Per-app firewall**: block an app fully or in the background only.

Every name decision in every layer goes through one function, `NameBlockPolicy`,
in a fixed order: user Block → malware lists (unless a confirmed
`overridesSecurity` Allow) → user Allow → ad lists and `AdSdkPolicy`.

## Alternatives

- **DNS only.** Loses to any app with DoH or a hardcoded IP.
- **Separate matching code per layer.** Simpler locally, but each copy must get
  every detail right, or a name blocked in one layer leaks through another. A
  normalization detail like this once let `ads.example.com.` (trailing dot) bypass
  user rules.
- **Full HTTPS interception for everyone.** Most apps on Android 7+ ignore user CAs
  or pin certificates, so it reaches few apps and adds a CA key to protect. It
  exists as an opt-in (see 0006), not as a blocking layer.

## Consequences

- No second matching path may be added. A property test with fixed seed
  `20261021` checks DNS and SNI against a model of the order; keep both.
- Names are normalized (lowercase, no trailing dot) before every match.
- Every block records a `BlockSource`, so "Stopped by" is always true.
- Layer 3 runs on the hot path: the SNI check costs ≈ 4.3 µs per connection
  (CLAUDE.md §6.2) and must not regress.
- Limits stay: same-host in-app ads (YouTube, Instagram), ECH, and per-app rules
  at the DNS layer (Android's resolver makes the request) (CLAUDE.md §16).
