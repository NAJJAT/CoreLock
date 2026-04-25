package com.privacyguard.app.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.privacyguard.app.ui.components.PanelCard
import com.privacyguard.app.ui.theme.PgAccent
import com.privacyguard.app.ui.theme.PgTextMuted

@Composable
fun OnboardingScreen(
    onGetStarted: () -> Unit,
    onEnableVpn: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("PrivacyGuard", style = MaterialTheme.typography.displaySmall, color = PgAccent)
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            "Private DNS shield, live connection visibility, and per-app protection in one place.",
            style = MaterialTheme.typography.bodyLarge,
            color = PgTextMuted
        )
        Spacer(modifier = Modifier.height(24.dp))

        PanelCard(modifier = Modifier.fillMaxWidth()) {
            Text("1. Start the VPN tunnel to inspect live traffic.", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Text("2. Review suspicious connections, risky apps, and DNS anomalies.", style = MaterialTheme.typography.bodyMedium, color = PgTextMuted)
            Spacer(modifier = Modifier.height(8.dp))
            Text("3. Turn on cleartext blocking, weak TLS blocking, and the kill switch in Settings.", style = MaterialTheme.typography.bodyMedium, color = PgTextMuted)
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(onClick = onEnableVpn, modifier = Modifier.fillMaxWidth()) {
            Text("Enable Protection")
        }
        Spacer(modifier = Modifier.height(12.dp))
        Button(onClick = onGetStarted, modifier = Modifier.fillMaxWidth()) {
            Text("Continue to Dashboard")
        }
    }
}
