package dev.opielka.voiceshutter

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ShutterLocatorTest {

    private val locator = ShutterLocator()

    @Test
    fun `uses remembered view id when it still resolves`() {
        val shutter = node(viewId = SHUTTER_ID, clickable = true)
        val root = node(children = listOf(node()))
        every { root.findAccessibilityNodeInfosByViewId(SHUTTER_ID) } returns listOf(shutter)

        val target = locator.locate(root, SHUTTER_ID)

        assertTrue(target is ShutterTarget.Clickable)
        assertSame(shutter, (target as ShutterTarget.Clickable).node)
    }

    @Test
    fun `falls back to content description when remembered id is gone`() {
        val shutter = node(viewId = SHUTTER_ID, description = "Shutter", clickable = true)
        val root = node(children = listOf(node(description = "Flash"), shutter))
        every { root.findAccessibilityNodeInfosByViewId(any()) } returns emptyList()

        val target = locator.locate(root, "stale:id/gone")

        assertEquals(SHUTTER_ID, (target as ShutterTarget.Clickable).viewId)
    }

    @Test
    fun `matches a polish camera ui`() {
        val shutter = node(viewId = "com.hihonor.camera:id/btn", description = "Zrób zdjęcie", clickable = true)
        val root = node(children = listOf(shutter))

        val target = locator.locate(root, null)

        assertTrue(target is ShutterTarget.Clickable)
    }

    @Test
    fun `prefers the shutter over the gallery thumbnail on a real Honor tree`() {
        // Ordering mirrors the real dump: BFS reaches the thumbnail before the shutter.
        val motionPhoto = node(
            viewId = "com.hihonor.camera:id/feature_best_moment",
            description = "Ruchome zdjęcie wyłączone",
            clickable = true,
        )
        val thumbnail = node(
            viewId = "com.hihonor.camera:id/thumbnail_background_view",
            description = "Najnowsze zdjęcie",
            clickable = true,
        )
        val shutter = node(
            viewId = SHUTTER_ID,
            description = "Dotknij, aby zrobić zdjęcie",
            clickable = true,
        )
        val root = node(children = listOf(motionPhoto, thumbnail, shutter))

        val target = locator.locate(root, null)

        assertEquals(SHUTTER_ID, (target as ShutterTarget.Clickable).viewId)
    }

    @Test
    fun `climbs to a clickable ancestor when the matched node is not clickable`() {
        val parent = node(clickable = true)
        val shutter = node(description = "shutter", clickable = false, parent = parent)
        val root = node(children = listOf(shutter))

        val target = locator.locate(root, null)

        assertSame(parent, (target as ShutterTarget.Clickable).node)
    }

    @Test
    fun `falls back to centre coordinates when nothing is clickable`() {
        val shutter = node(
            description = "shutter",
            clickable = false,
            bounds = Rect(100, 200, 300, 400),
        )
        val root = node(children = listOf(shutter))

        val target = locator.locate(root, null)

        assertEquals(ShutterTarget.Coordinates(200f, 300f, null), target)
    }

    @Test
    fun `reports not found for an unrecognisable tree`() {
        val root = node(children = listOf(node(description = "Flash"), node(description = "Gallery")))

        assertEquals(ShutterTarget.NotFound, locator.locate(root, null))
    }

    @Test
    fun `reports not found for a null root`() {
        assertEquals(ShutterTarget.NotFound, locator.locate(null, null))
    }

    private fun node(
        viewId: String? = null,
        description: String? = null,
        clickable: Boolean = false,
        bounds: Rect = Rect(0, 0, 10, 10),
        parent: AccessibilityNodeInfo? = null,
        children: List<AccessibilityNodeInfo> = emptyList(),
    ): AccessibilityNodeInfo = mockk(relaxed = true) {
        every { viewIdResourceName } returns viewId
        every { contentDescription } returns description
        every { isClickable } returns clickable
        every { this@mockk.parent } returns parent
        every { childCount } returns children.size
        children.forEachIndexed { index, child -> every { getChild(index) } returns child }
        every { findAccessibilityNodeInfosByViewId(any()) } returns emptyList()
        every { getBoundsInScreen(any()) } answers { firstArg<Rect>().set(bounds) }
    }

    private companion object {
        const val SHUTTER_ID = "com.hihonor.camera:id/shutter_button"
    }
}
