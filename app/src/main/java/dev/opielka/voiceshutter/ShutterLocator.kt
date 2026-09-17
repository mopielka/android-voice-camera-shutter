package dev.opielka.voiceshutter

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

sealed interface ShutterTarget {
    /** The node itself (or a clickable ancestor) accepts ACTION_CLICK. */
    data class Clickable(val node: AccessibilityNodeInfo, val viewId: String?) : ShutterTarget

    /** Nothing clickable was found, so the caller must fall back to a tap gesture. */
    data class Coordinates(val x: Float, val y: Float, val viewId: String?) : ShutterTarget

    data object NotFound : ShutterTarget
}

/**
 * Finds the shutter button in a camera app's window.
 *
 * Tries the id remembered from a previous success first, because that is both the
 * fastest path and the one that survives a localised UI. Only when that misses does
 * it walk the tree looking for a button that describes itself as a shutter.
 */
class ShutterLocator(
    private val strongPattern: Regex = STRONG_PATTERN,
    private val weakPattern: Regex = WEAK_PATTERN,
) {

    fun locate(root: AccessibilityNodeInfo?, rememberedViewId: String?): ShutterTarget {
        if (root == null) return ShutterTarget.NotFound

        rememberedViewId
            ?.let { root.findAccessibilityNodeInfosByViewId(it).firstOrNull() }
            ?.let { return it.toTarget(rememberedViewId) }

        val match = findByPattern(root, strongPattern) ?: findByPattern(root, weakPattern)
        return match?.let { it.toTarget(it.viewIdResourceName) } ?: ShutterTarget.NotFound
    }

    private fun findByPattern(root: AccessibilityNodeInfo, pattern: Regex): AccessibilityNodeInfo? {
        val queue = ArrayDeque(listOf(root))
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val haystack = "${node.contentDescription ?: ""} ${node.viewIdResourceName ?: ""}"
            if (pattern.containsMatchIn(haystack)) return node
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let(queue::addLast)
            }
        }
        return null
    }

    private fun AccessibilityNodeInfo.toTarget(viewId: String?): ShutterTarget {
        clickableSelfOrAncestor()?.let { return ShutterTarget.Clickable(it, viewId) }

        val bounds = Rect().also { getBoundsInScreen(it) }
        if (bounds.isEmpty) return ShutterTarget.NotFound
        return ShutterTarget.Coordinates(bounds.exactCenterX(), bounds.exactCenterY(), viewId)
    }

    private fun AccessibilityNodeInfo.clickableSelfOrAncestor(): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = this
        var depth = 0
        while (current != null && depth < MAX_ANCESTOR_DEPTH) {
            if (current.isClickable) return current
            current = current.parent
            depth++
        }
        return null
    }

    private companion object {
        const val MAX_ANCESTOR_DEPTH = 5

        /**
         * Unambiguous markers, searched first. A Honor camera exposes the shutter as
         * `…:id/shutter_button`, which lands here.
         */
        val STRONG_PATTERN = Regex("shutter|capture|migawk", RegexOption.IGNORE_CASE)

        /**
         * Localised wording, searched only if nothing strong matched. Deliberately
         * requires a verb: a bare "zdjęcie" would also match the gallery thumbnail
         * ("Najnowsze zdjęcie") and the motion-photo toggle sitting in the same tree.
         */
        val WEAK_PATTERN = Regex(
            "(zrób|zrobić|zrob)\\s+zdj|take\\s+(a\\s+)?(photo|picture)",
            RegexOption.IGNORE_CASE,
        )
    }
}
