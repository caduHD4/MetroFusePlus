/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ai.provider

import com.metrolist.music.ai.core.AiStreamEvent
import com.metrolist.music.ai.model.AiModel
import com.metrolist.music.ai.model.AiProviderConfig
import com.metrolist.music.ai.model.AiRequest
import kotlinx.coroutines.flow.Flow

interface AiProvider {
    val descriptor: AiProviderDescriptor

    suspend fun validateCredentials(config: AiProviderConfig): Result<Unit>

    suspend fun listModels(config: AiProviderConfig): Result<List<AiModel>>

    fun streamResponse(
        request: AiRequest,
        config: AiProviderConfig,
    ): Flow<AiStreamEvent>
}

data class AiProviderDescriptor(
    val id: String,
    val displayName: String,
    val defaultBaseUrl: String?,
    val supportsDynamicModels: Boolean,
    val supportsTools: Boolean,
    val supportsStreaming: Boolean,
    val requiresApiKey: Boolean,
    val fallbackModelIds: List<String> = emptyList(),
)
