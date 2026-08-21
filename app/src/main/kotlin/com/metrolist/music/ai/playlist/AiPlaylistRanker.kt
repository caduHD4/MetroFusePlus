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
    fun candidatePool(
        candidates: List<SongItem>,
        intent: AiPlaylistIntent? = null,
    ): List<SongItem> {
        val seenIds = mutableSetOf<String>()
        val seenMetadata = mutableSetOf<String>()
        val unique =
            candidates
                .asSequence()
                .filterNot(SongItem::isEpisode)
                .filter { song -> isPlausibleTrack(song, intent) }
                .filter { song ->
                    val idIsNew = seenIds.add(song.id)
                    val metadataKey =
                        "${normalize(song.title)}\u0000${normalize(song.artists.firstOrNull()?.name.orEmpty())}"
                    idIsNew && seenMetadata.add(metadataKey)
                }.take(AiSessionArtifacts.MAX_CANDIDATE_POOL)
                .toList()
        return unique
            .mapIndexed { index, song -> RankedSong(song, scoreCandidate(song, intent, index)) }
            .sortedByDescending(RankedSong::score)
            .map(RankedSong::song)
    }

    fun finalizeSelection(
        selected: List<SongItem>,
        targetCount: Int,
        intent: AiPlaylistIntent? = null,
    ): List<SongItem> {
        val target = targetCount.coerceIn(1, AiSessionArtifacts.MAX_CANDIDATE_POOL)
        val unique = candidatePool(selected, intent)
        if (intent?.type == AiPlaylistIntentType.ARTIST) return unique.take(target)
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
        private const val MIN_TRACK_SECONDS = 35
        private const val MAX_TRACK_SECONDS = 12 * 60
        private val compilationMarkers =
            listOf(
                "compilation",
                "playlist",
                "full album",
                "one hour",
                "1 hour",
                "60 minutes",
                "music collection",
                "songs collection",
                "continuous mix",
                "hour mix",
                "musicas para",
                "músicas para",
            )

        internal fun isPlausibleTrack(
            song: SongItem,
            intent: AiPlaylistIntent? = null,
        ): Boolean {
            if (song.artists.none { it.name.isNotBlank() }) return false
            val duration = song.duration
            if (duration != null && duration !in MIN_TRACK_SECONDS..MAX_TRACK_SECONDS) return false
            val normalizedTitle = song.title.lowercase()
            if (compilationMarkers.any(normalizedTitle::contains)) return false
            val conceptIntent =
                intent?.takeIf {
                    it.type in setOf(AiPlaylistIntentType.GENRE, AiPlaylistIntentType.MOOD, AiPlaylistIntentType.CONCEPT)
                }
            if (conceptIntent != null) {
                val conceptTokens =
                    (conceptIntent.title + " " + conceptIntent.description.orEmpty())
                        .lowercase()
                        .split(Regex("[^\\p{L}\\p{N}]+"))
                        .filter { it.length >= 4 }
                        .toSet()
                val genericMusicTitle =
                    (normalizedTitle.contains(" music") || normalizedTitle.contains(" songs")) &&
                        conceptTokens.any(normalizedTitle::contains) &&
                        song.album == null
                if (genericMusicTitle) return false
            }
            val excluded = intent?.exclusions.orEmpty().map(String::lowercase)
            return excluded.none { it.isNotBlank() && normalizedTitle.contains(it) }
        }

        internal fun scoreCandidate(
            song: SongItem,
            intent: AiPlaylistIntent?,
            originalIndex: Int,
        ): Int {
            var score =
                (AiSessionArtifacts.MAX_CANDIDATE_POOL -
                    originalIndex.coerceAtMost(AiSessionArtifacts.MAX_CANDIDATE_POOL)) * 10
            if (song.album != null) score += 5
            if (song.duration != null) score += 4
            if (song.endpoint != null) score += 2
            score += song.artists.count { !it.id.isNullOrBlank() }.coerceAtMost(2)

            val normalizedArtist = song.artists.joinToString(" ") { it.name }.lowercase()
            val requestedArtist = intent?.artistName
            if (intent?.type == AiPlaylistIntentType.ARTIST && requestedArtist != null) {
                if (normalize(normalizedArtist).contains(normalize(requestedArtist))) score += 100
            }
            if (intent?.type in setOf(AiPlaylistIntentType.GENRE, AiPlaylistIntentType.MOOD, AiPlaylistIntentType.CONCEPT)) {
                val tokens =
                    (intent?.title.orEmpty() + " " + intent?.description.orEmpty() + " " + intent?.era.orEmpty())
                        .lowercase()
                        .split(Regex("[^\\p{L}\\p{N}]+"))
                        .filter { it.length >= 4 }
                        .distinct()
                val albumAndArtist = normalizedArtist + " " + song.album?.name.orEmpty().lowercase()
                score += tokens.count(albumAndArtist::contains) * 3
            }
            return score
        }

        private fun normalize(value: String): String =
            value
                .lowercase()
                .filter(Char::isLetterOrDigit)
    }
}

private data class RankedSong(
    val song: SongItem,
    val score: Int,
)
