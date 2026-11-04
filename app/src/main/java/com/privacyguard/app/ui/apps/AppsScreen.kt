package com.privacyguard.app.ui.apps

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.privacyguard.app.R
import com.privacyguard.app.ui.activity.rowShape
import com.privacyguard.app.ui.components.AppIconImage
import com.privacyguard.app.ui.components.AppRow
import com.privacyguard.app.ui.components.AttentionBanner
import com.privacyguard.app.ui.components.BannerTone
import com.privacyguard.app.ui.components.EmptyState
import com.privacyguard.app.ui.components.ErrorState
import com.privacyguard.app.ui.components.InfoSheet
import com.privacyguard.app.ui.components.LoadingSkeleton
import com.privacyguard.app.ui.components.SegmentedControl
import com.privacyguard.app.ui.theme.CoreLockTheme

/** What the Apps screen can ask for; a plain class so tests drive the content directly. */
class AppsActions(
    val onQuery: (String) -> Unit = {},
    val onFilter: (AppsFilter) -> Unit = {},
    val onSort: (AppSort) -> Unit = {},
    val onRetry: () -> Unit = {},
    val onOpen: (AppRiskItem) -> Unit = {},
)

/**
 * Apps (DESIGN_SPEC_1.1.md §6.3): every app, what it may do, and one plain word about
 * its last 24 hours. The word is explained in App detail, not by a second button inside
 * the row: a tap near the middle of the row hit that button instead of opening the app.
 * Blocking is in App detail too, where the choice is explained; the row has no switch,
 * grade or package name.
 */
@Composable
fun AppsScreen(
    onAppClick: (packageName: String, appName: String) -> Unit = { _, _ -> },
    viewModel: AppsViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    AppsContent(
        state,
        AppsActions(
            onQuery = viewModel::setQuery,
            onFilter = viewModel::setFilter,
            onSort = viewModel::setSort,
            onRetry = viewModel::retry,
            onOpen = { onAppClick(it.packageName, it.appName) },
        ),
    )
}

@Composable
fun AppsContent(state: AppsUiState, actions: AppsActions) {
    val spacing = CoreLockTheme.spacing
    // Filter and sort only when the list or a choice changed, not on every recomposition.
    val shown = remember(state.list, state.query, state.filter, state.sort) { state.shown }

    LazyColumn(Modifier.fillMaxSize().testTag(TAG_APPS_LIST)) {
        item(key = "header") { AppsHeader(state, actions) }

        if (state.couldWatch > 0) {
            item(key = "watch") {
                AttentionBanner(
                    message = pluralStringResource(R.plurals.ui_apps_could_watch, state.couldWatch, state.couldWatch),
                    actionLabel = stringResource(R.string.ui_show_them),
                    onAction = { actions.onFilter(AppsFilter.ALL); actions.onSort(AppSort.RISK) },
                    tone = BannerTone.DANGER,
                    modifier = Modifier.padding(horizontal = spacing.screen, vertical = spacing.s),
                )
            }
        }

        when (val list = state.list) {
            AppsList.Loading -> item(key = "loading") { LoadingSkeleton(rows = 6) }
            AppsList.Error -> item(key = "error") { ErrorState(stringResource(R.string.ui_error_apps), onRetry = actions.onRetry) }
            is AppsList.Loaded -> if (shown.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        when {
                            state.query.isNotBlank() -> stringResource(R.string.ui_apps_empty_search, state.query.trim())
                            state.filter == AppsFilter.BLOCKED -> stringResource(R.string.ui_apps_empty_blocked)
                            state.filter == AppsFilter.ACTIVE_NOW -> stringResource(R.string.ui_apps_empty_active)
                            else -> stringResource(R.string.ui_apps_empty)
                        },
                    )
                }
            } else {
                itemsIndexed(shown, key = { _, app -> app.packageName }) { i, app ->
                    val word = AppsLogic.riskWord(app)
                    Column(
                        Modifier
                            .padding(horizontal = spacing.screen)
                            .clip(rowShape(i == 0, i == shown.lastIndex))
                            .background(CoreLockTheme.colors.surface),
                    ) {
                        AppRow(
                            name = app.appName,
                            status = stringResource(app.status.label),
                            onClick = { actions.onOpen(app) },
                            icon = { AppIconImage(app.packageName) },
                            riskWord = stringResource(word.label),
                            riskTone = word.tone,
                            blockedToday = app.blockedToday,
                            modifier = Modifier.testTag("$TAG_APP_ROW${app.packageName}"),
                        )
                        if (i != shown.lastIndex) HorizontalDivider(color = CoreLockTheme.colors.surfaceVariant)
                    }
                }
            }
        }
        item(key = "bottom") { Box(Modifier.padding(bottom = spacing.xxxl)) }
    }
}

