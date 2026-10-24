package com.privacyguard.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BlockSourceTest {

    @Test
    fun every_value_survives_storage() {
        for (s in BlockSource.entries) assertEquals(s, BlockSource.fromStored(s.name))
    }

    @Test
    fun unknown_or_missing_values_read_as_null() {
        assertNull(BlockSource.fromStored(null))
        assertNull(BlockSource.fromStored("SOMETHING_NEWER"))
        assertNull(BlockSource.fromStored("sni"))
    }

    @Test
    fun older_quic_blocks_stored_as_sni_read_as_quic() {
        assertEquals(BlockSource.QUIC, BlockSource.fromStored("SNI", "UDP"))
        assertEquals(BlockSource.SNI, BlockSource.fromStored("SNI", "TCP"))
        assertEquals(BlockSource.QUIC, BlockSource.fromStored("QUIC", "UDP"))
        assertEquals("DoH over QUIC stays DoH", BlockSource.DOH, BlockSource.fromStored("DOH", "UDP"))
        assertNull(BlockSource.fromStored(null, "UDP"))
    }
}
