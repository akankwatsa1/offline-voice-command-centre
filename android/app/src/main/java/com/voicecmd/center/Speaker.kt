package com.voicecmd.center

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Spoken replies, so the app can be used without looking at the screen.
 *
 * Kept deliberately short: the sentences it speaks come from Executor.Outcome, which
 * describes what actually happened, including when the user still has a tap to make.
 */
class Speaker(private val context: Context) {

    private var tts: TextToSpeech? = null

    @Volatile
    private var ready = false

    @Volatile
    private var queued: String? = null

    fun prepare(onReady: (Boolean) -> Unit = {}) {
        if (tts != null) {
            onReady(ready)
            return
        }
        tts = TextToSpeech(context) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                tts?.language = Locale.getDefault()
                queued?.let { say(it); queued = null }
            }
            onReady(ready)
        }
    }

    fun say(text: String) {
        if (text.isBlank()) return
        val engine = tts
        if (engine == null || !ready) {
            // Still initialising: speak the most recent line once it is up.
            queued = text
            if (engine == null) prepare()
            return
        }
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "voicecmd")
    }

    fun stop() {
        try {
            tts?.stop()
        } catch (_: Exception) {
            // nothing to stop
        }
    }

    fun release() {
        try {
            tts?.shutdown()
        } catch (_: Exception) {
            // already shut down
        }
        tts = null
        ready = false
    }
}
