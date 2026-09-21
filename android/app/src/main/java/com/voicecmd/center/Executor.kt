package com.voicecmd.center

import android.Manifest
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings

/**
 * Carries out the tool calls Needle produced.
 *
 * Every tool reports the tier it actually used, because on modern Android the honest
 * answer differs per action and per device:
 *
 *  - torch       genuinely silent, no permission needed
 *  - reminder    silent, exact when the user has granted exact alarms
 *  - sms         opens the composer with the body filled in; the user taps send
 *  - wifi        root shell, or otherwise the internet settings panel
 *  - hotspot     tethering has no app-facing API at all, so the panel is the answer
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
        "set_reminder" -> setReminder(call)
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
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            ctx.startActivity(intent)
            Outcome(call.name, Tier.PANEL, true, spoken, "Android does not let an app change this on its own.")
        } catch (e: Exception) {
            Outcome(call.name, Tier.UNSUPPORTED, false, "I couldn't open the settings panel.", e.message)
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

        val number: String
        val label: String
        if (Contacts.looksLikeNumber(recipient)) {
            number = Contacts.dialable(recipient)
            label = recipient
        } else {
            if (!Capabilities.granted(ctx, Manifest.permission.READ_CONTACTS)) {
                return Outcome(
                    "send_sms",
                    Tier.UNSUPPORTED,
                    false,
                    "I need permission to read your contacts to find $recipient.",
                    "Grant Contacts access and try again.",
                )
            }
            val hit = Contacts.findNumber(ctx, recipient)
                ?: return Outcome(
                    "send_sms",
                    Tier.UNSUPPORTED,
                    false,
                    "I couldn't find $recipient in your contacts.",
                    "Try the full name, or dictate the number.",
                )
            number = hit.first
            label = hit.second
        }

        if (number.isEmpty()) {
            return bad(call, "I couldn't work out a number for $recipient.")
        }

        // ACTION_SENDTO needs no permission: it opens the user's messaging app with the
        // body already written. Sending silently would need SEND_SMS, which is not
        // something a voice command should do without the user seeing what goes out.
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")).apply {
            putExtra("sms_body", message)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            ctx.startActivity(intent)
            Outcome("send_sms", Tier.CONFIRM, true, "Message to $label is ready — tap send.")
        } catch (e: Exception) {
            Outcome("send_sms", Tier.UNSUPPORTED, false, "I couldn't open your messaging app.", e.message)
        }
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
}
