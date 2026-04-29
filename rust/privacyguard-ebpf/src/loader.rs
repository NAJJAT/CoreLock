/// eBPF program loader — attaches programs and drives the event loop.

use anyhow::{Context, Result};
use aya::{
    include_bytes_aligned,
    maps::ring_buf::RingBuf,
    programs::{tc, CgroupSkb, CgroupSkbAttachType, SchedClassifier, TcAttachType},
    Bpf,
};
use aya_log::BpfLogger;
use std::fs::File;
use tokio::io::unix::AsyncFd;
use tracing::{debug, info, warn};
use crate::events::{DnsEvent, KernelEvent, PacketEvent};
use crate::policy::PolicyEngine;

pub struct EbpfLoader {
    bpf:    Bpf,
    iface:  String,
    cgroup: String,
    policy: PolicyEngine,
}

impl EbpfLoader {
    pub fn new(iface: &str, cgroup: &str) -> Result<Self> {
        // The eBPF bytecode is embedded at compile time from
        // target/bpfeb-unknown-none/release/privacyguard_ebpf_programs.o
        // For development, use the pre-built stub.
        let bpf_data = include_bytes_aligned!(
            concat!(env!("CARGO_MANIFEST_DIR"), "/bpf/privacyguard.bpf.o")
        );

        let mut bpf = Bpf::load(bpf_data)?;

        if let Err(e) = BpfLogger::init(&mut bpf) {
            warn!("BpfLogger init failed (kernel logs unavailable): {e}");
        }

        Ok(Self {
            bpf,
            iface: iface.to_string(),
            cgroup: cgroup.to_string(),
            policy: PolicyEngine::new(),
        })
    }

    pub fn load_programs(&mut self) -> Result<()> {
        info!("Loading eBPF programs");
        // Programs are already loaded from the .o file in new()
        Ok(())
    }

    pub fn attach(&mut self) -> Result<()> {
        // ── TC egress ────────────────────────────────────────────────────────
        tc::qdisc_add_clsact(&self.iface)
            .context("add clsact qdisc (already present is ok)")?;

        let egress: &mut SchedClassifier = self
            .bpf
            .program_mut("pg_tc_egress")
            .context("program pg_tc_egress not found")?
            .try_into()?;
        egress.load()?;
        egress
            .attach(&self.iface, TcAttachType::Egress)
            .context("attach TC egress")?;

        info!("TC egress attached to {}", self.iface);

        // ── TC ingress ───────────────────────────────────────────────────────
        let ingress: &mut SchedClassifier = self
            .bpf
            .program_mut("pg_tc_ingress")
            .context("program pg_tc_ingress not found")?
            .try_into()?;
        ingress.load()?;
        ingress
            .attach(&self.iface, TcAttachType::Ingress)
            .context("attach TC ingress")?;

        info!("TC ingress attached to {}", self.iface);

        // ── cgroup SKB ───────────────────────────────────────────────────────
        let cgroup_file = File::open(&self.cgroup).context("open cgroup")?;
        let cgroup_skb: &mut CgroupSkb = self
            .bpf
            .program_mut("pg_cgroup_skb")
            .context("program pg_cgroup_skb not found")?
            .try_into()?;
        cgroup_skb.load()?;
        cgroup_skb
            .attach(&cgroup_file, CgroupSkbAttachType::Egress)
            .context("attach cgroup skb")?;

        info!("cgroup_skb attached to {}", self.cgroup);
        Ok(())
    }

    pub async fn run_event_loop(&mut self) -> Result<()> {
        let ring_buf: RingBuf<&mut aya::maps::MapData> = self
            .bpf
            .map_mut("EVENTS")
            .context("map EVENTS not found")?
            .try_into()?;

        let async_fd = AsyncFd::new(ring_buf)?;

        loop {
            let mut guard = async_fd.readable().await?;
            guard.get_inner_mut().next().map(|item| {
                self.handle_raw_event(item.as_ref());
            });
            guard.clear_ready();
        }
    }

    fn handle_raw_event(&mut self, data: &[u8]) {
        if data.len() < 1 { return; }
        let event_type = data[0];
        match event_type {
            0 => {
                // PacketEvent
                if data.len() < std::mem::size_of::<PacketEvent>() + 1 { return; }
                let pkt: PacketEvent = unsafe { std::ptr::read(data[1..].as_ptr() as *const _) };
                let event = if pkt.action == 0 {
                    KernelEvent::PacketAllowed(pkt)
                } else {
                    KernelEvent::PacketBlocked(pkt)
                };
                self.policy.process(event);
            }
            1 => {
                // DnsEvent
                if data.len() < std::mem::size_of::<DnsEvent>() + 1 { return; }
                let dns: DnsEvent = unsafe { std::ptr::read(data[1..].as_ptr() as *const _) };
                let entropy = dns.entropy as f64 / 65536.0;
                if entropy > 3.4 {
                    warn!(
                        "⚠️  DNS tunnelling (kernel): pid={} entropy={:.2}",
                        dns.pid, entropy
                    );
                }
            }
            _ => debug!("Unknown eBPF event type: {event_type}"),
        }
    }

    pub fn detach(&mut self) {
        // aya automatically detaches when programs are dropped
        info!("Detaching eBPF programs");
    }
}
