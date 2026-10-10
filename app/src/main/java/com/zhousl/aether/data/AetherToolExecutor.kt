package com.zhousl.aether.data

import com.zhousl.aether.runtime.RuntimeRouter
import org.json.JSONArray
import org.json.JSONObject

data class AetherToolExecutionResult(
    val toolName: String,
    val argumentsJson: String,
    val rawOutput: String,
    val visibleOutput: String = AetherToolExecutor.sanitizeToolOutputForConversation(toolName, rawOutput),
) {
    val isError: Boolean = !AetherToolExecutor.inferToolOutputOk(visibleOutput)
}

/**
 * Adapter for Aether-owned host capabilities only. Pi Coding Agent owns all
 * filesystem, shell, search, Skill, Extension, and image-reading mechanics.
 */
class AetherToolExecutor(
    private val runtimeRouter: RuntimeRouter,
    private val agentModeController: AgentModeController? = null,
) {
    suspend fun execute(
        settings: AppSettings,
        workspaceDirectory: String,
        termuxWorkspaceDirectory: String,
        toolName: String,
        argumentsJson: String,
        selfManagementTool: AetherSelfManagementTool? = null,
        agentModeEnabled: Boolean = false,
        currentRuntimeId: LocalRuntimeId = settings.defaultRuntimeId ?: LocalRuntimeId.Alpine,
        onRuntimeChanged: suspend (LocalRuntimeId) -> Unit = {},
        onProgress: (suspend (String) -> Unit)? = null,
    ): AetherToolExecutionResult {
        val rawOutput = when (toolName) {
            "agent_display" -> if (agentModeEnabled) {
                agentModeController?.execute(
                    settings = settings,
                    workspaceDirectory = workspaceDirectory,
                    termuxWorkspaceDirectory = termuxWorkspaceDirectory,
                    argumentsJson = argumentsJson,
                ) ?: unavailableToolOutput(toolName)
            } else {
                JSONObject()
                    .put("ok", false)
                    .put("errmsg", "Agent Mode is not enabled for this chat.")
                    .toString()
            }

            "aether_runtime_manage" -> executeRuntimeManage(
                settings = settings,
                currentRuntimeId = currentRuntimeId,
                workspaceDirectory = workspaceDirectory,
                termuxWorkspaceDirectory = termuxWorkspaceDirectory,
                argumentsJson = argumentsJson,
                onRuntimeChanged = onRuntimeChanged,
            )

            in SelfManagementToolNames -> selfManagementTool?.execute(
                toolName = toolName,
                argumentsJson = argumentsJson,
            ) ?: unavailableToolOutput(toolName)

            else -> JSONObject()
                .put("ok", false)
                .put("error", "Unknown Aether host tool '$toolName'.")
                .toString()
        }
        return AetherToolExecutionResult(toolName, argumentsJson, rawOutput)
    }

    private suspend fun executeRuntimeManage(
        settings: AppSettings,
        currentRuntimeId: LocalRuntimeId,
        workspaceDirectory: String,
        termuxWorkspaceDirectory: String,
        argumentsJson: String,
        onRuntimeChanged: suspend (LocalRuntimeId) -> Unit,
    ): String {
        val arguments = runCatching { JSONObject(argumentsJson) }.getOrNull()
            ?: return JSONObject().put("ok", false).put("errmsg", "Invalid JSON arguments.").toString()
        val action = arguments.optString("action").trim()
        if (action == "status") {
            val states = LocalRuntimeId.entries.associateWith { runtimeId ->
                runtimeRouter.runtimeById(runtimeId).inspectSetup()
            }
            return JSONObject().apply {
                put("ok", true)
                put("action", "status")
                put("runtime", currentRuntimeId.storageValue)
                put("cwd", runtimeCwd(currentRuntimeId, workspaceDirectory, termuxWorkspaceDirectory))
                put(
                    "available",
                    JSONObject().apply {
                        states.forEach { (runtimeId, state) -> put(runtimeId.storageValue, state.isReady) }
                    },
                )
            }.toString()
        }
        if (action != "set") {
            return JSONObject().put("ok", false).put("errmsg", "action must be 'status' or 'set'.").toString()
        }
        val requested = LocalRuntimeId.fromStorage(arguments.optString("runtime"))
            ?: return JSONObject().put("ok", false).put("errmsg", "runtime must be 'alpine' or 'termux'.").toString()
        val setup = runtimeRouter.runtimeById(requested).inspectSetup()
        val enabled = settings.enabledRuntimeIds.isEmpty() || requested in settings.enabledRuntimeIds
        if (!setup.isReady || !enabled) {
            return JSONObject().apply {
                put("ok", false)
                put("errmsg", "${requested.displayName} runtime is unavailable.")
                put("detail", setup.detail)
                put("runtime", currentRuntimeId.storageValue)
                put("cwd", runtimeCwd(currentRuntimeId, workspaceDirectory, termuxWorkspaceDirectory))
            }.toString()
        }
        onRuntimeChanged(requested)
        return JSONObject().apply {
            put("ok", true)
            put("action", "set")
            put("runtime", requested.storageValue)
            put("cwd", runtimeCwd(requested, workspaceDirectory, termuxWorkspaceDirectory))
        }.toString()
    }

    companion object {
        val hostToolNames: Set<String> = setOf("agent_display", *SelfManagementToolNames.toTypedArray())

        fun supports(toolName: String): Boolean = toolName in hostToolNames

        fun hostToolDefinitions(
            selfManagementTool: AetherSelfManagementTool? = null,
            agentModeEnabled: Boolean = false,
        ): JSONArray = JSONArray().apply {
            selfManagementTool?.toolDefinitions()?.forEach { definition ->
                put(
                    flattenOpenAiToolDefinition(
                        definition = definition,
                        executionMode = if (
                            definition.optJSONObject("function")?.optString("name") == "aether_config_get"
                        ) "parallel" else "sequential",
                    ),
                )
            }
            if (agentModeEnabled) put(agentModeToolDefinition())
        }

        fun sanitizeToolOutputForConversation(toolName: String, output: String): String {
            if (toolName != "agent_display") return output
            val parsed = runCatching { JSONObject(output) }.getOrNull() ?: return output
            if (!parsed.has("screenshot_base64")) return output
            parsed.remove("screenshot_base64")
            parsed.put("screenshot_injected_into_next_model_request", true)
            return parsed.toString()
        }

        /**
         * The tool-result text the model reads. The conversation keeps [visibleOutput] intact for
         * the UI replay (preview_path, display-pixel cursor); fields the model has no use for are
         * dropped here because every later request resends them.
         */
        fun modelVisibleToolOutput(toolName: String, visibleOutput: String): String {
            if (toolName != "agent_display") return visibleOutput
            val parsed = runCatching { JSONObject(visibleOutput) }.getOrNull() ?: return visibleOutput
            AgentDisplayModelHiddenKeys.forEach(parsed::remove)
            if (parsed.has("image_width")) {
                parsed.remove("width")
                parsed.remove("height")
            }
            return parsed.toString()
        }

        fun inferToolOutputOk(output: String): Boolean {
            val parsed = runCatching { JSONObject(output) }.getOrNull() ?: return true
            return parsed.optBoolean("ok", !parsed.optBoolean("err", false))
        }
    }
}

