package dev.minios.ocremote.ui.navigation

import dev.minios.ocremote.domain.model.ServerConfig
import dev.minios.ocremote.domain.model.Session
import dev.minios.ocremote.domain.model.SessionCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NavGraphTest {
    @Test
    fun `share picker puts connected favorites first in global order`() {
        val firstServer = ServerConfig(id = "server-a", url = "http://a")
        val secondServer = ServerConfig(id = "server-b", url = "http://b")
        val offlineServer = ServerConfig(id = "server-c", url = "http://c")
        val recent = session("recent", updated = 300)
        val firstFavorite = session("favorite-a", updated = 100)
        val secondFavorite = session("favorite-b", updated = 200)
        val offlineFavorite = session("favorite-c", updated = 400)
        val category = SessionCategory(id = "work", name = "Work", color = "blue", icon = "work")

        val result = buildSharePickerItems(
            servers = listOf(firstServer, secondServer, offlineServer),
            sessions = listOf(recent, firstFavorite, secondFavorite, offlineFavorite),
            serverSessions = mapOf(
                firstServer.id to setOf(recent.id, firstFavorite.id),
                secondServer.id to setOf(secondFavorite.id),
                offlineServer.id to setOf(offlineFavorite.id),
            ),
            connectedServerIds = setOf(firstServer.id, secondServer.id),
            preferencesByServer = mapOf(
                firstServer.id to SharePickerServerPreferences(
                    favoriteIds = listOf(firstFavorite.id),
                    categoryAssignments = mapOf(firstFavorite.id to category.id),
                ),
                secondServer.id to SharePickerServerPreferences(listOf(secondFavorite.id), emptyMap()),
                offlineServer.id to SharePickerServerPreferences(listOf(offlineFavorite.id), emptyMap()),
            ),
            favoriteOrder = listOf(
                "${secondServer.id}:${secondFavorite.id}",
                "${firstServer.id}:${firstFavorite.id}",
            ),
            categories = listOf(category),
        )

        assertEquals(listOf(secondFavorite.id, firstFavorite.id, recent.id), result.map { it.session.id })
        assertTrue(result.take(2).all(SharePickerItem::isFavorite))
        assertEquals(category, result.first { it.session.id == firstFavorite.id }.category)
    }

    private fun session(id: String, updated: Long) = Session(
        id = id,
        title = id,
        time = Session.Time(created = updated, updated = updated),
    )
}
