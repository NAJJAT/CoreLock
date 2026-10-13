package com.privacyguard.app.ui.rules

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.privacyguard.app.R
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewmodel.compose.viewModel
import com.privacyguard.core.filter.FilterRule
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RulesScreen(vm: RulesViewModel = viewModel()) {
    val rules by vm.rules.collectAsStateWithLifecycle()
    val suggestions by vm.suggestions.collectAsStateWithLifecycle()
    val searchQuery by vm.searchQuery.collectAsStateWithLifecycle()
    var showAddDialog by remember { mutableStateOf(false) }
    val securityWarning by vm.securityWarning.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.rules_title), fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.rules_add))
            }
        },
    ) { padding ->
        if (rules.isEmpty() && suggestions.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Security, contentDescription = null,
                        modifier = Modifier.size(48.dp), tint = Color.LightGray)
                    Text(stringResource(R.string.rules_empty), color = Color.Gray, fontSize = 16.sp)
                    Text(stringResource(R.string.rules_empty_desc), color = Color.LightGray, fontSize = 13.sp)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                item {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { vm.setSearchQuery(it) },
                        placeholder = { Text(stringResource(R.string.rules_search), fontSize = 13.sp, color = Color.Gray) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = if (searchQuery.isNotEmpty()) {{
                            IconButton(onClick = { vm.setSearchQuery("") }) {
                                Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.rules_clear))
                            }
                        }} else null,
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                    )
                }
                if (suggestions.isNotEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.rules_suggested).uppercase(),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Gray,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                    items(suggestions, key = { it.id }) { s ->
                        SuggestionCard(
                            suggestion = s,
                            onAccept   = { vm.acceptSuggestion(s) },
                            onDismiss  = { vm.dismissSuggestion(s.id) },
                        )
                    }
                    item {
                        Text(
                            stringResource(R.string.rules_active, rules.size).uppercase(),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Gray,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                } else {
                    item {
                        Text(
                            pluralStringResource(R.plurals.rules_count, rules.size, rules.size),
                            fontSize = 12.sp,
                            color = Color.Gray,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
                items(rules, key = { it.id }) { rule ->
                    RuleCard(
                        rule      = rule,
                        onToggle  = { vm.toggleRule(rule.id, it) },
                        onDelete  = { vm.deleteRule(rule.id) },
                    )
                }
            }
        }
    }

    securityWarning?.let { domain ->
        SecurityAllowWarningDialog(
            domain = domain,
            onConfirm = { vm.addDomainRule(domain, com.privacyguard.core.filter.FilterRule.Action.ALLOW, confirmedRisk = true) },
            onDismiss = vm::dismissSecurityWarning,
        )
    }
    if (showAddDialog) {
        AddRuleDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { value, type, action ->
                when (type) {
                    RuleType.DOMAIN  -> vm.addDomainRule(value, action)
                    RuleType.PACKAGE -> vm.addPackageRule(value, action)
                    RuleType.IP      -> vm.addIpRule(value, action)
                }
                showAddDialog = false
            },
        )
    }
}

@Composable
private fun RuleCard(
    rule:     FilterRule,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val actionColor = if (rule.action == FilterRule.Action.DENY) Color(0xFFF44336) else Color(0xFF4CAF50)
    val actionLabel = (if (rule.action == FilterRule.Action.DENY) stringResource(R.string.ads_block) else stringResource(R.string.ads_allow)).uppercase()

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(10.dp),
        colors   = CardDefaults.cardColors(
            containerColor = if (!rule.isEnabled)
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            else
                MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier          = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Action badge
            Box(
                modifier           = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(actionColor.copy(alpha = 0.12f))
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                contentAlignment   = Alignment.Center,
            ) {
                Text(actionLabel, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = actionColor)
            }

            Spacer(Modifier.width(10.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    rule.label,
                    fontSize   = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color      = if (rule.isEnabled) MaterialTheme.colorScheme.onSurface else Color.Gray,
                    maxLines   = 1,
                )
                val detail = listOfNotNull(
                    rule.matchDomain?.let { stringResource(R.string.rules_d_domain, it) },
                    rule.matchPackage?.let { stringResource(R.string.rules_d_app, it) },
                    rule.matchIp?.let { stringResource(R.string.rules_d_ip, it) },
                    rule.matchBackground?.let { if (it) stringResource(R.string.rules_d_bg) else null },
                ).joinToString(" · ").ifBlank { rule.source.name }
                Text(detail, fontSize = 11.sp, color = Color.Gray, maxLines = 1)
                if (rule.hitCount > 0) {
                    Text(
                        pluralStringResource(R.plurals.rules_triggered, rule.hitCount, rule.hitCount),
                        fontSize = 10.sp,
                        color = actionColor.copy(alpha = 0.7f),
                    )
                }
            }

            Switch(
                checked         = rule.isEnabled,
                onCheckedChange = onToggle,
                modifier        = Modifier.size(36.dp, 20.dp),
            )

            Spacer(Modifier.width(4.dp))

            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.rules_delete),
                    tint = Color.Gray, modifier = Modifier.size(18.dp))
            }
        }
    }
}

