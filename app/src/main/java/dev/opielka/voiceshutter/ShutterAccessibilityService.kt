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
    @Volatile private var lastSeenPackage: String? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val stopListening = Runnable { stopNow() }

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
            prefs.cameraPackageOverrides.collectLatest { overrides ->
                cameraPackages = registry.effectiveCameraPackages(overrides)
                DiagnosticLog.log("Pakiety aparatu: $cameraPackages")
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val packageName = event.packageName?.toString() ?: return
        if (packageName == this.packageName) return

        if (packageName != lastSeenPackage) {
            lastSeenPackage = packageName
            DiagnosticLog.log(
                "Okno: $packageName (enabled=$enabled, aparat=${packageName in cameraPackages})",
            )
        }

        if (enabled && packageName in cameraPackages) enterCamera(packageName) else leaveCamera()
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        mainHandler.removeCallbacks(stopListening)
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
        VoiceShutterService.start(this)
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
        if (activeCameraPackage == null) return
        DiagnosticLog.log("Aparat zamknięty — zatrzymuję nasłuch")
        activeCameraPackage = null
        VoiceShutterService.stop(this)
    }

    /**
     * Debouncing lives here rather than in the caller so every trigger path — the wake
     * word, the debug broadcast, anything added later — is protected from firing a burst.
     */
    fun triggerShutter(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - lastTriggerAt < DEBOUNCE_MS) {
            DiagnosticLog.log("Wyzwolenie pominięte (debounce)")
            return false
        }
        lastTriggerAt = now

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
        private const val DEBOUNCE_MS = 2_000L
        private const val LINGER_MS = 4_000L
        private const val TAP_DURATION_MS = 60L

        @Volatile
        var instance: ShutterAccessibilityService? = null
            private set
    }
}
