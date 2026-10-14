#![no_main]
//! cargo +nightly fuzz run ja3  (needs a C++ toolchain for libFuzzer)
use libfuzzer_sys::fuzz_target;

fuzz_target!(|data: &[u8]| {
    unsafe {
        let s = privacyguard_core::ffi::pg_compute_ja3(data.as_ptr(), data.len() as i32);
        privacyguard_core::ffi::pg_free_string(s);
    }
});
