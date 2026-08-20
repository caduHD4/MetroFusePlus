/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ai.action

import com.metrolist.innertube.models.SongItem
import com.metrolist.music.ai.playlist.AiPlaylistIntent

sealed interface AiPendingAction {
    val id: String

    data class AddSongsToQueue(
        override val id: String,
        val songs: List<SongItem>,
        val insertion: AiQueueInsertion,
    ) : AiPendingAction

    data class CreatePlaylistDraft(
        override val id: String,
        val intent: AiPlaylistIntent,
        val songs: List<SongItem>,
    ) : AiPendingAction

    data class BuildPlaylistDraft(
        override val id: String,
        val intent: AiPlaylistIntent,
        val queries: List<String>,
    ) : AiPendingAction

    data class PlaySong(
        override val id: String,
        val song: SongItem,
    ) : AiPendingAction

    data class StartRadio(
        override val id: String,
        val song: SongItem,
    ) : AiPendingAction

    data class PlayPlaylist(
        override val id: String,
        val title: String,
        val songs: List<SongItem>,
    ) : AiPendingAction

    data class SavePlaylistDraft(
        override val id: String,
        val draftId: String,
        val title: String,
    ) : AiPendingAction

    data class AddTracksToPlaylist(
        override val id: String,
        val playlistId: String,
        val playlistName: String,
        val songs: List<SongItem>,
    ) : AiPendingAction

    data class RemoveFromQueue(
        override val id: String,
        val entries: List<AiQueueRemoval>,
    ) : AiPendingAction

    data class UpdatePlaylistDraft(
        override val id: String,
        val draftId: String,
        val title: String?,
        val songs: List<SongItem>,
        val replace: Boolean,
    ) : AiPendingAction
}

data class AiQueueRemoval(
    val position: Int,
    val songId: String,
    val title: String,
)

enum class AiQueueInsertion {
    NEXT,
    END,
}

enum class AiActionStatus {
    PENDING,
    COMPLETED,
    DISMISSED,
    FAILED,
}
