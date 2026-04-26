package com.privacyguard.ui.mitm

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.privacyguard.app.data.db.PayloadLogEntity
import com.privacyguard.app.data.db.AppDatabase
// FIXED: replaced invalid package import with concrete type import.
import com.privacyguard.app.data.repository.PayloadLogRepositoryImpl
import com.privacyguard.domain.repository.PayloadLogRepository
import com.privacyguard.vpn.mitm.MitmConfig
import com.privacyguard.vpn.mitm.PayloadShipper
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MitmScreen() {
    val context = LocalContext.current

    // Create dependencies
    val mitmConfig = MitmConfig(context)
    val database = AppDatabase.getInstance(context)
    val repository: PayloadLogRepository = PayloadLogRepositoryImpl(database.payloadLogDao())
    val payloadShipper = PayloadShipper(mitmConfig)

    val viewModel: MitmViewModel = viewModel(
        factory = MitmViewModelFactory(mitmConfig, repository, payloadShipper)
    )

    val uiState by viewModel.uiState.collectAsState()
    var showConsentDialog by remember { mutableStateOf(false) }
    var selectedPayload by remember { mutableStateOf<PayloadLogEntity?>(null) }

    LaunchedEffect(uiState.isEnabled, uiState.isConsentValid) {
        if (uiState.isEnabled && !uiState.isConsentValid) {
            showConsentDialog = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Payload Inspection", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                actions = {
                    IconButton(onClick = { viewModel.shipNow() }) {
                        Badge(
                            containerColor = MaterialTheme.colorScheme.primary
                        ) {
                            Text(uiState.pendingEvents.toString())
                        }
                        Icon(Icons.Default.Send, contentDescription = "Ship to SIEM")
                    }
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Status Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (uiState.isEnabled)
                        MaterialTheme.colorScheme.primaryContainer
                    else
                        MaterialTheme.colorScheme.surfaceVariant
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = if (uiState.isEnabled) "🔒 MITM Active" else "⛔ MITM Disabled",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Pending events: ${uiState.pendingEvents}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = uiState.isEnabled,
                        onCheckedChange = { enabled ->
                            if (enabled && !uiState.isConsentValid) {
                                showConsentDialog = true
                            } else if (enabled) {
                                viewModel.enableMitm()
                            } else {
                                viewModel.disableMitm()
                            }
                        }
                    )
                }
            }

            if (uiState.isEnabled) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = when (uiState.mitmStatus) {
                            "ERROR", "HANDSHAKE_FAILED" -> MaterialTheme.colorScheme.errorContainer
                            "PINNED_BYPASS" -> MaterialTheme.colorScheme.tertiaryContainer
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        }
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Text(
                            text = "MITM Status: ${uiState.mitmStatus}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = uiState.mitmStatusMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        uiState.mitmStatusDomain?.let { domain ->
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Domain: $domain",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Filter Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = uiState.filterDirection == "OUTBOUND",
                    onClick = {
                        viewModel.setFilterDirection(
                            if (uiState.filterDirection == "OUTBOUND") null else "OUTBOUND"
                        )
                    },
                    label = { Text("↑ OUTBOUND") },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = uiState.filterDirection == "INBOUND",
                    onClick = {
                        viewModel.setFilterDirection(
                            if (uiState.filterDirection == "INBOUND") null else "INBOUND"
                        )
                    },
                    label = { Text("↓ INBOUND") },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = uiState.filterHasBody,
                    onClick = { viewModel.setFilterHasBody(!uiState.filterHasBody) },
                    label = { Text("📄 Has Body") },
                    modifier = Modifier.weight(1f)
                )
            }

            // Search Field
            OutlinedTextField(
                value = uiState.searchQuery,
                onValueChange = { viewModel.setSearchQuery(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                placeholder = { Text("Search by hostname, package, or URL...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )

            // Payload List
            if (uiState.recentLogs.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Wifi,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "No payloads intercepted yet",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Enable MITM and browse HTTPS websites",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(uiState.recentLogs) { payload ->
                        PayloadListItem(
                            payload = payload,
                            onClick = { selectedPayload = payload }
                        )
                    }
                }
            }
        }
    }

    // Consent Dialog
    if (showConsentDialog) {
        AlertDialog(
            onDismissRequest = { showConsentDialog = false },
            title = { Text("⚠️ Legal Consent Required") },
            text = {
                Text(
                    "This feature intercepts and logs all HTTPS traffic including " +
                            "usernames, passwords, messages, and personal data.\n\n" +
                            "It must only be enabled on company-managed devices with " +
                            "employee written consent.\n\n" +
                            "By enabling this feature you confirm that all legal requirements " +
                            "for employee monitoring have been met."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.recordConsent()
                        showConsentDialog = false
                    }
                ) {
                    Text("✅ I Confirm — Enable")
                }
            },
            dismissButton = {
                TextButton(onClick = { showConsentDialog = false }) {
                    Text("❌ Cancel")
                }
            }
        )
    }

    // Detail Bottom Sheet
    selectedPayload?.let { payload ->
        InlinePayloadDetailSheet(
            payload = payload,
            onDismiss = { selectedPayload = null }
        )
    }
}

