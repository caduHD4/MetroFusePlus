/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ai.provider

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AiProviderRegistry
@Inject
constructor() {
    private val client = createAiHttpClient()

    private val descriptors =
        listOf(
            AiProviderDescriptor(
                id = "openrouter",
                displayName = "OpenRouter",
                defaultBaseUrl = "https://openrouter.ai/api/v1",
                supportsDynamicModels = true,
                supportsTools = true,
                supportsStreaming = true,
                requiresApiKey = true,
                fallbackModelIds = listOf("openrouter/free"),
            ),
            AiProviderDescriptor(
                id = "openai",
                displayName = "OpenAI",
                defaultBaseUrl = "https://api.openai.com/v1",
                supportsDynamicModels = true,
                supportsTools = true,
                supportsStreaming = true,
                requiresApiKey = true,
                fallbackModelIds = listOf("gpt-4o-mini"),
            ),
            AiProviderDescriptor(
                id = "gemini",
                displayName = "Gemini",
                defaultBaseUrl = "https://generativelanguage.googleapis.com/v1beta",
                supportsDynamicModels = true,
                supportsTools = true,
                supportsStreaming = true,
                requiresApiKey = true,
                fallbackModelIds = listOf("gemini-2.5-flash-lite", "gemini-2.5-flash"),
            ),
            AiProviderDescriptor(
                id = "anthropic",
                displayName = "Anthropic",
                defaultBaseUrl = "https://api.anthropic.com/v1",
                supportsDynamicModels = true,
                supportsTools = true,
                supportsStreaming = true,
                requiresApiKey = true,
                fallbackModelIds = listOf("claude-sonnet-4-6", "claude-haiku-4-5-20251001"),
            ),
            AiProviderDescriptor(
                id = "groq",
                displayName = "Groq",
                defaultBaseUrl = "https://api.groq.com/openai/v1",
                supportsDynamicModels = true,
                supportsTools = true,
                supportsStreaming = true,
                requiresApiKey = true,
                fallbackModelIds = listOf("llama-3.3-70b-versatile"),
            ),
            AiProviderDescriptor(
                id = "xai",
                displayName = "xAI",
                defaultBaseUrl = "https://api.x.ai/v1",
                supportsDynamicModels = true,
                supportsTools = true,
                supportsStreaming = true,
                requiresApiKey = true,
                fallbackModelIds = listOf("grok-4-1-fast"),
            ),
            AiProviderDescriptor(
                id = "mistral",
                displayName = "Mistral",
                defaultBaseUrl = "https://api.mistral.ai/v1",
                supportsDynamicModels = true,
                supportsTools = true,
                supportsStreaming = true,
                requiresApiKey = true,
                fallbackModelIds = listOf("mistral-small-latest", "mistral-large-latest"),
            ),
            AiProviderDescriptor(
                id = "perplexity",
                displayName = "Perplexity",
                defaultBaseUrl = "https://api.perplexity.ai",
                supportsDynamicModels = false,
                supportsTools = false,
                supportsStreaming = true,
                requiresApiKey = true,
                fallbackModelIds = listOf("sonar", "sonar-pro"),
            ),
            AiProviderDescriptor(
                id = "custom",
                displayName = "Custom (OpenAI compatible)",
                defaultBaseUrl = null,
                supportsDynamicModels = true,
                supportsTools = false,
                supportsStreaming = true,
                requiresApiKey = false,
            ),
        )

    private val providers: Map<String, AiProvider> =
        descriptors.associate { descriptor ->
            val provider =
                when (descriptor.id) {
                    "gemini" -> GeminiProvider(descriptor, client)
                    "anthropic" -> AnthropicProvider(descriptor, client)
                    "openrouter" ->
                        OpenAiCompatibleProvider(
                            descriptor = descriptor,
                            client = client,
                            extraHeaders =
                                mapOf(
                                    "HTTP-Referer" to "https://github.com/caduHD4/MetroFusePlus",
                                    "X-Title" to "MetroFuse+",
                                ),
                        )
                    else -> OpenAiCompatibleProvider(descriptor, client)
                }
            descriptor.id to provider
        }

    fun provider(providerId: String): AiProvider? = providers[providerId.lowercase()]

    fun requireProvider(providerId: String): AiProvider =
        requireNotNull(provider(providerId)) { "Unknown AI provider: $providerId" }

    fun descriptors(): List<AiProviderDescriptor> = descriptors

    fun descriptor(providerId: String): AiProviderDescriptor? = provider(providerId)?.descriptor
}
