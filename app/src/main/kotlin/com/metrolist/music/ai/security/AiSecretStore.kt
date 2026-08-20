/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ai.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.metrolist.music.constants.DeeplApiKey
import com.metrolist.music.constants.OpenRouterApiKey
import com.metrolist.music.constants.AiProviderKey
import com.metrolist.music.utils.dataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AiSecretStore
@Inject
constructor(
    @ApplicationContext context: Context,
) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    fun get(alias: String): String? =
        synchronized(globalLock) {
            preferences.getString(alias, null)?.let(::decrypt)
        }

    fun has(alias: String): Boolean = get(alias).isNullOrBlank().not()

    fun put(alias: String, secret: String) {
        val normalized = secret.trim()
        synchronized(globalLock) {
            if (normalized.isBlank()) {
                preferences.edit().remove(alias).apply()
            } else {
                preferences.edit().putString(alias, encrypt(normalized)).apply()
            }
        }
    }

    fun remove(alias: String) {
        synchronized(globalLock) {
            preferences.edit().remove(alias).apply()
        }
    }

    fun masked(alias: String): String? {
        val value = get(alias) ?: return null
        val suffix = value.takeLast(4).takeIf { value.length > 8 }.orEmpty()
        return "••••••••$suffix"
    }

    suspend fun migrateLegacyTranslationKeys() {
        val legacy = appContext.dataStore.data.first()
        val legacyProvider = legacy[AiProviderKey].orEmpty().ifBlank { "OpenRouter" }
        migrateLegacyValue(
            preferences = legacy,
            legacyKey = OpenRouterApiKey,
            destinationAlias = AiSecretAliases.lyricsChat(legacyProvider),
        )
        migrateLegacyValue(
            preferences = legacy,
            legacyKey = DeeplApiKey,
            destinationAlias = AiSecretAliases.lyrics("DeepL"),
        )
    }

    private suspend fun migrateLegacyValue(
        preferences: Preferences,
        legacyKey: Preferences.Key<String>,
        destinationAlias: String,
    ) {
        val plaintext = preferences[legacyKey]?.takeIf { it.isNotBlank() } ?: return
        if (!has(destinationAlias)) {
            put(destinationAlias, plaintext)
        }
        if (get(destinationAlias) == plaintext.trim()) {
            appContext.dataStore.edit { it.remove(legacyKey) }
        }
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        return listOf(cipher.iv, ciphertext)
            .joinToString(SEPARATOR) { Base64.encodeToString(it, Base64.NO_WRAP) }
    }

    private fun decrypt(value: String): String? =
        runCatching {
            val parts = value.split(SEPARATOR, limit = 2)
            require(parts.size == 2)
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_LENGTH, iv))
            String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
        }.getOrNull()

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        return KeyGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
            .apply {
                init(
                    KeyGenParameterSpec
                        .Builder(
                            KEY_ALIAS,
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setRandomizedEncryptionRequired(true)
                        .build(),
                )
            }.generateKey()
    }

    companion object {
        private const val PREFERENCES_NAME = "ai_secrets"
        private const val KEY_ALIAS = "metrofuse_ai_secrets_v1"
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH = 128
        private const val SEPARATOR = ":"
        private val globalLock = Any()
    }
}

object AiSecretAliases {
    fun assistant(providerId: String): String = "assistant:${providerId.normalizedProviderId()}"

    fun lyrics(providerId: String): String = "lyrics:${providerId.normalizedProviderId()}"

    fun lyricsChat(providerId: String): String =
        lyrics(providerId.takeUnless { it.equals("DeepL", ignoreCase = true) } ?: "OpenRouter")

    private fun String.normalizedProviderId(): String = lowercase().replace(Regex("[^a-z0-9_-]"), "_")
}
