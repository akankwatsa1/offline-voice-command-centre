package com.voicecmd.center

import android.Manifest
import android.content.Context
import android.net.Uri
import android.provider.ContactsContract

/** A candidate match, ranked so the most specific one wins. */
private data class Match(val number: String, val name: String, val rank: Int, val length: Int)

/**
 * Turns the name a user actually said into a phone number.
 *
 * This is deliberately done in Kotlin rather than handed to the model. Needle's tool
 * schemas share the context window with the conversation, so listing contacts in the
 * prompt would either blow the budget or force tool retrieval; the fix is to keep the
 * `recipient` argument a plain span of the request and resolve it here.
 */
object Contacts {

    private val NUMBER_LIKE = Regex("^[+]?[0-9()\\-\\s.]{6,}$")

    /** True when the recipient is already dialable, so no lookup is needed. */
    fun looksLikeNumber(value: String): Boolean =
        NUMBER_LIKE.matches(value.trim()) && value.count { it.isDigit() } >= 6

    /** Strips formatting so the value is safe to put in an `smsto:` URI. */
    fun dialable(value: String): String = buildString {
        value.trim().forEach { c ->
            when {
                c.isDigit() -> append(c)
                c == '+' && isEmpty() -> append(c)
            }
        }
    }

    private fun normalise(value: String): String =
        value.lowercase().filter { it.isLetterOrDigit() }

    /**
     * Looks a phone number up in contacts so a read-out can say a name instead of a
     * string of digits. Returns null when contacts are unreadable or nothing matches.
     */
    fun nameFor(ctx: Context, address: String): String? {
        if (address.isBlank()) return null
        if (!Capabilities.granted(ctx, Manifest.permission.READ_CONTACTS)) return null
        return try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(address),
            )
            ctx.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Best match for a spoken name. Returns the number and the contact name it matched,
     * or null when contacts are unreadable or nothing is close enough.
     *
     * Matching prefers an exact name, then a prefix, then a substring, then any single
     * name token — and among equals, the shortest display name, which is the most
     * specific hit for a query like "mum".
     */
    fun findNumber(ctx: Context, recipient: String): Pair<String, String>? {
        if (!Capabilities.granted(ctx, Manifest.permission.READ_CONTACTS)) return null
        val query = normalise(recipient)
        if (query.isEmpty()) return null

        val best = HashMap<String, Match>()

        try {
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
            )
            ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                null,
            )?.use { cursor ->
                val nameIdx = cursor.getColumnIndexOrThrow(projection[0])
                val numberIdx = cursor.getColumnIndexOrThrow(projection[1])
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameIdx) ?: continue
                    val number = cursor.getString(numberIdx) ?: continue
                    if (number.isBlank()) continue

                    val normalisedName = normalise(name)
                    if (normalisedName.isEmpty()) continue

                    // -1 means "no match"; a sentinel keeps this a plain expression, since
                    // `continue` is not allowed as a value inside a when expression.
                    val rank = when {
                        normalisedName == query -> 0
                        normalisedName.startsWith(query) -> 1
                        normalisedName.contains(query) -> 2
                        normalisedName.split(Regex("[^a-z0-9]+")).any { it == query } -> 3
                        else -> -1
                    }
                    if (rank < 0) continue

                    val candidate = Match(dialable(number), name, rank, normalisedName.length)
                    val existing = best[number]
                    if (existing == null || candidate.rank < existing.rank ||
                        (candidate.rank == existing.rank && candidate.length < existing.length)
                    ) {
                        best[number] = candidate
                    }
                }
            }
        } catch (_: Exception) {
            return null
        }

        return best.values
            .filter { it.number.isNotEmpty() }
            .minWithOrNull(compareBy({ it.rank }, { it.length }))
            ?.let { it.number to it.name }
    }
}
