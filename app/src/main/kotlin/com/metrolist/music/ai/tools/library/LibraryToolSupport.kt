/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ai.tools.library

import com.metrolist.innertube.models.SongItem
import com.metrolist.music.ai.repository.AiUserContextRepository
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun paginationSchema(): JsonObject =
    buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                put(
                    "limit",
                    buildJsonObject {
                        put("type", "integer")
                        put("minimum", 1)
                        put("maximum", AiUserContextRepository.MAX_RESULTS)
                        put("default", DEFAULT_LIBRARY_LIMIT)
                    },
                )
                put(
                    "offset",
                    buildJsonObject {
                        put("type", "integer")
                        put("minimum", 0)
                        put("maximum", AiUserContextRepository.MAX_OFFSET)
                        put("default", 0)
                    },
                )
            },
        )
        put("additionalProperties", false)
    }

fun songsPayload(songs: List<SongItem>) =
    buildJsonArray {
        songs.forEach { song ->
            add(
                buildJsonObject {
                    put("id", song.id)
                    put("title", song.title)
                    put("artists", buildJsonArray { song.artists.forEach { add(it.name) } })
                    song.album?.name?.let { put("album", it) }
                    song.duration?.let { put("durationSeconds", it) }
                },
            )
        }
    }

internal const val DEFAULT_LIBRARY_LIMIT = 12
