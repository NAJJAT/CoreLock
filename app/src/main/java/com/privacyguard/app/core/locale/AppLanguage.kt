package com.privacyguard.app.core.locale

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

/**
 * The in-app language choice (Settings › Language).
 *
 * [apply] uses the official per-app language API (AppCompatDelegate.setApplicationLocales):
 * Android 13+ stores the choice and shows it in the system "App language" screen;
 * below 13 AppCompat stores it (autoStoreLocales) and recreates the activity, which
 * also flips the layout to RTL for Arabic at once.
 *
 * Below 13 AppCompat applies the language to activities only, and after a reboot the
 * VPN service starts before any activity, so its notification stayed in the phone's
 * language. The choice is therefore also kept here, and [localized] gives non-activity
 * code (notifications, workers) a context in the chosen language on every version.
 */
object AppLanguage {

    /** Languages shipped in res/values-*; keep in sync with res/xml/locales_config.xml. */
    val SUPPORTED = listOf("en", "ar", "fr", "nl")

    private const val PREFS_NAME = "app_language"
    private const val KEY_TAG = "tag"

    /** Empty tag = follow the system language. */
    fun apply(context: Context, tag: String) {
        // AppCompat first: saving notifies the VPN service, which must then already read
        // the new language.
        AppCompatDelegate.setApplicationLocales(
            if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag)
        )
        save(context, tag)
    }

    /** Stores [tag] ("" or one of [SUPPORTED]); anything else is stored as "follow the system". */
    fun save(context: Context, tag: String) {
        prefs(context).edit().putString(KEY_TAG, tag.takeIf { it in SUPPORTED }.orEmpty()).apply()
    }

    /** The stored choice: null when never chosen here, "" for the system language. */
    fun saved(context: Context): String? =
        prefs(context).getString(KEY_TAG, null)?.let { stored -> stored.takeIf { it in SUPPORTED }.orEmpty() }

    /** The language the app shows now; "" = the system language. */
    fun current(context: Context): String = resolve(
        sdk = Build.VERSION.SDK_INT,
        appCompatTag = AppCompatDelegate.getApplicationLocales().takeIf { !it.isEmpty }?.get(0)?.language.orEmpty(),
        savedTag = saved(context),
    )

    /**
     * Android 13+ keeps the per-app language itself (also when changed in the system
     * "App language" screen), so AppCompat's answer is the truth there; the stored copy
     * would be stale after such a change. Below 13 the in-app picker is the only way to
     * change it, so the stored copy is always current, while AppCompat only knows it once
     * an activity has started in this process (and may still hold the old value while the
     * change is applied). AppCompat is the fallback for a choice made before this copy existed.
     */
    internal fun resolve(sdk: Int, appCompatTag: String, savedTag: String?): String {
        val fromAppCompat = appCompatTag.takeIf { it in SUPPORTED }.orEmpty()
        return if (sdk >= Build.VERSION_CODES.TIRAMISU) fromAppCompat else savedTag ?: fromAppCompat
    }

    @Volatile private var cache: Triple<Context, String, Context>? = null

    /** [context] with the chosen language applied, or [context] itself when it follows the system. */
    fun localized(context: Context): Context {
        val tag = current(context)
        if (tag.isEmpty()) return context
        cache?.let { (base, cachedTag, localizedContext) -> if (base === context && cachedTag == tag) return localizedContext }
        val config = Configuration(context.resources.configuration)
        val locale = Locale.forLanguageTag(tag)
        if (config.locales[0]?.language == locale.language) return context
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return context.createConfigurationContext(config).also { cache = Triple(context, tag, it) }
    }

    /**
     * Calls [onChange] (main thread) when the in-app choice changes. Keep the result
     * referenced and close it to stop: SharedPreferences holds listeners weakly.
     */
    fun observe(context: Context, onChange: () -> Unit): AutoCloseable {
        val prefs = prefs(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> if (key == KEY_TAG) onChange() }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        return AutoCloseable { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
