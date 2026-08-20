package com.metrolist.music.ai.playlist

import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiPlaylistSafetyTest {
    @Test
    fun `rejects IDs that were not returned by catalog tools`() {
        val artifacts = AiSessionArtifacts()
        artifacts.rememberSongs(listOf(song("real-1", "Artist A")))

        val resolution = artifacts.resolveSongs(listOf("real-1", "invented-id"))

        assertEquals(listOf("real-1"), resolution.songs.map { it.id })
        assertEquals(listOf("invented-id"), resolution.missingIds)
    }

    @Test
    fun `candidate pool is bounded and keeps real objects`() {
        val artifacts = AiSessionArtifacts(maxCandidatePool = 3)
        artifacts.rememberSongs((1..10).map { song("id-$it", "Artist $it") })

        val resolution = artifacts.resolveSongs((1..10).map { "id-$it" })

        assertEquals(3, resolution.songs.size)
        assertEquals(7, resolution.missingIds.size)
    }

    @Test
    fun `ranker removes duplicates and limits one dominant artist`() {
        val ranker = AiPlaylistRanker()
        val selected =
            listOf(
                song("a1", "Artist A"),
                song("a1", "Artist A"),
                song("a2", "Artist A"),
                song("a3", "Artist A"),
                song("b1", "Artist B"),
                song("c1", "Artist C"),
            )

        val ranked = ranker.finalizeSelection(selected, targetCount = 5)

        assertEquals(ranked.size, ranked.distinctBy { it.id }.size)
        assertTrue(ranked.take(4).any { it.artists.first().name == "Artist B" })
        assertFalse(ranked.isEmpty())
    }

    @Test
    fun `playlist IDs must be observed before detailed access`() {
        val artifacts = AiSessionArtifacts()

        assertFalse(artifacts.knowsPlaylist("invented"))
        artifacts.rememberPlaylistIds(listOf("real-playlist"))
        assertTrue(artifacts.knowsPlaylist("real-playlist"))
        assertFalse(artifacts.knowsPlaylist("invented"))
    }

    @Test
    fun `playlist draft exists only after the pending action is confirmed`() {
        val artifacts = AiSessionArtifacts()
        val intent = AiPlaylistIntent("Night drive", null, 1)
        val action = artifacts.createPlaylistDraftAction(intent, listOf(song("real-1", "Artist A")))

        assertTrue(artifacts.draft(action.id) == null)
        val draft = artifacts.confirmPlaylistDraftAction(action.id)

        assertEquals("Night drive", draft?.intent?.title)
        assertEquals(listOf("real-1"), draft?.songs?.map { it.id })
        assertTrue(artifacts.pendingAction(action.id) == null)
    }

    private fun song(
        id: String,
        artist: String,
    ) = SongItem(
        id = id,
        title = id,
        artists = listOf(Artist(artist, artist)),
        thumbnail = "https://example.invalid/$id.jpg",
    )
}
