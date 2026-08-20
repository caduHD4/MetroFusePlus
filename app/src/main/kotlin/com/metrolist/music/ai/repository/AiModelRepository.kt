/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ai.repository

import android.content.Context
import com.metrolist.music.ai.model.AiModel
import com.metrolist.music.ai.model.AiProviderConfig
import com.metrolist.music.ai.provider.AiModelClassifier
import com.metrolist.music.ai.provider.AiProviderRegistry
import com.metrolist.music.ai.provider.aiJson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AiModelRepository
@Inject
constructor(
    @ApplicationContext context: Context,
    private val providerRegistry: AiProviderRegistry,
) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    suspend fun models(
        config: AiProviderConfig,
        forceRefresh: Boolean = false,
    ): AiModelCatalog {
        val provider = providerRegistry.requireProvider(config.providerId)
        val cached = readCache(provider.descriptor.id)
        val now = System.currentTimeMillis()
        if (!forceRefresh && cached != null && now - cached.fetchedAt < CACHE_TTL_MILLIS) {
            return AiModelCatalog(cached.models, AiModelSource.CACHE)
        }

        if (provider.descriptor.supportsDynamicModels && (!provider.descriptor.requiresApiKey || config.apiKey.isNotBlank())) {
            provider.listModels(config).fold(
                onSuccess = { discovered ->
                    if (discovered.isNotEmpty()) {
                        writeCache(provider.descriptor.id, discovered, now)
                        return AiModelCatalog(discovered, AiModelSource.NETWORK)
                    }
                },
                onFailure = { error ->
                    if (cached != null) {
                        return AiModelCatalog(cached.models, AiModelSource.STALE_CACHE, error.message)
                    }
                    return fallbackCatalog(provider.descriptor.id, error.message)
                },
            )
        }

        if (cached != null) return AiModelCatalog(cached.models, AiModelSource.STALE_CACHE)
        return fallbackCatalog(provider.descriptor.id)
    }

    suspend fun validate(config: AiProviderConfig): Result<Unit> =
        providerRegistry.requireProvider(config.providerId).validateCredentials(config)

    fun clear(providerId: String) {
        preferences.edit().remove(cacheKey(providerId)).apply()
    }

    private fun fallbackCatalog(
        providerId: String,
        warning: String? = null,
    ): AiModelCatalog {
        val descriptor = providerRegistry.descriptor(providerId) ?: return AiModelCatalog(emptyList(), AiModelSource.FALLBACK, warning)
        val models =
            descriptor.fallbackModelIds.map { id ->
                AiModel(
                    id = id,
                    displayName = id,
                    providerId = descriptor.id,
                    capabilities = AiModelClassifier.capabilities(descriptor, id),
                    metadata = mapOf("source" to "offline_fallback"),
                )
            }
        return AiModelCatalog(models, AiModelSource.FALLBACK, warning)
    }

    private fun readCache(providerId: String): CachedModels? =
        preferences.getString(cacheKey(providerId), null)?.let { encoded ->
            runCatching { aiJson.decodeFromString<CachedModels>(encoded) }.getOrNull()
        }

    private fun writeCache(
        providerId: String,
        models: List<AiModel>,
        fetchedAt: Long,
    ) {
        val encoded = aiJson.encodeToString(CachedModels(models, fetchedAt))
        preferences.edit().putString(cacheKey(providerId), encoded).apply()
    }

    private fun cacheKey(providerId: String): String = "models:${providerId.lowercase()}"

    companion object {
        private const val PREFERENCES_NAME = "ai_model_cache"
        private const val CACHE_TTL_MILLIS = 8 * 60 * 60 * 1000L
    }
}

data class AiModelCatalog(
    val models: List<AiModel>,
    val source: AiModelSource,
    val warning: String? = null,
)

enum class AiModelSource {
    NETWORK,
    CACHE,
    STALE_CACHE,
    FALLBACK,
}

@Serializable
private data class CachedModels(
    val models: List<AiModel>,
    val fetchedAt: Long,
)
