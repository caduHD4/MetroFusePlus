/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ai.tools.playlist

import com.metrolist.music.ai.action.AiPendingAction
import com.metrolist.music.ai.model.AiToolContext
import com.metrolist.music.ai.repository.AiUserContextRepository
import com.metrolist.music.ai.repository.AiPlaylistRepository
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
import java.util.UUID
import javax.inject.Inject

class SavePlaylistTool
@Inject
constructor() : AiConfirmableTool {
    override val name = "save_playlist"
    override val description = "Saves an existing in-memory playlist draft after explicit confirmation."
    override val inputSchema = singleIdSchema("draftId")
    override val risk = AiToolRisk.SIDE_EFFECT

    override fun isAvailable(context: AiToolContext): Boolean = context.permissions.playlists

    override suspend fun execute(arguments: JsonObject, context: AiToolContext): AiToolResult {
        val draftId = arguments.string("draftId")
        val draft = context.artifacts.draft(draftId)
            ?: return AiToolResult.Failure("unknown_draft", "The playlist draft does not exist in this session.")
        if (draft.savedPlaylistId != null) {
            return AiToolResult.Failure("already_saved", "The playlist draft has already been saved.")
        }
        val action =
            context.artifacts.rememberAction(
                AiPendingAction.SavePlaylistDraft(
                    id = actionId(),
                    draftId = draft.id,
                    title = draft.intent.title,
                ),
            )
        return pending(action)
    }
}

class AddTracksToPlaylistTool
@Inject
constructor(
    private val userContextRepository: AiUserContextRepository,
) : AiConfirmableTool {
    override val name = "add_tracks_to_playlist"
    override val description = "Adds real song IDs observed in this session to a known editable library playlist after confirmation."
    override val inputSchema =
        buildJsonObject {
            put("type", "object")
            put(
                "properties",
                buildJsonObject {
                    put("playlistId", idProperty())
                    put("songIds", idArrayProperty())
                },
            )
            put("required", JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("playlistId"), kotlinx.serialization.json.JsonPrimitive("songIds"))))
            put("additionalProperties", false)
        }
    override val risk = AiToolRisk.SIDE_EFFECT

    override fun isAvailable(context: AiToolContext): Boolean = context.permissions.playlists

    override suspend fun execute(arguments: JsonObject, context: AiToolContext): AiToolResult {
        val playlistId = arguments.string("playlistId")
        if (!context.artifacts.knowsPlaylist(playlistId)) {
            return AiToolResult.Failure("unknown_playlist", "The playlist ID was not returned by a library tool in this session.")
        }
        val resolution = context.artifacts.resolveSongs(arguments.ids("songIds"))
        if (resolution.missingIds.isNotEmpty() || resolution.songs.isEmpty()) {
            return AiToolResult.Failure("unknown_song_ids", "Every song must come from a catalog or library tool in this session.")
        }
        val name = userContextRepository.editablePlaylistName(playlistId)
            ?: return AiToolResult.Failure("playlist_not_editable", "The playlist no longer exists or is not editable.")
        val action =
            context.artifacts.rememberAction(
                AiPendingAction.AddTracksToPlaylist(actionId(), playlistId, name, resolution.songs),
            )
        return pending(action)
    }
}

class PlayPlaylistTool
@Inject
constructor(
    private val userContextRepository: AiUserContextRepository,
    private val playlistRepository: AiPlaylistRepository,
) : AiConfirmableTool {
    override val name = "play_playlist"
    override val description = "Plays a known assistant draft or library playlist after explicit confirmation. Provide exactly one ID."
    override val inputSchema =
        buildJsonObject {
            put("type", "object")
            put(
                "properties",
                buildJsonObject {
                    put("draftId", idProperty())
                    put("playlistId", idProperty())
                },
            )
            put("additionalProperties", false)
        }
    override val risk = AiToolRisk.REVERSIBLE

    override suspend fun execute(arguments: JsonObject, context: AiToolContext): AiToolResult {
        val draftId = arguments.stringOrNull("draftId")
        val playlistId = arguments.stringOrNull("playlistId")
        if ((draftId == null) == (playlistId == null)) {
            return AiToolResult.Failure("invalid_arguments", "Provide exactly one of draftId or playlistId.")
        }
        val title: String
        val songs =
            if (draftId != null) {
                val draft = context.artifacts.draft(draftId)
                    ?: return AiToolResult.Failure("unknown_draft", "The playlist draft does not exist in this session.")
                title = draft.intent.title
                draft.songs
            } else {
                if (!context.permissions.playlists || !context.artifacts.knowsPlaylist(playlistId!!)) {
                    return AiToolResult.Failure("unknown_playlist", "The playlist ID was not returned by a permitted library tool.")
                }
                title = userContextRepository.playlistName(playlistId)
                    ?: return AiToolResult.Failure("unknown_playlist", "The playlist no longer exists.")
                playlistRepository.playlistSongs(playlistId).getOrElse {
                    return AiToolResult.Failure("playlist_unavailable", it.message ?: "The playlist could not be loaded.")
                }
            }
        if (songs.isEmpty()) return AiToolResult.Failure("empty_playlist", "The playlist has no playable songs.")
        context.artifacts.rememberSongs(songs)
        val action = context.artifacts.rememberAction(AiPendingAction.PlayPlaylist(actionId(), title, songs))
        return pending(action)
    }
}

private fun pending(action: AiPendingAction) =
    AiToolResult.Success(
        payload =
            buildJsonObject {
                put("actionId", action.id)
                put("status", "pending_confirmation")
            },
        presentation = AiToolPresentation.Confirmation(action),
    )

private fun singleIdSchema(name: String) =
    buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject { put(name, idProperty()) })
        put("required", JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive(name))))
        put("additionalProperties", false)
    }

private fun idProperty() =
    buildJsonObject {
        put("type", "string")
        put("minLength", 1)
        put("maxLength", MAX_ID_CHARS)
    }

private fun idArrayProperty() =
    buildJsonObject {
        put("type", "array")
        put("items", idProperty())
        put("minItems", 1)
        put("maxItems", MAX_PLAYLIST_TRACKS)
        put("uniqueItems", true)
    }

private fun JsonObject.string(name: String): String = stringOrNull(name).orEmpty()

private fun JsonObject.stringOrNull(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotBlank)

private fun JsonObject.ids(name: String): List<String> =
    runCatching { this[name]?.jsonArray.orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull } }.getOrDefault(emptyList())

private fun actionId(): String = "action_${UUID.randomUUID()}"

private const val MAX_ID_CHARS = 512
private const val MAX_PLAYLIST_TRACKS = 100
