# Bug-fix progress (branch bug-fixes)

Working log for the 1.2.x / 1.3 bug-fix round. Security details stay out of this file
until they are fixed; entries use generic wording.

## Part 1: audit (read-only)

- 2026-11-11: started. Branch `bug-fixes`, clean tree at `a0ab4a1`.
- 2026-11-11: audit done (code reading). Areas covered: packet path and parsers, blocking
  logic, resource limits, app attribution, HTTPS inspection, SIEM, secrets and crypto,
  components, data handling, blocklist pipeline, lifecycle, database, build and CI, docs.
- Findings: 18 items (F-1 to F-18), reported in chat to the owner. Details stay in chat
  until each item is fixed.
- Status: done; owner's decisions received 2026-11-11.

## Part 2: fixes

Owner said go on 2026-11-11. Baseline: 1,202 unit tests, 0 failures. Hot path on the JVM: SNI
check 3.39 µs per connection, QUIC gate 9.56 µs per new flow (new QuicGateCostTest).
Device checks run on the owner's phone from 2026-11-11 (owner's decision); the emulator was
used before that.

- F-1 IPv4 fragments: already dropped by the TUN reader. Added a regression test and a
  Diagnostics count. Mutation proof: the test fails with the check removed.
- F-2 connection limits: 4,096 TCP, 4,096 UDP, 1,024 per app; idle UDP closed first, then
  refused and counted. Normal browsing peak measured on the emulator: about 120 sessions.
- F-3 own-app check: exact application ID only; a source scan test keeps it so.
- F-7 TLS version: read from the handshake, not the record header.
- F-10 TLS name bounds: the server name must lie inside its extension, and each extension
  inside the extension block. New fuzz target (ParserBoundsFuzzTest); the fuzzer found the
  second case. Mutation proof done.
- F-11 DNS name limits: label and name lengths as RFC 1035 allows; names inside a record end
  inside that record. New tests and fuzz target. Mutation proof done.
- F-6 QUIC reassembly: handshake data offered twice with different bytes makes the flow
  unreadable (fail closed). The QUIC fuzz mix keeps most frames honest so flows still reach
  the deep states (coverage floor unchanged). Mutation proof: 2 tests fail without the fix.
  QUIC gate 9.47-9.58 µs, no regression.
- Checkpoint after F-6: 1,236 unit tests, 0 failures; lint passed (warnings only); R8 passed.
  Next: F-12, then F-4, F-5, F-8, F-9, F-13, F-14, F-15, F-16, the known items, and the
  smaller items. SNI cost not yet re-measured after F-10.
