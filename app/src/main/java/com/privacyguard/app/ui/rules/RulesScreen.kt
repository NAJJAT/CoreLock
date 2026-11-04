package com.privacyguard.app.ui.rules

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.GppBad
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Rule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.privacyguard.app.R
import com.privacyguard.app.ui.activity.rowShape
import com.privacyguard.app.ui.components.CoreLockBottomSheet
import com.privacyguard.app.ui.components.CoreLockTopBar
import com.privacyguard.app.ui.components.EmptyState
import com.privacyguard.app.ui.components.ErrorState
import com.privacyguard.app.ui.components.LoadingSkeleton
import com.privacyguard.app.ui.components.PrimaryButton
import com.privacyguard.app.ui.components.SectionHeader
import com.privacyguard.app.ui.components.SecondaryButton
import com.privacyguard.app.ui.components.SegmentedControl
import com.privacyguard.app.ui.theme.CoreLockTheme
import com.privacyguard.core.filter.FilterRule
import com.privacyguard.core.metadata.EncryptionStatus

/** Everything the Rules list shows. Built from [RulesViewModel]; tests build it directly. */
data class RulesUiState(
    val loaded: Boolean = false,
    val loadFailed: Boolean = false,
    /** The rules that match [query]. */
    val rules: List<FilterRule> = emptyList(),
    val hasAnyRule: Boolean = false,
    val suggestions: List<SuggestedRule> = emptyList(),
    val query: String = "",
    val appNames: Map<String, String> = emptyMap(),
)

data class RulesActions(
    val onBack: () -> Unit = {},
    val onQuery: (String) -> Unit = {},
    val onAdd: (RulesLogic.Kind, String, FilterRule.Action) -> Unit = { _, _, _ -> },
    val onToggle: (FilterRule, Boolean) -> Unit = { _, _ -> },
    val onDelete: (FilterRule) -> Unit = {},
    val onAcceptSuggestion: (SuggestedRule) -> Unit = {},
    val onSkipSuggestion: (SuggestedRule) -> Unit = {},
    val onRetry: () -> Unit = {},
)

/**
 * Settings › Rules and allowed sites (DESIGN_SPEC_1.1.md R16, §5.6): the user's own
 * rules, each said in words (what it does, to what, how often it was used), with an
 * on/off switch and a delete that can be undone. Most rules are made where the problem
 * is (Activity, Apps); this screen is where they are reviewed, and where an expert can
 * type one in.
 */
@Composable
fun RulesScreen(onBack: () -> Unit, vm: RulesViewModel = viewModel()) {
    val loaded by vm.loaded.collectAsStateWithLifecycle()
    val loadFailed by vm.loadFailed.collectAsStateWithLifecycle()
    val rules by vm.rules.collectAsStateWithLifecycle()
    val hasAnyRule by vm.hasAnyRule.collectAsStateWithLifecycle()
    val suggestions by vm.suggestions.collectAsStateWithLifecycle()
    val query by vm.searchQuery.collectAsStateWithLifecycle()
    val appNames by vm.appNames.collectAsStateWithLifecycle()
    val securityWarning by vm.securityWarning.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val undoLabel = stringResource(R.string.ui_undo)

    LaunchedEffect(message?.id) {
        val m = message ?: return@LaunchedEffect
        val r = snackbar.showSnackbar(m.text, actionLabel = m.undo?.let { undoLabel }, duration = if (m.undo != null) SnackbarDuration.Long else SnackbarDuration.Short)
        if (r == SnackbarResult.ActionPerformed) m.undo?.invoke()
        vm.messageShown(m.id)
    }

    Box(Modifier.fillMaxSize().background(CoreLockTheme.colors.background)) {
        RulesContent(
            state = RulesUiState(loaded, loadFailed, rules, hasAnyRule, suggestions, query, appNames),
            actions = RulesActions(
                onBack = onBack,
                onQuery = vm::setSearchQuery,
                onAdd = { kind, value, action ->
                    when (kind) {
                        RulesLogic.Kind.SITE -> vm.addDomainRule(value, action)
                        RulesLogic.Kind.APP -> vm.addPackageRule(value, action)
                        RulesLogic.Kind.ADDRESS -> vm.addIpRule(value, action)
                    }
                },
                onToggle = { rule, on -> vm.toggleRule(rule.id, on) },
                onDelete = vm::deleteRule,
                onAcceptSuggestion = vm::acceptSuggestion,
                onSkipSuggestion = { vm.dismissSuggestion(it.id) },
                onRetry = vm::retry,
            ),
        )
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(CoreLockTheme.spacing.l))
    }

    securityWarning?.let { domain ->
        SecurityAllowWarningDialog(
            domain = domain,
            onConfirm = { vm.addDomainRule(domain, FilterRule.Action.ALLOW, confirmedRisk = true) },
            onDismiss = vm::dismissSecurityWarning,
        )
    }
}

