mod bloom;
mod entropy;
mod ja3;
mod packet;
pub mod ffi;

use bloom::BloomFilter;
#[cfg(feature = "android")]
use jni::objects::{JByteArray, JClass, JObject, JObjectArray, JString};
#[cfg(feature = "android")]
use jni::sys::{jboolean, jdouble, jint, jstring};
#[cfg(feature = "android")]
use jni::JNIEnv;
use std::sync::Mutex;

// ── Global state ──────────────────────────────────────────────────────────────

static BLOOM: Mutex<Option<BloomFilter>> = Mutex::new(None);

// ── JNI exports (Android only) ────────────────────────────────────────────────
#[cfg(feature = "android")]
// Package: com.privacyguard.core.native_engine
// Class:   RustBridge  (Kotlin object → singleton class in bytecode)
//
// JNI name rule: Java_<package_underscores>_<Class>_<method>
// Underscore in package component → _1  (JNI encoding)

/// Parse a raw IPv4 packet and return a flag bitmask:
///   0x01 = should be blocked by domain filter
///   0x02 = contains TLS ClientHello (port 443, flags detected)
///   0x04 = is a DNS query (UDP port 53)
#[no_mangle]
pub extern "C" fn Java_com_privacyguard_core_native_1engine_RustBridge_processIpv4Packet(
    _env: JNIEnv,
    _class: JClass,
    raw_packet: JByteArray,
    len: jint,
) -> jint {
    let env = _env;
    let bytes = match env.convert_byte_array(raw_packet) {
        Ok(b) => b,
        Err(_) => return 0,
    };
    let limit = (len as usize).min(bytes.len());
    packet::classify_ipv4(&bytes[..limit])
}

/// Extract a JA3 fingerprint from raw TLS ClientHello bytes.
/// Returns the MD5 hex string, or null if parsing fails.
#[no_mangle]
pub extern "C" fn Java_com_privacyguard_core_native_1engine_RustBridge_computeJa3(
    env: JNIEnv,
    _class: JClass,
    client_hello: JByteArray,
) -> jstring {
    let bytes = match env.convert_byte_array(client_hello) {
        Ok(b) => b,
        Err(_) => return JObject::null().into_raw(),
    };
    match ja3::compute(&bytes) {
        Some(hash) => env
            .new_string(hash)
            .map(|s| s.into_raw())
            .unwrap_or_else(|_| JObject::null().into_raw()),
        None => JObject::null().into_raw(),
    }
}

/// Query whether a domain is present in the native bloom filter.
#[no_mangle]
pub extern "C" fn Java_com_privacyguard_core_native_1engine_RustBridge_bloomCheck(
    env: JNIEnv,
    _class: JClass,
    domain: JString,
) -> jboolean {
    let s: String = match env.get_string(&domain) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };
    let guard = BLOOM.lock().unwrap();
    guard.as_ref().map_or(0, |bf| bf.contains(&s) as jboolean)
}

/// Rebuild the bloom filter from a domain array.
#[no_mangle]
pub extern "C" fn Java_com_privacyguard_core_native_1engine_RustBridge_bloomRebuild(
    env: JNIEnv,
    _class: JClass,
    domains: JObjectArray,
) {
    let len = env.get_array_length(&domains).unwrap_or(0) as usize;
    let mut bf = BloomFilter::new(len.max(1_000_000));
    for i in 0..len {
        if let Ok(obj) = env.get_object_array_element(&domains, i as i32) {
            let jstr = JString::from(obj);
            if let Ok(s) = env.get_string(&jstr) {
                let domain: String = s.into();
                bf.insert(&domain);
            }
        }
    }
    *BLOOM.lock().unwrap() = Some(bf);
}

/// Compute Shannon entropy of a byte slice (used for DNS tunneling detection).
#[no_mangle]
pub extern "C" fn Java_com_privacyguard_core_native_1engine_RustBridge_shannonEntropy(
    env: JNIEnv,
    _class: JClass,
    data: JByteArray,
) -> jdouble {
    match env.convert_byte_array(data) {
        Ok(bytes) => entropy::shannon(&bytes),
        Err(_) => 0.0,
    }
}
