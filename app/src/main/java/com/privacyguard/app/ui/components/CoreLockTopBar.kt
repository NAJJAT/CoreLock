package com.privacyguard.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.privacyguard.app.R
import com.privacyguard.app.ui.theme.CoreLockTheme

/**
 * The header of every screen below a tab: a labelled back arrow (mirrored in RTL), the
 * title as a TalkBack heading, an optional line under it, and optional actions. The
 * title wraps instead of being cut at 200 % font; the row grows with it.
 */
@Composable
fun CoreLockTopBar(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(horizontal = CoreLockTheme.spacing.xs, vertical = CoreLockTheme.spacing.xs),
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.ui_back))
            }
        } else {
            Spacer(Modifier.width(CoreLockTheme.spacing.m))
        }
        Column(Modifier.weight(1f).padding(horizontal = CoreLockTheme.spacing.xs)) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                color = CoreLockTheme.colors.onSurface,
                modifier = Modifier.semantics { heading() },
            )
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = CoreLockTheme.colors.onSurfaceMuted)
            }
        }
        actions()
    }
}
