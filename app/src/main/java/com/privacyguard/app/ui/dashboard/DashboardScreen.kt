package com.privacyguard.app.ui.dashboard

import android.app.Activity
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.privacyguard.app.R
import com.privacyguard.app.core.stats.StatsManager
import com.privacyguard.app.vpn.PrivacyVpnService
import com.privacyguard.app.vpn.VpnManager
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onOpenSettings: () -> Unit = {}
) {
    val context = LocalContext.current
    var isVpnActive by remember { mutableStateOf(PrivacyVpnService.isRunning) }
    val stats by StatsManager.snapshot.collectAsState()

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            VpnManager.startVpn(context)
            isVpnActive = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name), fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings))
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                // VPN Status Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isVpnActive) Color(0xFF4CAF50) else Color(0xFFF44336)
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier.size(80.dp).clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isVpnActive) Icons.Default.Lock else Icons.Default.LockOpen,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = Color.White
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = if (isVpnActive) stringResource(R.string.protected_status) else stringResource(R.string.not_protected),
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (isVpnActive) stringResource(R.string.privacy_protected) else stringResource(R.string.tap_to_start),
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 14.sp
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = {
                                if (isVpnActive) {
                                    VpnManager.stopVpn(context)
                                    isVpnActive = false
                                } else {
                                    val intent = VpnManager.prepareVpn(context)
                                    if (intent != null) {
                                        vpnPermissionLauncher.launch(intent)
                                    } else {
                                        VpnManager.startVpn(context)
                                        isVpnActive = true
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.White,
                                contentColor = if (isVpnActive) Color(0xFF4CAF50) else Color(0xFFF44336)
                            ),
                            shape = RoundedCornerShape(24.dp)
                        ) {
                            Text(
                                if (isVpnActive) stringResource(R.string.stop_vpn) else stringResource(R.string.start_vpn),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            item {
                // Statistics Row
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    StatisticCard(
                        modifier = Modifier.weight(1f),
                        title = stringResource(R.string.blocked_today),
                        value = stats.blockedToday.toString(),
                        icon = Icons.Default.Block,
                        color = Color(0xFFF44336)
                    )
                    StatisticCard(
                        modifier = Modifier.weight(1f),
                        title = stringResource(R.string.data_saved),
                        value = formatBytes(stats.dataSavedBytes),
                        icon = Icons.Default.Save,
                        color = Color(0xFF4CAF50)
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    StatisticCard(
                        modifier = Modifier.weight(1f),
                        title = stringResource(R.string.active_apps),
                        value = stats.activeConnections.size.toString(),
                        icon = Icons.Default.Apps,
                        color = Color(0xFF2196F3)
                    )
                    StatisticCard(
                        modifier = Modifier.weight(1f),
                        title = stringResource(R.string.trackers),
                        value = stats.totalTrackersBlocked.toString(),
                        icon = Icons.Default.Visibility,
                        color = Color(0xFFFF9800)
                    )
                }
            }

            item {
                // Privacy Score Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier.size(60.dp).clip(CircleShape)
                                .background(Color(0xFF4CAF50)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = calculatePrivacyScore(stats).toString(),
                                color = Color.White,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text(text = stringResource(R.string.privacy_score), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Text(
                                text = stringResource(R.string.excellent),
                                fontSize = 12.sp,
                                color = Color.Gray
                            )
                        }
                    }
                }
            }

            item {
                Text(text = stringResource(R.string.recent_activity), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            }

            if (stats.recentActivity.isEmpty()) {
                item {
                    RecentActivityItem(
                        appName = stringResource(R.string.app_name),
                        description = "No activity yet",
                        timeAgo = "just now",
                        isBlocked = false
                    )
                }
            } else {
                items(stats.recentActivity.take(5).size) { index ->
                    val activity = stats.recentActivity[index]
                    RecentActivityItem(
                        appName = activity.appName,
                        description = activity.description,
                        timeAgo = formatTimeAgo(activity.timeMillis),
                        isBlocked = activity.isBlocked
                    )
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    return when {
        bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024 -> String.format("%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}

private fun calculatePrivacyScore(stats: com.privacyguard.app.core.stats.StatsSnapshot): Int {
    val base = 50
    val trackerBonus = minOf(35, stats.blockedToday.toInt())
    val packetBonus = if (stats.totalPackets > 0) 15 else 0
    return (base + trackerBonus + packetBonus).coerceIn(0, 100)
}

private fun formatTimeAgo(timeMillis: Long): String {
    val diff = (System.currentTimeMillis() - timeMillis).coerceAtLeast(0L)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(diff)
    return when {
        minutes <= 0L -> "now"
        minutes == 1L -> "1 min ago"
        minutes < 60L -> "$minutes min ago"
        else -> "${TimeUnit.MILLISECONDS.toHours(diff)} h ago"
    }
}

@Composable
fun StatisticCard(
    modifier: Modifier = Modifier,
    title: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.1f)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(40.dp).clip(CircleShape).background(color.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(imageVector = icon, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(text = title, fontSize = 12.sp, color = Color.Gray)
                Text(text = value, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = color)
            }
        }
    }
}

@Composable
fun RecentActivityItem(appName: String, description: String, timeAgo: String, isBlocked: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(36.dp).clip(CircleShape).background(
                    if (isBlocked) Color(0xFFF44336).copy(alpha = 0.2f)
                    else Color(0xFF4CAF50).copy(alpha = 0.2f)
                ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isBlocked) Icons.Default.Block else Icons.Default.Check,
                    contentDescription = null,
                    tint = if (isBlocked) Color(0xFFF44336) else Color(0xFF4CAF50),
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = appName, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(text = description, fontSize = 12.sp, color = Color.Gray)
            }
            Text(text = timeAgo, fontSize = 11.sp, color = Color.Gray)
        }
    }
}
