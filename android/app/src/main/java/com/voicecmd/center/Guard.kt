package com.voicecmd.center

/**
 * A safety net that does not depend on the model.
 *
 * On the frozen suite, Needle 3 acted on both "do not turn off the wifi" and "she said
 * turn off the wifi", the first with a confidence of 1.0. Its own reasoning line even read
 * "User forbids turning off wifi. Alternative request: turn off wifi."
 *
 * A 29 MB model is a good router and a poor guardian, so anything that reads as negated,
 * reported or hypothetical is downgraded from act to confirm regardless of the score.
 * The cost is an extra tap; the alternative is silently doing the opposite of what was
 * asked. Needle's documented act/confirm/refuse contract already has the tier for this.
 */
object Guard {

    private data class Rule(val pattern: Regex, val kind: Kind)

    private enum class Kind { NEGATED, REPORTED, HYPOTHETICAL }

    private val RULES = listOf(
        // A negative instruction, or a cancellation.
        Rule(
            Regex("""\b(do not|don't|dont|does not|doesn't|never|no need to|needn't|cancel|cancel that|forget it)\b"""),
            Kind.NEGATED,
        ),
        // Reported speech: someone else saying it is not the user asking for it.
        Rule(
            Regex("""\b(she|he|they|someone|somebody|mum|mom|dad|my wife|my husband|my boss)\s+(said|says|asked|asks|wants|wanted|told|tells|thinks|thinks that)\b"""),
            Kind.REPORTED,
        ),
        // A question or a condition rather than an instruction.
        Rule(
            Regex("""\b(if i|if we|whether|should i|shall i|do i need|did i|why did|what if)\b"""),
            Kind.HYPOTHETICAL,
        ),
    )

    /**
     * Returns a sentence to say while asking the user to confirm, or null when the request
     * reads as a plain instruction.
     */
    fun reasonToConfirm(utterance: String): String? {
        val text = utterance.lowercase().trim()
        if (text.isEmpty()) return null

        for (rule in RULES) {
            if (!rule.pattern.containsMatchIn(text)) continue
            val quoted = utterance.trim().trimEnd('.', '!', '?', ' ')
            return when (rule.kind) {
                Kind.REPORTED ->
                    "\"$quoted\" sounds like someone else's words, so confirm it first."
                Kind.HYPOTHETICAL ->
                    "\"$quoted\" is a question rather than an instruction, so confirm it first."
                Kind.NEGATED ->
                    "\"$quoted\" contains a negative. I want you to confirm what you meant before I do anything."
            }
        }
        return null
    }
}
