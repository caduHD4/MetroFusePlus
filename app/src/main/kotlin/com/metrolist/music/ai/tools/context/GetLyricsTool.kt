/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ai.tools.context

import com.metrolist.music.ai.model.AiToolContext
import com.metrolist.music.ai.tools.AiTool
import com.metrolist.music.ai.tools.AiToolResult
import com.metrolist.music.ai.tools.AiToolRisk
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject

class GetLyricsTool
@Inject
constructor() : AiTool {
    override val name = "get_lyrics"
    override val description = "Returns bounded lyrics for the active song, including a saved translation when available."
    override val inputSchema =
        buildJsonObject {
            put("type", "object")
            put("properties", JsonObject(emptyMap()))
            put("additionalProperties", false)
        }
    override val risk = AiToolRisk.READ_ONLY

    override fun isAvailable(context: AiToolContext): Boolean =
        context.permissions.currentSong && context.permissions.lyrics

    override suspend fun execute(
        arguments: JsonObject,
        context: AiToolContext,
    ): AiToolResult {
        if (!isAvailable(context)) {
            return AiToolResult.Failure("permission_denied", "Lyrics context is disabled.")
        }
        val lyrics = context.lyrics
            ?: return AiToolResult.Success(buildJsonObject { put("available", false) })
        val original = lyrics.text.take(MAX_LYRICS_CHARS)
        val translation = lyrics.translatedText?.takeIf(String::isNotBlank)?.take(MAX_LYRICS_CHARS)
        return AiToolResult.Success(
            buildJsonObject {
                put("available", true)
                put("songId", lyrics.songId)
                put("provider", lyrics.provider)
                put("lyrics", original)
                put("lyricsTruncated", lyrics.originalTruncated || lyrics.text.length > original.length)
                translation?.let {
                    put("translatedLyrics", it)
                    lyrics.translationLanguage?.takeIf(String::isNotBlank)?.let { language ->
                        put("translationLanguage", language)
                    }
                    put(
                        "translationTruncated",
                        lyrics.translationTruncated || lyrics.translatedText.orEmpty().length > it.length,
                    )
                }
            },
        )
    }

    companion object {
        const val MAX_LYRICS_CHARS = 12_000
    }
}
