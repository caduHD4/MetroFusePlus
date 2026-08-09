/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import com.metrolist.innertube.models.YouTubeClient

internal val YOUTUBE_MUSIC_HISTORY_TRACKING_CLIENTS =
    listOf(
        YouTubeClient.WEB_REMIX,
        YouTubeClient.ANDROID_MUSIC,
        YouTubeClient.MOBILE,
        YouTubeClient.TVHTML5,
        YouTubeClient.TVHTML5_SIMPLY_EMBEDDED_PLAYER,
    )

internal data class YouTubeClientResolution<T : Any>(
    val client: YouTubeClient,
    val value: T,
)

internal suspend fun <T : Any> resolveWithYouTubeClientFallback(
    clients: List<YouTubeClient>,
    resolve: suspend (YouTubeClient) -> T?,
): YouTubeClientResolution<T>? {
    clients.forEach { client ->
        resolve(client)?.let { value ->
            return YouTubeClientResolution(client, value)
        }
    }
    return null
}
