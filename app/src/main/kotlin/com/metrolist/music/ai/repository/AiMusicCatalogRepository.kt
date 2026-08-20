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
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AiMusicCatalogRepository
@Inject
constructor() {
    suspend fun searchSongs(
        query: String,
        limit: Int,
    ): Result<List<SongItem>> =
        YouTube.search(query, YouTube.SearchFilter.FILTER_SONG).map { page ->
            page.items
                .filterIsInstance<SongItem>()
                .distinctBy(SongItem::id)
                .take(limit.coerceIn(1, MAX_RESULTS))
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
    }
}
