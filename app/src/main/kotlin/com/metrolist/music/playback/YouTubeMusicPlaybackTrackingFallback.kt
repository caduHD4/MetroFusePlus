/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import com.metrolist.innertube.models.YouTubeClient

// Keep these isolated from normal playback: they are current tracking identities from yt-dlp's
// maintained Innertube client table, so a future refresh does not alter the app's audio providers.
private val YOUTUBE_MUSIC_TRACKING_WEB_REMIX =
    YouTubeClient.WEB_REMIX.copy(
        clientVersion = "1.20260707.12.00",
        userAgent = YOUTUBE_TRACKING_WEB_USER_AGENT,
    )

private val YOUTUBE_MUSIC_TRACKING_WEB =
    YouTubeClient.WEB.copy(
        clientVersion = "2.20260708.00.00",
        userAgent = YOUTUBE_TRACKING_WEB_USER_AGENT,
        loginSupported = true,
    )

internal val YOUTUBE_MUSIC_HISTORY_TRACKING_CLIENTS =
    listOf(
        YOUTUBE_MUSIC_TRACKING_WEB_REMIX,
        YouTubeClient.ANDROID_MUSIC,
        YOUTUBE_MUSIC_TRACKING_WEB,
    )

internal fun youTubeMusicHistoryTrackingClients(
    useWebRemix: Boolean,
    useAndroidMusic: Boolean,
    useWeb: Boolean,
): List<YouTubeClient> =
    buildList {
        if (useWebRemix) add(YOUTUBE_MUSIC_TRACKING_WEB_REMIX)
        if (useAndroidMusic) add(YouTubeClient.ANDROID_MUSIC)
        if (useWeb) add(YOUTUBE_MUSIC_TRACKING_WEB)
    }.ifEmpty { YOUTUBE_MUSIC_HISTORY_TRACKING_CLIENTS }

private const val YOUTUBE_TRACKING_WEB_USER_AGENT =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 " +
        "(KHTML, like Gecko) Version/15.5 Safari/605.1.15,gzip(gfe)"

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
