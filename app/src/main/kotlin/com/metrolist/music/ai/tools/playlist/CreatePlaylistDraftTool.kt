/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ai.tools.playlist

import com.metrolist.music.ai.model.AiToolContext
import com.metrolist.music.ai.playlist.AiPlaylistIntent
import com.metrolist.music.ai.playlist.AiPlaylistIntentType
import com.metrolist.music.ai.playlist.AiPlaylistRanker
import com.metrolist.music.ai.playlist.AiSessionArtifacts
import com.metrolist.music.ai.tools.AiConfirmableTool
import com.metrolist.music.ai.tools.AiToolPresentation
import com.metrolist.music.ai.tools.AiToolResult
import com.metrolist.music.ai.tools.AiToolRisk
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import javax.inject.Inject

class CreatePlaylistDraftTool
@Inject
constructor(
    private val ranker: AiPlaylistRanker,
) : AiConfirmableTool {
    override val name = "create_playlist_draft"
    override val description =
        "Plans a user-confirmed playlist with a typed musical intent. For an exact artist provide artistName; for concepts provide focused seed queries. Song IDs must come from catalog tools."
    override val inputSchema =
        buildJsonObject {
            put("type", "object")
            put(
                "properties",
                buildJsonObject {
                    put("title", buildJsonObject { put("type", "string"); put("minLength", 1); put("maxLength", 100) })
                    put("description", buildJsonObject { put("type", "string"); put("maxLength", 300) })
                    put(
                        "intentType",
                        buildJsonObject {
                            put("type", "string")
                            put(
                                "enum",
                                JsonArray(AiPlaylistIntentType.entries.map { kotlinx.serialization.json.JsonPrimitive(it.name.lowercase()) }),
                            )
                        },
                    )
                    put("artistName", buildJsonObject { put("type", "string"); put("maxLength", 120) })
                    put("era", buildJsonObject { put("type", "string"); put("maxLength", 80) })
                    put(
                        "exclusions",
                        buildJsonObject {
                            put("type", "array")
                            put("items", buildJsonObject { put("type", "string"); put("maxLength", 80) })
                            put("maxItems", MAX_EXCLUSIONS)
                            put("uniqueItems", true)
                        },
                    )
                    put(
                        "targetCount",
                        buildJsonObject {
                            put("type", "integer")
                            put("minimum", 1)
                            put("maximum", AiSessionArtifacts.MAX_CANDIDATE_POOL)
                        },
                    )
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
                            put("maxItems", AiSessionArtifacts.MAX_CANDIDATE_POOL)
                            put("uniqueItems", true)
                        },
                    )
                    put(
                        "queries",
                        buildJsonObject {
                            put("type", "array")
                            put(
                                "items",
                                buildJsonObject {
                                    put("type", "string")
                                    put("minLength", 1)
                                    put("maxLength", 180)
                                },
                            )
                            put("minItems", 1)
                            put("maxItems", MAX_INITIAL_QUERIES)
                            put("uniqueItems", true)
                        },
                    )
                },
            )
            put(
                "required",
                JsonArray(
                    listOf(
                        kotlinx.serialization.json.JsonPrimitive("title"),
                        kotlinx.serialization.json.JsonPrimitive("targetCount"),
                    ),
                ),
            )
            put("additionalProperties", false)
        }
    override val risk = AiToolRisk.REVERSIBLE

    override suspend fun execute(
        arguments: JsonObject,
        context: AiToolContext,
    ): AiToolResult {
        val title = cleanGeneratedText(arguments["title"]?.jsonPrimitive?.contentOrNull.orEmpty(), 100)
        if (title.isBlank()) return AiToolResult.Failure("invalid_arguments", "title must not be empty.")
        val songIds =
            runCatching {
                arguments["songIds"]
                    ?.jsonArray
                    .orEmpty()
                    .take(AiSessionArtifacts.MAX_CANDIDATE_POOL)
                    .mapNotNull { it.jsonPrimitive.contentOrNull }
            }.getOrDefault(emptyList())
        val queries =
            runCatching {
                arguments["queries"]
                    ?.jsonArray
                    .orEmpty()
                    .mapNotNull { it.jsonPrimitive.contentOrNull }
                    .map { cleanGeneratedText(it, 180) }
                    .filter(String::isNotBlank)
                    .distinct()
                    .take(MAX_INITIAL_QUERIES)
            }.getOrDefault(emptyList())
        val artistName =
            arguments["artistName"]?.jsonPrimitive?.contentOrNull
                ?.let { cleanGeneratedText(it, 120) }
                ?.takeIf(String::isNotBlank)
        if (songIds.isNotEmpty() && (queries.isNotEmpty() || artistName != null)) {
            return AiToolResult.Failure("invalid_arguments", "Use either real song IDs or a catalog search strategy.")
        }
        if (songIds.isEmpty() && queries.isEmpty() && artistName == null) {
            return AiToolResult.Failure("invalid_arguments", "Provide songIds, artistName, or at least one seed query.")
        }

        val requestedCount =
            arguments["targetCount"]?.jsonPrimitive?.intOrNull
                ?.coerceIn(1, AiSessionArtifacts.MAX_CANDIDATE_POOL)
                ?: songIds.size.coerceAtLeast(1)
        val intent =
            AiPlaylistIntent(
                title = title,
                description =
                    arguments["description"]
                        ?.jsonPrimitive
                        ?.contentOrNull
                        ?.let { cleanGeneratedText(it, 300) }
                        ?.takeIf(String::isNotBlank),
                targetCount = requestedCount,
                type = parseIntentType(arguments["intentType"]?.jsonPrimitive?.contentOrNull, artistName),
                artistName = artistName,
                era =
                    arguments["era"]?.jsonPrimitive?.contentOrNull
                        ?.let { cleanGeneratedText(it, 80) }
                        ?.takeIf(String::isNotBlank),
                exclusions =
                    runCatching {
                        arguments["exclusions"]
                            ?.jsonArray
                            .orEmpty()
                            .mapNotNull { it.jsonPrimitive.contentOrNull }
                            .map { cleanGeneratedText(it, 80) }
                            .filter(String::isNotBlank)
                            .distinct()
                            .take(MAX_EXCLUSIONS)
                    }.getOrDefault(emptyList()),
            )
        if (songIds.isEmpty()) {
            val action =
                context.artifacts.rememberAction(
                    com.metrolist.music.ai.action.AiPendingAction.BuildPlaylistDraft(
                        id = "action_${java.util.UUID.randomUUID()}",
                        intent = intent,
                        queries = queries,
                    ),
                )
            return AiToolResult.Success(
                payload =
                    buildJsonObject {
                        put("actionId", action.id)
                        put("status", "pending_confirmation")
                        put("queryCount", action.queries.size)
                        put("targetCount", action.intent.targetCount)
                    },
                presentation = AiToolPresentation.Confirmation(action),
            )
        }

        val resolution = context.artifacts.resolveSongs(songIds)
        if (resolution.missingIds.isNotEmpty()) {
            return AiToolResult.Failure(
                "unknown_song_ids",
                "Some song IDs were not returned by catalog tools in this session.",
            )
        }
        val songs = ranker.finalizeSelection(resolution.songs, requestedCount, intent)
        if (songs.isEmpty()) return AiToolResult.Failure("empty_draft", "No validated songs remain for the draft.")
        val action = context.artifacts.createPlaylistDraftAction(intent, songs)
        return AiToolResult.Success(
            payload =
                buildJsonObject {
                    put("actionId", action.id)
                    put("status", "pending_confirmation")
                    put("title", action.intent.title)
                    put("songCount", action.songs.size)
                },
            presentation = AiToolPresentation.Confirmation(action),
        )
    }
}

private fun cleanGeneratedText(
    value: String,
    maximumLength: Int,
): String =
    value
        .asSequence()
        .filterNot { it.isISOControl() }
        .take(maximumLength)
        .joinToString("")
        .trim()

private fun parseIntentType(
    value: String?,
    artistName: String?,
): AiPlaylistIntentType {
    if (artistName != null) return AiPlaylistIntentType.ARTIST
    return AiPlaylistIntentType.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
        ?: AiPlaylistIntentType.CONCEPT
}

private const val MAX_ID_CHARS = 512
private const val MAX_INITIAL_QUERIES = 12
private const val MAX_EXCLUSIONS = 12
