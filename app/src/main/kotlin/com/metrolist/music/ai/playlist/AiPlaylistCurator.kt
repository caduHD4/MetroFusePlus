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
        webSearchEnabled: Boolean = false,
    ): Result<AiPlaylistSelection> =
        runCatchingPreservingCancellation {
            require(candidates.isNotEmpty()) { "The candidate pool is empty." }
            val provider = providerRegistry.requireProvider(config.providerId)
            val request = selectionRequest(intent, candidates, webSearchEnabled)
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
                        is AiStreamEvent.Grounding,
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
    val additionalQueries: List<String> = emptyList(),
    val complete: Boolean = true,
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
    val additionalQueries =
        runCatching {
            arguments["additionalQueries"]
                ?.jsonArray
                .orEmpty()
                .mapNotNull { it.jsonPrimitive.contentOrNull?.trim() }
                .filter(String::isNotBlank)
                .distinct()
                .take(MAX_ADDITIONAL_QUERIES)
        }.getOrDefault(emptyList())
    require(songs.isNotEmpty() || additionalQueries.isNotEmpty()) {
        "The model did not select candidates or request a broader catalog search."
    }
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
    val complete =
        arguments["complete"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
            ?: (songs.size >= maximum && additionalQueries.isEmpty())
    return AiPlaylistSelection(songs, title, additionalQueries, complete)
}

private fun selectionRequest(
    intent: AiPlaylistIntent,
    candidates: List<SongItem>,
    webSearchEnabled: Boolean,
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
Choose at most $maximum tracks and prioritize musical identity, sound, scene, mood, and era over literal title matches.
For an exact artist, select only tracks credited to that artist and do not enforce artist diversity.
For a genre, mood, or aesthetic, reject compilations, long mixes, generic background tracks, and titles that merely repeat the requested label without fitting the music.
Use additionalQueries when the pool is weak, repetitive, or too literal. Those queries should explore artists, adjacent genres, production traits, scenes, and representative tracks instead of repeating the same label.
Set complete=true only when the pool contains enough strongly relevant tracks.
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
                        append("\nIntent type: ")
                        append(intent.type.name.lowercase())
                        intent.artistName?.let {
                            append("\nExact artist: ")
                            append(it)
                        }
                        intent.era?.let {
                            append("\nEra: ")
                            append(it)
                        }
                        if (intent.exclusions.isNotEmpty()) {
                            append("\nExclude: ")
                            append(intent.exclusions.joinToString())
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
        temperature = 0.2,
        enableWebSearch = webSearchEnabled && intent.requiresConceptResearch,
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
                        put(
                            "additionalQueries",
                            buildJsonObject {
                                put("type", "array")
                                put("items", buildJsonObject { put("type", "string"); put("maxLength", 180) })
                                put("maxItems", MAX_ADDITIONAL_QUERIES)
                                put("uniqueItems", true)
                            },
                        )
                        put(
                            "complete",
                            buildJsonObject {
                                put("type", "boolean")
                                put("description", "True only when the selected candidates strongly satisfy the concept and target count.")
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
private const val MAX_ADDITIONAL_QUERIES = 8

private val AiPlaylistIntent.requiresConceptResearch: Boolean
    get() = type in setOf(AiPlaylistIntentType.GENRE, AiPlaylistIntentType.MOOD, AiPlaylistIntentType.CONCEPT)
