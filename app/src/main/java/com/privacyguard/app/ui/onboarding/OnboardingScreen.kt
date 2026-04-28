package com.privacyguard.app.ui.onboarding

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.privacyguard.app.ui.theme.PgAccent
import com.privacyguard.app.ui.theme.PgAccentDim
import com.privacyguard.app.ui.theme.PgDanger
import com.privacyguard.app.ui.theme.PgInfo
import com.privacyguard.app.ui.theme.PgText
import com.privacyguard.app.ui.theme.PgTextFaint
import com.privacyguard.app.ui.theme.PgTextMuted
import com.privacyguard.app.ui.theme.PgWarning
import kotlinx.coroutines.launch

private data class OnboardingPage(
    val icon: ImageVector,
    val iconTint: androidx.compose.ui.graphics.Color,
    val title: String,
    val body: String,
    val bullets: List<String> = emptyList(),
)

private val pages = listOf(
    OnboardingPage(
        icon      = Icons.Default.Shield,
        iconTint  = PgAccent,
        title     = "Your device. Your rules.",
        body      = "PrivacyGuard runs a local VPN to inspect every connection — no traffic leaves your device unmonitored.",
        bullets   = listOf(
            "Zero-knowledge inspection — no payload decryption",
            "Works entirely on-device, no cloud backend",
            "Kill switch locks network if VPN drops",
        ),
    ),
    OnboardingPage(
        icon      = Icons.Default.Fingerprint,
        iconTint  = PgDanger,
        title     = "What it detects",
        body      = "PrivacyGuard uses multiple layers of analysis to surface threats your device wouldn't normally show you.",
        bullets   = listOf(
            "JA3 TLS fingerprints matched against 35+ malware signatures",
            "DNS tunneling and DGA domain detection",
            "Tracker SDK identification from APK metadata",
            "Background app network activity",
            "Cleartext (unencrypted) connections",
        ),
    ),
    OnboardingPage(
        icon      = Icons.Default.Visibility,
        iconTint  = PgWarning,
        title     = "What you control",
        body      = "Block anything — per app, per domain, per IP, or globally. Rules take effect instantly without restarting.",
        bullets   = listOf(
            "Block specific apps, domains, or IPs",
            "Block all cleartext or weak TLS globally",
            "Suggested rules based on observed behaviour",
            "Export logs as PCAP, CSV, or IT report",
        ),
    ),
    OnboardingPage(
        icon      = Icons.Default.Security,
        iconTint  = PgInfo,
        title     = "Ready to start",
        body      = "Tap 'Enable Protection' to start the VPN. You can change everything later in Settings.",
        bullets   = emptyList(),
    ),
)

@Composable
fun OnboardingScreen(
    onGetStarted: () -> Unit,
    onEnableVpn: () -> Unit,
) {
    val pagerState = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()
    val isLast = pagerState.currentPage == pages.lastIndex

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(bottom = 32.dp),
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f),
        ) { pageIndex ->
            PageContent(page = pages[pageIndex])
        }

        // Dot indicator
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
        ) {
            pages.indices.forEach { i ->
                val color by animateColorAsState(
                    if (pagerState.currentPage == i) PgAccent else PgTextFaint,
                    label = "dot",
                )
                Box(
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .size(if (pagerState.currentPage == i) 8.dp else 6.dp)
                        .background(color, CircleShape),
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Action buttons
        Column(modifier = Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (isLast) {
                Button(
                    onClick = onEnableVpn,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text("Enable Protection", fontWeight = FontWeight.SemiBold)
                }
                TextButton(
                    onClick = onGetStarted,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Continue without VPN", color = PgTextMuted)
                }
            } else {
                Button(
                    onClick = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) } },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text("Next", fontWeight = FontWeight.SemiBold)
                }
                TextButton(
                    onClick = onGetStarted,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Skip", color = PgTextFaint)
                }
            }
        }
    }
}

@Composable
private fun PageContent(page: OnboardingPage) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(88.dp)
                .background(page.iconTint.copy(alpha = 0.12f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = page.icon,
                contentDescription = null,
                tint = page.iconTint,
                modifier = Modifier.size(44.dp),
            )
        }

        Spacer(modifier = Modifier.height(28.dp))

        Text(
            page.title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = PgText,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            page.body,
            style = MaterialTheme.typography.bodyLarge,
            color = PgTextMuted,
            textAlign = TextAlign.Center,
        )

        if (page.bullets.isNotEmpty()) {
            Spacer(modifier = Modifier.height(24.dp))
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                page.bullets.forEach { bullet ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Box(
                            modifier = Modifier
                                .padding(top = 6.dp)
                                .size(6.dp)
                                .background(page.iconTint, CircleShape),
                        )
                        Text(
                            bullet,
                            style = MaterialTheme.typography.bodyMedium,
                            color = PgTextMuted,
                        )
                    }
                }
            }
        }
    }
}
