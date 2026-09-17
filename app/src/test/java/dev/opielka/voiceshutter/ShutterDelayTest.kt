package dev.opielka.voiceshutter

import org.junit.Assert.assertEquals
import org.junit.Test

class ShutterDelayTest {

    @Test
    fun `the axis is time, so gaps are proportional not equal`() {
        assertEquals(0f, ShutterDelay.fractionOf(0L), TOLERANCE)
        assertEquals(1f / 6, ShutterDelay.fractionOf(500L), TOLERANCE)
        assertEquals(1f / 3, ShutterDelay.fractionOf(1_000L), TOLERANCE)
        assertEquals(2f / 3, ShutterDelay.fractionOf(2_000L), TOLERANCE)
        assertEquals(1f, ShutterDelay.fractionOf(3_000L), TOLERANCE)
    }

    @Test
    fun `a drag snaps to the nearest supported value`() {
        assertEquals(0L, ShutterDelay.fromSeconds(0.2f))
        assertEquals(500L, ShutterDelay.fromSeconds(0.4f))
        assertEquals(1_000L, ShutterDelay.fromSeconds(1.1f))
        assertEquals(2_000L, ShutterDelay.fromSeconds(1.6f))
        assertEquals(3_000L, ShutterDelay.fromSeconds(2.9f))
    }

    @Test
    fun `values outside the axis are clamped`() {
        assertEquals(0f, ShutterDelay.fractionOf(-100L), TOLERANCE)
        assertEquals(1f, ShutterDelay.fractionOf(99_000L), TOLERANCE)
        assertEquals(3_000L, ShutterDelay.fromSeconds(10f))
        assertEquals(0L, ShutterDelay.fromSeconds(-1f))
    }

    @Test
    fun `sanitise snaps an unsupported stored value`() {
        assertEquals(500L, ShutterDelay.sanitise(400L))
        assertEquals(1_000L, ShutterDelay.sanitise(1_100L))
        assertEquals(3_000L, ShutterDelay.sanitise(10_000L))
    }

    @Test
    fun `default is the recommended half second`() {
        assertEquals(500L, ShutterDelay.DEFAULT_MS)
        assertEquals("0,5 s (zalecane)", ShutterDelay.label(500L))
    }

    @Test
    fun `tick labels are bare numbers`() {
        assertEquals(listOf("0", "0,5", "1", "2", "3"), ShutterDelay.OPTIONS_MS.map(ShutterDelay::tickLabel))
    }

    private companion object {
        const val TOLERANCE = 0.0001f
    }
}
