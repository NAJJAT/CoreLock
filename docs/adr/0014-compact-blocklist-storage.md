# 0014. Keep blocklists in memory as sorted 64-bit fingerprints

- **Status:** Proposed
- **Date:** 2026-11-05
- **Commits:** none yet (implemented on branch `hardening-1.2`, not committed)
- **Backlog:** CLAUDE.md §16.2 item 3; hardening plan task 1.2.

## Context

The running VPN holds three name sets built from the stored lists:

| Set | Holds | Where |
|---|---|---|
| DNS/SNI filter | the level's ad, tracker and telemetry names | `PrivacyVpnService.domainFilter` (`DomainFilter`) |
| Security filter | malware and phishing names, every level | `PrivacyVpnService.securityFilter` (`DomainFilter`) |
| Known trackers | every stored list except the DoH lists, for risk scores | `MetadataEngine.knownTrackers` (`Set<String>`) |

Every list is downloaded at every level (`BlocklistManager.refreshDue` walks all sources),
so the known-trackers set holds HaGeZi TIF mini, Pro mini, Pro and OISD small whatever the
level. Each is a `HashSet<String>`: a hash entry plus a `String` and its character array
per name.

Measured on the JVM (`DomainFilterMemoryCostTest`, 2026-11-05, synthetic names of the same
length mix): **about 105 bytes per name**, 19 / 28 / 48 MiB for 195k / 300k / 480k names.
On the phone the app's PSS was measured at 23 / 31 / 44 MB (MINIMAL / STANDARD / STRICT),
dominated by these sets. A lookup walks the name's parents with `substring`, allocating one
string per parent on the packet path, against CLAUDE.md §6.2.

## Decision

1. **One compact set type.** A name is stored as a 64-bit fingerprint: FNV-1a over its
   characters (lowercase, no trailing dot), finished with the MurmurHash3 `fmix64` mixer.
   A set is a sorted, de-duplicated `LongArray` with a small directory on the top bits
   of the fingerprint (`FingerprintSet`, about 4 values per slot), so `contains` is a
   binary search over a few neighbours: one or two cache misses, not about 19. About
   9 bytes per name (8 for the value, about 1 for the directory) instead of about 105.
2. **`DomainFilter` keeps its API** (`rebuild`, `isBlocked`, counters) and stores two
   such arrays (plain names, which also cover subdomains, and `*.` wildcards). A lookup
   hashes each parent of the name in place, by index, so **nothing is allocated** per
   lookup.
3. **Known trackers use a `DomainFilter` too**, not a `Set<String>`: the same parent walk,
   the same fingerprints.
4. **Atomic swap stays:** a new array is built aside and published through one volatile
   field, so a lookup never sees half a list (CLAUDE.md §5.6).
5. **The start-up cache stores fingerprints.** `BlocklistCache` writes the sorted arrays
   as a binary file (version 2: header, counts, arrays, CRC-32; renamed into place); a VPN
   start reads it in one bulk read and copies it into the arrays, instead of parsing
   hundreds of thousands of text lines. The file is used only if its length, order and
   checksum are right; otherwise the database is read. A version-1 file is ignored and
   deleted on the next write. (A plain read rather than `FileChannel.map`: the file is a
   few MB, and a mapped file can't be replaced or deleted on every platform while mapped.)

### Collisions

Two different names with the same fingerprint would make the second one blocked when the
first is listed. With n listed names, the chance that any one looked-up name collides is
about n / 2^64: for 500,000 names, under 3 × 10^-14 per lookup. A collision can only add a
block, never remove one, so it can't open a listed name. An attacker can't use it to block
someone else's name: they would need that name's fingerprint on a list, and a list author
could simply list the name. So the fingerprint is not keyed; it is the same on every phone
and in the cache file.

## Alternatives considered

- **Keep the arrays memory-mapped for lookups** (no heap at all): a `LongBuffer` read per
  probe is slower than an array read, and a mapped file must not be replaced while mapped.
  It would save the last 8 bytes per name. Deferred; revisit if the arrays still matter.
- **A trie or a DAFSA of labels:** exact, but more complex code on untrusted input, and
  the earlier trie needed about 100 MB (`DomainFilter` header).
- **Bloom filter:** smaller still, but false positives of a few in a thousand would block
  real sites.
- **Keyed hash (SipHash with a per-install key):** see Collisions; it buys nothing here and
  would make the cache file per-install.

## Consequences

- Heap for the three sets drops from about 105 to about 9 bytes per name. Measured on the
  JVM (`DomainFilterMemoryCostTest`, 2026-11-05): 9.3 / 9.7 / 9.1 bytes per name for
  195k / 300k / 480k names (exact array bytes), against 106 / 100 / 105 before; lookup
  134–146 ns against 115–298 ns; build 47–128 ms against 52–102 ms. SNI check per
  connection (`SniCheckCostTest`, same machine, back to back): 3.30–3.79 µs against
  3.38–3.72 µs. The phone numbers are to be measured on a device.
- Known-tracker scoring now also matches a listed top-level name (an entry `zip` marks
  `a.zip`); before, it stopped one label short. The blocking filters always did this.
- The names themselves are no longer in memory. Anything that needs to *show* a listed name
  reads it from the database (as the list screens already do).
- Diagnostics' "names loaded" counts distinct fingerprints: the same as distinct names
  except after a collision (see above).
- The packet path loses its per-parent string allocation.
- Loading still reads the names from the database once per reload (paged), then drops
  them; the peak during a reload is unchanged.
