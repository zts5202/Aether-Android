package com.zhousl.aether.data

import kotlin.math.abs
import org.json.JSONArray
import org.json.JSONObject

/** How many normalized units count as the same spot for the repeated-failure stop. */
internal const val AgentModeSameRegionTolerance = 60

internal const val AgentModeMaxNodes = 20
internal const val AgentModeMaxNodeChars = 1500
internal const val AgentModeNearbyNodeLimit = 5

internal const val AgentModeSourceUiAutomation = "ui_automation"
internal const val AgentModeSourceAccessibility = "accessibility_service"
internal const val AgentModeSourceOcr = "ocr"

internal const val AgentModeReasonRepeatedFailure = "stopped_after_repeated_failure"
internal const val AgentModeReasonNotConfirmed = "not_confirmed"
internal const val AgentModeReasonNoTree = "no_control_tree"
internal const val AgentModeReasonNodeNotFound = "node_not_found"
internal const val AgentModeReasonActionClick = "action_click"
internal const val AgentModeReasonActionFocus = "action_focus"
internal const val AgentModeReasonActionSetText = "action_set_text"
internal const val AgentModeReasonClipboardPaste = "clipboard_paste"
internal const val AgentModeReasonFocusChanged = "focus_or_text_changed"
internal const val AgentModeReasonOcrUnconfirmed = "ocr_unconfirmed"
internal const val AgentModeReasonInjectedUnconfirmed = "injected_unconfirmed"
internal const val AgentModeReasonUiAutomationUnavailable = "ui_automation_unavailable"
internal const val AgentModeReasonDisplayNotInTree = "display_not_in_tree"

private val ScreenshotOnRequestActions = setOf(
    "tap",
    "swipe",
    "text",
    "key",
    "tap_text",
    "find_and_tap",
    "find_and_input",
    "tap_node",
)

/**
 * `screenshot` always attaches an image. The gesture and composite actions attach one only when the
 * caller passes `include_screenshot`. Other actions (start, launch) keep attaching an image.
 */
internal fun agentModeAttachesScreenshot(action: String, includeScreenshot: Boolean): Boolean {
    if (action == "screenshot") return true
    if (action in ScreenshotOnRequestActions) return includeScreenshot
    return true
}

internal fun agentModeWantsScreenshot(arguments: JSONObject): Boolean =
    arguments.optBoolean("include_screenshot", false)

/**
 * One control observed on the Agent Mode display, already in the normalized 0..1000 space.
 * [sourceIndex] points back at the raw list the snapshot was built from and is not shown to the model.
 */
internal data class AgentModeNode(
    val id: String,
    val type: String,
    val text: String,
    val description: String,
    val editable: Boolean,
    val clickable: Boolean,
    val focusable: Boolean,
    val scrollable: Boolean,
    val centerX: Int,
    val centerY: Int,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val sourceIndex: Int,
)

/** Display-pixel bounds collected from an accessibility node before filtering. */
internal data class AgentModeNodeDraft(
    val type: String,
    val text: String,
    val description: String,
    val editable: Boolean,
    val clickable: Boolean,
    val focusable: Boolean,
    val scrollable: Boolean,
    val boundsLeft: Int,
    val boundsTop: Int,
    val boundsRight: Int,
    val boundsBottom: Int,
    val viewId: String = "",
) {
    val interactive: Boolean
        get() = clickable || focusable || editable || scrollable

    val width: Int
        get() = (boundsRight - boundsLeft).coerceAtLeast(0)

    val height: Int
        get() = (boundsBottom - boundsTop).coerceAtLeast(0)

    val area: Int
        get() = width * height

    val centerPixelX: Int
        get() = boundsLeft + width / 2

    val centerPixelY: Int
        get() = boundsTop + height / 2
}

internal data class AgentModeNodeSelection(
    val nodes: List<AgentModeNode>,
    val truncated: Boolean,
    val serialized: String,
)

internal fun agentModeNodeMatchesQuery(node: AgentModeNodeDraft, query: String): Boolean {
    val needle = query.trim()
    if (needle.isEmpty()) return false
    return node.text.contains(needle, ignoreCase = true) ||
        node.description.contains(needle, ignoreCase = true) ||
        node.viewId.contains(needle, ignoreCase = true)
}

