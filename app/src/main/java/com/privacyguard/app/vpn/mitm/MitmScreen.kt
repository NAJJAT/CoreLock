package com.privacyguard.app.vpn.mitm

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable // ADDED: missing import for clickable modifier used in PayloadListItem
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel // FIXED: replaced hiltViewModel (Hilt not configured); viewModel() is the correct factory here
import com.privacyguard.app.data.db.PayloadLogEntity // FIXED: was com.privacyguard.data.local.database.entity.PayloadLogEntity
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MitmScreen(
    viewModel: MitmViewModel = viewModel() // FIXED: hiltViewModel() → viewModel() (Hilt not configured in this project)
) {
    val uiState by viewModel.uiState.collectAsState()
    val scope = rememberCoroutineScope()
    var showConsentDialog by remember { mutableStateOf(false) }
    var selectedPayload by remember { mutableStateOf<PayloadLogEntity?>(null) }

    // Show consent dialog if needed
    LaunchedEffect(uiState.isEnabled, uiState.isConsentValid) {
        if (uiState.isEnabled && !uiState.isConsentValid) {
            showConsentDialog = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Payload Inspection") },
                actions = {
                    IconButton(onClick = { viewModel.shipNow() }) {
                        Badge(
                            containerColor = MaterialTheme.colorScheme.primary
                        ) {
                            Text(uiState.pendingEvents.toString())
                        }
                        Icon(Icons.Default.Send, contentDescription = "Ship to SIEM")
                    }
                    IconButton(onClick = { /* Export logs */ }) {
                        Icon(Icons.Default.Share, contentDescription = "Export")
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
                )
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
                            text = if (uiState.isEnabled) "MITM Active" else "MITM Disabled",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "Pending SIEM events: ${uiState.pendingEvents}",
                            style = MaterialTheme.typography.bodySmall
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
                    label = { Text("↑ OUTBOUND") }
                )
                FilterChip(
                    selected = uiState.filterDirection == "INBOUND",
                    onClick = {
                        viewModel.setFilterDirection(
                            if (uiState.filterDirection == "INBOUND") null else "INBOUND"
                        )
                    },
                    label = { Text("↓ INBOUND") }
                )
                FilterChip(
                    selected = uiState.filterHasBody,
                    onClick = { viewModel.setFilterHasBody(!uiState.filterHasBody) },
                    label = { Text("Has Body") }
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
                singleLine = true
            )

            // Payload List
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

    // Consent Dialog
    if (showConsentDialog) {
        AlertDialog(
            onDismissRequest = { showConsentDialog = false },
            title = { Text("Enterprise Payload Inspection — Important Notice") },
            text = {
                Text(
                    "This feature intercepts and logs all HTTPS traffic including " +
                            "usernames, passwords, messages, and personal data.\n\n" +
                            "It must only be enabled on company-managed devices with " +
                            "employee written consent under your jurisdiction's privacy laws.\n\n" +
                            "By enabling this feature you confirm that all legal requirements " +
                            "for employee monitoring in your jurisdiction have been met."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.recordConsent()
                        showConsentDialog = false
                    }
                ) {
                    Text("I Confirm — Enable")
                }
            },
            dismissButton = {
                TextButton(onClick = { showConsentDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Detail Bottom Sheet
    selectedPayload?.let { payload ->
        PayloadDetailSheet(
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
            .clickable { onClick() }
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row {
                    Text(
                        text = if (payload.direction == "OUTBOUND") "↑" else "↓",
                        color = if (payload.direction == "OUTBOUND")
                            MaterialTheme.colorScheme.error
                        else
                            MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = payload.sniHostname ?: payload.destinationIp,
                        style = MaterialTheme.typography.titleSmall
                    )
                }
                Text(
                    text = formatRelativeTime(payload.timestamp),
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AssistChip(
                    onClick = { },
                    label = { Text(payload.protocol) },
                    modifier = Modifier.height(24.dp)
                )
                payload.method?.let {
                    AssistChip(
                        onClick = { },
                        label = { Text(it) },
                        modifier = Modifier.height(24.dp)
                    )
                }
                Text(
                    text = formatBytes(payload.sizeBytes),
                    style = MaterialTheme.typography.bodySmall
                )
            }

            payload.urlPath?.let {
                Text(
                    text = it.take(80),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            payload.body?.let {
                Text(
                    text = it.take(80),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2
                )
            }
        }
    }
}

private fun formatRelativeTime(timestamp: Long): String {
    val diff = System.currentTimeMillis() - timestamp
    return when {
        diff < 60000 -> "${diff / 1000}s ago"
        diff < 3600000 -> "${diff / 60000}m ago"
        diff < 86400000 -> "${diff / 3600000}h ago"
        else -> "${diff / 86400000}d ago"
    }
}

private fun formatBytes(bytes: Int): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> "${bytes / (1024 * 1024)} MB"
    }
}
