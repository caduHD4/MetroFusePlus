/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import com.metrolist.music.lyrics.LyricsEntry
import com.metrolist.music.lyrics.LyricsUtils

internal object AndroidAutoLyrics {
    const val UPDATE_INTERVAL_MS = 750L
    private const val MAX_SUBTITLE_LENGTH = 96

    data class CurrentLine(
        val index: Int,
        val text: String,
    )

    fun currentLine(
        lines: List<LyricsEntry>,
        positionMs: Long,
        offsetMs: Long = 0L,
    ): CurrentLine? {
        if (lines.isEmpty()) return null

        val effectivePosition = (positionMs + offsetMs).coerceAtLeast(0L)
        val activeIndices = LyricsUtils.findActiveLineIndices(lines, effectivePosition)
        val index = activeIndices
            .filterNot { lines[it].isBackground }
            .maxByOrNull { lines[it].time }
            ?: LyricsUtils.findCurrentLineIndex(lines, effectivePosition)
        if (index !in lines.indices) return null

        val text = lines[index].text
            .replace(WHITESPACE_REGEX, " ")
            .trim()
            .take(MAX_SUBTITLE_LENGTH)
        return text.takeIf { it.isNotEmpty() }?.let { CurrentLine(index, it) }
    }

    private val WHITESPACE_REGEX = Regex("\\s+")
}
