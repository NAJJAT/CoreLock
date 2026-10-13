package com.privacyguard.app.ui.traffic

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.privacyguard.app.R
import com.privacyguard.app.core.tracker.DomainClass
import com.privacyguard.app.core.tracker.DomainPurpose
import com.privacyguard.app.ui.components.AppIconImage
import com.privacyguard.app.ui.components.PanelCard
import com.privacyguard.app.ui.components.StatusPill
import com.privacyguard.app.ui.components.formatBytes
import com.privacyguard.app.ui.text.FindingText
import com.privacyguard.app.ui.theme.PgAccent
import com.privacyguard.app.ui.theme.PgAccentDim
import com.privacyguard.app.ui.theme.PgDanger
import com.privacyguard.app.ui.theme.PgDangerDim
import com.privacyguard.app.ui.theme.PgInfo
import com.privacyguard.app.ui.theme.PgInfoDim
import com.privacyguard.app.ui.theme.PgPanelMuted
import com.privacyguard.app.ui.theme.PgText
import com.privacyguard.app.ui.theme.PgTextFaint
import com.privacyguard.app.ui.theme.PgTextMuted
import com.privacyguard.app.ui.theme.PgWarning
import com.privacyguard.app.ui.theme.PgWarningDim

/** Friendly names only: an installed app's label, or a plain description — never a package name. */
object AppNames {
    fun isUnattributed(packageName: String?): Boolean =
        packageName.isNullOrBlank() || packageName.equals(TrafficRows.UNKNOWN_PACKAGE, ignoreCase = true)

    fun label(context: Context, packageName: String, storedName: String? = null): String {
        // Android did not tell us which app (shared resolver, system services): "Unknown
        // app", never "Removed app". Older anomaly rows used lower-case "unknown".
        if (isUnattributed(packageName)) return context.getString(R.string.conn_unknown_app)
        if (packageName == com.privacyguard.app.core.network.NetworkSignals.NETWORK_OWNER) return context.getString(R.string.net_owner)
        runCatching {
            val pm = context.packageManager
            return pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        }
        // Uninstalled: the name we stored when we saw it, if it is a real name.
        return storedName?.takeIf { it.isNotBlank() && !it.contains('.') && it != TrafficRows.UNKNOWN_PACKAGE }
            ?: context.getString(R.string.trf_removed_app)
    }
}

fun purposeChip(context: Context, p: DomainPurpose): String = context.getString(
    when (p) {
        DomainPurpose.FIRST_PARTY -> R.string.trf_c_first
        DomainPurpose.ADS -> R.string.trf_c_ads
        DomainPurpose.ANALYTICS -> R.string.trf_c_analytics
        DomainPurpose.TRACKING -> R.string.trf_c_tracking
        DomainPurpose.CDN -> R.string.trf_c_cdn
        DomainPurpose.SERVICE -> R.string.trf_c_service
        DomainPurpose.UNKNOWN -> R.string.trf_c_unknown
    }
)

fun purposeExplanation(context: Context, p: DomainPurpose): String = context.getString(
    when (p) {
        DomainPurpose.FIRST_PARTY -> R.string.trf_pd_first
        DomainPurpose.ADS -> R.string.trf_pd_ads
        DomainPurpose.ANALYTICS -> R.string.trf_pd_analytics
        DomainPurpose.TRACKING -> R.string.trf_pd_tracking
        DomainPurpose.CDN -> R.string.trf_pd_cdn
        DomainPurpose.SERVICE -> R.string.trf_pd_service
        DomainPurpose.UNKNOWN -> R.string.trf_pd_unknown
    }
)

/** "Instagram core", "Meta ads", "CDN (content delivery)", … */
fun groupTitle(context: Context, group: PurposeGroup, appName: String): String {
    val company = group.company ?: context.getString(R.string.trf_company_unknown)
    return when (group.purpose) {
        DomainPurpose.FIRST_PARTY -> context.getString(R.string.trf_p_first, appName)
        DomainPurpose.ADS -> context.getString(R.string.trf_p_ads, company)
        DomainPurpose.ANALYTICS -> context.getString(R.string.trf_p_analytics, company)
        DomainPurpose.TRACKING -> context.getString(R.string.trf_p_tracking, company)
        DomainPurpose.CDN -> context.getString(R.string.trf_p_cdn)
        DomainPurpose.SERVICE -> context.getString(R.string.trf_p_service, company)
        DomainPurpose.UNKNOWN -> context.getString(R.string.trf_p_unknown)
    }
}

fun purposeColors(p: DomainPurpose): Pair<Color, Color> = when (p) {
    DomainPurpose.ADS, DomainPurpose.TRACKING -> PgDangerDim to PgDanger
    DomainPurpose.ANALYTICS -> PgWarningDim to PgWarning
    DomainPurpose.FIRST_PARTY -> PgAccentDim to PgAccent
    DomainPurpose.CDN, DomainPurpose.SERVICE -> PgInfoDim to PgInfo
    DomainPurpose.UNKNOWN -> PgPanelMuted to PgTextMuted
}

