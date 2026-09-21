package com.voicecmd.center

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.json.JSONArray
import org.json.JSONObject

/**
 * Reminder scheduling that survives a restart.
 *
 * Reminders are held in SharedPreferences as well as in the alarm manager because
 * AlarmManager does not persist across a reboot; BootReceiver re-arms anything still in
 * the future. Exact delivery needs the SCHEDULE_EXACT_ALARM grant on Android 12+, and
 * when it is missing the alarm is set inexactly and flagged as such rather than failing.
 */
object Reminders {

    const val CHANNEL_ID = "voice_reminders"
    private const val PREFS = "voice_reminders"
    private const val KEY_PENDING = "pending"
    private const val KEY_NEXT_ID = "next_id"

    const val EXTRA_ID = "reminder_id"
    const val EXTRA_TEXT = "reminder_text"

    data class Pending(val id: Int, val at: Long, val text: String)

    fun ensureChannel(ctx: Context) {
        val manager = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Voice reminders",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Reminders you set by voice"
            enableVibration(true)
        }
        manager.createNotificationChannel(channel)
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun pending(ctx: Context): List<Pending> {
        val raw = prefs(ctx).getString(KEY_PENDING, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                Pending(o.optInt("id"), o.optLong("at"), o.optString("text"))
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun save(ctx: Context, list: List<Pending>) {
        val array = JSONArray()
        list.forEach { array.put(JSONObject().put("id", it.id).put("at", it.at).put("text", it.text)) }
        prefs(ctx).edit().putString(KEY_PENDING, array.toString()).apply()
    }

    private fun nextId(ctx: Context): Int {
        val id = prefs(ctx).getInt(KEY_NEXT_ID, 1)
        prefs(ctx).edit().putInt(KEY_NEXT_ID, id + 1).apply()
        return id
    }

    private fun intentFor(ctx: Context, reminder: Pending): PendingIntent {
        val intent = Intent(ctx, ReminderReceiver::class.java).apply {
            action = "com.voicecmd.center.REMINDER.${reminder.id}"
            putExtra(EXTRA_ID, reminder.id)
            putExtra(EXTRA_TEXT, reminder.text)
        }
        return PendingIntent.getBroadcast(
            ctx,
            reminder.id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Schedules a reminder and returns whether the system will fire it exactly on time. */
    fun schedule(ctx: Context, atMillis: Long, text: String): Pair<Int, Boolean> {
        ensureChannel(ctx)
        val reminder = Pending(nextId(ctx), atMillis, text)
        save(ctx, pending(ctx) + reminder)
        val exact = arm(ctx, reminder)
        return reminder.id to exact
    }

    /** Arms one alarm. Returns true when it was scheduled exactly. */
    private fun arm(ctx: Context, reminder: Pending): Boolean {
        val manager = ctx.getSystemService(AlarmManager::class.java) ?: return false
        val pi = intentFor(ctx, reminder)
        val exact = Capabilities.canScheduleExactAlarms(ctx)
        return try {
            if (exact) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.at, pi)
            } else {
                // Without the exact-alarm grant the OS batches this; it may land a few
                // minutes late, which the caller reports to the user.
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.at, pi)
            }
            exact
        } catch (_: SecurityException) {
            try {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.at, pi)
            } catch (_: Exception) {
                // nothing further we can do
            }
            false
        }
    }

    fun cancel(ctx: Context, id: Int) {
        val manager = ctx.getSystemService(AlarmManager::class.java)
        val intent = Intent(ctx, ReminderReceiver::class.java).apply {
            action = "com.voicecmd.center.REMINDER.$id"
        }
        val pi = PendingIntent.getBroadcast(
            ctx,
            id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        manager?.cancel(pi)
        save(ctx, pending(ctx).filterNot { it.id == id })
    }

    fun remove(ctx: Context, id: Int) {
        save(ctx, pending(ctx).filterNot { it.id == id })
    }

    /** Called after a reboot: drop what has passed, re-arm what has not. */
    fun rescheduleAll(ctx: Context) {
        val now = System.currentTimeMillis()
        val all = pending(ctx)
        val future = all.filter { it.at > now }
        all.filter { it.at <= now }.forEach { remove(ctx, it.id) }
        future.forEach { arm(ctx, it) }
    }

    fun notify(ctx: Context, id: Int, text: String) {
        ensureChannel(ctx)
        val open = Intent(ctx, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pi = PendingIntent.getActivity(
            ctx,
            id,
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("Reminder")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(id, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted; the plugin surfaces this to the UI.
        }
    }
}

/** Fires when a reminder is due. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(Reminders.EXTRA_ID, 0)
        val text = intent.getStringExtra(Reminders.EXTRA_TEXT) ?: return
        Reminders.notify(context, id, text)
        Reminders.remove(context, id)
    }
}

/**
 * AlarmManager does not survive a reboot, so re-arm anything still in the future.
 * MY_PACKAGE_REPLACED covers the same need after an app update.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED ->
                Reminders.rescheduleAll(context)
        }
    }
}