private enum class RuleType(val labelRes: Int) {
    DOMAIN(R.string.rules_type_domain), PACKAGE(R.string.rules_type_app), IP(R.string.rules_type_ip),
}

@Composable
private fun AddRuleDialog(
    onDismiss: () -> Unit,
    onConfirm: (value: String, type: RuleType, action: FilterRule.Action) -> Unit,
) {
    var value      by remember { mutableStateOf("") }
    var ruleType   by remember { mutableStateOf(RuleType.DOMAIN) }
    var blockAction by remember { mutableStateOf(true) }
    var typeExpanded by remember { mutableStateOf(false) }
    // IPv6 and malformed addresses used to be dropped without a word.
    val ipInvalid = ruleType == RuleType.IP && value.isNotBlank() && !RulesViewModel.isValidIpOrCidr(value.trim())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rules_add_title), fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Rule type selector
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.rules_type), fontSize = 14.sp, modifier = Modifier.width(60.dp))
                    Box {
                        OutlinedButton(
                            onClick = { typeExpanded = true },
                            modifier = Modifier.height(36.dp),
                        ) {
                            Text(stringResource(ruleType.labelRes), fontSize = 13.sp)
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null,
                                modifier = Modifier.size(16.dp))
                        }
                        DropdownMenu(expanded = typeExpanded, onDismissRequest = { typeExpanded = false }) {
                            RuleType.values().forEach { t ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(t.labelRes)) },
                                    onClick = { ruleType = t; typeExpanded = false },
                                )
                            }
                        }
                    }
                }

                // Value input
                OutlinedTextField(
                    value         = value,
                    onValueChange = { value = it },
                    label         = {
                        Text(
                            when (ruleType) {
                                RuleType.DOMAIN  -> stringResource(R.string.rules_hint_domain)
                                RuleType.PACKAGE -> stringResource(R.string.rules_hint_package)
                                RuleType.IP      -> stringResource(R.string.rules_hint_ip)
                            }
                        )
                    },
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth(),
                    isError       = ipInvalid,
                    supportingText = when {
                        ipInvalid -> R.string.rules_ip_invalid
                        ruleType == RuleType.DOMAIN -> R.string.rules_domain_exact
                        else -> null
                    }?.let { res -> @Composable { Text(stringResource(res)) } },
                )

                // Action toggle
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.rules_action), fontSize = 14.sp)
                    FilterChip(
                        selected = blockAction,
                        onClick  = { blockAction = true },
                        label    = { Text(stringResource(R.string.ads_block)) },
                        leadingIcon = {
                            if (blockAction) Icon(Icons.Default.Check, null, modifier = Modifier.size(16.dp))
                        },
                    )
                    FilterChip(
                        selected = !blockAction,
                        onClick  = { blockAction = false },
                        label    = { Text(stringResource(R.string.ads_allow)) },
                        leadingIcon = {
                            if (!blockAction) Icon(Icons.Default.Check, null, modifier = Modifier.size(16.dp))
                        },
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick  = {
                    if (value.isNotBlank()) {
                        onConfirm(
                            value.trim(),
                            ruleType,
                            if (blockAction) FilterRule.Action.DENY else FilterRule.Action.ALLOW,
                        )
                    }
                },
                enabled = value.isNotBlank() && !ipInvalid,
            ) {
                Text(stringResource(R.string.rules_add_button))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

@Composable
private fun SuggestionCard(
    suggestion: SuggestedRule,
    onAccept: () -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Lightbulb,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.size(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    suggestion.label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    suggestion.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                )
            }
            Spacer(modifier = Modifier.size(8.dp))
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.rules_skip), fontSize = 12.sp) }
            Button(
                onClick = onAccept,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Text(stringResource(R.string.rules_add_button), fontSize = 12.sp)
            }
        }
    }
}
