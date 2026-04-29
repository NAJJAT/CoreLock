/// PrivacyGuard Linux daemon — TUN-based traffic inspection.
///
/// Architecture:
///   Creates a TUN interface (tun0), sets up routing to capture all traffic,
///   reads raw packets, classifies them via the shared Rust core,
///   drops blocked traffic, forwards allowed traffic via raw sockets.
///
/// Requires: CAP_NET_ADMIN or root.
///
/// Build:
///   cargo build --release --target x86_64-unknown-linux-gnu
///   sudo ./target/release/privacyguard --iface tun0 --blocklist /etc/privacyguard/blocklist.txt
///
/// Setup (run once):
///   sudo ip tuntap add dev tun0 mode tun
///   sudo ip addr add 10.8.0.1/24 dev tun0
///   sudo ip link set tun0 up
///   sudo ip route add default via 10.8.0.1 dev tun0 table 100
///   sudo ip rule add fwmark 0x1 table 100

use clap::Parser;
use std::fs;
use std::net::IpAddr;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tracing::{debug, error, info, warn};

mod blocklist;
mod firewall;
mod session;

#[derive(Parser, Debug)]
#[command(name = "privacyguard", about = "PrivacyGuard Linux VPN daemon")]
struct Args {
    /// TUN interface name
    #[arg(long, default_value = "pg0")]
    iface: String,

    /// Path to domain blocklist (one domain per line)
    #[arg(long)]
    blocklist: Option<String>,

    /// Maximum packet size (MTU)
    #[arg(long, default_value_t = 1500)]
    mtu: usize,

    /// Log level (trace/debug/info/warn/error)
    #[arg(long, default_value = "info")]
    log_level: String,
}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    let args = Args::parse();

    tracing_subscriber::fmt()
        .with_env_filter(&args.log_level)
        .init();

    info!("PrivacyGuard Linux starting on interface {}", args.iface);

    // Load blocklist
    let domains: Vec<String> = if let Some(path) = &args.blocklist {
        fs::read_to_string(path)
            .unwrap_or_default()
            .lines()
            .filter(|l| !l.starts_with('#') && !l.is_empty())
            .map(String::from)
            .collect()
    } else {
        vec![]
    };

    info!("Loaded {} blocked domains", domains.len());

    // Rebuild native bloom filter
    let domain_ptrs: Vec<*const i8> = domains
        .iter()
        .map(|s| s.as_ptr() as *const i8)
        .collect();
    // Safety: domain_ptrs points into `domains` which outlives this call
    unsafe {
        privacyguard_core::ffi::pg_bloom_rebuild(domain_ptrs.as_ptr() as *const *const i8);
    }

    // Open TUN device
    let config = tun::Configuration::default();
    let mut dev = tun::create_as_async(&config)?;

    info!("TUN device opened — entering packet loop");

    let mut buf = vec![0u8; args.mtu + 64];
    loop {
        let n = match dev.read(&mut buf).await {
            Ok(n) if n > 0 => n,
            Ok(_)          => continue,
            Err(e)         => { error!("TUN read error: {e}"); break; }
        };

        let raw = &buf[..n];
        let version = (raw[0] >> 4) & 0xF;

        let flags = unsafe {
            privacyguard_core::ffi::pg_classify_packet(raw.as_ptr(), n as i32)
        };

        // 0x01 = blocked by domain/IP rule
        if flags & 0x01 != 0 {
            debug!("Blocked packet (version={version}, len={n})");
            continue;
        }

        // 0x02 = TLS ClientHello — check JA3
        if flags & 0x02 != 0 {
            check_ja3(raw);
        }

        // 0x04 = DNS query — inspect for tunnelling
        if flags & 0x04 != 0 {
            check_dns_entropy(raw);
        }

        // Forward allowed packet back to TUN (kernel routes it to real network)
        if let Err(e) = dev.write_all(raw).await {
            error!("TUN write error: {e}");
        }
    }

    Ok(())
}

fn check_ja3(raw: &[u8]) {
    // Simplified: extract TCP payload and compute JA3
    if raw.len() < 54 { return; }  // 20 IP + 20 TCP + 14 TLS min
    let ip_hdr  = (raw[0] & 0x0F) as usize * 4;
    let tcp_hdr = (raw[ip_hdr + 12] >> 4) as usize * 4;
    let payload_off = ip_hdr + tcp_hdr;
    if raw.len() <= payload_off { return; }

    let tls = &raw[payload_off..];
    let hash_ptr = unsafe {
        privacyguard_core::ffi::pg_compute_ja3(tls.as_ptr(), tls.len() as i32)
    };
    if hash_ptr.is_null() { return; }

    let hash = unsafe { std::ffi::CStr::from_ptr(hash_ptr).to_string_lossy().into_owned() };
    unsafe { privacyguard_core::ffi::pg_free_string(hash_ptr); }

    // Known Cobalt Strike hashes
    if hash == "6734f37431670b3ab4292b8f60f29984" {
        warn!("⚠️  JA3 THREAT DETECTED: Cobalt Strike — hash={hash}");
    }
}

fn check_dns_entropy(raw: &[u8]) {
    if raw.len() < 40 { return; }
    // UDP payload starts at IP header + 8 (UDP header)
    let ip_hdr = (raw[0] & 0x0F) as usize * 4;
    let udp_payload = ip_hdr + 8;
    if raw.len() <= udp_payload + 12 { return; }

    let dns_query = &raw[udp_payload + 12..]; // skip DNS header
    let entropy = unsafe {
        privacyguard_core::ffi::pg_shannon_entropy(dns_query.as_ptr(), dns_query.len() as i32)
    };

    if entropy > 3.4 {
        warn!("⚠️  DNS tunnelling suspected — entropy={:.2}", entropy);
    }
}
