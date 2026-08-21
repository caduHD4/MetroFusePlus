/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ai.provider

import com.metrolist.music.ai.core.AiError
import com.metrolist.music.ai.core.AiErrorType
import com.metrolist.music.ai.core.AiGroundingMetadata
import com.metrolist.music.ai.core.AiGroundingSource
import com.metrolist.music.ai.core.AiStreamEvent
import com.metrolist.music.ai.core.runCatchingPreservingCancellation
import com.metrolist.music.ai.model.AiConversationMessage
import com.metrolist.music.ai.model.AiModel
import com.metrolist.music.ai.model.AiPendingToolCall
import com.metrolist.music.ai.model.AiProviderConfig
import com.metrolist.music.ai.model.AiRequest
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

class OpenAiCompatibleProvider(
    override val descriptor: AiProviderDescriptor,
    private val client: OkHttpClient,
    private val extraHeaders: Map<String, String> = emptyMap(),
) : AiProvider {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    override suspend fun validateCredentials(config: AiProviderConfig): Result<Unit> =
        if (descriptor.supportsDynamicModels && descriptor.id != "custom") {
            listModels(config).map { Unit }
        } else {
            runCatchingPreservingCancellation {
                require(config.modelId.isNotBlank()) { "A model ID is required to test this provider." }
                val body =
                    buildJsonObject {
                        put("model", config.modelId)
                        put(
                            "messages",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("role", "user")
                                        put("content", "Reply with OK.")
                                    },
                                )
                            },
                        )
                        put("max_tokens", 1)
                        put("stream", false)
                    }
                val response = client.newCall(buildRequest(config, "chat/completions", body)).execute()
                response.use {
                    if (!it.isSuccessful) {
                        throw parseProviderError(it.code, it.body?.string(), it.header("Retry-After"))
                    }
                }
            }
        }

    override suspend fun listModels(config: AiProviderConfig): Result<List<AiModel>> =
        runCatchingPreservingCancellation {
            check(descriptor.supportsDynamicModels) { "${descriptor.displayName} does not expose model discovery." }
            val response = client.newCall(buildGetRequest(config, "models")).execute()
            response.use {
                val responseBody = it.body?.string()
                if (!it.isSuccessful) {
                    throw parseProviderError(it.code, responseBody, it.header("Retry-After"))
                }
                val root = aiJson.parseToJsonElement(responseBody.orEmpty()).jsonObject
                val data = root["data"]?.jsonArray ?: JsonArray(emptyList())
                data.mapNotNull { element ->
                    val model = element.jsonObject
                    val id = model["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    if (!AiModelClassifier.isTextModel(descriptor.id, id)) return@mapNotNull null
                    val displayName =
                        model["name"]?.jsonPrimitive?.contentOrNull
                            ?: model["display_name"]?.jsonPrimitive?.contentOrNull
                            ?: id
                    val contextWindow =
                        model["context_length"]?.jsonPrimitive?.longOrNull
                            ?: model["context_window"]?.jsonPrimitive?.longOrNull
                            ?: model["max_context_length"]?.jsonPrimitive?.longOrNull
                    AiModel(
                        id = id,
                        displayName = displayName,
                        providerId = descriptor.id,
                        contextWindow = contextWindow,
                        capabilities = AiModelClassifier.capabilities(descriptor, id, model),
                        metadata =
                            buildMap {
                                model["owned_by"]?.jsonPrimitive?.contentOrNull?.let { put("owner", it) }
                                model["description"]?.jsonPrimitive?.contentOrNull?.let { put("description", it.take(240)) }
                            },
                    )
                }.distinctBy(AiModel::id)
                    .sortedWith(
                        compareBy<AiModel> { model ->
                            descriptor.fallbackModelIds
                                .indexOf(model.id)
                                .takeIf { it >= 0 }
                                ?: Int.MAX_VALUE
                        }.thenByDescending { com.metrolist.music.ai.model.AiCapability.TOOLS in it.capabilities }
                            .thenBy { it.displayName.lowercase() },
                    )
            }
        }

    override fun streamResponse(
        request: AiRequest,
        config: AiProviderConfig,
    ): Flow<AiStreamEvent> =
        flow {
            val body = buildChatBody(request, config)
            val call = client.newCall(buildRequest(config, "chat/completions", body))
            val cancellationHandle = currentCoroutineContext()[Job]?.invokeOnCompletion { call.cancel() }
            try {
                val seenGroundingSources = linkedMapOf<String, AiGroundingSource>()
                var groundingEmitted = false
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        throw parseProviderError(response.code, response.body?.string(), response.header("Retry-After"))
                    }
                    val source = response.body?.source() ?: error("Empty response body")
                    val toolBuilders = linkedMapOf<Int, ToolCallBuilder>()
                    var completed = false
                    while (!source.exhausted()) {
                        currentCoroutineContext().ensureActive()
                        val line = source.readUtf8Line() ?: break
                        if (!line.startsWith("data:")) continue
                        val data = line.removePrefix("data:").trim()
                        if (data.isBlank()) continue
                        if (data == "[DONE]") {
                            emitCompletedToolCalls(toolBuilders)
                            if (seenGroundingSources.isNotEmpty()) {
                                emit(
                                    AiStreamEvent.Grounding(
                                        AiGroundingMetadata(
                                            queries = emptyList(),
                                            sources = seenGroundingSources.values.toList(),
                                        ),
                                    ),
                                )
                                groundingEmitted = true
                            }
                            emit(AiStreamEvent.Completed)
                            completed = true
                            break
                        }

                        val root = aiJson.parseToJsonElement(data).jsonObject
                        root["error"]?.let { throw parseProviderError(400, root.toString()) }
                        val choice = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: continue
                        val delta = choice["delta"]?.jsonObject ?: continue
                        delta["content"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotEmpty)?.let {
                            emit(AiStreamEvent.TextDelta(it))
                        }
                        delta["annotations"]?.jsonArray.orEmpty().forEach { annotation ->
                            annotation.openRouterGroundingSource()?.let { source ->
                                seenGroundingSources[source.url] = source
                            }
                        }
                        delta["tool_calls"]?.jsonArray?.forEachIndexed { fallbackIndex, toolElement ->
                            val tool = toolElement.jsonObject
                            val index = tool["index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: fallbackIndex
                            val function = tool["function"]?.jsonObject
                            val extraContent = tool["extra_content"] as? JsonObject
                            val id = tool["id"]?.jsonPrimitive?.contentOrNull
                            val name = function?.get("name")?.jsonPrimitive?.contentOrNull
                            val argumentsDelta = function?.get("arguments")?.jsonPrimitive?.contentOrNull.orEmpty()
                            val builder = toolBuilders.getOrPut(index) { ToolCallBuilder() }
                            if (!id.isNullOrBlank()) builder.id = id
                            if (!name.isNullOrBlank()) builder.name.append(name)
                            if (extraContent != null) builder.transportMetadata = extraContent
                            if (argumentsDelta.isNotEmpty()) builder.arguments.append(argumentsDelta)
                            if (!builder.started && builder.name.isNotEmpty()) {
                                builder.started = true
                                val eventId = builder.id ?: "tool_${UUID.randomUUID()}".also { builder.id = it }
                                emit(
                                    AiStreamEvent.ToolCallStarted(
                                        eventId,
                                        builder.name.toString(),
                                    ),
                                )
                            }
                            if (argumentsDelta.isNotEmpty()) {
                                emit(
                                    AiStreamEvent.ToolCallArgumentsDelta(
                                        builder.id ?: "tool_$index",
                                        argumentsDelta,
                                    ),
                                )
                            }
                        }
                        (root["usage"] as? JsonObject)?.let { usage ->
                            emit(
                                AiStreamEvent.Usage(
                                    inputTokens = usage["prompt_tokens"]?.jsonPrimitive?.longOrNull,
                                    outputTokens = usage["completion_tokens"]?.jsonPrimitive?.longOrNull,
                                ),
                            )
                        }
                    }
                    if (!completed) {
                        emitCompletedToolCalls(toolBuilders)
                        if (!groundingEmitted && seenGroundingSources.isNotEmpty()) {
                            emit(
                                AiStreamEvent.Grounding(
                                    AiGroundingMetadata(
                                        queries = emptyList(),
                                        sources = seenGroundingSources.values.toList(),
                                    ),
                                ),
                            )
                        }
                        emit(AiStreamEvent.Completed)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                emit(AiStreamEvent.Error(throwable.toAiError()))
            } finally {
                cancellationHandle?.dispose()
            }
        }.flowOn(Dispatchers.IO)

    private suspend fun kotlinx.coroutines.flow.FlowCollector<AiStreamEvent>.emitCompletedToolCalls(
        builders: Map<Int, ToolCallBuilder>,
    ) {
        builders.values.forEach { builder ->
            val name = builder.name.toString()
            if (name.isBlank()) return@forEach
            val id = builder.id ?: "tool_${UUID.randomUUID()}"
            val arguments =
                if (builder.arguments.isBlank()) {
                    JsonObject(emptyMap())
                } else {
                    runCatching { aiJson.parseToJsonElement(builder.arguments.toString()).jsonObject }
                        .getOrElse {
                            emit(
                                AiStreamEvent.Error(
                                    AiError(
                                        AiErrorType.PARSING,
                                        "The provider returned invalid arguments for $name.",
                                        cause = it,
                                    ),
                                ),
                            )
                            return@forEach
                        }
                }
            emit(
                AiStreamEvent.ToolCallCompleted(
                    AiPendingToolCall(id, name, arguments, builder.transportMetadata),
                ),
            )
        }
    }

    internal fun buildChatBody(
        request: AiRequest,
        config: AiProviderConfig,
    ): JsonObject =
        buildJsonObject {
            val openRouterWebSearch = descriptor.id == "openrouter" && request.enableWebSearch
            put("model", config.modelId)
            put("stream", true)
            if (!usesRestrictedReasoningParameters(config)) {
                put("temperature", request.temperature)
            }
            put(
                if (usesRestrictedReasoningParameters(config)) "max_completion_tokens" else "max_tokens",
                request.maxOutputTokens,
            )
            put(
                "messages",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("role", "system")
                            put("content", request.systemPrompt)
                        },
                    )
                    request.messages.forEach { message -> add(message.toOpenAiJson()) }
                },
            )
            if (request.tools.isNotEmpty() || openRouterWebSearch) {
                put(
                    "tools",
                    buildJsonArray {
                        if (openRouterWebSearch) {
                            add(
                                buildJsonObject {
                                    put("type", "openrouter:web_search")
                                    put(
                                        "parameters",
                                        buildJsonObject {
                                            put("engine", "parallel")
                                            put("mode", "turbo")
                                            put("max_results", OPENROUTER_WEB_MAX_RESULTS)
                                            put("max_total_results", OPENROUTER_WEB_MAX_RESULTS)
                                            put("max_uses", 1)
                                        },
                                    )
                                },
                            )
                        }
                        request.tools.forEach { tool ->
                            add(
                                buildJsonObject {
                                    put("type", "function")
                                    put(
                                        "function",
                                        buildJsonObject {
                                            put("name", tool.name)
                                            put("description", tool.description)
                                            put("parameters", tool.inputSchema)
                                        },
                                    )
                                },
                            )
                        }
                    },
                )
                put("tool_choice", "auto")
            }
        }

    private fun usesRestrictedReasoningParameters(config: AiProviderConfig): Boolean {
        if (descriptor.id != "openai") return false
        val model = config.modelId.lowercase()
        return model.startsWith("o1") ||
            model.startsWith("o3") ||
            model.startsWith("o4") ||
            model.startsWith("gpt-5")
    }

    private fun JsonElement.openRouterGroundingSource(): AiGroundingSource? {
        val annotation = this as? JsonObject ?: return null
        if (annotation["type"]?.jsonPrimitive?.contentOrNull != "url_citation") return null
        val citation = annotation["url_citation"] as? JsonObject ?: annotation
        val url = citation["url"]?.jsonPrimitive?.contentOrNull ?: return null
        val title = citation["title"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) ?: url
        return AiGroundingSource(title = title, url = url)
    }

    private fun AiConversationMessage.toOpenAiJson(): JsonObject =
        when (this) {
            is AiConversationMessage.User ->
                buildJsonObject {
                    put("role", "user")
                    put("content", text)
                }

            is AiConversationMessage.Assistant ->
                buildJsonObject {
                    put("role", "assistant")
                    put("content", text)
                    if (toolCalls.isNotEmpty()) {
                        put(
                            "tool_calls",
                            buildJsonArray {
                                toolCalls.forEach { call ->
                                    add(
                                        buildJsonObject {
                                            put("id", call.id)
                                            put("type", "function")
                                            if (call.transportMetadata.isNotEmpty()) {
                                                put("extra_content", call.transportMetadata)
                                            }
                                            put(
                                                "function",
                                                buildJsonObject {
                                                    put("name", call.name)
                                                    put("arguments", call.arguments.toString())
                                                },
                                            )
                                        },
                                    )
                                }
                            },
                        )
                    }
                }

            is AiConversationMessage.ToolResult ->
                buildJsonObject {
                    put("role", "tool")
                    put("tool_call_id", toolCallId)
                    put("content", payload.toString())
                }
        }

    private fun buildRequest(
        config: AiProviderConfig,
        path: String,
        body: JsonElement,
    ): Request =
        requestBuilder(config, path)
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()

    private fun buildGetRequest(
        config: AiProviderConfig,
        path: String,
    ): Request = requestBuilder(config, path).get().build()

    private fun requestBuilder(
        config: AiProviderConfig,
        path: String,
    ): Request.Builder {
        val baseUrl = normalizeBaseUrl(config.baseUrl, descriptor.defaultBaseUrl)
        require(baseUrl.isNotBlank()) { "Base URL is required for ${descriptor.displayName}." }
        return Request
            .Builder()
            .url("$baseUrl/${path.trimStart('/')}")
            .addHeader("Accept", "application/json")
            .addHeader("Content-Type", "application/json")
            .apply {
                if (config.apiKey.isNotBlank()) addHeader("Authorization", "Bearer ${config.apiKey.trim()}")
                (extraHeaders + config.customHeaders).forEach { (name, value) -> addHeader(name, value) }
            }
    }

    private class ToolCallBuilder {
        var id: String? = null
        val name = StringBuilder()
        val arguments = StringBuilder()
        var transportMetadata: JsonObject = JsonObject(emptyMap())
        var started = false
    }

    private companion object {
        const val OPENROUTER_WEB_MAX_RESULTS = 3
    }
}
