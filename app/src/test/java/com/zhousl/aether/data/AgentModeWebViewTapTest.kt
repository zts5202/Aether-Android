package com.zhousl.aether.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentModeWebViewTapTest {
    @Test
    fun webViewNodeAndItsDescendantsUseOneTouchAndSkipActionClick() {
        assertTrue(agentModeClassLooksLikeWebView("android.webkit.WebView"))
        assertTrue(agentModeClassLooksLikeWebView("com.uc.webview.export.WebView"))
        assertFalse(agentModeClassLooksLikeWebView("android.widget.RadioButton"))

        val classNames = listOf(
            "android.webkit.WebView",
            "android.view.View",
            "android.widget.RadioButton",
        )
        val parents = listOf(-1, 0, 1)
        assertTrue(agentModeNodeInWebView(classNames, parents, 0))
        assertTrue(agentModeNodeInWebView(classNames, parents, 2))
        assertTrue(agentModePrimaryClickIsTouch(inWebView = true))
        assertFalse(
            agentModePrimaryClickIsTouch(
                agentModeNodeInWebView(listOf("android.widget.Button"), listOf(-1), 0),
            ),
        )

        val checked = agentModeTraceClick(
            inWebView = true,
            actionClickReturnsTrue = true,
            visualAfterActionClick = true,
            visualAfterTouch = true,
        )
        assertEquals(0, checked.actionClicks)
        assertEquals(1, checked.touches)
        assertTrue(checked.confirmed)
        assertEquals("confirmed", checked.status)

        val unchanged = agentModeTraceClick(
            inWebView = true,
            actionClickReturnsTrue = true,
            visualAfterActionClick = false,
            visualAfterTouch = false,
        )
        assertEquals(0, unchanged.actionClicks)
        assertEquals(1, unchanged.touches)
        assertFalse(unchanged.confirmed)
        assertEquals("not_confirmed", unchanged.status)
    }

    @Test
    fun actionClickWithNoVisualChangeFallsBackToOneTouchAndDoesNotDoubleFire() {
        val noopThenTouch = agentModeTraceClick(
            inWebView = false,
            actionClickReturnsTrue = true,
            visualAfterActionClick = false,
            visualAfterTouch = true,
        )
        assertEquals(1, noopThenTouch.actionClicks)
        assertEquals(1, noopThenTouch.touches)
        assertTrue(noopThenTouch.confirmed)
        assertEquals(1, successes(noopThenTouch))

        val clickAlreadyChangedTheScreen = agentModeTraceClick(
            inWebView = false,
            actionClickReturnsTrue = true,
            visualAfterActionClick = true,
            visualAfterTouch = true,
        )
        assertEquals(1, clickAlreadyChangedTheScreen.actionClicks)
        assertEquals(0, clickAlreadyChangedTheScreen.touches)
        assertEquals(1, successes(clickAlreadyChangedTheScreen))

        val unknown = agentModeTraceClick(
            inWebView = false,
            actionClickReturnsTrue = true,
            visualAfterActionClick = null,
            visualAfterTouch = true,
        )
        assertEquals(0, unknown.touches)
        assertFalse(unknown.confirmed)
        assertEquals("uncertain", unknown.status)

        val stillUnchanged = agentModeTraceClick(
            inWebView = false,
            actionClickReturnsTrue = true,
            visualAfterActionClick = false,
            visualAfterTouch = false,
        )
        assertEquals(1, stillUnchanged.touches)
        assertEquals(0, successes(stillUnchanged))
        assertEquals("not_confirmed", stillUnchanged.status)

        assertEquals(
            1,
            agentModeTouchFallbackCount(
                actionClickReturnedTrue = true,
                touchAlreadyInjected = false,
                visualChanged = false,
            ),
        )
        assertEquals(
            0,
            agentModeTouchFallbackCount(
                actionClickReturnedTrue = true,
                touchAlreadyInjected = true,
                visualChanged = false,
            ),
        )
        assertEquals(
            0,
            agentModeTouchFallbackCount(
                actionClickReturnedTrue = true,
                touchAlreadyInjected = false,
                visualChanged = true,
            ),
        )
        assertEquals(
            0,
            agentModeTouchFallbackCount(
                actionClickReturnedTrue = true,
                touchAlreadyInjected = false,
                visualChanged = null,
            ),
        )
    }

    @Test
    fun sourcesTriedIncludesAccessibilityWhenUiAutomationReturnsNodes() {
        val tried = JSONArray()
            .put(
                JSONObject()
                    .put("source", AgentModeSourceUiAutomation)
                    .put("available", true)
                    .put("tried", true)
                    .put("reason", "nodes"),
            )
            .put(agentModeAccessibilitySkipNote(serviceEnabled = false))
        assertEquals(2, tried.length())
        val note = tried.getJSONObject(1)
        assertEquals(AgentModeSourceAccessibility, note.getString("source"))
        assertFalse(note.getBoolean("available"))
        assertFalse(note.getBoolean("service_enabled"))
        assertFalse(note.getBoolean("tried"))
        assertTrue(note.getBoolean("skipped"))
        assertEquals(AgentModeReasonAccessibilityDisabled, note.getString("reason"))
        assertTrue(note.getString("errmsg").contains("ui_automation"))
        assertFalse(agentModeShouldCacheEmptyTree(tried))

        val enabled = agentModeAccessibilitySkipNote(serviceEnabled = true)
        assertTrue(enabled.getBoolean("service_enabled"))
        assertFalse(enabled.getBoolean("tried"))
        assertTrue(enabled.getBoolean("skipped"))
        assertEquals(AgentModeReasonSkippedUiAutomation, enabled.getString("reason"))
        assertTrue(enabled.getString("errmsg").contains("ui_automation"))
    }

    private fun successes(trace: AgentModeClickTrace): Int = if (trace.confirmed) 1 else 0
}
