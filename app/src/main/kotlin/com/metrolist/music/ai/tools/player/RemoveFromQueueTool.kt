/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ai.tools.player

import com.metrolist.music.ai.action.AiPendingAction
import com.metrolist.music.ai.action.AiQueueRemoval
import com.metrolist.music.ai.model.AiToolContext
import com.metrolist.music.ai.tools.AiConfirmableTool
import com.metrolist.music.ai.tools.AiToolPresentation
import com.metrolist.music.ai.tools.AiToolResult
import com.metrolist.music.ai.tools.AiToolRisk
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID
import javax.inject.Inject

class RemoveFromQueueTool
@Inject
constructor() : AiConfirmableTool {
    override val name = "remove_from_queue"
    override val description = "Removes queue positions from the current bounded queue snapshot after confirmation."
    override val inputSchema =
        buildJsonObject {
            put("type", "object")
            put(
                "properties",
                buildJsonObject {
                    put(
                        "positions",
                        buildJsonObject {
                            put("type", "array")
                            put("items", buildJsonObject { put("type", "integer"); put("minimum", 0) })
                            put("minItems", 1)
                            put("maxItems", 20)
                            put("uniqueItems", true)
                        },
                    )
                },
            )
            put("required", JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("positions"))))
            put("additionalProperties", false)
        }
    override val risk = AiToolRisk.REVERSIBLE

    override fun isAvailable(context: AiToolContext): Boolean = context.permissions.queue

    override suspend fun execute(arguments: JsonObject, context: AiToolContext): AiToolResult {
        val positions =
            runCatching {
                arguments["positions"]?.jsonArray.orEmpty().mapNotNull { it.jsonPrimitive.intOrNull }.distinct()
            }.getOrDefault(emptyList())
        if (positions.isEmpty()) {
            return AiToolResult.Failure("invalid_arguments", "At least one queue position is required.")
        }
        val byPosition = context.queue.associateBy { it.position }
        val entries =
            positions.mapNotNull { position ->
                byPosition[position]?.takeUnless { it.isCurrent }?.let {
                    AiQueueRemoval(position, it.id, it.title)
                }
            }
        if (entries.size != positions.size) {
            return AiToolResult.Failure(
                "stale_or_current_queue_item",
                "Every position must exist in the current snapshot and the currently playing item cannot be removed.",
            )
        }
        val action =
            context.artifacts.rememberAction(
                AiPendingAction.RemoveFromQueue("action_${UUID.randomUUID()}", entries),
            )
        return AiToolResult.Success(
            buildJsonObject {
                put("actionId", action.id)
                put("status", "pending_confirmation")
                put("itemCount", entries.size)
            },
            AiToolPresentation.Confirmation(action),
        )
    }
}
