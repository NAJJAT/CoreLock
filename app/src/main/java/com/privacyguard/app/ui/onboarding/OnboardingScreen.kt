package com.privacyguard.app.ui.onboarding

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.privacyguard.app.ui.theme.*
import kotlinx.coroutines.launch

private data class OnboardingPage(
    val icon: ImageVector,
    val iconTint: androidx.compose.ui.graphics.Color,
    val title: String,
    val body: String,
    val bullets: List<String> = emptyList(),
)

private val infoPages = listOf(
    OnboardingPage(
        icon     = Icons.Default.Shield,
        iconTint = PgAccent,
        title    = "Your device. Your rules.",
        body     = "PrivacyGuard runs a local VPN to inspect every connection — no traffic leaves your device unmonitored.",
        bullets  = listOf(
            "Zero-knowledge inspection — no payload decryption by default",
            "Works entirely on-device, no cloud backend",
            "Kill switch locks network if VPN drops",
        ),
    ),
    OnboardingPage(
        icon     = Icons.Default.Fingerprint,
        iconTint = PgDanger,
        title    = "What it detects",
        body     = "Multiple analysis layers surface threats your device wouldn't normally show you.",
        bullets  = listOf(
            "JA3 TLS fingerprints matched against 35+ malware signatures",
            "DNS tunneling and DGA domain detection",
            "Tracker SDK identification from APK metadata",
            "Background app network activity",
            "Cleartext (unencrypted) connections",
        ),
    ),
    OnboardingPage(
        icon     = Icons.Default.Visibility,
        iconTint = PgWarning,
        title    = "What you control",
        body     = "Block anything — per app, per domain, per IP, or globally. Rules take effect instantly.",
        bullets  = listOf(
            "Block specific apps, domains, or IPs",
            "Block all cleartext or weak TLS globally",
            "Suggested rules based on observed behaviour",
            "Export logs as PCAP, CSV, or IT report",
        ),
    ),
)

// Total pages = info pages + consent page
private val TOTAL_PAGES = infoPages.size + 1
private val CONSENT_PAGE_INDEX = infoPages.size

