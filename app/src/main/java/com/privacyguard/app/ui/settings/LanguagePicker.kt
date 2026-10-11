package com.privacyguard.app.ui.settings

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import com.privacyguard.app.R
import java.util.Locale

/** Languages shipped in res/values-*; keep in sync with res/xml/locales_config.xml. */
private val SUPPORTED = listOf("en", "nl", "fr", "ar")

/** Empty tag = follow the system language. */
fun currentAppLanguageTag(): String =
    AppCompatDelegate.getApplicationLocales().takeIf { !it.isEmpty }?.get(0)?.language.orEmpty()

/** A language named in itself ("Français", "العربية"), so it is findable whatever the current UI language. */
@Composable
fun languageDisplayName(tag: String): String =
    if (tag.isEmpty()) stringResource(R.string.settings_language_system)
    else Locale.forLanguageTag(tag).let { it.getDisplayName(it) }.replaceFirstChar { it.titlecase(Locale.forLanguageTag(tag)) }

@Composable
fun LanguagePickerDialog(onDismiss: () -> Unit) {
    val current = currentAppLanguageTag()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_language)) },
        text = {
            Column {
                (listOf("") + SUPPORTED).forEach { tag ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable {
                                onDismiss()
                                // Recreates the activity in the new language; stored by the
                                // platform (13+) or AppCompat's metadata service (older).
                                AppCompatDelegate.setApplicationLocales(
                                    if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList()
                                    else LocaleListCompat.forLanguageTags(tag)
                                )
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = tag == current, onClick = null)
                        Text(languageDisplayName(tag), Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}