private fun runtimeCwd(
    runtimeId: LocalRuntimeId,
    workspaceDirectory: String,
    termuxWorkspaceDirectory: String,
): String = if (runtimeId == LocalRuntimeId.Termux) termuxWorkspaceDirectory else workspaceDirectory

private val AgentDisplayModelHiddenKeys = listOf(
    "preview_path",
    "cursor_x",
    "cursor_y",
    "screenshot_mime_type",
    "screenshot_injected_into_next_model_request",
)

private val SelfManagementToolNames = setOf(
    "aether_config_get",
    "aether_config_set",
    "aether_skill_manage",
    "aether_termux_manage",
    "aether_runtime_manage",
    "aether_agent_mode_manage",
    "aether_scheduled_task_manage",
    "aether_extension_manage",
    "aether_developer_manage",
)

private fun unavailableToolOutput(toolName: String): String = JSONObject()
    .put("ok", false)
    .put("errmsg", "Host dependency for '$toolName' is not available.")
    .toString()

private fun flattenOpenAiToolDefinition(
    definition: JSONObject,
    executionMode: String,
): JSONObject {
    val function = definition.optJSONObject("function") ?: JSONObject()
    return JSONObject().apply {
        put("name", function.optString("name"))
        put("description", function.optString("description"))
        put("parameters", relaxStrictOptionalParameters(function.optJSONObject("parameters")))
        put("execution_mode", executionMode)
    }
}

private fun relaxStrictOptionalParameters(parameters: JSONObject?): JSONObject {
    val relaxed = JSONObject((parameters ?: JSONObject().put("type", "object")).toString())
    val properties = relaxed.optJSONObject("properties") ?: return relaxed
    val required = relaxed.optJSONArray("required") ?: return relaxed
    relaxed.put(
        "required",
        JSONArray().apply {
            for (index in 0 until required.length()) {
                val name = required.optString(index)
                if (name.isNotBlank() && !properties.optJSONObject(name).allowsNull()) put(name)
            }
        },
    )
    return relaxed
}

private fun JSONObject?.allowsNull(): Boolean = when (val type = this?.opt("type")) {
    "null" -> true
    is JSONArray -> (0 until type.length()).any { type.optString(it) == "null" }
    else -> false
}

