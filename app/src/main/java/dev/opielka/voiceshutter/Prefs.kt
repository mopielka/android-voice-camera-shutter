package dev.opielka.voiceshutter

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "voice_shutter")

class Prefs(private val context: Context) {

    val enabled: Flow<Boolean> = context.dataStore.data.map { it[ENABLED] ?: false }

    val cameraPackageOverrides: Flow<Set<String>> =
        context.dataStore.data.map { it[CAMERA_PACKAGES] ?: emptySet() }

    val shutterDelayMs: Flow<Long> = context.dataStore.data.map {
        ShutterDelay.sanitise(it[SHUTTER_DELAY_MS] ?: ShutterDelay.DEFAULT_MS)
    }

    /** Raw, exactly as typed — parsing belongs to [KeywordList], not to storage. */
    val keywordsRaw: Flow<String> =
        context.dataStore.data.map { it[KEYWORDS] ?: KeywordList.DEFAULT }


    suspend fun setEnabled(value: Boolean) {
        context.dataStore.edit { it[ENABLED] = value }
    }

    suspend fun setCameraPackageOverrides(packages: Set<String>) {
        context.dataStore.edit { it[CAMERA_PACKAGES] = packages }
    }

    suspend fun setShutterDelayMs(delayMs: Long) {
        context.dataStore.edit { it[SHUTTER_DELAY_MS] = ShutterDelay.sanitise(delayMs) }
    }

    suspend fun setKeywords(raw: String) {
        context.dataStore.edit { it[KEYWORDS] = raw.take(KeywordList.MAX_LENGTH) }
    }


    fun shutterViewId(packageName: String): Flow<String?> =
        context.dataStore.data.map { it[shutterViewIdKey(packageName)] }

    /**
     * Caching the id that actually worked turns every later trigger into a single
     * lookup instead of a tree walk, and makes a camera-app update cost one slow
     * trigger rather than breaking the feature.
     */
    suspend fun rememberShutterViewId(packageName: String, viewId: String) {
        context.dataStore.edit { it[shutterViewIdKey(packageName)] = viewId }
    }

    suspend fun forgetShutterViewId(packageName: String) {
        context.dataStore.edit { it.remove(shutterViewIdKey(packageName)) }
    }

    private companion object {
        val ENABLED = booleanPreferencesKey("enabled")
        val CAMERA_PACKAGES = stringSetPreferencesKey("camera_packages")
        val KEYWORDS = stringPreferencesKey("keywords")
        val SHUTTER_DELAY_MS = longPreferencesKey("shutter_delay_ms")

        fun shutterViewIdKey(packageName: String) = stringPreferencesKey("shutter_id_$packageName")
    }
}