/** The word's explanation (spec §6.3.1); for "Could watch you", with the reasons found. */
@Composable
internal fun RiskWordSheet(app: AppRiskItem, onDismiss: () -> Unit) {
    val word = AppsLogic.riskWord(app)
    val body = if (word == RiskWord.COULD_WATCH) {
        stringResource(word.explanation, app.stalkerwareReasons.joinToString(", ").ifBlank { stringResource(R.string.ui_risk_watch) })
    } else {
        stringResource(word.explanation)
    }
    InfoSheet(title = stringResource(word.label), body = body, onDismiss = onDismiss)
}

@Composable
private fun AppsHeader(state: AppsUiState, actions: AppsActions) {
    val spacing = CoreLockTheme.spacing
    var menu by remember { mutableStateOf(false) }
    Column(
        verticalArrangement = Arrangement.spacedBy(spacing.m),
        modifier = Modifier.padding(start = spacing.screen, end = spacing.screen, top = spacing.l, bottom = spacing.m),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.apps_title),
                style = MaterialTheme.typography.titleLarge,
                color = CoreLockTheme.colors.onSurface,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.testTag(TAG_SORT)) {
                    Icon(Icons.AutoMirrored.Rounded.Sort, contentDescription = stringResource(R.string.ui_sort_by))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    Text(
                        stringResource(R.string.ui_sort_by),
                        style = MaterialTheme.typography.labelMedium,
                        color = CoreLockTheme.colors.onSurfaceMuted,
                        modifier = Modifier.padding(horizontal = spacing.l, vertical = spacing.s).semantics { heading() },
                    )
                    AppSort.entries.forEach { sort ->
                        DropdownMenuItem(
                            text = { Text(stringResource(sort.label)) },
                            // Selected = a check mark, not colour alone.
                            leadingIcon = { if (sort == state.sort) Icon(Icons.Rounded.Check, contentDescription = null) },
                            onClick = { menu = false; actions.onSort(sort) },
                        )
                    }
                }
            }
        }
        SearchField(state.query, actions.onQuery)
        SegmentedControl(
            options = AppsFilter.entries,
            selected = state.filter,
            label = { stringResource(it.label) },
            onSelect = actions.onFilter,
        )
    }
}

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit) {
    val colors = CoreLockTheme.colors
    OutlinedTextField(
        value = query,
        onValueChange = onQuery,
        modifier = Modifier.fillMaxWidth().testTag(TAG_SEARCH),
        placeholder = { Text(stringResource(R.string.ui_apps_search), color = colors.onSurfaceMuted) },
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
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = colors.surface,
            unfocusedContainerColor = colors.surface,
            focusedBorderColor = colors.primary,
            unfocusedBorderColor = colors.surfaceVariant,
            focusedTextColor = colors.onSurface,
            unfocusedTextColor = colors.onSurface,
            cursorColor = colors.primary,
        ),
    )
}

internal const val TAG_APPS_LIST = "apps_list"
internal const val TAG_APP_ROW = "app_row:"
internal const val TAG_SORT = "apps_sort"
internal const val TAG_SEARCH = "apps_search"
