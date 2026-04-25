package com.privacyguard.app.core.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Debug
import android.util.Log
import com.privacyguard.app.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.security.MessageDigest

enum class SecurityRiskLevel {
    LOW,
    ELEVATED,
    HIGH,
}

data class SecurityPosture(
    val debuggerAttached: Boolean = false,
    val rooted: Boolean = false,
    val suspiciousPackages: List<String> = emptyList(),
    val signatureValid: Boolean = true,
    val hardwareBackedKeystore: Boolean = false,
    val buildTagsRisk: Boolean = false,
    val riskLevel: SecurityRiskLevel = SecurityRiskLevel.LOW,
    val summary: String = "Trusted runtime",
)

object AppSecurityMonitor {
    private const val TAG = "AppSecurityMonitor"

    private val suspiciousPackages = listOf(
        "com.topjohnwu.magisk",
        "eu.chainfire.supersu",
        "com.koushikdutta.superuser",
        "com.thirdparty.superuser",
        "com.devadvance.rootcloak2",
        "de.robv.android.xposed.installer",
    )

    private val rootIndicators = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/system/app/Superuser.apk",
        "/data/local/su",
        "/data/local/bin/su",
        "/system/bin/.ext/su",
        "/system/usr/we-need-root/su",
        "/cache/su",
        "/data/adb/magisk",
    )

    private val _state = MutableStateFlow(SecurityPosture())
    val state: StateFlow<SecurityPosture> = _state.asStateFlow()

    fun refresh(context: Context): SecurityPosture {
        val debuggerAttached = Debug.isDebuggerConnected() || Debug.waitingForDebugger()
        val rooted = rootIndicators.any { path -> File(path).exists() }
        val buildTagsRisk = Build.TAGS?.contains("test-keys") == true
        val suspicious = suspiciousPackages.filter { pkg ->
            runCatching {
                context.packageManager.getPackageInfo(pkg, 0)
                true
            }.getOrDefault(false)
        }
        val signatureValid = verifySignature(context)
        val hardwareBackedKeystore = readHardwareBackedKeystore(context)
        val riskLevel = when {
            !signatureValid -> SecurityRiskLevel.HIGH
            debuggerAttached || rooted || suspicious.isNotEmpty() -> SecurityRiskLevel.HIGH
            buildTagsRisk || !hardwareBackedKeystore -> SecurityRiskLevel.ELEVATED
            else -> SecurityRiskLevel.LOW
        }
        val summary = when (riskLevel) {
            SecurityRiskLevel.LOW -> "Trusted runtime"
            SecurityRiskLevel.ELEVATED -> "Hardened, but hardware/integrity guarantees are reduced"
            SecurityRiskLevel.HIGH -> "Tamper signals detected"
        }
        return SecurityPosture(
            debuggerAttached = debuggerAttached,
            rooted = rooted,
            suspiciousPackages = suspicious,
            signatureValid = signatureValid,
            hardwareBackedKeystore = hardwareBackedKeystore,
            buildTagsRisk = buildTagsRisk,
            riskLevel = riskLevel,
            summary = summary,
        ).also {
            _state.value = it
            Log.i(TAG, "Security posture=${it.riskLevel} rooted=${it.rooted} debugger=${it.debuggerAttached} signatureValid=${it.signatureValid}")
        }
    }

    private fun verifySignature(context: Context): Boolean {
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        }
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.signingInfo?.apkContentsSigners?.map { it.toByteArray() }.orEmpty()
        } else {
            @Suppress("DEPRECATION")
            packageInfo.signatures?.map { it.toByteArray() }.orEmpty()
        }
        if (signatures.isEmpty()) return false
        val expected = BuildConfig.APP_SIGNATURE_SHA256
        if (expected.isBlank()) return true
        val normalized = signatures.map { bytes ->
            MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { part -> "%02X".format(part) }
        }
        return normalized.any { it == expected }
    }

    private fun readHardwareBackedKeystore(context: Context): Boolean {
        return try {
            com.privacyguard.app.core.security.SecureSecretStore.getInstance(context).isHardwareBacked()
        } catch (_: Exception) {
            false
        }
    }
}
