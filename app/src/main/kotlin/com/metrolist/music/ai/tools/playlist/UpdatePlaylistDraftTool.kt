/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ai.tools.playlist

import com.metrolist.music.ai.action.AiPendingAction
import com.metrolist.music.ai.model.AiToolContext
import com.metrolist.music.ai.tools.AiConfirmableTool
import com.metrolist.music.ai.tools.AiToolPresentation
import com.metrolist.music.ai.tools.AiToolResult
import com.metrolist.music.ai.tools.AiToolRisk
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID
import javax.inject.Inject

class UpdatePlaylistDraftTool
@Inject
constructor() : AiConfirmableTool {
    override val name = "update_playlist_draft"
    override val description = "Updates the active or specified draft using only real song IDs observed in this session."
    override val inputSchema =
        buildJsonObject {
            put("type", "object")
            put(
                "properties",
                buildJsonObject {
                    put("draftId", buildJsonObject { put("type", "string"); put("maxLength", 512) })
                    put("title", buildJsonObject { put("type", "string"); put("maxLength", 100) })
                    put(
                        "songIds",
                        buildJsonObject {
                            put("type", "array")
                            put("items", buildJsonObject { put("type", "string"); put("maxLength", 512) })
                            put("minItems", 1)
                            put("maxItems", 60)
                            put("uniqueItems", true)
                        },
                    )
                    put("replace", buildJsonObject { put("type", "boolean") })
                },
            )
            put("required", JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("songIds"))))
            put("additionalProperties", false)
        }
    override val risk = AiToolRisk.REVERSIBLE

    override suspend fun execute(arguments: JsonObject, context: AiToolContext): AiToolResult {
        val requestedId = arguments["draftId"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
        val draft =
            if (requestedId != null) {
                context.artifacts.draft(requestedId)
                    ?: return AiToolResult.Failure("unknown_draft", "The requested playlist draft does not exist in this session.")
            } else {
                context.artifacts.activeDraft()
                    ?: return AiToolResult.Failure("unknown_draft", "There is no active playlist draft in this session.")
            }
        val ids = runCatching { arguments["songIds"]?.jsonArray.orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull } }
            .getOrDefault(emptyList())
        val resolution = context.artifacts.resolveSongs(ids)
        if (resolution.missingIds.isNotEmpty() || resolution.songs.isEmpty()) {
            return AiToolResult.Failure("unknown_song_ids", "Every song must come from a catalog tool in this session.")
        }
        val action =
            context.artifacts.rememberAction(
                AiPendingAction.UpdatePlaylistDraft(
                    id = "action_${UUID.randomUUID()}",
                    draftId = draft.id,
                    title = arguments["title"]?.jsonPrimitive?.contentOrNull?.let(::cleanTitle),
                    songs = resolution.songs,
                    replace = arguments["replace"]?.jsonPrimitive?.booleanOrNull ?: true,
                ),
            )
        return AiToolResult.Success(
            buildJsonObject {
                put("actionId", action.id)
                put("status", "pending_confirmation")
                put("draftId", draft.id)
            },
            AiToolPresentation.Confirmation(action),
        )
    }
}

private fun cleanTitle(value: String): String =
    value
        .asSequence()
        .filterNot(Char::isISOControl)
        .take(MAX_TITLE_CHARS)
        .joinToString("")
        .trim()

private const val MAX_TITLE_CHARS = 100
