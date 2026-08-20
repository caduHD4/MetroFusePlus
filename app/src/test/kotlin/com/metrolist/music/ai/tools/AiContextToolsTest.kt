package com.metrolist.music.ai.tools

import com.metrolist.music.ai.model.AiPermissions
import com.metrolist.music.ai.model.AiQueueItemContext
import com.metrolist.music.ai.model.AiToolContext
import com.metrolist.music.ai.model.AiUiContext
import com.metrolist.music.ai.model.AiUiContextType
import com.metrolist.music.ai.model.CurrentLyricsContext
import com.metrolist.music.ai.playlist.AiSessionArtifacts
import com.metrolist.music.ai.tools.context.GetLyricsTool
import com.metrolist.music.ai.tools.context.GetQueueTool
import com.metrolist.music.ai.tools.context.GetUiContextTool
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiContextToolsTest {
    @Test
    fun `queue tool is unavailable and denied without permission`() = runBlocking {
        val tool = GetQueueTool()
        val context = context(permissions = AiPermissions(queue = false))

        assertFalse(tool.isAvailable(context))
        val result = tool.execute(JsonObject(emptyMap()), context)
        assertEquals("permission_denied", (result as AiToolResult.Failure).code)
    }

    @Test
    fun `queue tool returns a bounded page of normalized items`() = runBlocking {
        val tool = GetQueueTool()
        val queue =
            (0 until 60).map { index ->
                AiQueueItemContext(
                    id = "id-$index",
                    title = "Song $index",
                    artists = listOf("Artist"),
                    album = null,
                    durationSeconds = 180,
                    position = index,
                    isCurrent = index == 3,
                )
            }
        val result =
            tool.execute(
                buildJsonObject {
                    put("offset", 2)
                    put("limit", 50)
                },
                context(AiPermissions(queue = true), queue = queue),
            ) as AiToolResult.Success
        val payload = result.payload.jsonObject

        assertEquals(50, payload["items"]!!.jsonArray.size)
        assertEquals("id-2", payload["items"]!!.jsonArray.first().jsonObject["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `lyrics tool keeps original and translation bounded`() = runBlocking {
        val tool = GetLyricsTool()
        val original = "a".repeat(GetLyricsTool.MAX_LYRICS_CHARS + 20)
        val result =
            tool.execute(
                JsonObject(emptyMap()),
                context(
                    permissions = AiPermissions(currentSong = true, lyrics = true),
                    lyrics =
                        CurrentLyricsContext(
                            songId = "real-id",
                            provider = "Test",
                            text = original,
                            translatedText = "translation",
                            translationLanguage = "en",
                        ),
                ),
            ) as AiToolResult.Success
        val payload = result.payload.jsonObject

        assertTrue(payload["available"]!!.jsonPrimitive.boolean)
        assertTrue(payload["lyricsTruncated"]!!.jsonPrimitive.boolean)
        assertEquals(GetLyricsTool.MAX_LYRICS_CHARS, payload["lyrics"]!!.jsonPrimitive.content.length)
        assertEquals("translation", payload["translatedLyrics"]!!.jsonPrimitive.content)
    }

    @Test
    fun `UI context tool returns typed bounded route context`() = runBlocking {
        val result =
            GetUiContextTool().execute(
                JsonObject(emptyMap()),
                context(
                    permissions = AiPermissions(),
                    uiContext = AiUiContext(AiUiContextType.ALBUM, resourceId = "album-id"),
                ),
            ) as AiToolResult.Success
        val payload = result.payload.jsonObject

        assertEquals("album", payload["type"]!!.jsonPrimitive.content)
        assertEquals("album-id", payload["resourceId"]!!.jsonPrimitive.content)
    }

    private fun context(
        permissions: AiPermissions,
        queue: List<AiQueueItemContext> = emptyList(),
        lyrics: CurrentLyricsContext? = null,
        uiContext: AiUiContext? = null,
    ) = AiToolContext(
        permissions = permissions,
        currentMusic = null,
        artifacts = AiSessionArtifacts(),
        queue = queue,
        lyrics = lyrics,
        uiContext = uiContext,
    )
}
