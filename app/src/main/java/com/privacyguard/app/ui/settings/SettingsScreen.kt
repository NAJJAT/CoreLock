package com.privacyguard.app.ui.settings

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
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Update
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import com.privacyguard.app.R
import com.privacyguard.app.utils.LocaleHelper

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onLanguageChanged: () -> Unit
) {
    val context = LocalContext.current
    var showLanguageDialog by remember { mutableStateOf(false) }
    val currentLanguage = remember { mutableStateOf(LocaleHelper.getSavedLanguage(context)) }

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
                SettingsItem(
                    title = stringResource(R.string.update_blocklist),
                    description = stringResource(R.string.update_blocklist_desc),
                    icon = Icons.Default.Update,
                    onClick = {}
                )
            }
            item {
                SettingsItem(
                    title = stringResource(R.string.export_pcap),
                    description = stringResource(R.string.export_pcap_desc),
                    icon = Icons.Default.Storage,
                    onClick = {}
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
