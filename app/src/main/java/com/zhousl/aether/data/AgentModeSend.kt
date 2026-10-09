package com.zhousl.aether.data

internal data class AgentModeVisibleLine(
    val text: String,
    val top: Int,
)

/** Normalized top at which a line is treated as the composer rather than a chat bubble. */
internal const val AgentModeComposerBandTop = 880

internal data class AgentModeSendCheck(
    val confirmed: Boolean,
    val reason: String,
)

/**
 * A send is confirmed only when the typed message leaves the composer or shows up as a new bubble.
 * Anything else is uncertain: the caller must not report a miss that invites another tap.
 */
internal fun agentModeSendCheck(
    message: String,
    before: List<AgentModeVisibleLine>,
    after: List<AgentModeVisibleLine>,
): AgentModeSendCheck {
    val needle = message.trim()
    if (needle.isEmpty()) return AgentModeSendCheck(confirmed = false, reason = AgentModeReasonUncertain)
    fun composer(lines: List<AgentModeVisibleLine>) =
        lines.filter { it.top >= AgentModeComposerBandTop && it.text.contains(needle) }
    fun bubbles(lines: List<AgentModeVisibleLine>) =
        lines.filter { it.top < AgentModeComposerBandTop && it.text.contains(needle) }
    val composerCleared = composer(before).isNotEmpty() && composer(after).isEmpty()
    val bubbleAdded = bubbles(after).size > bubbles(before).size
    return when {
        composerCleared -> AgentModeSendCheck(confirmed = true, reason = "composer_cleared")
        bubbleAdded -> AgentModeSendCheck(confirmed = true, reason = "message_bubble")
        else -> AgentModeSendCheck(confirmed = false, reason = AgentModeReasonUncertain)
    }
}

internal data class AgentModeFocusPoint(
    val x: Int,
    val y: Int,
)

/** True when [message] is visible in the composer band, not merely somewhere in the chat history. */
internal fun agentModeComposerHasText(message: String, lines: List<AgentModeVisibleLine>): Boolean {
    val needle = message.trim()
    if (needle.isEmpty()) return false
    val composer = lines
        .filter { it.top >= AgentModeComposerBandTop }
        .joinToString(separator = "") { it.text }
    if (composer.contains(needle)) return true
    val compactNeedle = needle.filterNot(Char::isWhitespace)
    if (compactNeedle.length < 2) return false
    return composer.filterNot(Char::isWhitespace).contains(compactNeedle)
}

/**
 * A point in the composer, left of a send button. Falls back to the left side of the bottom bar
 * when OCR has no field, so the tap does not land on 发送.
 */
internal fun agentModeComposerFocusPoint(
    elements: List<AgentModeTextElement>,
    imageWidth: Int,
    imageHeight: Int,
): AgentModeFocusPoint {
    if (imageWidth <= 0 || imageHeight <= 0) return AgentModeFocusPoint(400, 960)
    val target = elements
        .filter { element ->
            element.granularity != AgentModeOcrGranularity.SYMBOL &&
                normalizeAgentModePixel(element.boundingBox.top, imageHeight) >= AgentModeComposerBandTop &&
                !agentModeSendLike(element.text, normalizedX = null, normalizedY = null) &&
                normalizeAgentModePixel(element.boundingBox.centerX(), imageWidth) < 750
        }
        .maxByOrNull { it.boundingBox.right - it.boundingBox.left }
        ?: return AgentModeFocusPoint(400, 960)
    return AgentModeFocusPoint(
        x = normalizeAgentModePixel(target.boundingBox.centerX(), imageWidth).coerceAtMost(700),
        y = normalizeAgentModePixel(target.boundingBox.centerY(), imageHeight),
    )
}

internal fun agentModeSendLike(query: String?, normalizedX: Int?, normalizedY: Int?): Boolean {
    val label = query?.trim()?.lowercase().orEmpty()
    if (label.isNotEmpty()) return label in AgentModeSendLabels
    return normalizedX != null && normalizedY != null && normalizedX >= 750 && normalizedY >= 900
}

/**
 * The text looks like one OCR line that glued the composer contents to the send label.
 * The user's own message is not pasted in that shape.
 */
internal fun agentModeInputLooksLikeMergedOcr(text: String): Boolean {
    val trimmed = text.trim()
    if (!trimmed.endsWith("发送")) return false
    val body = trimmed.removeSuffix("发送").trim().trimEnd('(', '（', 'G', ' ')
    if (body.length < 4 || body == trimmed) return false
    return body.contains('。') || body.contains('，') || body.contains(',') ||
        body.contains('(') || body.contains('（')
}

/**
 * After one send-like tap, every later send-like tap is refused until a screenshot runs.
 */
internal class AgentModeSendGate {
    private var armed = false

    fun shouldBlock(query: String?, normalizedX: Int?, normalizedY: Int?): Boolean =
        armed && agentModeSendLike(query, normalizedX, normalizedY)

    fun record() {
        armed = true
    }

    fun noteScreenshot() {
        armed = false
    }
}

private val AgentModeSendLabels = setOf("发送", "send", "傳送", "传送", "送出")

internal const val AgentModeUncertainSendMessage =
    "Uncertain whether this sent the message. Take one screenshot to check. Do not tap or type it again."

internal const val AgentModeSendRepeatMessage =
    "A send-like tap already ran. Take one screenshot to check. Do not tap or type again. Nothing was injected."
