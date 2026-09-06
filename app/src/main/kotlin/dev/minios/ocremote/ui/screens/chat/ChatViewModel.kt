package dev.minios.ocremote.ui.screens.chat

import dev.minios.ocremote.logging.AppLogger as Log
import dev.minios.ocremote.BuildConfig
import dev.minios.ocremote.R
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.minios.ocremote.data.api.AgentInfo
import dev.minios.ocremote.data.api.CommandInfo
import dev.minios.ocremote.data.api.ModelSelection
import dev.minios.ocremote.data.api.MessageIdGenerator
import dev.minios.ocremote.data.api.OpenCodeApi
import dev.minios.ocremote.data.api.PromptPart
import dev.minios.ocremote.data.api.ProviderInfo
import dev.minios.ocremote.data.api.ServerConnection
import dev.minios.ocremote.data.repository.DraftRepository
import dev.minios.ocremote.data.repository.Draft
import dev.minios.ocremote.data.repository.EventReducer
import dev.minios.ocremote.data.repository.PendingPromptRecord
import dev.minios.ocremote.data.repository.PromptDeliveryInfo
import dev.minios.ocremote.data.repository.PromptDeliveryState
import dev.minios.ocremote.data.repository.PendingPromptRepository
import dev.minios.ocremote.data.repository.SettingsRepository
import dev.minios.ocremote.data.repository.ServerConnectionStateRepository
import dev.minios.ocremote.domain.model.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "ChatViewModel"

internal fun Throwable.rethrowCancellation() {
    if (this is CancellationException) throw this
}
private const val MAX_REVERT_RECOVERY_PAGES = 20
private const val MAX_CHILD_SESSIONS = 100
private const val FAST_INITIAL_MESSAGE_COUNT = 10
private const val BACKGROUND_MESSAGE_PAGE_COUNT = 25

internal fun fastInitialMessageLimit(configuredLimit: Int): Int =
    configuredLimit.coerceAtLeast(1).coerceAtMost(FAST_INITIAL_MESSAGE_COUNT)

internal fun backgroundMessageLimit(loadedCount: Int, configuredLimit: Int): Int =
    (configuredLimit - loadedCount).coerceIn(1, BACKGROUND_MESSAGE_PAGE_COUNT)

internal fun descendantSessionIds(sessions: List<Session>, rootSessionId: String): Set<String> {
    val result = linkedSetOf(rootSessionId)
    var changed: Boolean
    do {
        changed = false
        sessions.forEach { session ->
            if (session.parentId in result && result.add(session.id)) changed = true
        }
    } while (changed)
    return result
}

data class ChatUiState(
    val sessionTitle: String = "",
    val sessionLoaded: Boolean = false,
    val parentSessionId: String? = null,
    val serverName: String = "",
    val messages: List<ChatMessage> = emptyList(),
    val revert: Session.Revert? = null,
    val sessionStatus: SessionStatus = SessionStatus.Idle,
    val pendingInteractions: List<PendingInteraction> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val isSending: Boolean = false,
    val providers: List<ProviderInfo> = emptyList(),
    val hasServerModelCatalog: Boolean = false,
    val defaultModels: Map<String, String> = emptyMap(),
    val selectedProviderId: String? = null,
    val selectedModelId: String? = null,
    val totalCost: Double = 0.0,
    val totalInputTokens: Int = 0,
    val totalOutputTokens: Int = 0,
    val agents: List<AgentInfo> = emptyList(),
    val selectedAgent: String = "build",
    val variantNames: List<String> = emptyList(),
    val selectedVariant: String? = null,
    val commands: List<CommandInfo> = emptyList(),
    /** True when there are older messages on the server that haven't been loaded yet. */
    val hasOlderMessages: Boolean = false,
    /** True while a "load older" request is in flight. */
    val isLoadingOlder: Boolean = false,
    /** Share URL if session is shared, null otherwise. */
    val shareUrl: String? = null,
    /** Context window size of the current model (0 if unknown). */
    val contextWindow: Int = 0,
    /** Total tokens from the last assistant message with output > 0 (current context usage). */
    val lastContextTokens: Int = 0,
    val contextUsage: ContextUsageDetails = ContextUsageDetails(),
)

data class ContextUsageDetails(
    val input: Int = 0,
    val output: Int = 0,
    val reasoning: Int = 0,
    val cacheRead: Int = 0,
    val cacheWrite: Int = 0,
    val sessionInput: Int = 0,
    val sessionOutput: Int = 0,
    val sessionReasoning: Int = 0,
    val sessionCacheRead: Int = 0,
    val sessionCacheWrite: Int = 0,
    val totalCost: Double = 0.0,
    val userMessages: Int = 0,
    val assistantMessages: Int = 0,
) {
    val currentTotal: Int get() = input + output + reasoning + cacheRead + cacheWrite
    val sessionTotal: Int get() = sessionInput + sessionOutput + sessionReasoning + sessionCacheRead + sessionCacheWrite
}

internal fun sessionAcceptsPrompts(session: Session?): Boolean = session != null && session.parentId == null

internal fun needsOlderHistoryForRevert(messageIds: Collection<String>, revertMessageId: String?): Boolean {
    return revertMessageId != null && messageIds.none { it < revertMessageId }
}

internal fun missingPendingPromptIds(
    pending: List<PendingPromptRecord>,
    authoritative: List<MessageWithParts>,
    now: Long,
    minimumAgeMs: Long,
): Set<String> {
    val oldestMessageId = authoritative.minOfOrNull { it.info.id } ?: return emptySet()
    val authoritativeIds = authoritative.mapTo(mutableSetOf()) { it.info.id }
    return pending.asSequence()
        .filter { it.messageId !in authoritativeIds }
        .filter { it.messageId >= oldestMessageId }
        .filter { now - it.createdAt >= minimumAgeMs }
        .mapTo(mutableSetOf(), PendingPromptRecord::messageId)
}

data class RevertedDraftPayload(
    val text: String,
    val attachmentUris: List<String> = emptyList(),
)

/**
 * A flattened chat message for the UI.
 * Combines Message info with its parts.
 */
data class ChatMessage(
    val message: Message,
    val parts: List<Part>,
    val delivery: MessageDelivery? = null,
) {
    val isUser: Boolean get() = message is Message.User
    val isAssistant: Boolean get() = message is Message.Assistant
}

enum class MessageDelivery { QUEUED, PROMOTED }

data class ChatTurn(val messages: List<ChatMessage>) {
    val isUser: Boolean get() = messages.singleOrNull()?.isUser == true
    val key: String get() = if (isUser) "u_${messages.single().message.id}" else "t_${messages.first().message.id}"
}

internal fun groupChatTurns(messages: List<ChatMessage>): List<ChatTurn> {
    val result = mutableListOf<ChatTurn>()
    var assistantRun = mutableListOf<ChatMessage>()

    fun flushAssistantRun() {
        if (assistantRun.isNotEmpty()) {
            result += ChatTurn(assistantRun)
            assistantRun = mutableListOf()
        }
    }

    messages.forEach { message ->
        if (message.isAssistant) {
            assistantRun += message
        } else {
            flushAssistantRun()
            result += ChatTurn(listOf(message))
        }
    }
    flushAssistantRun()
    return result
}

private fun PendingPromptRecord.toChatMessage(delivery: MessageDelivery = MessageDelivery.QUEUED): ChatMessage {
    val message = Message.User(
        id = messageId,
        sessionId = sessionId,
        time = TimeInfo(createdAt),
        agent = agent,
        model = model?.let { Message.User.Model(it.providerId, it.modelId) },
        variant = variant,
    )
    return ChatMessage(message, toLocalParts(), delivery)
}

private fun PendingPromptRecord.toLocalParts(): List<Part> =
    parts.mapIndexedNotNull { index, part ->
        when (part.type) {
            "text" -> part.text?.takeIf { it.isNotBlank() }?.let {
                Part.Text("$messageId-local-$index", sessionId, messageId, text = it)
            }
            "file" -> Part.File(
                id = "$messageId-local-$index",
                sessionId = sessionId,
                messageId = messageId,
                mime = part.mime ?: "application/octet-stream",
                filename = part.filename ?: part.path,
                url = part.url,
            )
            else -> null
        }
    }

