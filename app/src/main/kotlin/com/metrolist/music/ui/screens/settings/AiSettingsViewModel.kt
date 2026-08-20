/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.music.ai.model.AiModel
import com.metrolist.music.ai.model.AiProviderConfig
import com.metrolist.music.ai.provider.AiProviderDescriptor
import com.metrolist.music.ai.provider.AiProviderRegistry
import com.metrolist.music.ai.repository.AiModelRepository
import com.metrolist.music.ai.repository.AiModelSource
import com.metrolist.music.ai.security.AiSecretAliases
import com.metrolist.music.ai.security.AiSecretStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class AiSettingsViewModel
@Inject
constructor(
    private val providerRegistry: AiProviderRegistry,
    private val modelRepository: AiModelRepository,
    private val secretStore: AiSecretStore,
) : ViewModel() {
    private val _state =
        MutableStateFlow(
            AiSettingsUiState(
                providers = providerRegistry.descriptors(),
            ),
        )
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            secretStore.migrateLegacyTranslationKeys()
        }
    }

    fun loadAssistantProvider(
        providerId: String,
        baseUrl: String,
        selectedModelId: String,
        forceRefresh: Boolean = false,
    ) {
        val descriptor = providerRegistry.descriptor(providerId) ?: return
        _state.update {
            val sameProvider = it.selectedProviderId == descriptor.id
            it.copy(
                selectedProviderId = descriptor.id,
                models = if (sameProvider) it.models else emptyList(),
                modelSource = if (sameProvider) it.modelSource else null,
                keyMask = if (sameProvider) it.keyMask else null,
                isLoadingModels = true,
                modelWarning = null,
                connectionTest = AiConnectionTestState.IDLE,
            )
        }
        viewModelScope.launch {
            val (keyMask, catalog) =
                withContext(Dispatchers.IO) {
                    val config = config(descriptor, baseUrl, selectedModelId)
                    secretStore.masked(AiSecretAliases.assistant(descriptor.id)) to
                        modelRepository.models(config, forceRefresh)
                }
            _state.update { current ->
                if (current.selectedProviderId != descriptor.id) return@update current
                current.copy(
                    models = catalog.models,
                    keyMask = keyMask,
                    modelSource = catalog.source,
                    isLoadingModels = false,
                    modelWarning = catalog.warning,
                )
            }
        }
    }

    fun saveAssistantKey(
        providerId: String,
        apiKey: String,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            secretStore.put(AiSecretAliases.assistant(providerId), apiKey)
            _state.update {
                it.copy(
                    keyMask = secretStore.masked(AiSecretAliases.assistant(providerId)),
                    connectionTest = AiConnectionTestState.IDLE,
                )
            }
        }
    }

    fun removeAssistantKey(providerId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            secretStore.remove(AiSecretAliases.assistant(providerId))
            _state.update { it.copy(keyMask = null, connectionTest = AiConnectionTestState.IDLE) }
        }
    }

    fun testConnection(
        providerId: String,
        baseUrl: String,
        modelId: String,
    ) {
        val descriptor = providerRegistry.descriptor(providerId) ?: return
        _state.update { it.copy(connectionTest = AiConnectionTestState.TESTING) }
        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    modelRepository.validate(config(descriptor, baseUrl, modelId))
                }
            _state.update { current ->
                result.fold(
                    onSuccess = { current.copy(connectionTest = AiConnectionTestState.CONNECTED) },
                    onFailure = { error ->
                        current.copy(
                            connectionTest = AiConnectionTestState.FAILED,
                            connectionError = error.message,
                        )
                    },
                )
            }
        }
    }

    private fun config(
        descriptor: AiProviderDescriptor,
        baseUrl: String,
        modelId: String,
    ) = AiProviderConfig(
        providerId = descriptor.id,
        apiKey = secretStore.get(AiSecretAliases.assistant(descriptor.id)).orEmpty(),
        baseUrl = baseUrl.takeIf(String::isNotBlank),
        modelId = modelId.ifBlank { descriptor.fallbackModelIds.firstOrNull().orEmpty() },
    )
}

data class AiSettingsUiState(
    val providers: List<AiProviderDescriptor> = emptyList(),
    val selectedProviderId: String = "openrouter",
    val models: List<AiModel> = emptyList(),
    val modelSource: AiModelSource? = null,
    val isLoadingModels: Boolean = false,
    val modelWarning: String? = null,
    val keyMask: String? = null,
    val connectionTest: AiConnectionTestState = AiConnectionTestState.IDLE,
    val connectionError: String? = null,
)

enum class AiConnectionTestState {
    IDLE,
    TESTING,
    CONNECTED,
    FAILED,
}
