package com.zhousl.aether.data

internal data class AgentModeLoopDecision(
    val stop: Boolean,
    val message: String = "",
)

/**
 * Stops an Agent Mode turn that keeps reading the same logs or repeating a check with no new result.
 * A gesture or a launch counts as progress and clears the streak.
 */
internal class AgentModeLoopGuard {
    private var lastSignature: String? = null
    private var streak = 0
    private var lastOutput: String? = null
    private var unchangedResults = 0
    private var stopped = false

    fun reset() {
        lastSignature = null
        streak = 0
        lastOutput = null
        unchangedResults = 0
        stopped = false
    }

    fun beforeCall(toolName: String, arguments: String): AgentModeLoopDecision {
        if (stopped) return stopDecision()
        if (!isInspection(toolName, arguments)) {
            lastSignature = null
            streak = 0
            lastOutput = null
            unchangedResults = 0
            return AgentModeLoopDecision(stop = false)
        }
        if (unchangedResults >= 2) return stopDecision()
        val signature = signature(toolName, arguments)
        streak = if (signature == lastSignature) streak + 1 else 1
        lastSignature = signature
        if (streak >= 3) return stopDecision()
        return AgentModeLoopDecision(stop = false)
    }

    fun afterResult(toolName: String, arguments: String, output: String): AgentModeLoopDecision {
        if (stopped) return stopDecision()
        if (!isInspection(toolName, arguments)) return AgentModeLoopDecision(stop = false)
        val fingerprint = output.trim().take(240)
        if (fingerprint.length < 40) return AgentModeLoopDecision(stop = false)
        unchangedResults = if (fingerprint == lastOutput) unchangedResults + 1 else 1
        lastOutput = fingerprint
        if (unchangedResults >= 3) return stopDecision()
        return AgentModeLoopDecision(stop = false)
    }

    private fun stopDecision(): AgentModeLoopDecision {
        stopped = true
        return AgentModeLoopDecision(stop = true, message = Message)
    }

    private fun signature(toolName: String, arguments: String): String =
        toolName.trim() + "\n" + arguments.trim()

    private fun isInspection(toolName: String, arguments: String): Boolean {
        val name = toolName.trim()
        if (name in InspectionTools) return true
        if (name != "agent_display") return false
        val action = actionOf(arguments) ?: return true
        return action in InspectionActions
    }

    private fun actionOf(arguments: String): String? {
        val match = ActionRegex.find(arguments) ?: return null
        return match.groupValues[1]
    }

    companion object {
        const val Message =
            "Stopped: the same check ran again with no progress. Do not re-read logs or repeat this tool call. Tell the user the current state and stop."

        private val InspectionTools = setOf("read", "bash", "grep", "find", "ls")
        private val InspectionActions = setOf("status", "list_apps", "find_text", "screenshot")
        private val ActionRegex = Regex(""""action"\s*:\s*"([^"]+)"""")
    }
}
