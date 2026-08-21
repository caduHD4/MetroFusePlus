/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ai.repository

import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.WatchEndpoint
import java.text.Normalizer
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AiMusicCatalogRepository
@Inject
constructor() {
    suspend fun searchSongs(
        query: String,
        limit: Int,
    ): Result<List<SongItem>> = searchSongsPaged(query, limit, maxPages = 1)

    suspend fun searchSongsPaged(
        query: String,
        desiredCount: Int,
        maxPages: Int = DEFAULT_SEARCH_PAGES,
    ): Result<List<SongItem>> =
        runCatching {
            val target = desiredCount.coerceIn(1, MAX_PAGED_RESULTS)
            val pages = maxPages.coerceIn(1, MAX_SEARCH_PAGES)
            val firstPage = YouTube.search(query, YouTube.SearchFilter.FILTER_SONG).getOrThrow()
            val songs = firstPage.items.filterIsInstance<SongItem>().toMutableList()
            val seenContinuations = mutableSetOf<String>()
            var continuation = firstPage.continuation
            var fetchedPages = 1
            while (songs.distinctBy(SongItem::id).size < target && continuation != null && fetchedPages < pages) {
                if (!seenContinuations.add(continuation)) break
                val page = YouTube.searchContinuation(continuation).getOrThrow()
                songs += page.items.filterIsInstance<SongItem>()
                continuation = page.continuation
                fetchedPages++
            }
            songs.distinctBy(SongItem::id).take(target)
        }

    suspend fun songsByArtist(
        requestedArtistName: String,
        limit: Int,
    ): Result<ResolvedArtistSongs> =
        runCatching {
            val requestedKey = normalizeName(requestedArtistName)
            val matches = searchArtists(requestedArtistName, ARTIST_SEARCH_RESULTS).getOrThrow()
            val artist =
                matches.firstOrNull { candidate -> normalizeName(candidate.title) == requestedKey }
                    ?: error("Exact artist not found: $requestedArtistName")
            val page = YouTube.artist(artist.id).getOrThrow()
            val canonicalArtist = page.artist
            val target = limit.coerceIn(1, MAX_PAGED_RESULTS)
            val songs = page.sections.flatMap { it.items }.filterIsInstance<SongItem>().toMutableList()
            val endpoints = page.sections.mapNotNull { it.moreEndpoint }.distinctBy { it.browseId to it.params }
            endpoints.forEach { endpoint ->
                if (verifiedArtistSongs(songs, canonicalArtist).size >= target) return@forEach
                val itemsPage = YouTube.artistItems(endpoint).getOrNull() ?: return@forEach
                songs += itemsPage.items.filterIsInstance<SongItem>()
                var continuation = itemsPage.continuation
                var pagesFetched = 1
                val seenContinuations = mutableSetOf<String>()
                while (
                    verifiedArtistSongs(songs, canonicalArtist).size < target &&
                    continuation != null &&
                    pagesFetched < MAX_ARTIST_PAGES
                ) {
                    if (!seenContinuations.add(continuation)) break
                    val continuationPage = YouTube.artistItemsContinuation(continuation).getOrNull() ?: break
                    songs += continuationPage.items.filterIsInstance<SongItem>()
                    continuation = continuationPage.continuation
                    pagesFetched++
                }
            }
            ResolvedArtistSongs(
                artist = canonicalArtist,
                songs = verifiedArtistSongs(songs, canonicalArtist).take(target),
            )
        }

    suspend fun searchAlbums(
        query: String,
        limit: Int,
    ): Result<List<AlbumItem>> =
        YouTube.search(query, YouTube.SearchFilter.FILTER_ALBUM).map { page ->
            page.items
                .filterIsInstance<AlbumItem>()
                .distinctBy(AlbumItem::id)
                .take(limit.coerceIn(1, MAX_RESULTS))
        }

    suspend fun searchArtists(
        query: String,
        limit: Int,
    ): Result<List<ArtistItem>> =
        YouTube.search(query, YouTube.SearchFilter.FILTER_ARTIST).map { page ->
            page.items
                .filterIsInstance<ArtistItem>()
                .distinctBy(ArtistItem::id)
                .take(limit.coerceIn(1, MAX_RESULTS))
        }

    suspend fun relatedSongs(
        seedSongId: String,
        limit: Int,
    ): Result<List<SongItem>> {
        val nextPage = YouTube.next(WatchEndpoint(videoId = seedSongId)).getOrElse {
            return Result.failure(it)
        }
        val relatedEndpoint = nextPage.relatedEndpoint ?: return Result.success(emptyList())
        return YouTube.related(relatedEndpoint).map { page ->
            page.songs
                .asSequence()
                .filterNot { it.id == seedSongId }
                .distinctBy(SongItem::id)
                .take(limit.coerceIn(1, MAX_RESULTS))
                .toList()
        }
    }

    companion object {
        const val MAX_RESULTS = 20
        const val MAX_PAGED_RESULTS = 120
        private const val DEFAULT_SEARCH_PAGES = 3
        private const val MAX_SEARCH_PAGES = 6
        private const val ARTIST_SEARCH_RESULTS = 8
        private const val MAX_ARTIST_PAGES = 6
    }
}

data class ResolvedArtistSongs(
    val artist: ArtistItem,
    val songs: List<SongItem>,
)

private fun verifiedArtistSongs(
    songs: List<SongItem>,
    artist: ArtistItem,
): List<SongItem> {
    val artistIds = setOfNotNull(artist.id, artist.channelId)
    val artistName = normalizeName(artist.title)
    return songs
        .filter { song ->
            song.artists.any { songArtist ->
                songArtist.id in artistIds || normalizeName(songArtist.name) == artistName
            }
        }.distinctBy(SongItem::id)
}

internal fun normalizeName(value: String): String =
    Normalizer
        .normalize(value, Normalizer.Form.NFKD)
        .lowercase()
        .filter(Char::isLetterOrDigit)
