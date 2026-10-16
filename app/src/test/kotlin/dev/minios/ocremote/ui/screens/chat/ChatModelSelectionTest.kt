package dev.minios.ocremote.ui.screens.chat

import dev.minios.ocremote.data.api.AgentInfo
import dev.minios.ocremote.data.api.ModelSelection
import dev.minios.ocremote.data.api.ProviderInfo
import dev.minios.ocremote.data.api.ProviderModel
import dev.minios.ocremote.domain.model.Message
import dev.minios.ocremote.domain.model.Session
import dev.minios.ocremote.domain.model.TimeInfo
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatModelSelectionTest {
    private val visible = ModelSelection("deepseek", "flash")
    private val other = ModelSelection("deepseek", "other")
    private val providers = listOf(
        ProviderInfo(
            id = "deepseek",
            name = "DeepSeek",
            models = linkedMapOf(
                "flash" to ProviderModel("flash", name = "Flash"),
                "other" to ProviderModel("other", name = "Other"),
            ),
        ),
    )

    @Test
    fun firstMessageUsesVisibleFallbackWhenConfiguredDefaultIsHidden() {
        assertEquals(visible, resolve(configured = ModelSelection("deepseek", "pro"), hidden = true))
    }

    @Test
    fun configuredDefaultIsNotReplacedByFirstCatalogModel() {
        assertEquals(other, resolve(configured = other))
    }

    @Test
    fun unknownServerDefaultIsLeftToServerWithoutLocalFiltering() {
        assertNull(resolve())
    }

    @Test
    fun localFilteringCanChooseVisibleModelWithoutConfiguredDefault() {
        assertEquals(visible, resolve(hidden = true))
    }

    @Test
    fun currentExplicitSelectionWinsImmediatelyOverHistoryAndDefaults() {
        assertEquals(
            other,
            resolve(selected = other, messages = listOf(user(visible, 1)), configured = visible, agent = visible),
        )
    }

    @Test
    fun newestHistoryModelWinsOverConfiguredDefaultRegardlessOfListOrder() {
        assertEquals(other, resolve(messages = listOf(user(other, 2), user(visible, 1)), configured = visible))
    }

    @Test
    fun agentModelWinsOverHistoryAndGlobalDefault() {
        assertEquals(visible, resolve(messages = listOf(user(other, 2)), configured = other, agent = visible))
    }

    @Test
    fun selectedModelIsPreservedBeforeCatalogLoads() {
        assertEquals(
            other,
            resolveChatModelSelection(other, emptyList(), null, null, emptyList(), false, false),
        )
    }

    @Test
    fun automaticFallbackWaitsForDefaultsEvenWhenCatalogAndFiltersAreLoaded() {
        assertNull(
            resolveChatModelSelection(null, emptyList(), null, null, providers, true, true, defaultsReady = false),
        )
        assertEquals(
            other,
            resolveChatModelSelection(other, emptyList(), null, null, providers, true, true, defaultsReady = false),
        )
    }

    @Test
    fun revertedMessageModelDoesNotOverrideRetainedHistory() {
        assertEquals(
            visible,
            resolveChatModelSelection(
                null,
                listOf(user(visible, 1), user(other, 2)),
                null,
                null,
                providers,
                true,
                false,
                revert = Session.Revert(messageId = "message-2"),
            ),
        )
    }

    @Test
    fun partLevelRevertKeepsTargetMessageModel() {
        assertEquals(
            other,
            resolveChatModelSelection(
                null,
                listOf(user(visible, 1), user(other, 2)),
                null,
                null,
                providers,
                true,
                false,
                revert = Session.Revert(messageId = "message-2", partId = "part"),
            ),
        )
    }

    @Test
    fun partialHistoryDoesNotPromoteConfigDefaultOverUnknownLastUserModel() {
        assertNull(
            resolveChatModelSelection(
                null, emptyList(), visible, null, providers, true, true, historyComplete = false,
            ),
        )
        assertEquals(
            other,
            resolveChatModelSelection(
                null, listOf(user(other, 1)), visible, null, providers, true, false, historyComplete = false,
            ),
        )
    }

    @Test
    fun allModelsHiddenDoesNotForceHiddenDefault() {
        assertNull(resolveChatModelSelection(null, emptyList(), other, null, emptyList(), true, true))
    }

    @Test
    fun configuredModelIdsCanContainSlashes() {
        assertEquals(ModelSelection("provider", "org/model"), configuredModelSelection("provider/org/model"))
        assertNull(configuredModelSelection(null))
        assertNull(configuredModelSelection("missing-provider"))
        assertNull(configuredModelSelection("provider/"))
    }

    @Test
    fun agentModelDeserializesServerIdentifiers() {
        val agent = Json.decodeFromString<AgentInfo>(
            """{"name":"plan","model":{"providerID":"deepseek","modelID":"other"}}""",
        )
        assertEquals(other, agent.model)
        assertNull(Json.decodeFromString<AgentInfo>("""{"name":"build"}""").model)
    }

    @Test
    fun sessionOwnedV2ModelWinsBeforeCatalogOrDefaultsLoad() {
        assertEquals(
            other,
            resolveChatModelSelection(
                null, listOf(user(visible, 1)), visible, visible, emptyList(), false, false,
                defaultsReady = false,
                historyComplete = false,
                sessionModel = Session.Model(other.modelId, other.providerId, "high"),
            ),
        )
    }

    @Test
    fun explicitModelWinsOverSessionOwnedV2Model() {
        assertEquals(
            visible,
            resolveChatModelSelection(
                visible, emptyList(), null, null, providers, true, false,
                sessionModel = Session.Model(other.modelId, other.providerId),
            ),
        )
    }

    private fun resolve(
        selected: ModelSelection? = null,
        messages: List<Message> = emptyList(),
        configured: ModelSelection? = null,
        agent: ModelSelection? = null,
        hidden: Boolean = false,
    ) = resolveChatModelSelection(selected, messages, configured, agent, providers, true, hidden)

    private fun user(model: ModelSelection, created: Long) = Message.User(
        id = "message-$created",
        sessionId = "session",
        time = TimeInfo(created = created),
        model = Message.User.Model(model.providerId, model.modelId),
    )
}
