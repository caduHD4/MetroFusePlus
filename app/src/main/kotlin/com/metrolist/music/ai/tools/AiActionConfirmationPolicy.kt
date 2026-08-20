/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ai.tools

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AiActionConfirmationPolicy
@Inject
constructor() {
    fun requiresConfirmation(risk: AiToolRisk): Boolean = risk != AiToolRisk.READ_ONLY
}
