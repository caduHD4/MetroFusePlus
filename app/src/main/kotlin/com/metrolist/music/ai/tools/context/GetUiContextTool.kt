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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject

class GetUiContextTool
@Inject
constructor() : AiTool {
    override val name = "get_ui_context"
    override val description = "Returns the MetroFuse+ screen from which the assistant was opened, using only bounded route context."
    override val inputSchema =
        buildJsonObject {
            put("type", "object")
            put("properties", JsonObject(emptyMap()))
            put("additionalProperties", false)
        }
    override val risk = AiToolRisk.READ_ONLY

    override suspend fun execute(arguments: JsonObject, context: AiToolContext): AiToolResult =
        AiToolResult.Success(
            buildJsonObject {
                val ui = context.uiContext
                put("type", ui?.type?.name?.lowercase() ?: "none")
                ui?.resourceId?.let { put("resourceId", it) }
                ui?.query?.let { put("query", it) }
            },
        )
}
