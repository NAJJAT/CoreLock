package com.privacyguard.platform.android

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import com.privacyguard.app.MainActivity
import com.privacyguard.app.R
import com.privacyguard.vpn.interception.InspectionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/** Review finding #1: HTTPS inspection is never silent in the VPN notification. */
@RunWith(RobolectricTestRunner::class)
// Robolectric's default installs Conscrypt JVM-wide, which broke the plain-JVM TLS
// tests (DohHostnameVerificationTest) that run later in the same fork.
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
class InspectionIndicatorTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val helper = NotificationHelper(context).also { it.createChannels() }

    private fun subText(status: InspectionStatus): CharSequence? =
        helper.buildVpnActiveNotification(0, MainActivity::class.java, status)
            .extras.getCharSequence(Notification.EXTRA_SUB_TEXT)

    @Test
    fun the_vpn_notification_says_when_inspection_is_on_and_who_turned_it_on() {
        assertNull(subText(InspectionStatus.OFF))
        assertEquals(context.getString(R.string.notif_inspection_on), subText(InspectionStatus.ON))
        assertEquals(context.getString(R.string.notif_inspection_on_org), subText(InspectionStatus.ON_BY_ORGANISATION))
    }

    @Test
    fun the_organisation_notice_is_posted() {
        helper.postInspectionEnabledByOrganisation(MainActivity::class.java)
        val posted = shadowOf(context.getSystemService(NotificationManager::class.java))
            .getNotification(NotificationHelper.NOTIFICATION_ID_INSPECTION_BY_ORG)
        assertNotNull(posted)
        assertEquals(
            context.getString(R.string.notif_inspection_org_title),
            posted.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
        )
    }
}
