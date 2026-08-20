/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ai.provider

import com.metrolist.music.ai.core.AiError
import com.metrolist.music.ai.core.AiErrorType
import com.metrolist.music.ai.core.AiStreamEvent
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
import java.util.UUID

class AnthropicProvider(
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
                val data =
                    aiJson.parseToJsonElement(body.orEmpty()).jsonObject["data"]?.jsonArray
                        ?: JsonArray(emptyList())
                data.mapNotNull { element ->
                    val model = element.jsonObject
                    val id = model["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    if (!AiModelClassifier.isTextModel(descriptor.id, id)) return@mapNotNull null
                    AiModel(
                        id = id,
                        displayName = model["display_name"]?.jsonPrimitive?.contentOrNull ?: id,
                        providerId = descriptor.id,
                        capabilities =
                            setOf(
                                AiCapability.TEXT,
                                AiCapability.STREAMING,
                                AiCapability.TOOLS,
                                AiCapability.STRUCTURED_OUTPUT,
                            ),
                        metadata =
                            buildMap {
                                model["created_at"]?.jsonPrimitive?.contentOrNull?.let { put("createdAt", it) }
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
            val call =
                client.newCall(
                    requestBuilder(config, "messages")
                        .post(buildBody(request, config).toString().toRequestBody(jsonMediaType))
                        .build(),
                )
            val cancellationHandle = currentCoroutineContext()[Job]?.invokeOnCompletion { call.cancel() }
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        throw parseProviderError(response.code, response.body?.string(), response.header("Retry-After"))
                    }
                    val source = response.body?.source() ?: error("Empty response body")
                    val tools = mutableMapOf<Int, ToolBuilder>()
                    var completed = false
                    while (!source.exhausted()) {
                        currentCoroutineContext().ensureActive()
                        val line = source.readUtf8Line() ?: break
                        if (!line.startsWith("data:")) continue
                        val data = line.removePrefix("data:").trim()
                        if (data.isBlank()) continue
                        val event = aiJson.parseToJsonElement(data).jsonObject
                        when (event["type"]?.jsonPrimitive?.contentOrNull) {
                            "error" -> throw parseProviderError(400, event.toString())
                            "message_start" -> {
                                val usage =
                                    ((event["message"] as? JsonObject)?.get("usage") as? JsonObject)
                                if (usage != null) {
                                    emit(
                                        AiStreamEvent.Usage(
                                            inputTokens = usage["input_tokens"]?.jsonPrimitive?.longOrNull,
                                            outputTokens = usage["output_tokens"]?.jsonPrimitive?.longOrNull,
                                        ),
                                    )
                                }
                            }
                            "content_block_start" -> {
                                val index = event["index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
                                val block = event["content_block"]?.jsonObject ?: continue
                                if (block["type"]?.jsonPrimitive?.contentOrNull == "tool_use") {
                                    val id = block["id"]?.jsonPrimitive?.contentOrNull ?: "tool_${UUID.randomUUID()}"
                                    val name = block["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                                    tools[index] = ToolBuilder(id, name)
                                    emit(AiStreamEvent.ToolCallStarted(id, name))
                                }
                            }
                            "content_block_delta" -> {
                                val index = event["index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
                                val delta = event["delta"]?.jsonObject ?: continue
                                when (delta["type"]?.jsonPrimitive?.contentOrNull) {
                                    "text_delta" ->
                                        delta["text"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotEmpty)?.let {
                                            emit(AiStreamEvent.TextDelta(it))
                                        }
                                    "input_json_delta" -> {
                                        val arguments = delta["partial_json"]?.jsonPrimitive?.contentOrNull.orEmpty()
                                        tools[index]?.arguments?.append(arguments)
                                        tools[index]?.let {
                                            emit(AiStreamEvent.ToolCallArgumentsDelta(it.id, arguments))
                                        }
                                    }
                                }
                            }
                            "content_block_stop" -> {
                                val index = event["index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
                                tools.remove(index)?.let { builder ->
                                    val arguments =
                                        if (builder.arguments.isBlank()) {
                                            JsonObject(emptyMap())
                                        } else {
                                            runCatching {
                                                aiJson.parseToJsonElement(builder.arguments.toString()).jsonObject
                                            }.getOrElse {
                                                emit(
                                                    AiStreamEvent.Error(
                                                        AiError(
                                                            AiErrorType.PARSING,
                                                            "The provider returned invalid arguments for ${builder.name}.",
                                                            cause = it,
                                                        ),
                                                    ),
                                                )
                                                return@let
                                            }
                                        }
                                    emit(
                                        AiStreamEvent.ToolCallCompleted(
                                            AiPendingToolCall(builder.id, builder.name, arguments),
                                        ),
                                    )
                                }
                            }
                            "message_delta" -> {
                                val usage = event["usage"] as? JsonObject
                                if (usage != null) {
                                    emit(
                                        AiStreamEvent.Usage(
                                            inputTokens = null,
                                            outputTokens = usage["output_tokens"]?.jsonPrimitive?.longOrNull,
                                        ),
                                    )
                                }
                            }
                            "message_stop" -> {
                                emit(AiStreamEvent.Completed)
                                completed = true
                            }
                        }
                    }
                    if (!completed) emit(AiStreamEvent.Completed)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                emit(AiStreamEvent.Error(throwable.toAiError()))
            } finally {
                cancellationHandle?.dispose()
            }
        }.flowOn(Dispatchers.IO)

    private fun buildBody(
        request: AiRequest,
        config: AiProviderConfig,
    ): JsonObject =
        buildJsonObject {
            put("model", config.modelId)
            put("system", request.systemPrompt)
            put("stream", true)
            put("temperature", request.temperature)
            put("max_tokens", request.maxOutputTokens)
            put(
                "messages",
                buildJsonArray { request.messages.forEach { add(it.toAnthropicJson()) } },
            )
            if (request.tools.isNotEmpty()) {
                put(
                    "tools",
                    buildJsonArray {
                        request.tools.forEach { tool ->
                            add(
                                buildJsonObject {
                                    put("name", tool.name)
                                    put("description", tool.description)
                                    put("input_schema", tool.inputSchema)
                                },
                            )
                        }
                    },
                )
            }
        }

    private fun AiConversationMessage.toAnthropicJson(): JsonObject =
        when (this) {
            is AiConversationMessage.User ->
                buildJsonObject {
                    put("role", "user")
                    put("content", text)
                }
            is AiConversationMessage.Assistant ->
                buildJsonObject {
                    put("role", "assistant")
                    put(
                        "content",
                        buildJsonArray {
                            if (text.isNotBlank()) {
                                add(
                                    buildJsonObject {
                                        put("type", "text")
                                        put("text", text)
                                    },
                                )
                            }
                            toolCalls.forEach { call ->
                                add(
                                    buildJsonObject {
                                        put("type", "tool_use")
                                        put("id", call.id)
                                        put("name", call.name)
                                        put("input", call.arguments)
                                    },
                                )
                            }
                        },
                    )
                }
            is AiConversationMessage.ToolResult ->
                buildJsonObject {
                    put("role", "user")
                    put(
                        "content",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("type", "tool_result")
                                    put("tool_use_id", toolCallId)
                                    put("content", payload.toString())
                                },
                            )
                        },
                    )
                }
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
            .addHeader("anthropic-version", "2023-06-01")
            .apply {
                if (config.apiKey.isNotBlank()) addHeader("x-api-key", config.apiKey.trim())
                config.customHeaders.forEach { (name, value) -> addHeader(name, value) }
            }
    }

    private data class ToolBuilder(
        val id: String,
        val name: String,
        val arguments: StringBuilder = StringBuilder(),
    )
}
