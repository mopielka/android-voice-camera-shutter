package dev.opielka.voiceshutter

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService
import kotlin.concurrent.thread
import kotlin.math.sqrt

/**
 * Wake-word detection on top of Vosk.
 *
 * Vosk is a general speech recogniser, but constraining its grammar to the handful of
 * phrases we care about turns it into a wake-word engine: it can only ever return one
 * of them or "[unk]", which is both far more accurate and far cheaper than open
 * recognition. Note that it accepts a grammar containing words its vocabulary does not
 * know — it simply never matches them — so a misspelled phrase fails silently.
 *
 * The audio loop is ours rather than Vosk's `SpeechService` for two reasons: the
 * microphone is then opened once per camera session instead of being torn down and
 * rebuilt after every shot, and the recogniser is only ever touched from the thread
 * that feeds it, which removes the races that come from Vosk delivering results on the
 * main thread. It also lets us log the signal level, without which a degraded stream
 * is indistinguishable from a broken engine.
 */
class VoskDetector(
    private val context: Context,
    private val keywords: List<String>,
) : WakeWordDetector {

    @Volatile private var running = false
    private var audioThread: Thread? = null

    override fun start(onDetected: () -> Unit) {
        if (running) return
        if (keywords.isEmpty()) {
            DiagnosticLog.log("Brak haseł — nasłuch nie rusza")
            return
        }
        running = true

        sharedModel?.let { model ->
            launchLoop(model, onDetected)
            return
        }

        StorageService.unpack(
            context,
            MODEL_ASSET,
            MODEL_TARGET,
            { unpacked ->
                sharedModel = unpacked
                if (running) launchLoop(unpacked, onDetected)
            },
            { error -> DiagnosticLog.log("Nie udało się rozpakować modelu", error) },
        )
    }

    override fun stop() {
        running = false
        audioThread?.join(STOP_TIMEOUT_MS)
        audioThread = null
        DiagnosticLog.log("Nasłuch zatrzymany")
    }

    private fun launchLoop(model: Model, onDetected: () -> Unit) {
        audioThread = thread(name = "voice-shutter-audio") { listen(model, onDetected) }
    }

    @SuppressLint("MissingPermission")
    private fun listen(model: Model, onDetected: () -> Unit) {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBuffer <= 0) {
            DiagnosticLog.log("AudioRecord: nieprawidłowy rozmiar bufora ($minBuffer)")
            return
        }

        val record = runCatching {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, CHANNEL, ENCODING, minBuffer * 4)
        }.getOrElse {
            DiagnosticLog.log("Nie udało się otworzyć mikrofonu", it)
            return
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            DiagnosticLog.log("Mikrofon niezainicjalizowany (state=${record.state})")
            record.release()
            return
        }

        val recognizer = runCatching { Recognizer(model, SAMPLE_RATE.toFloat(), grammar()) }
            .getOrElse {
                DiagnosticLog.log("Nie udało się utworzyć rozpoznawania", it)
                record.release()
                return
            }

        record.startRecording()
        DiagnosticLog.log(
            "Nasłuch uruchomiony, hasła: ${keywords.joinToString(", ")} " +
                "(bufor=$minBuffer, źródło=VOICE_RECOGNITION)",
        )

        val buffer = ShortArray(CHUNK_SAMPLES)
        var chunks = 0
        var lastSpoken = ""
        var deafUntil = 0L

        try {
            while (running) {
                val read = record.read(buffer, 0, buffer.size)
                if (read <= 0) {
                    DiagnosticLog.log("Odczyt mikrofonu zwrócił $read — przerywam")
                    break
                }

                chunks++
                if (chunks % LEVEL_EVERY == 0) {
                    DiagnosticLog.log("Poziom sygnału: ${rms(buffer, read)} (porcji: $chunks)")
                }

                // The tail of the phrase — and the camera's own shutter sound, which is
                // a problem for a trigger like "click" — keeps arriving after a hit and
                // fires again. Audio is still drained here, just not listened to.
                if (SystemClock.elapsedRealtime() < deafUntil) continue
                if (deafUntil != 0L) {
                    deafUntil = 0L
                    recognizer.reset()
                    lastSpoken = ""
                }

                val complete = recognizer.acceptWaveForm(buffer, read)
                val hypothesis = if (complete) recognizer.result else recognizer.partialResult
                val field = if (complete) "text" else "partial"
                val spoken = hypothesis.textOf(field)

                if (spoken.isNotBlank() && spoken != lastSpoken) {
                    lastSpoken = spoken
                    DiagnosticLog.log("Słyszę: \"$spoken\"")
                }

                if (spoken.matchesPhrase()) {
                    // Safe here: the recogniser is only ever used from this thread.
                    recognizer.reset()
                    lastSpoken = ""
                    deafUntil = SystemClock.elapsedRealtime() + DEAF_AFTER_HIT_MS
                    onDetected()
                }
            }
        } catch (error: Exception) {
            DiagnosticLog.log("Pętla nasłuchu przerwana", error)
        } finally {
            runCatching { record.stop() }
            record.release()
            recognizer.close()
        }
    }

    private fun grammar(): String =
        (keywords + "[unk]").joinToString(prefix = "[", postfix = "]") { "\"$it\"" }

    private fun String.matchesPhrase(): Boolean {
        // Padded so a phrase matches whole words only: "smile" must not fire on "smiled".
        val haystack = " ${lowercase()} "
        return keywords.any { haystack.contains(" $it ") }
    }

    private fun String?.textOf(field: String): String {
        if (this == null) return ""
        return runCatching { JSONObject(this).optString(field) }.getOrNull().orEmpty()
    }

    private fun rms(buffer: ShortArray, length: Int): Int {
        var sum = 0.0
        for (i in 0 until length) {
            val sample = buffer[i].toDouble()
            sum += sample * sample
        }
        return sqrt(sum / length).toInt()
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

        /** 100 ms of audio per pass — small enough to react quickly, large enough to be cheap. */
        const val CHUNK_SAMPLES = SAMPLE_RATE / 10
        const val LEVEL_EVERY = 300

        /**
         * Covers the tail of the phrase echoing back into the microphone. It also covers
         * the shutter sound on handsets where it is not muted, which matters for a
         * trigger like "click".
         */
        const val DEAF_AFTER_HIT_MS = 1_000L
        const val STOP_TIMEOUT_MS = 1_000L

        const val MODEL_ASSET = "model-en-us"
        const val MODEL_TARGET = "model"

        /** Outlives individual sessions: unpacking costs ~68 MB of I/O. */
        @Volatile
        var sharedModel: Model? = null
    }
}
