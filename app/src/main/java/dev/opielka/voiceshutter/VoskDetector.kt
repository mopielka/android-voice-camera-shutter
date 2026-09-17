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
 * Vosk is a general speech recogniser, but constraining its grammar to the single word
 * we care about turns it into a wake-word engine: it can only ever return "smile" or
 * "unknown", which is both far more accurate and far cheaper than open recognition.
 */
class VoskDetector(
    private val context: Context,
    private val keyword: String = "smile",
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
        runCatching {
            val recognizer = Recognizer(model, SAMPLE_RATE, """["$keyword", "[unk]"]""")
            val listener = KeywordListener(keyword) {
                // Without this the partial hypothesis keeps containing the keyword and
                // fires again on every audio chunk until the debounce window closes.
                recognizer.reset()
                onDetected()
            }
            SpeechService(recognizer, SAMPLE_RATE).also { service ->
                speechService = service
                service.startListening(listener)
                DiagnosticLog.log("Nasłuch uruchomiony, słowo: $keyword")
            }
        }.onFailure { DiagnosticLog.log("Nie udało się uruchomić nasłuchu", it) }
    }

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

    private class KeywordListener(
        private val keyword: String,
        private val onDetected: () -> Unit,
    ) : RecognitionListener {

        override fun onPartialResult(hypothesis: String?) {
            if (hypothesis.containsKeyword("partial")) onDetected()
        }

        override fun onResult(hypothesis: String?) {
            if (hypothesis.containsKeyword("text")) onDetected()
        }

        override fun onFinalResult(hypothesis: String?) = Unit

        override fun onError(exception: Exception?) {
            DiagnosticLog.log("Błąd rozpoznawania", exception)
        }

        override fun onTimeout() = Unit

        private fun String?.containsKeyword(field: String): Boolean {
            if (this == null) return false
            val spoken = runCatching { JSONObject(this).optString(field) }.getOrNull().orEmpty()
            return spoken.split(' ').any { it.equals(keyword, ignoreCase = true) }
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
