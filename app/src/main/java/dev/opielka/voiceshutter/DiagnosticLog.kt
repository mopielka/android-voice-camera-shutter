package dev.opielka.voiceshutter

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes a ring-buffered log to the app's files directory, readable with
 * `adb shell run-as dev.opielka.voiceshutter cat files/voice-shutter.log`.
 *
 * Logcat is unusable on Honor's MagicOS — even `log -t TAG` from a shell produces
 * nothing — so a file is the only way to see what the service is doing on-device.
 */
object DiagnosticLog {

    private const val TAG = "VoiceShutter"
    private const val FILE_NAME = "voice-shutter.log"
    private const val MAX_BYTES = 64 * 1024

    private val timestamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var file: File? = null

    fun init(context: Context) {
        file = File(context.filesDir, FILE_NAME)
    }

    fun log(message: String, error: Throwable? = null) {
        Log.i(TAG, message, error)

        val target = file ?: return
        val line = buildString {
            append(timestamp.format(Date()))
            append("  ")
            append(message)
            error?.let { append(" | ").append(it.stackTraceToString().lineSequence().take(4).joinToString(" / ")) }
            append('\n')
        }

        runCatching {
            synchronized(this) {
                if (target.length() > MAX_BYTES) target.writeText("")
                target.appendText(line)
            }
        }
    }
}
