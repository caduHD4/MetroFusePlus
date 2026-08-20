package com.metrolist.music.ai.core

import com.metrolist.music.ai.model.AiConversationMessage
import com.metrolist.music.ai.model.AiPermissions
import com.metrolist.music.ai.model.AiQueueItemContext
import com.metrolist.music.ai.model.CurrentLyricsContext
import com.metrolist.music.ai.model.CurrentMusicContext
import com.metrolist.music.ai.playlist.AiSessionArtifacts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiContextSafetyTest {
    @Test
    fun `conversation compaction keeps complete recent user turns`() {
        val messages =
            buildList {
                repeat(15) { index ->
                    add(AiConversationMessage.User("user-$index"))
                    add(AiConversationMessage.Assistant("assistant-$index"))
                }
            }

        val compacted = AiConversationWindow.compact(messages, maxUserTurns = 4)

        assertEquals("user-11", (compacted.first() as AiConversationMessage.User).text)
        assertEquals(8, compacted.size)
    }

    @Test
    fun `context builder removes disabled snapshots`() {
        val builder = AiContextBuilder(AiDataSanitizer())
        val context =
            builder.build(
                permissions = AiPermissions(currentSong = false, queue = false, lyrics = false),
                currentMusic = currentMusic(),
                queue = listOf(queueItem()),
                queueTotal = 1,
                lyrics = CurrentLyricsContext("id", "provider", "lyrics"),
                artifacts = AiSessionArtifacts(),
            )

        assertNull(context.currentMusic)
        assertTrue(context.queue.isEmpty())
        assertEquals(0, context.queueTotal)
        assertNull(context.lyrics)
        assertFalse(context.permissions.library)
    }

    @Test
    fun `sanitizer bounds user input and removes control characters`() {
        val sanitizer = AiDataSanitizer()
        val sanitized = sanitizer.userMessage("hello\u0000" + "x".repeat(AiDataSanitizer.MAX_USER_MESSAGE_CHARS))

        assertTrue('\u0000' !in sanitized)
        assertEquals(AiDataSanitizer.MAX_USER_MESSAGE_CHARS, sanitized.length)
    }

    private fun currentMusic() =
        CurrentMusicContext(
            id = "id",
            title = "Song",
            artists = listOf("Artist"),
            album = null,
            durationSeconds = 180,
            positionSeconds = 10,
            isPlaying = true,
        )

    private fun queueItem() =
        AiQueueItemContext(
            id = "id",
            title = "Song",
            artists = listOf("Artist"),
            album = null,
            durationSeconds = 180,
            position = 0,
            isCurrent = true,
        )
}
