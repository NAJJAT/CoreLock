package com.privacyguard.mdm

import android.content.Context
import com.privacyguard.vpn.interception.InspectionStatus
import com.privacyguard.vpn.mitm.MitmConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Review findings #1 and #5: what an organisation's MDM switched on is shown as such,
 * and ends when its managed configuration disappears; the user's own choice stays.
 */
@RunWith(RobolectricTestRunner::class)
// Robolectric's default installs Conscrypt JVM-wide, which broke the plain-JVM TLS
// tests (DohHostnameVerificationTest) that run later in the same fork.
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
class OrganisationInspectionTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val config = MitmConfig(context)

    // Switching inspection off schedules the payload prune (MitmConfig.setEnabled).
    // WorkManager is process-wide and outlives each test, so it is set up once.
    @Before
    fun initWorkManager() {
        if (androidx.work.WorkManager.isInitialized()) return
        androidx.work.WorkManager.initialize(
            context,
            androidx.work.Configuration.Builder().setExecutor { it.run() }.build(),
        )
    }

    @After
    fun tearDown() {
        config.setEnabled(false)
        config.clearConsent()
    }

    @Test
    fun organisation_settings_are_withdrawn_with_the_policy() {
        config.setEnabled(true, byOrganisation = true)
        config.recordConsent(byOrganisation = true)
        assertEquals(InspectionStatus.ON_BY_ORGANISATION, MitmConfig.readStatus(context))

        ManagedConfigApplier.withdrawOrganisationSettings(config)

        assertFalse(config.isEnabled)
        assertFalse(config.isConsentValid())
        assertEquals(InspectionStatus.OFF, MitmConfig.readStatus(context))
    }

    @Test
    fun the_users_own_choice_survives_a_removed_policy() {
        config.setEnabled(true)
        config.recordConsent()

        ManagedConfigApplier.withdrawOrganisationSettings(config)

        assertTrue(config.isEnabled)
        assertTrue(config.isConsentValid())
        assertEquals(InspectionStatus.ON, MitmConfig.readStatus(context))
    }

    @Test
    fun switching_off_forgets_who_switched_it_on() {
        config.setEnabled(true, byOrganisation = true)
        config.setEnabled(false)
        config.setEnabled(true)
        assertFalse(config.enabledByOrganisation)
    }

    @Test
    fun the_users_consent_replaces_the_organisations() {
        config.recordConsent(byOrganisation = true)
        config.recordConsent()
        assertFalse(config.consentByOrganisation)
    }
}
