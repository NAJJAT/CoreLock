package com.privacyguard.app.ui.settings

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Update
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.privacyguard.app.R
import com.privacyguard.app.data.local.preferences.SettingsPreferences
import com.privacyguard.app.core.blocklist.BlocklistManager
import com.privacyguard.app.utils.LocaleHelper
import com.privacyguard.app.core.pcap.PcapWriter
import com.privacyguard.app.vpn.KillSwitch
import com.privacyguard.app.vpn.PrivacyVpnService
import com.privacyguard.app.workers.BlocklistUpdateWorker
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onLanguageChanged: () -> Unit
) {
    val context = LocalContext.current
    var showLanguageDialog by remember { mutableStateOf(false) }
    val currentLanguage = remember { mutableStateOf(LocaleHelper.getSavedLanguage(context)) }
    var isPcapEnabled by remember { mutableStateOf(PrivacyVpnService.isPcapEnabled) }
    var isKillSwitchEnabled by remember { mutableStateOf(KillSwitch.isEnabled()) }
    val settingsPreferences = remember(context) { SettingsPreferences.getInstance(context) }
    val notificationsEnabled by settingsPreferences.notificationsEnabled.collectAsState()
    val blockNotifications by settingsPreferences.blockNotifications.collectAsState()
    val trackerNotifications by settingsPreferences.trackerNotifications.collectAsState()
    val vpnNotifications by settingsPreferences.vpnNotifications.collectAsState()
    val weeklyReport by settingsPreferences.weeklyReport.collectAsState()
    val killSwitchNotifications by settingsPreferences.killSwitchNotifications.collectAsState()
    val blocklistSize by BlocklistManager.size.collectAsState()
    val lastBlocklistUpdate by BlocklistManager.lastUpdate.collectAsState()
    val isUpdatingBlocklist by BlocklistManager.isUpdating.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title), fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item { SectionHeader(stringResource(R.string.protection)) }
            item {
                SettingsSwitchItem(
                    title = stringResource(R.string.auto_start),
                    description = stringResource(R.string.auto_start_desc),
                    icon = Icons.Default.AutoFixHigh,
                    checked = false,
                    onCheckedChange = {}
                )
            }
            item {
                SettingsSwitchItem(
                    title = stringResource(R.string.block_ads),
                    description = stringResource(R.string.block_ads_desc),
                    icon = Icons.Default.Block,
                    checked = true,
                    onCheckedChange = {}
                )
            }
            item {
                SettingsSwitchItem(
                    title = stringResource(R.string.kill_switch_title),
                    description = stringResource(R.string.kill_switch_desc),
                    icon = Icons.Default.Warning,
                    checked = isKillSwitchEnabled,
                    onCheckedChange = { enabled ->
                        isKillSwitchEnabled = enabled
                        if (enabled) {
                            KillSwitch.enable()
                            if (PrivacyVpnService.isRunning) {
                                KillSwitch.startMonitoring(context)
                            }
                        } else {
                            KillSwitch.disable()
                        }
                    }
                )
            }

            item { SectionHeader(stringResource(R.string.appearance)) }
            item {
                SettingsDropdownItem(
                    title = stringResource(R.string.theme),
                    description = stringResource(R.string.theme_desc),
                    icon = Icons.Default.BrightnessMedium,
                    value = stringResource(R.string.system),
                    options = listOf(
                        stringResource(R.string.light),
                        stringResource(R.string.dark),
                        stringResource(R.string.system)
                    ),
                    onValueSelected = {}
                )
            }
            item {
                SettingsItem(
                    title = stringResource(R.string.language),
                    description = stringResource(R.string.language_desc),
                    icon = Icons.Default.Language,
                    onClick = { showLanguageDialog = true }
                )
            }

            item { SectionHeader(stringResource(R.string.advanced)) }
            item {
                NotificationSettingsSection(
                    notificationsEnabled = notificationsEnabled,
                    blockNotifications = blockNotifications,
                    trackerNotifications = trackerNotifications,
                    vpnNotifications = vpnNotifications,
                    weeklyReport = weeklyReport,
                    killSwitchNotifications = killSwitchNotifications,
                    onNotificationsEnabled = settingsPreferences::setNotificationsEnabled,
                    onBlockNotifications = settingsPreferences::setBlockNotifications,
                    onTrackerNotifications = settingsPreferences::setTrackerNotifications,
                    onVpnNotifications = settingsPreferences::setVpnNotifications,
                    onWeeklyReport = settingsPreferences::setWeeklyReport,
                    onKillSwitchNotifications = settingsPreferences::setKillSwitchNotifications
                )
            }
            item {
                BlocklistSettingsSection(
                    blocklistSize = blocklistSize,
                    lastUpdate = lastBlocklistUpdate,
                    isUpdating = isUpdatingBlocklist,
                    onUpdate = {
                        BlocklistUpdateWorker.updateNow(context)
                        Toast.makeText(
                            context,
                            context.getString(R.string.blocklist_update_started),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                )
            }
            item {
                SettingsItem(
                    title = stringResource(R.string.update_blocklist),
                    description = stringResource(R.string.update_blocklist_desc),
                    icon = Icons.Default.Update,
                    onClick = {
                        BlocklistUpdateWorker.updateNow(context)
                        Toast.makeText(
                            context,
                            context.getString(R.string.blocklist_update_started),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                )
            }
            item {
                SettingsSwitchItem(
                    title = stringResource(R.string.pcap_capture),
                    description = stringResource(R.string.pcap_capture_desc),
                    icon = Icons.Default.Storage,
                    checked = isPcapEnabled,
                    onCheckedChange = { enabled ->
                        isPcapEnabled = enabled
                        PrivacyVpnService.isPcapEnabled = enabled
                        if (enabled && PrivacyVpnService.isRunning) {
                            PcapWriter.startCapture(context)
                        } else if (!enabled) {
                            val file = PcapWriter.stopCapture()
                            if (file != null) {
                                showPcapReadyNotification(context, file)
                            }
                        }
                    }
                )
            }
            item {
                SettingsItem(
                    title = stringResource(R.string.export_pcap),
                    description = stringResource(R.string.export_pcap_desc),
                    icon = Icons.Default.Share,
                    onClick = {
                        val file = if (PcapWriter.isCapturing()) {
                            PcapWriter.stopCapture()
                        } else {
                            PcapWriter.getLastCompletedFile()
                        }

                        if (file != null) {
                            sharePcapFile(context, file)
                            if (PrivacyVpnService.isRunning && PrivacyVpnService.isPcapEnabled) {
                                PcapWriter.startCapture(context)
                            }
                        } else {
                            Toast.makeText(
                                context,
                                context.getString(R.string.pcap_not_available),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                )
            }
            item {
                SettingsItem(
                    title = stringResource(R.string.share_pcap),
                    description = PcapWriter.getCurrentFilePath() ?: stringResource(R.string.pcap_not_available),
                    icon = Icons.Default.Storage,
                    onClick = {
                        val file = PcapWriter.getLastCompletedFile()
                        if (file != null) {
                            showPcapReadyNotification(context, file)
                        } else {
                            Toast.makeText(
                                context,
                                context.getString(R.string.pcap_not_available),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                )
            }
            item {
                SettingsItem(
                    title = stringResource(R.string.backup_settings),
                    description = stringResource(R.string.backup_settings_desc),
                    icon = Icons.Default.Backup,
                    onClick = {}
                )
            }

            item { SectionHeader(stringResource(R.string.about)) }
            item {
                SettingsItem(
                    title = stringResource(R.string.about_app),
                    description = stringResource(R.string.version),
                    icon = Icons.Default.Info,
                    onClick = {}
                )
            }
            item {
                SettingsItem(
                    title = stringResource(R.string.privacy_policy),
                    description = "",
                    icon = Icons.Default.PrivacyTip,
                    onClick = {}
                )
            }
            item {
                SettingsItem(
                    title = stringResource(R.string.open_source),
                    description = "",
                    icon = Icons.Default.Code,
                    onClick = {}
                )
            }
        }
    }

    if (showLanguageDialog) {
        LanguageDialog(
            currentLanguage = currentLanguage.value,
            onLanguageSelected = { languageCode ->
                LocaleHelper.setLanguage(context, languageCode)
                currentLanguage.value = languageCode
                showLanguageDialog = false
                onLanguageChanged()
            },
            onDismiss = { showLanguageDialog = false }
        )
    }
}

private fun showPcapReadyNotification(context: Context, file: File) {
    val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val channelId = "pcap_channel"

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val channel = NotificationChannel(channelId, "PCAP Export", NotificationManager.IMPORTANCE_DEFAULT)
        notificationManager.createNotificationChannel(channel)
    }

    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/vnd.tcpdump.pcap")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val pendingIntent = PendingIntent.getActivity(
        context,
        1002,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    val notification = NotificationCompat.Builder(context, channelId)
        .setContentTitle(context.getString(R.string.pcap_ready_title))
        .setContentText(context.getString(R.string.pcap_ready_text))
        .setSmallIcon(android.R.drawable.ic_menu_save)
        .setContentIntent(pendingIntent)
        .setAutoCancel(true)
        .build()

    notificationManager.notify(1002, notification)
}

private fun sharePcapFile(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = "application/vnd.tcpdump.pcap"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(shareIntent, context.getString(R.string.share_pcap)))
}

@Composable
fun SectionHeader(title: String) {
    Text(
        text = title,
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
    )
}

@Composable
fun SettingsItem(
    title: String,
    description: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                if (description.isNotEmpty()) {
                    Text(text = description, fontSize = 11.sp, color = Color.Gray)
                }
            }
        }
    }
}

@Composable
fun SettingsSwitchItem(
    title: String,
    description: String,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(text = description, fontSize = 11.sp, color = Color.Gray)
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

@Composable
fun SettingsDropdownItem(
    title: String,
    description: String,
    icon: ImageVector,
    value: String,
    options: List<String>,
    onValueSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = true }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(text = description, fontSize = 11.sp, color = Color.Gray)
                }
                Text(text = value, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
                Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(20.dp))
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option) },
                        onClick = {
                            onValueSelected(option)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun LanguageDialog(
    currentLanguage: String,
    onLanguageSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.select_language),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                LanguageOption(
                    name = stringResource(R.string.english),
                    isSelected = currentLanguage == "en",
                    onClick = { onLanguageSelected("en") }
                )
                Spacer(modifier = Modifier.height(8.dp))
                LanguageOption(
                    name = stringResource(R.string.arabic),
                    isSelected = currentLanguage == "ar",
                    onClick = { onLanguageSelected("ar") }
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.cancel))
                }
            }
        }
    }
}

