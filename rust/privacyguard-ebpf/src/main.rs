/// PrivacyGuard eBPF daemon — kernel-level network monitoring.
///
/// Requires:
///   - Linux kernel 5.8+ (for BPF ring buffer, cgroup skb)
///   - Root or CAP_BPF + CAP_NET_ADMIN
///
/// Architecture:
///   Four eBPF programs are loaded into the kernel:
///
///   1. tc_egress   — TC (traffic control) hook on egress: inspects every
///                    outgoing packet before it leaves the host, can DROP.
///
///   2. tc_ingress  — TC hook on ingress: monitors incoming packets.
///
///   3. cgroup_skb  — per-cgroup (per-process-group) socket buffer hook:
///                    tracks which cgroup / process sends/receives traffic.
///
///   4. tracepoint  — hooks into sched_process_exec to track new processes.
///
///   All events are surfaced to userspace via a BPF ring buffer (O(1) copy).
///
/// Build:
///   # Install aya-tool and bpf target
///   cargo install aya-tool
///   rustup toolchain install nightly
///   rustup component add rust-src --toolchain nightly
///   cargo +nightly build --target bpfeb-unknown-none -Z build-std=core
///
///   # Run
///   sudo ./target/release/privacyguard-ebpf --iface eth0

mod events;
mod loader;
mod policy;

use anyhow::Result;
use clap::Parser;
use loader::EbpfLoader;
use tokio::signal;
use tracing::{info, warn};

#[derive(Parser, Debug)]
#[command(name = "privacyguard-ebpf", about = "PrivacyGuard kernel-level monitor")]
struct Args {
    /// Network interface to attach TC hooks to (e.g. eth0, wlan0)
    #[arg(long, default_value = "eth0")]
    iface: String,

    /// Path to domain blocklist
    #[arg(long)]
    blocklist: Option<String>,

    /// Path to cgroup v2 mount point
    #[arg(long, default_value = "/sys/fs/cgroup")]
    cgroup: String,

    #[arg(long, default_value = "info")]
    log_level: String,
}

#[tokio::main]
async fn main() -> Result<()> {
    let args = Args::parse();

    tracing_subscriber::fmt()
        .with_env_filter(&args.log_level)
        .init();

    info!("PrivacyGuard eBPF starting on interface {}", args.iface);

    // Load and attach eBPF programs
    let mut loader = EbpfLoader::new(&args.iface, &args.cgroup)?;
    loader.load_programs()?;
    loader.attach()?;

    info!("eBPF programs loaded. Monitoring traffic…");
    info!("Press Ctrl-C to stop.");

    // Process events from ring buffer until interrupted
    let event_loop = loader.run_event_loop();
    let ctrl_c = signal::ctrl_c();

    tokio::select! {
        result = event_loop => {
            if let Err(e) = result { warn!("Event loop error: {e}") }
        }
        _ = ctrl_c => { info!("Received Ctrl-C — shutting down") }
    }

    loader.detach();
    info!("eBPF programs detached. Goodbye.");
    Ok(())
}
