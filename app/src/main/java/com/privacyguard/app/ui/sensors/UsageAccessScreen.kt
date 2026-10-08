package com.privacyguard.app.ui.sensors

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.privacyguard.app.core.sensors.SensorAttributor
import com.privacyguard.app.ui.components.PanelCard
import com.privacyguard.app.ui.components.ScreenScaffold
import com.privacyguard.app.ui.components.SectionLabel
import com.privacyguard.app.ui.components.StatusPill
import com.privacyguard.app.ui.theme.PgAccent
import com.privacyguard.app.ui.theme.PgAccentDim
import com.privacyguard.app.ui.theme.PgText
import com.privacyguard.app.ui.theme.PgTextMuted
import com.privacyguard.app.ui.theme.PgWarning
import com.privacyguard.app.ui.theme.PgWarningDim

/**
 * Opt-in for Usage Access, which lets Camera & Mic Watch say which app was on
 * screen when the camera or microphone switched on. Explains what is read and
 * what is not, then hands over to the system setting.
 */
@Composable
fun UsageAccessScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val attributor = remember(context) { SensorAttributor(context) }
    var granted by remember { mutableStateOf(attributor.hasUsageAccess()) }
    val advanced = remember { attributor.hasAdvancedMode() }

    // Re-check when the user comes back from the system setting.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) granted = attributor.hasUsageAccess()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    ScreenScaffold(
        title = "Which app was it?",
        subtitle = "Camera & Mic Watch",
        badge = if (granted) "ON" else "OFF",
    ) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PanelCard {
                SectionLabel("Why we ask")
                Spacer(Modifier.height(8.dp))
                Text(
                    "CoreLock detects camera and microphone use through Android's system signals. " +
                        "Android does not say which app is using them, so on its own CoreLock can only tell you " +
                        "that something did.",
                    style = MaterialTheme.typography.bodyMedium, color = PgText,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "With Usage Access, CoreLock checks which app was on screen at that moment. " +
                        "If it holds the camera or microphone permission, the alert names it as the likely app.",
                    style = MaterialTheme.typography.bodyMedium, color = PgText,
                )
            }
            PanelCard {
                SectionLabel("What it reads — and what it doesn't")
                Spacer(Modifier.height(8.dp))
                Text(
                    "• Which app was opened and when\n" +
                        "• Nothing inside apps: no messages, no screen contents, no keystrokes\n" +
                        "• Stays on this phone; nothing is uploaded",
                    style = MaterialTheme.typography.bodyMedium, color = PgTextMuted,
                )
            }
            PanelCard {
                StatusPill(
                    if (granted) "Usage Access is on" else "Usage Access is off",
                    if (granted) PgAccentDim else PgWarningDim,
                    if (granted) PgAccent else PgWarning,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    if (advanced) "Advanced mode is on: the system's own record names the app exactly."
                    else "Without Usage Access, alerts say \"an app\" and list the apps that could have done it.",
                    style = MaterialTheme.typography.bodySmall, color = PgTextMuted,
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                            .setData(Uri.fromParts("package", context.packageName, null))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        // Some devices reject the package-specific form; fall back to the list.
                        runCatching { context.startActivity(intent) }.onFailure {
                            context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = PgAccentDim, contentColor = PgAccent),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (granted) "Open Usage Access settings" else "Turn on Usage Access") }
                TextButton(onClick = onDone, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Text(if (granted) "Done" else "Not now", color = PgTextMuted)
                }
            }
        }
    }
}
