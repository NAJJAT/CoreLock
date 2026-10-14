#![no_main]
//! cargo +nightly fuzz run classify_packet  (needs a C++ toolchain for libFuzzer)
use libfuzzer_sys::fuzz_target;

fuzz_target!(|data: &[u8]| {
    // Through the C ABI, as the app's native bridge calls it.
    unsafe {
        let _ = privacyguard_core::ffi::pg_classify_packet(data.as_ptr(), data.len() as i32);
        let _ = privacyguard_core::ffi::pg_shannon_entropy(data.as_ptr(), data.len() as i32);
    }
});
