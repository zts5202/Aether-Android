package com.zhousl.aether.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentModeNodesTest {
    @Test
    fun filterKeepsInteractiveNodesAndQueryMatchesOnly() {
        val drafts = listOf(
            draft(type = "TextView", text = "昨天", clickable = false),
            draft(type = "Button", text = "发送", clickable = true, boundsTop = 2400, boundsBottom = 2500),
            draft(type = "EditText", text = "", editable = true, focusable = true, boundsTop = 2480, boundsBottom = 2590),
            draft(type = "TextView", text = "输入框提示", clickable = false),
        )

        val withoutQuery = selectAgentModeNodes(drafts, query = "", displayWidth = 1200, displayHeight = 2608)
        assertEquals(listOf("EditText", "Button"), withoutQuery.nodes.map { it.type })

        val withQuery = selectAgentModeNodes(drafts, query = "输入框", displayWidth = 1200, displayHeight = 2608)
        assertTrue(withQuery.nodes.any { it.text == "输入框提示" })
        assertTrue(withQuery.nodes.any { it.editable })
        assertTrue(withQuery.serialized.length <= AgentModeMaxNodeChars)
    }

    @Test
    fun capsNodeCountAndSerializedLength() {
        val drafts = (1..40).map { index ->
            draft(
                type = "Button",
                text = "按钮$index-" + "字".repeat(40),
                clickable = true,
                boundsTop = index,
                boundsBottom = index + 1,
            )
        }
        val selection = selectAgentModeNodes(drafts, query = "", displayWidth = 1200, displayHeight = 2608)

        assertTrue(selection.truncated)
        assertTrue(selection.nodes.size <= AgentModeMaxNodes)
        assertTrue(selection.serialized.length <= AgentModeMaxNodeChars)
        val parsed = JSONArray(selection.serialized)
        assertEquals(selection.nodes.size, parsed.length())
        val first = parsed.getJSONObject(0)
        assertTrue(first.getString("id").isNotBlank())
        assertEquals("Button", first.getString("type"))
        assertTrue(first.has("text"))
        assertTrue(first.has("editable"))
        assertEquals(2, first.getJSONArray("center").length())
    }

    @Test
    fun bottomNodeCenterRoundTripsInsideItsBounds() {
        val height = 2608
        val draft = draft(
            type = "EditText",
            text = "",
            editable = true,
            focusable = true,
            clickable = true,
            boundsLeft = 48,
            boundsTop = 2480,
            boundsRight = 1152,
            boundsBottom = 2596,
        )
        val node = selectAgentModeNodes(listOf(draft), query = "", displayWidth = 1200, displayHeight = height)
            .nodes
            .single()
        val resolved = resolveAgentModeCoordinate("y", node.centerY.toDouble(), height)
        val pixel = (resolved as AgentModeCoordinateResult.Valid).pixel
        assertTrue("center y=$pixel left the edit bounds", pixel in draft.boundsTop..draft.boundsBottom)
        assertTrue(pixel > (height * 0.95).toInt())
    }

    @Test
    fun highNormalizedYIsNotRejectedOrLifted() {
        val height = 2608
        val y990 = resolveAgentModeCoordinate("y", 990.0, height) as AgentModeCoordinateResult.Valid
        val y998 = resolveAgentModeCoordinate("y", 998.0, height) as AgentModeCoordinateResult.Valid
        assertEquals(2582, y990.pixel)
        assertEquals(2603, y998.pixel)
        assertTrue(y998.pixel > y990.pixel)
        assertTrue(y998.pixel < height)
    }

    @Test
    fun repeatedFailuresOnTheSameTargetStopTheThirdAttempt() {
        val guard = AgentModeFailureGuard()
        assertFalse(guard.shouldBlock(query = "发送"))
        guard.record(success = false, query = "发送")
        assertFalse(guard.shouldBlock(query = "发送"))
        guard.record(success = false, query = "发送")
        assertTrue(guard.shouldBlock(query = "发送"))
        assertFalse(guard.shouldBlock(query = "取消"))

        val points = AgentModeFailureGuard()
        points.record(success = false, normalizedX = 500, normalizedY = 944)
        points.record(success = false, normalizedX = 510, normalizedY = 990)
        assertTrue(points.shouldBlock(normalizedX = 500, normalizedY = 998))
        assertFalse(points.shouldBlock(normalizedX = 500, normalizedY = 800))

        val nodes = AgentModeFailureGuard()
        nodes.record(success = false, nodeId = "n2")
        nodes.record(success = false, nodeId = "n2")
        assertTrue(nodes.shouldBlock(nodeId = "n2"))
        nodes.record(success = true, nodeId = "n2")
        assertFalse(nodes.shouldBlock(nodeId = "n2"))
    }

    @Test
    fun screenshotsStayOffUntilTheCallerAsks() {
        listOf("tap", "swipe", "text", "key", "tap_text", "find_and_tap", "find_and_input", "tap_node")
            .forEach { action ->
                assertFalse(action, agentModeAttachesScreenshot(action, includeScreenshot = false))
                assertTrue(action, agentModeAttachesScreenshot(action, includeScreenshot = true))
            }
        assertTrue(agentModeAttachesScreenshot("screenshot", includeScreenshot = false))
        assertTrue(agentModeAttachesScreenshot("launch", includeScreenshot = false))
        assertTrue(agentModeWantsScreenshot(JSONObject().put("include_screenshot", true)))
        assertFalse(agentModeWantsScreenshot(JSONObject()))
    }

    @Test
    fun nearbyNodesPreferTheTappedArea() {
        val nodes = listOf(
            node("n1", centerY = 100),
            node("n2", centerY = 960),
            node("n3", centerY = 500),
        )
        val nearby = agentModeNearbyNodes(nodes, normalizedX = 500, normalizedY = 944, limit = 2)
        assertEquals(listOf("n2", "n3"), nearby.map { it.id })
    }

    private fun node(id: String, centerY: Int) = AgentModeNode(
        id = id,
        type = "Button",
        text = id,
        description = "",
        editable = false,
        clickable = true,
        focusable = true,
        scrollable = false,
        centerX = 500,
        centerY = centerY,
        left = 0,
        top = centerY,
        right = 1000,
        bottom = centerY,
        sourceIndex = 0,
    )

    private fun draft(
        type: String,
        text: String,
        clickable: Boolean = false,
        editable: Boolean = false,
        focusable: Boolean = false,
        boundsLeft: Int = 0,
        boundsTop: Int = 0,
        boundsRight: Int = 100,
        boundsBottom: Int = 40,
    ) = AgentModeNodeDraft(
        type = type,
        text = text,
        description = "",
        editable = editable,
        clickable = clickable,
        focusable = focusable,
        scrollable = false,
        boundsLeft = boundsLeft,
        boundsTop = boundsTop,
        boundsRight = boundsRight,
        boundsBottom = boundsBottom,
    )
}
