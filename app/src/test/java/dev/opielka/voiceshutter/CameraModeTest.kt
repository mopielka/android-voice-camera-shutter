package dev.opielka.voiceshutter

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraModeTest {

    @Test
    fun `selected video tab on a polish honor is video`() {
        assertEquals(CameraShootingMode.VIDEO, CameraMode.describe("WybranoWideo"))
    }

    @Test
    fun `a shutter promising to record is video`() {
        assertEquals(CameraShootingMode.VIDEO, CameraMode.describe("Nagraj wideo"))
    }

    @Test
    fun `a shutter offering to stop recording is video`() {
        // Seen mid-recording, when the mode tabs are gone.
        assertEquals(CameraShootingMode.VIDEO, CameraMode.describe("Zatrzymaj nagrywanie"))
        assertEquals(CameraShootingMode.VIDEO, CameraMode.describe("Stop recording"))
    }

    @Test
    fun `english video wording is video`() {
        assertEquals(CameraShootingMode.VIDEO, CameraMode.describe("Record video"))
        assertEquals(CameraShootingMode.VIDEO, CameraMode.describe("Selected Video"))
    }

    @Test
    fun `an inactive video tab is not video`() {
        // Sits next to the photo tab while shooting stills; matching it would switch the
        // trigger off in exactly the mode it exists for.
        assertEquals(CameraShootingMode.UNKNOWN, CameraMode.describe("Wideo"))
        assertEquals(CameraShootingMode.UNKNOWN, CameraMode.describe("Video"))
    }

    @Test
    fun `the selected photo tab is photo`() {
        assertEquals(CameraShootingMode.PHOTO, CameraMode.describe("WybranoZdjęcie"))
        assertEquals(CameraShootingMode.PHOTO, CameraMode.describe("Selected photo"))
    }

    @Test
    fun `the shutter alone does not prove photo mode`() {
        // Honor offers a still-capture button while recording video, so treating this as
        // photo mode took the microphone back mid-recording and truncated the file.
        assertEquals(CameraShootingMode.UNKNOWN, CameraMode.describe("Dotknij, aby zrobić zdjęcie"))
        assertEquals(CameraShootingMode.UNKNOWN, CameraMode.describe("Take a photo"))
    }

    @Test
    fun `unrelated camera chrome is unknown`() {
        assertEquals(CameraShootingMode.UNKNOWN, CameraMode.describe("Najnowsze zdjęcie"))
        assertEquals(CameraShootingMode.UNKNOWN, CameraMode.describe("Lampa błyskowa wyłączona"))
        assertEquals(CameraShootingMode.UNKNOWN, CameraMode.describe("Ustawienia"))
        assertEquals(CameraShootingMode.UNKNOWN, CameraMode.describe(null))
        assertEquals(CameraShootingMode.UNKNOWN, CameraMode.describe(""))
    }
}
