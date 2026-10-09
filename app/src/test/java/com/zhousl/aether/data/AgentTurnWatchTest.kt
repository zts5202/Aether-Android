package com.zhousl.aether.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentTurnWatchTest {
    @Test
    fun silenceAbortsWhenNothingIsRunning() {
        val activity = AgentTurnActivity(nowMillis = 0L)

        assertNull(activity.shouldAbort(toolInProgress = false, nowMillis = 59_000L))
        assertEquals("silence", activity.shouldAbort(toolInProgress = false, nowMillis = 60_000L))
    }

    @Test
    fun runningToolKeepsTheTurnAlivePastTheSilenceTimeout() {
        val activity = AgentTurnActivity(nowMillis = 0L)

        assertNull(activity.shouldAbort(toolInProgress = true, nowMillis = 120_000L))
    }

    @Test
    fun terminalStopSettlesAfterTheGracePeriod() {
        val activity = AgentTurnActivity(nowMillis = 0L)
        activity.noteTerminalStop("stop", nowMillis = 1_000L)

        assertNull(activity.shouldAbort(toolInProgress = false, nowMillis = 2_999L))
        assertEquals("settled", activity.shouldAbort(toolInProgress = false, nowMillis = 3_000L))
    }

    @Test
    fun toolUseIsNotATerminalStop() {
        val activity = AgentTurnActivity(nowMillis = 0L)
        activity.noteTerminalStop("toolUse", nowMillis = 1_000L)

        assertNull(activity.shouldAbort(toolInProgress = false, nowMillis = 10_000L))
        assertFalse(agentTurnStopReasonIsTerminal("toolUse"))
        assertTrue(agentTurnStopReasonIsTerminal("end_turn"))
        assertTrue(agentTurnStopReasonIsTerminal("error"))
    }

    @Test
    fun laterActivityClearsATerminalStop() {
        val activity = AgentTurnActivity(nowMillis = 0L)
        activity.noteTerminalStop("stop", nowMillis = 1_000L)
        activity.noteActivity(nowMillis = 1_500L)

        assertNull(activity.shouldAbort(toolInProgress = false, nowMillis = 4_000L))
    }

    @Test
    fun markAbortedKeepsTheFirstReason() {
        val activity = AgentTurnActivity(nowMillis = 0L)
        activity.markAborted("silence")
        activity.markAborted("settled")

        assertEquals("silence", activity.endReason)
        assertNull(activity.shouldAbort(toolInProgress = false, nowMillis = 120_000L))
    }

    @Test
    fun refusalTitlesAreNotUsedAsTheSessionTitle() {
        assertFalse(isUsableGeneratedSessionTitle("你好！作为小米MiMo，我无法帮你打开ChatGPT或操作你的相册，但我"))
        assertFalse(isUsableGeneratedSessionTitle("I can't open apps"))
        assertFalse(isUsableGeneratedSessionTitle("Sorry, I cannot do that"))
        assertTrue(isUsableGeneratedSessionTitle("Change hairstyle in ChatGPT"))
    }

    @Test
    fun assistantDoneTextIsShownOnce() {
        assertEquals(
            "你好，我可以打开相册。",
            mergeAssistantDoneText(emptyList(), "你好，我可以打开相册。"),
        )
        assertNull(
            mergeAssistantDoneText(listOf("你好，我可以打开相册。"), "你好，我可以打开相册。"),
        )
        assertEquals(
            "世界",
            mergeAssistantDoneText(listOf("你好"), "你好世界"),
        )
    }

    @Test
    fun runningNotificationPrefersStreamedTextOverTheTitle() {
        assertEquals(
            "你好，我可以打开相册。",
            foregroundRunningSessionLine(
                sessionTitle = "你好！作为小米MiMo，我无法帮你打开",
                pendingAssistantText = "你好，我可以打开相册。",
                untitled = "New chat",
            ),
        )
        assertEquals(
            "Open ChatGPT",
            foregroundRunningSessionLine(
                sessionTitle = "Open ChatGPT",
                pendingAssistantText = "   ",
                untitled = "New chat",
            ),
        )
    }

    @Test
    fun silenceFallbackKeepsTheStopMessage() {
        assertEquals(AgentTurnSilenceMessage, watchdogFallbackText("silence"))
        assertEquals(
            "Agent engine error\nprovider closed the stream",
            watchdogFallbackText(
                reason = "settled",
                statusText = "Agent engine error",
                statusDetail = "provider closed the stream",
            ),
        )
    }
}
