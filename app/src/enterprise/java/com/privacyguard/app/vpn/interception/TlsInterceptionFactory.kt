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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.net.Socket

/** Learned pins in SharedPreferences, so a VPN restart does not re-learn them by failing. */
private class PrefsPinStore(context: Context) : PinningDetector.Store {
    private val prefs = context.getSharedPreferences("mitm_pins", Context.MODE_PRIVATE)
    override fun load() =
        prefs.getStringSet("domains", null).orEmpty().toSet() to prefs.getStringSet("packages", null).orEmpty().toSet()
    override fun save(domains: Set<String>, packages: Set<String>) {
        prefs.edit().putStringSet("domains", domains).putStringSet("packages", packages).apply()
    }
}

/** Enterprise builds wire up the full MITM pipeline. */
object TlsInterceptionFactory {
    private const val TAG = "TlsInterceptionFactory"

    fun create(context: Context, db: AppDatabase, protect: (Socket) -> Boolean): TlsInterception {
        val caManager = CaManager(context)
        // Initialize the CA before the forwarder starts so the first HTTPS
        // session cannot race ahead of certificate generation.
        val caReady = runBlocking(Dispatchers.IO) { caManager.initialize() }
        if (caReady) {
            val cert = caManager.getCaCert()
            Log.i(TAG, "CaManager ready — cert=${cert?.encoded?.size}B subject=${cert?.subjectDN}")
        } else {
            Log.e(TAG, "CaManager.initialize() failed — MITM payload decryption will be unavailable")
        }

        val pinningDetector = PinningDetector(PrefsPinStore(context))
        val mitmConfig = MitmConfig(context)
        val payloadParser = PayloadParser(PiiRedactor())
        val mitmEngine = MitmEngine(CertForger(caManager), pinningDetector, caManager, payloadParser, protect)
        return MitmTlsInterception(
            mitmEngine,
            pinningDetector,
            mitmConfig,
            payloadParser,
            PayloadShipper(mitmConfig),
            PayloadLogRepositoryImpl(db.payloadLogDao()),
        )
    }
}
