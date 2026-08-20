/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ai.core

import com.metrolist.music.ai.model.AiPendingToolCall

enum class AiErrorType {
    INVALID_API_KEY,
    RATE_LIMITED,
    MODEL_UNAVAILABLE,
    NETWORK,
    PROVIDER_SERVER,
    TOOL_UNAVAILABLE,
    PERMISSION_DENIED,
    CONFIRMATION_REQUIRED,
    TOOL_EXECUTION_FAILED,
    PARSING,
    CONTEXT_LIMIT,
    CANCELLED,
    UNKNOWN,
}

data class AiError(
    val type: AiErrorType,
    val message: String,
    val retryAfterSeconds: Long? = null,
    val cause: Throwable? = null,
)

class AiProviderException(
    val error: AiError,
) : Exception(error.message, error.cause)

sealed interface AiStreamEvent {
    data class TextDelta(
        val text: String,
    ) : AiStreamEvent

    data class Status(
        val message: String,
    ) : AiStreamEvent

    data class ToolCallStarted(
        val id: String,
        val name: String,
    ) : AiStreamEvent

    data class ToolCallArgumentsDelta(
        val id: String,
        val delta: String,
    ) : AiStreamEvent

    data class ToolCallCompleted(
        val call: AiPendingToolCall,
    ) : AiStreamEvent

    data class Usage(
        val inputTokens: Long?,
        val outputTokens: Long?,
    ) : AiStreamEvent

    data object Completed : AiStreamEvent

    data class Error(
        val error: AiError,
    ) : AiStreamEvent
}

enum class AiAssistantPhase {
    IDLE,
    THINKING,
    SEARCHING,
    WAITING_CONFIRMATION,
    EXECUTING,
    COMPLETED,
    ERROR,
    CANCELLED,
}

data class AiAssistantState(
    val phase: AiAssistantPhase = AiAssistantPhase.IDLE,
    val status: String? = null,
    val canCancel: Boolean = false,
    val error: AiError? = null,
)
