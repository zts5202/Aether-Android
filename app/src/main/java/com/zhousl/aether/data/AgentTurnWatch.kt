package com.zhousl.aether.data

/** No tokens and no running tool for this long ends the turn instead of leaving it on Thinking. */
internal const val AgentTurnSilenceTimeoutMillis = 60_000L

/** After a final assistant message, wait briefly for the agent loop to settle before aborting it. */
internal const val AgentTurnSettleGraceMillis = 2_000L

/** If aborting the session does not unblock the bridge, close the turn in the UI anyway. */
internal const val AgentTurnAbortGraceMillis = 5_000L

internal const val AgentTurnSilenceMessage =
    "Stopped: no stream activity and no tool in progress for 60 seconds. " +
        "The text above is everything that arrived. The run has ended."

internal fun agentTurnStopReasonIsTerminal(stopReason: String): Boolean {
    val normalized = stopReason.trim().lowercase()
    return normalized in setOf(
        "stop",
        "end_turn",
        "end",
        "error",
        "aborted",
        "length",
        "max_tokens",
    )
}

/**
 * A generated chat title should be a short label. A model refusal is not a title, and it was
 * showing up as the only visible "answer" in the running-task notification.
 */
internal fun isUsableGeneratedSessionTitle(title: String): Boolean {
    val trimmed = title.trim()
    if (trimmed.length < 2) return false
    val lower = trimmed.lowercase()
    if (lower.startsWith("i can't") || lower.startsWith("i cannot") || lower.startsWith("sorry")) {
        return false
    }
    if (
        trimmed.startsWith("你好") ||
        trimmed.contains("我无法") ||
        trimmed.contains("我不能") ||
        trimmed.contains("无法帮")
    ) {
        return false
    }
    return true
}

/**
 * Text from assistant_done is the full message. Return only the part that is not already on screen,
 * or null when the chat already shows it.
 */
internal fun mergeAssistantDoneText(
    existingTextBlocks: List<String>,
    assistantText: String,
): String? {
    val incoming = assistantText.trim()
    if (incoming.isBlank()) return null
    val existing = existingTextBlocks.map { it.trim() }.filter { it.isNotEmpty() }
    if (existing.any { block -> block == incoming || block.endsWith(incoming) }) return null
    val combined = existing.joinToString("\n")
    if (combined.isNotEmpty() && incoming.startsWith(combined)) {
        return incoming.removePrefix(combined).trimStart('\n').takeIf { it.isNotBlank() }
    }
    if (existing.isEmpty()) return incoming
    return null
}

internal fun watchdogFallbackText(
    reason: String,
    statusText: String = "",
    statusDetail: String = "",
): String {
    if (reason == "silence") return AgentTurnSilenceMessage
    val status = listOf(statusText.trim(), statusDetail.trim())
        .filter { it.isNotBlank() }
        .joinToString("\n")
    if (status.isNotBlank()) return status
    return "The model finished without returning any assistant text."
}

/** Prefer the live assistant text over the session title in the running-task notification. */
internal fun foregroundRunningSessionLine(
    sessionTitle: String,
    pendingAssistantText: String,
    untitled: String,
    maxChars: Int = 160,
): String {
    val streamed = pendingAssistantText
        .trim()
        .replace(Regex("\\s+"), " ")
        .take(maxChars)
        .trim()
    if (streamed.isNotBlank()) return streamed
    return sessionTitle.trim().ifBlank { untitled }
}

/**
 * Decides when a turn that has stopped producing events should be aborted.
 * A running tool keeps the turn alive. A terminal stop (final text, error, length) aborts after
 * a short grace if the agent loop does not return. Silence aborts after [AgentTurnSilenceTimeoutMillis].
 */
internal class AgentTurnActivity(
    nowMillis: Long = System.currentTimeMillis(),
) {
    private val lock = Any()
    private var lastActivityAtMillis: Long = nowMillis
    private var terminalStopAtMillis: Long? = null

    var endReason: String? = null
        private set

    fun noteActivity(nowMillis: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            lastActivityAtMillis = nowMillis
            terminalStopAtMillis = null
        }
    }

    fun noteTerminalStop(stopReason: String, nowMillis: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            lastActivityAtMillis = nowMillis
            if (!agentTurnStopReasonIsTerminal(stopReason)) {
                terminalStopAtMillis = null
                return
            }
            if (terminalStopAtMillis == null) terminalStopAtMillis = nowMillis
        }
    }

    fun shouldAbort(toolInProgress: Boolean, nowMillis: Long = System.currentTimeMillis()): String? {
        synchronized(lock) {
            if (endReason != null || toolInProgress) return null
            val settledAt = terminalStopAtMillis
            if (settledAt != null && nowMillis - settledAt >= AgentTurnSettleGraceMillis) return "settled"
            if (nowMillis - lastActivityAtMillis >= AgentTurnSilenceTimeoutMillis) return "silence"
            return null
        }
    }

    fun markAborted(reason: String) {
        synchronized(lock) {
            if (endReason == null) endReason = reason
        }
    }
}
