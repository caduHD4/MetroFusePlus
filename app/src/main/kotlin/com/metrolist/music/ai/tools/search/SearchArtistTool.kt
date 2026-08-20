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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import javax.inject.Inject

class SearchArtistTool
@Inject
constructor(
    private val catalogRepository: AiMusicCatalogRepository,
) : AiTool {
    override val name = "search_artist"
    override val description = "Searches the YouTube Music catalog for artists and returns real artist IDs."
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
                ?: DEFAULT_LIMIT
        return catalogRepository.searchArtists(query, limit).fold(
            onSuccess = { artists ->
                AiToolResult.Success(
                    payload =
                        buildJsonObject {
                            put("query", query)
                            put(
                                "items",
                                buildJsonArray {
                                    artists.forEach { artist ->
                                        add(
                                            buildJsonObject {
                                                put("id", artist.id)
                                                put("name", artist.title)
                                            },
                                        )
                                    }
                                },
                            )
                        },
                    presentation = AiToolPresentation.Artists(artists),
                )
            },
            onFailure = {
                AiToolResult.Failure("catalog_search_failed", it.message ?: "Artist search failed.")
            },
        )
    }
}

internal fun searchEntitySchema(): JsonObject =
    buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                put(
                    "query",
                    buildJsonObject {
                        put("type", "string")
                        put("minLength", 1)
                        put("maxLength", SearchMusicTool.MAX_QUERY_CHARS)
                    },
                )
                put(
                    "limit",
                    buildJsonObject {
                        put("type", "integer")
                        put("minimum", 1)
                        put("maximum", AiMusicCatalogRepository.MAX_RESULTS)
                        put("default", DEFAULT_LIMIT)
                    },
                )
            },
        )
        put("required", JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("query"))))
        put("additionalProperties", false)
    }

private const val DEFAULT_LIMIT = 8
