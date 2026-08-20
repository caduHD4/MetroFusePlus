/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ai.core

import com.metrolist.music.ai.model.AiPermissions
import com.metrolist.music.ai.model.AiQueueItemContext
import com.metrolist.music.ai.model.AiToolContext
import com.metrolist.music.ai.model.AiUiContext
import com.metrolist.music.ai.model.AiUiContextType
import com.metrolist.music.ai.model.CurrentLyricsContext
import com.metrolist.music.ai.model.CurrentMusicContext
import com.metrolist.music.ai.playlist.AiSessionArtifacts
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AiContextBuilder
@Inject
constructor(
    private val sanitizer: AiDataSanitizer,
) {
    fun build(
        permissions: AiPermissions,
        currentMusic: CurrentMusicContext?,
        queue: List<AiQueueItemContext>,
        queueTotal: Int,
        lyrics: CurrentLyricsContext?,
        uiContext: AiUiContext? = null,
        artifacts: AiSessionArtifacts,
    ): AiToolContext {
        val safeUiContext =
            sanitizer.uiContext(uiContext)?.let { safeContext ->
                if (safeContext.type == AiUiContextType.PLAYLIST && !permissions.playlists) {
                    safeContext.copy(resourceId = null)
                } else {
                    safeContext
                }
            }
        if (permissions.playlists && safeUiContext?.type == AiUiContextType.PLAYLIST) {
            safeUiContext.resourceId?.let { artifacts.rememberPlaylistIds(listOf(it)) }
        }
        return AiToolContext(
            permissions = permissions,
            currentMusic = sanitizer.currentMusic(currentMusic).takeIf { permissions.currentSong },
            artifacts = artifacts,
            queue = sanitizer.queue(queue).takeIf { permissions.queue }.orEmpty(),
            queueTotal = queueTotal.coerceAtLeast(0).takeIf { permissions.queue } ?: 0,
            lyrics = sanitizer.lyrics(lyrics).takeIf { permissions.currentSong && permissions.lyrics },
            uiContext = safeUiContext,
        )
    }
}
