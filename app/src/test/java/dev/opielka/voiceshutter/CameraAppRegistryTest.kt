package dev.opielka.voiceshutter

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CameraAppRegistryTest {

    private val registry = CameraAppRegistry(ApplicationProvider.getApplicationContext())

    @Test
    fun `user overrides replace detection entirely`() {
        val overrides = setOf("com.hihonor.camera")

        assertEquals(overrides, registry.effectiveCameraPackages(overrides))
    }

    @Test
    fun `falls back to detection when there are no overrides`() {
        assertEquals(registry.detectedCameraPackages(), registry.effectiveCameraPackages(emptySet()))
    }
}
