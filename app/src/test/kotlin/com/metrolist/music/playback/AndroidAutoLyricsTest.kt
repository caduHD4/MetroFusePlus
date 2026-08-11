/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import com.metrolist.music.lyrics.LyricsEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AndroidAutoLyricsTest {
    private val lines = listOf(
        LyricsEntry(1_000L, "First line"),
        LyricsEntry(2_000L, "Second line"),
        LyricsEntry(3_000L, "   "),
    )

    @Test
    fun returnsNullBeforeFirstTimestamp() {
        assertNull(AndroidAutoLyrics.currentLine(lines, 500L))
    }

    @Test
    fun followsCurrentLineAcrossSeek() {
        assertEquals(
            AndroidAutoLyrics.CurrentLine(1, "Second line"),
            AndroidAutoLyrics.currentLine(lines, 2_500L),
        )
        assertEquals(
            AndroidAutoLyrics.CurrentLine(0, "First line"),
            AndroidAutoLyrics.currentLine(lines, 1_200L),
        )
    }

    @Test
    fun appliesStoredLyricsOffset() {
        assertEquals(
            AndroidAutoLyrics.CurrentLine(1, "Second line"),
            AndroidAutoLyrics.currentLine(lines, 1_600L, offsetMs = 500L),
        )
    }

    @Test
    fun blankCurrentLineRestoresNormalSubtitle() {
        assertNull(AndroidAutoLyrics.currentLine(lines, 3_100L))
    }

    @Test
    fun normalizesWhitespaceForSingleLineCarDisplays() {
        val spaced = listOf(LyricsEntry(0L, "one\n  two\tthree"))
        assertEquals(
            "one two three",
            AndroidAutoLyrics.currentLine(spaced, 0L)?.text,
        )
    }

    @Test
    fun prefersMainVocalOverSimultaneousBackgroundLine() {
        val simultaneous = listOf(
            LyricsEntry(1_000L, "Main vocal", isBackground = false),
            LyricsEntry(1_000L, "Background vocal", isBackground = true),
            LyricsEntry(2_000L, "Next line", isBackground = false),
        )
        assertEquals(
            "Main vocal",
            AndroidAutoLyrics.currentLine(simultaneous, 1_500L)?.text,
        )
    }

    @Test
    fun limitsSubtitleForGlanceableCarDisplay() {
        val longLine = listOf(LyricsEntry(0L, "x".repeat(200)))
        assertEquals(96, AndroidAutoLyrics.currentLine(longLine, 0L)?.text?.length)
    }
}