@Composable
fun RulesContent(state: RulesUiState, actions: RulesActions) {
    val spacing = CoreLockTheme.spacing
    // Saved, so the sheet and what was typed survive rotation.
    var adding by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        CoreLockTopBar(
            title = stringResource(R.string.ui_set_rules),
            onBack = actions.onBack,
            actions = {
                IconButton(onClick = { adding = true }, modifier = Modifier.testTag(TAG_RULES_ADD)) {
                    Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.ui_rules_add))
                }
            },
        )
        LazyColumn(Modifier.fillMaxSize().testTag(TAG_RULES_LIST)) {
            when {
                !state.loaded -> item(key = "loading") { LoadingSkeleton(rows = 5) }
                state.loadFailed && !state.hasAnyRule -> item(key = "error") {
                    ErrorState(stringResource(R.string.ui_rules_error), onRetry = actions.onRetry)
                }
                else -> {
                    if (state.suggestions.isNotEmpty()) {
                        item(key = "sugg_header") { SectionHeader(stringResource(R.string.rules_suggested), Modifier.padding(horizontal = spacing.screen)) }
                        itemsIndexed(state.suggestions, key = { _, s -> "s:" + s.id }) { i, s ->
                            Grouped(i == 0, i == state.suggestions.lastIndex) {
                                SuggestionRow(s, onAccept = { actions.onAcceptSuggestion(s) }, onSkip = { actions.onSkipSuggestion(s) })
                            }
                        }
                    }
                    if (!state.hasAnyRule) {
                        item(key = "empty") {
                            EmptyState(
                                stringResource(R.string.ui_rules_empty),
                                icon = Icons.Rounded.Rule,
                                actionLabel = stringResource(R.string.ui_rules_add),
                                onAction = { adding = true },
                            )
                        }
                    } else {
                        item(key = "search") {
                            RulesSearchField(state.query, actions.onQuery, Modifier.padding(horizontal = spacing.screen, vertical = spacing.s))
                        }
                        item(key = "rules_header") {
                            SectionHeader(
                                pluralStringResource(R.plurals.ui_rules_yours, state.rules.size, state.rules.size),
                                Modifier.padding(horizontal = spacing.screen),
                            )
                        }
                        if (state.rules.isEmpty()) {
                            item(key = "no_match") { EmptyState(stringResource(R.string.ui_rules_no_match, state.query.trim()), icon = Icons.Rounded.Search) }
                        }
                        itemsIndexed(state.rules, key = { _, r -> r.id }) { i, rule ->
                            Grouped(i == 0, i == state.rules.lastIndex) {
                                RuleRow(
                                    rule = rule,
                                    appName = rule.matchPackage?.let(state.appNames::get),
                                    onToggle = { actions.onToggle(rule, it) },
                                    onDelete = { actions.onDelete(rule) },
                                )
                            }
                        }
                    }
                    // Room for the snackbar, so it never covers the last row's delete.
                    item(key = "bottom") { Spacer(Modifier.heightIn(min = 96.dp)) }
                }
            }
        }
    }

    if (adding) {
        CoreLockBottomSheet(title = stringResource(R.string.ui_rules_add), onDismiss = { adding = false }) {
            AddRuleForm(
                onAdd = { kind, value, action -> actions.onAdd(kind, value, action); adding = false },
                onCancel = { adding = false },
            )
        }
    }
}

