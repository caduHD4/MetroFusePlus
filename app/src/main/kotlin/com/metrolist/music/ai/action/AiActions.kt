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

    data class PlaySong(
        override val id: String,
        val song: SongItem,
    ) : AiPendingAction

    data class StartRadio(
        override val id: String,
        val song: SongItem,
    ) : AiPendingAction
}

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
