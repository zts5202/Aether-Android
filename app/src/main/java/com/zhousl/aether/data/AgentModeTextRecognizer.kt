package com.zhousl.aether.data

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * One recognized text run from the Agent Mode display, positioned in the pixel space of the exact
 * bitmap that was analyzed. Callers pass that bitmap's width/height when converting to normalized
 * 0..1000 coordinates, so element boxes always line up with the screenshot handed back to the model.
 */
internal enum class AgentModeOcrGranularity {
    ELEMENT,
    SYMBOL,
    LINE,
}

/** Pixel box copied off an ML Kit [Rect] so matching does not depend on Android view methods. */
internal data class AgentModeOcrBox(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    fun centerX(): Int = (left + right) / 2

    fun centerY(): Int = (top + bottom) / 2

    fun height(): Int = (bottom - top).coerceAtLeast(0)
}

internal fun Rect.toOcrBox(): AgentModeOcrBox = AgentModeOcrBox(left, top, right, bottom)

internal data class AgentModeTextElement(
    val text: String,
    val boundingBox: AgentModeOcrBox,
    val granularity: AgentModeOcrGranularity = AgentModeOcrGranularity.LINE,
)

internal data class AgentModeOcrPiece(
    val text: String,
    val box: AgentModeOcrBox,
    val symbols: List<AgentModeOcrPiece> = emptyList(),
)

internal data class AgentModeOcrLine(
    val text: String,
    val box: AgentModeOcrBox,
    val elements: List<AgentModeOcrPiece> = emptyList(),
)

/**
 * Offline text recognition for Agent Mode element lookup.
 *
 * Backed by the bundled ML Kit Chinese recognizer, so the model ships inside the APK and nothing is
 * downloaded or sent anywhere at runtime.
 */
internal object AgentModeTextRecognizer {

    private val recognizer: TextRecognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    /**
     * Recognizes lines, words, and symbols. A merged line such as the composer plus 发送 still
     * exposes the word's own box, which is what a tap uses.
     */
    suspend fun recognize(bitmap: Bitmap): List<AgentModeTextElement> {
        val result = recognizer.process(InputImage.fromBitmap(bitmap, 0)).awaitResult()
        val lines = result.textBlocks.flatMap { block -> block.lines }.mapNotNull { line ->
            val box = line.boundingBox?.toOcrBox() ?: return@mapNotNull null
            AgentModeOcrLine(
                text = line.text,
                box = box,
                elements = line.elements.mapNotNull { element ->
                    val elementBox = element.boundingBox?.toOcrBox() ?: return@mapNotNull null
                    AgentModeOcrPiece(
                        text = element.text,
                        box = elementBox,
                        symbols = element.symbols.mapNotNull { symbol ->
                            val symbolBox = symbol.boundingBox?.toOcrBox() ?: return@mapNotNull null
                            AgentModeOcrPiece(text = symbol.text, box = symbolBox)
                        },
                    )
                },
            )
        }
        return expandOcrElements(lines)
    }
}

internal fun expandOcrElements(lines: List<AgentModeOcrLine>): List<AgentModeTextElement> {
    val expanded = mutableListOf<AgentModeTextElement>()
    lines.forEach { line ->
        val lineText = line.text.trim()
        if (lineText.isNotEmpty()) {
            expanded += AgentModeTextElement(lineText, line.box, AgentModeOcrGranularity.LINE)
        }
        line.elements.forEach { element ->
            val elementText = element.text.trim()
            if (elementText.isNotEmpty()) {
                expanded += AgentModeTextElement(elementText, element.box, AgentModeOcrGranularity.ELEMENT)
            }
            element.symbols.forEach { symbol ->
                val symbolText = symbol.text.trim()
                if (symbolText.isNotEmpty()) {
                    expanded += AgentModeTextElement(symbolText, symbol.box, AgentModeOcrGranularity.SYMBOL)
                }
            }
        }
    }
    return expanded
}

/**
 * Picks the box to tap for [query]. An exact word or symbol run wins. A merged line that only
 * contains the query is not tapped, and a copy of the query inside the composer text is ignored
 * when it is not the trailing control.
 */
