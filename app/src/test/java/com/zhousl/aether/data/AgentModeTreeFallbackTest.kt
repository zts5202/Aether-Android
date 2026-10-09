package com.zhousl.aether.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentModeTreeFallbackTest {
    @Test
    fun emptyOrDecorOnlyTreeHasNoControls() {
        assertFalse(agentModeTreeHasControls(emptyList()))
        assertFalse(
            agentModeTreeHasControls(
                listOf(
                    draft(type = "android.view.View", clickable = true),
                    draft(type = "com.android.internal.policy.DecorView"),
                    draft(type = "android.widget.FrameLayout", clickable = true),
                ),
            ),
        )
    }

    @Test
    fun labeledOrRealControlsCount() {
        assertTrue(agentModeTreeHasControls(listOf(draft(type = "android.widget.Button", clickable = true))))
        assertTrue(
            agentModeTreeHasControls(
                listOf(draft(type = "android.widget.TextView", text = "文件传输助手")),
            ),
        )
    }

    @Test
    fun emptyTreeIsUnusableAndFallsThrough() {
        val emptyUiAutomation = JSONObject()
            .put("available", true)
            .put("source", AgentModeSourceUiAutomation)
            .put("reason", AgentModeReasonNodeNotFound)
            .put("nodes", JSONArray())
            .put("candidate_count", 0)
        assertFalse(agentModeTreeReadUsable(emptyUiAutomation))

        val accessibility = JSONObject()
            .put("available", true)
            .put("reason", AgentModeReasonActionClick)
            .put("nodes", JSONArray().put(JSONObject().put("id", "n1").put("text", "发送")))
        assertEquals(
            AgentModeSourceAccessibility,
            agentModeChosenTreeSource(emptyUiAutomation, accessibility),
        )

        val emptyService = JSONObject()
            .put("available", false)
            .put("reason", AgentModeReasonEmptyTree)
            .put("nodes", JSONArray())
            .put("candidate_count", 0)
        assertEquals(
            AgentModeSourceOcr,
            agentModeChosenTreeSource(emptyUiAutomation, emptyService),
        )
    }

    @Test
    fun queryMissOnARealTreeStaysOnThatSource() {
        val miss = JSONObject()
            .put("available", true)
            .put("reason", AgentModeReasonNodeNotFound)
            .put("nodes", JSONArray().put(JSONObject().put("id", "n1").put("text", "微信")))
            .put("candidate_count", 4)
        assertTrue(agentModeTreeReadUsable(miss))
        assertEquals(AgentModeSourceUiAutomation, agentModeChosenTreeSource(miss, null))

        val click = JSONObject()
            .put("available", true)
            .put("reason", AgentModeReasonActionClick)
        assertTrue(agentModeTreeReadUsable(click))
        assertFalse(
            agentModeTreeReadUsable(
                JSONObject().put("available", false).put("reason", AgentModeReasonEmptyTree),
            ),
        )
    }

    private fun draft(
        type: String,
        text: String = "",
        clickable: Boolean = false,
        editable: Boolean = false,
    ) = AgentModeNodeDraft(
        type = type,
        text = text,
        description = "",
        editable = editable,
        clickable = clickable,
        focusable = false,
        scrollable = false,
        boundsLeft = 0,
        boundsTop = 0,
        boundsRight = 10,
        boundsBottom = 10,
    )
}
