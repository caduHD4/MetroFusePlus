/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ai.playlist

import com.metrolist.innertube.models.SongItem
import com.metrolist.music.ai.action.AiPendingAction
import com.metrolist.music.ai.action.AiQueueInsertion
import java.util.UUID

data class AiPlaylistIntent(
    val title: String,
    val description: String?,
    val targetCount: Int,
)

data class AiPlaylistDraft(
    val id: String,
    val intent: AiPlaylistIntent,
    val songs: List<SongItem>,
    val savedPlaylistId: String? = null,
)

class AiSessionArtifacts(
    private val maxCandidatePool: Int = MAX_CANDIDATE_POOL,
) {
    init {
        require(maxCandidatePool > 0) { "maxCandidatePool must be positive." }
    }

    private val lock = Any()
    private val catalogSongs = linkedMapOf<String, SongItem>()
    private val libraryPlaylistIds = linkedSetOf<String>()
    private val drafts = linkedMapOf<String, AiPlaylistDraft>()
    private val pendingActions = linkedMapOf<String, AiPendingAction>()

    fun rememberSongs(songs: List<SongItem>) {
        synchronized(lock) {
            songs.forEach { song ->
                catalogSongs.remove(song.id)
                while (catalogSongs.size >= maxCandidatePool) {
                    catalogSongs.remove(catalogSongs.keys.first())
                }
                catalogSongs[song.id] = song
            }
        }
    }

    fun resolveSongs(ids: List<String>): SongResolution =
        synchronized(lock) {
            val distinctIds = ids.distinct()
            val missing = distinctIds.filterNot(catalogSongs::containsKey)
            SongResolution(
                songs = distinctIds.mapNotNull(catalogSongs::get),
                missingIds = missing,
            )
        }

    fun rememberPlaylistIds(ids: Collection<String>) {
        synchronized(lock) {
            ids.forEach { id ->
                libraryPlaylistIds.remove(id)
                while (libraryPlaylistIds.size >= MAX_LIBRARY_PLAYLISTS) {
                    libraryPlaylistIds.remove(libraryPlaylistIds.first())
                }
                libraryPlaylistIds.add(id)
            }
        }
    }

    fun knowsPlaylist(id: String): Boolean = synchronized(lock) { id in libraryPlaylistIds }

    fun createDraft(
        intent: AiPlaylistIntent,
        songs: List<SongItem>,
    ): AiPlaylistDraft =
        synchronized(lock) {
            createDraftLocked(intent, songs)
        }

    fun draft(id: String): AiPlaylistDraft? = synchronized(lock) { drafts[id] }

    fun markSaved(
        draftId: String,
        playlistId: String,
    ): AiPlaylistDraft? =
        synchronized(lock) {
            drafts[draftId]?.copy(savedPlaylistId = playlistId)?.also { drafts[draftId] = it }
        }

    fun createQueueAction(
        songs: List<SongItem>,
        insertion: AiQueueInsertion,
    ): AiPendingAction.AddSongsToQueue =
        synchronized(lock) {
            trimPendingActions()
            AiPendingAction.AddSongsToQueue(
                id = "action_${UUID.randomUUID()}",
                songs = songs,
                insertion = insertion,
            ).also { pendingActions[it.id] = it }
        }

    fun createPlaylistDraftAction(
        intent: AiPlaylistIntent,
        songs: List<SongItem>,
    ): AiPendingAction.CreatePlaylistDraft =
        synchronized(lock) {
            trimPendingActions()
            AiPendingAction.CreatePlaylistDraft(
                id = "action_${UUID.randomUUID()}",
                intent = intent,
                songs = songs,
            ).also { pendingActions[it.id] = it }
        }

    fun createPlaySongAction(song: SongItem): AiPendingAction.PlaySong =
        synchronized(lock) {
            trimPendingActions()
            AiPendingAction.PlaySong(
                id = "action_${UUID.randomUUID()}",
                song = song,
            ).also { pendingActions[it.id] = it }
        }

    fun createStartRadioAction(song: SongItem): AiPendingAction.StartRadio =
        synchronized(lock) {
            trimPendingActions()
            AiPendingAction.StartRadio(
                id = "action_${UUID.randomUUID()}",
                song = song,
            ).also { pendingActions[it.id] = it }
        }

    fun confirmPlaylistDraftAction(actionId: String): AiPlaylistDraft? =
        synchronized(lock) {
            val action = pendingActions.remove(actionId) as? AiPendingAction.CreatePlaylistDraft
                ?: return@synchronized null
            createDraftLocked(action.intent, action.songs)
        }

    fun pendingAction(id: String): AiPendingAction? = synchronized(lock) { pendingActions[id] }

    fun hasPendingActions(): Boolean = synchronized(lock) { pendingActions.isNotEmpty() }

    fun removePendingAction(id: String): AiPendingAction? = synchronized(lock) { pendingActions.remove(id) }

    fun clear() {
        synchronized(lock) {
            catalogSongs.clear()
            libraryPlaylistIds.clear()
            drafts.clear()
            pendingActions.clear()
        }
    }

    private fun trimPendingActions() {
        while (pendingActions.size >= MAX_PENDING_ACTIONS) {
            pendingActions.remove(pendingActions.keys.first())
        }
    }

    private fun createDraftLocked(
        intent: AiPlaylistIntent,
        songs: List<SongItem>,
    ): AiPlaylistDraft {
        while (drafts.size >= MAX_DRAFTS) {
            drafts.remove(drafts.keys.first())
        }
        return AiPlaylistDraft(
            id = "draft_${UUID.randomUUID()}",
            intent = intent,
            songs = songs,
        ).also { drafts[it.id] = it }
    }

    companion object {
        const val MAX_CANDIDATE_POOL = 60
        const val MAX_LIBRARY_PLAYLISTS = 100
        const val MAX_PENDING_ACTIONS = 30
        const val MAX_DRAFTS = 30
    }
}

data class SongResolution(
    val songs: List<SongItem>,
    val missingIds: List<String>,
)
