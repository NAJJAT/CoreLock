# PrivacyGuard eBPF Build & Setup

## Prerequisites

```bash
# Kernel 5.8+
uname -r

# Install clang (for compiling BPF C programs)
sudo apt install clang llvm linux-headers-$(uname -r)

# Install libbpf
sudo apt install libbpf-dev

# Install bpftool
sudo apt install linux-tools-$(uname -r)

# Install Rust nightly (needed for aya eBPF target)
rustup toolchain install nightly
rustup component add rust-src --toolchain nightly

# Add bpf target
rustup target add bpfeb-unknown-none --toolchain nightly
```

## Compile eBPF kernel programs

```bash
cd rust/privacyguard-ebpf/src/bpf

# Compile to BPF bytecode
clang -O2 -g -target bpf \
      -D__TARGET_ARCH_x86 \
      -I/usr/include \
      -I/usr/include/bpf \
      -c privacyguard.bpf.c \
      -o privacyguard.bpf.o

# Verify
llvm-objdump -S privacyguard.bpf.o
```

## Compile userspace controller

```bash
# From rust/ directory
cargo build --release -p privacyguard-ebpf
```

## Run

```bash
# Load eBPF programs and start monitoring
sudo ./target/release/privacyguard-ebpf \
    --iface eth0 \
    --blocklist /etc/privacyguard/domains.txt \
    --cgroup /sys/fs/cgroup \
    --log-level info
```

## Block an IP from userspace

```bash
# Add 1.2.3.4 to the BLOCKED_IPS map
sudo bpftool map update pinned /sys/fs/bpf/pg/BLOCKED_IPS \
    key 1 2 3 4 \
    value 1
```

## Verify programs are loaded

```bash
sudo bpftool prog list | grep pg_
sudo bpftool map list | grep -E "EVENTS|BLOCKED"
```

## What each program does

| Program | Hook | Purpose |
|---|---|---|
| `pg_tc_egress` | TC egress | Classifies outgoing packets, drops blocked IPs |
| `pg_tc_ingress` | TC ingress | Monitors incoming traffic (observe only) |
| `pg_cgroup_skb` | cgroup_skb/egress | Per-process attribution — which PID sends what |

All events flow to the BPF ring buffer (`EVENTS` map) and are processed in userspace.
The Rust core library (`privacyguard-core`) handles JA3 fingerprinting and entropy.

## Difference from Linux TUN daemon

| Feature | TUN daemon | eBPF |
|---|---|---|
| Visibility to apps | Apps see a VPN | **Invisible** — apps can't detect it |
| Root required | Yes | Yes (CAP_BPF + CAP_NET_ADMIN) |
| Performance | ~5% overhead | <1% overhead |
| Kernel version | Any | 5.8+ |
| Traffic manipulation | Full proxy | Observe + drop only (no MITM) |
