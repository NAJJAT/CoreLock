/// Platform-neutral C ABI — usable from Swift, C, C++, Python, etc.
///
/// On iOS/macOS link as a static library (-lprivacyguard_core).
/// On Linux/Windows link as a shared library (.so / .dll).
///
/// The C header is generated in `include/privacyguard_core.h`.

use crate::bloom::BloomFilter;
use crate::{entropy, ja3, packet, BLOOM};
use std::ffi::{CStr, CString};
use std::os::raw::{c_char, c_double, c_int, c_uchar};
use std::slice;

/// Classify a raw IPv4/IPv6 packet.
/// Returns bitmask: 0x01=block, 0x02=tls_client_hello, 0x04=dns_query.
#[no_mangle]
pub extern "C" fn pg_classify_packet(data: *const c_uchar, len: c_int) -> c_int {
    if data.is_null() || len <= 0 {
        return 0;
    }
    let bytes = unsafe { slice::from_raw_parts(data, len as usize) };
    packet::classify_ipv4(bytes)
}

/// Compute JA3 fingerprint of a raw TLS ClientHello.
/// Caller must free the returned string with `pg_free_string`.
/// Returns NULL if parsing fails.
#[no_mangle]
pub extern "C" fn pg_compute_ja3(data: *const c_uchar, len: c_int) -> *mut c_char {
    if data.is_null() || len <= 0 {
        return std::ptr::null_mut();
    }
    let bytes = unsafe { slice::from_raw_parts(data, len as usize) };
    match ja3::compute(bytes) {
        Some(hash) => CString::new(hash)
            .map(|s| s.into_raw())
            .unwrap_or(std::ptr::null_mut()),
        None => std::ptr::null_mut(),
    }
}

/// Free a string previously returned by a `pg_*` function.
#[no_mangle]
pub extern "C" fn pg_free_string(s: *mut c_char) {
    if !s.is_null() {
        unsafe { drop(CString::from_raw(s)) };
    }
}

/// Query the native bloom filter for a domain name (null-terminated C string).
/// Returns 1 if present (probable), 0 if definitely absent.
#[no_mangle]
pub extern "C" fn pg_bloom_check(domain: *const c_char) -> c_int {
    if domain.is_null() {
        return 0;
    }
    let s = unsafe { CStr::from_ptr(domain) }.to_string_lossy();
    let guard = BLOOM.lock().unwrap();
    guard.as_ref().map_or(0, |bf| bf.contains(s.as_ref()) as c_int)
}

/// Rebuild the bloom filter from a null-terminated array of null-terminated strings.
/// The array must end with a NULL pointer.
#[no_mangle]
pub extern "C" fn pg_bloom_rebuild(domains: *const *const c_char) {
    if domains.is_null() {
        return;
    }
    let mut list = Vec::new();
    let mut ptr = domains;
    unsafe {
        while !(*ptr).is_null() {
            let s = CStr::from_ptr(*ptr).to_string_lossy().into_owned();
            list.push(s);
            ptr = ptr.add(1);
        }
    }
    let mut bf = BloomFilter::new(list.len().max(1_000_000));
    for d in &list {
        bf.insert(d);
    }
    *BLOOM.lock().unwrap() = Some(bf);
}

/// Compute Shannon entropy of a byte buffer.
#[no_mangle]
pub extern "C" fn pg_shannon_entropy(data: *const c_uchar, len: c_int) -> c_double {
    if data.is_null() || len <= 0 {
        return 0.0;
    }
    let bytes = unsafe { slice::from_raw_parts(data, len as usize) };
    entropy::shannon(bytes)
}
