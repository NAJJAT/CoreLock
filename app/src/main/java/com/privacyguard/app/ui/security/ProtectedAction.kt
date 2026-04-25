package com.privacyguard.app.ui.security

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.privacyguard.app.core.security.AppSecurityMonitor
import com.privacyguard.app.core.security.SensitiveActionPolicy
import com.privacyguard.app.core.security.SecurityActionPolicy

private data class PendingProtectedAction(
    val label: String,
    val onApproved: () -> Unit,
)

@Composable
fun rememberProtectedActionRunner(): (String, String, () -> Unit) -> Unit {
    val context = LocalContext.current
    var pendingAction by remember { mutableStateOf<PendingProtectedAction?>(null) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val action = pendingAction
        pendingAction = null
        if (result.resultCode == Activity.RESULT_OK) {
            action?.onApproved?.invoke()
        } else if (action != null) {
            Toast.makeText(context, "${action.label} canceled", Toast.LENGTH_SHORT).show()
        }
    }

    return remember(context, launcher) {
        fun runProtected(title: String, subtitle: String, onApproved: () -> Unit) {
            val posture = AppSecurityMonitor.refresh(context)
            val keyguardManager = context.getSystemService(KeyguardManager::class.java)
            val decision = SecurityActionPolicy.decide(
                posture = posture,
                deviceSecure = keyguardManager?.isDeviceSecure == true,
            )
            if (decision.policy == SensitiveActionPolicy.BLOCK) {
                Toast.makeText(context, decision.message, Toast.LENGTH_LONG).show()
                return
            }

            if (decision.policy == SensitiveActionPolicy.ALLOW) {
                onApproved()
                return
            }

            val activity = context.findActivity()
            val intent = keyguardManager?.createConfirmDeviceCredentialIntent(title, subtitle)
            if (activity == null || intent == null) {
                Toast.makeText(context, "Device authentication is not available for this action", Toast.LENGTH_LONG).show()
                return
            }

            pendingAction = PendingProtectedAction(title, onApproved)
            launcher.launch(intent)
        }
        ::runProtected
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
