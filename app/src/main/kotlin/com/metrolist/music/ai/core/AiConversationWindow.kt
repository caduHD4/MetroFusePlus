/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ai.core

import com.metrolist.music.ai.model.AiConversationMessage

object AiConversationWindow {
    fun compact(
        messages: List<AiConversationMessage>,
        maxUserTurns: Int = DEFAULT_MAX_USER_TURNS,
    ): List<AiConversationMessage> {
        require(maxUserTurns > 0)
        val userIndexes = messages.indices.filter { messages[it] is AiConversationMessage.User }
        if (userIndexes.size <= maxUserTurns) return messages.toList()
        return messages.drop(userIndexes[userIndexes.size - maxUserTurns])
    }

    fun compactInPlace(
        messages: MutableList<AiConversationMessage>,
        maxUserTurns: Int = DEFAULT_MAX_USER_TURNS,
    ) {
        val compacted = compact(messages, maxUserTurns)
        if (compacted.size == messages.size) return
        messages.clear()
        messages.addAll(compacted)
    }

    const val DEFAULT_MAX_USER_TURNS = 12
}
