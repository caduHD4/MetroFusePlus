/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ai.repository

import com.metrolist.innertube.models.SongItem
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.models.toMediaMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AiUserContextRepository
@Inject
constructor(
    private val database: MusicDatabase,
) {
    suspend fun likedSongs(
        limit: Int,
        offset: Int,
    ): List<SongItem> =
        database
            .likedSongsByCreateDateAsc()
            .first()
            .asReversed()
            .drop(safeOffset(offset))
            .take(safeLimit(limit))
            .map { it.toMediaMetadata().toYTItem() }

    suspend fun searchLibrary(
        query: String,
        limit: Int,
    ): List<SongItem> =
        database
            .searchSongs(query, safeLimit(limit))
            .first()
            .map { it.toMediaMetadata().toYTItem() }

    suspend fun playlists(
        limit: Int,
        offset: Int,
    ): List<AiLibraryPlaylist> =
        database
            .playlistsByUpdatedDateAsc()
            .first()
            .asReversed()
            .drop(safeOffset(offset))
            .take(safeLimit(limit))
            .map {
                AiLibraryPlaylist(
                    id = it.id,
                    name = it.title,
                    songCount = it.songCount,
                )
            }

    suspend fun playlistSongs(
        playlistId: String,
        limit: Int,
        offset: Int,
    ): List<SongItem> =
        database
            .playlistSongs(playlistId)
            .first()
            .drop(safeOffset(offset))
            .take(safeLimit(limit))
            .map { it.song.toMediaMetadata().toYTItem() }

    suspend fun playlistName(playlistId: String): String? =
        withContext(Dispatchers.IO) { database.playlistBlocking(playlistId)?.title }

    suspend fun editablePlaylistName(playlistId: String): String? =
        withContext(Dispatchers.IO) {
            database.playlistBlocking(playlistId)?.takeIf { it.playlist.isEditable }?.title
        }

    suspend fun recentlyPlayed(
        limit: Int,
        days: Int,
    ): List<AiRecentSong> {
        val cutoff = LocalDateTime.now().minusDays(days.coerceIn(1, MAX_HISTORY_DAYS).toLong())
        return database
            .events()
            .first()
            .asSequence()
            .filter { !it.event.timestamp.isBefore(cutoff) }
            .distinctBy { it.song.id }
            .take(safeLimit(limit))
            .map {
                AiRecentSong(
                    song = it.song.toMediaMetadata().toYTItem(),
                    listenedSeconds = it.event.playTime.coerceAtLeast(0L) / 1000L,
                )
            }.toList()
    }

    private fun safeLimit(limit: Int): Int = limit.coerceIn(1, MAX_RESULTS)

    private fun safeOffset(offset: Int): Int = offset.coerceIn(0, MAX_OFFSET)

    companion object {
        const val MAX_RESULTS = 20
        const val MAX_OFFSET = 5_000
        const val MAX_HISTORY_DAYS = 90
    }
}

data class AiLibraryPlaylist(
    val id: String,
    val name: String,
    val songCount: Int,
)

data class AiRecentSong(
    val song: SongItem,
    val listenedSeconds: Long,
)
