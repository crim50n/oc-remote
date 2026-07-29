package dev.minios.ocremote.ui.screens.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RevertHistoryPaginationTest {

    @Test
    fun requestsOlderHistoryWhenCurrentPageIsEntirelyReverted() {
        assertTrue(
            needsOlderHistoryForRevert(
                messageIds = listOf("msg_0300", "msg_0400"),
                revertMessageId = "msg_0200",
            )
        )
    }

    @Test
    fun stopsWhenPageContainsMessageBeforeRevertPoint() {
        assertFalse(
            needsOlderHistoryForRevert(
                messageIds = listOf("msg_0100", "msg_0300"),
                revertMessageId = "msg_0200",
            )
        )
    }

    @Test
    fun doesNotRequestRecoveryWithoutRevertState() {
        assertFalse(needsOlderHistoryForRevert(listOf("msg_0300"), null))
    }
}