/** Rows of one section share a card: rounded at its ends, a divider between rows. */
@Composable
private fun Grouped(first: Boolean, last: Boolean, content: @Composable () -> Unit) {
    Column(
        Modifier
            .padding(horizontal = CoreLockTheme.spacing.screen)
            .clip(rowShape(first, last))
            .background(CoreLockTheme.colors.surface),
    ) {
        content()
        if (!last) HorizontalDivider(color = CoreLockTheme.colors.surfaceVariant)
    }
}

/**
 * One rule: its name, what it does in words (never a coloured badge alone), what it
 * matches, how often it was used, the switch and delete. An Allow that overrides a
 * malware list says so in the danger colour, with an icon: it is the riskiest rule a
 * user can have and must never look like an ordinary Allow.
 */
@Composable
internal fun RuleRow(rule: FilterRule, appName: String?, onToggle: (Boolean) -> Unit, onDelete: () -> Unit) {
    val colors = CoreLockTheme.colors
    val effect = RulesLogic.effectOf(rule)
    val (icon, tint) = when (effect) {
        RulesLogic.Effect.BLOCK, RulesLogic.Effect.BLOCK_IN_BACKGROUND -> Icons.Rounded.Block to colors.onSurfaceMuted
        RulesLogic.Effect.ALLOW -> Icons.Rounded.CheckCircle to colors.protected
        RulesLogic.Effect.ALLOW_DESPITE_MALWARE -> Icons.Rounded.GppBad to colors.danger
    }
    val effectWord = stringResource(
        when (effect) {
            RulesLogic.Effect.BLOCK -> R.string.ui_rules_effect_block
            RulesLogic.Effect.BLOCK_IN_BACKGROUND -> R.string.ui_rules_effect_block_bg
            RulesLogic.Effect.ALLOW -> R.string.ui_rules_effect_allow
            RulesLogic.Effect.ALLOW_DESPITE_MALWARE -> R.string.ui_rules_effect_allow_risky
        },
    )
    val off = stringResource(R.string.ui_rules_off)
    val details = listOfNotNull(
        rule.matchDomain?.let { stringResource(R.string.ui_rules_d_site, it) },
        rule.matchPackage?.let { stringResource(R.string.ui_rules_d_app, appName ?: it) },
        rule.matchIp?.let { stringResource(R.string.ui_rules_d_address, it) },
        rule.matchPort?.let { stringResource(R.string.ui_rules_d_port, it) },
        rule.matchProtocol?.takeIf { it != FilterRule.Protocol.ANY }?.name,
        if (rule.matchEncryption == EncryptionStatus.CLEARTEXT) stringResource(R.string.ui_rules_d_unencrypted) else null,
    ).joinToString(" · ")
    val switchLabel = stringResource(R.string.ui_rules_use, rule.label)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .testTag(TAG_RULE_ROW + rule.id)
            .padding(start = CoreLockTheme.spacing.l, end = CoreLockTheme.spacing.xs, top = CoreLockTheme.spacing.m, bottom = CoreLockTheme.spacing.m),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(rule.label, style = MaterialTheme.typography.titleMedium, color = if (rule.isEnabled) colors.onSurface else colors.onSurfaceMuted)
            // Top-aligned: when the words wrap (Arabic, 200 %), the icon stays on the first line.
            Row(verticalAlignment = Alignment.Top) {
                Icon(icon, contentDescription = null, tint = if (rule.isEnabled) tint else colors.onSurfaceMuted, modifier = Modifier.padding(top = 2.dp).size(16.dp))
                Spacer(Modifier.width(CoreLockTheme.spacing.xs))
                Text(
                    if (rule.isEnabled) effectWord else "$effectWord · $off",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (effect == RulesLogic.Effect.ALLOW_DESPITE_MALWARE && rule.isEnabled) colors.danger else colors.onSurfaceMuted,
                )
            }
            if (details.isNotEmpty()) Text(details, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceMuted)
            if (rule.hitCount > 0) {
                Text(pluralStringResource(R.plurals.rules_triggered, rule.hitCount, rule.hitCount), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceMuted)
            }
        }
        Spacer(Modifier.width(CoreLockTheme.spacing.s))
        Switch(
            checked = rule.isEnabled,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(checkedTrackColor = colors.primary, checkedThumbColor = colors.onPrimary),
            modifier = Modifier.semantics { contentDescription = switchLabel }.testTag(TAG_RULE_SWITCH + rule.id),
        )
        // 48 dp (the 1.0 delete was 32 dp, DESIGN_SPEC_1.1.md §2.6). Undo follows, so no confirmation.
        IconButton(onClick = onDelete, modifier = Modifier.size(CoreLockTheme.spacing.minTouchTarget).testTag(TAG_RULE_DELETE + rule.id)) {
            Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.ui_rules_delete, rule.label), tint = colors.onSurfaceMuted)
        }
    }
}

