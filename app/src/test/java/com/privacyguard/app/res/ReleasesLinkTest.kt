package com.privacyguard.app.res

import com.privacyguard.app.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI

/** Settings › About opens this page in the browser; the app never fetches it. */
class ReleasesLinkTest {

    @Test
    fun releases_link_is_an_https_url_with_a_host() {
        val uri = URI(BuildConfig.RELEASES_URL)
        assertEquals("https", uri.scheme)
        assertTrue("no host in ${BuildConfig.RELEASES_URL}", !uri.host.isNullOrBlank())
    }
}
