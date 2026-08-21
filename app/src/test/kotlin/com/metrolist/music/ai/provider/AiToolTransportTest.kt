package com.metrolist.music.ai.provider

import com.metrolist.music.ai.model.AiConversationMessage
import com.metrolist.music.ai.model.AiPendingToolCall
import com.metrolist.music.ai.model.AiProviderConfig
import com.metrolist.music.ai.model.AiRequest
import com.metrolist.music.ai.model.AiToolDefinition
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class AiToolTransportTest {
    @Test
    fun `Gemini preserves thought signature and groups parallel responses`() {
        val provider = GeminiProvider(descriptor("gemini"), OkHttpClient())
        val body = provider.buildBody(request(geminiMetadata()))
        val contents = body["contents"]!!.jsonArray
        val assistantParts = contents[0].jsonObject["parts"]!!.jsonArray
        val responseParts = contents[1].jsonObject["parts"]!!.jsonArray

        assertEquals("signature-123", assistantParts[0].jsonObject["thoughtSignature"]!!.jsonPrimitive.content)
        assertEquals(2, assistantParts.size)
        assertEquals(2, responseParts.size)
        assertNotNull(responseParts[0].jsonObject["functionResponse"])
        assertNotNull(responseParts[1].jsonObject["functionResponse"])
    }

    @Test
    fun `Gemini reads thought signature from function call part`() {
        val call =
            geminiPendingToolCall(
                buildJsonObject {
                    put("thoughtSignature", "signature-456")
                    put(
                        "functionCall",
                        buildJsonObject {
                            put("id", "call-1")
                            put("name", "search_music")
                            put("args", buildJsonObject { put("query", "Ado") })
                        },
                    )
                },
            )

        assertEquals("signature-456", call!!.transportMetadata["thoughtSignature"]!!.jsonPrimitive.content)
    }

    @Test
    fun `Gemini combines Google Search only when explicitly enabled and supported`() {
        val provider = GeminiProvider(descriptor("gemini"), OkHttpClient())
        val grounded = provider.buildBody(request(geminiMetadata()).copy(enableWebSearch = true), true)
        val unsupported = provider.buildBody(request(geminiMetadata()).copy(enableWebSearch = true), false)

        assertEquals(2, grounded["tools"]!!.jsonArray.size)
        assertNotNull(grounded["tools"]!!.jsonArray[1].jsonObject["googleSearch"])
        assertEquals(1, unsupported["tools"]!!.jsonArray.size)
    }

    @Test
    fun `OpenAI compatible transport preserves Gemini extra content`() {
        val provider = OpenAiCompatibleProvider(descriptor("openrouter"), OkHttpClient())
        val body =
            provider.buildChatBody(
                request(openAiMetadata()),
                AiProviderConfig("openrouter", "key", "https://example.com", "google/gemini-3-flash"),
            )
        val toolCall =
            body["messages"]!!.jsonArray[1].jsonObject["tool_calls"]!!.jsonArray.single().jsonObject

        assertEquals(
            "signature-789",
            toolCall["extra_content"]!!
                .jsonObject["google"]!!
                .jsonObject["thought_signature"]!!
                .jsonPrimitive.content,
        )
    }

    @Test
    fun `OpenRouter web search is opt in and coexists with app tools`() {
        val provider = OpenAiCompatibleProvider(descriptor("openrouter"), OkHttpClient())
        val config = AiProviderConfig("openrouter", "key", "https://example.com", "openrouter/free")

        val disabled = provider.buildChatBody(request(openAiMetadata()), config)
        val enabled =
            provider.buildChatBody(
                request(openAiMetadata()).copy(enableWebSearch = true),
                config,
            )

        assertEquals(1, disabled["tools"]!!.jsonArray.size)
        assertEquals(2, enabled["tools"]!!.jsonArray.size)
        assertEquals(
            "openrouter:web_search",
            enabled["tools"]!!.jsonArray.first().jsonObject["type"]!!.jsonPrimitive.content,
        )
        assertEquals(
            "function",
            enabled["tools"]!!.jsonArray.last().jsonObject["type"]!!.jsonPrimitive.content,
        )
    }

    private fun request(metadata: JsonObject): AiRequest =
        AiRequest(
            systemPrompt = "test",
            tools =
                listOf(
                    AiToolDefinition(
                        name = "search_music",
                        description = "test",
                        inputSchema = buildJsonObject { put("type", "object") },
                    ),
                ),
            messages =
                listOf(
                    AiConversationMessage.Assistant(
                        text = "",
                        toolCalls =
                            listOf(
                                AiPendingToolCall(
                                    id = "call-1",
                                    name = "search_music",
                                    arguments = buildJsonObject { put("query", "Ado") },
                                    transportMetadata = metadata,
                                ),
                                AiPendingToolCall(
                                    id = "call-2",
                                    name = "get_related_songs",
                                    arguments = buildJsonObject { put("seedSongId", "song-1") },
                                ),
                            ),
                    ),
                    AiConversationMessage.ToolResult("call-1", "search_music", buildJsonObject { put("ok", true) }),
                    AiConversationMessage.ToolResult("call-2", "get_related_songs", buildJsonObject { put("ok", true) }),
                ),
        )

    private fun geminiMetadata(): JsonObject = buildJsonObject { put("thoughtSignature", "signature-123") }

    private fun openAiMetadata(): JsonObject =
        buildJsonObject {
            put(
                "google",
                buildJsonObject { put("thought_signature", "signature-789") },
            )
        }

    private fun descriptor(id: String) =
        AiProviderDescriptor(
            id = id,
            displayName = id,
            defaultBaseUrl = "https://example.com",
            supportsDynamicModels = true,
            supportsTools = true,
            supportsStreaming = true,
            requiresApiKey = true,
        )
}
