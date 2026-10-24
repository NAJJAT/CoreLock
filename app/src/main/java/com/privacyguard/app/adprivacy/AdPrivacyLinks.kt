package com.privacyguard.app.adprivacy

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/** Steps of the "Reduce ad tracking" checklist. The ids are persisted; don't rename. */
enum class ChecklistStep(val id: String) {
    DELETE_AD_ID("ad_id"),
    META_OFF_ACTIVITY("meta_off_activity"),
    GOOGLE_AD_CENTER("google_ad_center"),
    GOOGLE_TIMELINE("google_timeline"),
    REVIEW_MIC("review_mic"),
}

/**
 * Deep links for the checklist and audits. Each target is a list of intents
 * tried in order: OEMs and Google move these screens, so a generic fallback
 * always follows the specific one.
 */
object AdPrivacyLinks {

    fun intentsFor(step: ChecklistStep): List<Intent> = when (step) {
        // Google Play services' "Ads" screen, where Android 12+ offers "Delete advertising ID".
        ChecklistStep.DELETE_AD_ID -> listOf(
            Intent("com.google.android.gms.settings.ADS_PRIVACY"),
            Intent(Settings.ACTION_PRIVACY_SETTINGS),
        )
        ChecklistStep.META_OFF_ACTIVITY -> listOf(
            web("https://accountscenter.facebook.com/info_and_permissions/off_facebook_activity"),
            web("https://accountscenter.facebook.com/"),
        )
        ChecklistStep.GOOGLE_AD_CENTER -> listOf(web("https://myadcenter.google.com/"))
        // Timeline now lives on the phone, inside Google Maps; activity controls
        // are the account-level fallback.
        ChecklistStep.GOOGLE_TIMELINE -> listOf(
            web("https://www.google.com/maps/timeline").setPackage("com.google.android.apps.maps"),
            web("https://myactivity.google.com/activitycontrols"),
        )
        ChecklistStep.REVIEW_MIC -> listOf(Intent(Settings.ACTION_PRIVACY_SETTINGS))
    }

    fun appDetails(packageName: String) = listOf(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
    )

    /** Android's own per-app history of location/mic use (Android 12+), else privacy settings. */
    fun permissionHistory(permissionGroup: String) = listOf(
        Intent("android.intent.action.REVIEW_PERMISSION_HISTORY")
            .putExtra("android.intent.extra.PERMISSION_GROUP_NAME", permissionGroup),
        Intent(Settings.ACTION_PRIVACY_SETTINGS),
    )

    /**
     * This app's own Usage Access switch where Android has one (10 and newer accept
     * a package: URI), else the list of all apps, else Settings. The list alone made
     * users pick between look-alike entries.
     */
    fun usageAccess(packageName: String) = listOfNotNull(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.fromParts("package", packageName, null))
        else null,
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS),
        Intent(Settings.ACTION_SETTINGS),
    )

    /** Starts the first intent that resolves; false if none could be opened. */
    fun open(context: Context, intents: List<Intent>): Boolean {
        for (intent in intents) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
                // Some screens are restricted to system callers on some builds.
            }
        }
        return false
    }

    private fun web(url: String) = Intent(Intent.ACTION_VIEW, Uri.parse(url))
}
