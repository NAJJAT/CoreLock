package com.privacyguard.vpn.interception

import android.content.Context
import android.util.Log
import com.privacyguard.app.data.db.AppDatabase
import com.privacyguard.data.repository.PayloadLogRepositoryImpl
import com.privacyguard.vpn.mitm.CaManager
import com.privacyguard.vpn.mitm.CertForger
import com.privacyguard.vpn.mitm.MitmConfig
import com.privacyguard.vpn.mitm.MitmEngine
import com.privacyguard.vpn.mitm.MitmTlsInterception
import com.privacyguard.vpn.mitm.PayloadParser
import com.privacyguard.vpn.mitm.PayloadShipper
import com.privacyguard.vpn.mitm.PiiRedactor
import com.privacyguard.vpn.mitm.PinningDetector
import java.net.Socket

/** Enterprise builds wire up the full MITM pipeline. */
object TlsInterceptionFactory {
    private const val TAG = "TlsInterceptionFactory"

    /**
     * Whether HTTPS payloads are being decrypted: inspection switched on and its
     * consent still valid, the same test [MitmTlsInterception] applies per connection.
     */
    fun isInspectionActive(context: Context): Boolean =
        MitmConfig(context).let { isInspectionActive(it.isEnabled, it.isConsentValid()) }

    /**
     * Onboarding's "HTTPS traffic inspection" box: the same consent the Payload screen
     * asks for. It does not switch inspection on.
     */
    fun recordConsent(context: Context) = MitmConfig(context).recordConsent()

    /** Decrypting only when switched on and consented to; either alone is not enough. */
    internal fun isInspectionActive(enabled: Boolean, consentValid: Boolean): Boolean = enabled && consentValid

    /**
     * Called from the VPN's background startup. The CA (a key that can sign for any
     * host) is only loaded or created when inspection is on and consented to; with
     * inspection off it does not exist at all. If inspection is switched on while the
     * VPN runs, [MitmTlsInterception] creates it on the first eligible connection.
     */
    suspend fun create(context: Context, db: AppDatabase, protect: (Socket) -> Boolean): TlsInterception {
        val caManager = CaManager(context)
        val mitmConfig = MitmConfig(context)
        if (mitmConfig.isEnabled && mitmConfig.isConsentValid()) {
            if (caManager.initialize()) Log.i(TAG, "CA ready")
            else Log.e(TAG, "CaManager.initialize() failed — HTTPS inspection unavailable")
        }

        val pinningDetector = PinningDetector()
        val payloadParser = PayloadParser(PiiRedactor())
        val mitmEngine = MitmEngine(CertForger(caManager), pinningDetector, caManager, payloadParser, protect)
        return MitmTlsInterception(
            mitmEngine,
            pinningDetector,
            mitmConfig,
            payloadParser,
            PayloadShipper.getInstance(context),
            PayloadLogRepositoryImpl(db.payloadLogDao()),
            ensureCa = { caManager.initialize() },
        )
    }
}
