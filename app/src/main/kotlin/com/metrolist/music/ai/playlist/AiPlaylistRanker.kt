/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ai.playlist

import com.metrolist.innertube.models.SongItem
import javax.inject.Inject

class AiPlaylistRanker
@Inject
constructor() {
    fun finalizeSelection(
        selected: List<SongItem>,
        targetCount: Int,
    ): List<SongItem> {
        val target = targetCount.coerceIn(1, AiSessionArtifacts.MAX_CANDIDATE_POOL)
        val unique = selected.distinctBy(SongItem::id)
        if (unique.size <= 3) return unique.take(target)

        val primaryArtistLimit = MAX_PRIMARY_ARTIST_TRACKS
        val artistCounts = mutableMapOf<String, Int>()
        val diverse = mutableListOf<SongItem>()
        val deferred = mutableListOf<SongItem>()
        unique.forEach { song ->
            val artistKey = song.artists.firstOrNull()?.id ?: song.artists.firstOrNull()?.name.orEmpty().lowercase()
            val currentCount = artistCounts[artistKey] ?: 0
            if (artistKey.isBlank() || currentCount < primaryArtistLimit) {
                diverse += song
                if (artistKey.isNotBlank()) artistCounts[artistKey] = currentCount + 1
            } else {
                deferred += song
            }
        }
        return (diverse + deferred).take(target)
    }

    companion object {
        private const val MAX_PRIMARY_ARTIST_TRACKS = 3
    }
}
