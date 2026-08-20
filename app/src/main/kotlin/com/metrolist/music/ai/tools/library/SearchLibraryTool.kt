/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ai.tools.library

import com.metrolist.music.ai.core.runCatchingPreservingCancellation
import com.metrolist.music.ai.model.AiToolContext
import com.metrolist.music.ai.repository.AiUserContextRepository
import com.metrolist.music.ai.tools.AiTool
import com.metrolist.music.ai.tools.AiToolPresentation
import com.metrolist.music.ai.tools.AiToolResult
import com.metrolist.music.ai.tools.AiToolRisk
import com.metrolist.music.ai.tools.search.SearchMusicTool
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import javax.inject.Inject

class SearchLibraryTool
@Inject
constructor(
    private val repository: AiUserContextRepository,
) : AiTool {
    override val name = "search_library"
    override val description =
        "Searches a bounded subset of songs saved in the user's local library and returns real song IDs."
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
                            put("minLength", 1)
                            put("maxLength", SearchMusicTool.MAX_QUERY_CHARS)
                        },
                    )
                    put(
                        "limit",
                        buildJsonObject {
                            put("type", "integer")
                            put("minimum", 1)
                            put("maximum", AiUserContextRepository.MAX_RESULTS)
                            put("default", DEFAULT_LIBRARY_LIMIT)
                        },
                    )
                },
            )
            put("required", JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("query"))))
            put("additionalProperties", false)
        }
    override val risk = AiToolRisk.READ_ONLY

    override fun isAvailable(context: AiToolContext): Boolean = context.permissions.library

    override suspend fun execute(
        arguments: JsonObject,
        context: AiToolContext,
    ): AiToolResult {
        if (!context.permissions.library) {
            return AiToolResult.Failure("permission_denied", "Library search context is disabled.")
        }
        val query =
            arguments["query"]
                ?.jsonPrimitive
                ?.contentOrNull
                ?.trim()
                .orEmpty()
                .take(SearchMusicTool.MAX_QUERY_CHARS)
        if (query.isBlank()) return AiToolResult.Failure("invalid_arguments", "query must not be empty.")
        val limit =
            arguments["limit"]
                ?.jsonPrimitive
                ?.intOrNull
                ?.coerceIn(1, AiUserContextRepository.MAX_RESULTS)
                ?: DEFAULT_LIBRARY_LIMIT
        return runCatchingPreservingCancellation { repository.searchLibrary(query, limit) }.fold(
            onSuccess = { songs ->
                context.artifacts.rememberSongs(songs)
                AiToolResult.Success(
                    payload =
                        buildJsonObject {
                            put("query", query)
                            put("items", songsPayload(songs))
                        },
                    presentation = AiToolPresentation.Songs(songs),
                )
            },
            onFailure = {
                AiToolResult.Failure("library_search_failed", it.message ?: "Library search failed.")
            },
        )
    }
}