fun reasonText(context: Context, r: FlagReason): String = when (r) {
    is FlagReason.KnownTracker -> context.getString(R.string.trf_r_tracker, r.name,
        FindingText.trackerCategory(context, r.category).lowercase())
    FlagReason.Blocked -> context.getString(R.string.trf_r_blocked)
    FlagReason.ScreenOff -> context.getString(R.string.trf_r_screen_off)
    FlagReason.Background -> context.getString(R.string.trf_r_background)
    FlagReason.Cleartext -> context.getString(R.string.trf_r_cleartext)
    is FlagReason.DnsAnomaly -> context.getString(R.string.trf_r_anomaly, FindingText.dnsAnomalyTitle(context, r.type))
}

fun formatWhen(context: Context, time: Long): String =
    DateUtils.formatDateTime(context, time, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH)

fun companyText(context: Context, cls: DomainClass?): String =
    cls?.company ?: context.getString(R.string.trf_company_unknown)

/** One connection or lookup with everything we know about it. */
@Composable
fun TrafficRowItem(
    row: TrafficRow,
    showApp: Boolean,
    storedAppName: String? = null,
    onOpenApp: ((String) -> Unit)? = null,
    onOpenDomain: ((String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val cls = row.classification
    PanelCard(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .then(if (row.host != null && onOpenDomain != null) Modifier.clickable { onOpenDomain(row.host) } else Modifier),
    ) {
        if (showApp) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.then(
                    if (onOpenApp != null && row.packageName.isNotBlank()) Modifier.clickable { onOpenApp(row.packageName) } else Modifier
                ),
            ) {
                AppIconImage(row.packageName, size = 22.dp, cornerRadius = 6.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    AppNames.label(context, row.packageName, storedAppName),
                    style = MaterialTheme.typography.labelLarge, color = PgTextMuted,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(6.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                row.host ?: row.ip.orEmpty(),
                style = MaterialTheme.typography.titleSmall, color = PgText, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(formatWhen(context, row.timestamp), style = MaterialTheme.typography.labelSmall, color = PgTextFaint)
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (cls != null) {
                val (bg, fg) = purposeColors(cls.purpose)
                StatusPill(purposeChip(context, cls.purpose), bg, fg)
            }
            Text(
                listOf(
                    companyText(context, cls),
                    stringResource(
                        when (row.source) {
                            HostSource.TLS_SNI -> R.string.trf_source_sni
                            HostSource.DNS -> R.string.trf_source_dns
                            HostSource.IP_ONLY -> R.string.trf_source_ip
                        }
                    ),
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = PgTextMuted,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            listOfNotNull(
                if (row.local) stringResource(R.string.trf_local_network)
                else if (row.probe) stringResource(R.string.trf_connectivity_check) else null,
                if (row.isLookup) stringResource(R.string.trf_lookup_only)
                else stringResource(R.string.trf_bytes, formatBytes(row.bytesSent), formatBytes(row.bytesReceived)),
                stringResource(
                    when (row.screenOn) {
                        true -> R.string.trf_screen_on
                        false -> R.string.trf_screen_off
                        null -> R.string.trf_screen_unknown
                    }
                ),
                stringResource(
                    when (row.foreground) {
                        true -> R.string.trf_fg
                        false -> R.string.trf_bg
                        null -> R.string.trf_fg_unknown
                    }
                ),
                stringResource(if (row.blocked) R.string.trf_blocked else R.string.trf_allowed),
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall, color = PgTextMuted,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            if (row.flagged) stringResource(
                R.string.trf_why,
                row.reasons.joinToString(stringResource(R.string.trf_reason_sep)) { reasonText(context, it) },
            ) else stringResource(R.string.trf_not_flagged),
            style = MaterialTheme.typography.bodySmall,
            color = if (row.flagged) PgWarning else PgTextFaint,
        )
    }
}

/** Shown wherever foreground/background is displayed but Usage Access is missing. */
@Composable
fun UsageAccessHint() {
    val context = LocalContext.current
    PanelCard(modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
        Text(stringResource(R.string.trf_usage_hint), style = MaterialTheme.typography.bodySmall, color = PgTextMuted)
        TextButton(onClick = {
            runCatching {
                context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }) { Text(stringResource(R.string.trf_usage_grant), color = PgAccent) }
    }
}

@Composable
fun EmptyReason(text: String) {
    PanelCard(modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = PgTextMuted)
    }
}

@Composable
fun CountHeader(text: String, note: String? = null) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = PgText)
        if (note != null) {
            Spacer(Modifier.height(2.dp))
            Text(note, style = MaterialTheme.typography.bodySmall, color = PgTextMuted)
        }
        Spacer(Modifier.size(4.dp))
    }
}
