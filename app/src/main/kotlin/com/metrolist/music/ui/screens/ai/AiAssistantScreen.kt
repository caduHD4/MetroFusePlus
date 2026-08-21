/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.ai

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.ContextCompat
import androidx.media3.common.Timeline
import androidx.navigation.NavController
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.WatchEndpoint
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.ai.core.AiAssistantPhase
import com.metrolist.music.ai.action.AiActionStatus
import com.metrolist.music.ai.action.AiPendingAction
import com.metrolist.music.ai.action.AiQueueInsertion
import com.metrolist.music.ai.model.AiQueueItemContext
import com.metrolist.music.ai.model.AiUiContext
import com.metrolist.music.ai.model.AiUiContextType
import com.metrolist.music.ai.model.CurrentLyricsContext
import com.metrolist.music.ai.model.CurrentMusicContext
import com.metrolist.music.ai.voice.AndroidSpeechInputController
import com.metrolist.music.db.entities.LyricsEntity
import com.metrolist.music.extensions.metadata
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.playback.PlayerConnection
import com.metrolist.music.playback.queues.YouTubeQueue
import com.metrolist.music.playback.queues.ListQueue
import com.metrolist.music.ai.playlist.AiPlaylistDraft
import com.metrolist.music.ai.repository.AiLibraryPlaylist
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.ui.component.YouTubeListItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiAssistantScreen(
    navController: NavController,
    viewModel: AiAssistantViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val playerConnection = LocalPlayerConnection.current
    val context = LocalContext.current
    val mediaMetadataState: State<MediaMetadata?> =
        playerConnection?.mediaMetadata?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(null) }
    val isPlayingState: State<Boolean> =
        playerConnection?.isEffectivelyPlaying?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(false) }
    val queueWindowsState: State<List<Timeline.Window>> =
        playerConnection?.queueWindows?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(emptyList()) }
    val currentWindowIndexState: State<Int> =
        playerConnection?.currentWindowIndex?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(-1) }
    val lyricsState: State<LyricsEntity?> =
        playerConnection?.currentLyrics?.collectAsStateWithLifecycle(initialValue = null)
            ?: remember { mutableStateOf(null) }
    val mediaMetadata by mediaMetadataState
    val isPlaying by isPlayingState
    val queueWindows by queueWindowsState
    val currentWindowIndex by currentWindowIndexState
    val currentLyrics by lyricsState
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current
    val density = LocalDensity.current
    val imeBottom = WindowInsets.ime.getBottom(density)
    val uriHandler = LocalUriHandler.current
    var input by remember { mutableStateOf("") }
    var isListening by remember { mutableStateOf(false) }
    var voiceMessage by remember { mutableStateOf<Int?>(null) }
    val busy = uiState.execution.canCancel
    val sourceEntry = navController.previousBackStackEntry
    val sourceRoute = sourceEntry?.destination?.route
    val sourceArguments = sourceEntry?.arguments
    val screenContext = remember(sourceRoute, sourceArguments) { aiUiContext(sourceRoute, sourceArguments) }

    val transcriptHandler by rememberUpdatedState<(String) -> Unit> { transcript ->
        input = transcript
        voiceMessage = null
    }
    val listeningHandler by rememberUpdatedState<(Boolean) -> Unit> { listening ->
        isListening = listening
    }
    val voiceFailureHandler by rememberUpdatedState<() -> Unit> {
        voiceMessage = R.string.ai_voice_failed
    }
    val speechInput =
        remember(context) {
            AndroidSpeechInputController(
                context = context,
                onTranscript = { transcriptHandler(it) },
                onListeningChanged = { listeningHandler(it) },
                onFailure = { voiceFailureHandler() },
            )
        }
    DisposableEffect(speechInput) {
        onDispose { speechInput.destroy() }
    }
    val microphonePermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                voiceMessage = null
                speechInput.start()
            } else {
                voiceMessage = R.string.ai_voice_permission_denied
            }
        }

    fun toggleVoiceInput() {
        voiceMessage = null
        if (!speechInput.isAvailable) {
            voiceMessage = R.string.ai_voice_unavailable
            return
        }
        if (isListening) {
            speechInput.stop()
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            speechInput.start()
        } else {
            microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    fun currentContext(): CurrentMusicContext? =
        mediaMetadata?.let { song ->
            CurrentMusicContext(
                id = song.id,
                title = song.title,
                artists = song.artists.map { it.name },
                album = song.album?.title,
                durationSeconds = song.duration.takeIf { it >= 0 },
                positionSeconds = playerConnection?.player?.currentPosition?.div(1000L)?.toInt(),
                isPlaying = isPlaying,
            )
        }

    fun queueContext(): List<AiQueueItemContext> {
        val start = queueContextStart(queueWindows.size, currentWindowIndex)
        return queueWindows
            .drop(start)
            .take(MAX_QUEUE_CONTEXT_ITEMS)
            .mapIndexedNotNull { relativeIndex, window ->
                val index = start + relativeIndex
                window.mediaItem.metadata?.let { song ->
                    AiQueueItemContext(
                        id = song.id,
                        title = song.title,
                        artists = song.artists.map { it.name },
                        album = song.album?.title,
                        durationSeconds = song.duration.takeIf { it >= 0 },
                        position = index,
                        isCurrent = index == currentWindowIndex,
                    )
                }
            }
    }

    fun lyricsContext(): CurrentLyricsContext? =
        currentLyrics
            ?.takeIf {
                it.id == mediaMetadata?.id &&
                    it.lyrics.isNotBlank() &&
                    it.lyrics != LyricsEntity.LYRICS_NOT_FOUND
            }
            ?.let {
                CurrentLyricsContext(
                    songId = it.id,
                    provider = it.provider,
                    text = it.lyrics,
                    translatedText = it.translatedLyrics.takeIf(String::isNotBlank),
                    translationLanguage = it.translationLanguage.takeIf(String::isNotBlank),
                )
            }

    fun submit(text: String = input) {
        if (text.isBlank() || busy) return
        if (isListening) speechInput.stop()
        val currentMusicContext = currentContext()
        viewModel.sendMessage(
            text = text,
            currentMusic = currentMusicContext,
            currentSongItem = mediaMetadata?.toYTItem(),
            queue = queueContext(),
            queueTotal = queueWindows.size,
            lyrics = lyricsContext(),
            uiContext =
                screenContext.takeUnless { it.type == AiUiContextType.NONE }
                    ?: currentMusicContext?.let { AiUiContext(AiUiContextType.PLAYER, resourceId = it.id) },
        )
        input = ""
        focusManager.clearFocus()
    }

    LaunchedEffect(uiState.items.size, uiState.execution.phase, imeBottom) {
        if (uiState.items.isNotEmpty()) listState.animateScrollToItem(uiState.items.lastIndex)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ai_assistant_title)) },
                navigationIcon = {
                    IconButton(onClick = navController::navigateUp) {
                        Icon(painterResource(R.drawable.arrow_back), contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = { navController.navigate("settings/ai") }) {
                        Icon(painterResource(R.drawable.tune), contentDescription = stringResource(R.string.ai_assistant_settings))
                    }
                    IconButton(onClick = viewModel::clearConversation) {
                        Icon(painterResource(R.drawable.refresh), contentDescription = stringResource(R.string.ai_assistant_new_chat))
                    }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(
                    modifier =
                        Modifier
                            .windowInsetsPadding(
                                LocalPlayerAwareWindowInsets.current.union(WindowInsets.ime).only(
                                    WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
                                ),
                            ).padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    if (busy) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = uiState.execution.status ?: stringResource(R.string.ai_assistant_working),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = viewModel::cancel) {
                                Text(stringResource(R.string.cancel))
                            }
                        }
                    }
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(stringResource(R.string.ai_assistant_input_hint)) },
                        maxLines = 5,
                        enabled = !busy,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { submit() }),
                        supportingText = {
                            when {
                                isListening -> Text(stringResource(R.string.ai_voice_listening))
                                voiceMessage != null -> Text(stringResource(voiceMessage!!))
                            }
                        },
                        trailingIcon = {
                            Row {
                                IconButton(onClick = ::toggleVoiceInput, enabled = !busy) {
                                    Icon(
                                        painterResource(R.drawable.mic),
                                        contentDescription = stringResource(R.string.ai_voice_input),
                                        tint =
                                            if (isListening) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                    )
                                }
                                IconButton(onClick = { submit() }, enabled = input.isNotBlank() && !busy) {
                                    Icon(
                                        painterResource(R.drawable.send),
                                        contentDescription = stringResource(R.string.ai_assistant_send),
                                    )
                                }
                            }
                        },
                        shape = RoundedCornerShape(24.dp),
                    )
                }
            }
        },
    ) { paddingValues ->
        if (uiState.items.isEmpty()) {
            AiAssistantWelcome(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .padding(horizontal = 24.dp),
                currentSongAvailable = mediaMetadata != null,
                onPrompt = ::submit,
            )
        } else {
            LazyColumn(
                state = listState,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .windowInsetsPadding(
                            LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal),
                        ),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(uiState.items, key = AiChatItem::id) { item ->
                    when (item) {
                        is AiChatItem.UserText -> ChatBubble(item.text, isUser = true)
                        is AiChatItem.AssistantText -> {
                            if (item.text.isNotBlank()) ChatBubble(item.text, isUser = false)
                        }
                        is AiChatItem.SongResults ->
                            SongResultsCard(
                                songs = item.songs,
                                onPlay = { song ->
                                    playerConnection?.playQueue(
                                        YouTubeQueue(
                                            endpoint = WatchEndpoint(videoId = song.id),
                                            preloadItem = song.toMediaMetadata(),
                                        ),
                                    )
                                },
                                onAddToQueue = { song -> playerConnection?.addToQueue(song.toMediaItem()) },
                            )
                        is AiChatItem.AlbumResults ->
                            AlbumResultsCard(
                                albums = item.albums,
                                onOpen = { album -> navController.navigate("album/${album.id}") },
                            )
                        is AiChatItem.ArtistResults ->
                            ArtistResultsCard(
                                artists = item.artists,
                                onOpen = { artist -> navController.navigate("artist/${artist.id}") },
                            )
                        is AiChatItem.PlaylistResults ->
                            LibraryPlaylistResultsCard(
                                playlists = item.playlists,
                                onOpen = { playlist -> navController.navigate("local_playlist/${playlist.id}") },
                            )
                        is AiChatItem.GroundingSources ->
                            GroundingSourcesCard(
                                sources = item.sources,
                                onOpen = uriHandler::openUri,
                            )
                        is AiChatItem.PlaylistDraft ->
                            PlaylistDraftCard(
                                draft = item.draft,
                                saving = item.draft.id in uiState.savingDraftIds,
                                onPlay = {
                                    playerConnection?.playQueue(
                                        ListQueue(
                                            title = item.draft.intent.title,
                                            items = item.draft.songs.map { it.toMediaItem() },
                                        ),
                                    )
                                },
                                onSave = { viewModel.saveDraft(item.draft.id) },
                            )
                        is AiChatItem.Confirmation ->
                            AiConfirmationCard(
                                item = item,
                                onConfirm = {
                                    val action = viewModel.pendingAction(item.action.id)
                                    when (action) {
                                        is AiPendingAction.AddSongsToQueue -> {
                                            val activePlayer = playerConnection
                                            if (activePlayer == null || !activePlayer.canAcceptAssistantPlaybackCommand()) {
                                                viewModel.resolveAction(action.id, AiActionStatus.FAILED)
                                            } else {
                                                runCatching {
                                                    val mediaItems = action.songs.map { it.toMediaItem() }
                                                    when (action.insertion) {
                                                        AiQueueInsertion.NEXT -> activePlayer.playNext(mediaItems)
                                                        AiQueueInsertion.END -> activePlayer.addToQueue(mediaItems)
                                                    }
                                                }.fold(
                                                    onSuccess = {
                                                        viewModel.resolveAction(action.id, AiActionStatus.COMPLETED)
                                                    },
                                                    onFailure = {
                                                        viewModel.resolveAction(action.id, AiActionStatus.FAILED)
                                                    },
                                                )
                                            }
                                        }
                                        is AiPendingAction.CreatePlaylistDraft -> {
                                            viewModel.confirmDraftAction(action.id)
                                        }
                                        is AiPendingAction.BuildPlaylistDraft -> viewModel.confirmDraftAction(action.id)
                                        is AiPendingAction.UpdatePlaylistDraft -> viewModel.confirmDraftAction(action.id)
                                        is AiPendingAction.SavePlaylistDraft,
                                        is AiPendingAction.AddTracksToPlaylist,
                                        -> viewModel.executePersistentAction(action.id)
                                        is AiPendingAction.PlaySong -> {
                                            val activePlayer = playerConnection
                                            if (activePlayer == null || !activePlayer.canAcceptAssistantPlaybackCommand()) {
                                                viewModel.resolveAction(action.id, AiActionStatus.FAILED)
                                            } else {
                                                runCatching {
                                                    activePlayer.playQueue(
                                                        YouTubeQueue(
                                                            endpoint = WatchEndpoint(videoId = action.song.id),
                                                            preloadItem = action.song.toMediaMetadata(),
                                                        ),
                                                    )
                                                }.fold(
                                                    onSuccess = {
                                                        viewModel.resolveAction(action.id, AiActionStatus.COMPLETED)
                                                    },
                                                    onFailure = {
                                                        viewModel.resolveAction(action.id, AiActionStatus.FAILED)
                                                    },
                                                )
                                            }
                                        }
                                        is AiPendingAction.StartRadio -> {
                                            val activePlayer = playerConnection
                                            if (activePlayer == null || !activePlayer.canAcceptAssistantPlaybackCommand()) {
                                                viewModel.resolveAction(action.id, AiActionStatus.FAILED)
                                            } else {
                                                runCatching {
                                                    activePlayer.playQueue(
                                                        YouTubeQueue.radio(action.song.toMediaMetadata()),
                                                    )
                                                }.fold(
                                                    onSuccess = {
                                                        viewModel.resolveAction(action.id, AiActionStatus.COMPLETED)
                                                    },
                                                    onFailure = {
                                                        viewModel.resolveAction(action.id, AiActionStatus.FAILED)
                                                    },
                                                )
                                            }
                                        }
                                        is AiPendingAction.PlayPlaylist -> {
                                            val activePlayer = playerConnection
                                            if (activePlayer == null || !activePlayer.canAcceptAssistantPlaybackCommand()) {
                                                viewModel.resolveAction(action.id, AiActionStatus.FAILED)
                                            } else {
                                                runCatching {
                                                    activePlayer.playQueue(
                                                        ListQueue(action.title, action.songs.map { it.toMediaItem() }),
                                                    )
                                                }.fold(
                                                    onSuccess = { viewModel.resolveAction(action.id, AiActionStatus.COMPLETED) },
                                                    onFailure = { viewModel.resolveAction(action.id, AiActionStatus.FAILED, it.message) },
                                                )
                                            }
                                        }
                                        is AiPendingAction.RemoveFromQueue -> {
                                            val activePlayer = playerConnection
                                            val snapshotStillMatches =
                                                activePlayer != null && action.entries.all { entry ->
                                                    entry.position != currentWindowIndex &&
                                                        queueWindows.getOrNull(entry.position)?.mediaItem?.mediaId == entry.songId
                                                }
                                            if (!snapshotStillMatches || activePlayer == null) {
                                                viewModel.resolveAction(action.id, AiActionStatus.FAILED)
                                            } else {
                                                runCatching {
                                                    action.entries.sortedByDescending { it.position }.forEach {
                                                        activePlayer.player.removeMediaItem(it.position)
                                                    }
                                                }.fold(
                                                    onSuccess = { viewModel.resolveAction(action.id, AiActionStatus.COMPLETED) },
                                                    onFailure = { viewModel.resolveAction(action.id, AiActionStatus.FAILED, it.message) },
                                                )
                                            }
                                        }
                                        null -> Unit
                                    }
                                },
                                onDismiss = {
                                    viewModel.resolveAction(item.action.id, AiActionStatus.DISMISSED)
                                },
                            )
                        is AiChatItem.Plan ->
                            AiPlanCard(
                                item = item,
                                onConfirm = { viewModel.confirmDraftAction(item.action.id) },
                                onDismiss = { viewModel.resolveAction(item.action.id, AiActionStatus.DISMISSED) },
                            )
                        is AiChatItem.Progress -> AiProgressCard(item)
                        is AiChatItem.Error -> ErrorCard(item.error.message)
                    }
                }
                if (busy && uiState.items.lastOrNull() !is AiChatItem.AssistantText) {
                    item(key = "ai-progress") {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text(
                                uiState.execution.status ?: stringResource(R.string.ai_assistant_working),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GroundingSourcesCard(
    sources: List<com.metrolist.music.ai.core.AiGroundingSource>,
    onOpen: (String) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(stringResource(R.string.ai_grounding_sources), style = MaterialTheme.typography.labelLarge)
            sources.take(MAX_GROUNDING_SOURCES).forEach { source ->
                Text(
                    text = source.title,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable { onOpen(source.url) },
                )
            }
        }
    }
}

@Composable
private fun AiAssistantWelcome(
    modifier: Modifier,
    currentSongAvailable: Boolean,
    onPrompt: (String) -> Unit,
) {
    val similarPrompt = stringResource(R.string.ai_assistant_prompt_similar_query)
    val explainLyricsPrompt = stringResource(R.string.ai_assistant_prompt_explain_lyrics_query)
    val artistPrompt = stringResource(R.string.ai_assistant_prompt_artist_query)
    val playlistPrompt = stringResource(R.string.ai_assistant_prompt_playlist_query)
    val discoverPrompt = stringResource(R.string.ai_assistant_prompt_discover_query)
    val personalDjPrompt = stringResource(R.string.ai_assistant_prompt_dj_query)
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painterResource(R.drawable.ai_sparkle),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.ai_assistant_welcome_title), style = MaterialTheme.typography.headlineSmall)
        Text(
            stringResource(R.string.ai_assistant_welcome_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        if (currentSongAvailable) {
            AssistChip(
                onClick = { onPrompt(similarPrompt) },
                label = { Text(stringResource(R.string.ai_assistant_prompt_similar)) },
            )
            AssistChip(
                onClick = { onPrompt(explainLyricsPrompt) },
                label = { Text(stringResource(R.string.ai_assistant_prompt_explain_lyrics)) },
            )
            AssistChip(
                onClick = { onPrompt(artistPrompt) },
                label = { Text(stringResource(R.string.ai_assistant_prompt_artist)) },
            )
        }
        AssistChip(
            onClick = { onPrompt(playlistPrompt) },
            label = { Text(stringResource(R.string.ai_assistant_prompt_playlist)) },
        )
        AssistChip(
            onClick = { onPrompt(discoverPrompt) },
            label = { Text(stringResource(R.string.ai_assistant_prompt_discover)) },
        )
        AssistChip(
            onClick = { onPrompt(personalDjPrompt) },
            label = { Text(stringResource(R.string.ai_assistant_prompt_dj)) },
        )
    }
}

@Composable
private fun ChatBubble(
    text: String,
    isUser: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color =
                if (isUser) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth(if (isUser) 0.82f else 0.94f),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun SongResultsCard(
    songs: List<SongItem>,
    onPlay: (SongItem) -> Unit,
    onAddToQueue: (SongItem) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Text(
                text = stringResource(R.string.ai_assistant_song_results),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            songs.forEach { song ->
                YouTubeListItem(
                    item = song,
                    modifier = Modifier.clickable { onPlay(song) },
                    isSwipeable = false,
                    trailingContent = {
                        IconButton(onClick = { onAddToQueue(song) }) {
                            Icon(
                                painterResource(R.drawable.playlist_add),
                                contentDescription = stringResource(R.string.add_to_queue),
                            )
                        }
                        IconButton(onClick = { onPlay(song) }) {
                            Icon(painterResource(R.drawable.play), contentDescription = stringResource(R.string.play))
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun AlbumResultsCard(
    albums: List<AlbumItem>,
    onOpen: (AlbumItem) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Text(
                text = stringResource(R.string.ai_assistant_album_results),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            albums.forEach { album ->
                YouTubeListItem(
                    item = album,
                    modifier = Modifier.clickable { onOpen(album) },
                    isSwipeable = false,
                    trailingContent = {
                        IconButton(onClick = { onOpen(album) }) {
                            Icon(painterResource(R.drawable.arrow_forward), contentDescription = stringResource(R.string.open))
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun ArtistResultsCard(
    artists: List<ArtistItem>,
    onOpen: (ArtistItem) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Text(
                text = stringResource(R.string.ai_assistant_artist_results),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            artists.forEach { artist ->
                YouTubeListItem(
                    item = artist,
                    modifier = Modifier.clickable { onOpen(artist) },
                    isSwipeable = false,
                    trailingContent = {
                        IconButton(onClick = { onOpen(artist) }) {
                            Icon(painterResource(R.drawable.arrow_forward), contentDescription = stringResource(R.string.open))
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun LibraryPlaylistResultsCard(
    playlists: List<AiLibraryPlaylist>,
    onOpen: (AiLibraryPlaylist) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Text(
                text = stringResource(R.string.ai_assistant_playlist_results),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            playlists.forEach { playlist ->
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable { onOpen(playlist) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(painterResource(R.drawable.queue_music), contentDescription = null)
                    Column(Modifier.weight(1f)) {
                        Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            stringResource(R.string.ai_playlist_track_count, playlist.songCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(painterResource(R.drawable.arrow_forward), contentDescription = null)
                }
            }
        }
    }
}

@Composable
private fun ErrorCard(message: String) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painterResource(R.drawable.error),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = message,
                color = MaterialTheme.colorScheme.onErrorContainer,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun AiConfirmationCard(
    item: AiChatItem.Confirmation,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val action = item.action
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.ai_confirmation_title), style = MaterialTheme.typography.titleMedium)
            when (action) {
                is AiPendingAction.AddSongsToQueue -> {
                    Text(
                        stringResource(
                            if (action.insertion == AiQueueInsertion.NEXT) {
                                R.string.ai_confirmation_queue_next
                            } else {
                                R.string.ai_confirmation_queue_end
                            },
                            action.songs.size,
                        ),
                    )
                    action.songs.take(CONFIRMATION_PREVIEW_COUNT).forEach { song ->
                        Text("• ${song.title} — ${song.artists.joinToString { it.name }}")
                    }
                }
                is AiPendingAction.CreatePlaylistDraft -> {
                    Text(
                        stringResource(
                            R.string.ai_confirmation_playlist_draft,
                            action.intent.title,
                            action.songs.size,
                        ),
                    )
                    action.songs.take(CONFIRMATION_PREVIEW_COUNT).forEach { song ->
                        Text("• ${song.title} — ${song.artists.joinToString { it.name }}")
                    }
                }
                is AiPendingAction.BuildPlaylistDraft ->
                    Text(stringResource(R.string.ai_confirmation_build_playlist, action.intent.title, action.intent.targetCount))
                is AiPendingAction.PlaySong -> {
                    Text(stringResource(R.string.ai_confirmation_play_song, action.song.title))
                }
                is AiPendingAction.StartRadio -> {
                    Text(stringResource(R.string.ai_confirmation_start_radio, action.song.title))
                }
                is AiPendingAction.PlayPlaylist ->
                    Text(stringResource(R.string.ai_confirmation_play_playlist, action.title, action.songs.size))
                is AiPendingAction.SavePlaylistDraft ->
                    Text(stringResource(R.string.ai_confirmation_save_playlist, action.title))
                is AiPendingAction.AddTracksToPlaylist ->
                    Text(stringResource(R.string.ai_confirmation_add_tracks, action.songs.size, action.playlistName))
                is AiPendingAction.RemoveFromQueue ->
                    Text(stringResource(R.string.ai_confirmation_remove_queue, action.entries.size))
                is AiPendingAction.UpdatePlaylistDraft ->
                    Text(stringResource(R.string.ai_confirmation_update_draft, action.songs.size))
            }
            when (item.status) {
                AiActionStatus.PENDING ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    ) {
                        TextButton(onClick = onDismiss) { Text(stringResource(R.string.not_now)) }
                        Button(onClick = onConfirm) { Text(stringResource(R.string.ai_confirm_action)) }
                    }
                AiActionStatus.COMPLETED -> Text(stringResource(R.string.ai_action_completed))
                AiActionStatus.DISMISSED -> Text(stringResource(R.string.ai_action_dismissed))
                AiActionStatus.FAILED ->
                    Text(
                        item.errorMessage ?: stringResource(R.string.ai_action_failed),
                        color = MaterialTheme.colorScheme.error,
                    )
            }
        }
    }
}

@Composable
private fun AiPlanCard(
    item: AiChatItem.Plan,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val action = item.action
    val title =
        when (action) {
            is AiPendingAction.CreatePlaylistDraft -> action.intent.title
            is AiPendingAction.BuildPlaylistDraft -> action.intent.title
            is AiPendingAction.UpdatePlaylistDraft -> action.title ?: stringResource(R.string.ai_plan_playlist_update)
            else -> stringResource(R.string.ai_plan_playlist)
        }
    val songs =
        when (action) {
            is AiPendingAction.CreatePlaylistDraft -> action.songs
            is AiPendingAction.BuildPlaylistDraft -> emptyList()
            is AiPendingAction.UpdatePlaylistDraft -> action.songs
            else -> emptyList()
        }
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.ai_plan_title), style = MaterialTheme.typography.labelLarge)
            Text(title, style = MaterialTheme.typography.titleLarge)
            if (action is AiPendingAction.BuildPlaylistDraft) {
                Text(stringResource(R.string.ai_playlist_target_count, action.intent.targetCount))
                action.queries.forEach { query -> Text("• $query") }
            } else {
                Text(stringResource(R.string.ai_playlist_track_count, songs.size))
            }
            songs.take(CONFIRMATION_PREVIEW_COUNT).forEach { song ->
                Text("• ${song.title} — ${song.artists.joinToString { it.name }}")
            }
            when (item.status) {
                AiActionStatus.PENDING ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                        TextButton(onClick = onDismiss) { Text(stringResource(R.string.not_now)) }
                        Button(onClick = onConfirm) { Text(stringResource(R.string.ai_confirm_action)) }
                    }
                AiActionStatus.COMPLETED -> Text(stringResource(R.string.ai_action_completed))
                AiActionStatus.DISMISSED -> Text(stringResource(R.string.ai_action_dismissed))
                AiActionStatus.FAILED ->
                    Text(
                        item.errorMessage ?: stringResource(R.string.ai_action_failed),
                        color = MaterialTheme.colorScheme.error,
                    )
            }
        }
    }
}

@Composable
private fun AiProgressCard(item: AiChatItem.Progress) {
    val current = item.current
    val total = item.total
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                when {
                    item.failed -> Icon(painterResource(R.drawable.error), null, tint = MaterialTheme.colorScheme.error)
                    item.completed -> Icon(painterResource(R.drawable.check), null, tint = MaterialTheme.colorScheme.primary)
                    else -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                }
                Text(item.label)
                if (current != null && total != null) {
                    Text("$current/$total", style = MaterialTheme.typography.labelMedium)
                }
            }
            if (!item.completed && !item.failed && current != null && total != null && total > 0) {
                LinearProgressIndicator(
                    progress = { current.toFloat() / total.toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item.songs.take(PROGRESS_PREVIEW_COUNT).forEach { song ->
                Text(
                    text = "• ${song.title} — ${song.artists.joinToString { it.name }}",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun PlaylistDraftCard(
    draft: AiPlaylistDraft,
    saving: Boolean,
    onPlay: () -> Unit,
    onSave: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(vertical = 12.dp)) {
            Text(
                text = draft.intent.title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            draft.intent.description?.takeIf(String::isNotBlank)?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            Text(
                text = stringResource(R.string.ai_playlist_track_count, draft.songs.size),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
            draft.songs.take(DRAFT_PREVIEW_COUNT).forEach { song ->
                YouTubeListItem(item = song, isSwipeable = false)
            }
            if (draft.songs.size > DRAFT_PREVIEW_COUNT) {
                Text(
                    text = stringResource(R.string.ai_playlist_more_tracks, draft.songs.size - DRAFT_PREVIEW_COUNT),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(onClick = onPlay) {
                    Icon(painterResource(R.drawable.play), contentDescription = null)
                    Text(stringResource(R.string.ai_playlist_play_draft), Modifier.padding(start = 6.dp))
                }
                if (draft.savedPlaylistId == null) {
                    Button(onClick = onSave, enabled = !saving) {
                        if (saving) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(painterResource(R.drawable.library_add), contentDescription = null)
                        }
                        Text(stringResource(R.string.ai_playlist_save_draft), Modifier.padding(start = 6.dp))
                    }
                } else {
                    Text(
                        stringResource(R.string.ai_playlist_saved),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.align(Alignment.CenterVertically),
                    )
                }
            }
        }
    }
}

private const val DRAFT_PREVIEW_COUNT = 5
private const val CONFIRMATION_PREVIEW_COUNT = 3
private const val PROGRESS_PREVIEW_COUNT = 5
private const val MAX_GROUNDING_SOURCES = 5
private const val MAX_QUEUE_CONTEXT_ITEMS = 100
private const val QUEUE_CONTEXT_BEFORE_CURRENT = 10

private fun queueContextStart(
    queueSize: Int,
    currentIndex: Int,
): Int {
    if (queueSize <= MAX_QUEUE_CONTEXT_ITEMS) return 0
    val preferred = (currentIndex - QUEUE_CONTEXT_BEFORE_CURRENT).coerceAtLeast(0)
    return preferred.coerceAtMost(queueSize - MAX_QUEUE_CONTEXT_ITEMS)
}

private fun aiUiContext(
    route: String?,
    arguments: android.os.Bundle?,
): AiUiContext =
    when {
        route == null -> AiUiContext(AiUiContextType.NONE)
        route.startsWith("local_playlist/") || route.startsWith("online_playlist/") ->
            AiUiContext(AiUiContextType.PLAYLIST, arguments?.getString("playlistId"))
        route.startsWith("album/") -> AiUiContext(AiUiContextType.ALBUM, arguments?.getString("albumId"))
        route.startsWith("artist/") -> AiUiContext(AiUiContextType.ARTIST, arguments?.getString("artistId"))
        route.startsWith("search/") -> AiUiContext(AiUiContextType.SEARCH, query = arguments?.getString("query"))
        route.contains("library", ignoreCase = true) -> AiUiContext(AiUiContextType.LIBRARY)
        else -> AiUiContext(AiUiContextType.NONE)
    }

private fun PlayerConnection.canAcceptAssistantPlaybackCommand(): Boolean =
    allowInternalSync || shouldBlockPlaybackChanges?.invoke() != true
