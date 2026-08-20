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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import javax.inject.Inject

class GetRelatedSongsTool
@Inject
constructor(
    private val catalogRepository: AiMusicCatalogRepository,
) : AiTool {
    override val name = "get_related_songs"
    override val description =
        "Returns real YouTube Music recommendations related to the current song or to a song ID returned by a tool in this session."
    override val inputSchema =
        buildJsonObject {
            put("type", "object")
            put(
                "properties",
                buildJsonObject {
                    put(
                        "seedSongId",
                        buildJsonObject {
                            put("type", "string")
                            put("maxLength", MAX_ID_CHARS)
                            put("description", "Optional real song ID. Omit it to use the active song.")
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
            put("additionalProperties", false)
        }
    override val risk = AiToolRisk.READ_ONLY

    override suspend fun execute(
        arguments: JsonObject,
        context: AiToolContext,
    ): AiToolResult {
        val requestedId =
            arguments["seedSongId"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty().take(MAX_ID_CHARS)
        val seedSongId =
            requestedId.ifBlank {
                if (context.permissions.currentSong) context.currentMusic?.id.orEmpty() else ""
            }
        if (seedSongId.isBlank()) {
            return AiToolResult.Failure(
                "missing_seed_song",
                "Provide a song returned by a tool or allow current-song context.",
            )
        }
        val isCurrentSong =
            context.permissions.currentSong && context.currentMusic?.id == seedSongId
        val isObservedSong =
            context.artifacts.resolveSongs(listOf(seedSongId)).missingIds.isEmpty()
        if (!isCurrentSong && !isObservedSong) {
            return AiToolResult.Failure(
                "unknown_song_id",
                "The seed song ID was not returned by a tool in this session.",
            )
        }
        val limit =
            arguments["limit"]?.jsonPrimitive?.intOrNull
                ?.coerceIn(1, AiMusicCatalogRepository.MAX_RESULTS)
                ?: DEFAULT_LIMIT
        return catalogRepository.relatedSongs(seedSongId, limit).fold(
            onSuccess = { songs ->
                context.artifacts.rememberSongs(songs)
                AiToolResult.Success(
                    payload =
                        buildJsonObject {
                            put("seedSongId", seedSongId)
                            put("items", com.metrolist.music.ai.tools.library.songsPayload(songs))
                        },
                    presentation = AiToolPresentation.Songs(songs),
                )
            },
            onFailure = {
                AiToolResult.Failure("related_search_failed", it.message ?: "Related songs could not be loaded.")
            },
        )
    }

    companion object {
        private const val MAX_ID_CHARS = 512
        private const val DEFAULT_LIMIT = 12
    }
}
