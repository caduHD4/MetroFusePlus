/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ai.provider

import com.metrolist.music.ai.core.AiError
import com.metrolist.music.ai.core.AiErrorType
import com.metrolist.music.ai.core.AiProviderException
import com.metrolist.music.ai.model.AiCapability
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

internal val aiJson =
    Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

internal fun createAiHttpClient(): OkHttpClient =
    OkHttpClient
        .Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

internal fun normalizeBaseUrl(
    configured: String?,
    fallback: String?,
): String {
    val raw = configured?.trim().orEmpty().ifBlank { fallback.orEmpty() }
    return raw
        .trimEnd('/')
        .removeSuffix("/chat/completions")
        .removeSuffix("/messages")
}

internal fun parseProviderError(
    statusCode: Int,
    responseBody: String?,
    retryAfter: String? = null,
): AiProviderException {
    val message =
        runCatching {
            val root = aiJson.parseToJsonElement(responseBody.orEmpty()).jsonObject
            val error = root["error"]
            when (error) {
                is JsonObject -> error["message"]?.jsonPrimitive?.contentOrNull
                else -> error?.jsonPrimitive?.contentOrNull
            }
        }.getOrNull().orEmpty().ifBlank { "HTTP $statusCode" }
    val type =
        when (statusCode) {
            401, 403 -> AiErrorType.INVALID_API_KEY
            404 -> AiErrorType.MODEL_UNAVAILABLE
            429 -> AiErrorType.RATE_LIMITED
            in 500..599 -> AiErrorType.PROVIDER_SERVER
            else -> AiErrorType.UNKNOWN
        }
    return AiProviderException(
        AiError(
            type = type,
            message = message.take(600),
            retryAfterSeconds = retryAfter?.toLongOrNull(),
        ),
    )
}

internal fun Throwable.toAiError(): AiError =
    when (this) {
        is AiProviderException -> error
        is SocketTimeoutException -> AiError(AiErrorType.NETWORK, "The AI provider timed out.", cause = this)
        is IOException -> AiError(AiErrorType.NETWORK, message ?: "Network request failed.", cause = this)
        is kotlinx.coroutines.CancellationException -> AiError(AiErrorType.CANCELLED, "Request cancelled.", cause = this)
        else -> AiError(AiErrorType.UNKNOWN, message ?: "Unknown AI provider error.", cause = this)
    }

internal object AiModelClassifier {
    private val excludedTokens =
        listOf(
            "embedding",
            "whisper",
            "transcri",
            "moderation",
            "tts",
            "text-to-speech",
            "speech",
            "audio",
            "realtime",
            "dall-e",
            "image-generation",
            "gpt-image",
            "imagen",
            "veo",
            "safety",
            "guard",
        )

    fun isTextModel(
        providerId: String,
        id: String,
    ): Boolean {
        val normalized = id.lowercase()
        if (excludedTokens.any(normalized::contains)) return false
        return when (providerId) {
            "openai" ->
                normalized.startsWith("gpt-") ||
                    normalized.startsWith("chatgpt-") ||
                    Regex("^o[1-9]([-.].*)?$").matches(normalized)
            "anthropic" -> normalized.startsWith("claude-")
            "xai" -> normalized.startsWith("grok-")
            else -> true
        }
    }

    fun capabilities(
        descriptor: AiProviderDescriptor,
        modelId: String,
        metadata: JsonObject? = null,
    ): Set<AiCapability> {
        val capabilities = mutableSetOf(AiCapability.TEXT)
        if (descriptor.supportsStreaming) capabilities += AiCapability.STREAMING

        val supportedParameters =
            metadata
                ?.get("supported_parameters")
                ?.let { element -> runCatching { element.toString() }.getOrNull() }
                .orEmpty()
                .lowercase()
        val declaredCapabilities =
            metadata
                ?.get("capabilities")
                ?.let { element -> runCatching { element.toString() }.getOrNull() }
                .orEmpty()
                .lowercase()
        val functionCalling =
            (metadata?.get("capabilities") as? JsonObject)
                ?.get("function_calling")
                ?.jsonPrimitive
                ?.booleanOrNull

        val toolsDeclared =
            supportedParameters.contains("tools") ||
                declaredCapabilities.contains("function_call") ||
                declaredCapabilities.contains("tool")
        if (
            toolsDeclared ||
            (descriptor.id == "openrouter" && modelId == "openrouter/free") ||
            (descriptor.supportsTools && descriptor.id != "openrouter" && functionCalling != false)
        ) {
            capabilities += AiCapability.TOOLS
        }
        if (
            supportedParameters.contains("response_format") ||
            declaredCapabilities.contains("json") ||
            modelId.contains("gpt", ignoreCase = true)
        ) {
            capabilities += AiCapability.STRUCTURED_OUTPUT
        }
        if (
            modelId.contains("vision", ignoreCase = true) ||
            modelId.contains("vl", ignoreCase = true) ||
            modelId.contains("gemini", ignoreCase = true)
        ) {
            capabilities += AiCapability.VISION
        }
        if (
            modelId.startsWith("o") ||
            modelId.contains("reason", ignoreCase = true) ||
            modelId.contains("thinking", ignoreCase = true)
        ) {
            capabilities += AiCapability.REASONING
        }
        return capabilities
    }
}
