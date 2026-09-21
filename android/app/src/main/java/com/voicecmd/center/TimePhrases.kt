package com.voicecmd.center

import java.util.Calendar
import java.util.Locale

/**
 * Resolves the `when` span the model copied out of a request into a wall-clock instant.
 *
 * Needle is told the current date as a session fact and passes relative phrases through
 * verbatim, which is the right split: this side owns the clock and the arithmetic, and
 * the result is always echoed back to the user before or after the reminder is set, so an
 * ambiguous phrase like "at 8" cannot silently become the wrong one.
 */
object TimePhrases {

    private val RELATIVE = Regex("""\bin\s+(\d{1,3})\s*(min|mins|minute|minutes|hour|hours|hr|hrs|second|seconds)\b""")

    // "5:00 pm", "17:30", "5.30pm", "6am", "at 7"
    private val CLOCK = Regex("""\b(\d{1,2})(?:[:.](\d{2}))?\s*(am|pm|a\.m\.|p\.m\.)?\b""")

    private val WEEKDAYS = mapOf(
        "sunday" to Calendar.SUNDAY,
        "monday" to Calendar.MONDAY,
        "tuesday" to Calendar.TUESDAY,
        "wednesday" to Calendar.WEDNESDAY,
        "thursday" to Calendar.THURSDAY,
        "friday" to Calendar.FRIDAY,
        "saturday" to Calendar.SATURDAY,
        "sun" to Calendar.SUNDAY,
        "mon" to Calendar.MONDAY,
        "tue" to Calendar.TUESDAY,
        "tues" to Calendar.TUESDAY,
        "wed" to Calendar.WEDNESDAY,
        "thu" to Calendar.THURSDAY,
        "thur" to Calendar.THURSDAY,
        "thurs" to Calendar.THURSDAY,
        "fri" to Calendar.FRIDAY,
        "sat" to Calendar.SATURDAY,
    )

    /** A resolved instant plus the wording to read back to the user. */
    data class Resolved(val atMillis: Long, val spoken: String, val guessedDay: Boolean)

