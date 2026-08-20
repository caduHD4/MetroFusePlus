/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ai.security

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun rememberAiSecret(
    alias: String,
    legacyKey: Preferences.Key<String>? = null,
): MutableState<String> {
    val context = LocalContext.current.applicationContext
    val store = remember(context) { AiSecretStore(context) }
    val scope = rememberCoroutineScope()
    val backingState =
        remember(alias) {
            mutableStateOf("")
        }

    LaunchedEffect(alias, legacyKey) {
        val resolved =
            withContext(Dispatchers.IO) {
                val secure = store.get(alias)
                if (!secure.isNullOrBlank()) return@withContext secure
                val legacy = legacyKey?.let { context.dataStore.get(it, "") }.orEmpty()
                if (legacy.isNotBlank()) {
                    store.put(alias, legacy)
                    if (store.get(alias) == legacy.trim() && legacyKey != null) {
                        context.dataStore.edit { it.remove(legacyKey) }
                    }
                }
                store.get(alias).orEmpty()
            }
        backingState.value = resolved
    }

    return remember(alias) {
        object : MutableState<String> {
            override var value: String
                get() = backingState.value
                set(value) {
                    backingState.value = value
                    scope.launch(Dispatchers.IO) {
                        store.put(alias, value)
                        if (legacyKey != null) context.dataStore.edit { it.remove(legacyKey) }
                    }
                }

            override fun component1(): String = value

            override fun component2(): (String) -> Unit = { value = it }
        }
    }
}
