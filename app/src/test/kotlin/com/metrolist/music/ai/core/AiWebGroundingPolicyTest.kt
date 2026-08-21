/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ai.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiWebGroundingPolicyTest {
    @Test
    fun `enables research only for compatible direct Gemini models`() {
        assertTrue(AiWebGroundingPolicy.shouldEnable("gemini", "gemini-3-flash-preview", "playlist dreamcore anos 2000"))
        assertFalse(AiWebGroundingPolicy.shouldEnable("openrouter", "google/gemini-3-flash", "playlist dreamcore"))
        assertFalse(AiWebGroundingPolicy.shouldEnable("gemini", "gemini-2.5-flash", "playlist dreamcore"))
    }

    @Test
    fun `does not spend grounding on ordinary chat`() {
        assertFalse(AiWebGroundingPolicy.shouldEnable("gemini", "gemini-3-flash-preview", "oi, tudo bem?"))
    }
}
