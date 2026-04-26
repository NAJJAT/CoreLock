package com.privacyguard.app.vpn.mitm

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.privacyguard.app.data.db.AppDatabase // FIXED
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.json.JSONArray

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PayloadInspectorScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = remember { AppDatabase.getInstance(context).payloadLogDao() }
    val bridge = remember { PayloadInspectorBridge(dao, context.packageManager) }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                addJavascriptInterface(bridge, "Android")
                webViewClient = WebViewClient()
                loadUrl("file:///android_asset/payload_inspector.html")

                // Observe Room; push full refreshed list to the HTML on every change
                scope.launch {
                    dao.recentLogs(200).collectLatest { logs ->
                        val json = JSONArray().also { arr ->
                            logs.forEach { arr.put(bridge.entityToJson(it)) }
                        }.toString()
                        post {
                            evaluateJavascript("window.refreshRequests($json)", null)
                        }
                    }
                }
            }
        }
    )
}
