/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ai.core

import com.metrolist.music.ai.model.AiQueueItemContext
import com.metrolist.music.ai.model.CurrentLyricsContext
import com.metrolist.music.ai.model.CurrentMusicContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AiDataSanitizer
@Inject
constructor() {
    fun userMessage(text: String): String = clean(text, MAX_USER_MESSAGE_CHARS)

    fun customInstructions(text: String): String = clean(text, MAX_CUSTOM_INSTRUCTIONS_CHARS)

    fun currentMusic(value: CurrentMusicContext?): CurrentMusicContext? =
        value?.copy(
            id = clean(value.id, MAX_ID_CHARS),
            title = clean(value.title, MAX_TITLE_CHARS),
            artists = value.artists.map { clean(it, MAX_NAME_CHARS) }.filter(String::isNotBlank).take(MAX_ARTISTS),
            album = value.album?.let { clean(it, MAX_TITLE_CHARS) },
        )

    fun queue(values: List<AiQueueItemContext>): List<AiQueueItemContext> =
        values.take(MAX_QUEUE_SNAPSHOT).map { value ->
            value.copy(
                id = clean(value.id, MAX_ID_CHARS),
                title = clean(value.title, MAX_TITLE_CHARS),
                artists = value.artists.map { clean(it, MAX_NAME_CHARS) }.filter(String::isNotBlank).take(MAX_ARTISTS),
                album = value.album?.let { clean(it, MAX_TITLE_CHARS) },
            )
        }

    fun lyrics(value: CurrentLyricsContext?): CurrentLyricsContext? =
        value?.copy(
            songId = clean(value.songId, MAX_ID_CHARS),
            provider = clean(value.provider, MAX_NAME_CHARS),
            text = clean(value.text, MAX_LYRICS_CHARS),
            translatedText = value.translatedText?.let { clean(it, MAX_LYRICS_CHARS) },
            translationLanguage = value.translationLanguage?.let { clean(it, MAX_NAME_CHARS) },
            originalTruncated = value.originalTruncated || value.text.length > MAX_LYRICS_CHARS,
            translationTruncated =
                value.translationTruncated || value.translatedText.orEmpty().length > MAX_LYRICS_CHARS,
        )

    private fun clean(
        value: String,
        maxLength: Int,
    ): String =
        value
            .asSequence()
            .filter { character -> character == '\n' || character == '\t' || !character.isISOControl() }
            .take(maxLength)
            .joinToString("")
            .trim()

    companion object {
        const val MAX_USER_MESSAGE_CHARS = 8_000
        const val MAX_LYRICS_CHARS = 12_000
        const val MAX_QUEUE_SNAPSHOT = 100
        const val MAX_CUSTOM_INSTRUCTIONS_CHARS = 4_000
        private const val MAX_ID_CHARS = 512
        private const val MAX_TITLE_CHARS = 300
        private const val MAX_NAME_CHARS = 160
        private const val MAX_ARTISTS = 12
    }
}
