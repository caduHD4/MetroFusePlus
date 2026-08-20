/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ai.playlist

import com.metrolist.innertube.models.SongItem
import com.metrolist.music.ai.core.AiError
import com.metrolist.music.ai.core.AiProviderException
import com.metrolist.music.ai.core.AiStreamEvent
import com.metrolist.music.ai.core.retryDelaySeconds
import com.metrolist.music.ai.core.runCatchingPreservingCancellation
import com.metrolist.music.ai.model.AiConversationMessage
import com.metrolist.music.ai.model.AiProviderConfig
import com.metrolist.music.ai.model.AiRequest
import com.metrolist.music.ai.model.AiToolDefinition
import com.metrolist.music.ai.provider.AiProviderRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import javax.inject.Inject

class AiPlaylistCurator
@Inject
constructor(
    private val providerRegistry: AiProviderRegistry,
) {
    suspend fun select(
        config: AiProviderConfig,
        intent: AiPlaylistIntent,
        candidates: List<SongItem>,
    ): Result<AiPlaylistSelection> =
        runCatchingPreservingCancellation {
            require(candidates.isNotEmpty()) { "The candidate pool is empty." }
            val provider = providerRegistry.requireProvider(config.providerId)
            val request = selectionRequest(intent, candidates)
            var attempt = 0
            while (true) {
                var providerError: AiError? = null
                var sawOutput = false
                var selectionArguments: JsonObject? = null
                provider.streamResponse(request, config).collect { event ->
                    when (event) {
                        is AiStreamEvent.TextDelta,
                        is AiStreamEvent.ToolCallStarted,
                        -> sawOutput = true
                        is AiStreamEvent.ToolCallCompleted -> {
                            sawOutput = true
                            if (event.call.name == SELECTION_TOOL_NAME && selectionArguments == null) {
                                selectionArguments = event.call.arguments
                            }
                        }
                        is AiStreamEvent.Error -> providerError = event.error
                        is AiStreamEvent.Completed,
                        is AiStreamEvent.Status,
                        is AiStreamEvent.ToolCallArgumentsDelta,
                        is AiStreamEvent.Usage,
                        -> Unit
                    }
                }
                providerError?.let { error ->
                    val retryDelay = error.retryDelaySeconds(attempt, sawOutput)
                    if (retryDelay != null) {
                        attempt++
                        delay(retryDelay * 1000L)
                        return@let
                    }
                    throw AiProviderException(error)
                }
                if (providerError != null) continue
                return@runCatchingPreservingCancellation resolveSelection(
                    arguments = selectionArguments ?: error("The model did not return a typed playlist selection."),
                    candidates = candidates,
                    targetCount = intent.targetCount,
                )
            }
            error("Unreachable playlist selection state.")
        }
}

data class AiPlaylistSelection(
    val songs: List<SongItem>,
    val title: String?,
)

internal fun resolveSelection(
    arguments: JsonObject,
    candidates: List<SongItem>,
    targetCount: Int,
): AiPlaylistSelection {
    val indexes =
        runCatching {
            arguments["selectedIndexes"]
                ?.jsonArray
                .orEmpty()
                .mapNotNull { it.jsonPrimitive.intOrNull }
                .distinct()
        }.getOrDefault(emptyList())
    val maximum = targetCount.coerceIn(1, AiSessionArtifacts.MAX_CANDIDATE_POOL)
    val songs = indexes.mapNotNull(candidates::getOrNull).distinctBy(SongItem::id).take(maximum)
    require(songs.isNotEmpty()) { "The model did not select any valid candidate indexes." }
    val title =
        arguments["playlistName"]
            ?.jsonPrimitive
            ?.contentOrNull
            ?.asSequence()
            ?.filterNot(Char::isISOControl)
            ?.take(MAX_TITLE_CHARS)
            ?.joinToString("")
            ?.trim()
            ?.takeIf(String::isNotBlank)
    return AiPlaylistSelection(songs, title)
}

private fun selectionRequest(
    intent: AiPlaylistIntent,
    candidates: List<SongItem>,
): AiRequest {
    val maximum = intent.targetCount.coerceIn(1, candidates.size)
    val candidatePayload =
        buildJsonArray {
            candidates.forEachIndexed { index, song ->
                add(
                    buildJsonObject {
                        put("candidateIndex", index)
                        put("title", song.title)
                        put("artists", song.artists.joinToString { it.name })
                        song.album?.name?.let { put("album", it) }
                        song.duration?.let { put("durationSeconds", it) }
                    },
                )
            }
        }
    return AiRequest(
        systemPrompt =
            """You curate a MetroFuse+ playlist from a closed candidate pool.
You must call $SELECTION_TOOL_NAME exactly once.
Select only zero-based candidateIndex values present in the supplied candidates.
Choose at most $maximum tracks, prioritize the requested concept, and keep artist and album diversity.
Never emit song IDs and never introduce tracks outside the candidate pool.""",
        messages =
            listOf(
                AiConversationMessage.User(
                    buildString {
                        append("Title: ")
                        append(intent.title)
                        intent.description?.let {
                            append("\nConcept: ")
                            append(it)
                        }
                        append("\nTarget count: ")
                        append(maximum)
                        append("\nCandidates: ")
                        append(candidatePayload)
                    },
                ),
            ),
        tools = listOf(selectionTool(candidates.lastIndex, maximum)),
        maxOutputTokens = 768,
        temperature = 0.25,
    )
}

private fun selectionTool(
    maximumIndex: Int,
    maximumItems: Int,
): AiToolDefinition =
    AiToolDefinition(
        name = SELECTION_TOOL_NAME,
        description = "Selects real candidates by their zero-based indexes and optionally refines the playlist name.",
        inputSchema =
            buildJsonObject {
                put("type", "object")
                put(
                    "properties",
                    buildJsonObject {
                        put(
                            "selectedIndexes",
                            buildJsonObject {
                                put("type", "array")
                                put(
                                    "items",
                                    buildJsonObject {
                                        put("type", "integer")
                                        put("minimum", 0)
                                        put("maximum", maximumIndex)
                                    },
                                )
                                put("minItems", 1)
                                put("maxItems", maximumItems)
                                put("uniqueItems", true)
                            },
                        )
                        put(
                            "playlistName",
                            buildJsonObject {
                                put("type", "string")
                                put("maxLength", MAX_TITLE_CHARS)
                            },
                        )
                    },
                )
                put("required", JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("selectedIndexes"))))
                put("additionalProperties", false)
            },
    )

private const val SELECTION_TOOL_NAME = "select_playlist_candidates"
private const val MAX_TITLE_CHARS = 100
