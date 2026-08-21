/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrolist.music.R
import com.metrolist.music.ai.model.AiCapability
import com.metrolist.music.constants.AiAssistantBaseUrlKey
import com.metrolist.music.constants.AiAssistantCurrentSongPermissionKey
import com.metrolist.music.constants.AiAssistantHistoryPermissionKey
import com.metrolist.music.constants.AiAssistantLikedSongsPermissionKey
import com.metrolist.music.constants.AiAssistantLibraryPermissionKey
import com.metrolist.music.constants.AiAssistantLyricsPermissionKey
import com.metrolist.music.constants.AiAssistantMaxToolCallsKey
import com.metrolist.music.constants.AiAssistantModelKey
import com.metrolist.music.constants.AiAssistantPlaylistsPermissionKey
import com.metrolist.music.constants.AiAssistantProviderKey
import com.metrolist.music.constants.AiAssistantQueuePermissionKey
import com.metrolist.music.constants.AiAssistantSystemPromptKey
import com.metrolist.music.constants.AiAssistantWebSearchKey
import com.metrolist.music.ui.component.EnumDialog
import com.metrolist.music.ui.component.Material3SettingsGroup
import com.metrolist.music.ui.component.Material3SettingsItem
import com.metrolist.music.ui.component.TextFieldDialog
import com.metrolist.music.utils.rememberPreference
import kotlin.math.roundToInt

