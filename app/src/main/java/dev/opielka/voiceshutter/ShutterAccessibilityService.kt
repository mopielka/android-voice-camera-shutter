package dev.opielka.voiceshutter

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Does two jobs that both need the same permission: it notices when a camera app comes
 * to the foreground (so the microphone only runs when it is useful), and it presses the
 * shutter on request.
 *
 * Injecting a real KeyEvent the way a Bluetooth remote does needs INJECT_EVENTS, which is
 * signature-level and unavailable to us, so we act on the camera's UI tree instead.
 */
class ShutterAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val locator = ShutterLocator()

    private lateinit var prefs: Prefs
    private lateinit var registry: CameraAppRegistry

    @Volatile private var enabled = false
    @Volatile private var cameraPackages: Set<String> = emptySet()
    @Volatile private var rememberedIds: Map<String, String> = emptyMap()
    @Volatile private var activeCameraPackage: String? = null
    @Volatile private var lastTriggerAt = 0L
    @Volatile private var shutterDelayMs = ShutterDelay.DEFAULT_MS
    @Volatile private var lastSeenPackage: String? = null
    @Volatile private var pausedForVideo = false
    @Volatile private var lastModeCheckAt = 0L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val stopListening = Runnable { stopNow() }
    private val pressShutterLater = Runnable { pressShutter() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        DiagnosticLog.init(applicationContext)
        DiagnosticLog.log("Usługa dostępności podłączona")
        prefs = Prefs(applicationContext)
        registry = CameraAppRegistry(applicationContext)
        instance = this

        scope.launch {
            prefs.enabled.collectLatest { value ->
                enabled = value
                DiagnosticLog.log("Nasłuch włączony: $value")
                if (!value) mainHandler.post { stopNow() }
            }
        }
        scope.launch {
            prefs.shutterDelayMs.collectLatest { shutterDelayMs = it }
        }
        // A window-state event only arrives on a change. Restarting the service while the
        // camera is already open would otherwise leave it unnoticed until the user
        // switched away and back.
        mainHandler.postDelayed({ adoptForegroundCamera() }, ADOPT_DELAY_MS)
        scope.launch {
            prefs.cameraPackageOverrides.collectLatest { overrides ->
                cameraPackages = registry.effectiveCameraPackages(overrides)
                DiagnosticLog.log("Pakiety aparatu: $cameraPackages")
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val packageName = event.packageName?.toString() ?: return
        if (packageName == this.packageName) return

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            if (packageName == activeCameraPackage) followCameraMode()
            return
        }
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val isCamera = packageName in cameraPackages
        if (isCamera && !enabled && packageName != lastSeenPackage) {
            DiagnosticLog.log("Aparat otwarty, ale nasłuch jest wyłączony")
        }
        lastSeenPackage = packageName

        if (enabled && isCamera) enterCamera(packageName) else leaveCamera()
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        mainHandler.removeCallbacks(stopListening)
        mainHandler.removeCallbacks(pressShutterLater)
        stopNow()
        instance = null
        scope.cancel()
        super.onDestroy()
    }

    private fun enterCamera(packageName: String) {
        mainHandler.removeCallbacks(stopListening)
        if (activeCameraPackage == packageName) return
        activeCameraPackage = packageName
        DiagnosticLog.log("Aparat na pierwszym planie: $packageName — startuję nasłuch")
        scope.launch {
            prefs.shutterViewId(packageName).collectLatest { viewId ->
                rememberedIds = viewId
                    ?.let { rememberedIds + (packageName to it) }
                    ?: rememberedIds - packageName
            }
        }
        // Checked before starting: the service comes up asynchronously, so a pause sent
        // straight afterwards would arrive before there was anything to pause, and the
        // microphone would be taken from a recording already in progress.
        pausedForVideo = CameraMode.of(rootInActiveWindow) == CameraShootingMode.VIDEO
        if (pausedForVideo) {
            DiagnosticLog.log("Aparat otwarty w trybie wideo — nie zajmuję mikrofonu")
        } else {
            VoiceShutterService.start(this)
        }
    }

    /**
     * Holding the microphone does not merely spoil a video's audio — the camera refuses
     * to start recording at all. So the microphone is handed back as soon as the user
     * switches to a video mode, before they reach for the record button, and taken again
     * when they return to stills.
     *
     * Content-changed events arrive constantly from a live camera preview, hence the
     * throttle: walking the window tree on each one would be wasteful.
     */
    private fun adoptForegroundCamera() {
        if (!enabled || activeCameraPackage != null) return
        val current = rootInActiveWindow?.packageName?.toString() ?: return
        if (current in cameraPackages) {
            DiagnosticLog.log("Aparat był już otwarty przy starcie usługi")
            enterCamera(current)
        }
    }

    private fun followCameraMode(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastModeCheckAt < MODE_CHECK_INTERVAL_MS) return
        lastModeCheckAt = now

        if (activeCameraPackage == null) return

        val verdict = CameraMode.verdict(rootInActiveWindow)
        when (verdict.mode) {
            CameraShootingMode.VIDEO -> if (!pausedForVideo) {
                pausedForVideo = true
                DiagnosticLog.log("Tryb wideo — zwalniam mikrofon (wg: ${verdict.evidence})")
                VoiceShutterService.pause()
            }

            CameraShootingMode.PHOTO -> if (pausedForVideo) {
                pausedForVideo = false
                DiagnosticLog.log("Powrót do zdjęć — wznawiam nasłuch (wg: ${verdict.evidence})")
                VoiceShutterService.resume(this)
            }

            // While recording, the mode tabs are gone and nothing names the mode. Acting
            // on that silence is what grabbed the microphone mid-recording and truncated
            // the file, so the current state simply stands.
            CameraShootingMode.UNKNOWN -> Unit
        }
    }

    /**
     * Deferred, because the camera does not leave the foreground cleanly: MagicOS flashes
     * its own windows (systemui, launcher, AOD) over it for a fraction of a second, and
     * tearing the recogniser down and back up on each flash is what made it stop
     * responding after a few shots.
     */
    private fun leaveCamera() {
        if (activeCameraPackage == null) return
        mainHandler.removeCallbacks(stopListening)
        mainHandler.postDelayed(stopListening, LINGER_MS)
    }

    private fun stopNow() {
        val packageName = activeCameraPackage ?: return

        // A foreign window on top does not mean the camera is gone: MagicOS flashes the
        // launcher, systemui and the search bar over it constantly. The camera's window
        // still exists in the window list, and that — not what happens to be frontmost —
        // is what decides whether the microphone is still wanted.
        if (isWindowPresent(packageName)) {
            mainHandler.postDelayed(stopListening, LINGER_MS)
            return
        }

        mainHandler.removeCallbacks(pressShutterLater)
        DiagnosticLog.log("Aparat zamknięty — zatrzymuję nasłuch")
        activeCameraPackage = null
        pausedForVideo = false
        VoiceShutterService.stop(this)
    }

    private fun isWindowPresent(packageName: String): Boolean {
        if (rootInActiveWindow?.packageName == packageName) return true
        return runCatching {
            windows.any { it.root?.packageName == packageName }
        }.getOrDefault(false)
    }

    /**
     * Debouncing lives here rather than in the caller so every trigger path — the wake
     * word, the debug broadcast, anything added later — is protected from firing a burst.
     *
     * Returns whether a shot was scheduled, not whether it succeeded: the press itself
     * happens after the configured delay.
     */
    fun triggerShutter(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - lastTriggerAt < DEBOUNCE_MS) {
            DiagnosticLog.log("Wyzwolenie pominięte (debounce, ${now - lastTriggerAt} ms od ostatniego)")
            return false
        }
        lastTriggerAt = now

        val delay = shutterDelayMs
        if (delay <= 0L) return pressShutter()

        DiagnosticLog.log("Zdjęcie za ${delay} ms")
        mainHandler.postDelayed(pressShutterLater, delay)
        return true
    }

    /**
     * Fires after the delay, by which point the camera may be gone — the user can close
     * it while the countdown runs, and pressing into whatever replaced it would be worse
     * than missing the shot.
     */
    private fun pressShutter(): Boolean {
        val packageName = activeCameraPackage ?: run {
            DiagnosticLog.log("Brak aparatu na pierwszym planie")
            return false
        }
        val root: AccessibilityNodeInfo = rootInActiveWindow ?: run {
            DiagnosticLog.log("Brak drzewa aktywnego okna")
            return false
        }

        return when (val target = locator.locate(root, rememberedIds[packageName])) {
            is ShutterTarget.Clickable -> {
                val clicked = target.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                DiagnosticLog.log(
                    "Kliknięcie spustu => $clicked " +
                        "(id=${target.viewId}, węzeł=${target.node.viewIdResourceName}, " +
                        "desc=${target.node.contentDescription})",
                )
                if (clicked) remember(packageName, target.viewId)
                clicked
            }

            is ShutterTarget.Coordinates -> {
                DiagnosticLog.log("Spust nieklikalny — dotknięcie ${target.x}x${target.y}")
                tap(target.x, target.y).also { if (it) remember(packageName, target.viewId) }
            }

            ShutterTarget.NotFound -> {
                DiagnosticLog.log("Nie znaleziono spustu w $packageName")
                prefs.forgetLater(packageName)
                false
            }
        }
    }

    private fun remember(packageName: String, viewId: String?) {
        if (viewId == null || rememberedIds[packageName] == viewId) return
        scope.launch { prefs.rememberShutterViewId(packageName, viewId) }
    }

    private fun Prefs.forgetLater(packageName: String) {
        scope.launch { forgetShutterViewId(packageName) }
    }

    private fun tap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, TAP_DURATION_MS))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    companion object {
        /**
         * Only wide enough to swallow the tail of one utterance echoing into the next
         * recognition session. Duplicate hits from a single phrase are already prevented
         * by rebuilding that session, and a longer window silently ate deliberate
         * repeats spoken about two seconds apart.
         */
        private const val DEBOUNCE_MS = 800L
        private const val LINGER_MS = 4_000L
        private const val MODE_CHECK_INTERVAL_MS = 700L
        private const val ADOPT_DELAY_MS = 1_500L
        private const val TAP_DURATION_MS = 60L

        @Volatile
        var instance: ShutterAccessibilityService? = null
            private set
    }
}
