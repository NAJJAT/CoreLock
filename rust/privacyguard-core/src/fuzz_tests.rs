//! Random-input fuzzing of the parsers that see untrusted bytes, runnable with a
//! plain `cargo test` (no nightly, no libFuzzer). The coverage-guided targets for
//! cargo-fuzz live in `fuzz/`. Iterations: PG_FUZZ_ITERS (default 200_000).
//!
//! Anything that panics here would abort the app: the release profile uses
//! `panic = "abort"`, and these functions run on the VPN packet path.

use crate::bloom::BloomFilter;
use crate::{entropy, ja3, packet};

/// xorshift64*: deterministic, so a failure reproduces from its seed.
struct Rng(u64);

impl Rng {
    fn next(&mut self) -> u64 {
        self.0 ^= self.0 >> 12;
        self.0 ^= self.0 << 25;
        self.0 ^= self.0 >> 27;
        self.0.wrapping_mul(0x2545_F491_4F6C_DD1D)
    }
    fn below(&mut self, n: usize) -> usize {
        (self.next() % n.max(1) as u64) as usize
    }
    fn bytes(&mut self, n: usize) -> Vec<u8> {
        (0..n).map(|_| self.next() as u8).collect()
    }
}

fn iterations() -> usize {
    std::env::var("PG_FUZZ_ITERS").ok().and_then(|v| v.parse().ok()).unwrap_or(200_000)
}

fn client_hello() -> Vec<u8> {
    let sni = b"example.com";
    let mut ext = Vec::new();
    ext.extend_from_slice(&[0x00, 0x00]);
    ext.extend_from_slice(&((sni.len() + 5) as u16).to_be_bytes());
    ext.extend_from_slice(&((sni.len() + 3) as u16).to_be_bytes());
    ext.push(0);
    ext.extend_from_slice(&(sni.len() as u16).to_be_bytes());
    ext.extend_from_slice(sni);
    ext.extend_from_slice(&[0x00, 0x0a, 0x00, 0x06, 0x00, 0x04, 0x00, 0x1d, 0x00, 0x17]); // groups
    ext.extend_from_slice(&[0x00, 0x0b, 0x00, 0x02, 0x01, 0x00]); // point formats
    let mut body = vec![0x03, 0x03];
    body.extend_from_slice(&[0u8; 32]);
    body.push(0);
    body.extend_from_slice(&[0x00, 0x04, 0x13, 0x01, 0x0a, 0x0a]); // incl. GREASE
    body.extend_from_slice(&[0x01, 0x00]);
    body.extend_from_slice(&(ext.len() as u16).to_be_bytes());
    body.extend_from_slice(&ext);
    let mut hs = vec![0x01, 0x00];
    hs.extend_from_slice(&(body.len() as u16).to_be_bytes());
    hs.extend_from_slice(&body);
    let mut rec = vec![0x16, 0x03, 0x01];
    rec.extend_from_slice(&(hs.len() as u16).to_be_bytes());
    rec.extend_from_slice(&hs);
    rec
}

fn ipv4(proto: u8, dst_port: u16, payload: &[u8]) -> Vec<u8> {
    let mut p = vec![0x45, 0, 0, 0, 0, 1, 0, 0, 64, proto, 0, 0, 10, 0, 0, 2, 1, 1, 1, 1];
    let mut l4 = vec![0x9c, 0x40];
    l4.extend_from_slice(&dst_port.to_be_bytes());
    if proto == 6 {
        l4.extend_from_slice(&[0, 0, 0, 1, 0, 0, 0, 0, 0x50, 0x18, 0xff, 0xff, 0, 0, 0, 0]);
    } else {
        l4.extend_from_slice(&[0, 8, 0, 0]);
    }
    l4.extend_from_slice(payload);
    p.extend_from_slice(&l4);
    p
}

fn mutate(rng: &mut Rng, seed: &[u8]) -> Vec<u8> {
    let mut b = seed.to_vec();
    for _ in 0..1 + rng.below(4) {
        match rng.below(7) {
            0 if !b.is_empty() => { let i = rng.below(b.len()); b[i] ^= 1 << rng.below(8); }
            1 if !b.is_empty() => { let i = rng.below(b.len()); b[i] = [0x00, 0xff, 0x7f, 0x80, 0x0a][rng.below(5)]; }
            2 if b.len() >= 2 => { let i = rng.below(b.len() - 1); b[i] = 0xff; b[i + 1] = 0xff; }
            3 if !b.is_empty() => { let n = rng.below(b.len()); b.truncate(n); }
            4 => { let n = rng.below(64); let extra = rng.bytes(n); b.extend_from_slice(&extra); }
            5 if !b.is_empty() => { let i = rng.below(b.len()); let n = rng.below(32); let ins = rng.bytes(n); b.splice(i..i, ins); }
            _ if !b.is_empty() => { let i = rng.below(b.len()); b[i] = rng.next() as u8; }
            _ => {}
        }
    }
    b
}

fn fuzz(name: &str, seeds: &[Vec<u8>], target: impl Fn(&[u8])) {
    let mut rng = Rng(0x5EED_2026_1014);
    for i in 0..iterations() {
        let input = if seeds.is_empty() || rng.below(10) == 0 {
            let n = rng.below(2048);
            rng.bytes(n)
        } else {
            let pick = rng.below(seeds.len());
            mutate(&mut rng, &seeds[pick])
        };
        let r = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| target(&input)));
        if r.is_err() {
            let hex: String = input.iter().map(|b| format!("{:02x}", b)).collect();
            panic!("{name}: panic at iteration {i}, input {}B: {hex}", input.len());
        }
    }
}

#[test]
fn packet_classifier_never_panics() {
    let ch = client_hello();
    fuzz("classify_ipv4", &[ipv4(6, 443, &ch), ipv4(17, 53, &[0x12, 0x34, 1, 0, 0, 1]), ipv4(6, 80, b"GET /")], |b| {
        let _ = packet::classify_ipv4(b);
    });
    assert_eq!(packet::classify_ipv4(&ipv4(6, 443, &ch)) & 0x02, 0x02);
}

#[test]
fn ja3_never_panics_and_is_stable() {
    let ch = client_hello();
    fuzz("ja3::compute", &[ch.clone()], |b| {
        if let Some(h) = ja3::compute(b) {
            assert_eq!(h.len(), 32);
        }
    });
    // GREASE is skipped; same hello, same hash.
    assert_eq!(ja3::compute(&ch), ja3::compute(&ch));
}

#[test]
fn entropy_is_finite_and_bounded() {
    fuzz("shannon", &[b"aaaaabbbbbcccc".to_vec()], |b| {
        let h = entropy::shannon(b);
        assert!(h.is_finite() && (0.0..=8.0 + 1e-9).contains(&h), "entropy {h}");
    });
}

/// The Kotlin side skips its exact sets when the bloom filter says "absent", so a
/// false negative would let a blocked name through.
#[test]
fn bloom_filter_has_no_false_negatives() {
    let mut rng = Rng(42);
    let names: Vec<String> = (0..50_000)
        .map(|i| {
            let n = 1 + rng.below(40);
            let raw = rng.bytes(n);
            format!("{}{}.example", String::from_utf8_lossy(&raw), i)
        })
        .collect();
    let mut bf = BloomFilter::new(names.len());
    for n in &names {
        bf.insert(n);
    }
    for n in &names {
        assert!(bf.contains(n), "false negative for {n:?}");
    }
    fuzz("bloom contains", &[], |b| {
        let _ = bf.contains(&String::from_utf8_lossy(b));
    });
}
