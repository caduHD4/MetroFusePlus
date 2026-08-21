/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ai.core

import com.metrolist.music.ai.model.AiConversationMessage
import com.metrolist.music.ai.model.AiProviderConfig
import com.metrolist.music.ai.model.AiRequest
import com.metrolist.music.ai.model.AiToolContext
import com.metrolist.music.ai.provider.AiProviderRegistry
import com.metrolist.music.ai.tools.AiToolExecution
import com.metrolist.music.ai.tools.AiToolExecutor
import com.metrolist.music.ai.tools.AiToolPresentation
import com.metrolist.music.ai.tools.AiToolRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AiAgentRunner
@Inject
constructor(
    private val providerRegistry: AiProviderRegistry,
    private val toolRegistry: AiToolRegistry,
    private val toolExecutor: AiToolExecutor,
) {
    suspend fun runTurn(
        config: AiProviderConfig,
        systemPrompt: String,
        conversation: MutableList<AiConversationMessage>,
        toolContext: AiToolContext,
        toolsEnabled: Boolean,
        webGroundingEnabled: Boolean = false,
        maxToolCalls: Int = DEFAULT_MAX_TOOL_CALLS,
        onEvent: suspend (AiAgentEvent) -> Unit,
    ) {
        val provider = providerRegistry.requireProvider(config.providerId)
        var toolCallCount = 0

        while (true) {
            onEvent(AiAgentEvent.State(AiAssistantPhase.THINKING))
            val responseText = StringBuilder()
            val requestedTools = mutableListOf<com.metrolist.music.ai.model.AiPendingToolCall>()
            var providerError: AiError? = null
            var providerAttempt = 0
            var sawProviderOutput: Boolean
            var retryDelaySeconds: Long?

            do {
                providerError = null
                sawProviderOutput = false
                provider
                    .streamResponse(
                        request =
                            AiRequest(
                                systemPrompt = systemPrompt,
                                messages = conversation.toList(),
                                tools = if (toolsEnabled) toolRegistry.definitions(toolContext) else emptyList(),
                                enableWebSearch = webGroundingEnabled,
                            ),
                        config = config,
                    ).collect { event ->
                        when (event) {
                            is AiStreamEvent.TextDelta -> {
                                sawProviderOutput = true
                                responseText.append(event.text)
                                onEvent(AiAgentEvent.TextDelta(event.text))
                            }
                            is AiStreamEvent.ToolCallStarted -> {
                                sawProviderOutput = true
                                onEvent(AiAgentEvent.ToolStarted(event.id, event.name))
                            }
                            is AiStreamEvent.ToolCallCompleted -> {
                                sawProviderOutput = true
                                requestedTools += event.call
                            }
                            is AiStreamEvent.Status -> onEvent(AiAgentEvent.Status(event.message))
                            is AiStreamEvent.Error -> providerError = event.error
                            is AiStreamEvent.Completed,
                            is AiStreamEvent.ToolCallArgumentsDelta,
                            is AiStreamEvent.Usage,
                            -> Unit
                            is AiStreamEvent.Grounding -> onEvent(AiAgentEvent.Grounding(event.metadata))
                        }
                    }

                retryDelaySeconds = providerError?.retryDelaySeconds(providerAttempt, sawProviderOutput)
                retryDelaySeconds?.let { delaySeconds ->
                    providerAttempt++
                    onEvent(AiAgentEvent.RetryScheduled(providerAttempt, delaySeconds))
                    delay(delaySeconds * 1000L)
                }
            } while (retryDelaySeconds != null)

            providerError?.let {
                onEvent(AiAgentEvent.Error(it))
                return
            }

            conversation +=
                AiConversationMessage.Assistant(
                    text = responseText.toString(),
                    toolCalls = requestedTools,
                )
            if (requestedTools.isEmpty()) {
                onEvent(AiAgentEvent.Completed)
                return
            }
            if (!toolsEnabled) {
                onEvent(
                    AiAgentEvent.Error(
                        AiError(AiErrorType.TOOL_UNAVAILABLE, "The selected model cannot run app actions."),
                    ),
                )
                return
            }
            if (toolCallCount + requestedTools.size > maxToolCalls.coerceIn(1, DEFAULT_MAX_TOOL_CALLS)) {
                onEvent(
                    AiAgentEvent.Error(
                        AiError(AiErrorType.TOOL_EXECUTION_FAILED, "Tool call limit reached for this turn."),
                    ),
                )
                return
            }

            toolCallCount += requestedTools.size
            onEvent(AiAgentEvent.State(AiAssistantPhase.SEARCHING))
            executeTools(requestedTools, toolContext).forEach { execution ->
                conversation +=
                    AiConversationMessage.ToolResult(
                        toolCallId = execution.call.id,
                        toolName = execution.call.name,
                        payload = execution.result.payloadForModel(),
                    )
                onEvent(AiAgentEvent.ToolFinished(execution))
            }
        }
    }

    private suspend fun executeTools(
        calls: List<com.metrolist.music.ai.model.AiPendingToolCall>,
        context: AiToolContext,
    ): List<AiToolExecution> =
        coroutineScope {
            val semaphore = Semaphore(MAX_PARALLEL_TOOLS)
            calls
                .map { call ->
                    async {
                        semaphore.withPermit { toolExecutor.execute(call, context) }
                    }
                }
                .awaitAll()
        }

    companion object {
        const val DEFAULT_MAX_TOOL_CALLS = 10
        private const val MAX_PARALLEL_TOOLS = 3
    }
}

internal fun AiError.retryDelaySeconds(
    attempt: Int,
    sawOutput: Boolean,
): Long? {
    if (sawOutput || attempt >= MAX_PROVIDER_RETRIES) return null
    return when (type) {
        AiErrorType.NETWORK, AiErrorType.PROVIDER_SERVER -> (1L shl attempt).coerceAtMost(4L)
        AiErrorType.RATE_LIMITED -> retryAfterSeconds?.takeIf { it in 1..MAX_RETRY_AFTER_SECONDS }
        else -> null
    }
}

private const val MAX_PROVIDER_RETRIES = 2
private const val MAX_RETRY_AFTER_SECONDS = 30L

sealed interface AiAgentEvent {
    data class TextDelta(
        val text: String,
    ) : AiAgentEvent

    data class Status(
        val text: String,
    ) : AiAgentEvent

    data class State(
        val phase: AiAssistantPhase,
    ) : AiAgentEvent

    data class ToolStarted(
        val id: String,
        val name: String,
    ) : AiAgentEvent

    data class ToolFinished(
        val execution: AiToolExecution,
    ) : AiAgentEvent

    data class RetryScheduled(
        val attempt: Int,
        val delaySeconds: Long,
    ) : AiAgentEvent

    data class Grounding(
        val metadata: AiGroundingMetadata,
    ) : AiAgentEvent

    data class Error(
        val error: AiError,
    ) : AiAgentEvent

    data object Completed : AiAgentEvent
}
