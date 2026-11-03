package com.privacyguard.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.privacyguard.app.R
import com.privacyguard.app.ui.theme.CoreLockTheme

/**
 * The one bottom sheet of the app: drag handle, 28 dp top corners, back closes it, and
 * the content scrolls so nothing is cut at 200 % font. The title is a heading.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoreLockBottomSheet(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = CoreLockTheme.shapes.sheet,
        containerColor = CoreLockTheme.colors.surface,
        contentColor = CoreLockTheme.colors.onSurface,
    ) {
        SheetBody(title, Modifier.verticalScroll(rememberScrollState()), content)
    }
}

/**
 * Sheet content without the modal window, so the same layout can be shown inline in the
 * component catalog and in tests. It does not scroll by itself: inside a scrolling
 * parent a second vertical scroll crashes (unbounded height), so the modal sheet passes
 * the scroll modifier.
 */
@Composable
fun SheetBody(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(CoreLockTheme.spacing.m),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = CoreLockTheme.spacing.xl)
            .padding(bottom = CoreLockTheme.spacing.xl)
            .navigationBarsPadding(),
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        content()
    }
}

/** "Learn more": a title, the explanation, and one close button. */
@Composable
fun InfoSheet(title: String, body: String, onDismiss: () -> Unit) {
    CoreLockBottomSheet(title = title, onDismiss = onDismiss) { InfoSheetContent(body, onDismiss) }
}

@Composable
fun ColumnScope.InfoSheetContent(body: String, onDismiss: () -> Unit) {
    Text(body, style = MaterialTheme.typography.bodyLarge, color = CoreLockTheme.colors.onSurface)
    SecondaryButton(stringResource(R.string.ui_close), onClick = onDismiss, modifier = Modifier.fillMaxWidth())
}

@CoreLockPreviews
@Composable
private fun InfoSheetPreview() = PreviewSurface {
    Surface(shape = CoreLockTheme.shapes.sheet, color = CoreLockTheme.colors.surface) {
        SheetBody(stringResource(R.string.ui_what_corelock_cant)) {
            InfoSheetContent(stringResource(R.string.ui_what_corelock_cant_body), onDismiss = {})
        }
    }
}
