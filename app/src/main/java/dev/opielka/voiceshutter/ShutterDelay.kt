package dev.opielka.voiceshutter

/**
 * How long to wait between hearing the phrase and pressing the shutter.
 *
 * A short delay is wanted, not tolerated: firing the instant the word is recognised
 * catches the subject mid-vowel, still mouthing the trigger word.
 *
 * The slider is a time axis rather than a row of equal steps, so 2→3 s occupies twice
 * the distance of 0→0.5 s. [fractionOf] places both the thumb and the tick labels.
 */
object ShutterDelay {

    val OPTIONS_MS = listOf(0L, 500L, 1_000L, 2_000L, 3_000L)

    val DEFAULT_MS = 500L

    val MAX_MS = OPTIONS_MS.max()

    /** Position along the axis, 0f at the start and 1f at [MAX_MS]. */
    fun fractionOf(delayMs: Long): Float =
        (delayMs.toFloat() / MAX_MS).coerceIn(0f, 1f)

    /** Nearest supported value — snaps a drag, and resolves a value stored by an older build. */
    fun sanitise(delayMs: Long): Long = OPTIONS_MS.minBy { kotlin.math.abs(it - delayMs) }

    fun fromSeconds(seconds: Float): Long = sanitise((seconds * 1000).toLong())

    fun label(delayMs: Long): String = when (delayMs) {
        0L -> "natychmiast"
        500L -> "0,5 s (zalecane)"
        1_000L -> "1 s"
        else -> "${delayMs / 1000} s"
    }

    /** Tick label under the slider — just the number, the unit is in the heading. */
    fun tickLabel(delayMs: Long): String = when (delayMs) {
        0L -> "0"
        500L -> "0,5"
        else -> "${delayMs / 1000}"
    }
}
