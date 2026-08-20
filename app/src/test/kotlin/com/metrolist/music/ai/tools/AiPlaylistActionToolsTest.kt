package com.metrolist.music.ai.tools

import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.SongItem
import com.metrolist.music.ai.action.AiPendingAction
import com.metrolist.music.ai.model.AiPermissions
import com.metrolist.music.ai.model.AiQueueItemContext
import com.metrolist.music.ai.model.AiToolContext
import com.metrolist.music.ai.playlist.AiPlaylistIntent
import com.metrolist.music.ai.playlist.AiPlaylistRanker
import com.metrolist.music.ai.playlist.AiSessionArtifacts
import com.metrolist.music.ai.tools.player.RemoveFromQueueTool
import com.metrolist.music.ai.tools.playlist.CreatePlaylistDraftTool
import com.metrolist.music.ai.tools.playlist.SavePlaylistTool
import com.metrolist.music.ai.tools.playlist.UpdatePlaylistDraftTool
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiPlaylistActionToolsTest {
    @Test
    fun `query playlist request creates a confirmed plan without searching or persisting`() = runBlocking {
        val result =
            CreatePlaylistDraftTool(AiPlaylistRanker()).execute(
                buildJsonObject {
                    put("title", "Night Drive")
                    put("targetCount", 20)
                    put("queries", buildJsonArray { add("dark synthwave"); add("night drive electronic") })
                },
                context(),
            ) as AiToolResult.Success

        val action = (result.presentation as AiToolPresentation.Confirmation).action
        assertTrue(action is AiPendingAction.BuildPlaylistDraft)
        assertEquals(2, (action as AiPendingAction.BuildPlaylistDraft).queries.size)
    }

    @Test
    fun `query playlist plan requires multiple complementary searches`() = runBlocking {
        val result =
            CreatePlaylistDraftTool(AiPlaylistRanker()).execute(
                buildJsonObject {
                    put("title", "Night Drive")
                    put("targetCount", 20)
                    put("queries", buildJsonArray { add("night drive") })
                },
                context(),
            )

        assertEquals("invalid_arguments", (result as AiToolResult.Failure).code)
    }

    @Test
    fun `save tool only accepts a real draft from this session`() = runBlocking {
        val artifacts = AiSessionArtifacts()
        val denied = SavePlaylistTool().execute(buildJsonObject { put("draftId", "invented") }, context(artifacts))
        assertEquals("unknown_draft", (denied as AiToolResult.Failure).code)

        val draft = artifacts.createDraft(AiPlaylistIntent("Real", null, 1), listOf(song("real")))
        val result = SavePlaylistTool().execute(buildJsonObject { put("draftId", draft.id) }, context(artifacts))
        assertTrue(((result as AiToolResult.Success).presentation as AiToolPresentation.Confirmation).action is AiPendingAction.SavePlaylistDraft)
    }

    @Test
    fun `draft follow up updates the active draft with observed ids only`() = runBlocking {
        val artifacts = AiSessionArtifacts()
        val first = song("first")
        val second = song("second")
        artifacts.rememberSongs(listOf(first, second))
        val draft = artifacts.createDraft(AiPlaylistIntent("Draft", null, 2), listOf(first))
        val result =
            UpdatePlaylistDraftTool().execute(
                buildJsonObject {
                    put("songIds", buildJsonArray { add(second.id) })
                    put("replace", false)
                },
                context(artifacts),
            ) as AiToolResult.Success
        val action = (result.presentation as AiToolPresentation.Confirmation).action
        val updated = artifacts.confirmUpdateDraftAction(action.id)

        assertEquals(draft.id, updated?.id)
        assertEquals(listOf("first", "second"), updated?.songs?.map { it.id })
    }

    @Test
    fun `unknown explicit draft id never falls back to the active draft`() = runBlocking {
        val artifacts = AiSessionArtifacts()
        val observed = song("observed")
        artifacts.rememberSongs(listOf(observed))
        artifacts.createDraft(AiPlaylistIntent("Active", null, 1), listOf(observed))

        val result =
            UpdatePlaylistDraftTool().execute(
                buildJsonObject {
                    put("draftId", "invented")
                    put("songIds", buildJsonArray { add(observed.id) })
                },
                context(artifacts),
            )

        assertEquals("unknown_draft", (result as AiToolResult.Failure).code)
    }

    @Test
    fun `editing a saved draft marks the new revision as unsaved`() = runBlocking {
        val artifacts = AiSessionArtifacts()
        val first = song("first")
        val second = song("second")
        artifacts.rememberSongs(listOf(first, second))
        val draft = artifacts.createDraft(AiPlaylistIntent("Draft", null, 1), listOf(first))
        artifacts.markSaved(draft.id, "LP_SAVED")
        val result =
            UpdatePlaylistDraftTool().execute(
                buildJsonObject { put("songIds", buildJsonArray { add(second.id) }) },
                context(artifacts),
            ) as AiToolResult.Success
        val action = (result.presentation as AiToolPresentation.Confirmation).action

        val updated = artifacts.confirmUpdateDraftAction(action.id)

        assertEquals(null, updated?.savedPlaylistId)
    }

    @Test
    fun `queue removal rejects current or stale positions`() = runBlocking {
        val queue =
            listOf(
                queueItem(0, "current", true),
                queueItem(1, "next", false),
            )
        val tool = RemoveFromQueueTool()
        val current = tool.execute(buildJsonObject { put("positions", buildJsonArray { add(0) }) }, context(queue = queue))
        assertEquals("stale_or_current_queue_item", (current as AiToolResult.Failure).code)

        val next = tool.execute(buildJsonObject { put("positions", buildJsonArray { add(1) }) }, context(queue = queue))
        assertTrue(((next as AiToolResult.Success).presentation as AiToolPresentation.Confirmation).action is AiPendingAction.RemoveFromQueue)
    }

    @Test
    fun `queue removal rejects an empty request even without schema validation`() = runBlocking {
        val result = RemoveFromQueueTool().execute(buildJsonObject {}, context(queue = emptyList()))

        assertEquals("invalid_arguments", (result as AiToolResult.Failure).code)
    }

    private fun context(
        artifacts: AiSessionArtifacts = AiSessionArtifacts(),
        queue: List<AiQueueItemContext> = emptyList(),
    ) = AiToolContext(
        permissions = AiPermissions(queue = true, playlists = true),
        currentMusic = null,
        artifacts = artifacts,
        queue = queue,
    )

    private fun song(id: String) =
        SongItem(id = id, title = id, artists = listOf(Artist("Artist", "artist")), thumbnail = "")

    private fun queueItem(position: Int, id: String, current: Boolean) =
        AiQueueItemContext(id, id, listOf("Artist"), null, 180, position, current)
}
