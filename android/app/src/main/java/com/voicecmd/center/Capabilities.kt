package com.voicecmd.center

import android.app.AlarmManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import java.util.concurrent.TimeUnit

/**
 * How a command can actually be carried out on this device.
 *
 * Android does not let an ordinary app flip the Wi-Fi radio or the hotspot on Android 10
 * and later: WifiManager.setWifiEnabled is a no-op for targetSdk >= 29 and tethering is
 * an @SystemApi. Rather than pretend, every tool declares the strongest tier this device
 * permits, and the UI tells the user which one it used.
 */
enum class Tier(val wire: String) {
    /** Done by the app, no user involvement. */
    SILENT("silent"),

    /** The exact settings panel is opened; one tap by the user completes it. */
    PANEL("panel"),

    /** An intent the user reviews and confirms, like the SMS composer. */
    CONFIRM("confirm"),

    /** Not possible on this device. */
    UNSUPPORTED("unsupported")
}

object Capabilities {

    @Volatile
    private var rootChecked = false

    @Volatile
    private var rootAvailable = false

    /** Cached: probing for su spawns a process, so it runs at most once per app run. */
    fun hasRoot(): Boolean {
        if (rootChecked) return rootAvailable
        synchronized(this) {
            if (!rootChecked) {
                rootAvailable = try {
                    val p = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
                    val exited = p.waitFor(2, TimeUnit.SECONDS)
                    if (!exited) {
                        p.destroy()
                        false
                    } else {
                        p.inputStream.bufferedReader().readText().contains("uid=0")
                    }
                } catch (_: Exception) {
                    false
                }
                rootChecked = true
            }
        }
        return rootAvailable
    }

    /**
     * Runs a command as root. Returns the exit code, or null when there is no root shell.
     * `svc wifi enable` is the one radio toggle a root shell can still perform.
     */
    fun runAsRoot(command: String): Int? {
        if (!hasRoot()) return null
        return try {
            val p = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            val finished = p.waitFor(8, TimeUnit.SECONDS)
            if (!finished) {
                p.destroy()
                return null
            }
            // A root shell that prints "not found" or "unknown command" did not work,
            // even though it exited 0.
            val failed = out.contains("not found", ignoreCase = true) ||
                out.contains("unknown command", ignoreCase = true) ||
                out.contains("inaccessible", ignoreCase = true) ||
                out.contains("Permission denied", ignoreCase = true)
            if (failed) 1 else p.exitValue()
        } catch (_: Exception) {
            null
        }
    }

    fun isDeviceOwner(ctx: Context): Boolean = try {
        val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        dpm.isDeviceOwnerApp(ctx.packageName)
    } catch (_: Exception) {
        false
    }

    fun canScheduleExactAlarms(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return false
        return am.canScheduleExactAlarms()
    }

    fun isWifiPanelAvailable(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    fun granted(ctx: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(ctx, permission) == PackageManager.PERMISSION_GRANTED

    /** True when the device has an offline recognition model, so speech never leaves it. */
    fun onDeviceRecognition(ctx: Context): Boolean = try {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)
    } catch (_: Exception) {
        false
    }

    fun hasFlash(ctx: Context): Boolean = try {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
        cm.cameraIdList.any { id ->
            cm.getCameraCharacteristics(id)
                .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
    } catch (_: Exception) {
        false
    }

    /** The `network:` fact handed to the model, so it can reason about connectivity. */
    fun networkFact(ctx: Context): String = try {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val n = cm.activeNetwork
        val caps = n?.let { cm.getNetworkCapabilities(it) }
        when {
            caps == null -> "offline"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
    } catch (_: Exception) {
        "unknown"
    }

    fun batteryPercent(ctx: Context): Int? = try {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (level in 0..100) level else null
    } catch (_: Exception) {
        null
    }

    /** Everything the UI needs to explain what this phone will and will not let the app do. */
    fun report(ctx: Context): Map<String, Any?> {
        val root = hasRoot()
        val owner = isDeviceOwner(ctx)
        val exact = canScheduleExactAlarms(ctx)
        val flash = hasFlash(ctx)

        // Wi-Fi: only a root shell can still toggle the radio without user involvement.
        val wifi = when {
            root -> Tier.SILENT
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> Tier.SILENT
            isWifiPanelAvailable() -> Tier.PANEL
            else -> Tier.UNSUPPORTED
        }

        // Hotspot: tethering has no app-facing API. Even root only helps on builds that
        // expose `cmd tethering`, so the reliable answer is the settings panel.
        val hotspot = if (root) Tier.SILENT else Tier.PANEL

        return mapOf(
            "sdkInt" to Build.VERSION.SDK_INT,
            "androidRelease" to Build.VERSION.RELEASE,
            "device" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "rooted" to root,
            "deviceOwner" to owner,
            "exactAlarms" to exact,
            "hasFlash" to flash,
            "onDeviceRecognition" to onDeviceRecognition(ctx),
            "wifi" to wifi.wire,
            "hotspot" to hotspot.wire,
            "torch" to (if (flash) Tier.SILENT else Tier.UNSUPPORTED).wire,
            "sms" to Tier.CONFIRM.wire,
            // Reminders are always scheduled by the app itself; `reminderApproximate`
            // reports whether the system will let them fire exactly on time.
            "reminder" to Tier.SILENT.wire,
            "reminderApproximate" to !exact,
        )
    }
}
