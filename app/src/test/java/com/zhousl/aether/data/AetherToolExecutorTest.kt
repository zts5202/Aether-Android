package com.zhousl.aether.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AetherToolExecutorTest {
    @Test
    fun hostToolDefinitionsDoNotDuplicatePiNativeTools() {
        val definitions = AetherToolExecutor.hostToolDefinitions()
        assertEquals(0, definitions.length())
    }

    @Test
    fun sanitizeAgentDisplayOutputRemovesScreenshotBytes() {
        val sanitized = AetherToolExecutor.sanitizeToolOutputForConversation(
            toolName = "agent_display",
            output = JSONObject().apply {
                put("ok", true)
                put("screenshot_base64", "abc123")
                put("screenshot_mime_type", "image/png")
            }.toString(),
        )

        val json = JSONObject(sanitized)
        assertFalse(json.has("screenshot_base64"))
        assertTrue(json.getBoolean("screenshot_injected_into_next_model_request"))
        assertEquals("image/png", json.getString("screenshot_mime_type"))
    }

    @Test
    fun sanitizeAgentDisplayOutputKeepsOmittedScreenshotMarkerUntouched() {
        val raw = JSONObject().apply {
            put("ok", true)
            put("screenshot_omitted", "unchanged")
        }.toString()

        val json = JSONObject(AetherToolExecutor.sanitizeToolOutputForConversation("agent_display", raw))

        assertEquals("unchanged", json.getString("screenshot_omitted"))
        assertFalse(json.has("screenshot_injected_into_next_model_request"))
    }

    @Test
    fun modelVisibleAgentDisplayOutputDropsUiOnlyFields() {
        val visible = AetherToolExecutor.sanitizeToolOutputForConversation(
            "agent_display",
            JSONObject().apply {
                put("ok", true)
                put("width", 1200)
                put("height", 2608)
                put("image_width", 588)
                put("image_height", 1280)
                put("preview_path", "/data/cache/capture.jpg")
                put("screenshot_path", "/workspace/agent-mode/capture.jpg")
                put("cursor_x", 600)
                put("cursor_y", 1304)
                put("cursor_norm_x", 500)
                put("cursor_norm_y", 500)
                put("screenshot_mime_type", "image/jpeg")
                put("screenshot_base64", "abc123")
            }.toString(),
        )

        val json = JSONObject(AetherToolExecutor.modelVisibleToolOutput("agent_display", visible))

        listOf(
            "width", "height", "preview_path", "cursor_x", "cursor_y",
            "screenshot_mime_type", "screenshot_injected_into_next_model_request",
        ).forEach { assertFalse(it, json.has(it)) }
        assertEquals(588, json.getInt("image_width"))
        assertEquals(500, json.getInt("cursor_norm_x"))
        assertEquals("/workspace/agent-mode/capture.jpg", json.getString("screenshot_path"))
        assertTrue(JSONObject(visible).has("preview_path"))
    }

    @Test
    fun modelVisibleOutputKeepsDisplaySizeForStatusAndOtherTools() {
        val status = """{"ok":true,"width":1200,"height":2608}"""
        assertEquals(1200, JSONObject(AetherToolExecutor.modelVisibleToolOutput("agent_display", status)).getInt("width"))
        val other = """{"preview_path":"x"}"""
        assertEquals(other, AetherToolExecutor.modelVisibleToolOutput("browser", other))
    }

    @Test
    fun agentModeDescriptionDocumentsSlimElementsAndOmittedScreenshots() {
        val definitions = AetherToolExecutor.hostToolDefinitions(agentModeEnabled = true)
        val description = (0 until definitions.length())
            .map { definitions.getJSONObject(it) }
            .first { it.getString("name") == "agent_display" }
            .getString("description")

        assertFalse("bbox_px" in description)
        assertTrue("bbox_norm" in description)
        assertTrue("screenshot_omitted" in description)
        assertFalse("cursor_x/cursor_y" in description)
        assertTrue("ui_changed_delayed" in description)
        assertFalse("include_image" in description)
    }

    @Test
    fun dynamicHostToolDefinitionsExcludeMcpAndChromeButIncludeAgentMode() {
        val definitions = AetherToolExecutor.hostToolDefinitions(
            selfManagementTool = null,
            agentModeEnabled = true,
        )
        val names = (0 until definitions.length())
            .map { definitions.getJSONObject(it).getString("name") }

        assertFalse("mcp_list_tools" in names)
        assertFalse("mcp__docs__search" in names)
        assertTrue("agent_display" in names)
        assertFalse("chrome" in names)
        assertEquals(
            "sequential",
            (0 until definitions.length())
                .map { definitions.getJSONObject(it) }
                .first { it.getString("name") == "agent_display" }
                .getString("execution_mode"),
        )
    }

    @Test
    fun inferToolOutputOkHonorsAetherJsonFlags() {
        assertTrue(AetherToolExecutor.inferToolOutputOk("""{"ok":true}"""))
        assertFalse(AetherToolExecutor.inferToolOutputOk("""{"ok":false}"""))
        assertFalse(AetherToolExecutor.inferToolOutputOk("""{"err":true}"""))
        assertTrue(AetherToolExecutor.inferToolOutputOk("plain text"))
    }

    @Test
    fun workspaceFileRoutingRecognizesAlpineAndTermuxRoots() {
        assertEquals(
            LocalRuntimeId.Alpine,
            resolveWorkspaceRuntimeId(
                path = "/workspace/agent-mode/capture.png",
                workingDirectory = "",
                defaultRuntimeId = LocalRuntimeId.Termux,
            ),
        )
        assertEquals(
            LocalRuntimeId.Termux,
            resolveWorkspaceRuntimeId(
                path = "/data/data/com.termux/files/home/.aether/workspace/uploads/image.png",
                workingDirectory = "",
                defaultRuntimeId = LocalRuntimeId.Alpine,
            ),
        )
        assertEquals(
            LocalRuntimeId.Alpine,
            resolveWorkspaceRuntimeId(
                path = "relative.png",
                workingDirectory = "/workspace",
                defaultRuntimeId = LocalRuntimeId.Termux,
            ),
        )
        assertEquals(
            LocalRuntimeId.Termux,
            resolveWorkspaceRuntimeId(
                path = "/data/data/com.termux/files/home/.aether/workspace/output.png",
                workingDirectory = "/workspace",
                defaultRuntimeId = LocalRuntimeId.Alpine,
            ),
        )
        assertEquals(
            LocalRuntimeId.Alpine,
            resolveWorkspaceRuntimeId(
                path = "file:///workspace/agent-mode/capture.png",
                workingDirectory = "/data/data/com.termux/files/home/.aether/workspace",
                defaultRuntimeId = LocalRuntimeId.Termux,
            ),
        )
    }

}
