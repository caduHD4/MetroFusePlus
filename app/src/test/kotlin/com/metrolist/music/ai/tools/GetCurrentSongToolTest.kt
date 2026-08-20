package com.metrolist.music.ai.tools

import com.metrolist.music.ai.model.AiPermissions
import com.metrolist.music.ai.model.AiToolContext
import com.metrolist.music.ai.model.CurrentMusicContext
import com.metrolist.music.ai.tools.context.GetCurrentSongTool
import com.metrolist.music.ai.playlist.AiSessionArtifacts
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GetCurrentSongToolTest {
    private val tool = GetCurrentSongTool()

    @Test
    fun `returns inactive when no song is playing`() = runBlocking {
        val result =
            tool.execute(
                JsonObject(emptyMap()),
                AiToolContext(AiPermissions(currentSong = true), currentMusic = null, artifacts = AiSessionArtifacts()),
            ) as AiToolResult.Success

        assertFalse(result.payload.jsonObject["active"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `returns only the normalized current song snapshot`() = runBlocking {
        val result =
            tool.execute(
                JsonObject(emptyMap()),
                AiToolContext(
                    permissions = AiPermissions(currentSong = true),
                    currentMusic =
                        CurrentMusicContext(
                            id = "real-id",
                            title = "Song",
                            artists = listOf("Artist"),
                            album = "Album",
                            durationSeconds = 180,
                            positionSeconds = 42,
                            isPlaying = true,
                        ),
                    artifacts = AiSessionArtifacts(),
                ),
            ) as AiToolResult.Success
        val payload = result.payload.jsonObject

        assertTrue(payload["active"]!!.jsonPrimitive.boolean)
        assertEquals("real-id", payload["id"]!!.jsonPrimitive.content)
        assertEquals(42, payload["positionSeconds"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `denies current song when permission is disabled`() = runBlocking {
        val result =
            tool.execute(
                JsonObject(emptyMap()),
                AiToolContext(AiPermissions(currentSong = false), currentMusic = null, artifacts = AiSessionArtifacts()),
            )

        assertTrue(result is AiToolResult.Failure)
        assertEquals("permission_denied", (result as AiToolResult.Failure).code)
    }
}
