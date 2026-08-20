/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ai.tools.player

import com.metrolist.music.ai.action.AiPendingAction
import com.metrolist.music.ai.model.AiToolContext
import com.metrolist.music.ai.tools.AiConfirmableTool
import com.metrolist.music.ai.tools.AiToolPresentation
import com.metrolist.music.ai.tools.AiToolResult
import com.metrolist.music.ai.tools.AiToolRisk
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import javax.inject.Inject

class PlaySongTool
@Inject
constructor() : AiConfirmableTool {
    override val name = "play_song"
    override val description =
        "Prepares playback of one real song returned by a tool in this session. Playback starts only after user confirmation."
    override val inputSchema = songIdSchema()
    override val risk = AiToolRisk.REVERSIBLE

    override suspend fun execute(
        arguments: JsonObject,
        context: AiToolContext,
    ): AiToolResult =
        resolveObservedSong(arguments, context)?.let { song ->
            val action = context.artifacts.createPlaySongAction(song)
            pendingActionResult(action)
        } ?: AiToolResult.Failure(
            "unknown_song_id",
            "The song ID was not returned by a tool in this session.",
        )
}

class StartRadioTool
@Inject
constructor() : AiConfirmableTool {
    override val name = "start_radio"
    override val description =
        "Prepares a MetroFuse+ radio from one real song returned by a tool in this session. Playback starts only after user confirmation."
    override val inputSchema = songIdSchema()
    override val risk = AiToolRisk.REVERSIBLE

    override suspend fun execute(
        arguments: JsonObject,
        context: AiToolContext,
    ): AiToolResult =
        resolveObservedSong(arguments, context)?.let { song ->
            val action = context.artifacts.createStartRadioAction(song)
            pendingActionResult(action)
        } ?: AiToolResult.Failure(
            "unknown_song_id",
            "The song ID was not returned by a tool in this session.",
        )
}

private fun resolveObservedSong(
    arguments: JsonObject,
    context: AiToolContext,
) =
    arguments["songId"]
        ?.jsonPrimitive
        ?.contentOrNull
        ?.trim()
        .orEmpty()
        .take(MAX_ID_CHARS)
        .takeIf(String::isNotBlank)
        ?.let { id ->
            val resolution = context.artifacts.resolveSongs(listOf(id))
            if (resolution.missingIds.isEmpty()) {
                resolution.songs.singleOrNull()
            } else {
                null
            }
        }

private fun pendingActionResult(action: AiPendingAction) =
    AiToolResult.Success(
        payload =
            buildJsonObject {
                put("actionId", action.id)
                put("status", "pending_confirmation")
            },
        presentation = AiToolPresentation.Confirmation(action),
    )

private fun songIdSchema() =
    buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                put(
                    "songId",
                    buildJsonObject {
                        put("type", "string")
                        put("minLength", 1)
                        put("maxLength", MAX_ID_CHARS)
                    },
                )
            },
        )
        put("required", JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("songId"))))
        put("additionalProperties", false)
    }

private const val MAX_ID_CHARS = 512
