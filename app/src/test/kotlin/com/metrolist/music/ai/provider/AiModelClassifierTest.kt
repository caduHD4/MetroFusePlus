package com.metrolist.music.ai.provider

import com.metrolist.music.ai.model.AiCapability
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiModelClassifierTest {
    @Test
    fun `filters non-chat OpenAI models`() {
        assertTrue(AiModelClassifier.isTextModel("openai", "gpt-4o-mini"))
        assertTrue(AiModelClassifier.isTextModel("openai", "o3-mini"))
        assertFalse(AiModelClassifier.isTextModel("openai", "text-embedding-3-small"))
        assertFalse(AiModelClassifier.isTextModel("openai", "whisper-1"))
        assertFalse(AiModelClassifier.isTextModel("openai", "gpt-image-1"))
        assertFalse(AiModelClassifier.isTextModel("openai", "babbage-002"))
    }

    @Test
    fun `uses OpenRouter capability metadata instead of assuming tools`() {
        val descriptor = descriptor(id = "openrouter", supportsTools = true)
        val chatOnly = AiModelClassifier.capabilities(descriptor, "provider/chat-model")
        val withTools =
            AiModelClassifier.capabilities(
                descriptor,
                "provider/tool-model",
                buildJsonObject {
                    put(
                        "supported_parameters",
                        buildJsonArray {
                            add("temperature")
                            add("tools")
                        },
                    )
                },
            )

        assertFalse(AiCapability.TOOLS in chatOnly)
        assertTrue(AiCapability.TOOLS in withTools)
    }

    @Test
    fun `OpenRouter free router advertises tools because routing filters by request capability`() {
        val capabilities =
            AiModelClassifier.capabilities(
                descriptor(id = "openrouter", supportsTools = true),
                "openrouter/free",
            )

        assertTrue(AiCapability.TOOLS in capabilities)
    }

    @Test
    fun `respects explicit provider function calling capability`() {
        val descriptor = descriptor(id = "mistral", supportsTools = true)
        val capabilities =
            AiModelClassifier.capabilities(
                descriptor,
                "mistral-model",
                buildJsonObject {
                    put(
                        "capabilities",
                        buildJsonObject { put("function_calling", false) },
                    )
                },
            )

        assertFalse(AiCapability.TOOLS in capabilities)
    }

    private fun descriptor(
        id: String,
        supportsTools: Boolean,
    ) = AiProviderDescriptor(
        id = id,
        displayName = id,
        defaultBaseUrl = null,
        supportsDynamicModels = true,
        supportsTools = supportsTools,
        supportsStreaming = true,
        requiresApiKey = false,
    )
}
