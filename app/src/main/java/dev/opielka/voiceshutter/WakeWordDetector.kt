package dev.opielka.voiceshutter

/**
 * Contract for the speech engine, so swapping Vosk for another engine (or adding a
 * user-facing choice) touches only the construction site in VoiceShutterService.
 */
interface WakeWordDetector {

    /** Begins listening. [onDetected] fires on an unspecified thread. */
    fun start(onDetected: () -> Unit)

    /** Stops listening and releases the microphone. Safe to call when not started. */
    fun stop()
}
