/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ai.tools.library

import com.metrolist.music.ai.model.AiToolContext
import com.metrolist.music.ai.core.runCatchingPreservingCancellation
import com.metrolist.music.ai.repository.AiUserContextRepository
import com.metrolist.music.ai.tools.AiTool
import com.metrolist.music.ai.tools.AiToolResult
import com.metrolist.music.ai.tools.AiToolRisk
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import javax.inject.Inject

class GetPlaylistTool
@Inject
constructor(
    private val repository: AiUserContextRepository,
) : AiTool {
    override val name = "get_playlist"
    override val description =
        "Returns a bounded page of songs from a playlist ID previously returned by get_playlists."
    override val inputSchema =
        buildJsonObject {
            put("type", "object")
            put(
                "properties",
                buildJsonObject {
                    put(
                        "playlistId",
                        buildJsonObject {
                            put("type", "string")
                            put("minLength", 1)
                            put("maxLength", 512)
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
                    put(
                        "offset",
                        buildJsonObject {
                            put("type", "integer")
                            put("minimum", 0)
                            put("maximum", AiUserContextRepository.MAX_OFFSET)
                            put("default", 0)
                        },
                    )
                },
            )
            put("required", JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("playlistId"))))
            put("additionalProperties", false)
        }
    override val risk = AiToolRisk.READ_ONLY

    override fun isAvailable(context: AiToolContext): Boolean = context.permissions.playlists

    override suspend fun execute(
        arguments: JsonObject,
        context: AiToolContext,
    ): AiToolResult {
        if (!context.permissions.playlists) {
            return AiToolResult.Failure("permission_denied", "Playlists context is disabled.")
        }
        val playlistId = arguments["playlistId"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty().take(512)
        if (playlistId.isBlank()) {
            return AiToolResult.Failure("invalid_arguments", "playlistId must not be empty.")
        }
        if (!context.artifacts.knowsPlaylist(playlistId)) {
            return AiToolResult.Failure(
                "unknown_playlist_id",
                "The playlist ID was not returned by get_playlists in this session.",
            )
        }
        val limit =
            arguments["limit"]?.jsonPrimitive?.intOrNull
                ?.coerceIn(1, AiUserContextRepository.MAX_RESULTS)
                ?: DEFAULT_LIBRARY_LIMIT
        val offset =
            arguments["offset"]?.jsonPrimitive?.intOrNull
                ?.coerceIn(0, AiUserContextRepository.MAX_OFFSET)
                ?: 0
        return runCatchingPreservingCancellation { repository.playlistSongs(playlistId, limit, offset) }.fold(
            onSuccess = { songs ->
                context.artifacts.rememberSongs(songs)
                AiToolResult.Success(
                    buildJsonObject {
                        put("playlistId", playlistId)
                        put("offset", offset)
                        put("items", songsPayload(songs))
                    },
                )
            },
            onFailure = {
                AiToolResult.Failure("library_read_failed", it.message ?: "Playlist songs could not be read.")
            },
        )
    }
}
