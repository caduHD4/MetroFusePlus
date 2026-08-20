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
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import javax.inject.Inject

class GetQueueTool
@Inject
constructor() : AiTool {
    override val name = "get_queue"
    override val description = "Returns a bounded snapshot of the active MetroFuse+ playback queue."
    override val inputSchema =
        buildJsonObject {
            put("type", "object")
            put(
                "properties",
                buildJsonObject {
                    put("offset", integerProperty(0, MAX_QUEUE_ITEMS, 0))
                    put("limit", integerProperty(1, MAX_QUEUE_ITEMS, DEFAULT_LIMIT))
                },
            )
            put("additionalProperties", false)
        }
    override val risk = AiToolRisk.READ_ONLY

    override fun isAvailable(context: AiToolContext): Boolean = context.permissions.queue

    override suspend fun execute(
        arguments: JsonObject,
        context: AiToolContext,
    ): AiToolResult {
        if (!context.permissions.queue) {
            return AiToolResult.Failure("permission_denied", "Queue context is disabled.")
        }
        val offset = arguments["offset"]?.jsonPrimitive?.intOrNull?.coerceIn(0, MAX_QUEUE_ITEMS) ?: 0
        val limit =
            arguments["limit"]?.jsonPrimitive?.intOrNull?.coerceIn(1, MAX_QUEUE_ITEMS)
                ?: DEFAULT_LIMIT
        val items = context.queue.drop(offset).take(limit)
        return AiToolResult.Success(
            buildJsonObject {
                put("total", context.queueTotal)
                put("snapshotCount", context.queue.size)
                put("snapshotStartIndex", context.queue.firstOrNull()?.position ?: 0)
                put("snapshotLimited", context.queueTotal > context.queue.size)
                put("offset", offset)
                put(
                    "items",
                    buildJsonArray {
                        items.forEach { item ->
                            add(
                                buildJsonObject {
                                    put("id", item.id)
                                    put("title", item.title)
                                    put("artists", buildJsonArray { item.artists.forEach { add(it) } })
                                    item.album?.let { put("album", it) }
                                    item.durationSeconds?.let { put("durationSeconds", it) }
                                    put("queueIndex", item.position)
                                    put("isCurrent", item.isCurrent)
                                },
                            )
                        }
                    },
                )
            },
        )
    }

    private fun integerProperty(
        minimum: Int,
        maximum: Int,
        default: Int,
    ) = buildJsonObject {
        put("type", "integer")
        put("minimum", minimum)
        put("maximum", maximum)
        put("default", default)
    }

    companion object {
        const val MAX_QUEUE_ITEMS = 50
        private const val DEFAULT_LIMIT = 20
    }
}
