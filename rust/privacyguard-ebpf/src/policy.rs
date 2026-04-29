/// Userspace policy engine that acts on kernel events.
/// Mirrors the Kotlin FilterEngine but runs in the eBPF userspace controller.

use crate::events::KernelEvent;
use std::collections::{HashMap, HashSet};
use tracing::{info, warn};

pub struct PolicyEngine {
    blocked_ips:       HashSet<u32>,
    alert_counts:      HashMap<u32, u32>,   // pid → alert count
    blocked_packets:   u64,
    allowed_packets:   u64,
}

impl PolicyEngine {
    pub fn new() -> Self {
        Self {
            blocked_ips:     HashSet::new(),
            alert_counts:    HashMap::new(),
            blocked_packets: 0,
            allowed_packets: 0,
        }
    }

    pub fn process(&mut self, event: KernelEvent) {
        match event {
            KernelEvent::PacketBlocked(pkt) => {
                self.blocked_packets += 1;
                let count = self.alert_counts.entry(pkt.pid).or_insert(0);
                *count += 1;
                if *count == 1 || *count % 100 == 0 {
                    warn!(
                        "🚫 Blocked: pid={} {}:{} → {}:{} proto={}",
                        pkt.pid,
                        pkt.src_addr(), pkt.src_port,
                        pkt.dst_addr(), pkt.dst_port,
                        pkt.protocol,
                    );
                }
            }
            KernelEvent::PacketAllowed(pkt) => {
                self.allowed_packets += 1;
                if self.allowed_packets % 1000 == 0 {
                    info!("📊 Stats: allowed={} blocked={}", self.allowed_packets, self.blocked_packets);
                }
            }
            KernelEvent::DnsTunnelingSuspect(dns) => {
                warn!("⚠️  DNS tunnelling suspect: pid={} dst={}", dns.pid, dns.dst_ip);
            }
            KernelEvent::ProcessSpawned(proc) => {
                let comm = std::str::from_utf8(&proc.comm)
                    .unwrap_or("?")
                    .trim_matches('\0');
                info!("🆕 Process: pid={} ppid={} comm={}", proc.pid, proc.ppid, comm);
            }
            KernelEvent::TlsClientHello(tls) => {
                info!("🔐 TLS ClientHello: pid={} → {}:{}", tls.pid, tls.dst_ip, tls.dst_port);
            }
        }
    }

    pub fn block_ip(&mut self, ip: u32) {
        self.blocked_ips.insert(ip);
    }

    pub fn unblock_ip(&mut self, ip: u32) {
        self.blocked_ips.remove(&ip);
    }

    pub fn stats(&self) -> (u64, u64) {
        (self.allowed_packets, self.blocked_packets)
    }
}
