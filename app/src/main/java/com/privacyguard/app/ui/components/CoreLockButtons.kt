package com.privacyguard.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.privacyguard.app.R
import com.privacyguard.app.ui.theme.CoreLockTheme

/*
 * Buttons are verbs in sentence case ("Allow everywhere", never "ALLOW"). All are at
 * least 48 dp tall, and the label wraps instead of being cut at 200 % font.
 */

private val ButtonPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null,
) {
    val colors = CoreLockTheme.colors
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = CoreLockTheme.shapes.small,
        contentPadding = ButtonPadding,
        colors = ButtonDefaults.buttonColors(containerColor = colors.primary, contentColor = colors.onPrimary),
        modifier = modifier.heightIn(min = CoreLockTheme.spacing.minTouchTarget),
    ) { ButtonContent(text, leadingIcon) }
}

@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null,
) {
    val colors = CoreLockTheme.colors
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = CoreLockTheme.shapes.small,
        contentPadding = ButtonPadding,
        border = BorderStroke(1.dp, colors.outline),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.primary),
        modifier = modifier.heightIn(min = CoreLockTheme.spacing.minTouchTarget),
    ) { ButtonContent(text, leadingIcon) }
}

/** For actions that delete or turn protection off. Always paired with a confirmation. */
@Composable
fun DestructiveButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = CoreLockTheme.colors
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = CoreLockTheme.shapes.small,
        contentPadding = ButtonPadding,
        colors = ButtonDefaults.buttonColors(containerColor = colors.dangerContainer, contentColor = colors.onDangerContainer),
        modifier = modifier.heightIn(min = CoreLockTheme.spacing.minTouchTarget),
    ) { ButtonContent(text, null) }
}

@Composable
private fun ButtonContent(text: String, icon: ImageVector?) {
    if (icon != null) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(CoreLockTheme.spacing.s))
    }
    Text(text, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
}

@CoreLockPreviews
@Composable
private fun ButtonsPreview() = PreviewSurface {
    Column(verticalArrangement = Arrangement.spacedBy(CoreLockTheme.spacing.m)) {
        PrimaryButton(stringResource(R.string.ui_allow_everywhere), onClick = {})
        SecondaryButton(stringResource(R.string.ui_something_not_working), onClick = {}, leadingIcon = Icons.Rounded.Build)
        DestructiveButton(stringResource(R.string.ui_clear_all_data), onClick = {})
    }
}
