package com.privacyguard.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Rotation and process death restore the screen; only a real onboarding mismatch moves. */
class OnboardingRedirectTest {

    @Test
    fun a_restored_screen_stays_where_it_was() {
        // The 1.1 bug: rotating on Activity went back to Home.
        assertNull(onboardingRedirect(completed = true, currentRoute = "connections"))
        assertNull(onboardingRedirect(completed = true, currentRoute = "appDetail"))
        assertNull(onboardingRedirect(completed = true, currentRoute = "dashboard"))
    }

    @Test
    fun before_the_graph_exists_nothing_moves() {
        assertNull(onboardingRedirect(completed = true, currentRoute = null))
        assertNull(onboardingRedirect(completed = false, currentRoute = null))
    }

    @Test
    fun onboarding_status_still_decides_when_it_does_not_match() {
        assertEquals("dashboard", onboardingRedirect(completed = true, currentRoute = "onboarding"))
        assertEquals("onboarding", onboardingRedirect(completed = false, currentRoute = "connections"))
        assertNull(onboardingRedirect(completed = false, currentRoute = "onboarding"))
    }
}