@Composable
fun AiAssistantSettingsSection(
    viewModel: AiSettingsViewModel = hiltViewModel(),
) {
    var providerId by rememberPreference(AiAssistantProviderKey, "openrouter")
    var baseUrl by rememberPreference(AiAssistantBaseUrlKey, "")
    var modelId by rememberPreference(AiAssistantModelKey, "")
    var systemPrompt by rememberPreference(AiAssistantSystemPromptKey, "")
    var webSearchEnabled by rememberPreference(AiAssistantWebSearchKey, false)
    var allowCurrentSong by rememberPreference(AiAssistantCurrentSongPermissionKey, true)
    var allowQueue by rememberPreference(AiAssistantQueuePermissionKey, false)
    var allowLibrary by rememberPreference(AiAssistantLibraryPermissionKey, false)
    var allowLyrics by rememberPreference(AiAssistantLyricsPermissionKey, false)
    var allowLikedSongs by rememberPreference(AiAssistantLikedSongsPermissionKey, false)
    var allowPlaylists by rememberPreference(AiAssistantPlaylistsPermissionKey, false)
    var allowHistory by rememberPreference(AiAssistantHistoryPermissionKey, false)
    var maxToolCalls by rememberPreference(AiAssistantMaxToolCallsKey, 10)
    val state by viewModel.state.collectAsStateWithLifecycle()
    val descriptor = state.providers.firstOrNull { it.id == providerId } ?: state.providers.firstOrNull()

    var showProviderPicker by rememberSaveable { mutableStateOf(false) }
    var showModelPicker by rememberSaveable { mutableStateOf(false) }
    var showApiKeyDialog by rememberSaveable { mutableStateOf(false) }
    var showBaseUrlDialog by rememberSaveable { mutableStateOf(false) }
    var showManualModelDialog by rememberSaveable { mutableStateOf(false) }
    var showSystemPromptDialog by rememberSaveable { mutableStateOf(false) }
    var showMaxToolCallsDialog by rememberSaveable { mutableStateOf(false) }

    if (showMaxToolCallsDialog) {
        AlertDialog(
            onDismissRequest = { showMaxToolCallsDialog = false },
            title = { Text(stringResource(R.string.ai_max_tool_calls)) },
            text = {
                Column {
                    Text(stringResource(R.string.ai_max_tool_calls_value, maxToolCalls))
                    Slider(
                        value = maxToolCalls.toFloat(),
                        onValueChange = { maxToolCalls = it.roundToInt().coerceIn(1, 10) },
                        valueRange = 1f..10f,
                        steps = 8,
                    )
                    Text(
                        stringResource(R.string.ai_max_tool_calls_desc),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showMaxToolCallsDialog = false }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
        )
    }

    LaunchedEffect(providerId, baseUrl, state.keyMask) {
        viewModel.loadAssistantProvider(providerId, baseUrl, modelId)
    }

    if (showProviderPicker) {
        EnumDialog(
            onDismiss = { showProviderPicker = false },
            onSelect = { selected ->
                providerId = selected
                baseUrl = ""
                modelId = ""
                showProviderPicker = false
            },
            title = stringResource(R.string.ai_provider),
            current = providerId,
            values = state.providers.map { it.id },
            valueText = { id -> state.providers.firstOrNull { it.id == id }?.displayName ?: id },
        )
    }

    if (showApiKeyDialog) {
        SecureApiKeyDialog(
            onDismiss = { showApiKeyDialog = false },
            onSave = { key ->
                viewModel.saveAssistantKey(providerId, key)
                showApiKeyDialog = false
            },
        )
    }

    if (showBaseUrlDialog) {
        TextFieldDialog(
            title = { Text(stringResource(R.string.ai_base_url)) },
            icon = { Icon(painterResource(R.drawable.link), null) },
            initialTextFieldValue = TextFieldValue(baseUrl),
            onDone = {
                baseUrl = it.trim()
                showBaseUrlDialog = false
            },
            onDismiss = { showBaseUrlDialog = false },
        )
    }

    if (showManualModelDialog) {
        TextFieldDialog(
            title = { Text(stringResource(R.string.ai_custom_model_id)) },
            icon = { Icon(painterResource(R.drawable.discover_tune), null) },
            initialTextFieldValue = TextFieldValue(modelId),
            onDone = {
                modelId = it.trim()
                showManualModelDialog = false
            },
            onDismiss = { showManualModelDialog = false },
        )
    }

    if (showSystemPromptDialog) {
        TextFieldDialog(
            title = { Text(stringResource(R.string.ai_system_prompt)) },
            icon = { Icon(painterResource(R.drawable.edit), null) },
            initialTextFieldValue = TextFieldValue(systemPrompt),
            singleLine = false,
            maxLines = 12,
            isInputValid = { true },
            onDone = {
                systemPrompt =
                    it.trim().take(com.metrolist.music.ai.core.AiDataSanitizer.MAX_CUSTOM_INSTRUCTIONS_CHARS)
                showSystemPromptDialog = false
            },
            onDismiss = { showSystemPromptDialog = false },
        )
    }

    if (showModelPicker) {
        SearchableModelDialog(
            models = state.models,
            currentModelId = modelId,
            loading = state.isLoadingModels,
            onSelect = {
                modelId = it
                showModelPicker = false
            },
            onManual = {
                showModelPicker = false
                showManualModelDialog = true
            },
            onDismiss = { showModelPicker = false },
        )
    }

    Material3SettingsGroup(
        title = stringResource(R.string.ai_assistant_section),
        items =
            buildList {
                add(
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.ai_sparkle),
                        title = { Text(stringResource(R.string.ai_provider)) },
                        description = { Text(descriptor?.displayName ?: providerId) },
                        onClick = { showProviderPicker = true },
                    ),
                )
                add(
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.key),
                        title = { Text(stringResource(R.string.ai_api_key)) },
                        description = {
                            Column {
                                Text(state.keyMask ?: stringResource(R.string.not_set))
                                Text(
                                    stringResource(R.string.ai_api_key_secure_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        },
                        onClick = { showApiKeyDialog = true },
                        trailingContent =
                            if (state.keyMask != null) {
                                {
                                    IconButton(onClick = { viewModel.removeAssistantKey(providerId) }) {
                                        Icon(
                                            painterResource(R.drawable.close),
                                            contentDescription = stringResource(R.string.ai_remove_api_key),
                                        )
                                    }
                                }
                            } else {
                                null
                            },
                    ),
                )
                if (providerId == "custom") {
                    add(
                        Material3SettingsItem(
                            icon = painterResource(R.drawable.link),
                            title = { Text(stringResource(R.string.ai_base_url)) },
                            description = { Text(baseUrl.ifBlank { stringResource(R.string.not_set) }) },
                            onClick = { showBaseUrlDialog = true },
                        ),
                    )
                }
                add(
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.discover_tune),
                        title = { Text(stringResource(R.string.ai_model)) },
                        description = {
                            Column {
                                Text(modelId.ifBlank { state.models.firstOrNull()?.id ?: stringResource(R.string.not_set) })
                                state.models.firstOrNull { it.id == modelId }?.let { model ->
                                    Text(
                                        if (AiCapability.TOOLS in model.capabilities) {
                                            stringResource(R.string.ai_model_tools_streaming)
                                        } else {
                                            stringResource(R.string.ai_model_conversation_only)
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        },
                        onClick = { showModelPicker = true },
                        trailingContent =
                            if (state.isLoadingModels) {
                                { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }
                            } else {
                                null
                            },
                    ),
                )
                add(
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.language),
                        title = { Text(stringResource(R.string.ai_web_search)) },
                        description = { Text(stringResource(R.string.ai_web_search_desc)) },
                        trailingContent = {
                            Switch(
                                checked = webSearchEnabled,
                                onCheckedChange = { webSearchEnabled = it },
                            )
                        },
                        onClick = { webSearchEnabled = !webSearchEnabled },
                    ),
                )
                add(
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.refresh),
                        title = { Text(stringResource(R.string.ai_models_refresh)) },
                        description =
                            if (state.modelWarning != null) {
                                { Text(state.modelWarning.orEmpty(), maxLines = 2) }
                            } else {
                                null
                            },
                        onClick = {
                            viewModel.loadAssistantProvider(providerId, baseUrl, modelId, forceRefresh = true)
                        },
                    ),
                )
                add(
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.check),
                        title = { Text(stringResource(R.string.ai_test_connection)) },
                        description = {
                            Text(
                                when (state.connectionTest) {
                                    AiConnectionTestState.IDLE -> descriptor?.displayName.orEmpty()
                                    AiConnectionTestState.TESTING -> stringResource(R.string.ai_connection_testing)
                                    AiConnectionTestState.CONNECTED -> stringResource(R.string.ai_connection_connected)
                                    AiConnectionTestState.FAILED ->
                                        state.connectionError ?: stringResource(R.string.ai_connection_failed)
                                },
                                maxLines = 2,
                            )
                        },
                        onClick = {
                            val effectiveModel = modelId.ifBlank { state.models.firstOrNull()?.id.orEmpty() }
                            viewModel.testConnection(providerId, baseUrl, effectiveModel)
                        },
                    ),
                )
                add(
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.edit),
                        title = { Text(stringResource(R.string.ai_system_prompt)) },
                        description = {
                            Text(
                                if (systemPrompt.isBlank()) {
                                    stringResource(R.string.ai_system_prompt_default_rules)
                                } else {
                                    systemPrompt.take(80)
                                },
                            )
                        },
                        onClick = { showSystemPromptDialog = true },
                    ),
                )
            },
    )

    Material3SettingsGroup(
        title = stringResource(R.string.ai_context_permissions),
        items =
            listOf(
                Material3SettingsItem(
                    icon = painterResource(R.drawable.music_note),
                    title = { Text(stringResource(R.string.ai_context_current_song)) },
                    description = { Text(stringResource(R.string.ai_context_current_song_desc)) },
                    trailingContent = {
                        Switch(checked = allowCurrentSong, onCheckedChange = { allowCurrentSong = it })
                    },
                    onClick = { allowCurrentSong = !allowCurrentSong },
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.queue_music),
                    title = { Text(stringResource(R.string.ai_context_queue)) },
                    description = { Text(stringResource(R.string.ai_context_queue_desc)) },
                    trailingContent = {
                        Switch(checked = allowQueue, onCheckedChange = { allowQueue = it })
                    },
                    onClick = { allowQueue = !allowQueue },
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.library_music),
                    title = { Text(stringResource(R.string.ai_context_library)) },
                    description = { Text(stringResource(R.string.ai_context_library_desc)) },
                    trailingContent = {
                        Switch(checked = allowLibrary, onCheckedChange = { allowLibrary = it })
                    },
                    onClick = { allowLibrary = !allowLibrary },
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.lyrics),
                    title = { Text(stringResource(R.string.ai_context_lyrics)) },
                    description = { Text(stringResource(R.string.ai_context_lyrics_desc)) },
                    trailingContent = {
                        Switch(checked = allowLyrics, onCheckedChange = { allowLyrics = it })
                    },
                    onClick = { allowLyrics = !allowLyrics },
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.favorite),
                    title = { Text(stringResource(R.string.ai_context_liked_songs)) },
                    description = { Text(stringResource(R.string.ai_context_liked_songs_desc)) },
                    trailingContent = {
                        Switch(checked = allowLikedSongs, onCheckedChange = { allowLikedSongs = it })
                    },
                    onClick = { allowLikedSongs = !allowLikedSongs },
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.library_music),
                    title = { Text(stringResource(R.string.ai_context_playlists)) },
                    description = { Text(stringResource(R.string.ai_context_playlists_desc)) },
                    trailingContent = {
                        Switch(checked = allowPlaylists, onCheckedChange = { allowPlaylists = it })
                    },
                    onClick = { allowPlaylists = !allowPlaylists },
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.history),
                    title = { Text(stringResource(R.string.ai_context_history)) },
                    description = { Text(stringResource(R.string.ai_context_history_desc)) },
                    trailingContent = {
                        Switch(checked = allowHistory, onCheckedChange = { allowHistory = it })
                    },
                    onClick = { allowHistory = !allowHistory },
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.lock),
                    title = { Text(stringResource(R.string.ai_context_privacy)) },
                    description = { Text(stringResource(R.string.ai_context_privacy_desc)) },
                ),
            ),
    )

    Material3SettingsGroup(
        title = stringResource(R.string.advanced),
        items =
            listOf(
                Material3SettingsItem(
                    icon = painterResource(R.drawable.tune),
                    title = { Text(stringResource(R.string.ai_max_tool_calls)) },
                    description = { Text(stringResource(R.string.ai_max_tool_calls_value, maxToolCalls)) },
                    onClick = { showMaxToolCallsDialog = true },
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.info),
                    title = { Text(stringResource(R.string.ai_model_source)) },
                    description = {
                        Text(
                            state.modelSource?.name?.lowercase()?.replace('_', ' ')
                                ?: stringResource(R.string.not_set),
                        )
                    },
                ),
            ),
    )
}

