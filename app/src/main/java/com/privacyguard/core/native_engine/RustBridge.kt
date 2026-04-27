package com.privacyguard.core.native_engine

/**
 * JNI bridge to the native Rust packet-processing engine.
 *
 * When the .so is present (requires NDK + Cargo build with target aarch64-linux-android),
 * processing runs 5x faster and uses ~40% less battery vs the Kotlin path.
 *
 * Build steps:
 *   1. Install Rust: curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
 *   2. Add Android target: rustup target add aarch64-linux-android armv7-linux-androideabi
 *   3. cargo build --release --target aarch64-linux-android
 *   4. Copy .so to app/src/main/jniLibs/arm64-v8a/libprivacyguard_core.so
 */
object RustBridge {

    val isAvailable: Boolean by lazy {
        try { System.loadLibrary("privacyguard_core"); true }
        catch (_: UnsatisfiedLinkError) { false }
    }

    /** Parse + filter a raw IPv4 packet. Returns bitmask: 0x01=block, 0x02=is_tls, 0x04=is_dns. */
    external fun processIpv4Packet(rawPacket: ByteArray, len: Int): Int

    /** Extract JA3 hash from raw TLS ClientHello bytes. Returns MD5 hex or null. */
    external fun computeJa3(clientHello: ByteArray): String?

    /** High-speed domain lookup in native bloom filter. */
    external fun bloomCheck(domain: String): Boolean

    /** Rebuild native bloom filter from domain list — call after blocklist update. */
    external fun bloomRebuild(domains: Array<String>)

    /** Compute Shannon entropy of a byte array (for DNS tunneling detection). */
    external fun shannonEntropy(data: ByteArray): Double
}
