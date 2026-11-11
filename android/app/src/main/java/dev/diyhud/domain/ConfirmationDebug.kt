package dev.diyhud.domain

enum class ConfirmationReason(val text: String) {
    NEW_CANDIDATE("New candidate"), MATCHED("Waiting for more hits"),
    BOX_MISMATCH("No box matched the IoU threshold"), WINDOW_EXPIRED("Old hits left the time window"),
    NOT_SEEN("Not detected in this frame"), BELOW_CONFIDENCE("Below confirmation confidence"),
    CONFIRMED("Confirmed now"), ALREADY_CONFIRMED("Already confirmed; waiting for absence to rearm"),
}

data class ConfirmationProgress(
    val trackId: Long?, val detection: Detection, val hits: Int, val required: Int,
    val lastSeenMs: Long, val reason: ConfirmationReason, val matchedIou: Double? = null,
)

/** Latest observation only. Diagnostic rows never become reducer inputs. */
data class ConfirmationDebug(
    val captureMs: Long, val windowMs: Long, val confidence: Double, val associationIou: Double,
    val rows: List<ConfirmationProgress> = emptyList(), val notes: List<String> = emptyList(),
)