@Composable
fun PayloadListItem(
    payload: PayloadLogEntity,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (payload.direction == "OUTBOUND") "↑" else "↓",
                        fontSize = 20.sp,
                        color = if (payload.direction == "OUTBOUND")
                            Color(0xFFE53935)
                        else
                            Color(0xFF43A047)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = payload.sniHostname ?: payload.destinationIp,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium
                    )
                }
                Text(
                    text = formatRelativeTime(payload.timestamp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AssistChip(
                    onClick = { },
                    label = { Text(payload.protocol) },
                    modifier = Modifier.height(28.dp)
                )
                payload.method?.let {
                    AssistChip(
                        onClick = { },
                        label = { Text(it) },
                        modifier = Modifier.height(28.dp)
                    )
                }
                Text(
                    text = formatBytes(payload.sizeBytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            payload.urlPath?.let {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = it.take(60),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1
                )
            }

            payload.body?.let {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = it.take(80),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2
                )
            }

            if (payload.piiRedacted) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "🔒 PII Redacted",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFFF9800)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InlinePayloadDetailSheet(
    payload: PayloadLogEntity,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = "Payload Details",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(16.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DetailRow("Direction", payload.direction)
                    DetailRow("Protocol", payload.protocol)
                    payload.method?.let { DetailRow("Method", it) }
                    DetailRow("Destination", "${payload.destinationIp}:${payload.destinationPort}")
                    payload.sniHostname?.let { DetailRow("SNI Hostname", it) }
                    payload.ownerPackage?.let { DetailRow("Package", it) }
                    DetailRow("Size", formatBytes(payload.sizeBytes))
                    DetailRow("Timestamp", formatRelativeTime(payload.timestamp))
                    payload.urlPath?.let { DetailRow("URL Path", it) }
                    payload.headers?.let {
                        if (it != "{}" && it.isNotEmpty()) {
                            DetailRow("Headers", it.take(200))
                        }
                    }
                    payload.body?.let {
                        DetailRow("Body", it)
                    }
                    if (payload.piiRedacted) {
                        DetailRow("PII Status", "Redacted 🔒")
                    }
                    DetailRow("MITM Success", if (payload.isMitmSuccess) "✅ Yes" else "❌ No")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Close")
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            textAlign = androidx.compose.ui.text.style.TextAlign.End
        )
    }
}

private fun formatRelativeTime(timestamp: Long): String {
    val diff = System.currentTimeMillis() - timestamp
    return when {
        diff < 60000 -> "${diff / 1000}s ago"
        diff < 3600000 -> "${diff / 60000}m ago"
        diff < 86400000 -> "${diff / 3600000}h ago"
        else -> SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()).format(Date(timestamp))
    }
}

private fun formatBytes(bytes: Int): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> "${bytes / (1024 * 1024)} MB"
    }
}
