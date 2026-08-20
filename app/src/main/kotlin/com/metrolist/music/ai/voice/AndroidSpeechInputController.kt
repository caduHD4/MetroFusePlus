/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ai.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

class AndroidSpeechInputController(
    context: Context,
    private val onTranscript: (String) -> Unit,
    private val onListeningChanged: (Boolean) -> Unit,
    private val onFailure: () -> Unit,
) : RecognitionListener {
    private val recognizer =
        runCatching {
            if (SpeechRecognizer.isRecognitionAvailable(context)) {
                SpeechRecognizer.createSpeechRecognizer(context.applicationContext)
            } else {
                null
            }
        }.getOrNull()
    val isAvailable: Boolean
        get() = recognizer != null
    private var listening = false

    init {
        recognizer?.setRecognitionListener(this)
    }

    fun start() {
        val activeRecognizer = recognizer ?: run {
            onFailure()
            return
        }
        if (listening) {
            stop()
            return
        }
        listening = true
        onListeningChanged(true)
        runCatching {
            activeRecognizer.startListening(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                },
            )
        }.onFailure {
            updateListening(false)
            onFailure()
        }
    }

    fun stop() {
        runCatching { recognizer?.stopListening() }
        updateListening(false)
    }

    fun destroy() {
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
        updateListening(false)
    }

    override fun onReadyForSpeech(params: Bundle?) = Unit

    override fun onBeginningOfSpeech() = Unit

    override fun onRmsChanged(rmsdB: Float) = Unit

    override fun onBufferReceived(buffer: ByteArray?) = Unit

    override fun onEndOfSpeech() {
        updateListening(false)
    }

    override fun onError(error: Int) {
        updateListening(false)
        if (error != SpeechRecognizer.ERROR_CLIENT) {
            onFailure()
        }
    }

    override fun onResults(results: Bundle?) {
        updateListening(false)
        results.bestTranscript()?.let(onTranscript) ?: onFailure()
    }

    override fun onPartialResults(partialResults: Bundle?) {
        partialResults.bestTranscript()?.let(onTranscript)
    }

    override fun onEvent(
        eventType: Int,
        params: Bundle?,
    ) = Unit

    private fun updateListening(value: Boolean) {
        if (listening == value) return
        listening = value
        onListeningChanged(value)
    }

    private fun Bundle?.bestTranscript(): String? =
        this
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.trim()
            ?.takeIf(String::isNotEmpty)
}
