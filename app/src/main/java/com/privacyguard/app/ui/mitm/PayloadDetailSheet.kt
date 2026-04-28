package com.privacyguard.ui.mitm

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment // ADDED
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp // ADDED
import com.privacyguard.data.db.PayloadLogEntity
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PayloadDetailSheet(
    payload: PayloadLogEntity,
    onDismiss: () -> Unit
) {
    val clipboardManager = LocalClipboardManager.current
    // ADDED: compatibility helper for Material3 versions without BottomSheetDragHandle.
    @Composable
    fun BottomSheetDragHandle() {
        BottomSheetDefaults.DragHandle()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (payload.direction == "OUTBOUND") "↑" else "↓",
                        fontSize = 28.sp,
                        color = if (payload.direction == "OUTBOUND")
                            MaterialTheme.colorScheme.error
                        else
                            MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = payload.protocol,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                        )
                        Text(
                            text = "${payload.sniHostname ?: payload.destinationIp}:${payload.destinationPort}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Metadata info
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AssistChip(
                    onClick = { },
                    label = { Text("Size: ${formatBytes(payload.sizeBytes)}") }
                )
                AssistChip(
                    onClick = { },
                    label = { Text(payload.ownerPackage?.substringAfterLast(".") ?: "Unknown") }
                )
                if (payload.piiRedacted) {
                    AssistChip(
                        onClick = { },
                        label = { Text("🔒 PII Redacted") },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Tabs
            val tabTitles = listOf("Headers", "Body", "Info")
            var selectedTab by remember { mutableIntStateOf(0) }

            SecondaryScrollableTabRow(
                selectedTabIndex = selectedTab,
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                tabTitles.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Content based on selected tab
            when (selectedTab) {
                0 -> HeadersTab(headersJson = payload.headers)
                1 -> BodyTab(body = payload.body, encoding = payload.bodyEncoding)
                2 -> InfoTab(payload = payload)
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Copy button
            Button(
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
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Copy All")
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
fun HeadersTab(headersJson: String) {
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .height(300.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        try {
            val headers = Json.decodeFromString<Map<String, String>>(headersJson)
            items(headers.entries.toList()) { (key, value) ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(8.dp)
                    ) {
                        Text(
                            text = key,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                        )
                        Text(
                            text = value,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        } catch (e: Exception) {
            item {
                Text(
                    text = "Unable to parse headers: ${e.message}",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(8.dp)
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
                        modifier = Modifier.padding(8.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
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
fun InfoTab(payload: PayloadLogEntity) {
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .height(300.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            InfoRow("Session ID", payload.sessionId)
            InfoRow("Direction", payload.direction)
            InfoRow("Protocol", payload.protocol)
            payload.method?.let { InfoRow("Method", it) }
            payload.urlPath?.let { InfoRow("URL Path", it) }
            InfoRow("Destination", "${payload.destinationIp}:${payload.destinationPort}")
            payload.ownerPackage?.let { InfoRow("App Package", it) }
            payload.sniHostname?.let { InfoRow("SNI Hostname", it) }
            InfoRow("Timestamp", formatTimestamp(payload.timestamp))
            InfoRow("Size", formatBytes(payload.sizeBytes))
            InfoRow("Encoding", payload.bodyEncoding)
            InfoRow("PII Redacted", if (payload.piiRedacted) "Yes" else "No")
            InfoRow("MITM Success", if (payload.isMitmSuccess) "Yes" else "No")
        }
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f, fill = false)
        )
    }
    Divider()
}

private fun formatTimestamp(timestamp: Long): String {
    val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    return date.format(Date(timestamp))
}

private fun formatBytes(bytes: Int): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> "${bytes / (1024 * 1024)} MB"
    }
}

