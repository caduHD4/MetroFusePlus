/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ai.repository

import com.metrolist.music.ai.playlist.AiPlaylistDraft
import com.metrolist.music.ai.core.runCatchingPreservingCancellation
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.PlaylistEntity
import com.metrolist.music.models.toMediaMetadata
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AiPlaylistRepository
@Inject
constructor(
    private val database: MusicDatabase,
) {
    suspend fun saveDraft(draft: AiPlaylistDraft): Result<String> =
        runCatchingPreservingCancellation {
            val playlist =
                PlaylistEntity(
                    name = draft.intent.title,
                    bookmarkedAt = LocalDateTime.now(),
                    isEditable = true,
                    thumbnailUrl = draft.songs.firstOrNull()?.thumbnail,
                )
            database.withTransaction {
                insert(playlist)
                draft.songs.forEach { insert(it.toMediaMetadata()) }
                val created = playlistBlocking(playlist.id)
                    ?: error("The playlist could not be created.")
                addSongsToPlaylist(
                    created,
                    draft.songs.map { it.id to it.setVideoId },
                )
            }
            playlist.id
        }
}
