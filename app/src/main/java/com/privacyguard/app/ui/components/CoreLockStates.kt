package com.privacyguard.app.ui.components

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.privacyguard.app.R
import com.privacyguard.app.ui.theme.CoreLockTheme

/** Nothing to show yet: one sentence that says why, and at most one action. */
@Composable
fun EmptyState(
    message: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Rounded.Inbox,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    CenteredMessage(icon, message, modifier) {
        if (actionLabel != null && onAction != null) SecondaryButton(actionLabel, onClick = onAction)
    }
}

/** Load failed: a plain sentence and "Try again". Never a raw exception text. */
@Composable
fun ErrorState(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    CenteredMessage(Icons.Rounded.CloudOff, message, modifier) {
        PrimaryButton(stringResource(R.string.ui_try_again), onClick = onRetry)
    }
}

@Composable
private fun CenteredMessage(icon: ImageVector, message: String, modifier: Modifier, action: @Composable () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(CoreLockTheme.spacing.l),
        modifier = modifier.fillMaxWidth().padding(CoreLockTheme.spacing.xl),
    ) {
        Icon(icon, contentDescription = null, tint = CoreLockTheme.colors.onSurfaceMuted, modifier = Modifier.size(48.dp))
        Text(message, style = MaterialTheme.typography.bodyLarge, color = CoreLockTheme.colors.onSurface, textAlign = TextAlign.Center)
        action()
    }
}

/**
 * Placeholder rows shaped like the real ones while the first data loads. Static on
 * purpose (no shimmer): no animation to switch off, no frames drawn while waiting.
 * TalkBack hears "Loading" once instead of empty boxes.
 */
@Composable
fun LoadingSkeleton(modifier: Modifier = Modifier, rows: Int = 4) {
    val label = stringResource(R.string.ui_loading)
    val block = CoreLockTheme.colors.surfaceVariant
    Column(
        verticalArrangement = Arrangement.spacedBy(CoreLockTheme.spacing.l),
        modifier = modifier
            .fillMaxWidth()
            .padding(CoreLockTheme.spacing.l)
            .clearAndSetSemantics { contentDescription = label },
    ) {
        repeat(rows) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(block))
                Spacer(Modifier.width(CoreLockTheme.spacing.l))
                Column(verticalArrangement = Arrangement.spacedBy(CoreLockTheme.spacing.s)) {
                    Box(Modifier.fillMaxWidth(0.6f).height(14.dp).clip(CoreLockTheme.shapes.small).background(block))
                    Box(Modifier.fillMaxWidth(0.4f).height(12.dp).clip(CoreLockTheme.shapes.small).background(block))
                }
            }
        }
    }
}

@CoreLockPreviews
@Composable
private fun StatesPreview() = PreviewSurface {
    Column {
        EmptyState(stringResource(R.string.ui_empty_activity))
        ErrorState(stringResource(R.string.ui_error_activity), onRetry = {})
        LoadingSkeleton(rows = 2)
    }
}

/**
 * A reload failed, but older data is still on screen: say so above it, with "Try again".
 * For screens that keep their last numbers instead of switching to [ErrorState].
 */
@Composable
fun LoadFailedBanner(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    AttentionBanner(
        message = stringResource(R.string.ui_error_load),
        actionLabel = stringResource(R.string.ui_try_again),
        onAction = onRetry,
        modifier = modifier.testTag(TAG_LOAD_FAILED),
    )
}

const val TAG_LOAD_FAILED = "load_failed"
