package com.privacyguard.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.privacyguard.app.R
import com.privacyguard.app.ui.theme.CoreLockTheme

/**
 * A row whose main job is a switch. The whole row toggles (one focusable control, read
 * as "title, subtitle, switch, on"); the visual switch has no click handler of its own.
 */
@Composable
fun PrimaryToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    val colors = CoreLockTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = CoreLockTheme.spacing.minTouchTarget)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = CoreLockTheme.spacing.l, vertical = CoreLockTheme.spacing.m),
    ) {
        RowTexts(title, subtitle, Modifier.weight(1f))
        Spacer(Modifier.width(CoreLockTheme.spacing.l))
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedTrackColor = colors.primary,
                checkedThumbColor = colors.onPrimary,
                uncheckedBorderColor = colors.outline,
                uncheckedThumbColor = colors.outline,
                uncheckedTrackColor = colors.surface,
            ),
        )
    }
}

/**
 * A row that opens something or shows a value: title (about five words), one-line
 * subtitle, optional value, chevron. Longer explanations go in an [InfoSheet].
 */
@Composable
fun SettingRow(
    title: String,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    value: String? = null,
    leadingIcon: ImageVector? = null,
    // A destructive action ("Clear all data"): the title in the danger colour. It still
    // only opens a confirmation; nothing is deleted by the row itself.
    danger: Boolean = false,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = CoreLockTheme.spacing.minTouchTarget)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = CoreLockTheme.spacing.l, vertical = CoreLockTheme.spacing.m),
    ) {
        if (leadingIcon != null) {
            Icon(leadingIcon, contentDescription = null, tint = CoreLockTheme.colors.onSurfaceMuted, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(CoreLockTheme.spacing.l))
        }
        RowTexts(title, subtitle, Modifier.weight(1f), titleColor = if (danger) CoreLockTheme.colors.danger else CoreLockTheme.colors.onSurface)
        if (value != null) {
            Spacer(Modifier.width(CoreLockTheme.spacing.s))
            Text(value, style = MaterialTheme.typography.bodyMedium, color = CoreLockTheme.colors.onSurfaceMuted)
        }
        // No chevron on a row that shows a value only (Version): it would promise a screen.
        if (onClick != null) {
            Spacer(Modifier.width(CoreLockTheme.spacing.s))
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = CoreLockTheme.colors.onSurfaceMuted,
            )
        }
    }
}

/** Groups rows. Sentence case, read by TalkBack as a heading so users can jump between sections. */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = CoreLockTheme.colors.onSurfaceMuted,
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = CoreLockTheme.spacing.l,
                end = CoreLockTheme.spacing.l,
                top = CoreLockTheme.spacing.xl,
                bottom = CoreLockTheme.spacing.s,
            )
            .semantics { heading() },
    )
}

@Composable
private fun RowTexts(
    title: String,
    subtitle: String?,
    modifier: Modifier,
    titleColor: androidx.compose.ui.graphics.Color = CoreLockTheme.colors.onSurface,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = titleColor)
        if (subtitle != null) {
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = CoreLockTheme.colors.onSurfaceMuted)
        }
    }
}

@CoreLockPreviews
@Composable
private fun RowsPreview() = PreviewSurface {
    var on by remember { mutableStateOf(true) }
    Column {
        SectionHeader(stringResource(R.string.ui_protection))
        SettingRow(
            title = stringResource(R.string.ui_protection_level),
            subtitle = stringResource(R.string.ui_level_standard_desc),
            leadingIcon = Icons.Rounded.Tune,
            onClick = {},
        )
        PrimaryToggleRow(
            title = stringResource(R.string.ui_restart_after_crash),
            subtitle = stringResource(R.string.ui_restart_after_crash_sub),
            checked = on,
            onCheckedChange = { on = it },
        )
        SettingRow(title = stringResource(R.string.ui_advanced), subtitle = stringResource(R.string.ui_advanced_sub), onClick = {})
    }
}
