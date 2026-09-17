package dev.opielka.voiceshutter

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.MediaStore

/**
 * Resolves which packages count as "the camera". Discovered from the system rather
 * than hardcoded, so the app works on any handset, not just the Honor it was built for.
 */
class CameraAppRegistry(private val context: Context) {

    fun detectedCameraPackages(): Set<String> {
        val pm = context.packageManager
        val intents = listOf(
            Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA),
            Intent(MediaStore.ACTION_IMAGE_CAPTURE),
        )
        return intents
            .flatMap { pm.queryIntentActivities(it, PackageManager.MATCH_DEFAULT_ONLY) }
            .map { it.activityInfo.packageName }
            .toSet()
    }

    /**
     * User overrides win outright: on some ROMs a gallery or scanner app also answers
     * the camera intent, and the user needs a way to narrow it down.
     */
    fun effectiveCameraPackages(userOverrides: Set<String>): Set<String> =
        userOverrides.ifEmpty { detectedCameraPackages() }
}
