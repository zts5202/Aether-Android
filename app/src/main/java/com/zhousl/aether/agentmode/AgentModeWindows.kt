package com.zhousl.aether.agentmode

import android.os.Build
import android.util.SparseArray
import android.view.accessibility.AccessibilityWindowInfo
import com.zhousl.aether.data.AgentModeWindowCandidate
import com.zhousl.aether.data.selectAgentModeWindowIndexes

internal data class AgentModeWindowBatch(
    val windows: List<AccessibilityWindowInfo>,
    val foreignDisplayIds: List<Int> = emptyList(),
)

/**
 * Accepts a window only when its own display id is [requestedDisplayId].
 * Windows filed under the right map key but reporting another display are dropped and recycled.
 */
internal fun windowsForRequestedDisplay(
    requestedDisplayId: Int,
    buckets: SparseArray<List<AccessibilityWindowInfo>>?,
): AgentModeWindowBatch? {
    if (buckets == null) return null
    val flat = mutableListOf<Pair<Int, AccessibilityWindowInfo>>()
    for (index in 0 until buckets.size()) {
        val key = buckets.keyAt(index)
        buckets.valueAt(index).orEmpty().forEach { window -> flat += key to window }
    }
    val selection = selectAgentModeWindowIndexes(
        requestedDisplayId,
        flat.map { (key, window) ->
            val reported = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.displayId
            } else {
                key
            }
            AgentModeWindowCandidate(mapKey = key, displayId = reported)
        },
    )
    val acceptedIndexes = selection.acceptedIndexes.toSet()
    val accepted = selection.acceptedIndexes.map { flat[it].second }
    flat.forEachIndexed { index, (_, window) ->
        if (index !in acceptedIndexes) runCatching { window.recycle() }
    }
    return AgentModeWindowBatch(accepted, selection.foreignDisplayIds)
}
