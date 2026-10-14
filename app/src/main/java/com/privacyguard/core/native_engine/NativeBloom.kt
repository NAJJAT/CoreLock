package com.privacyguard.core.native_engine

/**
 * The native bloom filter DomainFilter uses as a fast "definitely not listed"
 * check. An interface so tests can run DomainFilter against the real Rust library
 * (or a stand-in) without the Android JNI loader.
 */
interface NativeBloom {
    val isAvailable: Boolean
    fun check(entry: String): Boolean
    fun rebuild(entries: Array<String>)

    /** The Rust library, when the .so is packaged. */
    object Rust : NativeBloom {
        override val isAvailable: Boolean get() = RustBridge.isAvailable
        override fun check(entry: String) = RustBridge.bloomCheck(entry)
        override fun rebuild(entries: Array<String>) = RustBridge.bloomRebuild(entries)
    }
}
