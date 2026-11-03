package com.privacyguard.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.privacyguard.app.R
import com.privacyguard.app.ui.theme.CoreLockTheme

/**
 * Two to four mutually exclusive choices. The selected segment is filled and carries a
 * check mark, so the choice is visible without colour.
 *
 * Segments sit side by side with equal widths and heights while every label's longest
 * word fits. When one doesn't (French or Dutch at 200 % font: "Geblokkeerd op de
 * achtergrond"), the segments stack vertically at full width instead of breaking words
 * in the middle.
 */
@Composable
fun <T> SegmentedControl(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CoreLockTheme.colors
    val shape = CoreLockTheme.shapes.small
    val gap = CoreLockTheme.spacing.xs
    Layout(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surfaceVariant)
            .border(BorderStroke(1.dp, colors.outline), shape)
            .padding(gap)
            .selectableGroup(),
        content = {
            options.forEach { option ->
                Segment(label(option), option == selected) { onSelect(option) }
            }
        },
    ) { measurables, constraints ->
        val gapPx = gap.roundToPx()
        val width = constraints.maxWidth
        val count = measurables.size.coerceAtLeast(1)
        val cell = ((width - gapPx * (count - 1)) / count).coerceAtLeast(0)
        // minIntrinsicWidth of a text is its longest word (plus padding and the check).
        val fitsInRow = measurables.all { it.minIntrinsicWidth(Constraints.Infinity) <= cell }
        if (fitsInRow) {
            val rowHeight = measurables.maxOf { it.minIntrinsicHeight(cell) }
            val placeables = measurables.map { it.measure(Constraints.fixed(cell, rowHeight)) }
            layout(width, rowHeight) {
                placeables.forEachIndexed { i, p -> p.placeRelative(i * (cell + gapPx), 0) }
            }
        } else {
            val placeables = measurables.map { it.measure(Constraints(minWidth = width, maxWidth = width)) }
            val height = placeables.sumOf { it.height } + gapPx * (placeables.size - 1)
            layout(width, height) {
                var y = 0
                placeables.forEach { p -> p.placeRelative(0, y); y += p.height + gapPx }
            }
        }
    }
}

@Composable
private fun Segment(text: String, isSelected: Boolean, onClick: () -> Unit) {
    val colors = CoreLockTheme.colors
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .heightIn(min = CoreLockTheme.spacing.minTouchTarget)
            .clip(CoreLockTheme.shapes.small)
            .background(if (isSelected) colors.primary else colors.surfaceVariant)
            .selectable(selected = isSelected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = CoreLockTheme.spacing.s, vertical = CoreLockTheme.spacing.s),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (isSelected) {
                Icon(Icons.Rounded.Check, contentDescription = null, tint = colors.onPrimary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(CoreLockTheme.spacing.xs))
            }
            Text(
                text,
                style = MaterialTheme.typography.labelLarge,
                color = if (isSelected) colors.onPrimary else colors.onSurface,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@CoreLockPreviews
@Composable
private fun SegmentedControlPreview() = PreviewSurface {
    var app by remember { mutableIntStateOf(1) }
    var filter by remember { mutableIntStateOf(0) }
    val appLabels = listOf(R.string.ui_app_allowed, R.string.ui_app_blocked_background, R.string.ui_app_blocked)
    val filterLabels = listOf(R.string.ui_filter_blocked, R.string.ui_filter_all)
    Column(verticalArrangement = Arrangement.spacedBy(CoreLockTheme.spacing.m)) {
        SegmentedControl(listOf(0, 1), filter, { stringResource(filterLabels[it]) }, { filter = it })
        SegmentedControl(listOf(0, 1, 2), app, { stringResource(appLabels[it]) }, { app = it })
    }
}
