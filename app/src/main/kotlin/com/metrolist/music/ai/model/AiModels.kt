/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ai.model

import com.metrolist.music.ai.playlist.AiSessionArtifacts
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
enum class AiCapability {
    TEXT,
    STREAMING,
    TOOLS,
    STRUCTURED_OUTPUT,
    VISION,
    AUDIO_INPUT,
    REASONING,
}

@Serializable
data class AiModel(
    val id: String,
    val displayName: String,
    val providerId: String,
    val contextWindow: Long? = null,
    val capabilities: Set<AiCapability> = setOf(AiCapability.TEXT, AiCapability.STREAMING),
    val metadata: Map<String, String> = emptyMap(),
)

data class AiProviderConfig(
    val providerId: String,
    val apiKey: String,
    val baseUrl: String? = null,
    val modelId: String,
    val customHeaders: Map<String, String> = emptyMap(),
)

data class AiToolDefinition(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
)

data class AiPendingToolCall(
    val id: String,
    val name: String,
    val arguments: JsonObject,
    val transportMetadata: JsonObject = JsonObject(emptyMap()),
)

sealed interface AiConversationMessage {
    data class User(
        val text: String,
    ) : AiConversationMessage

    data class Assistant(
        val text: String,
        val toolCalls: List<AiPendingToolCall> = emptyList(),
    ) : AiConversationMessage

    data class ToolResult(
        val toolCallId: String,
        val toolName: String,
        val payload: JsonElement,
    ) : AiConversationMessage
}

data class AiRequest(
    val systemPrompt: String,
    val messages: List<AiConversationMessage>,
    val tools: List<AiToolDefinition> = emptyList(),
    val maxOutputTokens: Int = 2048,
    val temperature: Double = 0.45,
    val enableWebSearch: Boolean = false,
)

data class CurrentMusicContext(
    val id: String,
    val title: String,
    val artists: List<String>,
    val album: String?,
    val durationSeconds: Int?,
    val positionSeconds: Int?,
    val isPlaying: Boolean,
)

data class AiQueueItemContext(
    val id: String,
    val title: String,
    val artists: List<String>,
    val album: String?,
    val durationSeconds: Int?,
    val position: Int,
    val isCurrent: Boolean,
)

data class CurrentLyricsContext(
    val songId: String,
    val provider: String,
    val text: String,
    val translatedText: String? = null,
    val translationLanguage: String? = null,
    val originalTruncated: Boolean = false,
    val translationTruncated: Boolean = false,
)

enum class AiUiContextType {
    NONE,
    PLAYER,
    PLAYLIST,
    ALBUM,
    ARTIST,
    SEARCH,
    LIBRARY,
}

data class AiUiContext(
    val type: AiUiContextType,
    val resourceId: String? = null,
    val query: String? = null,
)

data class AiPermissions(
    val currentSong: Boolean = true,
    val queue: Boolean = false,
    val library: Boolean = false,
    val lyrics: Boolean = false,
    val likedSongs: Boolean = false,
    val playlists: Boolean = false,
    val history: Boolean = false,
)

data class AiToolContext(
    val permissions: AiPermissions,
    val currentMusic: CurrentMusicContext?,
    val artifacts: AiSessionArtifacts,
    val queue: List<AiQueueItemContext> = emptyList(),
    val queueTotal: Int = queue.size,
    val lyrics: CurrentLyricsContext? = null,
    val uiContext: AiUiContext? = null,
)
