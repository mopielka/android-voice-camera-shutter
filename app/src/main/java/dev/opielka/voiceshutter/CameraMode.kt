package dev.opielka.voiceshutter

import android.view.accessibility.AccessibilityNodeInfo

enum class CameraShootingMode { PHOTO, VIDEO, UNKNOWN }

/**
 * Works out whether the camera app is taking stills or shooting video.
 *
 * This matters because holding the microphone does not merely spoil a video's audio —
 * the camera refuses to start recording at all. The microphone therefore has to be given
 * back when the user switches to a video mode, before they reach the record button.
 *
 * [UNKNOWN] is a real answer, not a failure: while recording is in progress the mode
 * tabs disappear and nothing in the tree names the mode. Treating that as "back to
 * photos" made the app grab the microphone mid-recording and truncate the file, so an
 * unreadable tree means "keep doing what you were doing".
 *
 * Detection goes through accessibility descriptions because this ROM does not expose the
 * camera's view ids to accessibility. Those descriptions are localised, hence matching
 * both Polish and English wording.
 */
object CameraMode {

    private val VIDEO_WORD = Regex("wideo|video|film", RegexOption.IGNORE_CASE)
    private val SELECTED_OR_RECORD = Regex("nagr|record|wybrano|selected", RegexOption.IGNORE_CASE)
    private val RECORDING_NOW = Regex(
        "zatrzymaj nagr|zakończ nagr|wstrzymaj nagr|stop record|pause record",
        RegexOption.IGNORE_CASE,
    )
    /**
     * Only the mode tab counts, never the shutter. Honor lets you take stills *during*
     * video recording, so a "take a photo" button is present mid-recording too — reading
     * it as photo mode made the app snatch the microphone back and truncate the file.
     */
    private val PHOTO_TAB = Regex("wybranozdj|selected\\s*photo", RegexOption.IGNORE_CASE)

    data class Verdict(val mode: CameraShootingMode, val evidence: String?)

    fun of(root: AccessibilityNodeInfo?): CameraShootingMode = verdict(root).mode

    fun verdict(root: AccessibilityNodeInfo?): Verdict {
        if (root == null) return Verdict(CameraShootingMode.UNKNOWN, null)

        var photoEvidence: String? = null
        val queue = ArrayDeque(listOf(root))
        var visited = 0

        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val node = queue.removeFirst()
            visited++

            val description = node.contentDescription?.toString()
            when (describe(description)) {
                // Video wins outright: guessing wrong here breaks recording entirely,
                // while guessing wrong the other way only costs a missed voice trigger.
                CameraShootingMode.VIDEO -> return Verdict(CameraShootingMode.VIDEO, description)
                CameraShootingMode.PHOTO -> photoEvidence = description
                CameraShootingMode.UNKNOWN -> Unit
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let(queue::addLast)
            }
        }

        return photoEvidence
            ?.let { Verdict(CameraShootingMode.PHOTO, it) }
            ?: Verdict(CameraShootingMode.UNKNOWN, null)
    }

    fun describe(description: String?): CameraShootingMode {
        if (description.isNullOrBlank()) return CameraShootingMode.UNKNOWN

        if (RECORDING_NOW.containsMatchIn(description)) return CameraShootingMode.VIDEO

        // Both halves required: a bare "Wideo" is the inactive tab sitting beside the
        // photo tab, and matching it would disable the trigger in photo mode too.
        if (VIDEO_WORD.containsMatchIn(description) && SELECTED_OR_RECORD.containsMatchIn(description)) {
            return CameraShootingMode.VIDEO
        }

        if (PHOTO_TAB.containsMatchIn(description)) return CameraShootingMode.PHOTO

        return CameraShootingMode.UNKNOWN
    }

    private const val MAX_NODES = 400
}
