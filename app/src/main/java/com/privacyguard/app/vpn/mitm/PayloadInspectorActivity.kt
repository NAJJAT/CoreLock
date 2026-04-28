package com.privacyguard.app.vpn.mitm

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import com.privacyguard.app.data.db.AppDatabase // FIXED
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.json.JSONArray

class PayloadInspectorActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private lateinit var bridge: PayloadInspectorBridge

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val dao = AppDatabase.getInstance(this).payloadLogDao()
        bridge = PayloadInspectorBridge(dao, packageManager)

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            addJavascriptInterface(bridge, "Android")
            webViewClient = WebViewClient()
            loadUrl("file:///android_asset/payload_inspector.html")
        }
        setContentView(webView)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
        // Observe Room for changes and push the refreshed list to the WebView
        lifecycleScope.launch {
            dao.recentLogs(200).collectLatest { logs ->
                val json = JSONArray().also { arr ->
                    logs.forEach { arr.put(bridge.entityToJson(it)) }
                }.toString()
                webView.post {
                    webView.evaluateJavascript("window.refreshRequests($json)", null)
                }
            }
        }
    }
}

