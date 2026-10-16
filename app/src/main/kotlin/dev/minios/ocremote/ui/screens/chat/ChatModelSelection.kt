package dev.minios.ocremote.ui.screens.chat

import dev.minios.ocremote.data.api.ModelSelection
import dev.minios.ocremote.data.api.ProviderInfo
import dev.minios.ocremote.domain.model.Message
import dev.minios.ocremote.domain.model.Session

internal fun resolveChatModelSelection(
    selected: ModelSelection?,
    messages: List<Message>,
    configuredModel: ModelSelection?,
    agentModel: ModelSelection?,
    providers: List<ProviderInfo>,
    catalogLoaded: Boolean,
    hasHiddenModels: Boolean,
    defaultsReady: Boolean = true,
    revert: Session.Revert? = null,
    historyComplete: Boolean = true,
    sessionModel: Session.Model? = null,
): ModelSelection? {
    // V2 owns selection on the session, independently of transcript messages and catalog defaults.
    if (sessionModel != null) return selected ?: ModelSelection(sessionModel.providerId, sessionModel.id)
    if (!defaultsReady) return selected
    val history = modelSelectionHistory(messages, revert).filterIsInstance<Message.User>()
        .filter { it.model != null }
        .maxByOrNull { it.time.created }
        ?.model?.let { ModelSelection(it.providerId, it.modelId) }
    if (selected == null && agentModel == null && history == null && !historyComplete) return null
    val model = selected ?: agentModel ?: history ?: configuredModel
    if (!catalogLoaded) return model
    if (model != null && providers.any { it.id == model.providerId && model.modelId in it.models }) return model
    // A catalog's per-provider defaults are not the server's global or agent default.
    if (model == null && !hasHiddenModels) return null
    return providers.firstOrNull { it.models.isNotEmpty() }?.let { provider ->
        ModelSelection(provider.id, provider.models.keys.first())
    }
}

internal fun modelSelectionHistory(messages: List<Message>, revert: Session.Revert?): List<Message> =
    if (revert == null) messages else messages.filter {
        it.id < revert.messageId || (it.id == revert.messageId && revert.partId != null)
    }

internal fun configuredModelSelection(model: String?): ModelSelection? {
    if (model == null) return null
    val providerId = model.substringBefore('/')
    val modelId = model.substringAfter('/', "")
    return if (providerId.isNotBlank() && modelId.isNotBlank()) ModelSelection(providerId, modelId) else null
}