/** Lower is a better find_and_tap / find_and_input match. Null when the query misses the node. */
internal fun agentModeMatchRank(node: AgentModeNodeDraft, query: String): Int? {
    val needle = query.trim()
    if (needle.isEmpty()) return null
    val text = node.text.trim()
    val description = node.description.trim()
    val viewId = node.viewId.substringAfterLast('/')
    return when {
        text.equals(needle, ignoreCase = true) -> 0
        description.equals(needle, ignoreCase = true) -> 1
        viewId.equals(needle, ignoreCase = true) -> 2
        text.contains(needle, ignoreCase = true) -> 3
        description.contains(needle, ignoreCase = true) -> 4
        node.viewId.contains(needle, ignoreCase = true) -> 5
        else -> null
    }
}

internal fun selectAgentModeNodes(
    drafts: List<AgentModeNodeDraft>,
    query: String,
    displayWidth: Int,
    displayHeight: Int,
    pinSourceIndexes: Set<Int> = emptySet(),
): AgentModeNodeSelection {
    val candidates = drafts.mapIndexedNotNull { index, draft ->
        val matches = agentModeNodeMatchesQuery(draft, query)
        if (!draft.interactive && !matches && index !in pinSourceIndexes) return@mapIndexedNotNull null
        index to draft
    }.sortedWith(
        compareBy<Pair<Int, AgentModeNodeDraft>>(
            { (index, draft) -> if (index in pinSourceIndexes) 0 else 1 },
            { (_, draft) -> nodePreference(draft, query) },
            { (_, draft) -> draft.area },
        ),
    )
    val identified = candidates.mapIndexed { position, (index, draft) ->
        draft.toNode(
            id = "n${position + 1}",
            sourceIndex = index,
            displayWidth = displayWidth,
            displayHeight = displayHeight,
        )
    }
    val limited = limitAgentModeNodes(identified)
    return AgentModeNodeSelection(
        nodes = limited.nodes,
        truncated = limited.truncated,
        serialized = agentModeNodesJson(limited.nodes).toString(),
    )
}

private fun nodePreference(draft: AgentModeNodeDraft, query: String): Int = when {
    query.isNotBlank() && draft.text.trim().equals(query.trim(), ignoreCase = true) -> 0
    query.isNotBlank() && agentModeNodeMatchesQuery(draft, query) -> 1
    draft.editable -> 2
    draft.clickable -> 3
    draft.focusable -> 4
    draft.scrollable -> 5
    else -> 6
}

internal data class AgentModeLimitedNodes(
    val nodes: List<AgentModeNode>,
    val truncated: Boolean,
)

/** Caps count at 20 and the JSON array at 1500 characters, dropping from the tail. */
internal fun limitAgentModeNodes(nodes: List<AgentModeNode>): AgentModeLimitedNodes {
    var current = nodes.take(AgentModeMaxNodes)
    var truncated = nodes.size > AgentModeMaxNodes
    while (current.isNotEmpty() && agentModeNodesJson(current).toString().length > AgentModeMaxNodeChars) {
        current = current.dropLast(1)
        truncated = true
    }
    if (current.isEmpty() && nodes.isNotEmpty()) {
        current = listOf(shrinkNode(nodes.first()))
        truncated = true
        while (agentModeNodesJson(current).toString().length > AgentModeMaxNodeChars &&
            (current[0].text.length > 8 || current[0].description.length > 8 || current[0].type.length > 12)
        ) {
            current = listOf(shrinkNode(current[0]))
        }
    }
    return AgentModeLimitedNodes(current, truncated)
}

private fun shrinkNode(node: AgentModeNode): AgentModeNode = node.copy(
    text = node.text.take(24),
    description = node.description.take(24),
    type = node.type.substringAfterLast('.').take(24),
)

internal fun agentModeNodesJson(nodes: List<AgentModeNode>): JSONArray = JSONArray().apply {
    nodes.forEach { node ->
        put(
            JSONObject().apply {
                put("id", node.id)
                put("type", node.type.substringAfterLast('.').ifBlank { node.type })
                val label = node.text.ifBlank { node.description }
                put("text", label)
                if (node.description.isNotBlank() && node.description != label) {
                    put("description", node.description)
                }
                put("editable", node.editable)
                put(
                    "center",
                    JSONArray().put(node.centerX).put(node.centerY),
                )
            },
        )
    }
}

internal fun parseAgentModeNodes(array: JSONArray?): List<AgentModeNode> {
    if (array == null) return emptyList()
    return buildList {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val center = item.optJSONArray("center")
            add(
                AgentModeNode(
                    id = item.optString("id"),
                    type = item.optString("type"),
                    text = item.optString("text"),
                    description = item.optString("description"),
                    editable = item.optBoolean("editable"),
                    clickable = false,
                    focusable = false,
                    scrollable = false,
                    centerX = center?.optInt(0) ?: 0,
                    centerY = center?.optInt(1) ?: 0,
                    left = 0,
                    top = 0,
                    right = 0,
                    bottom = 0,
                    sourceIndex = -1,
                ),
            )
        }
    }
}

