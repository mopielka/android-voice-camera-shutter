package dev.opielka.voiceshutter

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

/**
 * Owns the microphone for as long as a camera app is in the foreground.
 *
 * Started and stopped by [ShutterAccessibilityService] rather than running all day,
 * so the microphone is only live when it can actually be useful.
 */
class VoiceShutterService : Service() {

    private var detector: WakeWordDetector? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        DiagnosticLog.init(applicationContext)
        DiagnosticLog.log("VoiceShutterService utworzony")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )

        if (detector == null) {
            detector = VoskDetector(applicationContext).also { engine ->
                engine.start { ShutterAccessibilityService.instance?.triggerShutter() }
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        detector?.stop()
        detector = null
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, VoiceShutterService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setSmallIcon(R.drawable.ic_tile)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    null,
                    getString(R.string.notification_stop),
                    stopPending,
                ).build(),
            )
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "voice_shutter_listening"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "dev.opielka.voiceshutter.STOP"

        /**
         * Starting a foreground service from the background is only permitted because the
         * app is exempt from battery optimisations. Without that exemption this throws,
         * and the log line is the first thing to check when auto-start stops working.
         */
        fun start(context: Context) {
            val intent = Intent(context, VoiceShutterService::class.java)
            runCatching { context.startForegroundService(intent) }
                .onFailure {
                    DiagnosticLog.log("Nie udało się wystartować nasłuchu — sprawdź wyjątek od optymalizacji baterii", it)
                }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, VoiceShutterService::class.java))
        }
    }
}
