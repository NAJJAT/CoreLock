package com.privacyguard.app.ui.settings

import androidx.annotation.RawRes
import androidx.annotation.StringRes
import com.privacyguard.app.R

/**
 * Full license texts shipped in res/raw, in English: the legal text is the original,
 * so it is not translated. GPL-3.0 and Apache-2.0 are the unmodified official texts
 * (OpenSourceNoticesTest checks them). Bouncy Castle's own license files are excluded
 * from the APK (packaging in app/build.gradle.kts) because their notice is shown here.
 */
enum class LicenseText(@RawRes val res: Int, @StringRes val title: Int) {
    GPL_3_0(R.raw.license_gpl_3_0, R.string.lic_gpl_3),
    APACHE_2_0(R.raw.license_apache_2_0, R.string.lic_apache_2),
    SQLCIPHER_BSD_3(R.raw.license_sqlcipher_bsd_3, R.string.lic_bsd_3),
    BOUNCY_CASTLE_MIT(R.raw.license_bouncycastle_mit, R.string.lic_mit),
}

/**
 * One credit on the licenses screen. [source] is shown as text, not a link: the screen
 * opens nothing by itself.
 */
data class Notice(
    @StringRes val name: Int,
    /** Null when the source names no license (the registries publish their files freely). */
    @StringRes val license: Int?,
    @StringRes val copyright: Int? = null,
    @StringRes val description: Int? = null,
    @StringRes val source: Int? = null,
    val text: LicenseText? = null,
)

/**
 * Everything the release build ships or downloads that needs credit. When a dependency,
 * list source or bundled data file is added, add it here and to the README credits.
 */
object OpenSourceNotices {
    /** Libraries in the release APK (enterpriseReleaseRuntimeClasspath, 2026-10-25). */
    val software = listOf(
        Notice(R.string.lic_name_sqlcipher, R.string.lic_bsd_3, R.string.lic_copy_sqlcipher,
            source = R.string.lic_url_sqlcipher, text = LicenseText.SQLCIPHER_BSD_3),
        Notice(R.string.lic_name_bc, R.string.lic_mit, R.string.lic_copy_bc,
            source = R.string.lic_url_bc, text = LicenseText.BOUNCY_CASTLE_MIT),
        Notice(R.string.lic_name_apache, R.string.lic_apache_2,
            description = R.string.lic_desc_apache, text = LicenseText.APACHE_2_0),
        // OkHttp bundles publicsuffixes.gz; its NOTICE puts that data under MPL-2.0, whose
        // executable-form duty is to say where the source is.
        Notice(R.string.lic_name_psl, R.string.lic_mpl_2,
            description = R.string.lic_desc_psl, source = R.string.lic_url_psl),
    )

    /** Lists fetched at runtime (BlocklistSource, ExodusUpdater); not inside the APK. */
    val downloaded = listOf(
        Notice(R.string.lic_name_hagezi, R.string.lic_gpl_3,
            description = R.string.lic_desc_hagezi, source = R.string.lic_url_hagezi),
        Notice(R.string.lic_name_oisd, R.string.lic_gpl_3,
            description = R.string.lic_desc_oisd, source = R.string.lic_url_oisd),
        Notice(R.string.lic_name_exodus, R.string.lic_odbl,
            description = R.string.lic_desc_exodus, source = R.string.lic_url_exodus),
    )

    /** Data built into the APK: assets/geoip_v4.bin (tools/geoip/GenerateGeoIp.java). */
    val bundledData = listOf(
        Notice(R.string.lic_name_rir, license = null, description = R.string.lic_desc_rir),
    )
}
