package com.privacyguard.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.privacyguard.app.R
import com.privacyguard.app.ui.theme.CoreLockTheme

enum class BannerTone { ATTENTION, DANGER }

/**
 * At most one per screen: one sentence and one verb ("Open settings"). [onDismiss] is
 * only given when the problem is optional; a real problem can't be swiped away.
 * DANGER is for real danger only (malware, protection stopped unexpectedly).
 */
@Composable
fun AttentionBanner(
    message: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    tone: BannerTone = BannerTone.ATTENTION,
    onDismiss: (() -> Unit)? = null,
    /** A second verb, only where the card links to two places ("Some things moved"). */
    secondaryAction: Pair<String, () -> Unit>? = null,
) {
    val colors = CoreLockTheme.colors
    val (container, content, icon) = when (tone) {
        BannerTone.ATTENTION -> Triple(colors.attentionContainer, colors.onAttentionContainer, Icons.Rounded.Info)
        BannerTone.DANGER -> Triple(colors.dangerContainer, colors.onDangerContainer, Icons.Rounded.Error)
    }
    Surface(
        color = container,
        contentColor = content,
        shape = CoreLockTheme.shapes.card,
        modifier = modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(Modifier.padding(start = CoreLockTheme.spacing.l, top = CoreLockTheme.spacing.m, bottom = CoreLockTheme.spacing.s)) {
            Icon(
                icon,
                contentDescription = stringResource(R.string.ui_warning),
                modifier = Modifier.padding(top = CoreLockTheme.spacing.xs),
            )
            Spacer(Modifier.width(CoreLockTheme.spacing.m))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(CoreLockTheme.spacing.xs)) {
                Text(message, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = CoreLockTheme.spacing.xs))
                BannerAction(actionLabel, onAction, content)
                secondaryAction?.let { (label, action) -> BannerAction(label, action, content) }
            }
            if (onDismiss != null) {
                IconButton(onClick = onDismiss, modifier = Modifier.size(CoreLockTheme.spacing.minTouchTarget)) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.ui_dismiss))
                }
            } else {
                Spacer(Modifier.width(CoreLockTheme.spacing.l))
            }
        }
    }
}

@CoreLockPreviews
@Composable
private fun AttentionBannerPreview() = PreviewSurface {
    Column(verticalArrangement = Arrangement.spacedBy(CoreLockTheme.spacing.m)) {
        AttentionBanner(
            message = stringResource(R.string.ui_attention_private_dns),
            actionLabel = stringResource(R.string.ui_open_settings),
            onAction = {},
        )
        AttentionBanner(
            message = stringResource(R.string.ui_hero_stopped_sub),
            actionLabel = stringResource(R.string.ui_turn_on),
            onAction = {},
            tone = BannerTone.DANGER,
            onDismiss = {},
        )
    }
}

/** Stacked, not side by side: two verbs in French or Dutch at 200 % don't fit one line. */
@Composable
private fun ColumnScope.BannerAction(label: String, onClick: () -> Unit, color: androidx.compose.ui.graphics.Color) {
    TextButton(
        onClick = onClick,
        colors = ButtonDefaults.textButtonColors(contentColor = color),
        modifier = Modifier.align(Alignment.Start).heightIn(min = CoreLockTheme.spacing.minTouchTarget),
    ) { Text(label, style = MaterialTheme.typography.labelLarge) }
}
