package com.privacyguard.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.GppBad
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.privacyguard.app.R
import com.privacyguard.app.ui.theme.CoreLockTheme

/** How a plain word is coloured. Always shown as text too; never colour alone. */
enum class WordTone { MUTED, ATTENTION, DANGER }

/**
 * An app in a list (DESIGN_SPEC_1.1.md §6.3): icon, name, status in words, the plain
 * risk word, blocks today, chevron. No package name, no letter grade and no inline
 * switch: blocking happens in App detail, where the choice is explained.
 *
 * [icon] is a slot so screens pass [AppIconImage] and the catalog a placeholder.
 */
@Composable
fun AppRow(
    name: String,
    status: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit = { PlaceholderAppIcon() },
    riskWord: String? = null,
    riskTone: WordTone = WordTone.MUTED,
    blockedToday: Int? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = CoreLockTheme.spacing.l, vertical = CoreLockTheme.spacing.m),
    ) {
        icon()
        Spacer(Modifier.width(CoreLockTheme.spacing.l))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(name, style = MaterialTheme.typography.titleMedium, color = CoreLockTheme.colors.onSurface)
            val details = listOfNotNull(
                status,
                blockedToday?.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.ui_blocked_today_n, it, it) },
            ).joinToString(" · ")
            Text(details, style = MaterialTheme.typography.bodyMedium, color = CoreLockTheme.colors.onSurfaceMuted)
            if (riskWord != null) ToneWord(riskWord, riskTone)
        }
        Spacer(Modifier.width(CoreLockTheme.spacing.s))
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = CoreLockTheme.colors.onSurfaceMuted)
    }
}

/** What kind of activity a row shows; decides its icon and colour. */
enum class ActivityKind { BLOCKED, BLOCKED_DANGER, ALLOWED }

/**
 * One blocked or allowed connection (§6.2). The reason is a status, so it is plain text
 * with an icon, never a button-shaped pill: the old "BLOCK" pill looked like an action
 * (UX audit). Actions live in the detail sheet the row opens.
 */
@Composable
fun ActivityRow(
    destination: String,
    reason: String,
    kind: ActivityKind,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    appIcon: @Composable () -> Unit = { PlaceholderAppIcon() },
    time: String? = null,
) {
    val colors = CoreLockTheme.colors
    val (statusIcon, tint) = when (kind) {
        ActivityKind.BLOCKED -> Icons.Rounded.Block to colors.onSurfaceMuted
        ActivityKind.BLOCKED_DANGER -> Icons.Rounded.GppBad to colors.danger
        ActivityKind.ALLOWED -> Icons.Rounded.CheckCircle to colors.protected
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = CoreLockTheme.spacing.l, vertical = CoreLockTheme.spacing.m),
    ) {
        appIcon()
        Spacer(Modifier.width(CoreLockTheme.spacing.l))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(destination, style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
            // Top-aligned: when the reason wraps (Arabic, 200 %), the icon stays on the first line.
            Row(verticalAlignment = Alignment.Top) {
                Icon(statusIcon, contentDescription = null, tint = tint, modifier = Modifier.padding(top = 2.dp).size(16.dp))
                Spacer(Modifier.width(CoreLockTheme.spacing.xs))
                Text(
                    reason,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (kind == ActivityKind.BLOCKED_DANGER) colors.danger else colors.onSurfaceMuted,
                )
            }
        }
        if (time != null) {
            Spacer(Modifier.width(CoreLockTheme.spacing.s))
            Text(time, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceMuted)
        }
    }
}

@Composable
private fun ToneWord(text: String, tone: WordTone) {
    val colors = CoreLockTheme.colors
    val color: Color = when (tone) {
        WordTone.MUTED -> colors.onSurfaceMuted
        WordTone.ATTENTION -> colors.attention
        WordTone.DANGER -> colors.danger
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (tone == WordTone.DANGER) {
            Icon(Icons.Rounded.GppBad, contentDescription = stringResource(R.string.ui_warning), tint = color, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(CoreLockTheme.spacing.xs))
        }
        Text(text, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

/** Neutral stand-in for an app icon (catalog, previews, apps without an icon). */
@Composable
fun PlaceholderAppIcon(icon: ImageVector = Icons.Rounded.Android) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(40.dp)
            .clip(CoreLockTheme.shapes.small)
            .background(CoreLockTheme.colors.surfaceVariant),
    ) {
        Icon(icon, contentDescription = null, tint = CoreLockTheme.colors.onSurfaceMuted)
    }
}

@CoreLockPreviews
@Composable
private fun ListRowsPreview() = PreviewSurface {
    Column {
        AppRow(
            name = "Chrome",
            status = stringResource(R.string.ui_app_allowed),
            riskWord = stringResource(R.string.ui_risk_trackers),
            blockedToday = 12,
            onClick = {},
        )
        AppRow(
            name = "Flashlight",
            status = stringResource(R.string.ui_app_blocked_background),
            riskWord = stringResource(R.string.ui_risk_watch),
            riskTone = WordTone.DANGER,
            onClick = {},
        )
        ActivityRow("Google ads", stringResource(R.string.ui_reason_ads), ActivityKind.BLOCKED, onClick = {}, time = "14:02")
        ActivityRow("login-secure.example", stringResource(R.string.ui_reason_malware), ActivityKind.BLOCKED_DANGER, onClick = {})
        ActivityRow("example.com", stringResource(R.string.ui_reason_allowed), ActivityKind.ALLOWED, onClick = {})
    }
}
