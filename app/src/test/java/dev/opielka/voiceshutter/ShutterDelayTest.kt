package dev.opielka.voiceshutter

import org.junit.Assert.assertEquals
import org.junit.Test

class ShutterDelayTest {

    @Test
    fun `slider positions map to the uneven steps`() {
        assertEquals(listOf(0L, 500L, 1_000L, 2_000L, 3_000L), ShutterDelay.OPTIONS_MS)
        assertEquals(0L, ShutterDelay.atIndex(0))
        assertEquals(500L, ShutterDelay.atIndex(1))
        assertEquals(3_000L, ShutterDelay.atIndex(4))
    }

    @Test
    fun `index round-trips`() {
        ShutterDelay.OPTIONS_MS.forEach { delay ->
            assertEquals(delay, ShutterDelay.atIndex(ShutterDelay.indexOf(delay)))
        }
    }

    @Test
    fun `out of range positions are clamped rather than crashing`() {
        assertEquals(0L, ShutterDelay.atIndex(-5))
        assertEquals(3_000L, ShutterDelay.atIndex(99))
    }

    @Test
    fun `an unknown stored value falls back to the default`() {
        assertEquals(ShutterDelay.indexOf(ShutterDelay.DEFAULT_MS), ShutterDelay.indexOf(1_234L))
    }

    @Test
    fun `sanitise snaps to the nearest supported value`() {
        assertEquals(500L, ShutterDelay.sanitise(400L))
        assertEquals(1_000L, ShutterDelay.sanitise(1_100L))
        assertEquals(3_000L, ShutterDelay.sanitise(10_000L))
        assertEquals(0L, ShutterDelay.sanitise(-1L))
    }

    @Test
    fun `default is the recommended half second`() {
        assertEquals(500L, ShutterDelay.DEFAULT_MS)
        assertEquals("0,5 s (zalecane)", ShutterDelay.label(500L))
    }

    @Test
    fun `slider exposes one stop per option`() {
        assertEquals(ShutterDelay.OPTIONS_MS.size, ShutterDelay.steps + 2)
    }
}
