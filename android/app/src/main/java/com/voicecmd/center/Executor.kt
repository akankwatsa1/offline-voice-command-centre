package com.voicecmd.center

import android.Manifest
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.Settings
import android.provider.Telephony
import android.telecom.TelecomManager
import java.util.Calendar
import java.util.Locale

/**
 * Carries out the tool calls Needle produced.
 *
 * Every tool reports the tier it actually used, because on modern Android the honest
 * answer differs per action and per device:
 *
 *  - torch, volume, reminders   genuinely silent
 *  - open_app, open_settings    hands over to the app or the settings screen
 *  - sms, dial_contact          opens the composer or dialler; the user completes it
 *  - wifi, hotspot              root shell, or otherwise the settings panel
 *  - navigate_to, search_web    hands over to maps or the browser, which need data
 *
 * The app never claims to have done something it only opened a panel for.
 */
class Executor(private val ctx: Context) {

    data class Outcome(
        val tool: String,
        val tier: Tier,
        val ok: Boolean,
        val spoken: String,
        val detail: String? = null,
    )

    fun execute(call: ToolCall): Outcome = when (call.name) {
        "set_wifi" -> setWifi(call)
        "set_hotspot" -> setHotspot(call)
        "set_torch" -> setTorch(call)
        "send_sms" -> sendSms(call)
        "dial_contact" -> dialContact(call)
        "read_messages" -> readMessages(call)
        "set_reminder" -> setReminder(call)
        "set_alarm" -> setAlarm(call)
        "set_timer" -> setTimer(call)
        "set_volume" -> setVolume(call)
        "open_app" -> openApp(call)
        "open_settings" -> openSettings(call)
        "navigate_to" -> navigateTo(call)
        "search_web" -> searchWeb(call)
        "answer_call" -> answerCall(call)
        else -> Outcome(
            call.name,
            Tier.UNSUPPORTED,
            false,
            "I don't have a way to do that on this phone.",
        )
    }

    private fun actionOf(call: ToolCall): Boolean? = when (call.arguments.optString("action").lowercase()) {
        "on", "true", "enable", "enabled" -> true
        "off", "false", "disable", "disabled" -> false
        else -> null
    }

    private fun bad(call: ToolCall, spoken: String) =
        Outcome(call.name, Tier.UNSUPPORTED, false, spoken)

