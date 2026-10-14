package com.privacyguard.vpn.firewall

import com.privacyguard.core.native_engine.NativeBloom
import com.privacyguard.core.native_engine.RustBridge
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Regression for R-1 (security test 2026-10-14): with the native bloom filter
 * present, "*.example.com" did not block "example.com" itself, and a name added
 * with addDomain after a rebuild was skipped by the fast path.
 */
class DomainFilterNativeBloomTest {

    /** Exact stand-in for the native filter (no false positives, like a perfect bloom). */
    private class SetBloom : NativeBloom {
        val entries = HashSet<String>()
        override val isAvailable = true
        override fun check(entry: String) = entry in entries
        override fun rebuild(entries: Array<String>) { this.entries.clear(); this.entries.addAll(entries) }
    }

    private fun assertBlocking(f: DomainFilter) {
        f.rebuild(listOf("*.example.com", "ads.test"))
        assertTrue("*.example.com must block example.com", f.isBlocked("example.com"))
        assertTrue(f.isBlocked("a.b.example.com"))
        assertTrue(f.isBlocked("ads.test"))
        assertTrue(f.isBlocked("x.ads.test"))
        assertFalse(f.isBlocked("example.org"))
        assertFalse(f.isBlocked("notexample.com"))
        f.addDomain("late.example.net")
        assertTrue("added after the rebuild", f.isBlocked("late.example.net"))
        assertTrue(f.isBlocked("cdn.late.example.net"))
    }

    @Test fun wildcard_and_added_entries_block_with_a_native_bloom() = assertBlocking(DomainFilter(nativeBloom = SetBloom()))

    /** The real Rust filter, through the real JNI functions, when the host library is built. */
    @Test fun wildcard_and_added_entries_block_with_the_real_rust_library() {
        val name = System.mapLibraryName("privacyguard_core")
        val lib = listOfNotNull(System.getenv("PG_NATIVE_LIB"), "../rust/target/release/$name", "../rust/target/debug/$name")
            .map(::File).firstOrNull { it.isFile }
        assumeTrue("build it with: cargo build --release -p privacyguard-core (in rust/)", lib != null)
        System.load(lib!!.absolutePath)
        val rust = object : NativeBloom {
            override val isAvailable = true
            override fun check(entry: String) = RustBridge.bloomCheck(entry)
            override fun rebuild(entries: Array<String>) = RustBridge.bloomRebuild(entries)
        }
        assertBlocking(DomainFilter(nativeBloom = rust))
    }
}
