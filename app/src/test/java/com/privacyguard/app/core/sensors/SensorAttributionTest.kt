package com.privacyguard.app.core.sensors

import com.privacyguard.app.core.sensors.SensorAttributor.OpRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SensorAttributionTest {

    private val self = "com.privacyguard.app.enterprise"
    private val camera = SensorUse(1, SensorType.CAMERA, startTime = 100_000, endTime = 107_000, source = "camera 0")

    private fun decide(
        ops: List<OpRecord>?,
        foreground: String?,
        holders: Set<String> = setOf("com.whatsapp", "com.sec.android.app.camera"),
    ) = SensorAttributor.decide(
        use = camera, now = 110_000, selfPackage = self, opRecords = ops,
        foregroundPackage = foreground, holdsPermission = { it in holders },
        candidates = { holders.toList() },
    )

    @Test
    fun appOpsRecordConfirmsTheApp() {
        val a = decide(listOf(OpRecord("com.sec.android.app.camera", running = false, lastAccess = 106_500)), foreground = null)
        assertEquals("com.sec.android.app.camera", a.packageName)
        assertEquals(Confidence.CONFIRMED, a.confidence)
    }

    @Test
    fun oldAppOpsRecordsDoNotCount() {
        val a = decide(listOf(OpRecord("com.limebike", running = false, lastAccess = 1_000)), foreground = null)
        assertEquals(Confidence.UNKNOWN, a.confidence)
        assertNull(a.packageName)
    }

    @Test
    fun severalAppsInTheRecordAreResolvedByTheScreen() {
        val ops = listOf(OpRecord("com.whatsapp", true, 101_000), OpRecord("com.example.flashlight", true, 101_000))
        assertEquals("com.whatsapp", decide(ops, foreground = "com.whatsapp").packageName)
        val unresolved = decide(ops, foreground = null)
        assertEquals(Confidence.UNKNOWN, unresolved.confidence)
        assertEquals(listOf("com.whatsapp", "com.example.flashlight"), unresolved.candidates)
    }

    @Test
    fun ourOwnRecordIsIgnored() {
        val a = decide(listOf(OpRecord(self, true, 101_000)), foreground = null)
        assertEquals(Confidence.UNKNOWN, a.confidence)
    }

    @Test
    fun appOnScreenWithPermissionIsLikelyNotConfirmed() {
        val a = decide(ops = null, foreground = "com.whatsapp")
        assertEquals("com.whatsapp", a.packageName)
        assertEquals(Confidence.LIKELY, a.confidence)
    }

    @Test
    fun appOnScreenWithoutPermissionIsNotBlamed() {
        val a = decide(ops = null, foreground = "com.example.game")
        assertNull(a.packageName)
        assertEquals(Confidence.UNKNOWN, a.confidence)
        assertEquals(2, a.candidates.size)
    }
}
