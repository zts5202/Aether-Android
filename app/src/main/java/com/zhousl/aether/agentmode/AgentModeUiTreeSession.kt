package com.zhousl.aether.agentmode

import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.zhousl.aether.data.AgentModeMaxNodeChars
import com.zhousl.aether.data.AgentModeNodeDraft
import com.zhousl.aether.data.AgentModeNodeSelection
import com.zhousl.aether.data.AgentModeClickPathActionClick
import com.zhousl.aether.data.AgentModeClickPathTouch
import com.zhousl.aether.data.AgentModeReasonActionClick
import com.zhousl.aether.data.AgentModeReasonActionSetText
import com.zhousl.aether.data.AgentModeReasonClipboardPaste
import com.zhousl.aether.data.AgentModeReasonDisplayNotInTree
import com.zhousl.aether.data.AgentModeReasonEmptyTree
import com.zhousl.aether.data.AgentModeReasonOtherDisplay
import com.zhousl.aether.data.agentModeTreeHasControls
import com.zhousl.aether.data.AgentModeReasonNodeNotFound
import com.zhousl.aether.data.AgentModeReasonNotConfirmed
import com.zhousl.aether.data.AgentModeReasonUiAutomationUnavailable
import com.zhousl.aether.data.agentModeClassLooksLikeWebView
import com.zhousl.aether.data.agentModeMatchRank
import com.zhousl.aether.data.agentModePrimaryClickIsTouch
import com.zhousl.aether.data.agentModeNearbyNodes
import com.zhousl.aether.data.agentModeNodesJson
import com.zhousl.aether.data.selectAgentModeNodes
import java.util.IdentityHashMap
import org.json.JSONArray
import org.json.JSONObject

internal interface AgentModeTreeInjector {
    fun tap(displayId: Int, x: Int, y: Int)
    fun paste(displayId: Int, text: String): String
    fun focusWindow(displayId: Int): String
}

/**
 * Walks the accessibility windows of one display and performs node actions.
 * [windowsOnDisplay] returns null when the backing connection itself is unavailable, and an empty
 * list when the connection works but this display has no windows.
 * [injector] performs coordinate taps and clipboard paste. When it is null the result asks the
 * caller to do those through the privileged input path.
 */