@Composable
private fun SuggestionRow(suggestion: SuggestedRule, onAccept: () -> Unit, onSkip: () -> Unit) {
    val colors = CoreLockTheme.colors
    Column(
        verticalArrangement = Arrangement.spacedBy(CoreLockTheme.spacing.s),
        modifier = Modifier.fillMaxWidth().padding(CoreLockTheme.spacing.l),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(Icons.Rounded.Lightbulb, contentDescription = null, tint = colors.attention, modifier = Modifier.padding(top = 2.dp).size(20.dp))
            Spacer(Modifier.width(CoreLockTheme.spacing.m))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(suggestion.label, style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
                Text(suggestion.reason, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceMuted)
            }
        }
        // Buttons on their own line: at 200 % font two buttons beside the text don't fit.
        Row(horizontalArrangement = Arrangement.spacedBy(CoreLockTheme.spacing.s), modifier = Modifier.fillMaxWidth()) {
            SecondaryButton(stringResource(R.string.rules_skip), onSkip, Modifier.weight(1f))
            PrimaryButton(stringResource(R.string.rules_add_button), onAccept, Modifier.weight(1f))
        }
    }
}

/**
 * The add sheet: what (site, app, address), the name, Block or Allow. Add is enabled only
 * for something a rule can match; a wrong value is explained under the field instead of
 * being dropped (1.0 ignored an invalid address without a word). An Allow for a site on a
 * malware list still goes through the warning, after this sheet closes.
 */
