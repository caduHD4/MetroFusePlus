/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ai.tools.library

import com.metrolist.music.ai.model.AiToolContext
import com.metrolist.music.ai.core.runCatchingPreservingCancellation
import com.metrolist.music.ai.repository.AiUserContextRepository
import com.metrolist.music.ai.tools.AiTool
import com.metrolist.music.ai.tools.AiToolPresentation
import com.metrolist.music.ai.tools.AiToolResult
import com.metrolist.music.ai.tools.AiToolRisk
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import javax.inject.Inject

class GetPlaylistsTool
@Inject
constructor(
    private val repository: AiUserContextRepository,
) : AiTool {
    override val name = "get_playlists"
    override val description = "Returns a small page of the user's local playlist summaries and real playlist IDs."
    override val inputSchema = paginationSchema()
    override val risk = AiToolRisk.READ_ONLY

    override fun isAvailable(context: AiToolContext): Boolean = context.permissions.playlists

    override suspend fun execute(
        arguments: JsonObject,
        context: AiToolContext,
    ): AiToolResult {
        if (!context.permissions.playlists) {
            return AiToolResult.Failure("permission_denied", "Playlists context is disabled.")
        }
        val limit =
            arguments["limit"]?.jsonPrimitive?.intOrNull
                ?.coerceIn(1, AiUserContextRepository.MAX_RESULTS)
                ?: DEFAULT_LIBRARY_LIMIT
        val offset =
            arguments["offset"]?.jsonPrimitive?.intOrNull
                ?.coerceIn(0, AiUserContextRepository.MAX_OFFSET)
                ?: 0
        return runCatchingPreservingCancellation { repository.playlists(limit, offset) }.fold(
            onSuccess = { playlists ->
                context.artifacts.rememberPlaylistIds(playlists.map { it.id })
                AiToolResult.Success(
                    payload =
                        buildJsonObject {
                            put("offset", offset)
                            put(
                                "items",
                                buildJsonArray {
                                    playlists.forEach { playlist ->
                                        add(
                                            buildJsonObject {
                                                put("id", playlist.id)
                                                put("name", playlist.name)
                                                put("songCount", playlist.songCount)
                                            },
                                        )
                                    }
                                },
                            )
                        },
                    presentation = AiToolPresentation.Playlists(playlists),
                )
            },
            onFailure = {
                AiToolResult.Failure("library_read_failed", it.message ?: "Playlists could not be read.")
            },
        )
    }
}
