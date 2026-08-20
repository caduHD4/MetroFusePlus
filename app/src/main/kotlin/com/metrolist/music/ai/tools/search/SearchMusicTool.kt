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

class SearchMusicTool
@Inject
constructor(
    private val catalogRepository: AiMusicCatalogRepository,
) : AiTool {
    override val name = "search_music"
    override val description =
        "Searches the YouTube Music catalog and returns real MetroFuse+ song IDs. Use this before recommending songs."
    override val inputSchema =
        buildJsonObject {
            put("type", "object")
            put(
                "properties",
                buildJsonObject {
                    put(
                        "query",
                        buildJsonObject {
                            put("type", "string")
                            put("description", "Music search query.")
                            put("minLength", 1)
                            put("maxLength", MAX_QUERY_CHARS)
                        },
                    )
                    put(
                        "limit",
                        buildJsonObject {
                            put("type", "integer")
                            put("minimum", 1)
                            put("maximum", AiMusicCatalogRepository.MAX_RESULTS)
                            put("default", 10)
                        },
                    )
                },
            )
            put("required", JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("query"))))
            put("additionalProperties", false)
        }
    override val risk = AiToolRisk.READ_ONLY

    override suspend fun execute(
        arguments: JsonObject,
        context: AiToolContext,
    ): AiToolResult {
        val query = arguments["query"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty().take(MAX_QUERY_CHARS)
        if (query.isBlank()) return AiToolResult.Failure("invalid_arguments", "query must not be empty.")
        val limit =
            arguments["limit"]?.jsonPrimitive?.intOrNull
                ?.coerceIn(1, AiMusicCatalogRepository.MAX_RESULTS)
                ?: 10
        return catalogRepository.searchSongs(query, limit).fold(
            onSuccess = { songs ->
                context.artifacts.rememberSongs(songs)
                val compact =
                    buildJsonObject {
                        put("query", query)
                        put(
                            "items",
                            buildJsonArray {
                                songs.forEach { song ->
                                    add(
                                        buildJsonObject {
                                            put("id", song.id)
                                            put("title", song.title)
                                            put(
                                                "artists",
                                                buildJsonArray { song.artists.forEach { add(it.name) } },
                                            )
                                            song.album?.name?.let { put("album", it) }
                                            song.duration?.let { put("durationSeconds", it) }
                                        },
                                    )
                                }
                            },
                        )
                    }
                AiToolResult.Success(compact, AiToolPresentation.Songs(songs))
            },
            onFailure = {
                AiToolResult.Failure("catalog_search_failed", it.message ?: "Music search failed.")
            },
        )
    }

    companion object {
        const val MAX_QUERY_CHARS = 300
    }
}
