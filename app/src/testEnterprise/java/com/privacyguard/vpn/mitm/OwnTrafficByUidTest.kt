package com.privacyguard.vpn.mitm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regression for V-11 (security test 2026-10-14): any package whose name started
 * with "com.privacyguard.app" was intercepted but never logged. Only our own UID
 * is exempt now.
 */
class OwnTrafficByUidTest {

    private fun interceptionWithUid(uid: Int): MitmTlsInterception {
        val unsafe = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null) as sun.misc.Unsafe
        val obj = unsafe.allocateInstance(MitmTlsInterception::class.java) as MitmTlsInterception
        MitmTlsInterception::class.java.getDeclaredField("ownUid").apply { isAccessible = true }.setInt(obj, uid)
        return obj
    }

    @Test
    fun only_our_exact_uid_is_exempt() {
        val i = interceptionWithUid(10_123)
        assertTrue(i.isPrivacyGuardTraffic(10_123))
        assertFalse(i.isPrivacyGuardTraffic(10_124))
        assertFalse(i.isPrivacyGuardTraffic(-1))
    }

    @Test
    fun no_package_name_prefix_check_is_left() {
        val src = File("src/enterprise/java/com/privacyguard/vpn/mitm/MitmTlsInterception.kt").readText()
        assertFalse(src.contains("startsWith(\"com.privacyguard"))
        assertFalse(src.contains("ownerPackage == BuildConfig.APPLICATION_ID"))
    }
}
