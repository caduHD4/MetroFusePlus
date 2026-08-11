package com.metrolist.music.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdaterTest {
    @Test
    fun comparesSemanticVersionNumbers() {
        assertEquals(1, Updater.compareVersions("7.0.5", "7.0.4"))
        assertEquals(0, Updater.compareVersions("v7.0.5", "7.0.5"))
        assertEquals(-1, Updater.compareVersions("7.0.4", "7.0.5"))
    }

    @Test
    fun recognizesMetroFusePlusReleaseAssets() {
        assertEquals("universal" to "foss", Updater.inferAssetTarget("MetroFusePlus.apk"))
        assertEquals(
            "universal" to "gms",
            Updater.inferAssetTarget("MetroFusePlus-with-Google-Cast.apk"),
        )
        assertNull(Updater.inferAssetTarget("MetroFusePlus-izzy.apk"))
    }
}
