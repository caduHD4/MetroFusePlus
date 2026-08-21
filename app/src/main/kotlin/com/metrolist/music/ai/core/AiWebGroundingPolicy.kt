/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ai.core

internal object AiWebGroundingPolicy {
    private val researchSignals =
        listOf(
            "playlist",
            "genre",
            "gênero",
            "genero",
            "style",
            "estilo",
            "mood",
            "vibe",
            "aesthetic",
            "estética",
            "estetica",
            "scene",
            "cena",
            "era",
            "anos ",
            "recommend",
            "recomen",
            "similar",
        )

    fun shouldEnable(
        providerId: String,
        modelId: String,
        prompt: String,
    ): Boolean {
        if (providerId != "gemini") return false
        if (!modelId.removePrefix("models/").lowercase().startsWith("gemini-3")) return false
        val normalized = prompt.lowercase()
        return researchSignals.any(normalized::contains)
    }
}
