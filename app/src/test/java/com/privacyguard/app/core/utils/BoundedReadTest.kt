package com.privacyguard.app.core.utils

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream

class BoundedReadTest {

    @Test fun readsWithinLimit() {
        assertEquals("hello", ByteArrayInputStream("hello".toByteArray()).readUtf8Capped(5))
    }

    @Test(expected = ResponseTooLargeException::class)
    fun failsPastLimit() {
        ByteArrayInputStream(ByteArray(100_000)).readBytesCapped(99_999)
    }
}
