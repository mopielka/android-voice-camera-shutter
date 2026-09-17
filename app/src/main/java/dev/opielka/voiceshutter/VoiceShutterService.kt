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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Owns the microphone for as long as a camera app is in the foreground.
 *
 * Started and stopped by [ShutterAccessibilityService] rather than running all day,
 * so the microphone is only live when it can actually be useful.
 */
class VoiceShutterService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val prefs by lazy { Prefs(applicationContext) }
    private var detector: WakeWordDetector? = null
    private var listening = false

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

        if (!listening) {
            listening = true
            scope.launch { followKeywords() }
        }

        return START_NOT_STICKY
    }

    /** Restarts the engine when the user edits the phrase list, without bouncing the service. */
    private suspend fun followKeywords() {
        prefs.keywordsRaw
            .map(KeywordList::parseOrDefault)
            .distinctUntilChanged()
            .collectLatest { phrases ->
                detector?.stop()
                detector = VoskDetector(applicationContext, phrases).also { engine ->
                    engine.start { ShutterAccessibilityService.instance?.triggerShutter() }
                }
            }
    }

    override fun onDestroy() {
        scope.cancel()
        detector?.stop()
        detector = null
        listening = false
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
