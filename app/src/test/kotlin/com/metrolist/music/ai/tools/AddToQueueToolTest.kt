package com.metrolist.music.ai.tools

import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.SongItem
import com.metrolist.music.ai.action.AiPendingAction
import com.metrolist.music.ai.model.AiPermissions
import com.metrolist.music.ai.model.AiToolContext
import com.metrolist.music.ai.playlist.AiSessionArtifacts
import com.metrolist.music.ai.tools.player.AddToQueueTool
import com.metrolist.music.ai.tools.player.PlaySongTool
import com.metrolist.music.ai.tools.player.StartRadioTool
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AddToQueueToolTest {
    @Test
    fun `rejects a song id not observed in the session`() = runBlocking {
        val artifacts = AiSessionArtifacts()
        val result = AddToQueueTool().execute(arguments("invented"), context(artifacts))

        assertEquals("unknown_song_ids", (result as AiToolResult.Failure).code)
    }

    @Test
    fun `prepares confirmation without changing the player`() = runBlocking {
        val artifacts = AiSessionArtifacts()
        artifacts.rememberSongs(listOf(song("real-id")))

        val result = AddToQueueTool().execute(arguments("real-id"), context(artifacts)) as AiToolResult.Success
        val presentation = result.presentation as AiToolPresentation.Confirmation

        assertTrue(presentation.action is AiPendingAction.AddSongsToQueue)
        assertNotNull(artifacts.pendingAction(presentation.action.id))
        assertEquals("real-id", (presentation.action as AiPendingAction.AddSongsToQueue).songs.single().id)
    }

    @Test
    fun `playback tools also reject invented IDs and only prepare actions`() = runBlocking {
        val artifacts = AiSessionArtifacts()
        val unknown = PlaySongTool().execute(songArguments("invented"), context(artifacts))
        assertEquals("unknown_song_id", (unknown as AiToolResult.Failure).code)

        artifacts.rememberSongs(listOf(song("real-id")))
        val playResult = PlaySongTool().execute(songArguments("real-id"), context(artifacts)) as AiToolResult.Success
        val radioResult =
            StartRadioTool().execute(songArguments("real-id"), context(artifacts)) as AiToolResult.Success

        assertTrue((playResult.presentation as AiToolPresentation.Confirmation).action is AiPendingAction.PlaySong)
        assertTrue((radioResult.presentation as AiToolPresentation.Confirmation).action is AiPendingAction.StartRadio)
    }

    private fun arguments(id: String) =
        buildJsonObject {
            put("songIds", buildJsonArray { add(id) })
            put("position", "next")
        }

    private fun songArguments(id: String) =
        buildJsonObject {
            put("songId", id)
        }

    private fun context(artifacts: AiSessionArtifacts) =
        AiToolContext(
            permissions = AiPermissions(),
            currentMusic = null,
            artifacts = artifacts,
        )

    private fun song(id: String) =
        SongItem(
            id = id,
            title = "Song",
            artists = listOf(Artist("Artist", null)),
            thumbnail = "https://example.invalid/song.jpg",
        )
}
