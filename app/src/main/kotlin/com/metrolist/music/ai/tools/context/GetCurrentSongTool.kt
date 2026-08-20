/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ai.tools.context

import com.metrolist.music.ai.model.AiToolContext
import com.metrolist.music.ai.tools.AiTool
import com.metrolist.music.ai.tools.AiToolResult
import com.metrolist.music.ai.tools.AiToolRisk
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject

class GetCurrentSongTool
@Inject
constructor() : AiTool {
    override val name = "get_current_song"
    override val description = "Returns the song that is currently active in MetroFuse+, if any."
    override val inputSchema =
        buildJsonObject {
            put("type", "object")
            put("properties", JsonObject(emptyMap()))
            put("additionalProperties", false)
        }
    override val risk = AiToolRisk.READ_ONLY

    override fun isAvailable(context: AiToolContext): Boolean = context.permissions.currentSong

    override suspend fun execute(
        arguments: JsonObject,
        context: AiToolContext,
    ): AiToolResult {
        if (!context.permissions.currentSong) {
            return AiToolResult.Failure("permission_denied", "Current song context is disabled.")
        }
        val song = context.currentMusic
            ?: return AiToolResult.Success(buildJsonObject { put("active", false) })
        return AiToolResult.Success(
            buildJsonObject {
                put("active", true)
                put("id", song.id)
                put("title", song.title)
                put("artists", kotlinx.serialization.json.buildJsonArray { song.artists.forEach { add(it) } })
                song.album?.let { put("album", it) }
                song.durationSeconds?.let { put("durationSeconds", it) }
                song.positionSeconds?.let { put("positionSeconds", it) }
                put("isPlaying", song.isPlaying)
            },
        )
    }
}