internal fun agentModeNearbyNodes(
    nodes: List<AgentModeNode>,
    normalizedX: Int?,
    normalizedY: Int?,
    limit: Int = AgentModeNearbyNodeLimit,
): List<AgentModeNode> {
    if (nodes.isEmpty()) return emptyList()
    val ranked = if (normalizedX == null || normalizedY == null) {
        nodes
    } else {
        nodes.sortedBy { node ->
            val dx = abs(node.centerX - normalizedX)
            val dy = abs(node.centerY - normalizedY)
            dx + dy
        }
    }
    return limitAgentModeNodes(ranked.take(limit)).nodes
}

private fun AgentModeNodeDraft.toNode(
    id: String,
    sourceIndex: Int,
    displayWidth: Int,
    displayHeight: Int,
): AgentModeNode {
    val centerX = normalizeAgentModePixel(centerPixelX, displayWidth)
    val centerY = normalizeAgentModePixel(centerPixelY, displayHeight)
    return AgentModeNode(
        id = id,
        type = type.ifBlank { "view" },
        text = text,
        description = description,
        editable = editable,
        clickable = clickable,
        focusable = focusable,
        scrollable = scrollable,
        centerX = centerX,
        centerY = centerY,
        left = normalizeAgentModePixel(boundsLeft, displayWidth),
        top = normalizeAgentModePixel(boundsTop, displayHeight),
        right = normalizeAgentModePixel(boundsRight, displayWidth),
        bottom = normalizeAgentModePixel(boundsBottom, displayHeight),
        sourceIndex = sourceIndex,
    )
}

/**
 * Stops a third consecutive attempt at the same query, node id, or nearby coordinate.
 * A success clears the streak. Comparing coordinates uses [AgentModeSameRegionTolerance], not a grid,
 * so a run of guesses along the bottom of the screen counts as one target.
 */
internal class AgentModeFailureGuard {
    private var query: String? = null
    private var nodeId: String? = null
    private var x: Int? = null
    private var y: Int? = null
    private var failures: Int = 0

    fun shouldBlock(query: String? = null, nodeId: String? = null, normalizedX: Int? = null, normalizedY: Int? = null): Boolean =
        failures >= 2 && sameTarget(query, nodeId, normalizedX, normalizedY)

    fun record(
        success: Boolean,
        query: String? = null,
        nodeId: String? = null,
        normalizedX: Int? = null,
        normalizedY: Int? = null,
    ) {
        if (success) {
            clear()
            return
        }
        if (failures > 0 && sameTarget(query, nodeId, normalizedX, normalizedY)) {
            failures += 1
        } else {
            this.query = query?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            this.nodeId = nodeId?.trim()?.takeIf { it.isNotEmpty() }
            this.x = normalizedX
            this.y = normalizedY
            failures = 1
        }
    }

    private fun sameTarget(query: String?, nodeId: String?, normalizedX: Int?, normalizedY: Int?): Boolean {
        val nextQuery = query?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        if (nextQuery != null || this.query != null) return nextQuery != null && nextQuery == this.query
        val nextNode = nodeId?.trim()?.takeIf { it.isNotEmpty() }
        if (nextNode != null || this.nodeId != null) return nextNode != null && nextNode == this.nodeId
        val lastX = x
        val lastY = y
        if (lastX == null || lastY == null || normalizedX == null || normalizedY == null) return false
        return abs(normalizedX - lastX) <= AgentModeSameRegionTolerance &&
            abs(normalizedY - lastY) <= AgentModeSameRegionTolerance
    }

    private fun clear() {
        query = null
        nodeId = null
        x = null
        y = null
        failures = 0
    }
}

internal fun agentModeTargetKey(
    query: String? = null,
    nodeId: String? = null,
    normalizedX: Int? = null,
    normalizedY: Int? = null,
): String {
    val normalizedQuery = query?.trim().orEmpty()
    if (normalizedQuery.isNotEmpty()) return "query:${normalizedQuery.lowercase()}"
    val normalizedNode = nodeId?.trim().orEmpty()
    if (normalizedNode.isNotEmpty()) return "node:$normalizedNode"
    if (normalizedX != null && normalizedY != null) return "point:$normalizedX:$normalizedY"
    return "action"
}
