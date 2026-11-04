package com.privacyguard.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.privacyguard.app.R
import com.privacyguard.app.ui.theme.CoreLockTechnicalValue
import com.privacyguard.app.ui.theme.CoreLockTheme
import com.privacyguard.app.ui.theme.rememberCoreLockTween

/**
 * Expert data, collapsed by default (everyday first, expert on request). Technical words
 * (IP, port, SNI, ...) are allowed only inside this block and in Advanced.
 */
@Composable
fun TechnicalDetails(
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    Column(modifier.fillMaxWidth()) {
        TechnicalDetailsHeader(expanded, onToggle = { expanded = !expanded })
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(rememberCoreLockTween()),
            exit = shrinkVertically(rememberCoreLockTween()),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(CoreLockTheme.spacing.s),
                modifier = Modifier.padding(bottom = CoreLockTheme.spacing.m),
                content = content,
            )
        }
    }
}

/**
 * The "Technical details" header alone, for a screen that shows the details as items of
 * a lazy list (App detail: hundreds of rows would be composed at once inside a Column).
 */
@Composable
fun TechnicalDetailsHeader(expanded: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val state = stringResource(if (expanded) R.string.ui_expanded else R.string.ui_collapsed)
    Column(modifier.fillMaxWidth()) {
        HorizontalDivider(color = CoreLockTheme.colors.surfaceVariant)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = CoreLockTheme.spacing.minTouchTarget)
                .clickable(role = Role.Button, onClick = onToggle)
                .semantics { stateDescription = state }
                .padding(vertical = CoreLockTheme.spacing.s),
        ) {
            Text(
                stringResource(R.string.ui_technical_details),
                style = MaterialTheme.typography.titleSmall,
                color = CoreLockTheme.colors.onSurfaceMuted,
                modifier = Modifier.weight(1f),
            )
            Icon(
                if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = null,
                tint = CoreLockTheme.colors.onSurfaceMuted,
            )
        }
    }
}

/** One label/value pair inside [TechnicalDetails]; the value is selectable for copying. */
@Composable
fun TechnicalValue(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = CoreLockTheme.colors.onSurfaceMuted)
        SelectionContainer {
            Text(value, style = CoreLockTechnicalValue, color = CoreLockTheme.colors.onSurface)
        }
    }
}

@CoreLockPreviews
@Composable
private fun TechnicalDetailsPreview() = PreviewSurface {
    TechnicalDetails(initiallyExpanded = true) {
        TechnicalValue("Stopped by", "SNI")
        TechnicalValue("IP", "142.250.185.66")
        TechnicalValue("Port", "443")
    }
}
