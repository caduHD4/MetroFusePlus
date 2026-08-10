/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import com.metrolist.innertube.models.YouTubeClient

// Keep these isolated from normal playback: they are current tracking identities from yt-dlp's
// maintained Innertube client table, so a future refresh does not alter the app's audio providers.
internal val YOUTUBE_MUSIC_HISTORY_TRACKING_CLIENTS =
    listOf(
        YouTubeClient.WEB_REMIX.copy(
            clientVersion = "1.20260707.12.00",
            userAgent = YOUTUBE_TRACKING_WEB_USER_AGENT,
        ),
        YouTubeClient.WEB.copy(
            clientVersion = "2.20260708.00.00",
            userAgent = YOUTUBE_TRACKING_WEB_USER_AGENT,
            loginSupported = true,
        ),
        YouTubeClient.ANDROID_MUSIC,
        YouTubeClient.MOBILE.copy(
            clientVersion = "21.26.364",
            userAgent = "com.google.android.youtube/21.26.364 (Linux; U; Android 11) gzip",
            osName = "Android",
            osVersion = "11",
            androidSdkVersion = "30",
        ),
        YouTubeClient.TVHTML5.copy(
            clientVersion = "7.20260707.07.00",
            userAgent =
                "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/25.lts.30.1034943-gold " +
                    "(unlike Gecko), Unknown_TV_Unknown_0/Unknown (Unknown, Unknown)",
        ),
        YouTubeClient.TVHTML5_SIMPLY_EMBEDDED_PLAYER,
    )

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
