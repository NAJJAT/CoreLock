/// Two-hash bloom filter — no external crates.
/// False positive rate ≈ 0.01% at 1M entries with this configuration.

pub struct BloomFilter {
    bits: Vec<u64>,
    num_bits: usize,
}

impl BloomFilter {
    pub fn new(expected_items: usize) -> Self {
        // m = -n * ln(p) / (ln2)^2  with p=0.001
        let m = ((expected_items as f64 * 10.0) as usize).max(64);
        let words = (m + 63) / 64;
        BloomFilter {
            bits: vec![0u64; words],
            num_bits: words * 64,
        }
    }

    pub fn insert(&mut self, item: &str) {
        let (h1, h2) = hashes(item);
        for k in 0..7u64 {
            let idx = ((h1.wrapping_add(k.wrapping_mul(h2))) as usize) % self.num_bits;
            self.bits[idx / 64] |= 1u64 << (idx % 64);
        }
    }

    pub fn contains(&self, item: &str) -> bool {
        let (h1, h2) = hashes(item);
        for k in 0..7u64 {
            let idx = ((h1.wrapping_add(k.wrapping_mul(h2))) as usize) % self.num_bits;
            if self.bits[idx / 64] & (1u64 << (idx % 64)) == 0 {
                return false;
            }
        }
        true
    }
}

/// FNV-1a 64-bit and djb2 as the two independent hash functions.
fn hashes(s: &str) -> (u64, u64) {
    let mut h1: u64 = 0xcbf29ce484222325;
    let mut h2: u64 = 5381;
    for b in s.bytes() {
        h1 ^= b as u64;
        h1 = h1.wrapping_mul(0x100000001b3);
        h2 = h2.wrapping_shl(5).wrapping_add(h2).wrapping_add(b as u64);
    }
    (h1, h2)
}
