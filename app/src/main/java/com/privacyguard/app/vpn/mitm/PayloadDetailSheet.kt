package com.privacyguard.app.vpn.mitm

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.privacyguard.app.data.db.PayloadLogEntity // FIXED: was com.privacyguard.data.local.database.entity.PayloadLogEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PayloadDetailSheet(
    payload: PayloadLogEntity,
    onDismiss: () -> Unit
) {
    val clipboardManager = LocalClipboardManager.current
    var showRawBytes by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${payload.direction} ${payload.protocol}",
                    style = MaterialTheme.typography.headlineSmall
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "${payload.sniHostname ?: payload.destinationIp}:${payload.destinationPort}",
                style = MaterialTheme.typography.bodyMedium
            )

            Text(
                text = "${payload.ownerPackage ?: "Unknown app"} • ${formatTimestamp(payload.timestamp)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Info Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AssistChip(
                    onClick = { },
                    label = { Text("Size: ${formatBytes(payload.sizeBytes)}") }
                )
                if (payload.piiRedacted) {
                    AssistChip(
                        onClick = { },
                        label = { Text("PII Redacted") },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Tabs
            val tabTitles = listOf("Headers", "Body", "Raw")
            var selectedTab by remember { mutableStateOf(0) }

            TabRow(selectedTabIndex = selectedTab) {
                tabTitles.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Content
            when (selectedTab) {
                0 -> HeadersTab(payload.headers)
                1 -> BodyTab(payload.body, payload.bodyEncoding)
                2 -> RawTab(payload, showRawBytes, { showRawBytes = !showRawBytes })
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val text = buildString {
                            appendLine("Protocol: ${payload.protocol}")
                            appendLine("Direction: ${payload.direction}")
                            appendLine("Host: ${payload.sniHostname ?: payload.destinationIp}")
                            appendLine("Time: ${formatTimestamp(payload.timestamp)}")
                            appendLine("\nHeaders:")
                            appendLine(payload.headers)
                            payload.body?.let {
                                appendLine("\nBody:")
                                appendLine(it)
                            }
                        }
                        clipboardManager.setText(AnnotatedString(text))
                    }
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Copy All")
                }
            }
        }
    }
}

@Composable
fun HeadersTab(headersJson: String) {
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .height(300.dp)
    ) {
        try {
            val headers = Json.decodeFromString<Map<String, String>>(headersJson)
            items(headers.size) { index ->
                val (key, value) = headers.entries.elementAt(index)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp)
                ) {
                    Text(
                        text = key,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (index < headers.size - 1) {
                        Divider(modifier = Modifier.padding(vertical = 4.dp))
                    }
                }
            }
        } catch (e: Exception) {
            item {
                Text(
                    text = "Unable to parse headers: ${e.message}",
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
fun BodyTab(body: String?, encoding: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(300.dp)
    ) {
        if (body.isNullOrEmpty()) {
            Text(
                text = "No body content",
                modifier = Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            LazyColumn {
                item {
                    Text(
                        text = "Encoding: $encoding",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(8.dp)
                    )
                    Divider()
                    Text(
                        text = body,
                        modifier = Modifier.padding(8.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

@Composable
fun RawTab(payload: PayloadLogEntity, showRawBytes: Boolean, onToggle: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(300.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = onToggle) {
                Text(if (showRawBytes) "Show Text" else "Show Raw Bytes")
            }
        }

        if (showRawBytes) {
            // Would need raw bytes stored in entity - simplified for now
            Text(
                text = "Raw byte view requires storing raw payloads in database",
                modifier = Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Text(
                text = payload.body ?: "No body content",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

private fun formatTimestamp(timestamp: Long): String {
    val date = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
    return date.format(java.util.Date(timestamp))
}

private fun formatBytes(bytes: Int): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> "${bytes / (1024 * 1024)} MB"
    }
}
