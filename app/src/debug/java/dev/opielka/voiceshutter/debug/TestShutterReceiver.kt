package dev.opielka.voiceshutter.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.opielka.voiceshutter.DiagnosticLog
import dev.opielka.voiceshutter.ShutterAccessibilityService

/**
 * Lets the accessibility path be verified from a terminal, without involving the
 * microphone, so a failure points at one layer rather than two:
 *
 *   adb shell am broadcast -a dev.opielka.voiceshutter.TEST_SHUTTER
 */
class TestShutterReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        context?.let(DiagnosticLog::init)
        val service = ShutterAccessibilityService.instance
        if (service == null) {
            DiagnosticLog.log("TEST_SHUTTER: instancja usługi dostępności jest null")
            return
        }
        DiagnosticLog.log("TEST_SHUTTER => ${service.triggerShutter()}")
    }
}