@Composable
internal fun AddRuleForm(onAdd: (RulesLogic.Kind, String, FilterRule.Action) -> Unit, onCancel: () -> Unit) {
    val colors = CoreLockTheme.colors
    var kind by rememberSaveable { mutableStateOf(RulesLogic.Kind.SITE) }
    var value by rememberSaveable { mutableStateOf("") }
    var block by rememberSaveable { mutableStateOf(true) }
    val error = RulesLogic.showsError(kind, value)
    val valid = RulesLogic.isValid(kind, value)
    val submit = { if (valid) onAdd(kind, value.trim(), if (block) FilterRule.Action.DENY else FilterRule.Action.ALLOW) }

    SegmentedControl(
        options = RulesLogic.Kind.entries,
        selected = kind,
        label = {
            stringResource(
                when (it) {
                    RulesLogic.Kind.SITE -> R.string.ui_rules_kind_site
                    RulesLogic.Kind.APP -> R.string.ui_rules_kind_app
                    RulesLogic.Kind.ADDRESS -> R.string.ui_rules_kind_address
                },
            )
        },
        onSelect = { kind = it },
    )
    OutlinedTextField(
        value = value,
        onValueChange = { value = it },
        label = {
            Text(
                stringResource(
                    when (kind) {
                        RulesLogic.Kind.SITE -> R.string.ui_rules_site_label
                        RulesLogic.Kind.APP -> R.string.ui_rules_app_label
                        RulesLogic.Kind.ADDRESS -> R.string.ui_rules_address_label
                    },
                ),
            )
        },
        supportingText = {
            Text(
                stringResource(
                    when {
                        error && kind == RulesLogic.Kind.SITE -> R.string.ui_rules_site_invalid
                        error && kind == RulesLogic.Kind.APP -> R.string.ui_rules_app_invalid
                        error -> R.string.rules_ip_invalid
                        kind == RulesLogic.Kind.SITE -> R.string.rules_domain_exact
                        kind == RulesLogic.Kind.APP -> R.string.ui_rules_app_hint
                        else -> R.string.ui_rules_address_hint
                    },
                ),
            )
        },
        isError = error,
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (kind == RulesLogic.Kind.ADDRESS) KeyboardType.Decimal else KeyboardType.Uri,
            imeAction = ImeAction.Done,
            autoCorrectEnabled = false,
        ),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { submit() }),
        shape = CoreLockTheme.shapes.small,
        colors = fieldColors(),
        modifier = Modifier.fillMaxWidth().testTag(TAG_RULES_FIELD),
    )
    SegmentedControl(
        options = listOf(true, false),
        selected = block,
        label = { stringResource(if (it) R.string.ads_block else R.string.ads_allow) },
        onSelect = { block = it },
    )
    if (!block) {
        Text(stringResource(R.string.ui_rules_allow_note), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceMuted)
    }
    PrimaryButton(stringResource(R.string.rules_add_button), onClick = submit, enabled = valid, modifier = Modifier.fillMaxWidth().testTag(TAG_RULES_SUBMIT))
    SecondaryButton(stringResource(android.R.string.cancel), onClick = onCancel, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun RulesSearchField(query: String, onQuery: (String) -> Unit, modifier: Modifier) {
    val colors = CoreLockTheme.colors
    OutlinedTextField(
        value = query,
        onValueChange = onQuery,
        modifier = modifier.fillMaxWidth().testTag(TAG_RULES_SEARCH),
        placeholder = { Text(stringResource(R.string.ui_rules_search), color = colors.onSurfaceMuted) },
        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, tint = colors.onSurfaceMuted) },
        trailingIcon = if (query.isEmpty()) null else {
            {
                IconButton(onClick = { onQuery("") }) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.ui_clear_search))
                }
            }
        },
        singleLine = true,
        shape = CoreLockTheme.shapes.small,
        colors = fieldColors(),
    )
}

@Composable
private fun fieldColors() = CoreLockTheme.colors.let { colors ->
    OutlinedTextFieldDefaults.colors(
        focusedContainerColor = colors.surface,
        unfocusedContainerColor = colors.surface,
        focusedBorderColor = colors.primary,
        unfocusedBorderColor = colors.outline,
        focusedTextColor = colors.onSurface,
        unfocusedTextColor = colors.onSurface,
        cursorColor = colors.primary,
        errorBorderColor = colors.danger,
        errorSupportingTextColor = colors.danger,
        errorLabelColor = colors.danger,
    )
}

internal const val TAG_RULES_LIST = "rules_list"
internal const val TAG_RULES_ADD = "rules_add"
internal const val TAG_RULES_SEARCH = "rules_search"
internal const val TAG_RULES_FIELD = "rules_field"
internal const val TAG_RULES_SUBMIT = "rules_submit"
internal const val TAG_RULE_ROW = "rule_row:"
internal const val TAG_RULE_SWITCH = "rule_switch:"
internal const val TAG_RULE_DELETE = "rule_delete:"
