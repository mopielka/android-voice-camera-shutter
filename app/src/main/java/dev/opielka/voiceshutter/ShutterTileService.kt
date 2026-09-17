package dev.opielka.voiceshutter

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Master on/off, reachable without opening the app. */
class ShutterTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val prefs by lazy { Prefs(applicationContext) }

    override fun onStartListening() {
        super.onStartListening()
        scope.launch { render(prefs.enabled.first()) }
    }

    override fun onClick() {
        super.onClick()
        scope.launch {
            val next = !prefs.enabled.first()
            prefs.setEnabled(next)
            render(next)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun render(enabled: Boolean) = withContext(Dispatchers.Main) {
        qsTile?.apply {
            state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            updateTile()
        }
    }
}
