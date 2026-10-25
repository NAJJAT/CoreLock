# 0004. Hold QUIC until the ClientHello is readable, with hard limits

- **Status:** Accepted
- **Date:** 2026-10-21
- **Commits:** `367f2f8` (gate), `e8871fd` (reporting and diagnostics)

## Context

QUIC (HTTP/3) runs on UDP/443. Its first packet carries a ClientHello with the
server name, but that ClientHello can span several datagrams. If we forward the first
datagrams before reading the name, the connection to a blocked host is already
started. UDP/443 is also used by STUN/TURN (calls), WireGuard and DTLS, which are not
QUIC and must keep working.

## Decision

At STANDARD and STRICT, datagrams of a new QUIC flow are held until the ClientHello
is complete, then decided by `NameBlockPolicy` like TCP SNI. Unreadable QUIC
long-header Initials are dropped (fail closed); the browser then retries over TCP,
where layer 3 reads the SNI. Non-QUIC UDP/443 is never held or dropped by the gate.

Hard limits (`QuicSniTracker` in `core/tls/QuicInitial.kt`):

| Limit | Value | Over the limit |
|---|---|---|
| Flows being held | 128 (`MAX_HOLDING_FLOWS`) | new flow dropped, counted |
| Bytes held in total | 512 KB (`MAX_HELD_BYTES`) | new flow dropped, counted |
| Hold time | 10 s (`HOLD_TIMEOUT_MS`) | flow abandoned |

"Block QUIC for better filtering" defaults on only at STRICT; an explicit user choice
always wins over the level default.

## Alternatives

- **Block all UDP/443.** Simplest, but breaks video calls, WireGuard and DTLS.
- **Block all QUIC.** Works (browsers fall back to TCP) but costs HTTP/3 speed for
  every site; kept as the STRICT default and an opt-in elsewhere.
- **Forward and decide later.** The first datagrams would reach a blocked server.
- **No limits.** A flood of half-open QUIC flows would exhaust memory and take down
  the whole tunnel.

## Consequences

- A small delay on the first packets of each new QUIC flow at STANDARD and STRICT.
- Dropped flows are counters only, never connection rows, because apps retry them by
  the hundred (CLAUDE.md §5.3). They show in Settings › Diagnostics.
- Changing a limit or the gate's scope needs the owner (CLAUDE.md §14.2).
