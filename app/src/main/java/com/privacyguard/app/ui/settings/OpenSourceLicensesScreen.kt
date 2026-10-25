package com.privacyguard.app.ui.settings

import android.content.Context
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.privacyguard.app.R
import com.privacyguard.app.ui.components.PanelCard
import com.privacyguard.app.ui.components.ScreenScaffold
import com.privacyguard.app.ui.components.SectionLabel
import com.privacyguard.app.ui.theme.PgBorder
import com.privacyguard.app.ui.theme.PgText
import com.privacyguard.app.ui.theme.PgTextMuted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Settings › About › Open-source licenses. Hand-written: no licenses library (CLAUDE.md §1.4). */
@Composable
fun OpenSourceLicensesScreen(onOpenText: (LicenseText) -> Unit) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            ScreenScaffold(title = stringResource(R.string.lic_title), subtitle = stringResource(R.string.lic_subtitle)) {}
        }
        item {
            Section(stringResource(R.string.lic_app_title)) {
                Text(stringResource(R.string.lic_app_body), style = MaterialTheme.typography.bodySmall, color = PgText)
                Spacer(Modifier.height(4.dp))
                SelectionContainer {
                    Text(stringResource(R.string.lic_repo_url), style = MaterialTheme.typography.bodySmall, color = PgTextMuted)
                }
                TextButton(onClick = { onOpenText(LicenseText.GPL_3_0) }) { Text(stringResource(R.string.lic_show_text)) }
            }
        }
        item { NoticeSection(stringResource(R.string.lic_section_software), OpenSourceNotices.software, onOpenText) }
        item {
            NoticeSection(stringResource(R.string.lic_section_downloaded), OpenSourceNotices.downloaded, onOpenText,
                note = stringResource(R.string.lic_downloaded_note))
        }
        item { NoticeSection(stringResource(R.string.lic_section_bundled), OpenSourceNotices.bundledData, onOpenText) }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    PanelCard(modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
        SectionLabel(title)
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun NoticeSection(title: String, notices: List<Notice>, onOpenText: (LicenseText) -> Unit, note: String? = null) {
    Section(title) {
        if (note != null) {
            Text(note, style = MaterialTheme.typography.bodySmall, color = PgTextMuted)
            Spacer(Modifier.height(8.dp))
        }
        notices.forEachIndexed { i, notice ->
            if (i > 0) HorizontalDivider(Modifier.padding(vertical = 10.dp), color = PgBorder)
            NoticeItem(notice, onOpenText)
        }
    }
}

@Composable
private fun NoticeItem(notice: Notice, onOpenText: (LicenseText) -> Unit) {
    Text(stringResource(notice.name), style = MaterialTheme.typography.titleSmall, color = PgText)
    notice.copyright?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = PgTextMuted) }
    notice.description?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = PgText) }
    notice.license?.let {
        Text(stringResource(R.string.lic_license, stringResource(it)), style = MaterialTheme.typography.bodySmall, color = PgTextMuted)
    }
    notice.source?.let {
        SelectionContainer {
            Text(stringResource(R.string.lic_source, stringResource(it)), style = MaterialTheme.typography.bodySmall, color = PgTextMuted)
        }
    }
    notice.text?.let { text ->
        TextButton(onClick = { onOpenText(text) }) { Text(stringResource(R.string.lic_show_text)) }
    }
}

/** One full license text, shown as reflowed paragraphs so it reads at any width and font size. */
@Composable
fun LicenseTextScreen(text: LicenseText?) {
    val context = LocalContext.current
    // null while loading; empty when the text could not be read (or the route named no text).
    val paragraphs by produceState<List<String>?>(initialValue = null, text) {
        value = if (text == null) emptyList() else withContext(Dispatchers.IO) { readParagraphs(context, text) }
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            ScreenScaffold(title = text?.let { stringResource(it.title) } ?: stringResource(R.string.lic_title)) {}
        }
        val loaded = paragraphs
        when {
            loaded == null -> item {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
            loaded.isEmpty() -> item {
                Text(stringResource(R.string.lic_text_error), style = MaterialTheme.typography.bodyMedium, color = PgText,
                    modifier = Modifier.padding(horizontal = 16.dp))
            }
            else -> items(loaded) { paragraph ->
                SelectionContainer {
                    Text(paragraph, style = MaterialTheme.typography.bodySmall, color = PgText,
                        modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

private fun readParagraphs(context: Context, text: LicenseText): List<String> =
    runCatching {
        context.resources.openRawResource(text.res).bufferedReader().use { reflow(it.readText()) }
    }.getOrElse { e ->
        Log.w("LicenseText", "Could not read ${text.name}: ${e.javaClass.simpleName}")
        emptyList()
    }

/**
 * Joins the hard-wrapped lines of a plain-text license into paragraphs. A line starting
 * with "* " (a list item, as in the BSD text) keeps its own line.
 */
internal fun reflow(raw: String): List<String> =
    raw.replace("\r\n", "\n").split(Regex("\n[ \t]*\n"))
        .map { block ->
            block.lines().map(String::trim).filter(String::isNotEmpty)
                .fold(StringBuilder()) { sb, line ->
                    if (sb.isNotEmpty()) sb.append(if (line.startsWith("* ")) "\n" else " ")
                    sb.append(line)
                }.toString()
        }
        .filter(String::isNotEmpty)
