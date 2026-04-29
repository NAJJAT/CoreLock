/// PrivacyGuard Windows service — WinDivert-based packet inspection.
///
/// WinDivert intercepts packets at the network layer before they reach sockets.
/// This is lighter than a TUN driver — no virtual interface, no routing changes.
///
/// Requires:
///   - WinDivert.dll / WinDivert64.sys in the same directory (or System32)
///   - Run as Administrator (for WinDivert filter installation)
///
/// Build:
///   cargo build --release --target x86_64-pc-windows-msvc
///
/// Install as a Windows service:
///   sc.exe create PrivacyGuard binPath= "C:\Program Files\PrivacyGuard\privacyguard.exe"
///   sc.exe start PrivacyGuard

use clap::Parser;
use tracing::{debug, error, info, warn};

#[derive(Parser, Debug)]
#[command(name = "privacyguard-windows")]
struct Args {
    /// WinDivert filter expression
    #[arg(long, default_value = "ip")]
    filter: String,

    /// Path to domain blocklist
    #[arg(long)]
    blocklist: Option<String>,

    #[arg(long, default_value = "info")]
    log_level: String,
}

fn main() -> anyhow::Result<()> {
    let args = Args::parse();

    tracing_subscriber::fmt()
        .with_env_filter(&args.log_level)
        .init();

    info!("PrivacyGuard Windows starting");

    // Load blocklist into native bloom filter
    if let Some(path) = &args.blocklist {
        let domains: Vec<std::ffi::CString> = std::fs::read_to_string(path)
            .unwrap_or_default()
            .lines()
            .filter(|l| !l.starts_with('#') && !l.is_empty())
            .filter_map(|l| std::ffi::CString::new(l).ok())
            .collect();

        let ptrs: Vec<*const i8> = domains.iter().map(|s| s.as_ptr()).collect();
        let mut ptrs_null = ptrs.clone();
        ptrs_null.push(std::ptr::null());

        unsafe {
            privacyguard_core::ffi::pg_bloom_rebuild(ptrs_null.as_ptr() as *const *const i8);
        }
        info!("Loaded {} domains into bloom filter", domains.len());
    }

    // Open WinDivert handle
    // Filter "ip" captures all IPv4 packets at NETWORK layer
    // Use "ip or ipv6" to also capture IPv6
    let handle = windivert::WinDivert::new(
        &args.filter,
        windivert::Layer::Network,
        0,   // priority
        0,   // flags
    )?;

    info!("WinDivert handle opened — entering packet loop");

    let mut buf = vec![0u8; 65_535];
    loop {
        let (n, addr) = match handle.recv(Some(&mut buf)) {
            Ok(r) => r,
            Err(e) => { error!("WinDivert recv error: {e}"); break; }
        };

        let raw = &buf[..n];
        let flags = unsafe {
            privacyguard_core::ffi::pg_classify_packet(raw.as_ptr(), n as i32)
        };

        if flags & 0x01 != 0 {
            debug!("Blocked packet (len={n}) — dropping");
            // Drop: do NOT re-inject
            continue;
        }

        if flags & 0x02 != 0 {
            check_ja3(raw);
        }
        if flags & 0x04 != 0 {
            check_dns_entropy(raw);
        }

        // Re-inject allowed packet
        if let Err(e) = handle.send(raw, &addr) {
            error!("WinDivert send error: {e}");
        }
    }

    Ok(())
}

fn check_ja3(raw: &[u8]) {
    if raw.len() < 54 { return; }
    let ip_hdr  = (raw[0] & 0x0F) as usize * 4;
    let tcp_hdr = (raw[ip_hdr + 12] >> 4) as usize * 4;
    let off = ip_hdr + tcp_hdr;
    if raw.len() <= off { return; }

    let tls = &raw[off..];
    let ptr = unsafe {
        privacyguard_core::ffi::pg_compute_ja3(tls.as_ptr(), tls.len() as i32)
    };
    if ptr.is_null() { return; }
    let hash = unsafe { std::ffi::CStr::from_ptr(ptr).to_string_lossy().into_owned() };
    unsafe { privacyguard_core::ffi::pg_free_string(ptr); }

    if hash == "6734f37431670b3ab4292b8f60f29984" {
        warn!("⚠️  JA3 THREAT: Cobalt Strike beacon — hash={hash}");
    }
}

fn check_dns_entropy(raw: &[u8]) {
    if raw.len() < 40 { return; }
    let ip_hdr = (raw[0] & 0x0F) as usize * 4;
    let off = ip_hdr + 8 + 12;
    if raw.len() <= off { return; }

    let entropy = unsafe {
        privacyguard_core::ffi::pg_shannon_entropy(raw[off..].as_ptr(), (raw.len() - off) as i32)
    };
    if entropy > 3.4 {
        warn!("⚠️  DNS tunnelling suspected — entropy={:.2}", entropy);
    }
}
