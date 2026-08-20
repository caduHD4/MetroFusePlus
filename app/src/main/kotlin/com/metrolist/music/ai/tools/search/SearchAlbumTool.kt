/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ai.tools.search

import com.metrolist.music.ai.model.AiToolContext
import com.metrolist.music.ai.repository.AiMusicCatalogRepository
import com.metrolist.music.ai.tools.AiTool
import com.metrolist.music.ai.tools.AiToolPresentation
import com.metrolist.music.ai.tools.AiToolResult
import com.metrolist.music.ai.tools.AiToolRisk
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import javax.inject.Inject

class SearchAlbumTool
@Inject
constructor(
    private val catalogRepository: AiMusicCatalogRepository,
) : AiTool {
    override val name = "search_album"
    override val description = "Searches the YouTube Music catalog for albums and returns real album IDs."
    override val inputSchema = searchEntitySchema()
    override val risk = AiToolRisk.READ_ONLY

    override suspend fun execute(
        arguments: JsonObject,
        context: AiToolContext,
    ): AiToolResult {
        val query =
            arguments["query"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty().take(SearchMusicTool.MAX_QUERY_CHARS)
        if (query.isBlank()) return AiToolResult.Failure("invalid_arguments", "query must not be empty.")
        val limit =
            arguments["limit"]?.jsonPrimitive?.intOrNull
                ?.coerceIn(1, AiMusicCatalogRepository.MAX_RESULTS)
                ?: DEFAULT_ALBUM_LIMIT
        return catalogRepository.searchAlbums(query, limit).fold(
            onSuccess = { albums ->
                AiToolResult.Success(
                    payload =
                        buildJsonObject {
                            put("query", query)
                            put(
                                "items",
                                buildJsonArray {
                                    albums.forEach { album ->
                                        add(
                                            buildJsonObject {
                                                put("id", album.id)
                                                put("title", album.title)
                                                put(
                                                    "artists",
                                                    buildJsonArray {
                                                        album.artists.orEmpty().forEach { add(it.name) }
                                                    },
                                                )
                                                album.year?.let { put("year", it) }
                                            },
                                        )
                                    }
                                },
                            )
                        },
                    presentation = AiToolPresentation.Albums(albums),
                )
            },
            onFailure = {
                AiToolResult.Failure("catalog_search_failed", it.message ?: "Album search failed.")
            },
        )
    }
}

private const val DEFAULT_ALBUM_LIMIT = 8