@Composable
private fun SecureApiKeyDialog(
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var value by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ai_api_key)) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                supportingText = { Text(stringResource(R.string.ai_api_key_secure_desc)) },
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(value) }, enabled = value.isNotBlank()) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

@Composable
private fun SearchableModelDialog(
    models: List<com.metrolist.music.ai.model.AiModel>,
    currentModelId: String,
    loading: Boolean,
    onSelect: (String) -> Unit,
    onManual: () -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val filtered =
        remember(models, query) {
            models.filter {
                it.id.contains(query, ignoreCase = true) || it.displayName.contains(query, ignoreCase = true)
            }
        }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ai_model)) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.ai_model_search_hint)) },
                    singleLine = true,
                )
                LazyColumn(Modifier.heightIn(max = 420.dp).padding(top = 8.dp)) {
                    if (loading && models.isEmpty()) {
                        item { Text(stringResource(R.string.ai_models_loading), Modifier.padding(16.dp)) }
                    } else if (filtered.isEmpty()) {
                        item { Text(stringResource(R.string.ai_model_no_results), Modifier.padding(16.dp)) }
                    }
                    items(filtered, key = { it.id }) { model ->
                        TextButton(
                            onClick = { onSelect(model.id) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(
                                    model.displayName,
                                    color =
                                        if (model.id == currentModelId) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                )
                                Text(
                                    model.id,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    if (AiCapability.TOOLS in model.capabilities) {
                                        stringResource(R.string.ai_model_tools_streaming)
                                    } else {
                                        stringResource(R.string.ai_model_conversation_only)
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onManual) { Text(stringResource(R.string.ai_custom_model_id)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}
