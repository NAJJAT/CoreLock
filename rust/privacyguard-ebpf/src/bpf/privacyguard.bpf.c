// SPDX-License-Identifier: GPL-2.0 OR MIT
//
// PrivacyGuard eBPF kernel programs.
//
// Compile:
//   clang -O2 -g -target bpf \
//         -D__TARGET_ARCH_x86 \
//         -I/usr/include/bpf \
//         -c privacyguard.bpf.c \
//         -o privacyguard.bpf.o
//
// Or with libbpf skeleton generation:
//   bpftool gen skeleton privacyguard.bpf.o > privacyguard.skel.h

#include <linux/bpf.h>
#include <linux/if_ether.h>
#include <linux/ip.h>
#include <linux/tcp.h>
#include <linux/udp.h>
#include <linux/pkt_cls.h>
#include <bpf/bpf_helpers.h>
#include <bpf/bpf_endian.h>

// ── Ring buffer for userspace events ─────────────────────────────────────────

struct {
    __uint(type, BPF_MAP_TYPE_RINGBUF);
    __uint(max_entries, 256 * 1024);   // 256 KB
} EVENTS SEC(".maps");

// ── IP blocklist (set from userspace) ────────────────────────────────────────

struct {
    __uint(type, BPF_MAP_TYPE_HASH);
    __uint(max_entries, 100000);
    __type(key,   __u32);  // IPv4 address (network byte order)
    __type(value, __u8);   // 1 = blocked
} BLOCKED_IPS SEC(".maps");

// ── Packet event struct ───────────────────────────────────────────────────────

struct packet_event {
    __u32 pid;
    __u32 uid;
    __u32 src_ip;
    __u32 dst_ip;
    __u16 src_port;
    __u16 dst_port;
    __u8  protocol;
    __u8  direction;  // 0=egress 1=ingress
    __u8  action;     // 0=allow 1=drop
    __u8  pad;
};

// ── TC egress hook — outgoing packets ────────────────────────────────────────

SEC("classifier/egress")
int pg_tc_egress(struct __sk_buff *skb) {
    void *data     = (void *)(long)skb->data;
    void *data_end = (void *)(long)skb->data_end;

    struct ethhdr *eth = data;
    if ((void *)(eth + 1) > data_end)
        return TC_ACT_OK;

    if (eth->h_proto != bpf_htons(ETH_P_IP))
        return TC_ACT_OK;

    struct iphdr *ip = (void *)(eth + 1);
    if ((void *)(ip + 1) > data_end)
        return TC_ACT_OK;

    // Check if destination IP is blocked
    __u32 dst = ip->daddr;
    __u8 *blocked = bpf_map_lookup_elem(&BLOCKED_IPS, &dst);

    __u16 src_port = 0, dst_port = 0;
    if (ip->protocol == IPPROTO_TCP) {
        struct tcphdr *tcp = (void *)ip + (ip->ihl * 4);
        if ((void *)(tcp + 1) <= data_end) {
            src_port = bpf_ntohs(tcp->source);
            dst_port = bpf_ntohs(tcp->dest);
        }
    } else if (ip->protocol == IPPROTO_UDP) {
        struct udphdr *udp = (void *)ip + (ip->ihl * 4);
        if ((void *)(udp + 1) <= data_end) {
            src_port = bpf_ntohs(udp->source);
            dst_port = bpf_ntohs(udp->dest);
        }
    }

    // Emit event to ring buffer
    struct packet_event *evt = bpf_ringbuf_reserve(&EVENTS, sizeof(*evt), 0);
    if (evt) {
        evt->pid       = bpf_get_current_pid_tgid() >> 32;
        evt->uid       = bpf_get_current_uid_gid() & 0xFFFFFFFF;
        evt->src_ip    = ip->saddr;
        evt->dst_ip    = ip->daddr;
        evt->src_port  = src_port;
        evt->dst_port  = dst_port;
        evt->protocol  = ip->protocol;
        evt->direction = 0;  // egress
        evt->action    = (blocked && *blocked) ? 1 : 0;
        bpf_ringbuf_submit(evt, 0);
    }

    if (blocked && *blocked)
        return TC_ACT_SHOT;   // drop the packet

    return TC_ACT_OK;
}

// ── TC ingress hook — incoming packets ───────────────────────────────────────

SEC("classifier/ingress")
int pg_tc_ingress(struct __sk_buff *skb) {
    void *data     = (void *)(long)skb->data;
    void *data_end = (void *)(long)skb->data_end;

    struct ethhdr *eth = data;
    if ((void *)(eth + 1) > data_end) return TC_ACT_OK;
    if (eth->h_proto != bpf_htons(ETH_P_IP)) return TC_ACT_OK;

    struct iphdr *ip = (void *)(eth + 1);
    if ((void *)(ip + 1) > data_end) return TC_ACT_OK;

    struct packet_event *evt = bpf_ringbuf_reserve(&EVENTS, sizeof(*evt), 0);
    if (evt) {
        evt->pid       = 0;   // not available on ingress
        evt->uid       = 0;
        evt->src_ip    = ip->saddr;
        evt->dst_ip    = ip->daddr;
        evt->src_port  = 0;
        evt->dst_port  = 0;
        evt->protocol  = ip->protocol;
        evt->direction = 1;   // ingress
        evt->action    = 0;   // always allow ingress (monitored only)
        bpf_ringbuf_submit(evt, 0);
    }

    return TC_ACT_OK;
}

// ── cgroup_skb — per-process-group socket buffer hook ────────────────────────
// Provides process identity (pid/uid) for each packet.

SEC("cgroup_skb/egress")
int pg_cgroup_skb(struct __sk_buff *skb) {
    // cgroup_skb runs with full process context — emit enriched event
    struct packet_event *evt = bpf_ringbuf_reserve(&EVENTS, sizeof(*evt), 0);
    if (evt) {
        __u64 pid_tgid = bpf_get_current_pid_tgid();
        evt->pid       = pid_tgid >> 32;
        evt->uid       = bpf_get_current_uid_gid() & 0xFFFFFFFF;
        evt->src_ip    = 0;   // not available in cgroup_skb context
        evt->dst_ip    = 0;
        evt->src_port  = 0;
        evt->dst_port  = 0;
        evt->protocol  = skb->protocol;
        evt->direction = 0;
        evt->action    = 0;
        bpf_ringbuf_submit(evt, 0);
    }
    return 1;  // 1 = allow
}

char _license[] SEC("license") = "GPL";
