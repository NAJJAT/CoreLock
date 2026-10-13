package com.privacyguard.app.ui.rules

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.privacyguard.app.R

/**
 * Shown before an Allow rule for a domain on a malware or phishing list. Allow
 * normally beats ad and tracker lists only; these lists need an explicit yes.
 * Cancel is the default (confirm button is styled as the dangerous choice).
 */
@Composable
fun SecurityAllowWarningDialog(domain: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
        title = { Text(stringResource(R.string.rule_security_warn_title)) },
        text = { Text(stringResource(R.string.rule_security_warn_body, domain)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.rule_security_warn_confirm), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.rule_security_warn_cancel)) } },
    )
}
