package com.voicecmd.center

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.TextView

/**
 * The two ways of starting the app without opening it.
 *
 * Android hands the volume keys to an accessibility service and to nothing else, so
 * long-pressing volume down to talk is only possible from here. The same service is what
 * makes a floating microphone possible, and later it is what will let the app search
 * inside apps that expose no intent for it, such as WhatsApp.
 *
 * The service deliberately does nothing else: it observes no screen content, keeps no
 * record, and only listens for one key and one tap. Anything more would be a privacy
 * problem in an app whose whole promise is that your voice stays on the phone.
 */
class VoiceAccessService : AccessibilityService() {

    companion object {
        /** Set while the user has the service switched on in Accessibility settings. */
        @Volatile
        var instance: VoiceAccessService? = null
            private set

        /**
         * How many key-repeat events count as a long press. Held volume keys repeat roughly
         * every 50ms, so four is about a fifth of a second — long enough not to fire by
         * accident while lowering the volume normally, short enough to feel deliberate.
         */
        private const val REPEATS_FOR_LONG_PRESS = 4
    }

    private var bubble: View? = null
    private var volumeDownRepeats = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        if (VoicePrefs.bubbleEnabled(this) && Settings.canDrawOverlays(this)) {
            showBubble()
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        hideBubble()
        instance = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    /**
     * Long-pressing volume down starts listening.
     *
     * The first press is not consumed, so lowering the volume normally still works. Only
     * once the key has been held long enough is the event swallowed, and the release with
     * it — otherwise the system would go on adjusting the volume after the user had
     * already started talking.
     */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) return false
        if (!VoicePrefs.volumeKeyEnabled(this)) return false

        return when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    volumeDownRepeats = 0
                    false
                } else {
                    volumeDownRepeats = event.repeatCount
                    if (event.repeatCount == REPEATS_FOR_LONG_PRESS) {
                        VoiceTrigger.fire()
                        true
                    } else {
                        event.repeatCount > REPEATS_FOR_LONG_PRESS
                    }
                }
            }
            KeyEvent.ACTION_UP -> {
                val wasLongPress = volumeDownRepeats >= REPEATS_FOR_LONG_PRESS
                volumeDownRepeats = 0
                wasLongPress
            }
            else -> false
        }
    }

    /** A round microphone that floats over whatever is on screen. */
    fun showBubble() {
        if (bubble != null) return
        if (!Settings.canDrawOverlays(this)) return

        val manager = getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val density = resources.displayMetrics.density
        val size = (60 * density).toInt()

        // Drawn in code rather than from a layout, so the bubble cannot go missing from the
        // APK the way a resource can.
        val view = TextView(this).apply {
            text = "\uD83C\uDF99"
            textSize = 26f
            gravity = Gravity.CENTER
            contentDescription = "Voice Command microphone. Tap to speak."
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF4F46E5.toInt())
                setStroke((2 * density).toInt(), 0xFFFFFFFF.toInt())
            }
            setOnClickListener { VoiceTrigger.fire() }
        }

        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Not focusable, so the bubble never steals the keyboard from the app behind it.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = (8 * density).toInt()
            y = (240 * density).toInt()
        }

        return try {
            manager.addView(view, params)
            bubble = view
        } catch (_: Exception) {
            bubble = null
        }
    }

    fun hideBubble() {
        val view = bubble ?: return
        try {
            (getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.removeView(view)
        } catch (_: Exception) {
            // already gone
        }
        bubble = null
    }

    fun refreshBubble() {
        if (VoicePrefs.bubbleEnabled(this) && Settings.canDrawOverlays(this)) showBubble() else hideBubble()
    }
}

/**
 * The single hand-off point from a trigger to the plugin.
 *
 * The plugin registers itself while it is alive; a trigger with nothing registered does
 * nothing rather than crashing, which is what should happen when the app has been closed
 * but the service is still running.
 */
object VoiceTrigger {
    @Volatile
    var listener: (() -> Unit)? = null

    fun fire() {
        listener?.invoke()
    }
}

/** The switches the user controls, kept where the services and the plugin all see them. */
object VoicePrefs {
    private const val FILE = "voicecmd_prefs"
    private const val KEY_VOLUME = "volume_key_enabled"
    private const val KEY_BUBBLE = "bubble_enabled"
    private const val KEY_WAKE = "wake_word_enabled"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun volumeKeyEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_VOLUME, false)

    fun setVolumeKeyEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_VOLUME, enabled).apply()
    }

    fun bubbleEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_BUBBLE, false)

    fun setBubbleEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_BUBBLE, enabled).apply()
    }

    fun wakeWordEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_WAKE, false)

    fun setWakeWordEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_WAKE, enabled).apply()
    }
}
