package com.metrolist.music.playback

import com.metrolist.innertube.models.YouTubeClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeMusicPlaybackTrackingFallbackTest {
    @Test
    fun resolverStopsAtFirstClientWithTracking() =
        runBlocking {
            val attemptedClients = mutableListOf<String>()
            val clients =
                listOf(
                    YouTubeClient.WEB_REMIX,
                    YouTubeClient.ANDROID_MUSIC,
                    YouTubeClient.TVHTML5,
                )

            val result =
                resolveWithYouTubeClientFallback(clients) { client ->
                    attemptedClients += client.clientName
                    "tracking".takeIf { client == YouTubeClient.ANDROID_MUSIC }
                }

            assertEquals(YouTubeClient.ANDROID_MUSIC, result?.client)
            assertEquals("tracking", result?.value)
            assertEquals(listOf("WEB_REMIX", "ANDROID_MUSIC"), attemptedClients)
        }

    @Test
    fun resolverReturnsNullAfterEveryClientFails() =
        runBlocking {
            val clients = listOf(YouTubeClient.WEB_REMIX, YouTubeClient.ANDROID_MUSIC)
            val attemptedClients = mutableListOf<String>()

            val result =
                resolveWithYouTubeClientFallback<String>(clients) { client ->
                    attemptedClients += client.clientName
                    null
                }

            assertNull(result)
            assertEquals(listOf("WEB_REMIX", "ANDROID_MUSIC"), attemptedClients)
        }

    @Test
    fun progressiveTrackingFallbackOnlyUsesAuthenticatedClients() {
        assertTrue(YOUTUBE_MUSIC_HISTORY_TRACKING_CLIENTS.all(YouTubeClient::loginSupported))
    }
}
