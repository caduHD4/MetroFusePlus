package com.metrolist.music.ai.playlist

import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.SongItem
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiPlaylistCuratorTest {
    @Test
    fun `typed selection resolves only indexes from the closed candidate pool`() {
        val candidates = listOf(song("real-0"), song("real-1"), song("real-2"))
        val selection =
            resolveSelection(
                arguments =
                    buildJsonObject {
                        put("selectedIndexes", buildJsonArray { add(2); add(99); add(0); add(2) })
                        put("playlistName", "Curated")
                    },
                candidates = candidates,
                targetCount = 3,
            )

        assertEquals(listOf("real-2", "real-0"), selection.songs.map { it.id })
        assertEquals("Curated", selection.title)
        assertFalse(selection.songs.any { it.id == "99" })
    }

    @Test
    fun `curator can request broader searches without inventing songs`() {
        val selection =
            resolveSelection(
                arguments =
                    buildJsonObject {
                        put("selectedIndexes", buildJsonArray { })
                        put("additionalQueries", buildJsonArray { add("2000s ethereal trip hop"); add("surreal nostalgic downtempo") })
                        put("complete", false)
                    },
                candidates = listOf(song("real-0")),
                targetCount = 6,
            )

        assertTrue(selection.songs.isEmpty())
        assertEquals(2, selection.additionalQueries.size)
        assertFalse(selection.complete)
    }

    private fun song(id: String) =
        SongItem(
            id = id,
            title = id,
            artists = listOf(Artist("Artist", "artist")),
            thumbnail = "",
        )
}