@HiltViewModel
class ChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val eventReducer: EventReducer,
    private val api: OpenCodeApi,
    private val draftRepository: DraftRepository,
    private val settingsRepository: SettingsRepository,
    private val pendingPromptRepository: PendingPromptRepository,
    private val connectionStateRepository: ServerConnectionStateRepository,
) : ViewModel() {

    @Volatile
    private var sessionPromptable = false

    private val serverUrl: String = savedStateHandle.get<String>("serverUrl").orEmpty()
    private val username: String = savedStateHandle.get<String>("username").orEmpty()
    private val password: String = savedStateHandle.get<String>("password").orEmpty()
    val serverName: String = savedStateHandle.get<String>("serverName").orEmpty()
    val serverId: String = savedStateHandle.get<String>("serverId").orEmpty()
    val sessionId: String = savedStateHandle.get<String>("sessionId").orEmpty()

    private val conn = ServerConnection.from(serverUrl, username, password.ifEmpty { null })
    private fun isServerConnected(): Boolean = serverId in connectionStateRepository.connectedServerIds.value

    private val _isLoading = MutableStateFlow(true)
    private var messageLoadJob: Job? = null
    private val _error = MutableStateFlow<String?>(null)
    private val _isSending = MutableStateFlow(false)
    private val _pendingPrompts = MutableStateFlow<List<PendingPromptRecord>>(emptyList())
    private val _allProviders = MutableStateFlow<List<ProviderInfo>>(emptyList())
    private val _providers = MutableStateFlow<List<ProviderInfo>>(emptyList())
    private val _hiddenModels = MutableStateFlow<Set<String>>(emptySet())
    private val _defaultModels = MutableStateFlow<Map<String, String>>(emptyMap())
    private val _selectedProviderId = MutableStateFlow<String?>(null)
    private val _selectedModelId = MutableStateFlow<String?>(null)
    // Track if the model was explicitly selected by the user to avoid overwriting it with defaults/history
    private var isModelExplicitlySelected = false
    /** The directory of this session's project — sent as x-opencode-directory so the server resolves the correct project context. */
    private var sessionDirectory: String? = eventReducer.sessions.value
        .firstOrNull { it.id == sessionId }
        ?.directory
        ?.takeIf { it.isNotBlank() }
    /** Signals when [loadSession] has finished (successfully or with error), so that terminal
     *  creation can wait for [sessionDirectory] to be populated. */
    private val sessionLoaded = CompletableDeferred<Unit>()
    private val _agents = MutableStateFlow<List<AgentInfo>>(emptyList())
    /** Pair(agentName, explicitlySelected) — using a single flow avoids race between flag and value */
    private val _selectedAgent = MutableStateFlow("build" to false)
    private val _selectedVariant = MutableStateFlow<String?>(null)
    private val _commands = MutableStateFlow<List<CommandInfo>>(emptyList())
    private val terminalWorkspace = ServerTerminalRegistry.workspaceFor(serverId, api, conn)
    val terminalTabs: StateFlow<List<TerminalTabUi>> = terminalWorkspace.tabList
    val activeTerminalTabId: StateFlow<String?> = terminalWorkspace.activeTabId
    /** Incremented on active terminal tab updates — observe to trigger recomposition. */
    val terminalVersion: StateFlow<Long> = terminalWorkspace.activeVersion
    val terminalConnected: StateFlow<Boolean> = terminalWorkspace.activeConnected
    val terminalFontSizeSp: StateFlow<Float> = terminalWorkspace.activeFontSizeSp
    val terminalEmulator: TerminalEmulator get() = terminalWorkspace.activeEmulator()

    // ============ Draft Persistence ============
    /** Draft text for the input field — survives navigation / app restart. */
    private val _draftText = MutableStateFlow("")
    val draftText: StateFlow<String> = _draftText

    /** One-shot event: emits reverted draft payload (text + image attachments) for ChatScreen. */
    private val _revertedDraftEvent = MutableSharedFlow<RevertedDraftPayload>(extraBufferCapacity = 1)
    val revertedDraftEvent: SharedFlow<RevertedDraftPayload> = _revertedDraftEvent

    /** Draft attachment URIs (content:// URIs as strings) — survives navigation / app restart. */
    private val _draftAttachmentUris = MutableStateFlow<List<String>>(emptyList())
    val draftAttachmentUris: StateFlow<List<String>> = _draftAttachmentUris

    /** Set of file paths that have been confirmed by user selection from the popup */
    private val _confirmedFilePaths = MutableStateFlow<Set<String>>(emptySet())
    val confirmedFilePaths: StateFlow<Set<String>> = _confirmedFilePaths

    // ============ Settings (exposed for ChatScreen) ============
    val chatFontSize = settingsRepository.chatFontSize.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), "medium"
    )
    val codeWordWrap = settingsRepository.codeWordWrap.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), false
    )
    val confirmBeforeSend = settingsRepository.confirmBeforeSend.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), false
    )
    val compactMessages = settingsRepository.compactMessages.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), false
    )
    val collapseTools = settingsRepository.collapseTools.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), false
    )
    val expandReasoning = settingsRepository.expandReasoning.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), false
    )
    val showTurnDividers = settingsRepository.showTurnDividers.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), true
    )
    val hapticFeedback = settingsRepository.hapticFeedback.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), true
    )
    val hapticDurationMillis = settingsRepository.hapticDurationMillis.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), 30
    )
    val hapticAmplitude = settingsRepository.hapticAmplitude.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), 160
    )
    val keepScreenOn = settingsRepository.keepScreenOn.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), false
    )
    val compressImageAttachments = settingsRepository.compressImageAttachments.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), true
    )
    val imageAttachmentMaxLongSide = settingsRepository.imageAttachmentMaxLongSide.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), 1440
    )
    val imageAttachmentWebpQuality = settingsRepository.imageAttachmentWebpQuality.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), 60
    )

    suspend fun shouldShowTerminalPanelHint(): Boolean = settingsRepository.showTerminalPanelHint.first()
    // ============ Pagination ============
    /** Current message limit (doubles each time user loads older messages). */
    private var currentMessageLimit = 50
    private var olderMessagesCursor: String? = null
    /** Whether there are more messages on the server beyond the current limit. */
    private val _hasOlderMessages = MutableStateFlow(false)
    /** Whether a "load older" request is in flight. */
    private val _isLoadingOlder = MutableStateFlow(false)

    val uiState: StateFlow<ChatUiState> = combine(
        eventReducer.sessions,
        eventReducer.messages,
        eventReducer.parts,
        eventReducer.sessionStatuses,
        eventReducer.pendingInteractions,
        _isLoading,
        _error,
        _isSending,
        _selectedProviderId,
        _selectedModelId,
        _allProviders,
        _providers,
        _defaultModels,
        _agents,
        _selectedAgent,
        _selectedVariant,
        _commands,
        _hasOlderMessages,
        _isLoadingOlder,
        _pendingPrompts,
        eventReducer.promptDeliveries,
    ) { args ->
        @Suppress("UNCHECKED_CAST")
        val allSessions = args[0] as List<Session>
        val allMessages = args[1] as Map<String, List<Message>>
        val allParts = args[2] as Map<String, List<Part>>
        val statuses = args[3] as Map<String, SessionStatus>
        val pendingInteractions = args[4] as List<PendingInteraction>
        val loading = args[5] as Boolean
        val error = args[6] as String?
        val sending = args[7] as Boolean
        val selProviderId = args[8] as String?
        val selModelId = args[9] as String?
        val allProviders = args[10] as List<ProviderInfo>
        val providers = args[11] as List<ProviderInfo>
        val defaultModels = args[12] as Map<String, String>
        val agents = args[13] as List<AgentInfo>
        @Suppress("UNCHECKED_CAST")
        val agentSelection = args[14] as Pair<String, Boolean>
        val selectedAgent = agentSelection.first
        val isAgentExplicitlySelected = agentSelection.second
        val selectedVariant = args[15] as String?
        val commands = args[16] as List<CommandInfo>
        val hasOlderMessages = args[17] as Boolean
        val isLoadingOlder = args[18] as Boolean
        val pendingPrompts = args[19] as List<PendingPromptRecord>
        val promptDeliveries = args[20] as Map<String, PromptDeliveryInfo>
        fun deliveryFor(messageId: String) = when (promptDeliveries[messageId]?.state) {
            PromptDeliveryState.PROMOTED -> MessageDelivery.PROMOTED
            else -> MessageDelivery.QUEUED
        }

        val session = allSessions.find { it.id == sessionId }
        val interactionSessionIds = descendantSessionIds(allSessions, sessionId)
        val sessionMessages = allMessages[sessionId] ?: emptyList()
        val confirmedMessageIds = sessionMessages.asSequence().map { it.id }.toSet()
        val pendingById = pendingPrompts.associateBy { it.messageId }
        val optimisticMessages = pendingPrompts
            .filterNot { it.messageId in confirmedMessageIds }
            .map { it.toChatMessage(deliveryFor(it.messageId)) }
        val revertState = session?.revert

        val chatMessages = run {
            val sorted = sessionMessages.sortedBy { it.time.created }
            // Filter out reverted messages (at or after revert point)
            val visible = if (revertState != null) {
                sorted.filter { it.id < revertState.messageId }
            } else {
                sorted
            }
            suppressRepeatedPatchCards(
                (visible.map { msg ->
                    val pending = pendingById[msg.id]
                    val authoritativeParts = allParts[msg.id].orEmpty()
                    ChatMessage(
                        message = msg,
                        parts = authoritativeParts.ifEmpty { pending?.toLocalParts().orEmpty() },
                        delivery = pending?.let { deliveryFor(msg.id) },
                    )
                } + optimisticMessages).sortedBy { it.message.time.created },
            )
        }

        // Resolve model: explicit selection > last user message's model > provider default
        var effectiveProviderId = selProviderId
        var effectiveModelId = selModelId

        // If no explicit selection, try to resolve from history
        if (!isModelExplicitlySelected) {
             val lastUserWithModel = sessionMessages
                .filterIsInstance<Message.User>()
                .lastOrNull { it.model != null }
             if (lastUserWithModel?.model != null) {
                 effectiveProviderId = lastUserWithModel.model.providerId
                 effectiveModelId = lastUserWithModel.model.modelId
             } else if (effectiveModelId == null && defaultModels.isNotEmpty()) {
                 // Fallback to default if nothing in history and nothing selected
                 val entry = defaultModels.entries.first()
                 effectiveProviderId = entry.key
                 effectiveModelId = entry.value
             }
        }

        // Resolve agent from last user message if not explicitly changed
        val effectiveAgent = if (!isAgentExplicitlySelected) {
            val lastUserAgent = sessionMessages
                .filterIsInstance<Message.User>()
                .lastOrNull { it.agent != null }
                ?.agent
            lastUserAgent ?: selectedAgent
        } else {
            selectedAgent
        }

        // Keep raw state in sync so sendParts()/runShellCommand() always use the displayed value
        if (effectiveAgent != selectedAgent && !isAgentExplicitlySelected) {
            _selectedAgent.value = effectiveAgent to false
        }

        // Compute cost/token totals from assistant messages
        val assistantMessages = sessionMessages.filterIsInstance<Message.Assistant>()
        val totalCost = assistantMessages.sumOf { it.cost ?: 0.0 }
        val totalInputTokens = assistantMessages.sumOf { it.tokens?.input ?: 0 }
        val totalOutputTokens = assistantMessages.sumOf { it.tokens?.output ?: 0 }
        // Context usage: total tokens from the last assistant message with output > 0
        val lastWithOutput = assistantMessages.lastOrNull { (it.tokens?.output ?: 0) > 0 }
        val lastContextTokens = lastWithOutput?.tokens?.let { t ->
            t.input + t.output + t.reasoning + t.cache.read + t.cache.write
        } ?: 0
        val contextUsage = ContextUsageDetails(
            input = lastWithOutput?.tokens?.input ?: 0,
            output = lastWithOutput?.tokens?.output ?: 0,
            reasoning = lastWithOutput?.tokens?.reasoning ?: 0,
            cacheRead = lastWithOutput?.tokens?.cache?.read ?: 0,
            cacheWrite = lastWithOutput?.tokens?.cache?.write ?: 0,
            sessionInput = totalInputTokens,
            sessionOutput = totalOutputTokens,
            sessionReasoning = assistantMessages.sumOf { it.tokens?.reasoning ?: 0 },
            sessionCacheRead = assistantMessages.sumOf { it.tokens?.cache?.read ?: 0 },
            sessionCacheWrite = assistantMessages.sumOf { it.tokens?.cache?.write ?: 0 },
            totalCost = totalCost,
            userMessages = sessionMessages.count { it is Message.User },
            assistantMessages = assistantMessages.size,
        )

        // Resolve available variants for the currently selected model.
        // If selected model is no longer visible (filtered out), fall back to first visible model.
        var currentModel = if (effectiveProviderId != null && effectiveModelId != null) {
            providers.find { it.id == effectiveProviderId }
                ?.models?.get(effectiveModelId)
        } else null
        if (currentModel == null) {
            val firstProvider = providers.firstOrNull()
            val firstModel = firstProvider?.models?.values?.firstOrNull()
            if (firstProvider != null && firstModel != null) {
                effectiveProviderId = firstProvider.id
                effectiveModelId = firstModel.id
                currentModel = firstModel
            }
        }
        // Match the Web UI by preserving the variant order supplied by the server.
        val availableVariants = currentModel?.variants?.keys?.toList() ?: emptyList()

        ChatUiState(
            sessionTitle = session?.title ?: "Chat",
            sessionLoaded = session != null,
            parentSessionId = session?.parentId,
            serverName = serverName,
            messages = chatMessages,
            revert = revertState,
            sessionStatus = statuses[sessionId] ?: SessionStatus.Idle,
            pendingInteractions = pendingInteractions.filter { it.sessionId in interactionSessionIds },
            isLoading = loading,
            error = error,
            isSending = sending,
            providers = providers,
            hasServerModelCatalog = allProviders.any { it.models.isNotEmpty() },
            defaultModels = defaultModels,
            selectedProviderId = effectiveProviderId,
            selectedModelId = effectiveModelId,
            totalCost = totalCost,
            totalInputTokens = totalInputTokens,
            totalOutputTokens = totalOutputTokens,
            agents = agents.filter { it.mode != "subagent" && !it.hidden },
            selectedAgent = effectiveAgent,
            variantNames = availableVariants,
            selectedVariant = if (selectedVariant != null && selectedVariant in availableVariants) selectedVariant else null,
            commands = commands,
            hasOlderMessages = hasOlderMessages,
            isLoadingOlder = isLoadingOlder,
            shareUrl = session?.share?.url,
            contextWindow = currentModel?.limit?.context ?: 0,
            lastContextTokens = lastContextTokens,
            contextUsage = contextUsage,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        ChatUiState()
    )

    init {
        _pendingPrompts.value = pendingPromptRepository.getForSession(sessionId)
        viewModelScope.launch {
            eventReducer.messages.collect { messagesBySession ->
                val confirmedIds = messagesBySession[sessionId].orEmpty().mapTo(mutableSetOf()) { it.id }
                val confirmedPending = _pendingPrompts.value.filter {
                    it.messageId in confirmedIds
                }
                if (confirmedPending.isNotEmpty()) {
                    val reconciledIds = confirmedPending.mapTo(mutableSetOf()) { it.messageId }
                    confirmedPending.forEach { pendingPromptRepository.remove(it.messageId) }
                    _pendingPrompts.value = _pendingPrompts.value.filterNot { it.messageId in reconciledIds }
                }
            }
        }

        // Restore draft from disk
        val draft = draftRepository.getDraft(sessionId)
        if (draft != null) {
            _draftText.value = draft.text
            _draftAttachmentUris.value = draft.imageUris
            if (draft.confirmedFilePaths.isNotEmpty()) {
                _confirmedFilePaths.value = draft.confirmedFilePaths.toSet()
            }
            if (!draft.selectedAgent.isNullOrBlank()) {
                _selectedAgent.value = draft.selectedAgent to true
            }
            if (!draft.selectedVariant.isNullOrBlank()) {
                _selectedVariant.value = draft.selectedVariant
            }
        }

        viewModelScope.launch {
            settingsRepository.hiddenModels(serverId).collect { hidden ->
                _hiddenModels.value = hidden
                applyProviderFilter()
            }
        }

        viewModelScope.launch {
            settingsRepository.terminalFontSize.collect { size ->
                terminalWorkspace.setDefaultFontSize(size)
            }
        }

        viewModelScope.launch {
            currentMessageLimit = settingsRepository.initialMessageCount.first()
            connectionStateRepository.connectedServerIds
                .map { serverId in it }
                .distinctUntilChanged()
                .collectLatest { connected ->
                    terminalWorkspace.setServerAvailable(connected)
                    if (connected) {
                        loadSession()
                        loadMessages()
                        loadPendingRequests()
                        loadProviders()
                        loadAgents()
                        loadCommands()
                    } else {
                        messageLoadJob?.cancel()
                        _isLoading.value = false
                        _isLoadingOlder.value = false
                        _error.value = null
                    }
                }
        }
        viewModelScope.launch {
            while (true) {
                delay(3_000)
                if (isServerConnected()) reconcileActiveStatus()
            }
        }

    }

    private suspend fun reconcileActiveStatus() {
        val localStatus = eventReducer.sessionStatuses.value[sessionId]
        val hasRunningTool = eventReducer.messages.value[sessionId].orEmpty().any { message ->
            eventReducer.parts.value[message.id].orEmpty().any { part ->
                part is Part.Tool && part.state is ToolState.Running
            }
        }
        if (localStatus !is SessionStatus.Busy && !hasRunningTool) return

        try {
            val remoteStatus = api.listSessionStatuses(conn, sessionDirectory)[sessionId] ?: SessionStatus.Idle
            if (remoteStatus is SessionStatus.Idle && (localStatus !is SessionStatus.Idle || hasRunningTool)) {
                val messages = api.listMessages(conn, sessionId, limit = 50, directory = sessionDirectory)
                eventReducer.mergeMessages(sessionId, messages)
            }
            eventReducer.updateSessionStatus(sessionId, remoteStatus)
        } catch (e: Exception) {
            e.rethrowCancellation()
            if (BuildConfig.DEBUG) Log.d(TAG, "Failed to reconcile active status for $sessionId: ${e.message}")
        }
    }

    /** Load the session info to get its directory for correct project context. */
    private suspend fun loadSession() {
        try {
            val session = api.getSession(conn, sessionId, directory = sessionDirectory)
            sessionPromptable = sessionAcceptsPrompts(session)
            if (session.directory.isNotBlank()) {
                sessionDirectory = session.directory
                if (BuildConfig.DEBUG) Log.d(TAG, "Session directory resolved")
            }
            val sessions = mutableListOf(session)
            val queue = ArrayDeque<String>().apply { add(session.id) }
            val visited = mutableSetOf(session.id)
            while (queue.isNotEmpty() && visited.size < MAX_CHILD_SESSIONS) {
                val parentId = queue.removeFirst()
                val children = try {
                    api.listChildSessions(conn, parentId, directory = sessionDirectory)
                        .filter { visited.add(it.id) }
                } catch (e: Exception) {
                    e.rethrowCancellation()
                    Log.e(TAG, "Failed to load children for session $parentId", e)
                    emptyList()
                }
                sessions += children
                children.forEach { queue.addLast(it.id) }
            }
            eventReducer.setSessions(serverId, sessions)
        } catch (e: Exception) {
            e.rethrowCancellation()
            Log.e(TAG, "Failed to load session info", e)
        } finally {
            sessionLoaded.complete(Unit)
        }
    }

    fun loadMessages() {
        if (!isServerConnected()) return
        messageLoadJob?.cancel()
        messageLoadJob = viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val initialLimit = fastInitialMessageLimit(currentMessageLimit)
                val firstPage = api.listMessagesPage(
                    conn,
                    sessionId,
                    limit = initialLimit,
                    directory = sessionDirectory,
                )
                val messages = firstPage.messages.toMutableList()
                val revertMessageId = eventReducer.sessions.value.find { it.id == sessionId }?.revert?.messageId
                var nextCursor = firstPage.nextCursor
                val knownIds = messages.mapTo(mutableSetOf()) { it.info.id }
                var recoveryPages = 0

                olderMessagesCursor = nextCursor
                eventReducer.mergeMessages(sessionId, messages)
                reconcilePendingPrompts(
                    authoritative = messages,
                    minimumAgeMs = 10_000L,
                )
                _hasOlderMessages.value = nextCursor != null
                _isLoading.value = false
                if (BuildConfig.DEBUG) {
                    Log.d(
                        TAG,
                        "Displayed initial ${messages.size} messages for session $sessionId " +
                            "(requested=$initialLimit, hasOlder=${_hasOlderMessages.value})",
                    )
                }

                while (nextCursor != null) {
                    val needsConfiguredHistory = messages.size < currentMessageLimit
                    val needsRevertHistory = !needsConfiguredHistory &&
                        recoveryPages < MAX_REVERT_RECOVERY_PAGES &&
                        needsOlderHistoryForRevert(knownIds, revertMessageId)
                    if (!needsConfiguredHistory && !needsRevertHistory) break

                    val requestedCursor = nextCursor
                    val pageLimit = if (needsConfiguredHistory) {
                        backgroundMessageLimit(messages.size, currentMessageLimit)
                    } else {
                        currentMessageLimit
                    }
                    _isLoadingOlder.value = true
                    val olderPage = try {
                        api.listMessagesPage(
                            conn,
                            sessionId,
                            limit = pageLimit,
                            before = requestedCursor,
                            directory = sessionDirectory,
                        )
                    } catch (e: Exception) {
                        e.rethrowCancellation()
                        Log.e(TAG, "Failed to preload older messages", e)
                        break
                    }
                    messages += olderPage.messages
                    knownIds += olderPage.messages.map { it.info.id }
                    eventReducer.mergeMessages(sessionId, olderPage.messages)
                    if (needsRevertHistory) recoveryPages++
                    nextCursor = olderPage.nextCursor?.takeUnless { it == requestedCursor }
                    olderMessagesCursor = nextCursor
                    _hasOlderMessages.value = nextCursor != null
                    if (olderPage.messages.isEmpty()) break
                }
                olderMessagesCursor = nextCursor
                _hasOlderMessages.value = nextCursor != null
                if (BuildConfig.DEBUG) {
                    Log.d(
                        TAG,
                        "Finished background history load: ${messages.size} messages for session $sessionId " +
                            "(limit=$currentMessageLimit, revertRecoveryPages=$recoveryPages, hasOlder=${_hasOlderMessages.value})",
                    )
                }
            } catch (e: Exception) {
                e.rethrowCancellation()
                Log.e(TAG, "Failed to load messages", e)
                // On OOM or other memory errors, retry with a smaller limit
                if (e is OutOfMemoryError || (e.cause is OutOfMemoryError)) {
                    Log.w(TAG, "OOM loading messages, retrying with smaller limit")
                    currentMessageLimit = (currentMessageLimit / 2).coerceAtLeast(10)
                    try {
                        val page = api.listMessagesPage(
                            conn,
                            sessionId,
                            limit = currentMessageLimit,
                            directory = sessionDirectory,
                        )
                        val messages = page.messages
                        olderMessagesCursor = page.nextCursor
                        eventReducer.mergeMessages(sessionId, messages)
                        _hasOlderMessages.value = page.nextCursor != null
                        if (BuildConfig.DEBUG) Log.d(TAG, "Retry succeeded: loaded ${messages.size} messages (limit=$currentMessageLimit)")
                    } catch (retryEx: Exception) {
                        retryEx.rethrowCancellation()
                        Log.e(TAG, "Retry also failed", retryEx)
                        _error.value = retryEx.message ?: "Failed to load messages"
                    }
                } else {
                    _error.value = e.message ?: "Failed to load messages"
                }
            } finally {
                _isLoading.value = false
                _isLoadingOlder.value = false
            }
        }
    }

    fun reloadSession() {
        if (!isServerConnected()) return
        _isLoading.value = true
        _error.value = null
        olderMessagesCursor = null
        _hasOlderMessages.value = false
        eventReducer.clearSessionHistory(sessionId)

        viewModelScope.launch {
            currentMessageLimit = settingsRepository.initialMessageCount.first()
            loadSession()
            loadPendingRequests()
            loadMessages()
        }
    }

    /**
     * Load older messages by doubling the limit and reloading.
     * The server returns the N most recent messages, so we simply request more.
     */
    fun loadOlderMessages() {
        if (!isServerConnected()) return
        viewModelScope.launch {
            var nextCursor: String? = olderMessagesCursor ?: return@launch
            _isLoadingOlder.value = true
            try {
                val messages = mutableListOf<MessageWithParts>()
                val knownIds = eventReducer.messages.value[sessionId].orEmpty().mapTo(mutableSetOf()) { it.id }
                val revertMessageId = eventReducer.sessions.value.find { it.id == sessionId }?.revert?.messageId
                var recoveryPages = 0
                do {
                    val requestedCursor = nextCursor
                    val page = api.listMessagesPage(
                        conn,
                        sessionId,
                        limit = currentMessageLimit,
                        before = requestedCursor,
                        directory = sessionDirectory,
                    )
                    messages += page.messages
                    knownIds += page.messages.map { it.info.id }
                    recoveryPages++
                    nextCursor = page.nextCursor?.takeUnless { it == requestedCursor }
                    if (page.messages.isEmpty()) break
                } while (
                    nextCursor != null &&
                    recoveryPages < MAX_REVERT_RECOVERY_PAGES &&
                    needsOlderHistoryForRevert(knownIds, revertMessageId)
                )
                eventReducer.mergeMessages(sessionId, messages)
                olderMessagesCursor = nextCursor
                _hasOlderMessages.value = nextCursor != null
                if (BuildConfig.DEBUG) {
                    Log.d(
                        TAG,
                        "Loaded older: ${messages.size} messages " +
                            "(revertRecoveryPages=$recoveryPages, hasOlder=${_hasOlderMessages.value})",
                    )
                }
            } catch (e: Exception) {
                e.rethrowCancellation()
                Log.e(TAG, "Failed to load older messages", e)
            } finally {
                _isLoadingOlder.value = false
            }
        }
    }

    /**
     * Load pending questions from the server REST API.
     * Converts QuestionRequest DTOs to SseEvent.QuestionAsked domain objects.
     * Must be called after loadSession() so sessionDirectory is set.
     */
    private suspend fun loadPendingRequests() {
        try {
            val revision = eventReducer.pendingSnapshotRevision()
            val allPermissions = api.listPendingPermissions(conn, directory = sessionDirectory)
            val allQuestions = api.listPendingQuestions(conn, directory = sessionDirectory)
            val interactionSessionIds = descendantSessionIds(eventReducer.sessions.value, sessionId)
            val sessionPermissions = allPermissions
                .filter { it.sessionId in interactionSessionIds }
                .map { req ->
                    SseEvent.PermissionAsked(
                        id = req.id,
                        sessionId = req.sessionId,
                        permission = req.permission,
                        patterns = req.patterns,
                        always = req.always,
                        metadata = req.metadata,
                        tool = req.tool,
                    )
                }
            val sessionQuestions = allQuestions
                .filter { it.sessionId in interactionSessionIds }
                .map { req ->
                    SseEvent.QuestionAsked(
                        id = req.id,
                        sessionId = req.sessionId,
                        questions = req.questions.map { q ->
                            SseEvent.QuestionAsked.Question(
                                header = q.header,
                                question = q.question,
                                multiple = q.multiple,
                                custom = q.custom,
                                options = q.options.map { o ->
                                    SseEvent.QuestionAsked.Option(
                                        label = o.label,
                                        description = o.description
                                    )
                                }
                            )
                        },
                        tool = req.tool
                    )
                }
            val applied = eventReducer.replacePendingRequestsForSessions(
                sessionIds = interactionSessionIds,
                permissions = sessionPermissions,
                questions = sessionQuestions,
                expectedRevision = revision,
            )
            Log.i(
                TAG,
                "Pending requests loaded: session=$sessionId descendants=${interactionSessionIds.size} " +
                    "permissions=${sessionPermissions.size}/${allPermissions.size} " +
                    "questions=${sessionQuestions.size}/${allQuestions.size} applied=$applied",
            )
        } catch (e: Exception) {
            e.rethrowCancellation()
            Log.e(TAG, "Failed to load pending requests: ${e.javaClass.simpleName}: ${e.message}", e)
        }
    }

    // Removed initModelFromMessages as it's handled reactively

    private suspend fun loadProviders() {
        try {
            val response = api.getProviders(conn)
            _allProviders.value = response.providers
            applyProviderFilter()
            _defaultModels.value = response.default
            if (BuildConfig.DEBUG) Log.d(TAG, "Loaded ${response.providers.size} providers, defaults: ${response.default}")
        } catch (e: Exception) {
            e.rethrowCancellation()
            Log.e(TAG, "Failed to load providers", e)
        }
    }

    private fun applyProviderFilter() {
        val hidden = _hiddenModels.value
        val filtered = _allProviders.value
            .map { provider ->
                provider.copy(
                    models = provider.models.filterKeys { modelId ->
                        "${provider.id}:$modelId" !in hidden
                    }
                )
            }
            .filter { it.models.isNotEmpty() }
        _providers.value = filtered
    }

    private suspend fun loadAgents() {
        try {
            val agents = api.listAgents(conn)
            _agents.value = agents
            if (BuildConfig.DEBUG) Log.d(TAG, "Loaded ${agents.size} agents: ${agents.map { it.name }}")
        } catch (e: Exception) {
            e.rethrowCancellation()
            Log.e(TAG, "Failed to load agents", e)
        }
    }

    fun selectAgent(name: String) {
        _selectedAgent.value = name to true
    }

    private suspend fun loadCommands() {
        try {
            val commands = api.listCommands(conn)
            _commands.value = commands
            if (BuildConfig.DEBUG) Log.d(TAG, "Loaded ${commands.size} commands: ${commands.map { it.name }}")
        } catch (e: Exception) {
            e.rethrowCancellation()
            Log.e(TAG, "Failed to load commands", e)
        }
    }

    fun selectVariant(name: String?) {
        _selectedVariant.value = name?.takeIf { it in uiState.value.variantNames }
    }

    fun selectModel(providerId: String, modelId: String) {
        _selectedProviderId.value = providerId
        _selectedModelId.value = modelId
        _selectedVariant.value = null
        isModelExplicitlySelected = true
    }

    // ============ @ File Mention Search ============

    /** File search results for @-autocomplete */
    private val _fileSearchResults = MutableStateFlow<List<String>>(emptyList())
    val fileSearchResults: StateFlow<List<String>> = _fileSearchResults

    /** Debounce job for file search */
    private var fileSearchJob: Job? = null

    /** Search files and directories for @-mention autocomplete. Debounced by 200ms. */
    fun searchFilesForMention(query: String) {
        if (!isServerConnected()) return
        fileSearchJob?.cancel()
        if (query.isEmpty()) {
            // Show recent/top files immediately with no debounce
            fileSearchJob = viewModelScope.launch {
                try {
                    val results = api.findFiles(
                        conn = conn,
                        query = "",
                        dirs = "true",
                        directory = sessionDirectory,
                        limit = 15
                    )
                    _fileSearchResults.value = results
                } catch (e: Exception) {
                    Log.e(TAG, "File search failed", e)
                    _fileSearchResults.value = emptyList()
                }
            }
            return
        }
        fileSearchJob = viewModelScope.launch {
            delay(150) // debounce
            try {
                val results = api.findFiles(
                    conn = conn,
                    query = query,
                    dirs = "true",
                    directory = sessionDirectory,
                    limit = 15
                )
                _fileSearchResults.value = results
            } catch (e: Exception) {
                Log.e(TAG, "File search failed", e)
                _fileSearchResults.value = emptyList()
            }
        }
    }

    /** Add a confirmed file path (user selected it from the popup) */
    fun confirmFilePath(path: String) {
        _confirmedFilePaths.value = _confirmedFilePaths.value + path
    }

    /** Remove a confirmed file path */
    fun removeFilePath(path: String) {
        _confirmedFilePaths.value = _confirmedFilePaths.value - path
    }

    /** Clear file search results (e.g. when popup is closed) */
    fun clearFileSearch() {
        fileSearchJob?.cancel()
        _fileSearchResults.value = emptyList()
    }

    /** Clear confirmed file paths (e.g. after sending a message) */
    fun clearConfirmedPaths() {
        _confirmedFilePaths.value = emptySet()
    }

    // ============ Draft Management ============

    /** Update the draft text (called on every keystroke). */
    fun updateDraftText(text: String) {
        _draftText.value = text
    }

    /** Add an attachment URI to the draft. */
    fun addDraftAttachment(uri: String) {
        _draftAttachmentUris.value = _draftAttachmentUris.value + uri
    }

    /** Remove an attachment URI from the draft by index. */
    fun removeDraftAttachment(index: Int) {
        val current = _draftAttachmentUris.value.toMutableList()
        if (index in current.indices) {
            current.removeAt(index)
            _draftAttachmentUris.value = current
        }
    }

    /** Clear all draft state (called after sending a message). */
    fun clearDraft() {
        _draftText.value = ""
        _draftAttachmentUris.value = emptyList()
        draftRepository.clearDraft(sessionId)
    }

    /** Persist current draft to disk. */
    private fun saveDraft() {
        val agentPair = _selectedAgent.value
        val draft = dev.minios.ocremote.data.repository.Draft(
            text = _draftText.value,
            imageUris = _draftAttachmentUris.value,
            confirmedFilePaths = _confirmedFilePaths.value.toList(),
            selectedAgent = agentPair.first.takeIf { agentPair.second },
            selectedVariant = _selectedVariant.value
        )
        draftRepository.saveDraft(sessionId, draft)
    }

    override fun onCleared() {
        closeTerminalSession()
        super.onCleared()
        saveDraft()
    }

    /** Get the session directory for building file:// URLs */
    fun getSessionDirectory(): String? = sessionDirectory

    fun sendMessage(text: String, attachments: List<PromptPart> = emptyList()): Boolean {
        if (!isServerConnected()) return false
        if (text.isBlank() && attachments.isEmpty()) return false
        val parts = mutableListOf<PromptPart>()
        if (text.isNotBlank()) {
            parts.add(PromptPart(type = "text", text = text))
        }
        parts.addAll(attachments)
        return sendParts(parts)
    }

    /** Send pre-built prompt parts (used when @-file mentions need structured parts). */
    fun sendMessage(promptParts: List<PromptPart>, attachments: List<PromptPart>): Boolean {
        if (!isServerConnected()) return false
        val parts = promptParts + attachments
        if (parts.isEmpty()) return false
        return sendParts(parts)
    }

    private fun sendParts(parts: List<PromptPart>): Boolean {
        if (!sessionPromptable) return false
        if (_isSending.value) return false
        _isSending.value = true

        val model = if (_selectedProviderId.value != null && _selectedModelId.value != null) {
            ModelSelection(_selectedProviderId.value!!, _selectedModelId.value!!)
        } else {
            null
        }
        val messageId = MessageIdGenerator.next()
        val draftSnapshot = Draft(
            text = _draftText.value,
            imageUris = _draftAttachmentUris.value,
            confirmedFilePaths = _confirmedFilePaths.value.toList(),
            selectedAgent = _selectedAgent.value.first.takeIf { _selectedAgent.value.second },
            selectedVariant = _selectedVariant.value,
        )
        val pending = PendingPromptRecord(
            messageId = messageId,
            sessionId = sessionId,
            parts = parts,
            model = model,
            agent = uiState.value.selectedAgent,
            variant = _selectedVariant.value,
            directory = sessionDirectory,
            createdAt = System.currentTimeMillis(),
        )
        pendingPromptRepository.save(pending)
        _pendingPrompts.value = _pendingPrompts.value + pending

        viewModelScope.launch {
            try {
                api.promptAsync(
                    conn = conn,
                    sessionId = sessionId,
                    messageId = messageId,
                    parts = parts,
                    model = model,
                    agent = uiState.value.selectedAgent,
                    variant = _selectedVariant.value,
                    directory = sessionDirectory
                )
                eventReducer.updateSessionStatus(sessionId, SessionStatus.Busy)
                if (BuildConfig.DEBUG) Log.d(TAG, "Sent prompt to session $sessionId (${parts.size} parts)")
                reconcilePendingMessage(messageId)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send message", e)
                _error.value = e.message ?: "Failed to send message"
                val definiteHttpFailure = e is RuntimeException && e.message?.startsWith("prompt_async failed:") == true
                if (definiteHttpFailure) {
                    eventReducer.updateSessionStatus(sessionId, SessionStatus.Idle)
                    pendingPromptRepository.remove(messageId)
                    _pendingPrompts.value = _pendingPrompts.value.filterNot { it.messageId == messageId }
                    delay(50)
                    restoreDraftAfterFailedSend(draftSnapshot)
                } else {
                    reconcilePendingMessage(messageId)
                }
            } finally {
                _isSending.value = false
            }
        }
        return true
    }

    private suspend fun reconcilePendingMessage(messageId: String) {
        var latestMessages = emptyList<MessageWithParts>()
        for (delayMs in listOf(150L, 400L, 1_000L, 2_000L, 4_000L)) {
            delay(delayMs)
            if (_pendingPrompts.value.none { it.messageId == messageId }) return
            try {
                val messages = api.listMessages(conn, sessionId, limit = 20)
                latestMessages = messages
                eventReducer.mergeMessages(sessionId, messages)
                if (messages.any { it.info.id == messageId }) return
            } catch (e: Exception) {
                Log.e(TAG, "Failed to reconcile pending message $messageId", e)
            }
        }
        if (latestMessages.isNotEmpty()) {
            reconcilePendingPrompts(authoritative = latestMessages, minimumAgeMs = 0L)
        }
    }

    private fun reconcilePendingPrompts(authoritative: List<MessageWithParts>, minimumAgeMs: Long) {
        val staleIds = missingPendingPromptIds(
            pending = _pendingPrompts.value,
            authoritative = authoritative,
            now = System.currentTimeMillis(),
            minimumAgeMs = minimumAgeMs,
        )
        if (staleIds.isEmpty()) return
        val stale = _pendingPrompts.value.filter { it.messageId in staleIds }
        stale.forEach { pendingPromptRepository.remove(it.messageId) }
        _pendingPrompts.value = _pendingPrompts.value.filterNot { it.messageId in staleIds }
        stale.firstOrNull()?.let(::restoreMissingPendingPrompt)
        Log.w(TAG, "Removed ${stale.size} unconfirmed pending prompt(s) for session $sessionId")
    }

    private fun restoreMissingPendingPrompt(pending: PendingPromptRecord) {
        if (_draftText.value.isNotBlank() || _draftAttachmentUris.value.isNotEmpty()) return
        val text = pending.parts
            .filter { it.type == "text" }
            .mapNotNull(PromptPart::text)
            .filter(String::isNotBlank)
            .joinToString("\n")
        val imageUris = pending.parts
            .filter { it.type == "file" }
            .mapNotNull(PromptPart::url)
        val draft = Draft(
            text = text,
            imageUris = imageUris,
            selectedAgent = pending.agent,
            selectedVariant = pending.variant,
        )
        _draftText.value = draft.text
        _draftAttachmentUris.value = draft.imageUris
        draftRepository.saveDraft(sessionId, draft)
        _revertedDraftEvent.tryEmit(RevertedDraftPayload(draft.text, draft.imageUris))
    }

    private fun restoreDraftAfterFailedSend(snapshot: Draft) {
        if (_draftText.value.isNotBlank() || _draftAttachmentUris.value.isNotEmpty()) return
        _draftText.value = snapshot.text
        _draftAttachmentUris.value = snapshot.imageUris
        _confirmedFilePaths.value = snapshot.confirmedFilePaths.toSet()
        draftRepository.saveDraft(sessionId, snapshot)
        _revertedDraftEvent.tryEmit(RevertedDraftPayload(snapshot.text, snapshot.imageUris))
    }

    /**
     * Reply to a permission request.
     * @param requestId The permission request ID
     * @param reply One of: "once", "always", "reject"
     */
    fun replyToPermission(
        requestSessionId: String,
        requestId: String,
        reply: String,
        onResult: (Boolean) -> Unit = {},
    ) {
        if (!isServerConnected()) {
            onResult(false)
            return
        }
        viewModelScope.launch {
            try {
                val success = api.replyToPermission(
                    conn = conn,
                    requestId = requestId,
                    reply = reply,
                    directory = requestDirectory(requestSessionId),
                )
                if (success) eventReducer.removePermission(requestSessionId, requestId)
                onResult(success)
                if (BuildConfig.DEBUG) Log.d(TAG, "Replied to permission $requestId with $reply: $success")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to reply to permission", e)
                onResult(false)
            }
        }
    }

    fun abortSession() {
        if (!isServerConnected()) return
        viewModelScope.launch {
            try {
                api.abortSession(conn, sessionId, directory = sessionDirectory)
                if (BuildConfig.DEBUG) Log.d(TAG, "Aborted session $sessionId")
                // Optimistically update session status to Idle so UI reflects change immediately
                eventReducer.updateSessionStatus(sessionId, SessionStatus.Idle)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to abort session", e)
            }
        }
    }

    /**
     * Reply to a question request.
     * @param requestId The question request ID
     * @param answers Answers for each question (list of selected labels per question)
     */
    fun replyToQuestion(
        requestSessionId: String,
        requestId: String,
        answers: List<List<String>>,
        onResult: (Boolean) -> Unit = {},
    ) {
        if (!isServerConnected()) {
            onResult(false)
            return
        }
        viewModelScope.launch {
            try {
                val success = api.replyToQuestion(
                    conn = conn,
                    requestId = requestId,
                    answers = answers,
                    directory = requestDirectory(requestSessionId),
                )
                if (success) {
                    // Optimistically remove the question card — SSE event may arrive late or not at all
                    eventReducer.removeQuestion(requestSessionId, requestId)
                }
                onResult(success)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to reply to question $requestId: ${e.javaClass.simpleName}: ${e.message}", e)
                onResult(false)
            }
        }
    }

    /**
     * Reject a question request.
     */
    fun rejectQuestion(
        requestSessionId: String,
        requestId: String,
        onResult: (Boolean) -> Unit = {},
    ) {
        if (!isServerConnected()) {
            onResult(false)
            return
        }
        viewModelScope.launch {
            try {
                val success = api.rejectQuestion(
                    conn = conn,
                    requestId = requestId,
                    directory = requestDirectory(requestSessionId),
                )
                if (success) {
                    // Optimistically remove the question card
                    eventReducer.removeQuestion(requestSessionId, requestId)
                }
                onResult(success)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to reject question $requestId: ${e.javaClass.simpleName}: ${e.message}", e)
                onResult(false)
            }
        }
    }

    private fun requestDirectory(requestSessionId: String): String? =
        eventReducer.sessions.value.firstOrNull { it.id == requestSessionId }
            ?.directory
            ?.takeIf { it.isNotBlank() }
            ?: sessionDirectory

    // ============ Slash Command Actions ============

    /** Share the current session. Returns the share URL or null on failure. */
    fun shareSession(onResult: (String?) -> Unit) {
        if (!isServerConnected()) {
            onResult(null)
            return
        }
        viewModelScope.launch {
            try {
                val session = api.shareSession(conn, sessionId)
                val url = session.share?.url
                if (BuildConfig.DEBUG) Log.d(TAG, "Session share completed")
                onResult(url)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to share session", e)
                onResult(null)
            }
        }
    }

    fun unshareSession(onResult: (Boolean) -> Unit) {
        if (!isServerConnected()) {
            onResult(false)
            return
        }
        viewModelScope.launch {
            try {
                api.unshareSession(conn, sessionId)
                if (BuildConfig.DEBUG) Log.d(TAG, "Unshared session $sessionId")
                onResult(true)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unshare session", e)
                onResult(false)
            }
        }
    }

    /** Compact (summarize) the current session. */
    fun compactSession(onResult: (Boolean) -> Unit) {
        if (!isServerConnected()) {
            onResult(false)
            return
        }
        viewModelScope.launch {
            try {
                val state = uiState.value
                val providerId = state.selectedProviderId
                val modelId = state.selectedModelId
                if (providerId == null || modelId == null) {
                    Log.e(TAG, "Cannot compact: no model selected")
                    onResult(false)
                    return@launch
                }
                api.summarizeSession(conn, sessionId, providerId, modelId)
                if (BuildConfig.DEBUG) Log.d(TAG, "Compacted session $sessionId")
                onResult(true)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to compact session", e)
                onResult(false)
            }
        }
    }

    /**
     * Export the session as JSON directly to a file URI.
     * Streams API responses directly to the output stream to avoid OOM
     * on large sessions (messages can be 80+ MB).
     * Shows a notification with download progress.
     */
    fun exportSession(context: android.content.Context, uri: android.net.Uri, onResult: (Boolean) -> Unit) {
        if (!isServerConnected()) {
            onResult(false)
            return
        }
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val notificationManager = context.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            val channelId = "opencode_export"
            val notificationId = 9999

            // Create notification channel
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val channel = android.app.NotificationChannel(
                    channelId,
                    context.getString(R.string.menu_export_session),
                    android.app.NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = context.getString(R.string.notification_export_progress)
                    setShowBadge(false)
                }
                notificationManager.createNotificationChannel(channel)
            }

            val builder = androidx.core.app.NotificationCompat.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(context.getString(R.string.menu_export_session))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(0, 0, true)

            try {
                Log.d(TAG, "exportSession: streaming to $uri")
                notificationManager.notify(notificationId, builder.build())

                var lastNotifyTime = 0L
                context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                    api.exportSessionToStream(conn, sessionId, outputStream) { bytesWritten ->
                        val now = System.currentTimeMillis()
                        if (now - lastNotifyTime > 500) { // throttle to 2 updates/sec
                            lastNotifyTime = now
                            val mb = String.format("%.1f MB", bytesWritten / 1_000_000.0)
                            builder.setContentText(mb)
                            notificationManager.notify(notificationId, builder.build())
                        }
                    }
                }

                Log.d(TAG, "exportSession: done")
                notificationManager.cancel(notificationId)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    onResult(true)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to export session", e)
                notificationManager.cancel(notificationId)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    onResult(false)
                }
            }
        }
    }

    /** Undo the last user message in the session, restoring its text to the input field. */
    fun undoMessage(onResult: (Boolean) -> Unit) {
        if (!isServerConnected()) {
            onResult(false)
            return
        }
        viewModelScope.launch {
            try {
                // Find the last user message (before any existing revert point)
                val messages = uiState.value.messages
                val lastUser = messages.lastOrNull { it.isUser }
                if (lastUser == null) {
                    onResult(false)
                    return@launch
                }
                val revertedSession = api.revertSession(conn, sessionId, lastUser.message.id)
                eventReducer.upsertSession(serverId, revertedSession)
                pendingPromptRepository.remove(lastUser.message.id)
                _pendingPrompts.value = _pendingPrompts.value.filterNot { it.messageId == lastUser.message.id }
                if (BuildConfig.DEBUG) Log.d(TAG, "Reverted session $sessionId to message ${lastUser.message.id}")
                // Restore the user message text to the input field
                restoreRevertedDraft(extractRevertedDraft(lastUser))
                onResult(true)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to revert session", e)
                onResult(false)
            }
        }
    }

    /** Revert to a specific user message by ID, optionally restoring its text to the input field. */
    fun revertMessage(messageId: String, revertedText: String? = null, onResult: (Boolean) -> Unit) {
        if (!isServerConnected()) {
            onResult(false)
            return
        }
        viewModelScope.launch {
            try {
                val revertedSession = api.revertSession(conn, sessionId, messageId)
                eventReducer.upsertSession(serverId, revertedSession)
                pendingPromptRepository.remove(messageId)
                _pendingPrompts.value = _pendingPrompts.value.filterNot { it.messageId == messageId }
                if (BuildConfig.DEBUG) Log.d(TAG, "Reverted session $sessionId to message $messageId")
                val targetMessage = uiState.value.messages
                    .lastOrNull { it.message.id == messageId && it.isUser }
                val fallbackPayload = RevertedDraftPayload(text = revertedText.orEmpty())
                restoreRevertedDraft(targetMessage?.let { extractRevertedDraft(it) } ?: fallbackPayload)
                onResult(true)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to revert to message $messageId", e)
                onResult(false)
            }
        }
    }

    private fun extractRevertedDraft(message: ChatMessage): RevertedDraftPayload {
        val revertedText = message.parts
            .filterIsInstance<Part.Text>()
            .joinToString("\n") { it.text }

        val imageUris = message.parts
            .filterIsInstance<Part.File>()
            .mapNotNull { part ->
                val mime = part.mime.lowercase()
                if (mime.startsWith("image/") && !part.url.isNullOrBlank()) part.url else null
            }

        return RevertedDraftPayload(
            text = revertedText,
            attachmentUris = imageUris,
        )
    }

    private fun restoreRevertedDraft(payload: RevertedDraftPayload) {
        _draftText.value = payload.text
        _draftAttachmentUris.value = payload.attachmentUris
        _confirmedFilePaths.value = emptySet()
        _revertedDraftEvent.tryEmit(payload)
    }

    /** Redo the last undone message. */
    fun redoMessage(onResult: (Boolean) -> Unit) {
        if (!isServerConnected()) {
            onResult(false)
            return
        }
        viewModelScope.launch {
            try {
                api.unrevertSession(conn, sessionId)
                if (BuildConfig.DEBUG) Log.d(TAG, "Unreverted session $sessionId")
                onResult(true)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unrevert session", e)
                onResult(false)
            }
        }
    }

    /** Fork the current session. Returns the new session or null. */
    fun forkSession(onResult: (Session?) -> Unit) {
        if (!isServerConnected()) {
            onResult(null)
            return
        }
        viewModelScope.launch {
            try {
                val session = api.forkSession(conn, sessionId)
                if (BuildConfig.DEBUG) Log.d(TAG, "Forked session $sessionId -> ${session.id}")
                onResult(session)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fork session", e)
                onResult(null)
            }
        }
    }

    /** Rename the current session. */
    fun renameSession(title: String, onResult: (Boolean) -> Unit) {
        if (!isServerConnected()) {
            onResult(false)
            return
        }
        viewModelScope.launch {
            try {
                api.updateSession(conn, sessionId, title)
                if (BuildConfig.DEBUG) Log.d(TAG, "Renamed session $sessionId to $title")
                onResult(true)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to rename session", e)
                onResult(false)
            }
        }
    }

    /** Execute a server-side command (e.g. /init, /review, MCP commands). */
    fun executeCommand(command: String, arguments: String = "", onResult: (Boolean) -> Unit) {
        if (!isServerConnected()) {
            onResult(false)
            return
        }
        viewModelScope.launch {
            try {
                if (!sessionLoaded.isCompleted) {
                    sessionLoaded.await()
                }
                if (sessionDirectory.isNullOrBlank()) {
                    loadSession()
                }

                val normalizedCommand = command.removePrefix("/").trim()
                val effectiveDirectory = sessionDirectory
                    ?: eventReducer.sessions.value
                        .firstOrNull { it.id == sessionId }
                        ?.directory
                        ?.takeIf { it.isNotBlank() }
                // /init: when arguments are omitted, rely on x-opencode-directory only.
                // Passing an explicit path (absolute or ".") can lead to duplicated or
                // malformed path text in the generated init prompt.
                val effectiveArguments = if (
                    normalizedCommand.equals("init", ignoreCase = true) && arguments.isBlank()
                ) {
                    ""
                } else {
                    arguments
                }

                val ok = api.executeCommand(
                    conn = conn,
                    sessionId = sessionId,
                    command = normalizedCommand,
                    arguments = effectiveArguments,
                    directory = effectiveDirectory
                )
                if (BuildConfig.DEBUG) {
                    Log.d(
                        TAG,
                        "Executed command /$normalizedCommand in session $sessionId: $ok (directory=$effectiveDirectory, arguments=$effectiveArguments)"
                    )
                }
                onResult(ok)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to execute command", e)
                onResult(false)
            }
        }
    }

    /** Execute shell command in current session. */
    fun runShellCommand(command: String, onResult: (Boolean) -> Unit) {
        if (!isServerConnected()) {
            onResult(false)
            return
        }
        if (!sessionPromptable) {
            onResult(false)
            return
        }
        val trimmed = command.trim()
        if (trimmed.isBlank()) {
            onResult(false)
            return
        }
        viewModelScope.launch {
            try {
                val model = if (_selectedProviderId.value != null && _selectedModelId.value != null) {
                    ModelSelection(
                        providerId = _selectedProviderId.value!!,
                        modelId = _selectedModelId.value!!
                    )
                } else null
                val ok = api.runShellCommand(
                    conn = conn,
                    sessionId = sessionId,
                    command = trimmed,
                    agent = uiState.value.selectedAgent,
                    model = model,
                    directory = sessionDirectory
                )
                if (BuildConfig.DEBUG) Log.d(TAG, "Executed shell command in session $sessionId: $ok")
                onResult(ok)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to execute shell command", e)
                onResult(false)
            }
        }
    }

    fun openTerminalSession(onResult: (Boolean) -> Unit = {}) {
        if (!isServerConnected()) {
            onResult(false)
            return
        }
        viewModelScope.launch {
            // Wait for loadSession() to finish so sessionDirectory is populated.
            // This prevents the race condition where the PTY is created with directory=null
            // and then resize is attempted with the real directory.
            sessionLoaded.await()
            if (BuildConfig.DEBUG) Log.d(TAG, "openTerminalSession: sessionDirectory=$sessionDirectory")
            terminalWorkspace.ensureActiveTab(cwd = sessionDirectory, directory = sessionDirectory, onResult = onResult)
        }
    }

    fun createTerminalTab(onResult: (Boolean) -> Unit = {}) {
        if (!isServerConnected()) {
            onResult(false)
            return
        }
        viewModelScope.launch {
            sessionLoaded.await()
            terminalWorkspace.createTab(cwd = sessionDirectory, directory = sessionDirectory, onResult = onResult)
        }
    }

    fun switchTerminalTab(tabId: String) {
        terminalWorkspace.switchTab(tabId)
    }

    fun closeTerminalTab(tabId: String) {
        terminalWorkspace.closeTab(tabId)
    }

    fun recoverTerminalTab(tabId: String, onResult: (Boolean) -> Unit = {}) {
        if (!isServerConnected()) {
            onResult(false)
            return
        }
        terminalWorkspace.recoverTab(tabId, onResult)
    }

    fun setTerminalFontSize(fontSizeSp: Float) {
        terminalWorkspace.setActiveFontSize(fontSizeSp)
    }

    fun sendTerminalInput(input: String) {
        if (!isServerConnected()) return
        terminalWorkspace.sendActiveInput(input)
    }

    fun clearTerminalBuffer() {
        terminalWorkspace.clearActiveBuffer()
    }

    fun resizeTerminal(cols: Int, rows: Int) {
        if (!isServerConnected()) return
        terminalWorkspace.resizeActive(cols, rows)
    }

    fun closeTerminalSession() {
        // Global terminal workspaces are server-scoped and survive chat screen changes.
    }

    /** Create a new session and return it. */
    fun createNewSession(onResult: (Session?) -> Unit) {
        if (!isServerConnected()) {
            onResult(null)
            return
        }
        viewModelScope.launch {
            try {
                val session = api.createSession(conn, directory = sessionDirectory)
                eventReducer.setSessions(serverId, listOf(session))
                if (BuildConfig.DEBUG) Log.d(TAG, "Created new session: ${session.id}")
                onResult(session)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create session", e)
                onResult(null)
            }
        }
    }

    /** Connection parameters for navigation to other sessions. */
    fun getConnectionParams(): ConnectionParams = ConnectionParams(
        serverUrl = serverUrl,
        username = username,
        password = password,
        serverName = serverName,
        serverId = serverId
    )

    /** Get the last assistant message text for copying. */
    fun getLastAssistantText(): String? {
        val msgs = uiState.value.messages
        val last = msgs.lastOrNull { it.isAssistant } ?: return null
        return last.parts
            .filterIsInstance<Part.Text>()
            .joinToString("") { it.text }
            .ifBlank { null }
    }
}

/** Holds server connection info for navigation purposes. */
data class ConnectionParams(
    val serverUrl: String,
    val username: String,
    val password: String,
    val serverName: String,
    val serverId: String
)
