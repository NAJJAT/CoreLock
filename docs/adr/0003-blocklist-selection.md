# 0003. HaGeZi and OISD small as the blocklists

- **Status:** Accepted
- **Date:** 2026-10-21
- **Commits:** `34ba4ac` (switch to HaGeZi), `f72705c` (disclosures aligned)

## Context

Earlier versions used StevenBlack, EasyList, EasyPrivacy, HaGeZi Light and URLhaus.
URLhaus's host file answered 410 Gone from October 2026, leaving no malware list.
Blocklists also dominate memory: every name is held in the VPN process.

## Decision

| Level | Lists |
|---|---|
| MINIMAL | HaGeZi TIF mini (malware, phishing, scams) |
| STANDARD | + HaGeZi Pro mini (~80k names) |
| STRICT | + HaGeZi Pro (~207k) + OISD small |
| All | HaGeZi DoH + DoH-IP lists, for layer 2 only |

HTTPS only, from an allow-listed set (`BlocklistSource`), with jsDelivr's GitHub
mirror as the fallback. Every list has a built-in fallback for a first start without
network. Old lists are removed only after their replacement downloaded and parsed
(`BlocklistManager.retireReplacedSources`), so an update never leaves a level with
fewer names in between.

## Alternatives

- **Keep StevenBlack / EasyList / EasyPrivacy / HaGeZi Light.** Replaced by HaGeZi
  Pro (`BlocklistManager.retireReplacedSources`). EasyList and EasyPrivacy are a
  browser filter format (URL patterns, cosmetic rules); a DNS/SNI blocker can only
  use hostnames, so most rules were useless while their parser added code. The
  hosts and EasyList parsers were dropped with them; only plain domain and IP lists
  are read now (`ListEntryValidator`).
- **OISD big.** 238k names, mostly already in the other lists, took the VPN heap from
  32 MB to 134 MB on a test phone. OISD small instead, STRICT only.
- **More sources.** Each source is another host in the network disclosure, another
  supply-chain risk, and more memory.

## Consequences

- Measured heap: MINIMAL ≈ 23 MB, STANDARD ≈ 31 MB, STRICT ≈ 44 MB. Compact storage
  (64-bit fingerprints) is on the backlog to cut this.
- We depend on two maintainers (HaGeZi, OISD); the mirror covers HaGeZi only.
- Every list host must appear in the README and docs network tables;
  `OutboundHostsDisclosureTest` enforces it.
- Re-adding a removed list needs a new record.
