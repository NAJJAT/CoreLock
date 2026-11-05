package com.privacyguard.app.ui.components

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
import androidx.compose.material.icons.rounded.GppBad
import androidx.compose.material.icons.rounded.GppMaybe
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.privacyguard.app.R
import com.privacyguard.app.ui.theme.CoreLockTheme

/** What the service really is doing; never guessed (no "off" before the state is known). */
/** PAUSED: off for a few minutes by the user's choice, coming back by itself (D1). */
enum class ProtectionState { ON, OFF, STARTING, STOPPED_UNEXPECTEDLY, PAUSED }

/**
 * Home's status hero (DESIGN_SPEC_1.1.md §6.1). The whole card is one switch: tapping
 * anywhere toggles protection, and TalkBack reads it as "Protection" with its state and
 * the status sentence. The visual [Switch] is decoration only (no own click handler),
 * so there is exactly one focusable control.
 *
 * Off is amber, not red: the user chose it. Red is only for a stop nobody asked for.
 */
@Composable
fun StatusHeroCard(
    state: ProtectionState,
    title: String,
    subtitle: String,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CoreLockTheme.colors
    val look = when (state) {
        ProtectionState.ON -> HeroLook(colors.protectedContainer, colors.onProtectedContainer, colors.protected, Icons.Rounded.VerifiedUser)
        ProtectionState.OFF -> HeroLook(colors.attentionContainer, colors.onAttentionContainer, colors.attention, Icons.Rounded.GppMaybe)
        ProtectionState.STARTING -> HeroLook(colors.surface, colors.onSurface, colors.onSurfaceMuted, Icons.Rounded.Shield)
        ProtectionState.STOPPED_UNEXPECTEDLY -> HeroLook(colors.dangerContainer, colors.onDangerContainer, colors.danger, Icons.Rounded.GppBad)
        // Amber like Off: the user chose it. Tapping the card resumes at once.
        ProtectionState.PAUSED -> HeroLook(colors.attentionContainer, colors.onAttentionContainer, colors.attention, Icons.Rounded.PauseCircle)
    }
    val on = state == ProtectionState.ON || state == ProtectionState.STARTING
    val label = stringResource(R.string.ui_protection)

    Surface(
        color = look.container,
        contentColor = look.content,
        shape = CoreLockTheme.shapes.card,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 96.dp)
            .toggleable(
                value = on,
                enabled = state != ProtectionState.STARTING,
                role = Role.Switch,
                onValueChange = onToggle,
            )
            .semantics { contentDescription = "$label. $title. $subtitle" },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(CoreLockTheme.spacing.l),
        ) {
            Icon(look.icon, contentDescription = null, tint = look.iconTint, modifier = Modifier.size(40.dp))
            Spacer(Modifier.width(CoreLockTheme.spacing.l))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(CoreLockTheme.spacing.xs)) {
                Text(title, style = MaterialTheme.typography.headlineMedium.merge(HeroTitleStyle))
                Text(subtitle, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.width(CoreLockTheme.spacing.m))
            Switch(
                checked = on,
                onCheckedChange = null,
                enabled = state != ProtectionState.STARTING,
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
}

/**
 * The headline may take two lines on purpose ("Protection / stopped" in English at 100 %,
 * most titles at 200 %, French and Dutch often): balanced breaks, so two lines come out of
 * similar length instead of one long line and a lone word, and a tighter line height, so
 * the two lines read as one title rather than as two paragraphs.
 */
internal val HeroTitleStyle = androidx.compose.ui.text.TextStyle(lineBreak = LineBreak.Heading, lineHeight = 1.15.em)

private class HeroLook(val container: Color, val content: Color, val iconTint: Color, val icon: ImageVector)

@CoreLockPreviews
@Composable
private fun StatusHeroCardPreview() = PreviewSurface {
    Column(verticalArrangement = Arrangement.spacedBy(CoreLockTheme.spacing.m)) {
        StatusHeroCard(ProtectionState.ON, stringResource(R.string.ui_hero_on), stringResource(R.string.ui_hero_sub_standard), {})
        StatusHeroCard(ProtectionState.OFF, stringResource(R.string.ui_hero_off), stringResource(R.string.ui_hero_off_sub), {})
        StatusHeroCard(ProtectionState.STARTING, stringResource(R.string.ui_hero_starting), stringResource(R.string.ui_hero_starting_sub), {})
        StatusHeroCard(ProtectionState.STOPPED_UNEXPECTEDLY, stringResource(R.string.ui_hero_stopped), stringResource(R.string.ui_hero_stopped_sub), {})
    }
}
