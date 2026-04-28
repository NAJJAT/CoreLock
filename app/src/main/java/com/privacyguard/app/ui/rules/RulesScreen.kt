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
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewmodel.compose.viewModel
import com.privacyguard.core.filter.FilterRule

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RulesScreen(vm: RulesViewModel = viewModel()) {
    val rules by vm.rules.collectAsState()
    val suggestions by vm.suggestions.collectAsState()
    val searchQuery by vm.searchQuery.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Rules", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add rule")
            }
        },
    ) { padding ->
        if (rules.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Security, contentDescription = null,
                        modifier = Modifier.size(48.dp), tint = Color.LightGray)
                    Text("No rules yet", color = Color.Gray, fontSize = 16.sp)
                    Text("Tap + to add a domain or app rule", color = Color.LightGray, fontSize = 13.sp)
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
                        placeholder = { Text("Search domain, app, IP…", fontSize = 13.sp, color = Color.Gray) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = if (searchQuery.isNotEmpty()) {{
                            IconButton(onClick = { vm.setSearchQuery("") }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear")
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
                            "SUGGESTED",
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
                            "ACTIVE RULES · ${rules.size}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Gray,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                } else {
                    item {
                        Text(
                            "${rules.size} rule${if (rules.size != 1) "s" else ""}",
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

    if (showAddDialog) {
        AddRuleDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { value, type, action ->
                when (type) {
                    RuleType.DOMAIN  -> vm.addDomainRule(value, action)
                    RuleType.PACKAGE -> vm.addPackageRule(value, action)
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
    val actionLabel = if (rule.action == FilterRule.Action.DENY) "BLOCK" else "ALLOW"

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
                    rule.matchDomain?.let { "domain: $it" },
                    rule.matchPackage?.let { "pkg: $it" },
                    rule.matchIp?.let { "ip: $it" },
                ).joinToString(" · ").ifBlank { rule.source.name }
                Text(detail, fontSize = 11.sp, color = Color.Gray, maxLines = 1)
            }

            Switch(
                checked         = rule.isEnabled,
                onCheckedChange = onToggle,
                modifier        = Modifier.size(36.dp, 20.dp),
            )

            Spacer(Modifier.width(4.dp))

            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Delete, contentDescription = "Delete",
                    tint = Color.Gray, modifier = Modifier.size(18.dp))
            }
        }
    }
}

private enum class RuleType { DOMAIN, PACKAGE }

@Composable
private fun AddRuleDialog(
    onDismiss: () -> Unit,
    onConfirm: (value: String, type: RuleType, action: FilterRule.Action) -> Unit,
) {
    var value      by remember { mutableStateOf("") }
    var ruleType   by remember { mutableStateOf(RuleType.DOMAIN) }
    var blockAction by remember { mutableStateOf(true) }
    var typeExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Rule", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Rule type selector
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Type:", fontSize = 14.sp, modifier = Modifier.width(60.dp))
                    Box {
                        OutlinedButton(
                            onClick = { typeExpanded = true },
                            modifier = Modifier.height(36.dp),
                        ) {
                            Text(ruleType.name, fontSize = 13.sp)
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null,
                                modifier = Modifier.size(16.dp))
                        }
                        DropdownMenu(expanded = typeExpanded, onDismissRequest = { typeExpanded = false }) {
                            RuleType.values().forEach { t ->
                                DropdownMenuItem(
                                    text = { Text(t.name) },
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
                            if (ruleType == RuleType.DOMAIN) "Domain (e.g. ads.example.com)"
                            else "Package (e.g. com.example.app)"
                        )
                    },
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth(),
                )

                // Action toggle
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Action:", fontSize = 14.sp)
                    FilterChip(
                        selected = blockAction,
                        onClick  = { blockAction = true },
                        label    = { Text("Block") },
                        leadingIcon = {
                            if (blockAction) Icon(Icons.Default.Check, null, modifier = Modifier.size(16.dp))
                        },
                    )
                    FilterChip(
                        selected = !blockAction,
                        onClick  = { blockAction = false },
                        label    = { Text("Allow") },
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
                enabled = value.isNotBlank(),
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
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
            TextButton(onClick = onDismiss) { Text("Skip", fontSize = 12.sp) }
            Button(
                onClick = onAccept,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Text("Add", fontSize = 12.sp)
            }
        }
    }
}
