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
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import javax.inject.Inject

class GetRecentHistoryTool
@Inject
constructor(
    private val repository: AiUserContextRepository,
) : AiTool {
    override val name = "get_recently_played"
    override val description = "Returns a bounded, de-duplicated sample of the user's recent listening history."
    override val inputSchema =
        buildJsonObject {
            put("type", "object")
            put(
                "properties",
                buildJsonObject {
                    put(
                        "limit",
                        buildJsonObject {
                            put("type", "integer")
                            put("minimum", 1)
                            put("maximum", AiUserContextRepository.MAX_RESULTS)
                            put("default", DEFAULT_LIBRARY_LIMIT)
                        },
                    )
                    put(
                        "days",
                        buildJsonObject {
                            put("type", "integer")
                            put("minimum", 1)
                            put("maximum", AiUserContextRepository.MAX_HISTORY_DAYS)
                            put("default", DEFAULT_HISTORY_DAYS)
                        },
                    )
                },
            )
            put("additionalProperties", false)
        }
    override val risk = AiToolRisk.READ_ONLY

    override fun isAvailable(context: AiToolContext): Boolean = context.permissions.history

    override suspend fun execute(
        arguments: JsonObject,
        context: AiToolContext,
    ): AiToolResult {
        if (!context.permissions.history) {
            return AiToolResult.Failure("permission_denied", "Listening history context is disabled.")
        }
        val limit =
            arguments["limit"]?.jsonPrimitive?.intOrNull
                ?.coerceIn(1, AiUserContextRepository.MAX_RESULTS)
                ?: DEFAULT_LIBRARY_LIMIT
        val days =
            arguments["days"]?.jsonPrimitive?.intOrNull
                ?.coerceIn(1, AiUserContextRepository.MAX_HISTORY_DAYS)
                ?: DEFAULT_HISTORY_DAYS
        return runCatchingPreservingCancellation { repository.recentlyPlayed(limit, days) }.fold(
            onSuccess = { entries ->
                context.artifacts.rememberSongs(entries.map { it.song })
                AiToolResult.Success(
                    buildJsonObject {
                        put("days", days)
                        put(
                            "items",
                            buildJsonArray {
                                entries.forEachIndexed { index, entry ->
                                    add(
                                        buildJsonObject {
                                            put("id", entry.song.id)
                                            put("title", entry.song.title)
                                            put(
                                                "artists",
                                                    buildJsonArray { entry.song.artists.forEach { add(it.name) } },
                                            )
                                            put("recentIndex", index)
                                            put("listenedSeconds", entry.listenedSeconds)
                                        },
                                    )
                                }
                            },
                        )
                    },
                )
            },
            onFailure = {
                AiToolResult.Failure("history_read_failed", it.message ?: "Listening history could not be read.")
            },
        )
    }

    companion object {
        private const val DEFAULT_HISTORY_DAYS = 7
    }
}
