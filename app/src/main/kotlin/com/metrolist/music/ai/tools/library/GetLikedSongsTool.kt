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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import javax.inject.Inject

class GetLikedSongsTool
@Inject
constructor(
    private val repository: AiUserContextRepository,
) : AiTool {
    override val name = "get_liked_songs"
    override val description = "Returns a small page of the user's most recently liked songs."
    override val inputSchema = paginationSchema()
    override val risk = AiToolRisk.READ_ONLY

    override fun isAvailable(context: AiToolContext): Boolean = context.permissions.likedSongs

    override suspend fun execute(
        arguments: JsonObject,
        context: AiToolContext,
    ): AiToolResult {
        if (!context.permissions.likedSongs) {
            return AiToolResult.Failure("permission_denied", "Liked songs context is disabled.")
        }
        val limit =
            arguments["limit"]?.jsonPrimitive?.intOrNull
                ?.coerceIn(1, AiUserContextRepository.MAX_RESULTS)
                ?: DEFAULT_LIBRARY_LIMIT
        val offset =
            arguments["offset"]?.jsonPrimitive?.intOrNull
                ?.coerceIn(0, AiUserContextRepository.MAX_OFFSET)
                ?: 0
        return runCatchingPreservingCancellation { repository.likedSongs(limit, offset) }.fold(
            onSuccess = { songs ->
                context.artifacts.rememberSongs(songs)
                AiToolResult.Success(
                    buildJsonObject {
                        put("offset", offset)
                        put("items", songsPayload(songs))
                    },
                )
            },
            onFailure = {
                AiToolResult.Failure("library_read_failed", it.message ?: "Liked songs could not be read.")
            },
        )
    }
}
