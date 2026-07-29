package dev.minios.ocremote.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageIdGeneratorTest {

    @Test
    fun createsServerCompatibleMonotonicMessageIds() {
        val first = MessageIdGenerator.next(1_700_000_000_000)
        val second = MessageIdGenerator.next(1_700_000_000_000)

        assertEquals(30, first.length)
        assertTrue(first.startsWith("msg_"))
        assertTrue(first < second)
    }
}
