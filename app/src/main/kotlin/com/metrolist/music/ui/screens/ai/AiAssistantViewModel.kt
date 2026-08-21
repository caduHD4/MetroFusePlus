/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.ai

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.music.ai.action.AiActionStatus
import com.metrolist.music.ai.action.AiPendingAction
import com.metrolist.music.ai.core.AiAgentEvent
import com.metrolist.music.ai.core.AiAgentRunner
import com.metrolist.music.ai.core.AiContextBuilder
import com.metrolist.music.ai.core.AiConversationWindow
import com.metrolist.music.ai.core.AiDataSanitizer
import com.metrolist.music.ai.core.AiAssistantPhase
import com.metrolist.music.ai.core.AiAssistantState
import com.metrolist.music.ai.core.AiError
import com.metrolist.music.ai.core.AiErrorType
import com.metrolist.music.ai.core.AiGroundingSource
import com.metrolist.music.ai.model.AiCapability
import com.metrolist.music.ai.model.AiConversationMessage
import com.metrolist.music.ai.model.AiPermissions
import com.metrolist.music.ai.model.AiProviderConfig
import com.metrolist.music.ai.model.AiQueueItemContext
import com.metrolist.music.ai.model.AiUiContext
import com.metrolist.music.ai.model.CurrentLyricsContext
import com.metrolist.music.ai.model.CurrentMusicContext
import com.metrolist.music.ai.playlist.AiPlaylistDraft
import com.metrolist.music.ai.playlist.AiPlaylistCurator
import com.metrolist.music.ai.playlist.AiPlaylistIntentType
import com.metrolist.music.ai.playlist.AiPlaylistRanker
import com.metrolist.music.ai.playlist.AiSessionArtifacts
import com.metrolist.music.ai.provider.AiProviderRegistry
import com.metrolist.music.ai.repository.AiModelRepository
import com.metrolist.music.ai.repository.AiMusicCatalogRepository
import com.metrolist.music.ai.repository.AiPlaylistRepository
import com.metrolist.music.ai.repository.AiLibraryPlaylist
import com.metrolist.music.ai.security.AiSecretAliases
import com.metrolist.music.ai.security.AiSecretStore
import com.metrolist.music.ai.tools.AiToolPresentation
import com.metrolist.music.R
import com.metrolist.music.constants.AiAssistantBaseUrlKey
import com.metrolist.music.constants.AiAssistantCurrentSongPermissionKey
import com.metrolist.music.constants.AiAssistantHistoryPermissionKey
import com.metrolist.music.constants.AiAssistantLikedSongsPermissionKey
import com.metrolist.music.constants.AiAssistantLibraryPermissionKey
import com.metrolist.music.constants.AiAssistantLyricsPermissionKey
import com.metrolist.music.constants.AiAssistantMaxToolCallsKey
import com.metrolist.music.constants.AiAssistantModelKey
import com.metrolist.music.constants.AiAssistantPlaylistsPermissionKey
import com.metrolist.music.constants.AiAssistantProviderKey
import com.metrolist.music.constants.AiAssistantQueuePermissionKey
import com.metrolist.music.constants.AiAssistantSystemPromptKey
import com.metrolist.music.constants.AiAssistantWebSearchKey
import com.metrolist.music.constants.DEFAULT_AI_ASSISTANT_SYSTEM_PROMPT
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.get
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class AiAssistantViewModel
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val agentRunner: AiAgentRunner,
    private val modelRepository: AiModelRepository,
    private val providerRegistry: AiProviderRegistry,
    private val secretStore: AiSecretStore,
    private val playlistRepository: AiPlaylistRepository,
    private val musicCatalogRepository: AiMusicCatalogRepository,
    private val playlistCurator: AiPlaylistCurator,
    private val playlistRanker: AiPlaylistRanker,
    private val contextBuilder: AiContextBuilder,
    private val dataSanitizer: AiDataSanitizer,
) : ViewModel() {
    private val conversation = mutableListOf<AiConversationMessage>()
    private val artifacts = AiSessionArtifacts()
    private val _uiState = MutableStateFlow(AiAssistantUiState())
    val uiState = _uiState.asStateFlow()
    private var activeJob: Job? = null
    private var sessionProviderConfig: AiProviderConfig? = null

    fun sendMessage(
        text: String,
        currentMusic: CurrentMusicContext?,
        currentSongItem: SongItem? = null,
        queue: List<AiQueueItemContext> = emptyList(),
        queueTotal: Int = queue.size,
        lyrics: CurrentLyricsContext? = null,
        uiContext: AiUiContext? = null,
    ) {
        val message = dataSanitizer.userMessage(text)
        if (message.isBlank() || activeJob?.isActive == true) return
        currentSongItem?.let { artifacts.rememberSongs(listOf(it)) }

        val userItem = AiChatItem.UserText(UUID.randomUUID().toString(), message)
        val initialAssistantId = UUID.randomUUID().toString()
        _uiState.update {
            it.copy(
                items = it.items + userItem + AiChatItem.AssistantText(initialAssistantId, ""),
                execution = AiAssistantState(AiAssistantPhase.THINKING, canCancel = true),
            )
        }
        conversation += AiConversationMessage.User(message)

        activeJob =
            viewModelScope.launch {
                runCatching {
                    val providerId = context.dataStore.get(AiAssistantProviderKey, "openrouter")
                    val descriptor = providerRegistry.descriptor(providerId)
                        ?: error("Unknown AI provider: $providerId")
                    val configuredModel = context.dataStore.get(AiAssistantModelKey, "")
                    val baseUrl = context.dataStore.get(AiAssistantBaseUrlKey, "")
                    val key = withContext(Dispatchers.IO) {
                        secretStore.get(AiSecretAliases.assistant(providerId)).orEmpty()
                    }
                    if (descriptor.requiresApiKey && key.isBlank()) {
                        throw MissingAiKeyException()
                    }
                    val provisionalConfig =
                        AiProviderConfig(
                            providerId = providerId,
                            apiKey = key,
                            baseUrl = baseUrl.takeIf(String::isNotBlank),
                            modelId = configuredModel.ifBlank { descriptor.fallbackModelIds.firstOrNull().orEmpty() },
                        )
                    val catalog = withContext(Dispatchers.IO) { modelRepository.models(provisionalConfig) }
                    val selectedModel =
                        if (configuredModel.isNotBlank()) {
                            catalog.models.firstOrNull { it.id == configuredModel }
                                ?: com.metrolist.music.ai.model.AiModel(
                                    id = configuredModel,
                                    displayName = configuredModel,
                                    providerId = descriptor.id,
                                    capabilities = setOf(AiCapability.TEXT, AiCapability.STREAMING),
                                    metadata = mapOf("source" to "manual_selection"),
                                )
                        } else {
                            descriptor.fallbackModelIds
                                .firstNotNullOfOrNull { preferredId ->
                                    catalog.models.firstOrNull { model ->
                                        model.id == preferredId && AiCapability.TOOLS in model.capabilities
                                    }
                                }
                                ?: catalog.models.firstOrNull { AiCapability.TOOLS in it.capabilities }
                                ?: catalog.models.firstOrNull()
                                ?: error(context.getString(R.string.ai_no_compatible_model, descriptor.displayName))
                        }
                    if (configuredModel.isBlank()) {
                        context.dataStore.edit { it[AiAssistantModelKey] = selectedModel.id }
                    }
                    val config = provisionalConfig.copy(modelId = selectedModel.id)
                    sessionProviderConfig = config
                    val customInstructions =
                        dataSanitizer.customInstructions(
                            context.dataStore.get(AiAssistantSystemPromptKey, ""),
                        )
                    val systemPrompt =
                        buildString {
                            append(DEFAULT_AI_ASSISTANT_SYSTEM_PROMPT)
                            if (customInstructions.isNotBlank()) {
                                append("\n\nAdditional user instructions:\n")
                                append(customInstructions)
                            }
                        }
                    val maxToolCalls = context.dataStore.get(AiAssistantMaxToolCallsKey, AiAgentRunner.DEFAULT_MAX_TOOL_CALLS)
                    val permissions =
                        AiPermissions(
                            currentSong = context.dataStore.get(AiAssistantCurrentSongPermissionKey, true),
                            queue = context.dataStore.get(AiAssistantQueuePermissionKey, false),
                            library = context.dataStore.get(AiAssistantLibraryPermissionKey, false),
                            lyrics = context.dataStore.get(AiAssistantLyricsPermissionKey, false),
                            likedSongs = context.dataStore.get(AiAssistantLikedSongsPermissionKey, false),
                            playlists = context.dataStore.get(AiAssistantPlaylistsPermissionKey, false),
                            history = context.dataStore.get(AiAssistantHistoryPermissionKey, false),
                        )
                    var assistantItemId = initialAssistantId
                    var startsNewAssistantMessage = false
                    var pendingDelta = StringBuilder()
                    var lastUiFlushNanos = System.nanoTime()

                    suspend fun flushText(force: Boolean = false) {
                        if (pendingDelta.isEmpty()) return
                        val now = System.nanoTime()
                        if (!force && now - lastUiFlushNanos < UI_DELTA_BATCH_NANOS) return
                        val delta = pendingDelta.toString()
                        pendingDelta = StringBuilder()
                        lastUiFlushNanos = now
                        appendAssistantText(assistantItemId, delta)
                    }

                    agentRunner.runTurn(
                        config = config,
                        systemPrompt = systemPrompt,
                        conversation = conversation,
                        toolContext =
                            contextBuilder.build(
                                permissions = permissions,
                                currentMusic = currentMusic,
                                queue = queue,
                                queueTotal = queueTotal,
                                lyrics = lyrics,
                                uiContext = uiContext,
                                artifacts = artifacts,
                            ),
                        toolsEnabled = AiCapability.TOOLS in selectedModel.capabilities,
                        webGroundingEnabled = context.dataStore.get(AiAssistantWebSearchKey, false),
                        maxToolCalls = maxToolCalls,
                    ) { event ->
                        when (event) {
                            is AiAgentEvent.TextDelta -> {
                                if (startsNewAssistantMessage) {
                                    assistantItemId = UUID.randomUUID().toString()
                                    _uiState.update {
                                        it.copy(items = it.items + AiChatItem.AssistantText(assistantItemId, ""))
                                    }
                                    startsNewAssistantMessage = false
                                }
                                pendingDelta.append(event.text)
                                flushText()
                            }
                            is AiAgentEvent.State -> {
                                _uiState.update {
                                    it.copy(
                                        execution =
                                            AiAssistantState(
                                                phase = event.phase,
                                                status = phaseStatus(event.phase),
                                                canCancel = true,
                                            ),
                                    )
                                }
                            }
                            is AiAgentEvent.Status ->
                                _uiState.update { it.copy(execution = it.execution.copy(status = event.text)) }
                            is AiAgentEvent.ToolStarted ->
                                _uiState.update {
                                    it.copy(
                                        items =
                                            it.items.filterNot { item -> item is AiChatItem.Progress && item.toolCallId == event.id } +
                                                AiChatItem.Progress(
                                                    id = "progress_${event.id}",
                                                    toolCallId = event.id,
                                                    label = toolStatus(event.name),
                                                ),
                                        execution =
                                            it.execution.copy(
                                                phase = toolPhase(event.name),
                                                status = toolStatus(event.name),
                                            ),
                                    )
                                }
                            is AiAgentEvent.ToolFinished -> {
                                flushText(force = true)
                                _uiState.update { state ->
                                    state.copy(
                                        items =
                                            state.items.map { item ->
                                                if (item is AiChatItem.Progress && item.toolCallId == event.execution.call.id) {
                                                    item.copy(
                                                        completed = event.execution.result is com.metrolist.music.ai.tools.AiToolResult.Success,
                                                        failed = event.execution.result is com.metrolist.music.ai.tools.AiToolResult.Failure,
                                                    )
                                                } else {
                                                    item
                                                }
                                            },
                                    )
                                }
                                when (val presentation =
                                    (event.execution.result as? com.metrolist.music.ai.tools.AiToolResult.Success)
                                        ?.presentation
                                ) {
                                    is AiToolPresentation.Songs -> {
                                        _uiState.update {
                                            it.copy(
                                                items =
                                                    it.items +
                                                        AiChatItem.SongResults(
                                                            UUID.randomUUID().toString(),
                                                            presentation.items,
                                                        ),
                                            )
                                        }
                                    }
                                    is AiToolPresentation.PlaylistDraft -> {
                                        _uiState.update {
                                            it.copy(
                                                items =
                                                    it.items +
                                                        AiChatItem.PlaylistDraft(
                                                            UUID.randomUUID().toString(),
                                                            presentation.draft,
                                                        ),
                                            )
                                        }
                                    }
                                    is AiToolPresentation.Albums -> {
                                        _uiState.update {
                                            it.copy(
                                                items =
                                                    it.items +
                                                        AiChatItem.AlbumResults(
                                                            UUID.randomUUID().toString(),
                                                            presentation.items,
                                                        ),
                                            )
                                        }
                                    }
                                    is AiToolPresentation.Artists -> {
                                        _uiState.update {
                                            it.copy(
                                                items =
                                                    it.items +
                                                        AiChatItem.ArtistResults(
                                                            UUID.randomUUID().toString(),
                                                            presentation.items,
                                                        ),
                                            )
                                        }
                                    }
                                    is AiToolPresentation.Playlists -> {
                                        _uiState.update {
                                            it.copy(
                                                items =
                                                    it.items +
                                                        AiChatItem.PlaylistResults(
                                                            UUID.randomUUID().toString(),
                                                            presentation.items,
                                                        ),
                                            )
                                        }
                                    }
                                    is AiToolPresentation.Confirmation -> {
                                        _uiState.update {
                                            val item =
                                                if (
                                                    presentation.action is AiPendingAction.CreatePlaylistDraft ||
                                                    presentation.action is AiPendingAction.BuildPlaylistDraft ||
                                                    presentation.action is AiPendingAction.UpdatePlaylistDraft
                                                ) {
                                                    AiChatItem.Plan(
                                                        id = UUID.randomUUID().toString(),
                                                        action = presentation.action,
                                                    )
                                                } else {
                                                    AiChatItem.Confirmation(
                                                        id = UUID.randomUUID().toString(),
                                                        action = presentation.action,
                                                    )
                                                }
                                            it.copy(
                                                items = it.items + item,
                                            )
                                        }
                                    }
                                    null -> Unit
                                }
                                startsNewAssistantMessage = true
                            }
                            is AiAgentEvent.RetryScheduled ->
                                _uiState.update {
                                    it.copy(
                                        execution =
                                            it.execution.copy(
                                                status =
                                                    context.getString(
                                                        R.string.ai_status_retrying_provider,
                                                        event.attempt,
                                                        event.delaySeconds,
                                                    ),
                                            ),
                                    )
                                }
                            is AiAgentEvent.Grounding -> {
                                flushText(force = true)
                                if (event.metadata.sources.isNotEmpty()) {
                                    _uiState.update {
                                        it.copy(
                                            items =
                                                it.items +
                                                    AiChatItem.GroundingSources(
                                                        UUID.randomUUID().toString(),
                                                        event.metadata.sources,
                                                    ),
                                        )
                                    }
                                }
                            }
                            is AiAgentEvent.Error -> {
                                flushText(force = true)
                                addError(event.error)
                            }
                            AiAgentEvent.Completed -> {
                                flushText(force = true)
                                AiConversationWindow.compactInPlace(conversation)
                                _uiState.update {
                                    it.copy(
                                        items = it.items.filterNot { item ->
                                            item is AiChatItem.AssistantText && item.text.isBlank()
                                        },
                                        execution =
                                            AiAssistantState(
                                                if (artifacts.hasPendingActions()) {
                                                    AiAssistantPhase.WAITING_CONFIRMATION
                                                } else {
                                                    AiAssistantPhase.COMPLETED
                                                },
                                            ),
                                    )
                                }
                            }
                        }
                    }
                }.onFailure { error ->
                    if (error is kotlinx.coroutines.CancellationException) return@onFailure
                    addError(
                        when (error) {
                            is MissingAiKeyException ->
                                AiError(AiErrorType.INVALID_API_KEY, context.getString(R.string.ai_missing_api_key))
                            else ->
                                AiError(
                                    AiErrorType.UNKNOWN,
                                    error.message ?: context.getString(R.string.ai_assistant_failed),
                                    cause = error,
                                )
                        },
                    )
                }
            }
    }

    fun cancel() {
        activeJob?.cancel()
        activeJob = null
        _uiState.update {
            it.copy(
                items =
                    it.items.filterNot { item ->
                        (item is AiChatItem.AssistantText && item.text.isBlank()) ||
                            (item is AiChatItem.Progress && !item.completed && !item.failed)
                    },
                execution = AiAssistantState(AiAssistantPhase.CANCELLED),
            )
        }
    }

    fun clearConversation() {
        cancel()
        conversation.clear()
        artifacts.clear()
        sessionProviderConfig = null
        _uiState.value = AiAssistantUiState()
    }

    fun saveDraft(draftId: String) {
        if (draftId in _uiState.value.savingDraftIds) return
        val draft = artifacts.draft(draftId) ?: return
        if (draft.savedPlaylistId != null) return
        _uiState.update { it.copy(savingDraftIds = it.savingDraftIds + draftId) }
        viewModelScope.launch {
            playlistRepository.saveDraft(draft).fold(
                onSuccess = { playlistId ->
                    val saved = artifacts.markSaved(draftId, playlistId) ?: return@fold
                    _uiState.update { state ->
                        state.copy(
                            items =
                                state.items.map { item ->
                                    if (item is AiChatItem.PlaylistDraft && item.draft.id == draftId) {
                                        item.copy(draft = saved)
                                    } else {
                                        item
                                    }
                                },
                            savingDraftIds = state.savingDraftIds - draftId,
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update { it.copy(savingDraftIds = it.savingDraftIds - draftId) }
                    addError(
                        AiError(
                            AiErrorType.TOOL_EXECUTION_FAILED,
                            error.message ?: context.getString(R.string.ai_playlist_save_failed),
                            cause = error,
                        ),
                    )
                },
            )
        }
    }

    fun pendingAction(actionId: String): AiPendingAction? = artifacts.pendingAction(actionId)

    fun confirmDraftAction(actionId: String) {
        val action = artifacts.pendingAction(actionId)
        if (action is AiPendingAction.BuildPlaylistDraft) {
            buildPlaylistDraft(action)
            return
        }
        val draft =
            when (action) {
                is AiPendingAction.CreatePlaylistDraft -> artifacts.confirmPlaylistDraftAction(actionId)
                is AiPendingAction.UpdatePlaylistDraft -> artifacts.confirmUpdateDraftAction(actionId)
                else -> null
            }
        if (draft == null) {
            resolveAction(actionId, AiActionStatus.FAILED)
            return
        }
        _uiState.update { state ->
            state.copy(
                items =
                    state.items.map { item ->
                        when {
                            item is AiChatItem.Confirmation && item.action.id == actionId ->
                                item.copy(status = AiActionStatus.COMPLETED)
                            item is AiChatItem.Plan && item.action.id == actionId ->
                                item.copy(status = AiActionStatus.COMPLETED)
                            item is AiChatItem.PlaylistDraft && item.draft.id == draft.id ->
                                item.copy(draft = draft)
                            else -> item
                        }
                    }.let { items ->
                        if (items.any { it is AiChatItem.PlaylistDraft && it.draft.id == draft.id }) {
                            items
                        } else {
                            items + AiChatItem.PlaylistDraft(UUID.randomUUID().toString(), draft)
                        }
                    },
                execution =
                    AiAssistantState(
                        if (artifacts.hasPendingActions()) {
                            AiAssistantPhase.WAITING_CONFIRMATION
                        } else {
                            AiAssistantPhase.COMPLETED
                        },
                    ),
            )
        }
    }

    private fun buildPlaylistDraft(action: AiPendingAction.BuildPlaylistDraft) {
        if (activeJob?.isActive == true) return
        val progressId = "build_${action.id}"
        _uiState.update { state ->
            state.copy(
                items =
                    state.items.filterNot { item -> item is AiChatItem.Progress && item.id == progressId } +
                        AiChatItem.Progress(
                            id = progressId,
                            toolCallId = progressId,
                            label = context.getString(R.string.ai_status_building_playlist),
                            current = 0,
                            total = action.queries.size.coerceAtLeast(1),
                        ),
                execution = AiAssistantState(AiAssistantPhase.BUILDING_PLAYLIST, canCancel = true),
            )
        }
        activeJob =
            viewModelScope.launch {
                runCatching {
                    generatePlaylistDraft(action, progressId)
                }.fold(
                    onSuccess = { draft ->
                        artifacts.removePendingAction(action.id)
                        _uiState.update { state ->
                            state.copy(
                                items =
                                    state.items.map { item ->
                                        when {
                                            item is AiChatItem.Plan && item.action.id == action.id ->
                                                item.copy(status = AiActionStatus.COMPLETED)
                                            item is AiChatItem.Progress && item.id == progressId ->
                                                item.copy(completed = true, current = item.total)
                                            else -> item
                                        }
                                    } + AiChatItem.PlaylistDraft(UUID.randomUUID().toString(), draft),
                                execution = AiAssistantState(AiAssistantPhase.COMPLETED),
                            )
                        }
                    },
                    onFailure = { error ->
                        if (error is kotlinx.coroutines.CancellationException) return@fold
                        _uiState.update { state ->
                            state.copy(
                                items = state.items.map { item ->
                                    if (item is AiChatItem.Progress && item.id == progressId) {
                                        item.copy(failed = true)
                                    } else item
                                },
                            )
                        }
                        resolveAction(action.id, AiActionStatus.FAILED, error.message)
                        addError(AiError(AiErrorType.TOOL_EXECUTION_FAILED, error.message ?: context.getString(R.string.ai_assistant_failed)))
                    },
                )
            }
    }

    private suspend fun generatePlaylistDraft(
        action: AiPendingAction.BuildPlaylistDraft,
        progressId: String,
    ): AiPlaylistDraft {
        val config = sessionProviderConfig ?: error(context.getString(R.string.ai_playlist_session_expired))
        if (action.intent.type == AiPlaylistIntentType.ARTIST) {
            val artistName = action.intent.artistName ?: error("An exact artist name is required.")
            val resolved =
                withContext(Dispatchers.IO) {
                    musicCatalogRepository.songsByArtist(artistName, action.intent.targetCount * 3).getOrThrow()
                }
            val verified = playlistRanker.candidatePool(resolved.songs, action.intent)
            val selected =
                playlistRanker.finalizeSelection(
                    verified,
                    action.intent.targetCount,
                    action.intent,
                )
            check(selected.isNotEmpty()) { context.getString(R.string.ai_playlist_no_candidates) }
            artifacts.rememberSongs(selected)
            updatePlaylistProgress(progressId, 1, 1, selected, ranking = false)
            return artifacts.createDraft(
                action.intent.copy(artistName = resolved.artist.title),
                selected,
            )
        }

        val pendingQueries = ArrayDeque(action.queries)
        val searchedQueries = linkedSetOf<String>()
        val startedAt = System.nanoTime()
        var candidates = emptyList<SongItem>()
        var latestSelection: com.metrolist.music.ai.playlist.AiPlaylistSelection? = null
        var relatedExpanded = false
        var round = 0
        var firstFailure: Throwable? = null

        while (
            round < MAX_ADAPTIVE_PLAYLIST_ROUNDS &&
            elapsedMilliseconds(startedAt) < PLAYLIST_SEARCH_TIMEOUT_MS
        ) {
            val batch = mutableListOf<String>()
            while (pendingQueries.isNotEmpty() && batch.size < MAX_QUERIES_PER_ROUND) {
                val query = pendingQueries.removeFirst().trim()
                if (query.isNotBlank() && searchedQueries.add(query.lowercase())) batch += query
            }

            if (batch.isNotEmpty()) {
                val semaphore = Semaphore(MAX_PARALLEL_PLAYLIST_SEARCHES)
                val desiredPerQuery =
                    (action.intent.targetCount * CANDIDATE_MULTIPLIER)
                        .coerceIn(MIN_RESULTS_PER_QUERY, MAX_RESULTS_PER_QUERY)
                val results =
                    coroutineScope {
                        batch.map { query ->
                            async(Dispatchers.IO) {
                                semaphore.withPermit {
                                    musicCatalogRepository.searchSongsPaged(
                                        query = query,
                                        desiredCount = desiredPerQuery,
                                        maxPages = PLAYLIST_SEARCH_PAGES,
                                    )
                                }
                            }
                        }.awaitAll()
                    }
                firstFailure = firstFailure ?: results.firstNotNullOfOrNull { it.exceptionOrNull() }
                val found = results.flatMap { it.getOrDefault(emptyList()) }
                candidates = playlistRanker.candidatePool(candidates + found, action.intent)
                artifacts.rememberSongs(candidates)
                updatePlaylistProgress(
                    progressId = progressId,
                    current = searchedQueries.size,
                    total = searchedQueries.size + pendingQueries.size,
                    songs = candidates,
                    ranking = false,
                )
            } else if (!relatedExpanded && candidates.isNotEmpty()) {
                val seeds = latestSelection?.songs.orEmpty().ifEmpty { candidates }.take(RELATED_SEED_COUNT)
                val related =
                    coroutineScope {
                        seeds.map { seed ->
                            async(Dispatchers.IO) {
                                musicCatalogRepository.relatedSongs(seed.id, RELATED_RESULTS_PER_SEED)
                            }
                        }.awaitAll().flatMap { it.getOrDefault(emptyList()) }
                    }
                candidates = playlistRanker.candidatePool(candidates + related, action.intent)
                artifacts.rememberSongs(candidates)
                relatedExpanded = true
            } else {
                break
            }

            if (candidates.isEmpty()) {
                round++
                continue
            }
            updatePlaylistProgress(
                progressId = progressId,
                current = searchedQueries.size,
                total = (searchedQueries.size + pendingQueries.size).coerceAtLeast(1),
                songs = candidates,
                ranking = true,
            )
            val curated =
                playlistCurator.select(
                    config = config,
                    intent = action.intent,
                    candidates = candidates,
                    webSearchEnabled = context.dataStore.get(AiAssistantWebSearchKey, false),
                ).getOrThrow()
            latestSelection = curated
            val selected =
                playlistRanker.finalizeSelection(
                    curated.songs,
                    action.intent.targetCount,
                    action.intent,
                )
            if (curated.complete && selected.size >= action.intent.targetCount.coerceAtMost(candidates.size)) {
                break
            }
            curated.additionalQueries
                .asSequence()
                .map(String::trim)
                .filter(String::isNotBlank)
                .filterNot { it.lowercase() in searchedQueries }
                .forEach(pendingQueries::addLast)
            round++
        }

        if (candidates.isEmpty()) {
            throw firstFailure ?: IllegalStateException(context.getString(R.string.ai_playlist_no_candidates))
        }
        val curatedSongs = latestSelection?.songs.orEmpty().ifEmpty { candidates }
        val selected =
            playlistRanker.finalizeSelection(
                curatedSongs,
                action.intent.targetCount,
                action.intent,
            )
        check(selected.isNotEmpty()) { context.getString(R.string.ai_playlist_no_candidates) }
        return artifacts.createDraft(
            action.intent.copy(title = latestSelection?.title ?: action.intent.title),
            selected,
        )
    }

    private fun updatePlaylistProgress(
        progressId: String,
        current: Int,
        total: Int,
        songs: List<SongItem>,
        ranking: Boolean,
    ) {
        val label =
            context.getString(
                if (ranking) R.string.ai_status_ranking else R.string.ai_status_building_playlist,
            )
        _uiState.update { state ->
            state.copy(
                items = state.items.map { item ->
                    if (item is AiChatItem.Progress && item.id == progressId) {
                        item.copy(
                            label = label,
                            current = current,
                            total = total.coerceAtLeast(current).coerceAtLeast(1),
                            songs = songs.take(actionPreviewCount(songs.size)),
                        )
                    } else {
                        item
                    }
                },
                execution =
                    AiAssistantState(
                        phase = if (ranking) AiAssistantPhase.RANKING else AiAssistantPhase.BUILDING_PLAYLIST,
                        status = label,
                        canCancel = true,
                    ),
            )
        }
    }

    private fun actionPreviewCount(candidateCount: Int): Int = candidateCount.coerceAtMost(MAX_PROGRESS_SONGS)

    private fun elapsedMilliseconds(startedAtNanos: Long): Long =
        (System.nanoTime() - startedAtNanos) / 1_000_000L

    fun executePersistentAction(actionId: String) {
        val action = artifacts.pendingAction(actionId) ?: return
        if (activeJob?.isActive == true) return
        _uiState.update {
            it.copy(
                execution =
                    AiAssistantState(
                        phase = AiAssistantPhase.SAVING,
                        status = context.getString(R.string.ai_status_saving),
                        canCancel = true,
                    ),
            )
        }
        activeJob = viewModelScope.launch {
            when (action) {
                is AiPendingAction.SavePlaylistDraft -> {
                    val draft = artifacts.draft(action.draftId)
                    if (draft == null) {
                        resolveAction(actionId, AiActionStatus.FAILED)
                        return@launch
                    }
                    playlistRepository.saveDraft(draft).fold(
                        onSuccess = { playlistId ->
                            artifacts.markSaved(draft.id, playlistId)?.let { saved ->
                                _uiState.update { state ->
                                    state.copy(
                                        items = state.items.map { item ->
                                            if (item is AiChatItem.PlaylistDraft && item.draft.id == saved.id) {
                                                item.copy(draft = saved)
                                            } else item
                                        },
                                    )
                                }
                            }
                            resolveAction(actionId, AiActionStatus.COMPLETED)
                        },
                        onFailure = { resolveAction(actionId, AiActionStatus.FAILED, it.message) },
                    )
                }
                is AiPendingAction.AddTracksToPlaylist ->
                    playlistRepository.addTracks(action.playlistId, action.songs).fold(
                        onSuccess = { resolveAction(actionId, AiActionStatus.COMPLETED) },
                        onFailure = { resolveAction(actionId, AiActionStatus.FAILED, it.message) },
                    )
                else -> resolveAction(actionId, AiActionStatus.FAILED)
            }
        }
    }

    fun resolveAction(
        actionId: String,
        status: AiActionStatus,
        errorMessage: String? = null,
    ) {
        if (status != AiActionStatus.PENDING) artifacts.removePendingAction(actionId)
        _uiState.update { state ->
            state.copy(
                items =
                    state.items.map { item ->
                        when {
                            item is AiChatItem.Confirmation && item.action.id == actionId ->
                                item.copy(status = status, errorMessage = errorMessage)
                            item is AiChatItem.Plan && item.action.id == actionId ->
                                item.copy(status = status, errorMessage = errorMessage)
                            else -> item
                        }
                    },
                execution =
                    AiAssistantState(
                        if (artifacts.hasPendingActions()) {
                            AiAssistantPhase.WAITING_CONFIRMATION
                        } else {
                            AiAssistantPhase.COMPLETED
                        },
                    ),
            )
        }
    }

    private fun appendAssistantText(
        id: String,
        delta: String,
    ) {
        _uiState.update { state ->
            state.copy(
                items =
                    state.items.map { item ->
                        if (item is AiChatItem.AssistantText && item.id == id) {
                            item.copy(text = item.text + delta)
                        } else {
                            item
                        }
                    },
            )
        }
    }

    private fun addError(error: AiError) {
        _uiState.update {
            it.copy(
                items =
                    it.items.filterNot { item -> item is AiChatItem.AssistantText && item.text.isBlank() } +
                        AiChatItem.Error(UUID.randomUUID().toString(), error),
                execution = AiAssistantState(AiAssistantPhase.ERROR, error = error),
            )
        }
    }

    private fun phaseStatus(phase: AiAssistantPhase): String? =
        when (phase) {
            AiAssistantPhase.THINKING -> context.getString(R.string.ai_status_analyzing)
            AiAssistantPhase.PLANNING -> context.getString(R.string.ai_status_planning)
            AiAssistantPhase.SEARCHING -> context.getString(R.string.ai_status_searching)
            AiAssistantPhase.RANKING -> context.getString(R.string.ai_status_ranking)
            AiAssistantPhase.BUILDING_PLAYLIST -> context.getString(R.string.ai_status_building_playlist)
            AiAssistantPhase.SAVING -> context.getString(R.string.ai_status_saving)
            AiAssistantPhase.PLAYING -> context.getString(R.string.ai_status_playing)
            AiAssistantPhase.EXECUTING -> context.getString(R.string.ai_status_running_action)
            else -> null
        }

    private fun toolStatus(name: String): String =
        when (name) {
            "search_music" -> context.getString(R.string.ai_status_searching)
            "search_artist" -> context.getString(R.string.ai_status_searching_artists)
            "search_album" -> context.getString(R.string.ai_status_searching_albums)
            "get_related_songs" -> context.getString(R.string.ai_status_related)
            "get_current_song" -> context.getString(R.string.ai_status_current_song)
            "get_queue" -> context.getString(R.string.ai_status_queue)
            "get_lyrics" -> context.getString(R.string.ai_status_lyrics)
            "get_liked_songs" -> context.getString(R.string.ai_status_liked_songs)
            "search_library" -> context.getString(R.string.ai_status_library)
            "get_playlists", "get_playlist" -> context.getString(R.string.ai_status_playlists)
            "get_recently_played" -> context.getString(R.string.ai_status_history)
            "add_to_queue" -> context.getString(R.string.ai_status_queue_action)
            "play_song" -> context.getString(R.string.ai_status_playback_action)
            "start_radio" -> context.getString(R.string.ai_status_radio_action)
            "create_playlist_draft" -> context.getString(R.string.ai_status_playlist_draft)
            "update_playlist_draft" -> context.getString(R.string.ai_status_playlist_draft)
            "save_playlist", "add_tracks_to_playlist" -> context.getString(R.string.ai_status_playlists)
            "play_playlist" -> context.getString(R.string.ai_status_playback_action)
            "remove_from_queue" -> context.getString(R.string.ai_status_queue_action)
            "get_ui_context" -> context.getString(R.string.ai_status_context)
            else -> context.getString(R.string.ai_status_running_tool, name)
        }

    private fun toolPhase(name: String): AiAssistantPhase =
        when (name) {
            "create_playlist_draft", "update_playlist_draft" -> AiAssistantPhase.PLANNING
            "save_playlist", "add_tracks_to_playlist" -> AiAssistantPhase.SAVING
            "play_song", "play_playlist", "start_radio" -> AiAssistantPhase.PLAYING
            "add_to_queue", "remove_from_queue" -> AiAssistantPhase.EXECUTING
            else -> AiAssistantPhase.SEARCHING
        }

    companion object {
        private const val UI_DELTA_BATCH_NANOS = 45_000_000L
    }
}

data class AiAssistantUiState(
    val items: List<AiChatItem> = emptyList(),
    val execution: AiAssistantState = AiAssistantState(),
    val savingDraftIds: Set<String> = emptySet(),
)

sealed interface AiChatItem {
    val id: String

    data class UserText(
        override val id: String,
        val text: String,
    ) : AiChatItem

    data class AssistantText(
        override val id: String,
        val text: String,
    ) : AiChatItem

    data class SongResults(
        override val id: String,
        val songs: List<SongItem>,
    ) : AiChatItem

    data class AlbumResults(
        override val id: String,
        val albums: List<AlbumItem>,
    ) : AiChatItem

    data class ArtistResults(
        override val id: String,
        val artists: List<ArtistItem>,
    ) : AiChatItem

    data class PlaylistResults(
        override val id: String,
        val playlists: List<AiLibraryPlaylist>,
    ) : AiChatItem

    data class GroundingSources(
        override val id: String,
        val sources: List<AiGroundingSource>,
    ) : AiChatItem

    data class PlaylistDraft(
        override val id: String,
        val draft: AiPlaylistDraft,
    ) : AiChatItem

    data class Confirmation(
        override val id: String,
        val action: AiPendingAction,
        val status: AiActionStatus = AiActionStatus.PENDING,
        val errorMessage: String? = null,
    ) : AiChatItem

    data class Plan(
        override val id: String,
        val action: AiPendingAction,
        val status: AiActionStatus = AiActionStatus.PENDING,
        val errorMessage: String? = null,
    ) : AiChatItem

    data class Progress(
        override val id: String,
        val toolCallId: String,
        val label: String,
        val completed: Boolean = false,
        val failed: Boolean = false,
        val current: Int? = null,
        val total: Int? = null,
        val songs: List<SongItem> = emptyList(),
    ) : AiChatItem

    data class Error(
        override val id: String,
        val error: AiError,
    ) : AiChatItem
}

private const val MAX_PARALLEL_PLAYLIST_SEARCHES = 3
private const val CANDIDATE_MULTIPLIER = 4
private const val MIN_RESULTS_PER_QUERY = 24
private const val MAX_RESULTS_PER_QUERY = 60
private const val PLAYLIST_SEARCH_PAGES = 4
private const val MAX_QUERIES_PER_ROUND = 8
private const val MAX_ADAPTIVE_PLAYLIST_ROUNDS = 6
private const val PLAYLIST_SEARCH_TIMEOUT_MS = 45_000L
private const val RELATED_SEED_COUNT = 3
private const val RELATED_RESULTS_PER_SEED = 20
private const val MAX_PROGRESS_SONGS = 24

private class MissingAiKeyException : IllegalStateException()
