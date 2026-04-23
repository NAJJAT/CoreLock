package com.privacyguard

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.privacyguard.ui.theme.PrivacyGuardTheme
import com.privacyguard.ui.AppNavHost
import com.privacyguard.vpn.VpnManager

class MainActivity : BaseActivity() {

    private lateinit var vpnManager: VpnManager

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) vpnManager.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vpnManager = VpnManager(applicationContext)
        setContent {
            PrivacyGuardTheme {
                AppNavHost(
                    onRequestVpn = { requestVpnPermission() },
                )
            }
        }
    }

    private fun requestVpnPermission() {
        val intent = VpnService.prepare(this)
        if (intent != null) vpnPermissionLauncher.launch(intent)
        else vpnManager.start()
    }
}