private fun agentModeToolDefinition(): JSONObject = JSONObject().apply {
    put("name", "agent_display")
    put(
        "description",
        "Operate Aether Agent Mode on an isolated Android virtual display. Use this only when Agent Mode is selected in the chat composer. " +
            "Sending a chat message is launch, then find_and_input, then find_and_tap on the send label. Do not take a screenshot for those steps. " +
            "launch returns the new screen's OCR elements and omits the image unless include_screenshot is true. Next, find_and_tap the label. Do not take a screenshot first. " +
            "Prefer find_and_tap, find_and_input, and tap_node. They use controls on this virtual display only. " +
            "Do not guess coordinates in the same area again after a miss: two failures on the same query, node id, or nearby point stop the third attempt and nothing is injected. " +
            "tap/swipe coordinates are normalized 0..1000 on each axis, independent of resolution; values above 1000 are rejected. " +
            "A successful tree read returns nodes (at most 20, serialized to at most 1500 characters). Each node has id, type, text, editable, and center [x, y] in 0..1000. " +
            "source is ui_automation, accessibility_service, or ocr. Nodes, nearby nodes, and focus come only from the Agent Mode display. " +
            "accessibility_hint means the accessibility service is off or restricted. Do not guess coordinates. " +
            "find_and_input clears and replaces the field: set-text when an editable node exists, otherwise clipboard paste on this display. Do not use shell input text. clipboard_paste means the composer was replaced, not that the message was sent. Pass only the user's message, never an OCR line. " +
            "composer_has_text is true only when that text is in the composer. If it is missing, the app focuses the composer and pastes once. Do not retry the input, and do not look for send until composer_has_text is true. " +
            "screenshot always attaches an image. launch, tap, swipe, key, text, tap_text, find_and_tap, find_and_input, and tap_node omit the image unless include_screenshot is true; the result then has screenshot_omitted=\"not_requested\". " +
            "find_and_tap and tap_text do not scroll unless scroll is true, and a send label is never scrolled for. " +
            "An empty control tree is unavailable. sources_tried lists ui_automation, then accessibility_service, then ocr. After empty_tree, later actions set tree_skipped and skip the tree read. " +
            "OCR elements use text, granularity, and bbox_norm = [left, top, right, bottom] in 0..1000. find_and_tap and tap_text tap the exact word's box center, not a merged line. Do not convert screenshot pixels. " +
            "A send-like tap is confirmed only when the composer clears or the message appears as a new bubble. Otherwise status is uncertain: take one screenshot and do not tap or type again. " +
            "Do not re-read logs. Repeating the same check with no progress stops the turn. " +
            "confirmed is true only after the tapped area changes (region_changed, ocr_text_changed, composer_cleared, or message_bubble). A WebView control is tapped with one real touch. ACTION_CLICK alone is not confirmation.",
    )
    put(
        "parameters",
        JSONObject().apply {
            put("type", "object")
            put(
                "properties",
                JSONObject().apply {
                    put("action", stringProperty("One of: list_apps, start, status, launch, find_and_tap, find_and_input, tap_node, tap, swipe, key, text, screenshot, stop, find_text, tap_text."))
                    put("query", stringProperty("For list_apps: optional app label, package, or activity filter. For find_and_tap, find_and_input, find_text, and tap_text: the on-screen text to locate (exact match preferred, then substring)."))
                    put("node_id", stringProperty("For tap_node: id from the latest nodes list, such as n1."))
                    put("include_screenshot", booleanProperty("Attach a screenshot for launch, tap, swipe, key, text, tap_text, find_and_tap, find_and_input, or tap_node. Default false. launch already returns OCR elements. screenshot always attaches an image."))
                    put("scroll", booleanProperty("For find_and_tap and tap_text: scroll to look for a label below the fold. Default false. Ignored for a send label such as 发送."))
                    put("include_system", booleanProperty("For list_apps: whether to include system apps."))
                    put("max_results", integerProperty("For list_apps: maximum number of apps to return."))
                    put("target", stringProperty("For launch: package name or exact app label."))
                    listOf("x", "y", "x1", "y1", "x2", "y2").forEach { key ->
                        val axis = if (key.startsWith("x")) "width" else "height"
                        put(
                            key,
                            integerProperty(
                                "For ${if (key.length == 1) "tap" else "swipe"}: normalized $axis coordinate in 0..1000 " +
                                    "(0 = left/top edge, 1000 = right/bottom edge). " +
                                    "Prefer a node center or the tap_norm from an OCR result. Do not convert screenshot pixels.",
                            ),
                        )
                    }
                    put("duration_ms", integerProperty("For swipe: gesture duration in milliseconds (50..10000)."))
                    put("key", stringProperty("For key: Android key code name or number."))
                    put(
                        "text",
                        stringProperty(
                            "For text and find_and_input: only the user's message, including Chinese, never text copied from OCR. " +
                                "The field is cleared and replaced. Sending is a separate tap. Do not tap the field first. " +
                                "If composer_has_text is false, the app already retried once. Do not paste again.",
                        ),
                    )
                },
            )
            put("required", JSONArray().put("action"))
            put("additionalProperties", false)
        },
    )
    put("execution_mode", "sequential")
}

private fun stringProperty(description: String): JSONObject = JSONObject()
    .put("type", "string")
    .put("description", description)

private fun integerProperty(description: String): JSONObject = JSONObject()
    .put("type", "integer")
    .put("description", description)

private fun booleanProperty(description: String): JSONObject = JSONObject()
    .put("type", "boolean")
    .put("description", description)