internal class AgentModeUiTreeSession(
    private val source: String,
    private val windowsOnDisplay: (Int) -> AgentModeWindowBatch?,
    private val injector: AgentModeTreeInjector?,
) {
    private val lock = Any()
    private var snapshotCounter = 0
    private var snapshotId = ""
    private var cached = emptyList<CachedNode>()

    fun close() = synchronized(lock) {
        cached.forEach { runCatching { it.info.recycle() } }
        cached = emptyList()
    }

    fun handle(displayId: Int, requestJson: String): String = synchronized(lock) {
        val request = runCatching { JSONObject(requestJson) }.getOrElse {
            return@synchronized failure(
                available = false,
                reason = "invalid_request",
                message = "Control-tree request was not valid JSON.",
            )
        }
        val width = request.optInt("width").takeIf { it > 0 } ?: 1
        val height = request.optInt("height").takeIf { it > 0 } ?: 1
        try {
            when (request.optString("op")) {
                "tap_node" -> tapCachedNode(displayId, request, width, height)
                else -> withFreshTree(displayId, width, height) { collected ->
                    when (request.optString("op")) {
                        "dump" -> publish(collected, request.optString("query"), width, height, pin = emptySet())
                            .put("ok", true)
                            .put("confirmed", true)
                            .put("reason", "dumped")
                        "click_at" -> clickAt(displayId, collected, request, width, height)
                        "find_and_tap" -> findAndTap(displayId, collected, request, width, height)
                        "find_and_input" -> findAndInput(displayId, collected, request, width, height)
                        "set_text" -> setTextOnFocused(displayId, collected, request, width, height)
                        else -> jsonFailure(available = true, reason = "unsupported_op", message = "Unsupported tree op.")
                    }
                }
            }.toString()
        } catch (throwable: Throwable) {
            failure(
                available = false,
                reason = AgentModeReasonUiAutomationUnavailable,
                message = throwable.message ?: throwable.javaClass.simpleName,
            )
        }
    }

    private fun withFreshTree(
        displayId: Int,
        width: Int,
        height: Int,
        block: (List<Collected>) -> JSONObject,
    ): JSONObject {
        val batch = windowsOnDisplay(displayId)
            ?: return jsonFailure(available = false, reason = AgentModeReasonUiAutomationUnavailable, message = "UiAutomation is not connected.")
        if (batch.windows.isEmpty()) {
            val foreign = batch.foreignDisplayIds
            val otherDisplays = foreign.isNotEmpty()
            return jsonFailure(
                available = false,
                reason = if (otherDisplays) AgentModeReasonOtherDisplay else AgentModeReasonDisplayNotInTree,
                message = if (otherDisplays) {
                    "Display $displayId has no controls. Windows on other displays $foreign were ignored."
                } else {
                    "Display $displayId is not in the accessibility window list."
                },
            ).put("foreign_display_ids", JSONArray(foreign))
        }
        val windows = batch.windows
        val collected = collect(windows)
        if (!agentModeTreeHasControls(collected.map { it.draft })) {
            val count = collected.size
            windows.forEach { runCatching { it.recycle() } }
            collected.forEach { runCatching { it.info.recycle() } }
            return jsonFailure(
                available = false,
                reason = AgentModeReasonEmptyTree,
                message = "Display $displayId has windows but no interactive controls.",
            ).put("candidate_count", count).put("nodes", JSONArray())
        }
        windows.forEach { runCatching { it.recycle() } }
        return try {
            block(collected)
        } finally {
            val kept = IdentityHashMap<AccessibilityNodeInfo, Boolean>()
            cached.forEach { kept[it.info] = true }
            collected.forEach { item ->
                if (kept[item.info] != true) runCatching { item.info.recycle() }
            }
        }
    }

    private fun clickAt(
        displayId: Int,
        collected: List<Collected>,
        request: JSONObject,
        width: Int,
        height: Int,
    ): JSONObject {
        val x = request.optInt("x")
        val y = request.optInt("y")
        val index = nodeAt(collected, x, y, width, height)
        val published = publish(collected, query = "", width, height, pin = setOfNotNull(index))
        if (index == null) {
            return injectTouch(displayId, x, y, published.put("in_webview", false))
        }
        val item = collected[index]
        return activateNode(
            displayId = displayId,
            inWebView = item.inWebView,
            centerX = item.draft.centerPixelX,
            centerY = item.draft.centerPixelY,
            published = published.put("node_id", idFor(index)),
            tryActionClick = { performClick(collected, index, width, height) != null },
        )
    }

    private fun findAndTap(
        displayId: Int,
        collected: List<Collected>,
        request: JSONObject,
        width: Int,
        height: Int,
    ): JSONObject {
        val query = request.optString("query").trim()
        if (query.isEmpty()) {
            return jsonFailure(available = true, reason = "missing_query", message = "find_and_tap requires query.")
        }
        val index = bestMatch(collected, query, editableOnly = false)
        val published = publish(collected, query, width, height, pin = setOfNotNull(index))
        if (index == null) {
            return notFound(published, query, width, height, collected)
        }
        val draft = collected[index].draft
        return activateNode(
            displayId = displayId,
            inWebView = collected[index].inWebView,
            centerX = draft.centerPixelX,
            centerY = draft.centerPixelY,
            published = published
                .put("node_id", idFor(index))
                .put("matched_text", draft.text.ifBlank { draft.description }),
            tryActionClick = { performClick(collected, index, width, height) != null },
        )
    }

    private fun findAndInput(
        displayId: Int,
        collected: List<Collected>,
        request: JSONObject,
        width: Int,
        height: Int,
    ): JSONObject {
        val text = request.optString("text")
        if (text.isEmpty()) {
            return jsonFailure(available = true, reason = "missing_text", message = "find_and_input requires text.")
        }
        val query = request.optString("query").trim()
        val index = editableTarget(collected, query, width, height)
        val published = publish(collected, query, width, height, pin = setOfNotNull(index))
        if (index == null) {
            return notFound(published, query.ifBlank { text }, width, height, collected)
                .put("needs_legacy_text", true)
        }
        return writeText(displayId, collected, index, text, published)
    }

    private fun setTextOnFocused(
        displayId: Int,
        collected: List<Collected>,
        request: JSONObject,
        width: Int,
        height: Int,
    ): JSONObject {
        val text = request.optString("text")
        val index = editableTarget(collected, query = "", width, height)
        val published = publish(collected, query = "", width, height, pin = setOfNotNull(index))
        if (index == null) {
            return published
                .put("ok", false)
                .put("confirmed", false)
                .put("reason", AgentModeReasonNodeNotFound)
                .put("needs_legacy_text", true)
        }
        return writeText(displayId, collected, index, text, published)
    }

    private fun tapCachedNode(
        displayId: Int,
        request: JSONObject,
        width: Int,
        height: Int,
    ): JSONObject {
        val nodeId = request.optString("node_id").trim()
        val requestedSnapshot = request.optString("snapshot_id").trim()
        val node = cached.find { it.id == nodeId && (requestedSnapshot.isEmpty() || it.snapshotId == requestedSnapshot) }
        if (node == null) {
            return jsonFailure(
                available = true,
                reason = AgentModeReasonNodeNotFound,
                message = "Node '$nodeId' is not in the current snapshot. Dump the tree again.",
            ).put("snapshot_id", snapshotId)
        }
        val result = JSONObject()
            .put("available", true)
            .put("source", source)
            .put("snapshot_id", node.snapshotId)
            .put("node_id", node.id)
        return activateNode(
            displayId = displayId,
            inWebView = node.inWebView,
            centerX = node.bounds.centerX(),
            centerY = node.bounds.centerY(),
            published = result,
            tryActionClick = { node.info.performAction(AccessibilityNodeInfo.ACTION_CLICK) },
        )
    }

    private fun writeText(
        displayId: Int,
        collected: List<Collected>,
        index: Int,
        text: String,
        published: JSONObject,
    ): JSONObject {
        val info = collected[index].info
        info.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val arguments = Bundle()
        arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        if (info.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) {
            settle()
            info.refresh()
            val actual = info.text?.toString().orEmpty()
            if (actual.isEmpty() || actual.contains(text)) {
                return published
                    .put("ok", true)
                    .put("confirmed", true)
                    .put("reason", AgentModeReasonActionSetText)
                    .put("node_id", idFor(index))
                    .put("text_input_method", AgentModeReasonActionSetText)
            }
        }
        val pasteMethod = pasteText(displayId, text, published)
        settle()
        info.refresh()
        val actual = info.text?.toString().orEmpty()
        val confirmed = actual.contains(text)
        return published
            .put("ok", confirmed)
            .put("confirmed", confirmed)
            .put("reason", if (confirmed) AgentModeReasonClipboardPaste else AgentModeReasonNotConfirmed)
            .put("node_id", idFor(index))
            .put("text_input_method", pasteMethod)
    }

    /**
     * WebView nodes get one real touch and no ACTION_CLICK. Native nodes try ACTION_CLICK and
     * leave a touch for the caller only after a visual no-op. Nothing here is confirmed:
     * ACTION_CLICK returning true is not a page change.
     */
    private fun activateNode(
        displayId: Int,
        inWebView: Boolean,
        centerX: Int,
        centerY: Int,
        published: JSONObject,
        tryActionClick: () -> Boolean,
    ): JSONObject {
        published.put("in_webview", inWebView)
        published.put("tap_x", centerX.coerceAtLeast(0))
        published.put("tap_y", centerY.coerceAtLeast(0))
        if (agentModePrimaryClickIsTouch(inWebView)) {
            published.put("action_click_attempted", false)
            published.put("action_click_returned", false)
            return injectTouch(displayId, centerX, centerY, published)
        }
        val clicked = tryActionClick()
        published.put("action_click_attempted", true)
        published.put("action_click_returned", clicked)
        if (clicked) {
            settle()
            published.put("click_path", AgentModeClickPathActionClick)
            published.put("touch_injected", false)
            return markAwaitingVisual(published)
        }
        return injectTouch(displayId, centerX, centerY, published)
    }

    private fun injectTouch(displayId: Int, x: Int, y: Int, published: JSONObject): JSONObject {
        tapPixels(displayId, x, y, published)
        published.put("click_path", AgentModeClickPathTouch)
        published.put("touch_injected", injector != null && !published.has("pending_tap_x"))
        if (!published.has("action_click_attempted")) published.put("action_click_attempted", false)
        if (!published.has("action_click_returned")) published.put("action_click_returned", false)
        return markAwaitingVisual(published)
    }

    private fun markAwaitingVisual(published: JSONObject): JSONObject =
        published
            .put("awaiting_visual_confirmation", true)
            .put("ok", false)
            .put("confirmed", false)
            .put("status", "not_confirmed")
            .put("reason", AgentModeReasonNotConfirmed)

    private fun notFound(
        published: JSONObject,
        query: String,
        width: Int,
        height: Int,
        collected: List<Collected>,
    ): JSONObject {
        val nearby = nearbyJson(published, null, null)
        return published
            .put("ok", false)
            .put("confirmed", false)
            .put("reason", AgentModeReasonNodeNotFound)
            .put("errmsg", "No control matching '$query' on this display.")
            .put("nearby", nearby)
            .put("candidate_count", collected.size)
    }

    private fun publish(
        collected: List<Collected>,
        query: String,
        width: Int,
        height: Int,
        pin: Set<Int>,
    ): JSONObject {
        snapshotCounter += 1
        snapshotId = "s$snapshotCounter"
        val selection = selectAgentModeNodes(
            drafts = collected.map { it.draft },
            query = query,
            displayWidth = width,
            displayHeight = height,
            pinSourceIndexes = pin,
        )
        replaceCache(collected, selection)
        return JSONObject()
            .put("available", true)
            .put("source", source)
            .put("snapshot_id", snapshotId)
            .put("nodes", JSONArray(selection.serialized))
            .put("nodes_truncated", selection.truncated)
            .put("node_char_limit", AgentModeMaxNodeChars)
    }

    private fun replaceCache(collected: List<Collected>, selection: AgentModeNodeSelection) {
        cached.forEach { runCatching { it.info.recycle() } }
        cached = selection.nodes.mapNotNull { node ->
            val item = collected.getOrNull(node.sourceIndex) ?: return@mapNotNull null
            CachedNode(
                snapshotId = snapshotId,
                id = node.id,
                info = item.info,
                bounds = Rect(item.draft.boundsLeft, item.draft.boundsTop, item.draft.boundsRight, item.draft.boundsBottom),
                sourceIndex = node.sourceIndex,
                inWebView = item.inWebView,
            )
        }
    }

    private fun idFor(sourceIndex: Int): String =
        cached.firstOrNull { it.sourceIndex == sourceIndex }?.id.orEmpty()

    private fun tapPixels(displayId: Int, x: Int, y: Int, result: JSONObject) {
        val tapX = x.coerceAtLeast(0)
        val tapY = y.coerceAtLeast(0)
        val injector = injector
        if (injector != null) {
            injector.tap(displayId, tapX, tapY)
        } else {
            result.put("pending_tap_x", tapX)
            result.put("pending_tap_y", tapY)
        }
        result.put("tap_x", tapX)
        result.put("tap_y", tapY)
    }

    private fun pasteText(displayId: Int, text: String, result: JSONObject): String {
        val injector = injector
        if (injector != null) {
            val method = injector.paste(displayId, text)
            result.put("text_input_method", method)
            return method
        }
        result.put("pending_paste", text)
        return "pending_paste"
    }

    private fun performClick(collected: List<Collected>, index: Int, width: Int, height: Int): String? {
        val targets = clickableChain(collected, index, width, height)
        for (target in targets) {
            if (target.info.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return AgentModeReasonActionClick
            }
        }
        val focusTarget = targets.firstOrNull() ?: collected.getOrNull(index) ?: return null
        if (focusTarget.info.performAction(AccessibilityNodeInfo.ACTION_FOCUS) &&
            focusTarget.info.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        ) {
            return AgentModeReasonActionClick
        }
        return null
    }

    private fun clickableChain(
        collected: List<Collected>,
        index: Int,
        width: Int,
        height: Int,
    ): List<Collected> {
        val chain = mutableListOf<Collected>()
        var current = index
        var hops = 0
        while (current >= 0 && hops < 12 && current < collected.size) {
            val item = collected[current]
            if (item.draft.clickable && !isHuge(item.draft, width, height)) chain.add(item)
            current = item.parentIndex
            hops += 1
        }
        if (chain.isEmpty()) collected.getOrNull(index)?.let(chain::add)
        return chain
    }

    private fun nodeAt(collected: List<Collected>, x: Int, y: Int, width: Int, height: Int): Int? =
        collected.indices
            .filter { index ->
                val draft = collected[index].draft
                draft.interactive &&
                    !isHuge(draft, width, height) &&
                    x in draft.boundsLeft..draft.boundsRight &&
                    y in draft.boundsTop..draft.boundsBottom
            }
            .minByOrNull { collected[it].draft.area }

    private fun bestMatch(collected: List<Collected>, query: String, editableOnly: Boolean): Int? =
        collected.indices
            .mapNotNull { index ->
                val draft = collected[index].draft
                if (editableOnly && !draft.editable) return@mapNotNull null
                val rank = agentModeMatchRank(draft, query) ?: return@mapNotNull null
                Triple(index, rank, draft.area)
            }
            .minWithOrNull(compareBy<Triple<Int, Int, Int>> { it.second }.thenBy { it.third })
            ?.first

    private fun editableTarget(
        collected: List<Collected>,
        query: String,
        width: Int,
        height: Int,
    ): Int? {
        if (query.isNotBlank()) {
            bestMatch(collected, query, editableOnly = true)?.let { return it }
            val label = bestMatch(collected, query, editableOnly = false) ?: return null
            return nearestEditable(collected, label, width, height)
        }
        val focused = collected.indexOfFirst { it.info.isFocused && it.draft.editable }
        if (focused >= 0) return focused
        val editables = collected.indices.filter {
            collected[it].draft.editable && !isHuge(collected[it].draft, width, height)
        }
        return editables.maxByOrNull { collected[it].draft.boundsTop }
    }

    private fun nearestEditable(
        collected: List<Collected>,
        anchor: Int,
        width: Int,
        height: Int,
    ): Int? {
        val origin = collected[anchor].draft
        return collected.indices
            .filter { collected[it].draft.editable && !isHuge(collected[it].draft, width, height) }
            .minByOrNull { index ->
                val draft = collected[index].draft
                kotlin.math.abs(draft.centerPixelX - origin.centerPixelX) +
                    kotlin.math.abs(draft.centerPixelY - origin.centerPixelY)
            }
    }

    private fun nearbyJson(published: JSONObject, normalizedX: Int?, normalizedY: Int?): JSONArray {
        val nodes = com.zhousl.aether.data.parseAgentModeNodes(published.optJSONArray("nodes"))
        return agentModeNodesJson(agentModeNearbyNodes(nodes, normalizedX, normalizedY))
    }

    private fun collect(windows: List<AccessibilityWindowInfo>): List<Collected> {
        val collected = mutableListOf<Collected>()
        for (window in windows) {
            val root = window.agentModeRoot() ?: continue
            root.refresh()
            walk(root, parentIndex = -1, underWebView = false, collected)
        }
        return collected
    }

    private fun walk(
        node: AccessibilityNodeInfo,
        parentIndex: Int,
        underWebView: Boolean,
        collected: MutableList<Collected>,
    ) {
        if (collected.size >= MaxCollectedNodes) {
            node.recycle()
            return
        }
        val inWebView = underWebView || agentModeClassLooksLikeWebView(node.className?.toString().orEmpty())
        // isVisibleToUser is often false for every node on a virtual display, which used to
        // drop the whole WeChat tree. Interactive nodes and nodes with text are kept anyway.
        val keep = collected.size < MaxCollectedNodes && (
            node.isVisibleToUser ||
                node.isEditable ||
                node.isClickable ||
                node.isFocusable ||
                node.isScrollable ||
                inWebView ||
                !node.text.isNullOrBlank() ||
                !node.contentDescription.isNullOrBlank()
            )
        val index = if (keep) {
            collected.add(
                Collected(
                    info = node,
                    draft = draftOf(node),
                    parentIndex = parentIndex,
                    inWebView = inWebView,
                ),
            )
            collected.lastIndex
        } else {
            parentIndex
        }
        for (childIndex in 0 until node.childCount) {
            val child = node.getChild(childIndex) ?: continue
            walk(child, if (keep) index else parentIndex, inWebView, collected)
        }
        if (!keep) node.recycle()
    }

    private fun draftOf(node: AccessibilityNodeInfo): AgentModeNodeDraft {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        return AgentModeNodeDraft(
            type = node.className?.toString().orEmpty(),
            text = node.text?.toString()?.trim().orEmpty(),
            description = node.contentDescription?.toString()?.trim().orEmpty(),
            editable = node.isEditable,
            clickable = node.isClickable,
            focusable = node.isFocusable,
            scrollable = node.isScrollable,
            boundsLeft = bounds.left,
            boundsTop = bounds.top,
            boundsRight = bounds.right,
            boundsBottom = bounds.bottom,
            viewId = node.viewIdResourceName.orEmpty(),
        )
    }

    private fun isHuge(draft: AgentModeNodeDraft, width: Int, height: Int): Boolean {
        val screen = width.toLong() * height.toLong()
        if (screen <= 0L) return false
        return draft.area.toLong() * 10L > screen * 7L
    }

    private fun settle() {
        SystemClock.sleep(200)
    }

    private fun failure(available: Boolean, reason: String, message: String): String =
        jsonFailure(available, reason, message).toString()

    private fun jsonFailure(available: Boolean, reason: String, message: String): JSONObject =
        JSONObject()
            .put("available", available)
            .put("ok", false)
            .put("confirmed", false)
            .put("source", source)
            .put("reason", reason)
            .put("errmsg", message)

    private class Collected(
        val info: AccessibilityNodeInfo,
        val draft: AgentModeNodeDraft,
        val parentIndex: Int,
        val inWebView: Boolean,
    )

    private data class CachedNode(
        val snapshotId: String,
        val id: String,
        val info: AccessibilityNodeInfo,
        val bounds: Rect,
        val sourceIndex: Int,
        val inWebView: Boolean,
    )

    private companion object {
        const val MaxCollectedNodes = 4000
    }
}
