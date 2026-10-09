package com.zhousl.aether.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentModeLoopGuardTest {
    @Test
    fun thirdIdenticalReadStopsBeforeItRuns() {
        val guard = AgentModeLoopGuard()
        val args = """{"path":"/session/log.txt"}"""
        assertFalse(guard.beforeCall("read", args).stop)
        assertFalse(guard.beforeCall("read", args).stop)
        val third = guard.beforeCall("read", args)
        assertTrue(third.stop)
        assertEquals(AgentModeLoopGuard.Message, third.message)
        assertTrue(guard.beforeCall("bash", """{"command":"ls"}""").stop)
    }

    @Test
    fun unchangedInspectionResultsStopTheNextCheck() {
        val guard = AgentModeLoopGuard()
        val output = "session log line ".repeat(6)
        assertTrue(output.length >= 40)
        assertFalse(guard.beforeCall("bash", """{"command":"tail log"}""").stop)
        assertFalse(guard.afterResult("bash", """{"command":"tail log"}""", output).stop)
        assertFalse(guard.beforeCall("read", """{"path":"log"}""").stop)
        assertFalse(guard.afterResult("read", """{"path":"log"}""", output).stop)
        val next = guard.beforeCall("grep", """{"pattern":"发送"}""")
        assertTrue(next.stop)
        assertEquals(AgentModeLoopGuard.Message, next.message)
    }

    @Test
    fun gestureResetsTheInspectionStreak() {
        val guard = AgentModeLoopGuard()
        val args = """{"path":"/session/log.txt"}"""
        assertFalse(guard.beforeCall("read", args).stop)
        assertFalse(guard.beforeCall("read", args).stop)
        assertFalse(
            guard.beforeCall(
                "agent_display",
                """{"action":"find_and_tap","query":"发送"}""",
            ).stop,
        )
        assertFalse(guard.beforeCall("read", args).stop)
    }
}
