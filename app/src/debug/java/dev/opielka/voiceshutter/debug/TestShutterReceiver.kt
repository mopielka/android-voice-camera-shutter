package dev.opielka.voiceshutter.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.opielka.voiceshutter.ShutterAccessibilityService

/**
 * Lets the accessibility path be verified from a terminal, without involving the
 * microphone, so a failure points at one layer rather than two:
 *
 *   adb shell am broadcast -a dev.opielka.voiceshutter.TEST_SHUTTER
 */
class TestShutterReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        val service = ShutterAccessibilityService.instance
        if (service == null) {
            Log.w("VoiceShutter", "Usługa dostępności nie działa")
            return
        }
        Log.i("VoiceShutter", "Wyzwolenie testowe => ${service.triggerShutter()}")
    }
}
