package com.privacyguard.vpn.mitm

/**
 * The inspection CA's name and when it is replaced (plan task 2.3, ADR 0009). Pure, so
 * the rules are tested on the JVM; [CaManager] applies them with the Keystore.
 *
 * - **Name:** "CoreLock HTTPS Inspection CA". CAs of earlier builds were called
 *   "PrivacyGuard Enterprise CA"; they are still recognised ([isOurs]) so the app can ask
 *   the user to remove them, and one still in use is replaced.
 * - **90-day rotation, one prompt:** a user's consent lasts [CONSENT_DAYS]. The CA lasts
 *   [VALIDITY_DAYS], a little longer, so it never ends before the consent prompt. When
 *   the user renews consent, the CA no longer covers the new period and is replaced at
 *   once, before the certificate install that follows the consent dialog: one prompt
 *   (renew and install), not two.
 * - **Organisation consent** (MDM) does not expire, so the CA is replaced
 *   [ORG_ROTATE_BEFORE_DAYS] before it ends; the MDM installs the new one.
 *
 * Replacing deletes the old key from the Keystore ([CaManager]); the old certificate then
 * can't sign anything, and the CA card asks the user to remove it.
 */
internal object CaPolicy {

    const val CN = "CoreLock HTTPS Inspection CA"
    const val ORG = "CoreLock"

    /** Name of the CA in builds up to 1.1: still recognised, never created. */
    const val LEGACY_CN = "PrivacyGuard Enterprise CA"
    const val LEGACY_ORG = "PrivacyGuard"

    /** A user's consent to inspection is asked again after this many days (MitmConfig). */
    const val CONSENT_DAYS = 90
    /** The consent period plus a week: the CA never ends before the consent prompt. */
    const val VALIDITY_DAYS = CONSENT_DAYS + 7
    /** Anything longer was made by an older build (one year, earlier ten): replaced. */
    const val MAX_LIFETIME_DAYS = 100
    const val ORG_ROTATE_BEFORE_DAYS = 14

    private const val DAY = 86_400_000L

    /** What the rules need from a certificate. [pathLen]: BasicConstraints, -1 if not a CA. */
    class CaInfo(val cn: String?, val org: String?, val notBefore: Long, val notAfter: Long, val pathLen: Int)

    /** A CoreLock inspection CA, of this build or an earlier one. */
    fun isOurs(cn: String?, org: String?): Boolean =
        (cn == CN && org == ORG) || (cn == LEGACY_CN && org == LEGACY_ORG)

    /**
     * True when [ca] must be replaced by a new one. [consentAt]: when consent was last
     * given (0 = never); [byOrganisation]: given by the organisation's MDM.
     */
    fun mustReplace(ca: CaInfo, now: Long, consentAt: Long, byOrganisation: Boolean): Boolean {
        if (now >= ca.notAfter) return true
        if ((ca.notAfter - ca.notBefore) / DAY > MAX_LIFETIME_DAYS) return true
        if (ca.pathLen != 0) return true                      // may sign only leaf certificates
        if (ca.cn != CN || ca.org != ORG) return true         // the old name: renamed with this rotation
        if (consentAt <= 0L) return false
        if (byOrganisation) return ca.notAfter - now < ORG_ROTATE_BEFORE_DAYS * DAY
        // The CA must outlast the current consent period. A consent time in the future
        // (a changed clock) counts as now, so a fresh CA always passes: no rotation loop.
        val consentEnds = minOf(consentAt, now) + CONSENT_DAYS * DAY
        return ca.notAfter < consentEnds + DAY
    }
}