@Composable
fun OnboardingScreen(
    onGetStarted: (tlsConsentGiven: Boolean) -> Unit,
    onEnableVpn: (tlsConsentGiven: Boolean) -> Unit,
) {
    val pagerState = rememberPagerState { TOTAL_PAGES }
    val scope = rememberCoroutineScope()

    var vpnAccepted by remember { mutableStateOf(false) }
    var tlsAccepted by remember { mutableStateOf(false) }
    var localStorageAck by remember { mutableStateOf(false) }

    val isConsentPage = pagerState.currentPage == CONSENT_PAGE_INDEX
    val canProceed = vpnAccepted && localStorageAck

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(bottom = 32.dp),
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f),
            userScrollEnabled = !isConsentPage,  // lock scroll on consent to force reading
        ) { pageIndex ->
            if (pageIndex < infoPages.size) {
                InfoPageContent(page = infoPages[pageIndex])
            } else {
                ConsentPageContent(
                    vpnAccepted       = vpnAccepted,
                    onVpnAccepted     = { vpnAccepted = it },
                    tlsAccepted       = tlsAccepted,
                    onTlsAccepted     = { tlsAccepted = it },
                    localStorageAck   = localStorageAck,
                    onLocalStorageAck = { localStorageAck = it },
                )
            }
        }

        // Dot indicator
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
        ) {
            (0 until TOTAL_PAGES).forEach { i ->
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

        Column(
            modifier = Modifier.padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (isConsentPage) {
                Button(
                    onClick = { if (canProceed) onEnableVpn(tlsAccepted) },
                    enabled = canProceed,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text("I understand — start monitoring", fontWeight = FontWeight.SemiBold)
                }
                TextButton(
                    onClick = { if (canProceed) onGetStarted(tlsAccepted) },
                    enabled = canProceed,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Continue without enabling VPN", color = if (canProceed) PgTextMuted else PgTextFaint)
                }
            } else {
                Button(
                    onClick = {
                        scope.launch {
                            pagerState.animateScrollToPage(pagerState.currentPage + 1)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text("Next", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun ConsentPageContent(
    vpnAccepted: Boolean,       onVpnAccepted: (Boolean) -> Unit,
    tlsAccepted: Boolean,       onTlsAccepted: (Boolean) -> Unit,
    localStorageAck: Boolean,   onLocalStorageAck: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 24.dp),
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .background(PgInfo.copy(alpha = 0.12f), CircleShape)
                .align(Alignment.CenterHorizontally),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.VerifiedUser, contentDescription = null,
                tint = PgInfo, modifier = Modifier.size(36.dp))
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            "What this app does",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = PgText,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Please read and confirm each item before continuing.",
            style = MaterialTheme.typography.bodyMedium,
            color = PgTextMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 20.dp),
        )

        ConsentItem(
            checked   = vpnAccepted,
            onChecked = onVpnAccepted,
            required  = true,
            title     = "Network monitoring",
            body      = "A local VPN is created on this device. All network traffic passes through it " +
                        "so the app can show you which apps are communicating and with whom. " +
                        "No data leaves your device.",
        )

        Spacer(modifier = Modifier.height(12.dp))

        ConsentItem(
            checked   = tlsAccepted,
            onChecked = onTlsAccepted,
            required  = false,
            title     = "HTTPS traffic inspection (optional)",
            body      = "To read encrypted traffic contents, the app acts as a local HTTPS proxy. " +
                        "This requires installing a security certificate. The certificate lives " +
                        "only on this device and is deleted when you uninstall the app. " +
                        "You can use the app without this feature.",
        )

        Spacer(modifier = Modifier.height(12.dp))

        ConsentItem(
            checked   = localStorageAck,
            onChecked = onLocalStorageAck,
            required  = true,
            title     = "Local storage",
            body      = "Traffic logs are stored in the app's private storage on this device only. " +
                        "They are never uploaded anywhere. You can delete them at any time from Settings.",
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            "Required items must be checked to continue.",
            style = MaterialTheme.typography.labelSmall,
            color = PgTextFaint,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun ConsentItem(
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
    required: Boolean,
    title: String,
    body: String,
) {
    val borderColor = when {
        checked  -> PgAccent
        required -> PgDanger.copy(alpha = 0.4f)
        else     -> PgBorder
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (checked) PgAccent.copy(alpha = 0.06f) else MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChecked(!checked) },
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Checkbox(
                checked = checked,
                onCheckedChange = onChecked,
                colors = CheckboxDefaults.colors(
                    checkedColor = PgAccent,
                    uncheckedColor = if (required) PgDanger else PgTextMuted,
                ),
            )
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(title, fontWeight = FontWeight.SemiBold, color = PgText,
                        style = MaterialTheme.typography.bodyMedium)
                    if (required) {
                        Text("Required", style = MaterialTheme.typography.labelSmall,
                            color = PgDanger)
                    } else {
                        Text("Optional", style = MaterialTheme.typography.labelSmall,
                            color = PgTextFaint)
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(body, style = MaterialTheme.typography.bodySmall, color = PgTextMuted)
            }
        }
    }
}

@Composable
private fun InfoPageContent(page: OnboardingPage) {
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
            Icon(page.icon, contentDescription = null,
                tint = page.iconTint, modifier = Modifier.size(44.dp))
        }

        Spacer(modifier = Modifier.height(28.dp))

        Text(page.title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold, color = PgText, textAlign = TextAlign.Center)

        Spacer(modifier = Modifier.height(12.dp))

        Text(page.body,
            style = MaterialTheme.typography.bodyLarge,
            color = PgTextMuted, textAlign = TextAlign.Center)

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
                        Text(bullet,
                            style = MaterialTheme.typography.bodyMedium, color = PgTextMuted)
                    }
                }
            }
        }
    }
}
