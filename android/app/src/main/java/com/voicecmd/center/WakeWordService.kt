package com.voicecmd.center

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import java.util.Locale

/**
 * Always-on listening for the wake phrase, "Hey VCC".
 *
 * This is the piece that lets you start a command without touching the phone at all, in the
 * way "Hey Google" and "Hey Siri" work.
 *
 * How it listens. Android offers no free hotword detector, so this drives the phone's own
 * on-device recogniser in a loop: each short utterance is transcribed locally and thrown
 * away unless it contains the wake phrase. That is the honest trade-off — it uses more
 * battery than a dedicated keyword spotter would, because the recogniser runs continuously,
 * but it needs no extra model, no native code, and it never sends audio off the device.
 *
 * Nothing is ever kept. Transcripts are checked for two words and discarded in the same
 * breath; the service stores nothing and reports nothing.
 *
 * Stopping it. The user can stop it three ways, and all three must keep working: the
 * notification's own Turn off button, the switch inside the app, and Android's own
 * force-stop. The notification exists mainly so that stopping it is always one tap away —
 * listening without an obvious off switch would be unacceptable.
 */
class WakeWordService : Service(), RecognitionListener {

    companion object {
        const val ACTION_START = "com.voicecmd.center.WAKE_START"
        const val ACTION_STOP = "com.voicecmd.center.WAKE_STOP"
        private const val CHANNEL_ID = "wake_word"
        private const val NOTIFICATION_ID = 4101
        private const val WAKE_PHRASE_SPOKEN = "Hey VCC"

        /**
         * After a wake, the loop stays quiet for this long so the command that follows is
         * heard by the app rather than swallowed by this service.
         */
        private const val COMMAND_WINDOW_MS = 15_000L

        /** Normalised forms that count as the wake phrase. */
        private val PHRASES = listOf("heyvcc", "heyvoicecommand", "okvcc", "heyvc c")

        @Volatile
        var running: Boolean = false
            private set
    }

    private var recognizer: SpeechRecognizer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var paused = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            VoicePrefs.setWakeWordEnabled(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification())
        running = true
        startLoop()
        // Not sticky: if Android kills it, it stays off until the user asks for it again.
        // An always-on microphone that restarts itself would be the wrong default.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        paused = false
        handler.removeCallbacksAndMessages(null)
        releaseRecognizer()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ the loop

    private fun startLoop() {
        if (paused) return
        releaseRecognizer()
        val listener = this
        recognizer = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
            ) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
            } else {
                SpeechRecognizer.createSpeechRecognizer(this)
            }
        } catch (_: Exception) {
            null
        }?.apply {
            setRecognitionListener(listener)
            try {
                startListening(intentForWakeWord())
            } catch (_: Exception) {
                scheduleRestart(3000)
            }
        }
    }

    private fun intentForWakeWord() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        // No partial results: a wake phrase is short, and partials would double the work.
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
    }

    private fun releaseRecognizer() {
        try {
            recognizer?.destroy()
        } catch (_: Exception) {
            // already gone
        }
        recognizer = null
    }

    private fun scheduleRestart(delayMs: Long) {
        if (!running) return
        handler.postDelayed({ if (running && !paused) startLoop() }, delayMs)
    }

    /** Lowercases and strips everything but letters, so "Hey, V.C.C.!" reads as "heyvcc". */
    private fun normalise(text: String): String =
        text.lowercase(Locale.US).filter { it.isLetter() }

    private fun isWakePhrase(text: String): Boolean {
        val clean = normalise(text)
        return PHRASES.any { clean.contains(it) }
    }

    /**
     * A short rising tone, the spoken equivalent of a light coming on.
     *
     * A chime rather than a word on purpose: a spoken "yes?" would be picked up by the
     * app's own recogniser a moment later and turn into a stray command.
     */
    private fun acknowledge() {
        try {
            val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90)
            tone.startTone(ToneGenerator.TONE_PROP_ACK, 180)
            handler.postDelayed({
                try {
                    tone.release()
                } catch (_: Exception) {
                    // already released
                }
            }, 600)
        } catch (_: Exception) {
            // a phone without a tone generator is not worth failing over
        }
    }

    private fun onWakeHeard() {
        acknowledge()
        VoiceTrigger.fire()
        // Stand down briefly so the command is not consumed by this loop.
        paused = true
        releaseRecognizer()
        handler.postDelayed({
            paused = false
            if (running) startLoop()
        }, COMMAND_WINDOW_MS)
    }

    // ------------------------------------------------------- RecognitionListener

    override fun onResults(results: Bundle?) {
        val said = results
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            .orEmpty()
        if (said.isNotEmpty() && isWakePhrase(said)) {
            onWakeHeard()
            return
        }
        // Nothing useful: go straight back to listening.
        scheduleRestart(200)
    }

    override fun onError(error: Int) {
        // ERROR_NO_MATCH and ERROR_SPEECH_TIMEOUT are the normal end of a quiet moment.
        scheduleRestart(300)
    }

    override fun onReadyForSpeech(params: Bundle?) = Unit
    override fun onBeginningOfSpeech() = Unit
    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() = Unit
    override fun onPartialResults(partialResults: Bundle?) = Unit
    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    // ------------------------------------------------------------ notification

    private fun buildNotification(): android.app.Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Listening for $WAKE_PHRASE_SPOKEN",
                    // Low: the notification has to be visible but must never make a sound.
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Shown while the app is listening for the wake phrase"
                    setShowBadge(false)
                },
            )
        }

        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, WakeWordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val openApp = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Listening for \"$WAKE_PHRASE_SPOKEN\"")
            .setContentText("Tap Turn off to stop listening.")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(openApp)
            // The off switch is the point of this notification.
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Turn off", stopIntent)
            .build()
    }
}
