package com.voicecmd.center

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/**
 * Speech input.
 *
 * On Android 12+ this prefers the on-device recogniser, so the utterance is transcribed
 * on the phone and nothing is uploaded. Where no on-device model exists it falls back to
 * the platform recogniser, which may be a cloud service — `Capabilities.onDeviceRecognition`
 * reports which one is in play, and the UI states it rather than claiming "fully offline"
 * when it is not.
 *
 * SpeechRecognizer must be created and driven from the main thread, so callers marshal.
 */
class SpeechListener(
    private val context: Context,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    private val onError: (String) -> Unit,
    private val onListeningChanged: (Boolean) -> Unit,
) : RecognitionListener {

    private var recognizer: SpeechRecognizer? = null

    /** True when this session will transcribe on the device itself. */
    val onDevice: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    fun start(): Boolean {
        if (!SpeechRecognizer.isRecognitionAvailable(context) &&
            !(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && onDevice)
        ) {
            onError("This phone has no speech recognition service.")
            return false
        }
        release()
        recognizer = try {
            if (onDevice) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } else {
                SpeechRecognizer.createSpeechRecognizer(context)
            }
        } catch (e: Exception) {
            onError("Could not start speech recognition: ${e.message}")
            return false
        }
        return try {
            recognizer?.setRecognitionListener(this)
            recognizer?.startListening(recognitionIntent())
            onListeningChanged(true)
            true
        } catch (e: Exception) {
            onError("Could not start listening: ${e.message}")
            false
        }
    }

    private fun recognitionIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        // Ignored by the on-device recogniser, honoured by the platform one.
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
    }

    fun stop() {
        try {
            recognizer?.stopListening()
        } catch (_: Exception) {
            // already gone
        }
    }

    fun cancel() {
        try {
            recognizer?.cancel()
        } catch (_: Exception) {
            // already gone
        }
        release()
    }

    private fun release() {
        try {
            recognizer?.destroy()
        } catch (_: Exception) {
            // already gone
        }
        recognizer = null
    }

    private fun firstResult(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    private fun messageFor(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "There was a problem with the microphone."
        SpeechRecognizer.ERROR_CLIENT -> "Speech input was cancelled."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is needed."
        SpeechRecognizer.ERROR_NETWORK -> "Speech recognition needs a network connection on this phone."
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognition timed out."
        SpeechRecognizer.ERROR_NO_MATCH -> "I didn't catch that."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The recogniser is busy; try again."
        SpeechRecognizer.ERROR_SERVER -> "The speech service reported an error."
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I didn't hear anything."
        else -> "Speech recognition failed."
    }

    override fun onReadyForSpeech(params: Bundle?) = Unit

    override fun onBeginningOfSpeech() = Unit

    override fun onRmsChanged(rmsdB: Float) = Unit

    override fun onBufferReceived(buffer: ByteArray?) = Unit

    override fun onEndOfSpeech() {
        onListeningChanged(false)
    }

    override fun onError(error: Int) {
        onListeningChanged(false)
        onError(messageFor(error))
        release()
    }

    override fun onResults(results: Bundle?) {
        onListeningChanged(false)
        val text = firstResult(results)
        if (text.isNullOrBlank()) onError("I didn't catch that.") else onFinal(text)
        release()
    }

    override fun onPartialResults(partialResults: Bundle?) {
        firstResult(partialResults)?.takeIf { it.isNotBlank() }?.let(onPartial)
    }

    override fun onEvent(eventType: Int, params: Bundle?) = Unit
}
