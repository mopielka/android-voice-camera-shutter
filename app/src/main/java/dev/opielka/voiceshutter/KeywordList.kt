package dev.opielka.voiceshutter

/**
 * Parses the user's comma-separated trigger phrases.
 *
 * The raw text is what the user typed and what gets stored; [parse] turns it into the
 * phrases the recogniser is given. Characters are restricted to letters, spaces and
 * apostrophes — partly because the model only knows English words, and partly because
 * the phrases are interpolated into Vosk's JSON grammar, where a stray quote would
 * corrupt it.
 */
object KeywordList {

    const val MAX_LENGTH = 1000
    const val DEFAULT = "smile"

    private val ALLOWED = Regex("[a-z' ]+")

    fun parse(raw: String): List<String> =
        raw.take(MAX_LENGTH)
            .split(',')
            .map { it.trim().lowercase().replace(Regex("\\s+"), " ") }
            .filter { it.isNotEmpty() && ALLOWED.matches(it) }
            .distinct()

    /** Phrases the user typed that cannot be used, so the UI can say why nothing happens. */
    fun rejected(raw: String): List<String> =
        raw.take(MAX_LENGTH)
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() && !ALLOWED.matches(it.lowercase().replace(Regex("\\s+"), " ")) }
            .distinct()

    fun parseOrDefault(raw: String): List<String> =
        parse(raw).ifEmpty { listOf(DEFAULT) }

    fun isTooLong(raw: String): Boolean = raw.length > MAX_LENGTH
}
