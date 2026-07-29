package dev.minios.ocremote.ui.screens.sessions

import dev.minios.ocremote.domain.model.ServerConfig
import dev.minios.ocremote.domain.model.Session
import dev.minios.ocremote.domain.model.SessionCategory
import dev.minios.ocremote.domain.model.SessionStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class CrossServerSessionsTest {
    private val server = ServerConfig(id = "server", url = "https://example.test")
    private val category = SessionCategory(id = "category", name = "Work", color = "blue", icon = "label")

    @Test
    fun `favorites list excludes regular sessions`() {
        val favorite = item("favorite", isFavorite = true, category = null, favoriteIndex = 0)
        val categorized = item("categorized", isFavorite = false, category = category)

        assertEquals(listOf("favorite"), sortCrossServerFavorites(listOf(favorite, categorized)).map { it.session.id })
    }

    @Test
    fun `favorites respect explicit cross server order`() {
        val second = item("second", isFavorite = true, category = category, favoriteIndex = 1)
        val first = item("first", isFavorite = true, category = null, favoriteIndex = 0)

        assertEquals(
            listOf("first", "second"),
            sortCrossServerFavorites(listOf(second, first)).map { it.session.id },
        )
    }

    @Test
    fun `category filter contains only favorites in that category`() {
        val included = item("included", isFavorite = true, category = category, favoriteIndex = 0)
        val uncategorized = item("uncategorized", isFavorite = true, category = null, favoriteIndex = 1)
        val regular = item("regular", isFavorite = false, category = category)

        assertEquals(
            listOf("included"),
            filterCrossServerFavorites(listOf(regular, uncategorized, included), category.id).map { it.session.id },
        )
    }

    @Test
    fun `moving visible favorite preserves disconnected server positions`() {
        assertEquals(
            listOf("server-a:two", "disconnected:hidden", "server-a:one"),
            moveCrossServerFavoriteOrder(
                currentOrder = listOf("server-a:one", "disconnected:hidden", "server-a:two"),
                visibleOrder = listOf("server-a:one", "server-a:two"),
                itemKey = "server-a:one",
                offset = 1,
            ),
        )
    }

    private fun item(
        id: String,
        isFavorite: Boolean,
        category: SessionCategory?,
        favoriteIndex: Int? = null,
    ) = CrossServerSessionItem(
        server = server,
        session = Session(
            id = id,
            title = id,
            directory = "/project/$id",
            time = Session.Time(created = 1, updated = 2),
        ),
        status = SessionStatus.Idle,
        category = category,
        isFavorite = isFavorite,
        favoriteIndex = favoriteIndex,
        isConnected = true,
    )
}
