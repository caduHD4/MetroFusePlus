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
import com.metrolist.music.ai.model.AiCapability
import com.metrolist.music.ai.model.AiConversationMessage
import com.metrolist.music.ai.model.AiPermissions
import com.metrolist.music.ai.model.AiProviderConfig
import com.metrolist.music.ai.model.AiQueueItemContext
import com.metrolist.music.ai.model.CurrentLyricsContext
import com.metrolist.music.ai.model.CurrentMusicContext
import com.metrolist.music.ai.playlist.AiPlaylistDraft
import com.metrolist.music.ai.playlist.AiSessionArtifacts
import com.metrolist.music.ai.provider.AiProviderRegistry
import com.metrolist.music.ai.repository.AiModelRepository
import com.metrolist.music.ai.repository.AiPlaylistRepository
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
import com.metrolist.music.constants.DEFAULT_AI_ASSISTANT_SYSTEM_PROMPT
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.get
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private val contextBuilder: AiContextBuilder,
    private val dataSanitizer: AiDataSanitizer,
) : ViewModel() {
    private val conversation = mutableListOf<AiConversationMessage>()
    private val artifacts = AiSessionArtifacts()
    private val _uiState = MutableStateFlow(AiAssistantUiState())
    val uiState = _uiState.asStateFlow()
    private var activeJob: Job? = null

    fun sendMessage(
        text: String,
        currentMusic: CurrentMusicContext?,
        currentSongItem: SongItem? = null,
        queue: List<AiQueueItemContext> = emptyList(),
        queueTotal: Int = queue.size,
        lyrics: CurrentLyricsContext? = null,
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
                            catalog.models.firstOrNull { AiCapability.TOOLS in it.capabilities }
                                ?: catalog.models.firstOrNull()
                                ?: error(context.getString(R.string.ai_no_compatible_model, descriptor.displayName))
                        }
                    if (configuredModel.isBlank()) {
                        context.dataStore.edit { it[AiAssistantModelKey] = selectedModel.id }
                    }
                    val config = provisionalConfig.copy(modelId = selectedModel.id)
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
                                artifacts = artifacts,
                            ),
                        toolsEnabled = AiCapability.TOOLS in selectedModel.capabilities,
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
                                    it.copy(execution = it.execution.copy(status = toolStatus(event.name)))
                                }
                            is AiAgentEvent.ToolFinished -> {
                                flushText(force = true)
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
                                    is AiToolPresentation.Confirmation -> {
                                        _uiState.update {
                                            it.copy(
                                                items =
                                                    it.items +
                                                        AiChatItem.Confirmation(
                                                            id = UUID.randomUUID().toString(),
                                                            action = presentation.action,
                                                        ),
                                            )
                                        }
                                    }
                                    null -> Unit
                                }
                                startsNewAssistantMessage = true
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
                items = it.items.filterNot { item -> item is AiChatItem.AssistantText && item.text.isBlank() },
                execution = AiAssistantState(AiAssistantPhase.CANCELLED),
            )
        }
    }

    fun clearConversation() {
        cancel()
        conversation.clear()
        artifacts.clear()
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

    fun confirmPlaylistDraft(actionId: String) {
        val draft = artifacts.confirmPlaylistDraftAction(actionId)
        if (draft == null) {
            resolveAction(actionId, AiActionStatus.FAILED)
            return
        }
        _uiState.update { state ->
            state.copy(
                items =
                    state.items.map { item ->
                        if (item is AiChatItem.Confirmation && item.action.id == actionId) {
                            item.copy(status = AiActionStatus.COMPLETED)
                        } else {
                            item
                        }
                    } + AiChatItem.PlaylistDraft(UUID.randomUUID().toString(), draft),
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
                        if (item is AiChatItem.Confirmation && item.action.id == actionId) {
                            item.copy(status = status, errorMessage = errorMessage)
                        } else {
                            item
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
            AiAssistantPhase.SEARCHING -> context.getString(R.string.ai_status_searching)
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
            else -> context.getString(R.string.ai_status_running_tool, name)
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

    data class Error(
        override val id: String,
        val error: AiError,
    ) : AiChatItem
}

private class MissingAiKeyException : IllegalStateException()
