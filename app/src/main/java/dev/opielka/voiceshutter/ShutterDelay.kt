package dev.opielka.voiceshutter

/**
 * How long to wait between hearing the phrase and pressing the shutter.
 *
 * A short delay is wanted, not tolerated: firing the instant the word is recognised
 * catches the subject mid-vowel, still mouthing the trigger word.
 *
 * The steps are deliberately uneven, so the slider works on positions and this maps
 * them to milliseconds.
 */
object ShutterDelay {

    val OPTIONS_MS = listOf(0L, 500L, 1_000L, 2_000L, 3_000L)

    val DEFAULT_MS = 500L

    val steps: Int = OPTIONS_MS.size - 2

    fun indexOf(delayMs: Long): Int =
        OPTIONS_MS.indexOf(delayMs).takeIf { it >= 0 } ?: OPTIONS_MS.indexOf(DEFAULT_MS)

    fun atIndex(index: Int): Long = OPTIONS_MS[index.coerceIn(OPTIONS_MS.indices)]

    /** Nearest supported value, so a stored delay from an older build still resolves. */
    fun sanitise(delayMs: Long): Long = OPTIONS_MS.minBy { kotlin.math.abs(it - delayMs) }

    fun label(delayMs: Long): String = when (delayMs) {
        0L -> "natychmiast"
        500L -> "0,5 s (zalecane)"
        1_000L -> "1 s"
        else -> "${delayMs / 1000} s"
    }
}
