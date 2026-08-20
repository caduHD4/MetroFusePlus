/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ai.tools

import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.music.ai.action.AiPendingAction
import com.metrolist.music.ai.model.AiPendingToolCall
import com.metrolist.music.ai.model.AiToolContext
import com.metrolist.music.ai.model.AiToolDefinition
import com.metrolist.music.ai.playlist.AiPlaylistDraft
import com.metrolist.music.ai.repository.AiLibraryPlaylist
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

interface AiTool {
    val name: String
    val description: String
    val inputSchema: JsonObject
    val risk: AiToolRisk

    suspend fun execute(
        arguments: JsonObject,
        context: AiToolContext,
    ): AiToolResult

    fun isAvailable(context: AiToolContext): Boolean = true

    fun definition(): AiToolDefinition = AiToolDefinition(name, description, inputSchema)
}

interface AiConfirmableTool : AiTool

enum class AiToolRisk {
    READ_ONLY,
    REVERSIBLE,
    SIDE_EFFECT,
    DESTRUCTIVE,
}

sealed interface AiToolPresentation {
    data class Songs(
        val items: List<SongItem>,
    ) : AiToolPresentation

    data class Albums(
        val items: List<AlbumItem>,
    ) : AiToolPresentation

    data class Artists(
        val items: List<ArtistItem>,
    ) : AiToolPresentation

    data class Playlists(
        val items: List<AiLibraryPlaylist>,
    ) : AiToolPresentation

    data class PlaylistDraft(
        val draft: AiPlaylistDraft,
    ) : AiToolPresentation

    data class Confirmation(
        val action: AiPendingAction,
    ) : AiToolPresentation
}

sealed interface AiToolResult {
    data class Success(
        val payload: JsonElement,
        val presentation: AiToolPresentation? = null,
    ) : AiToolResult

    data class Failure(
        val code: String,
        val message: String,
    ) : AiToolResult

    fun payloadForModel(): JsonElement =
        when (this) {
            is Success ->
                buildJsonObject {
                    put("success", true)
                    put("data", payload)
                }
            is Failure ->
                buildJsonObject {
                    put("success", false)
                    put(
                        "error",
                        buildJsonObject {
                            put("code", code)
                            put("message", message)
                        },
                    )
                }
        }
}

data class AiToolExecution(
    val call: AiPendingToolCall,
    val result: AiToolResult,
)
