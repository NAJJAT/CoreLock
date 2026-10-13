package com.privacyguard.core.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RepeatFilterTest {

    @Test
    fun a_and_aaaa_and_retries_of_one_lookup_count_once() {
        val f = RepeatFilter(10_000)
        assertFalse(f.isRepeat("app|ads.example|true", 0))       // A
        assertTrue(f.isRepeat("app|ads.example|true", 5))        // AAAA
        assertTrue(f.isRepeat("app|ads.example|true", 5_000))    // retry
    }

    @Test
    fun a_later_lookup_is_new() {
        val f = RepeatFilter(10_000)
        f.isRepeat("k", 0)
        assertFalse(f.isRepeat("k", 10_000))
    }

    @Test
    fun different_apps_or_names_are_separate() {
        val f = RepeatFilter(10_000)
        f.isRepeat("a|x|true", 0)
        assertFalse(f.isRepeat("b|x|true", 1))
        assertFalse(f.isRepeat("a|y|true", 1))
    }
}
