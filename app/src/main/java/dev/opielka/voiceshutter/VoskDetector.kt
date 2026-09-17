package dev.opielka.voiceshutter

import android.content.Context
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService

/**
 * Wake-word detection on top of Vosk.
 *
 * Vosk is a general speech recogniser, but constraining its grammar to the handful of
 * phrases we care about turns it into a wake-word engine: it can only ever return one
 * of them or "unknown", which is both far more accurate and far cheaper than open
 * recognition.
 *
 * Note that Vosk accepts a grammar containing words its vocabulary does not know — it
 * simply never matches them — so a misspelled phrase fails silently rather than being
 * reportable here.
 */
class VoskDetector(
    private val context: Context,
    private val keywords: List<String>,
) : WakeWordDetector {

    private var speechService: SpeechService? = null

    override fun start(onDetected: () -> Unit) {
        if (speechService != null) return

        sharedModel?.let {
            beginListening(it, onDetected)
            return
        }

        StorageService.unpack(
            context,
            MODEL_ASSET,
            MODEL_TARGET,
            { unpacked ->
                sharedModel = unpacked
                beginListening(unpacked, onDetected)
            },
            { error -> DiagnosticLog.log("Nie udało się rozpakować modelu", error) },
        )
    }

    private fun beginListening(model: Model, onDetected: () -> Unit) {
        if (keywords.isEmpty()) {
            DiagnosticLog.log("Brak haseł — nasłuch nie rusza")
            return
        }

        runCatching {
            val recognizer = Recognizer(model, SAMPLE_RATE, grammarFor(keywords))
            val listener = PhraseListener(keywords) {
                // Without this the partial hypothesis keeps containing the phrase and
                // fires again on every audio chunk until the debounce window closes.
                recognizer.reset()
                onDetected()
            }
            SpeechService(recognizer, SAMPLE_RATE).also { service ->
                speechService = service
                service.startListening(listener)
                DiagnosticLog.log("Nasłuch uruchomiony, hasła: ${keywords.joinToString(", ")}")
            }
        }.onFailure { DiagnosticLog.log("Nie udało się uruchomić nasłuchu", it) }
    }

    private fun grammarFor(phrases: List<String>): String =
        (phrases + "[unk]").joinToString(prefix = "[", postfix = "]") { "\"$it\"" }

    override fun stop() {
        speechService?.apply {
            stop()
            shutdown()
        }
        speechService = null
        // The model is deliberately not closed: unpacking it costs ~68 MB of I/O, and the
        // camera flips in and out of the foreground often enough that reloading it each
        // time is what makes the engine stop responding.
        DiagnosticLog.log("Nasłuch zatrzymany")
    }

    private class PhraseListener(
        private val phrases: List<String>,
        private val onDetected: () -> Unit,
    ) : RecognitionListener {

        override fun onPartialResult(hypothesis: String?) {
            if (hypothesis.containsPhrase("partial")) onDetected()
        }

        override fun onResult(hypothesis: String?) {
            if (hypothesis.containsPhrase("text")) onDetected()
        }

        override fun onFinalResult(hypothesis: String?) = Unit

        override fun onError(exception: Exception?) {
            DiagnosticLog.log("Błąd rozpoznawania", exception)
        }

        override fun onTimeout() = Unit

        private fun String?.containsPhrase(field: String): Boolean {
            if (this == null) return false
            val spoken = runCatching { JSONObject(this).optString(field) }.getOrNull().orEmpty()
            if (spoken.isBlank()) return false
            // Padded so a phrase matches whole words only: "smile" must not fire on "smiled".
            val haystack = " ${spoken.lowercase()} "
            return phrases.any { haystack.contains(" $it ") }
        }
    }

    private companion object {
        const val SAMPLE_RATE = 16_000f
        const val MODEL_ASSET = "model-en-us"
        const val MODEL_TARGET = "model"

        /** Outlives individual listening sessions; see the note in [stop]. */
        @Volatile
        var sharedModel: Model? = null
    }
}