internal fun selectOcrTapTarget(
    elements: List<AgentModeTextElement>,
    query: String,
): AgentModeTextElement? {
    val needle = query.trim()
    if (needle.isEmpty()) return null
    val exact = elements.filter {
        it.granularity != AgentModeOcrGranularity.LINE &&
            it.text.trim().equals(needle, ignoreCase = true) &&
            !ocrMatchInsideComposer(it, elements, needle)
    }
    if (exact.isNotEmpty()) {
        return exact.minWith(compareBy<AgentModeTextElement> { it.granularity.ordinal }.thenBy { ocrBoxArea(it) })
    }
    val assembled = assembleOcrSymbolRun(elements, needle)
    if (assembled != null && !ocrMatchInsideComposer(assembled, elements, needle)) return assembled
    return elements
        .filter {
            it.granularity == AgentModeOcrGranularity.LINE &&
                it.text.trim().equals(needle, ignoreCase = true)
        }
        .minByOrNull { ocrBoxArea(it) }
}

private fun ocrMatchInsideComposer(
    candidate: AgentModeTextElement,
    elements: List<AgentModeTextElement>,
    needle: String,
): Boolean {
    val container = elements
        .filter { line ->
            line.granularity == AgentModeOcrGranularity.LINE &&
                line.text.contains(needle, ignoreCase = true) &&
                line.text.trim().length > needle.length + 1 &&
                ocrBoxContains(line.boundingBox, candidate.boundingBox)
        }
        .maxByOrNull { ocrBoxArea(it) }
        ?: return false
    return !ocrBoxIsTrailing(container.boundingBox, candidate.boundingBox)
}

private fun assembleOcrSymbolRun(
    elements: List<AgentModeTextElement>,
    needle: String,
): AgentModeTextElement? {
    val symbols = elements
        .filter { it.granularity == AgentModeOcrGranularity.SYMBOL && it.text.isNotBlank() }
        .sortedWith(compareBy({ it.boundingBox.top }, { it.boundingBox.left }))
    if (symbols.isEmpty()) return null
    var best: AgentModeTextElement? = null
    for (start in symbols.indices) {
        val joined = StringBuilder()
        var left = Int.MAX_VALUE
        var top = Int.MAX_VALUE
        var right = Int.MIN_VALUE
        var bottom = Int.MIN_VALUE
        var previous: AgentModeTextElement? = null
        for (index in start until symbols.size) {
            val symbol = symbols[index]
            val prior = previous
            if (prior != null && !ocrSymbolsAdjacent(prior, symbol)) break
            val next = joined.toString() + symbol.text.trim()
            if (!needle.startsWith(next, ignoreCase = true)) break
            joined.append(symbol.text.trim())
            left = minOf(left, symbol.boundingBox.left)
            top = minOf(top, symbol.boundingBox.top)
            right = maxOf(right, symbol.boundingBox.right)
            bottom = maxOf(bottom, symbol.boundingBox.bottom)
            previous = symbol
            if (joined.toString().equals(needle, ignoreCase = true)) {
                val candidate = AgentModeTextElement(
                    needle,
                    AgentModeOcrBox(left, top, right, bottom),
                    AgentModeOcrGranularity.SYMBOL,
                )
                if (best == null || candidate.boundingBox.left > best.boundingBox.left) best = candidate
                break
            }
        }
    }
    return best
}

private fun ocrSymbolsAdjacent(left: AgentModeTextElement, right: AgentModeTextElement): Boolean {
    val overlap = minOf(left.boundingBox.bottom, right.boundingBox.bottom) -
        maxOf(left.boundingBox.top, right.boundingBox.top)
    val minHeight = minOf(left.boundingBox.height(), right.boundingBox.height()).coerceAtLeast(1)
    if (overlap < minHeight / 2) return false
    return right.boundingBox.left - left.boundingBox.right <= minHeight
}

private fun ocrBoxContains(outer: AgentModeOcrBox, inner: AgentModeOcrBox): Boolean =
    inner.left >= outer.left - 2 &&
        inner.right <= outer.right + 2 &&
        inner.top >= outer.top - 2 &&
        inner.bottom <= outer.bottom + 2

private fun ocrBoxIsTrailing(container: AgentModeOcrBox, candidate: AgentModeOcrBox): Boolean {
    val span = (container.right - container.left).coerceAtLeast(1)
    return candidate.right >= container.right - span / 5 && candidate.left >= container.left + span / 2
}

private fun ocrBoxArea(element: AgentModeTextElement): Int {
    val box = element.boundingBox
    return (box.right - box.left).coerceAtLeast(0) * (box.bottom - box.top).coerceAtLeast(0)
}

private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { value ->
        if (continuation.isActive) continuation.resume(value)
    }
    addOnFailureListener { error ->
        if (continuation.isActive) continuation.resumeWithException(error)
    }
}