@Composable
fun LanguageOption(
    name: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = name, fontSize = 16.sp)
            if (isSelected) {
                Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
fun NotificationSettingsSection(
    notificationsEnabled: Boolean,
    blockNotifications: Boolean,
    trackerNotifications: Boolean,
    vpnNotifications: Boolean,
    weeklyReport: Boolean,
    killSwitchNotifications: Boolean,
    onNotificationsEnabled: (Boolean) -> Unit,
    onBlockNotifications: (Boolean) -> Unit,
    onTrackerNotifications: (Boolean) -> Unit,
    onVpnNotifications: (Boolean) -> Unit,
    onWeeklyReport: (Boolean) -> Unit,
    onKillSwitchNotifications: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.notifications_title),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(8.dp))

            SettingsSwitchItem(
                title = stringResource(R.string.enable_notifications),
                description = stringResource(R.string.enable_notifications_desc),
                icon = Icons.Default.Notifications,
                checked = notificationsEnabled,
                onCheckedChange = onNotificationsEnabled
            )

            if (notificationsEnabled) {
                Spacer(modifier = Modifier.height(8.dp))
                SettingsSwitchItem(
                    title = stringResource(R.string.block_notifications),
                    description = stringResource(R.string.block_notifications_desc),
                    icon = Icons.Default.Block,
                    checked = blockNotifications,
                    onCheckedChange = onBlockNotifications
                )
                Spacer(modifier = Modifier.height(8.dp))
                SettingsSwitchItem(
                    title = stringResource(R.string.tracker_notifications),
                    description = stringResource(R.string.tracker_notifications_desc),
                    icon = Icons.Default.Visibility,
                    checked = trackerNotifications,
                    onCheckedChange = onTrackerNotifications
                )
                Spacer(modifier = Modifier.height(8.dp))
                SettingsSwitchItem(
                    title = stringResource(R.string.vpn_notifications),
                    description = stringResource(R.string.vpn_notifications_desc),
                    icon = Icons.Default.Lock,
                    checked = vpnNotifications,
                    onCheckedChange = onVpnNotifications
                )
                Spacer(modifier = Modifier.height(8.dp))
                SettingsSwitchItem(
                    title = stringResource(R.string.weekly_report),
                    description = stringResource(R.string.weekly_report_desc),
                    icon = Icons.Default.Info,
                    checked = weeklyReport,
                    onCheckedChange = onWeeklyReport
                )
                Spacer(modifier = Modifier.height(8.dp))
                SettingsSwitchItem(
                    title = stringResource(R.string.kill_switch_alerts),
                    description = stringResource(R.string.kill_switch_alerts_desc),
                    icon = Icons.Default.Warning,
                    checked = killSwitchNotifications,
                    onCheckedChange = onKillSwitchNotifications
                )
            }
        }
    }
}

@Composable
fun BlocklistSettingsSection(
    blocklistSize: Int,
    lastUpdate: Long,
    isUpdating: Boolean,
    onUpdate: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.blocklist_title),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = stringResource(R.string.blocklist_total_entries), fontSize = 14.sp)
                Text(
                    text = stringResource(R.string.blocklist_domains_count, blocklistSize),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = stringResource(R.string.blocklist_last_update), fontSize = 14.sp)
                Text(text = formatDate(lastUpdate), fontSize = 14.sp)
            }

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = onUpdate,
                enabled = !isUpdating,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isUpdating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = stringResource(R.string.blocklist_updating))
                } else {
                    Text(text = stringResource(R.string.blocklist_update_now))
                }
            }
        }
    }
}

private fun formatDate(timestamp: Long): String {
    if (timestamp == 0L) return "Never"
    return SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(Date(timestamp))
}
