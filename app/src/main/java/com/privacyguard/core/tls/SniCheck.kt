package com.privacyguard.core.tls

/**
 * Judges the server name a TLS or QUIC client sends (SNI). The SNI is chosen by the
 * app, so a tracker can claim to be "www.google.com" while talking to its own
 * server. Two things are flagged; neither blocks on its own:
 *
 *  - INVALID_SNI: a name was sent that is not a hostname (bidi text, HTML, an IP).
 *  - SNI_MISMATCH: the app reached an address that DNS gave out for one name, but
 *    claims another name, of another site, that it never looked up through us.
 *    Apps that resolve the name they then use (nearly all) never trigger it; a
 *    CDN address shared by many sites does not either, as long as the claimed
 *    name was looked up.
 */
object SniCheck {

    enum class Kind { INVALID_SNI, SNI_MISMATCH }

    data class Finding(val kind: Kind, val sni: String?, val dnsName: String?)

    /**
     * @param sni the validated SNI, or null
     * @param sniRejected an SNI was present but was not a valid hostname
     * @param dnsNameForIp the name DNS last gave out for the destination address
     * @param sniWasResolved whether [sni] was looked up through PrivacyGuard's DNS recently
     */
    fun evaluate(sni: String?, sniRejected: Boolean, dnsNameForIp: String?, sniWasResolved: Boolean): Finding? = when {
        sniRejected -> Finding(Kind.INVALID_SNI, null, dnsNameForIp)
        sni == null || dnsNameForIp == null || sniWasResolved -> null
        site(sni) == site(dnsNameForIp) -> null
        else -> Finding(Kind.SNI_MISMATCH, sni, dnsNameForIp)
    }

    /** Last two labels: enough to tell sites apart here (co.uk-style suffixes only make it stricter). */
    private fun site(host: String): String =
        host.lowercase().trimEnd('.').split('.').takeLast(2).joinToString(".")
}
