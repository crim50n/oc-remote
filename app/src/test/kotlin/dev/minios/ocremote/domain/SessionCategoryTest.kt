package dev.minios.ocremote.domain

import dev.minios.ocremote.domain.model.SessionCategory
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionCategoryTest {

    @Test
    fun categoryRoundTripsThroughPersistedJson() {
        val category = SessionCategory(
            id = "category",
            name = "Important work",
            color = "violet",
            icon = "work",
        )

        val restored = Json.decodeFromString<SessionCategory>(Json.encodeToString(category))

        assertEquals(category, restored)
    }
}
