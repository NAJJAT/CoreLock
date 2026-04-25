package com.privacyguard.app.ui.ads

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.privacyguard.app.ui.components.PanelCard
import com.privacyguard.app.ui.components.ScreenScaffold
import com.privacyguard.app.ui.components.SectionLabel
import com.privacyguard.app.ui.components.StatusPill
import com.privacyguard.app.ui.components.ToggleChip
import com.privacyguard.app.ui.theme.PgAccent
import com.privacyguard.app.ui.theme.PgAccentDim
import com.privacyguard.app.ui.theme.PgBackgroundAlt
import com.privacyguard.app.ui.theme.PgDanger
import com.privacyguard.app.ui.theme.PgDangerDim
import com.privacyguard.app.ui.theme.PgInfo
import com.privacyguard.app.ui.theme.PgInfoDim
import com.privacyguard.app.ui.theme.PgPanelStrong
import com.privacyguard.app.ui.theme.PgText
import com.privacyguard.app.ui.theme.PgTextFaint
import com.privacyguard.app.ui.theme.PgTextMuted
import com.privacyguard.app.ui.theme.PgWarning

@Composable
fun AdsScreen(
    viewModel: AdsViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()
    var dialogOpen by remember { mutableStateOf(false) }
    var customDomain by remember { mutableStateOf("") }
    var customAllow by remember { mutableStateOf(false) }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            ScreenScaffold(
                title = "Ad Blocker",
                subtitle = "DNS-level · works across all apps",
                badge = if (state.masterEnabled) "LIVE" else "OFF",
            ) {
                MasterBlockCard(
                    state = state,
                    onToggle = { viewModel.setMasterEnabled(!state.masterEnabled) },
                )
            }
        }

        item {
            PanelCard(modifier = Modifier.padding(horizontal = 16.dp)) {
                SectionLabel("Block categories")
                Spacer(modifier = Modifier.height(12.dp))
                state.categories.forEachIndexed { index, category ->
                    CategoryCard(
                        category = category,
                        onToggle = { viewModel.setCategoryEnabled(category.id, !category.isEnabled) },
                    )
                    if (index != state.categories.lastIndex) {
                        Spacer(modifier = Modifier.height(10.dp))
                    }
                }
            }
        }

        item {
            PanelCard(modifier = Modifier.padding(horizontal = 16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SectionLabel("Custom rules")
                    IconButton(onClick = { dialogOpen = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Add domain", tint = PgAccent)
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                if (state.customRules.isEmpty()) {
                    Text("No custom domain rules yet.", style = MaterialTheme.typography.bodySmall, color = PgTextMuted)
                } else {
                    state.customRules.forEachIndexed { index, rule ->
                        CustomRuleRow(
                            rule = rule,
                            onDelete = { viewModel.removeCustomRule(rule.id) },
                        )
                        if (index != state.customRules.lastIndex) {
                            Spacer(modifier = Modifier.height(10.dp))
                        }
                    }
                }
            }
        }

        item {
            PanelCard(modifier = Modifier.padding(horizontal = 16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SectionLabel("Blocklists active")
                    StatusPill("${state.totalDomainsLoaded} domains", PgAccentDim, PgAccent)
                }
                Spacer(modifier = Modifier.height(12.dp))
                state.sources.forEachIndexed { index, source ->
                    SourceRow(
                        source = source,
                        onToggle = { viewModel.setSourceEnabled(source.source, !source.isEnabled) },
                    )
                    if (index != state.sources.lastIndex) {
                        Spacer(modifier = Modifier.height(10.dp))
                    }
                }
                Spacer(modifier = Modifier.height(14.dp))
                Button(
                    onClick = { viewModel.refreshBlocklists() },
                    colors = ButtonDefaults.buttonColors(containerColor = PgAccentDim, contentColor = PgAccent),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (state.isRefreshing) "Refreshing..." else "Refresh now")
                }
            }
        }
    }

    if (dialogOpen) {
        AlertDialog(
            onDismissRequest = { dialogOpen = false },
            title = { Text("Add custom rule") },
            text = {
                Column {
                    OutlinedTextField(
                        value = customDomain,
                        onValueChange = { customDomain = it },
                        label = { Text("Domain") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ChoicePill(
                            label = "Block",
                            active = !customAllow,
                            tint = PgDanger,
                            onClick = { customAllow = false },
                        )
                        ChoicePill(
                            label = "Allow",
                            active = customAllow,
                            tint = PgAccent,
                            onClick = { customAllow = true },
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.addCustomRule(customDomain, customAllow)
                        customDomain = ""
                        customAllow = false
                        dialogOpen = false
                    },
                    enabled = customDomain.isNotBlank(),
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                Button(
                    onClick = { dialogOpen = false },
                    colors = ButtonDefaults.buttonColors(containerColor = PgBackgroundAlt, contentColor = PgTextMuted),
                ) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun MasterBlockCard(
    state: AdsState,
    onToggle: () -> Unit,
) {
    PanelCard {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        listOf(
                            Color(0x2200E5A0),
                            Color(0x1200B1FF),
                            Color(0x10111620),
                        )
                    ),
                    RoundedCornerShape(16.dp),
                )
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .background(if (state.masterEnabled) PgAccentDim else PgBackgroundAlt, RoundedCornerShape(14.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (state.masterEnabled) Icons.Default.Shield else Icons.Default.Public,
                            contentDescription = null,
                            tint = if (state.masterEnabled) PgAccent else PgTextMuted,
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Ad blocking", style = MaterialTheme.typography.titleMedium, color = PgText, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            if (state.masterEnabled) "${state.blockedToday} ads blocked today" else "Ad blocking is off",
                            style = MaterialTheme.typography.bodySmall,
                            color = PgTextMuted,
                        )
                    }
                    Box(modifier = Modifier.clickable(onClick = onToggle)) {
                        ToggleChip(checked = state.masterEnabled)
                    }
                }
                Spacer(modifier = Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MiniStat(value = state.blockedToday.toString(), label = "Blocked today", tint = PgAccent, modifier = Modifier.weight(1f))
                    MiniStat(value = "${state.blockRate}%", label = "Block rate", tint = PgWarning, modifier = Modifier.weight(1f))
                    MiniStat(value = "${state.timeSavedMs}ms", label = "Time saved", tint = PgInfo, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun CategoryCard(
    category: AdCategoryUi,
    onToggle: () -> Unit,
) {
    val tint = when (category.id) {
        "ads" -> PgAccent
        "trackers" -> PgWarning
        "malware" -> PgDanger
        else -> PgInfo
    }
    val progress = if (category.totalCount == 0) 0f else category.enabledCount.toFloat() / category.totalCount.toFloat()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(PgBackgroundAlt, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 13.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(category.title, style = MaterialTheme.typography.titleSmall, color = PgText, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    "${category.enabledCount} active · ${category.totalCount} domains",
                    style = MaterialTheme.typography.bodySmall,
                    color = PgTextMuted,
                )
            }
            Box(modifier = Modifier.clickable(onClick = onToggle)) {
                ToggleChip(checked = category.isEnabled)
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(category.subtitle, style = MaterialTheme.typography.bodySmall, color = PgTextFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(modifier = Modifier.height(10.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(5.dp)
                .background(PgPanelStrong, RoundedCornerShape(999.dp)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                    .height(5.dp)
                    .background(tint, RoundedCornerShape(999.dp)),
            )
        }
    }
}

@Composable
private fun CustomRuleRow(
    rule: CustomDomainRuleUi,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(PgBackgroundAlt, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(rule.domain, style = MaterialTheme.typography.titleSmall, color = PgText, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(modifier = Modifier.height(2.dp))
            Text(if (rule.isAllow) "Custom allow" else "Custom block", style = MaterialTheme.typography.bodySmall, color = PgTextFaint)
        }
        StatusPill(
            if (rule.isAllow) "ALLOW" else "BLOCK",
            if (rule.isAllow) PgAccentDim else PgDangerDim,
            if (rule.isAllow) PgAccent else PgDanger,
        )
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.DeleteOutline, contentDescription = "Delete rule", tint = PgTextMuted)
        }
    }
}

@Composable
private fun SourceRow(
    source: BlocklistSourceUi,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(PgBackgroundAlt, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(source.source, style = MaterialTheme.typography.titleSmall, color = PgText)
            Spacer(modifier = Modifier.height(2.dp))
            Text("${source.enabledCount}/${source.totalCount} domains enabled", style = MaterialTheme.typography.bodySmall, color = PgTextMuted)
        }
        Box(modifier = Modifier.clickable(onClick = onToggle)) {
            StatusPill(
                if (source.isEnabled) "ON" else "OFF",
                if (source.isEnabled) PgAccentDim else PgBackgroundAlt,
                if (source.isEnabled) PgAccent else PgTextMuted,
            )
        }
    }
}

@Composable
private fun MiniStat(
    value: String,
    label: String,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(PgBackgroundAlt, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 11.dp),
    ) {
        Text(value, style = MaterialTheme.typography.titleMedium, color = tint, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = PgTextMuted)
    }
}

@Composable
private fun ChoicePill(
    label: String,
    active: Boolean,
    tint: Color,
    onClick: () -> Unit,
) {
    Box(modifier = Modifier.clickable(onClick = onClick)) {
        StatusPill(
            label,
            if (active) tint.copy(alpha = 0.16f) else PgBackgroundAlt,
            if (active) tint else PgTextMuted,
        )
    }
}