    private fun launch(intent: Intent): Boolean = try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
        true
    } catch (_: Exception) {
        false
    }

    // ---------------------------------------------------------------- Wi-Fi

    private fun setWifi(call: ToolCall): Outcome {
        val enable = actionOf(call) ?: return bad(call, "I didn't catch whether that was Wi-Fi on or off.")
        val what = if (enable) "on" else "off"

        // A root shell is the only way left to move the radio without the user.
        if (Capabilities.hasRoot()) {
            val code = Capabilities.runAsRoot("svc wifi ${if (enable) "enable" else "disable"}")
            if (code == 0) {
                return Outcome("set_wifi", Tier.SILENT, true, "Wi-Fi $what.")
            }
        }

        // WifiManager.setWifiEnabled still works for apps that target below Android 10.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            try {
                val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                val toggled = wifi.setWifiEnabled(enable)
                if (toggled) {
                    return Outcome("set_wifi", Tier.SILENT, true, "Wi-Fi $what.")
                }
            } catch (_: Exception) {
                // fall through to the panel
            }
        }

        return openInternetPanel(
            call,
            if (enable) "Wi-Fi is in this panel — tap the Wi-Fi switch to turn it on."
            else "Wi-Fi is in this panel — tap the Wi-Fi switch to turn it off.",
        )
    }

    // ------------------------------------------------------------- Hotspot

    private fun setHotspot(call: ToolCall): Outcome {
        val enable = actionOf(call) ?: return bad(call, "I didn't catch whether that was hotspot on or off.")
        val what = if (enable) "on" else "off"

        // Tethering is an @SystemApi. Some rooted builds expose `cmd tethering`, so it is
        // worth one attempt, but it is not something to rely on.
        if (Capabilities.hasRoot()) {
            val code = Capabilities.runAsRoot("cmd tethering ${if (enable) "start" else "stop"}")
            if (code == 0) {
                return Outcome("set_hotspot", Tier.SILENT, true, "Hotspot $what.")
            }
        }

        return openInternetPanel(
            call,
            if (enable) "The hotspot switch is in this panel — tap Hotspot to turn it on."
            else "The hotspot switch is in this panel — tap Hotspot to turn it off.",
        )
    }

    private fun openInternetPanel(call: ToolCall, spoken: String): Outcome {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
        } else {
            Intent(Settings.ACTION_WIRELESS_SETTINGS)
        }
        return if (launch(intent)) {
            Outcome(call.name, Tier.PANEL, true, spoken, "Android does not let an app change this on its own.")
        } else {
            Outcome(call.name, Tier.UNSUPPORTED, false, "I couldn't open the settings panel.")
        }
    }

    // --------------------------------------------------------------- Torch

    private fun setTorch(call: ToolCall): Outcome {
        val enable = actionOf(call) ?: return bad(call, "I didn't catch whether that was light on or off.")
        val manager = ctx.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return Outcome("set_torch", Tier.UNSUPPORTED, false, "This phone has no camera service.")
        val cameraId = try {
            manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        } catch (_: Exception) {
            null
        } ?: return Outcome("set_torch", Tier.UNSUPPORTED, false, "This phone has no flashlight.")

        return try {
            manager.setTorchMode(cameraId, enable)
            Outcome("set_torch", Tier.SILENT, true, "Flashlight ${if (enable) "on" else "off"}.")
        } catch (e: Exception) {
            // setTorchMode throws when another app holds the camera.
            Outcome(
                "set_torch",
                Tier.UNSUPPORTED,
                false,
                "I couldn't reach the flashlight — another app may be using the camera.",
                e.message,
            )
        }
    }

    // ----------------------------------------------------------------- SMS

    private fun sendSms(call: ToolCall): Outcome {
        val recipient = call.arguments.optString("recipient").trim()
        val message = call.arguments.optString("message").trim()
        if (recipient.isEmpty()) return bad(call, "Who should the message go to?")
        if (message.isEmpty()) return bad(call, "What should the message say?")

        val resolved = resolveRecipient(recipient)
            ?: return Outcome(
                "send_sms",
                Tier.UNSUPPORTED,
                false,
                "I couldn't find $recipient in your contacts.",
                "Try the full name, or dictate the number.",
            )
        val (number, label) = resolved

        // ACTION_SENDTO needs no permission: it opens the user's messaging app with the
        // body already written. Sending silently would need SEND_SMS, which is not
        // something a voice command should do without the user seeing what goes out.
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")).apply {
            putExtra("sms_body", message)
        }
        return if (launch(intent)) {
            Outcome("send_sms", Tier.CONFIRM, true, "Message to $label is ready — tap send.")
        } else {
            Outcome("send_sms", Tier.UNSUPPORTED, false, "I couldn't open your messaging app.")
        }
    }

    // ---------------------------------------------------------------- Call

    private fun dialContact(call: ToolCall): Outcome {
        val recipient = call.arguments.optString("recipient").trim()
        if (recipient.isEmpty()) return bad(call, "Who should I call?")

        val resolved = resolveRecipient(recipient)
            ?: return Outcome(
                "dial_contact",
                Tier.UNSUPPORTED,
                false,
                "I couldn't find $recipient in your contacts.",
                "Try the full name, or say the number.",
            )
        val (number, label) = resolved

        // ACTION_DIAL needs no permission and cannot place a call on its own: the dialler
        // opens with the number ready, and the user presses call. That is deliberate.
        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))
        return if (launch(intent)) {
            Outcome("dial_contact", Tier.CONFIRM, true, "$label is ready — tap call.")
        } else {
            Outcome("dial_contact", Tier.UNSUPPORTED, false, "I couldn't open the dialler.")
        }
    }

    /** Turns a spoken name into a number, or accepts a dictated number as-is. */
    private fun resolveRecipient(recipient: String): Pair<String, String>? {
        if (Contacts.looksLikeNumber(recipient)) {
            val number = Contacts.dialable(recipient)
            return if (number.isEmpty()) null else number to recipient
        }
        if (!Capabilities.granted(ctx, Manifest.permission.READ_CONTACTS)) return null
        return Contacts.findNumber(ctx, recipient)
    }

    // ------------------------------------------------------- Read messages

    private fun readMessages(call: ToolCall): Outcome {
        val name = "read_messages"
        if (!Capabilities.granted(ctx, Manifest.permission.READ_SMS)) {
            return Outcome(
                name,
                Tier.UNSUPPORTED,
                false,
                "I need permission to read your messages before I can read them out.",
                "Grant the Messages permission in the app's Setup section.",
            )
        }

        val wanted = call.arguments.optInt("count", 3).coerceIn(1, 10)
        val rows = ArrayList<Triple<String, String, Long>>(wanted)
        try {
            ctx.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
                null,
                null,
                "${Telephony.Sms.DATE} DESC",
            )?.use { cursor ->
                while (cursor.moveToNext() && rows.size < wanted) {
                    val from = cursor.getString(0) ?: continue
                    val body = cursor.getString(1) ?: continue
                    val when_ = cursor.getLong(2)
                    rows.add(Triple(from, body, when_))
                }
            }
        } catch (e: Exception) {
            return Outcome(name, Tier.UNSUPPORTED, false, "I couldn't read your messages.", e.message)
        }

        if (rows.isEmpty()) {
            return Outcome(name, Tier.SILENT, true, "You have no messages.")
        }

        val spoken = StringBuilder()
        spoken.append(if (rows.size == 1) "One message. " else "${rows.size} messages. ")
        rows.forEachIndexed { index, (from, body, _) ->
            // A contact name is far more useful to hear than a raw number.
            val who = Contacts.nameFor(ctx, from) ?: from
            spoken.append("Message ${index + 1}, from $who. ")
            spoken.append(body.trim().take(400))
            if (index < rows.size - 1) spoken.append("  ")
        }

        return Outcome(
            name,
            Tier.SILENT,
            true,
            spoken.toString(),
            "Read the ${rows.size} most recent messages from your inbox.",
        )
    }

    // ------------------------------------------------------------ Reminder

    private fun setReminder(call: ToolCall): Outcome {
        val whenPhrase = call.arguments.optString("when").trim()
        val text = call.arguments.optString("text").trim()
        if (whenPhrase.isEmpty()) return bad(call, "When should I remind you?")
        if (text.isEmpty()) return bad(call, "What should I remind you about?")

        val resolved = TimePhrases.parse(whenPhrase)
            ?: return bad(call, "I couldn't work out the time from \"$whenPhrase\".")

        val (_, exact) = Reminders.schedule(ctx, resolved.atMillis, text)
        val spoken = "Reminder set for ${resolved.spoken}."
        return Outcome(
            "set_reminder",
            Tier.SILENT,
            true,
            if (exact) spoken else "$spoken It may be a few minutes late.",
            buildString {
                if (!exact) {
                    append("Exact alarms are switched off for this app, so Android may delay it. ")
                    append("Allow 'Alarms & reminders' in app settings to make it exact.")
                }
                if (resolved.guessedDay) {
                    if (isNotEmpty()) append(" ")
                    append("That time had already passed today, so it was set for tomorrow.")
                }
            }.trim().ifEmpty { null },
        )
    }

    // --------------------------------------------------------------- Clock

    private fun setAlarm(call: ToolCall): Outcome {
        val whenPhrase = call.arguments.optString("when").trim()
        if (whenPhrase.isEmpty()) return bad(call, "What time should the alarm go off?")

        val resolved = TimePhrases.parse(whenPhrase)
            ?: return bad(call, "I couldn't work out the time from \"$whenPhrase\".")

        val at = Calendar.getInstance().apply { timeInMillis = resolved.atMillis }
        // Handing this to the clock app rather than arming our own alarm is deliberate:
        // a real alarm survives, snoozes and rings full-screen, which a notification
        // cannot do.
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, at.get(Calendar.HOUR_OF_DAY))
            putExtra(AlarmClock.EXTRA_MINUTES, at.get(Calendar.MINUTE))
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        }
        return if (launch(intent)) {
            Outcome("set_alarm", Tier.SILENT, true, "Alarm set for ${resolved.spoken}.")
        } else {
            Outcome(
                "set_alarm",
                Tier.UNSUPPORTED,
                false,
                "I couldn't reach the clock app to set that alarm.",
            )
        }
    }

    private fun setTimer(call: ToolCall): Outcome {
        val phrase = call.arguments.optString("duration").trim()
        if (phrase.isEmpty()) return bad(call, "How long should the timer be?")

        val millis = TimePhrases.parseDuration(phrase)
            ?: return bad(call, "I couldn't work out how long \"$phrase\" is.")
        val seconds = (millis / 1000).toInt()

        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        }
        return if (launch(intent)) {
            Outcome(
                "set_timer",
                Tier.SILENT,
                true,
                "Timer set for ${TimePhrases.describeDuration(millis)}.",
            )
        } else {
            Outcome("set_timer", Tier.UNSUPPORTED, false, "I couldn't reach the clock app to set that timer.")
        }
    }

    // -------------------------------------------------------------- Volume

    private fun setVolume(call: ToolCall): Outcome {
        val action = call.arguments.optString("action").lowercase()
        if (action.isEmpty()) return bad(call, "Should I turn the volume up or down?")

        val manager = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return Outcome("set_volume", Tier.UNSUPPORTED, false, "This phone has no audio service.")

        val stream = when (call.arguments.optString("stream").lowercase()) {
            "ring", "ringtone" -> AudioManager.STREAM_RING
            "alarm" -> AudioManager.STREAM_ALARM
            "notification" -> AudioManager.STREAM_NOTIFICATION
            "call" -> AudioManager.STREAM_VOICE_CALL
            else -> AudioManager.STREAM_MUSIC
        }
        val streamName = when (stream) {
            AudioManager.STREAM_RING -> "Ringer"
            AudioManager.STREAM_ALARM -> "Alarm volume"
            AudioManager.STREAM_NOTIFICATION -> "Notification volume"
            AudioManager.STREAM_VOICE_CALL -> "Call volume"
            else -> "Volume"
        }

        fun percent(): Int {
            val max = manager.getStreamMaxVolume(stream)
            if (max <= 0) return 0
            return Math.round(manager.getStreamVolume(stream) * 100f / max)
        }

        return try {
            when (action) {
                "up" -> {
                    manager.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                    Outcome("set_volume", Tier.SILENT, true, "$streamName up, now ${percent()} percent.")
                }
                "down" -> {
                    manager.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                    Outcome("set_volume", Tier.SILENT, true, "$streamName down, now ${percent()} percent.")
                }
                "maximum" -> {
                    manager.setStreamVolume(stream, manager.getStreamMaxVolume(stream), AudioManager.FLAG_SHOW_UI)
                    Outcome("set_volume", Tier.SILENT, true, "$streamName at maximum.")
                }
                "set" -> {
                    val level = call.arguments.optInt("level", -1)
                    if (level < 0) return bad(call, "What percentage should I set the volume to?")
                    val max = manager.getStreamMaxVolume(stream)
                    val target = Math.round(level.coerceIn(0, 100) * max / 100f)
                    manager.setStreamVolume(stream, target, AudioManager.FLAG_SHOW_UI)
                    Outcome("set_volume", Tier.SILENT, true, "$streamName set to ${percent()} percent.")
                }
                "mute" -> {
                    manager.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
                    // Muting the ringer also means the phone should not ring at all.
                    if (stream == AudioManager.STREAM_RING) {
                        try {
                            manager.ringerMode = AudioManager.RINGER_MODE_SILENT
                        } catch (_: Exception) {
                            // Some builds restrict this without do-not-disturb access.
                        }
                    }
                    Outcome("set_volume", Tier.SILENT, true, "Muted.")
                }
                "unmute" -> {
                    manager.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
                    if (stream == AudioManager.STREAM_RING) {
                        try {
                            manager.ringerMode = AudioManager.RINGER_MODE_NORMAL
                        } catch (_: Exception) {
                            // as above
                        }
                    }
                    Outcome("set_volume", Tier.SILENT, true, "Sound back on, ${percent()} percent.")
                }
                else -> bad(call, "I didn't catch whether you wanted it louder or quieter.")
            }
        } catch (e: Exception) {
            Outcome("set_volume", Tier.UNSUPPORTED, false, "I couldn't change the volume.", e.message)
        }
    }

    // ------------------------------------------------------------- Open app

    private fun openApp(call: ToolCall): Outcome {
        val wanted = call.arguments.optString("app_name").trim()
        if (wanted.isEmpty()) return bad(call, "Which app should I open?")

        val pm = ctx.packageManager
        val query = normalise(wanted)

        data class Candidate(val pkg: String, val label: String, val rank: Int, val length: Int)

        val best = HashMap<String, Candidate>()
        try {
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            // Flag 0 is enough here: QUERY_ALL_PACKAGES in the manifest makes the full
            // list visible to the app.
            @Suppress("DEPRECATION")
            val activities = pm.queryIntentActivities(launcher, 0)
            for (info in activities) {
                val pkg = info.activityInfo?.packageName ?: continue
                if (pkg == ctx.packageName) continue
                val label = try {
                    info.loadLabel(pm).toString()
                } catch (_: Exception) {
                    continue
                }
                val n = normalise(label)
                if (n.isEmpty()) continue
                val rank = when {
                    n == query -> 0
                    n.startsWith(query) -> 1
                    n.contains(query) -> 2
                    else -> -1
                }
                if (rank < 0) continue
                val candidate = Candidate(pkg, label, rank, n.length)
                val existing = best[pkg]
                if (existing == null || candidate.rank < existing.rank ||
                    (candidate.rank == existing.rank && candidate.length < existing.length)
                ) {
                    best[pkg] = candidate
                }
            }
        } catch (e: Exception) {
            return Outcome("open_app", Tier.UNSUPPORTED, false, "I couldn't look through your apps.", e.message)
        }

        val winner = best.values.minWithOrNull(compareBy({ it.rank }, { it.length }))
            ?: return Outcome(
                "open_app",
                Tier.UNSUPPORTED,
                false,
                "I couldn't find an app called $wanted on this phone.",
            )

        val launchIntent = pm.getLaunchIntentForPackage(winner.pkg)
            ?: return Outcome("open_app", Tier.UNSUPPORTED, false, "I found ${winner.label} but it won't open.")

        return if (launch(launchIntent)) {
            Outcome("open_app", Tier.PANEL, true, "Opening ${winner.label}.")
        } else {
            Outcome("open_app", Tier.UNSUPPORTED, false, "I couldn't open ${winner.label}.")
        }
    }

    private fun normalise(value: String): String =
        value.lowercase(Locale.US).filter { it.isLetterOrDigit() }

    // --------------------------------------------------------- Open settings

    private fun openSettings(call: ToolCall): Outcome {
        val panel = call.arguments.optString("panel").trim().lowercase()
        val (action, label) = when (panel) {
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS to "Bluetooth settings"
            "mobile data" -> Settings.ACTION_DATA_ROAMING_SETTINGS to "Mobile data settings"
            "airplane mode" -> Settings.ACTION_AIRPLANE_MODE_SETTINGS to "Airplane mode settings"
            "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS to "Accessibility settings"
            "sound" -> Settings.ACTION_SOUND_SETTINGS to "Sound settings"
            "display" -> Settings.ACTION_DISPLAY_SETTINGS to "Display settings"
            "battery" -> Intent.ACTION_POWER_USAGE_SUMMARY to "Battery settings"
            "apps" -> Settings.ACTION_APPLICATION_SETTINGS to "App settings"
            "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS to "Location settings"
            "storage" -> Settings.ACTION_INTERNAL_STORAGE_SETTINGS to "Storage settings"
            "date and time" -> Settings.ACTION_DATE_SETTINGS to "Date and time settings"
            else -> return bad(call, "I don't know a settings screen called \"$panel\".")
        }
        return if (launch(Intent(action))) {
            Outcome("open_settings", Tier.PANEL, true, "Opening $label.")
        } else {
            Outcome("open_settings", Tier.UNSUPPORTED, false, "I couldn't open $label.")
        }
    }

    // ------------------------------------------------------------ Navigate

    private fun navigateTo(call: ToolCall): Outcome {
        val place = call.arguments.optString("place").trim()
        if (place.isEmpty()) return bad(call, "Where do you want directions to?")

        val mode = when (call.arguments.optString("mode").trim().lowercase()) {
            "walking" -> "w"
            "cycling" -> "b"
            "transit" -> "r"
            else -> "d"
        }
        val encoded = Uri.encode(place)

        // google.navigation starts turn-by-turn directly, but only Google Maps answers it.
        // Fall back to a geo: search pin, which any maps app understands.
        val turnByTurn = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$encoded&mode=$mode"))
        if (turnByTurn.resolveActivity(ctx.packageManager) != null && launch(turnByTurn)) {
            return Outcome("navigate_to", Tier.PANEL, true, "Starting directions to $place.")
        }
        val pin = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$encoded"))
        return if (launch(pin)) {
            Outcome(
                "navigate_to",
                Tier.PANEL,
                true,
                "Showing $place on the map — tap Directions to start.",
                "No turn-by-turn app answered, so the map was opened instead.",
            )
        } else {
            Outcome("navigate_to", Tier.UNSUPPORTED, false, "I couldn't open a maps app for $place.")
        }
    }

    // ---------------------------------------------------------------- Search

    private fun searchWeb(call: ToolCall): Outcome {
        val query = call.arguments.optString("query").trim()
        if (query.isEmpty()) return bad(call, "What should I search for?")

        // This one leaves the phone's own capabilities behind: it hands the question to
        // the browser, which needs a data connection to answer.
        val intent = Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, query)
        return if (launch(intent)) {
            Outcome(
                "search_web",
                Tier.PANEL,
                true,
                "Searching the web for $query.",
                "This opens your browser, so it needs a data connection.",
            )
        } else {
            Outcome("search_web", Tier.UNSUPPORTED, false, "I couldn't open a browser to search for that.")
        }
    }

    // ------------------------------------------------------------ Call state

    private fun answerCall(call: ToolCall): Outcome {
        val name = "answer_call"
        val action = call.arguments.optString("action").trim().lowercase()
        if (action.isEmpty()) return bad(call, "Should I answer the call or hang up?")

        if (!Capabilities.granted(ctx, Manifest.permission.ANSWER_PHONE_CALLS)) {
            return Outcome(
                name,
                Tier.UNSUPPORTED,
                false,
                "I need the Phone permission before I can answer calls.",
                "Grant the Phone permission in the app's Setup section.",
            )
        }

        val telecom = ctx.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            ?: return Outcome(name, Tier.UNSUPPORTED, false, "This phone has no call service.")

        return try {
            when (action) {
                "answer" -> {
                    @Suppress("DEPRECATION")
                    telecom.acceptRingingCall()
                    Outcome(name, Tier.SILENT, true, "Answered the call.")
                }
                "end" -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        telecom.endCall()
                        Outcome(name, Tier.SILENT, true, "Call ended.")
                    } else {
                        Outcome(name, Tier.UNSUPPORTED, false, "This Android version won't let me hang up a call.")
                    }
                }
                else -> bad(call, "I didn't catch whether to answer or hang up.")
            }
        } catch (e: Exception) {
            // Thrown when nothing is ringing, which is the common case for a stray request.
            Outcome(
                name,
                Tier.UNSUPPORTED,
                false,
                "There's no call ringing to ${if (action == "end") "hang up" else "answer"}.",
                e.message,
            )
        }
    }
}