    fun parse(phrase: String, now: Long = System.currentTimeMillis()): Resolved? {
        val text = phrase.lowercase(Locale.US).trim()
        if (text.isEmpty()) return null

        // "in 20 minutes", "in 2 hours"
        RELATIVE.find(text)?.let { m ->
            // A non-local return from this inline lambda, so a malformed amount abandons
            // the parse rather than falling through to the clock parsing below.
            val amount = m.groupValues[1].toLongOrNull() ?: return null
            val unit = m.groupValues[2]
            val millis = when {
                unit.startsWith("sec") -> amount * 1_000
                unit.startsWith("min") -> amount * 60_000
                else -> amount * 3_600_000
            }
            val at = now + millis
            val label = if (unit.startsWith("sec")) "in $amount seconds"
            else if (unit.startsWith("min")) "in $amount minutes"
            else "in $amount hours"
            return Resolved(at, label, guessedDay = false)
        }

        val calendar = Calendar.getInstance().apply { timeInMillis = now }

        // Day offset
        var dayOffset: Int? = null
        var explicitDay = false
        when {
            text.contains("day after tomorrow") -> {
                dayOffset = 2; explicitDay = true
            }
            text.contains("tomorrow") -> {
                dayOffset = 1; explicitDay = true
            }
            text.contains("tonight") || text.contains("today") || text.contains("this ") -> {
                dayOffset = 0; explicitDay = true
            }
        }
        if (dayOffset == null) {
            for ((word, dow) in WEEKDAYS) {
                if (Regex("""\b$word\b""").containsMatchIn(text)) {
                    val current = calendar.get(Calendar.DAY_OF_WEEK)
                    var delta = (dow - current + 7) % 7
                    if (delta == 0) delta = 7
                    dayOffset = delta
                    explicitDay = true
                    break
                }
            }
        }

        // Time of day. Initialised rather than declared-then-assigned so the compiler has
        // no reason to doubt the definite-assignment analysis across the when below.
        var hour = 0
        var minute = 0
        var sawClock = false

        when {
            text.contains("noon") -> {
                hour = 12; sawClock = true
            }
            text.contains("midnight") -> {
                hour = 0; sawClock = true
            }
            else -> {
                val candidates = CLOCK.findAll(text).filter { m ->
                    val raw = m.value.trim()
                    if (!raw.any { it.isDigit() }) return@filter false
                    val h = m.groupValues[1].toIntOrNull() ?: return@filter false
                    if (h !in 0..24) return@filter false
                    // Skip numbers glued to a preceding dot or letter, as in "S.4" or
                    // "v2" — those are labels in the request, not clock times.
                    val before = text.getOrNull(m.range.first - 1)
                    if (before != null && (before == '.' || before.isLetter())) return@filter false
                    true
                }.toList()

                if (candidates.isEmpty()) return null
                // Prefer an explicit "5 pm", then "5:00", and only then a bare "at 5",
                // so an unrelated number earlier in the sentence cannot win.
                val clockMatch = candidates.firstOrNull { it.groupValues[3].isNotEmpty() }
                    ?: candidates.firstOrNull { it.groupValues[2].isNotEmpty() }
                    ?: candidates.first()

                hour = clockMatch.groupValues[1].toIntOrNull() ?: return null
                minute = clockMatch.groupValues[2].toIntOrNull() ?: 0
                sawClock = true

                val meridiem = clockMatch.groupValues[3].replace(".", "")
                when {
                    meridiem.startsWith("p") -> if (hour in 1..11) hour += 12
                    meridiem.startsWith("a") -> if (hour == 12) hour = 0
                    hour >= 13 -> {
                        // already 24-hour
                    }
                    text.contains("morning") -> if (hour == 12) hour = 0
                    text.contains("afternoon") || text.contains("evening") || text.contains("night") ->
                        if (hour in 1..11) hour += 12
                    // No meridiem and no day part: read 1-6 as evening, 7-11 as morning.
                    hour in 1..6 -> hour += 12
                    else -> Unit
                }
            }
        }

        if (!sawClock) return null
        if (minute !in 0..59) return null
        hour = hour.coerceIn(0, 23)

        calendar.set(Calendar.HOUR_OF_DAY, hour)
        calendar.set(Calendar.MINUTE, minute)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)

        if (dayOffset != null) {
            calendar.add(Calendar.DAY_OF_YEAR, dayOffset)
        }

        var guessedDay = false
        if (calendar.timeInMillis <= now) {
            // An explicit "today at 5" that already passed, or a bare "at 7" earlier today,
            // rolls forward a day. Flag it so the user is told.
            calendar.add(Calendar.DAY_OF_YEAR, 1)
            guessedDay = !explicitDay
        }

        return Resolved(calendar.timeInMillis, describe(calendar), guessedDay)
    }

    private fun describe(calendar: Calendar): String {
        val now = Calendar.getInstance()
        val time = String.format(
            Locale.US,
            "%d:%02d %s",
            calendar.get(Calendar.HOUR).let { if (it == 0) 12 else it },
            calendar.get(Calendar.MINUTE),
            if (calendar.get(Calendar.AM_PM) == Calendar.AM) "AM" else "PM",
        )
        val sameDay = calendar.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
            calendar.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
        val tomorrow = run {
            val t = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }
            calendar.get(Calendar.YEAR) == t.get(Calendar.YEAR) &&
                calendar.get(Calendar.DAY_OF_YEAR) == t.get(Calendar.DAY_OF_YEAR)
        }
        val weekday = calendar.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.LONG, Locale.US)
        return when {
            sameDay -> time
            tomorrow -> "tomorrow at $time"
            else -> "$weekday at $time"
        }
    }
}
