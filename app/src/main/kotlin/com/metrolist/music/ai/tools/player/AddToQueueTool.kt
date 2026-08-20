/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ai.tools.player

import com.metrolist.music.ai.action.AiQueueInsertion
import com.metrolist.music.ai.model.AiToolContext
import com.metrolist.music.ai.tools.AiConfirmableTool
import com.metrolist.music.ai.tools.AiToolPresentation
import com.metrolist.music.ai.tools.AiToolResult
import com.metrolist.music.ai.tools.AiToolRisk
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import javax.inject.Inject

class AddToQueueTool
@Inject
constructor() : AiConfirmableTool {
    override val name = "add_to_queue"
    override val description =
        "Prepares a user-confirmed queue change using real song IDs returned by tools in this session. It never changes playback by itself."
    override val inputSchema =
        buildJsonObject {
            put("type", "object")
            put(
                "properties",
                buildJsonObject {
                    put(
                        "songIds",
                        buildJsonObject {
                            put("type", "array")
                            put(
                                "items",
                                buildJsonObject {
                                    put("type", "string")
                                    put("minLength", 1)
                                    put("maxLength", MAX_ID_CHARS)
                                },
                            )
                            put("minItems", 1)
                            put("maxItems", MAX_QUEUE_ACTION_SONGS)
                            put("uniqueItems", true)
                        },
                    )
                    put(
                        "position",
                        buildJsonObject {
                            put("type", "string")
                            put(
                                "enum",
                                JsonArray(
                                    listOf(
                                        kotlinx.serialization.json.JsonPrimitive("next"),
                                        kotlinx.serialization.json.JsonPrimitive("end"),
                                    ),
                                ),
                            )
                            put("default", "end")
                        },
                    )
                },
            )
            put("required", JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("songIds"))))
            put("additionalProperties", false)
        }
    override val risk = AiToolRisk.REVERSIBLE

    override suspend fun execute(
        arguments: JsonObject,
        context: AiToolContext,
    ): AiToolResult {
        val songIds =
            runCatching {
                arguments["songIds"]
                    ?.jsonArray
                    .orEmpty()
                    .take(MAX_QUEUE_ACTION_SONGS)
                    .mapNotNull { it.jsonPrimitive.contentOrNull }
                    .distinct()
            }.getOrDefault(emptyList())
        if (songIds.isEmpty()) {
            return AiToolResult.Failure("invalid_arguments", "songIds must not be empty.")
        }
        val resolution = context.artifacts.resolveSongs(songIds)
        if (resolution.missingIds.isNotEmpty()) {
            return AiToolResult.Failure(
                "unknown_song_ids",
                "Some song IDs were not returned by catalog or library tools in this session.",
            )
        }
        val insertion =
            when (arguments["position"]?.jsonPrimitive?.contentOrNull?.lowercase()) {
                "next" -> AiQueueInsertion.NEXT
                else -> AiQueueInsertion.END
            }
        val action = context.artifacts.createQueueAction(resolution.songs, insertion)
        return AiToolResult.Success(
            payload =
                buildJsonObject {
                    put("actionId", action.id)
                    put("status", "pending_confirmation")
                    put("songCount", action.songs.size)
                    put("position", insertion.name.lowercase())
                },
            presentation = AiToolPresentation.Confirmation(action),
        )
    }

    companion object {
        const val MAX_QUEUE_ACTION_SONGS = 20
        private const val MAX_ID_CHARS = 512
    }
}
