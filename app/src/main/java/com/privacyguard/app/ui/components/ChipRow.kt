package com.privacyguard.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.privacyguard.app.R
import com.privacyguard.app.ui.theme.CoreLockTheme

/**
 * Chips that scroll sideways instead of being squeezed: on App detail the old chips were
 * cut in French, Dutch and at 200 % font (UX audit). Each chip keeps Material's 48 dp
 * touch area; the selected one has a check mark, not only a colour.
 */
@Composable
fun <T> ChipRow(
    items: List<T>,
    selected: T?,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CoreLockTheme.colors
    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(CoreLockTheme.spacing.s),
        contentPadding = PaddingValues(horizontal = CoreLockTheme.spacing.l),
    ) {
        items(items) { item ->
            val isSelected = item == selected
            FilterChip(
                selected = isSelected,
                onClick = { onSelect(item) },
                label = { Text(label(item), style = MaterialTheme.typography.labelLarge) },
                leadingIcon = if (isSelected) {
                    { Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                } else null,
                shape = CoreLockTheme.shapes.small,
                modifier = Modifier.heightIn(min = CoreLockTheme.spacing.minTouchTarget).widthIn(min = CoreLockTheme.spacing.minTouchTarget),
                border = BorderStroke(1.dp, if (isSelected) colors.primary else colors.outline),
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = colors.surface,
                    labelColor = colors.onSurface,
                    selectedContainerColor = colors.protectedContainer,
                    selectedLabelColor = colors.onProtectedContainer,
                    selectedLeadingIconColor = colors.onProtectedContainer,
                ),
            )
        }
    }
}

@CoreLockPreviews
@Composable
private fun ChipRowPreview() = PreviewSurface {
    val labels = listOf(
        R.string.ui_filter_all, R.string.ui_risk_trackers, R.string.ui_risk_background,
        R.string.ui_risk_unprotected, R.string.ui_risk_high,
    )
    var selected by remember { mutableIntStateOf(0) }
    ChipRow(labels.indices.toList(), selected, { stringResource(labels[it]) }, { selected = it })
}
