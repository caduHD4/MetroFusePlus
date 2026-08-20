/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ai.tools

import com.metrolist.music.ai.model.AiPendingToolCall
import com.metrolist.music.ai.model.AiToolContext
import com.metrolist.music.ai.core.runCatchingPreservingCancellation
import com.metrolist.music.ai.tools.context.GetCurrentSongTool
import com.metrolist.music.ai.tools.context.GetLyricsTool
import com.metrolist.music.ai.tools.context.GetQueueTool
import com.metrolist.music.ai.tools.context.GetUiContextTool
import com.metrolist.music.ai.tools.library.GetLikedSongsTool
import com.metrolist.music.ai.tools.library.GetPlaylistTool
import com.metrolist.music.ai.tools.library.GetPlaylistsTool
import com.metrolist.music.ai.tools.library.GetRecentHistoryTool
import com.metrolist.music.ai.tools.library.SearchLibraryTool
import com.metrolist.music.ai.tools.search.SearchAlbumTool
import com.metrolist.music.ai.tools.search.SearchArtistTool
import com.metrolist.music.ai.tools.search.SearchMusicTool
import com.metrolist.music.ai.tools.search.GetRelatedSongsTool
import com.metrolist.music.ai.tools.playlist.CreatePlaylistDraftTool
import com.metrolist.music.ai.tools.playlist.AddTracksToPlaylistTool
import com.metrolist.music.ai.tools.playlist.PlayPlaylistTool
import com.metrolist.music.ai.tools.playlist.SavePlaylistTool
import com.metrolist.music.ai.tools.playlist.UpdatePlaylistDraftTool
import com.metrolist.music.ai.tools.player.AddToQueueTool
import com.metrolist.music.ai.tools.player.PlaySongTool
import com.metrolist.music.ai.tools.player.RemoveFromQueueTool
import com.metrolist.music.ai.tools.player.StartRadioTool
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AiToolRegistry
@Inject
constructor(
    getCurrentSongTool: GetCurrentSongTool,
    getQueueTool: GetQueueTool,
    getLyricsTool: GetLyricsTool,
    getUiContextTool: GetUiContextTool,
    searchMusicTool: SearchMusicTool,
    searchArtistTool: SearchArtistTool,
    searchAlbumTool: SearchAlbumTool,
    getRelatedSongsTool: GetRelatedSongsTool,
    getLikedSongsTool: GetLikedSongsTool,
    getPlaylistsTool: GetPlaylistsTool,
    getPlaylistTool: GetPlaylistTool,
    getRecentHistoryTool: GetRecentHistoryTool,
    searchLibraryTool: SearchLibraryTool,
    addToQueueTool: AddToQueueTool,
    playSongTool: PlaySongTool,
    startRadioTool: StartRadioTool,
    createPlaylistDraftTool: CreatePlaylistDraftTool,
    updatePlaylistDraftTool: UpdatePlaylistDraftTool,
    savePlaylistTool: SavePlaylistTool,
    addTracksToPlaylistTool: AddTracksToPlaylistTool,
    playPlaylistTool: PlayPlaylistTool,
    removeFromQueueTool: RemoveFromQueueTool,
) {
    private val tools =
        listOf(
            getCurrentSongTool,
            getQueueTool,
            getLyricsTool,
            getUiContextTool,
            searchMusicTool,
            searchArtistTool,
            searchAlbumTool,
            getRelatedSongsTool,
            getLikedSongsTool,
            getPlaylistsTool,
            getPlaylistTool,
            getRecentHistoryTool,
            searchLibraryTool,
            addToQueueTool,
            playSongTool,
            startRadioTool,
            createPlaylistDraftTool,
            updatePlaylistDraftTool,
            savePlaylistTool,
            addTracksToPlaylistTool,
            playPlaylistTool,
            removeFromQueueTool,
        ).associateBy(AiTool::name)

    fun definitions(context: AiToolContext) =
        tools.values.filter { it.isAvailable(context) }.map(AiTool::definition)

    fun tool(name: String): AiTool? = tools[name]
}

@Singleton
class AiToolExecutor
@Inject
constructor(
    private val registry: AiToolRegistry,
    private val confirmationPolicy: AiActionConfirmationPolicy,
) {
    suspend fun execute(
        call: AiPendingToolCall,
        context: AiToolContext,
    ): AiToolExecution {
        val tool = registry.tool(call.name)
            ?: return AiToolExecution(
                call,
                AiToolResult.Failure("tool_unavailable", "Unknown tool: ${call.name}"),
            )
        if (!tool.isAvailable(context)) {
            return AiToolExecution(
                call,
                AiToolResult.Failure("permission_denied", "This tool is disabled by the assistant context permissions."),
            )
        }
        AiToolArgumentsValidator.validate(tool.inputSchema, call.arguments)?.let { validationError ->
            return AiToolExecution(
                call,
                AiToolResult.Failure("invalid_arguments", validationError),
            )
        }
        if (confirmationPolicy.requiresConfirmation(tool.risk) && tool !is AiConfirmableTool) {
            return AiToolExecution(
                call,
                AiToolResult.Failure("confirmation_required", "This tool cannot run without explicit confirmation."),
            )
        }
        val result =
            runCatchingPreservingCancellation { tool.execute(call.arguments, context) }
                .getOrElse {
                    AiToolResult.Failure(
                        code = "execution_failed",
                        message = it.message ?: "Tool execution failed.",
                    )
                }
        val safeResult =
            if (
                confirmationPolicy.requiresConfirmation(tool.risk) &&
                (result as? AiToolResult.Success)?.presentation !is AiToolPresentation.Confirmation
            ) {
                AiToolResult.Failure(
                    "confirmation_required",
                    "The action was not prepared for explicit user confirmation.",
                )
            } else {
                result
            }
        return AiToolExecution(call, safeResult)
    }
}
