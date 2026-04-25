package com.privacyguard.app.ui.apps

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.privacyguard.app.core.tracker.TrackerCategory

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppDetailScreen(
    packageName: String,
    appName: String,
    onBack: () -> Unit,
    vm: AppDetailViewModel = viewModel(),
) {
    val state by vm.state.collectAsState()
    var selectedTab by remember { mutableStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.appName.ifBlank { appName }, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Summary chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SummaryChip(
                    label = "${state.domains.size} domains",
                    icon  = Icons.Default.Wifi,
                    color = MaterialTheme.colorScheme.primary,
                )
                SummaryChip(
                    label = "${state.detectedSdks.size} SDKs detected",
                    icon  = Icons.Default.BugReport,
                    color = Color(0xFFF44336),
                )
                if (state.isScanning) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }

            // Tabs
            TabRow(selectedTabIndex = selectedTab) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 },
                    text = { Text("Connections") })
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 },
                    text = { Text("SDKs") })
            }

            when (selectedTab) {
                0 -> ConnectionsTab(state)
                1 -> SdkTab(state)
            }
        }
    }
}

@Composable
private fun ConnectionsTab(state: AppDetailState) {
    if (state.isLoadingDomains) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    if (state.domains.isEmpty()) {
        EmptyState("No connections recorded yet.\nStart the VPN to capture traffic.")
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(state.domains) { row ->
            DomainCard(row)
        }
    }
}

@Composable
private fun DomainCard(row: DomainRow) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (row.trackerName != null)
                Color(0xFFF44336).copy(alpha = 0.07f)
            else
                MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(
                        if (row.trackerName != null) Color(0xFFF44336).copy(alpha = 0.15f)
                        else MaterialTheme.colorScheme.primaryContainer
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (row.trackerName != null) Icons.Default.TrackChanges else Icons.Default.Wifi,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = if (row.trackerName != null) Color(0xFFF44336) else MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(row.domain, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                Text(
                    text = row.trackerName?.let { "$it · ${row.company}" } ?: row.company,
                    fontSize = 11.sp,
                    color = Color.Gray,
                )
            }
            Text(
                text = "${row.count}×",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = if (row.trackerName != null) Color(0xFFF44336) else Color.Gray,
            )
        }
    }
}

@Composable
private fun SdkTab(state: AppDetailState) {
    if (state.isScanning) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator()
                Text("Scanning APK for tracker SDKs…", color = Color.Gray)
            }
        }
        return
    }
    if (state.detectedSdks.isEmpty()) {
        EmptyState("No known tracker SDKs detected in this app's APK.")
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(state.detectedSdks) { sdk ->
            SdkCard(sdk)
        }
    }
}

@Composable
private fun SdkCard(sdk: com.privacyguard.app.core.tracker.TrackerEntry) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(categoryColor(sdk.category).copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = categoryIcon(sdk.category),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = categoryColor(sdk.category),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(sdk.name, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text("${sdk.company} · ${sdk.category.label}", fontSize = 11.sp, color = Color.Gray)
            }
        }
    }
}

@Composable
private fun SummaryChip(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(color.copy(alpha = 0.1f), RoundedCornerShape(16.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp), tint = color)
        Spacer(Modifier.width(4.dp))
        Text(label, fontSize = 12.sp, color = color, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun EmptyState(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(message, color = Color.Gray, fontSize = 14.sp)
    }
}

private fun categoryColor(cat: TrackerCategory) = when (cat) {
    TrackerCategory.ADVERTISING    -> Color(0xFFF44336)
    TrackerCategory.ANALYTICS      -> Color(0xFFFF9800)
    TrackerCategory.CRASH_REPORTING-> Color(0xFF2196F3)
    TrackerCategory.FINGERPRINTING -> Color(0xFF9C27B0)
    TrackerCategory.SOCIAL         -> Color(0xFF3F51B5)
    TrackerCategory.PROFILING      -> Color(0xFFE91E63)
    TrackerCategory.OTHER          -> Color(0xFF607D8B)
}

private fun categoryIcon(cat: TrackerCategory) = when (cat) {
    TrackerCategory.ADVERTISING    -> Icons.Default.MonetizationOn
    TrackerCategory.ANALYTICS      -> Icons.Default.BarChart
    TrackerCategory.CRASH_REPORTING-> Icons.Default.BugReport
    TrackerCategory.FINGERPRINTING -> Icons.Default.Fingerprint
    TrackerCategory.SOCIAL         -> Icons.Default.People
    TrackerCategory.PROFILING      -> Icons.Default.Person
    TrackerCategory.OTHER          -> Icons.Default.Info
}
