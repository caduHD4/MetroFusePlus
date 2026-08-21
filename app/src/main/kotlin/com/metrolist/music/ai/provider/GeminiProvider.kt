/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ai.provider

import com.metrolist.music.ai.core.AiStreamEvent
import com.metrolist.music.ai.core.AiGroundingMetadata
import com.metrolist.music.ai.core.AiGroundingSource
import com.metrolist.music.ai.core.runCatchingPreservingCancellation
import com.metrolist.music.ai.model.AiCapability
import com.metrolist.music.ai.model.AiConversationMessage
import com.metrolist.music.ai.model.AiModel
import com.metrolist.music.ai.model.AiPendingToolCall
import com.metrolist.music.ai.model.AiProviderConfig
import com.metrolist.music.ai.model.AiRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.UUID

class GeminiProvider(
    override val descriptor: AiProviderDescriptor,
    private val client: OkHttpClient,
) : AiProvider {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    override suspend fun validateCredentials(config: AiProviderConfig): Result<Unit> = listModels(config).map { Unit }

    override suspend fun listModels(config: AiProviderConfig): Result<List<AiModel>> =
        runCatchingPreservingCancellation {
            val response = client.newCall(requestBuilder(config, "models").get().build()).execute()
            response.use {
                val body = it.body?.string()
                if (!it.isSuccessful) {
                    throw parseProviderError(it.code, body, it.header("Retry-After"))
                }
                val models =
                    aiJson.parseToJsonElement(body.orEmpty()).jsonObject["models"]?.jsonArray
                        ?: JsonArray(emptyList())
                models.mapNotNull { element ->
                    val model = element.jsonObject
                    val methods = model["supportedGenerationMethods"]?.jsonArray.orEmpty()
                    if (methods.none { method -> method.jsonPrimitive.contentOrNull == "generateContent" }) {
                        return@mapNotNull null
                    }
                    val id =
                        model["name"]?.jsonPrimitive?.contentOrNull
                            ?.removePrefix("models/")
                            ?: return@mapNotNull null
                    if (!AiModelClassifier.isTextModel(descriptor.id, id)) return@mapNotNull null
                    AiModel(
                        id = id,
                        displayName = model["displayName"]?.jsonPrimitive?.contentOrNull ?: id,
                        providerId = descriptor.id,
                        contextWindow = model["inputTokenLimit"]?.jsonPrimitive?.longOrNull,
                        capabilities =
                            setOf(
                                AiCapability.TEXT,
                                AiCapability.STREAMING,
                                AiCapability.TOOLS,
                                AiCapability.STRUCTURED_OUTPUT,
                                AiCapability.VISION,
                            ),
                        metadata =
                            buildMap {
                                model["description"]?.jsonPrimitive?.contentOrNull?.let {
                                    put("description", it.take(240))
                                }
                            },
                    )
                }.distinctBy(AiModel::id).sortedBy { it.displayName.lowercase() }
            }
        }

    override fun streamResponse(
        request: AiRequest,
        config: AiProviderConfig,
    ): Flow<AiStreamEvent> =
        flow {
            val model = URLEncoder.encode(config.modelId.removePrefix("models/"), Charsets.UTF_8.name())
            val path = "models/$model:streamGenerateContent?alt=sse"
            val call =
                client.newCall(
                    requestBuilder(config, path)
                        .post(
                            buildBody(
                                request,
                                googleSearchSupported = supportsCombinedGoogleSearch(config.modelId),
                            ).toString().toRequestBody(jsonMediaType),
                        )
                        .build(),
                )
            val cancellationHandle = currentCoroutineContext()[Job]?.invokeOnCompletion { call.cancel() }
            try {
                val seenGroundingQueries = linkedSetOf<String>()
                val seenGroundingSources = linkedMapOf<String, AiGroundingSource>()
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        throw parseProviderError(response.code, response.body?.string(), response.header("Retry-After"))
                    }
                    val source = response.body?.source() ?: error("Empty response body")
                    while (!source.exhausted()) {
                        currentCoroutineContext().ensureActive()
                        val line = source.readUtf8Line() ?: break
                        if (!line.startsWith("data:")) continue
                        val data = line.removePrefix("data:").trim()
                        if (data.isBlank()) continue
                        val root = aiJson.parseToJsonElement(data).jsonObject
                        root["error"]?.let { throw parseProviderError(400, root.toString()) }
                        root["candidates"]?.jsonArray?.forEach { candidateElement ->
                            val candidate = candidateElement.jsonObject
                            candidate["groundingMetadata"]?.jsonObject?.let { grounding ->
                                grounding["webSearchQueries"]?.jsonArray.orEmpty().forEach { query ->
                                    query.jsonPrimitive.contentOrNull?.let(seenGroundingQueries::add)
                                }
                                grounding["groundingChunks"]?.jsonArray.orEmpty().forEach chunkLoop@{ chunk ->
                                    val web = chunk.jsonObject["web"] as? JsonObject ?: return@chunkLoop
                                    val url = web["uri"]?.jsonPrimitive?.contentOrNull ?: return@chunkLoop
                                    val title = web["title"]?.jsonPrimitive?.contentOrNull ?: url
                                    seenGroundingSources[url] = AiGroundingSource(title, url)
                                }
                            }
                            candidate["content"]?.jsonObject?.get("parts")?.jsonArray?.forEach { partElement ->
                                val part = partElement.jsonObject
                                part["text"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotEmpty)?.let {
                                    emit(AiStreamEvent.TextDelta(it))
                                }
                                geminiPendingToolCall(part)?.let { call ->
                                    if (call.name.isNotBlank()) {
                                        emit(AiStreamEvent.ToolCallStarted(call.id, call.name))
                                        emit(
                                            AiStreamEvent.ToolCallCompleted(call),
                                        )
                                    }
                                }
                            }
                        }
                        (root["usageMetadata"] as? JsonObject)?.let { usage ->
                            emit(
                                AiStreamEvent.Usage(
                                    inputTokens = usage["promptTokenCount"]?.jsonPrimitive?.longOrNull,
                                    outputTokens = usage["candidatesTokenCount"]?.jsonPrimitive?.longOrNull,
                                ),
                            )
                        }
                    }
                    if (seenGroundingQueries.isNotEmpty() || seenGroundingSources.isNotEmpty()) {
                        emit(
                            AiStreamEvent.Grounding(
                                AiGroundingMetadata(
                                    queries = seenGroundingQueries.toList(),
                                    sources = seenGroundingSources.values.toList(),
                                ),
                            ),
                        )
                    }
                    emit(AiStreamEvent.Completed)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                emit(AiStreamEvent.Error(throwable.toAiError()))
            } finally {
                cancellationHandle?.dispose()
            }
        }.flowOn(Dispatchers.IO)

    internal fun buildBody(
        request: AiRequest,
        googleSearchSupported: Boolean = true,
    ): JsonObject =
        buildJsonObject {
            put(
                "systemInstruction",
                buildJsonObject {
                    put(
                        "parts",
                        buildJsonArray { add(buildJsonObject { put("text", request.systemPrompt) }) },
                    )
                },
            )
            put(
                "contents",
                JsonArray(request.messages.toGeminiContents()),
            )
            put(
                "generationConfig",
                buildJsonObject {
                    put("temperature", request.temperature)
                    put("maxOutputTokens", request.maxOutputTokens)
                },
            )
            if (request.tools.isNotEmpty() || (request.enableGoogleSearch && googleSearchSupported)) {
                put(
                    "tools",
                    buildJsonArray {
                        if (request.tools.isNotEmpty()) {
                            add(
                                buildJsonObject {
                                    put(
                                        "functionDeclarations",
                                        buildJsonArray {
                                            request.tools.forEach { tool ->
                                                add(
                                                    buildJsonObject {
                                                        put("name", tool.name)
                                                        put("description", tool.description)
                                                        put("parameters", tool.inputSchema.toGeminiSchema())
                                                    },
                                                )
                                            }
                                        },
                                    )
                                },
                            )
                        }
                        if (request.enableGoogleSearch && googleSearchSupported) {
                            add(buildJsonObject { put("googleSearch", buildJsonObject {}) })
                        }
                    },
                )
                if (request.tools.isNotEmpty()) {
                    put(
                        "toolConfig",
                        buildJsonObject {
                            put(
                                "functionCallingConfig",
                                buildJsonObject { put("mode", "AUTO") },
                            )
                        },
                    )
                }
            }
        }

    private fun List<AiConversationMessage>.toGeminiContents(): List<JsonObject> {
        val contents = mutableListOf<JsonObject>()
        var index = 0
        while (index < size) {
            val message = this[index]
            if (message is AiConversationMessage.ToolResult) {
                val results = mutableListOf<AiConversationMessage.ToolResult>()
                while (index < size && this[index] is AiConversationMessage.ToolResult) {
                    results += this[index] as AiConversationMessage.ToolResult
                    index++
                }
                contents +=
                    content(
                        role = "user",
                        parts = results.map { it.toGeminiFunctionResponse() },
                    )
                continue
            }
            contents += message.toGeminiJson()
            index++
        }
        return contents
    }

    private fun AiConversationMessage.toGeminiJson(): JsonObject =
        when (this) {
            is AiConversationMessage.User ->
                content(role = "user", parts = listOf(buildJsonObject { put("text", text) }))

            is AiConversationMessage.Assistant ->
                content(
                    role = "model",
                    parts =
                        buildList {
                            if (text.isNotBlank()) add(buildJsonObject { put("text", text) })
                            toolCalls.forEach { call ->
                                add(
                                    buildJsonObject {
                                        call.transportMetadata["thoughtSignature"]?.let {
                                            put("thoughtSignature", it)
                                        }
                                        put(
                                            "functionCall",
                                            buildJsonObject {
                                                put("id", call.id)
                                                put("name", call.name)
                                                put("args", call.arguments)
                                            },
                                        )
                                    },
                                )
                            }
                        },
                )

            is AiConversationMessage.ToolResult ->
                content(
                    role = "user",
                    parts = listOf(toGeminiFunctionResponse()),
                )
        }

    private fun AiConversationMessage.ToolResult.toGeminiFunctionResponse(): JsonObject =
        buildJsonObject {
            put(
                "functionResponse",
                buildJsonObject {
                    put("id", toolCallId)
                    put("name", toolName)
                    put("response", payload.asResponseObject())
                },
            )
        }

    private fun content(
        role: String,
        parts: List<JsonObject>,
    ): JsonObject =
        buildJsonObject {
            put("role", role)
            put("parts", JsonArray(parts))
        }

    private fun JsonElement.asResponseObject(): JsonObject =
        this as? JsonObject ?: buildJsonObject { put("result", this@asResponseObject) }

    private fun JsonElement.toGeminiSchema(): JsonElement =
        when (this) {
            is JsonObject ->
                JsonObject(
                    this
                        .filterKeys { key -> key !in GEMINI_UNSUPPORTED_SCHEMA_KEYS }
                        .mapValues { (_, value) -> value.toGeminiSchema() },
                )
            is JsonArray -> JsonArray(map { it.toGeminiSchema() })
            else -> this
        }

    private fun requestBuilder(
        config: AiProviderConfig,
        path: String,
    ): Request.Builder {
        val baseUrl = normalizeBaseUrl(config.baseUrl, descriptor.defaultBaseUrl)
        return Request
            .Builder()
            .url("$baseUrl/$path")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json")
            .apply {
                if (config.apiKey.isNotBlank()) addHeader("x-goog-api-key", config.apiKey.trim())
                config.customHeaders.forEach { (name, value) -> addHeader(name, value) }
            }
    }

    companion object {
        private val GEMINI_UNSUPPORTED_SCHEMA_KEYS = setOf("\$schema", "additionalProperties", "uniqueItems")
    }
}

private fun supportsCombinedGoogleSearch(modelId: String): Boolean =
    modelId.removePrefix("models/").lowercase().startsWith("gemini-3")

internal fun geminiPendingToolCall(part: JsonObject): AiPendingToolCall? {
    val function = part["functionCall"] as? JsonObject ?: return null
    val name = function["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
    if (name.isBlank()) return null
    val id = function["id"]?.jsonPrimitive?.contentOrNull ?: "tool_${UUID.randomUUID()}"
    val arguments = function["args"] as? JsonObject ?: JsonObject(emptyMap())
    val transportMetadata =
        buildJsonObject {
            part["thoughtSignature"]?.let { put("thoughtSignature", it) }
        }
    return AiPendingToolCall(id, name, arguments, transportMetadata)
}
