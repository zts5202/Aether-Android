package com.zhousl.aether.data

import kotlin.math.abs

/**
 * Averages every pixel of an ARGB frame into a [columns] x [rows] grid of 0..255 gray levels.
 *
 * Each cell is the true mean of the pixels it covers. Bitmap scaling cannot be used for this: a
 * large downscale samples only a few source pixels per output pixel, so a small widget (a toggle
 * switch) can change without moving any sampled pixel.
 */
internal fun areaAveragedGrayGrid(
    pixels: IntArray,
    width: Int,
    height: Int,
    columns: Int,
    rows: Int,
): IntArray {
    require(width > 0 && height > 0 && pixels.size >= width * height) { "Frame size does not match pixels." }
    val sums = LongArray(columns * rows)
    val counts = IntArray(columns * rows)
    for (y in 0 until height) {
        val rowOffset = (y.toLong() * rows / height).toInt() * columns
        val pixelOffset = y * width
        for (x in 0 until width) {
            val pixel = pixels[pixelOffset + x]
            val red = (pixel shr 16) and 0xFF
            val green = (pixel shr 8) and 0xFF
            val blue = pixel and 0xFF
            val cell = rowOffset + (x.toLong() * columns / width).toInt()
            sums[cell] += ((red * 299 + green * 587 + blue * 114) / 1000).toLong()
            counts[cell]++
        }
    }
    return IntArray(columns * rows) { cell ->
        if (counts[cell] == 0) 0 else (sums[cell] / counts[cell]).toInt()
    }
}

/** Number of cells whose gray level moved by more than [tolerance]; 0 when sizes differ. */
internal fun changedGridCellCount(before: IntArray, after: IntArray, tolerance: Int): Int {
    if (before.size != after.size) return 0
    return before.indices.count { abs(before[it] - after[it]) > tolerance }
}

internal fun maxGridCellDifference(before: IntArray, after: IntArray): Int {
    if (before.size != after.size) return 0
    return before.indices.maxOfOrNull { abs(before[it] - after[it]) } ?: 0
}

/** Grid cells that overlap a box of [radius] normalized units around one tap point. */
internal fun agentModeRegionCellIndexes(
    normalizedX: Int,
    normalizedY: Int,
    columns: Int,
    rows: Int,
    radius: Int = 80,
): List<Int> {
    if (columns <= 0 || rows <= 0) return emptyList()
    val left = (normalizedX - radius).coerceIn(0, 1000)
    val top = (normalizedY - radius).coerceIn(0, 1000)
    val right = (normalizedX + radius).coerceIn(0, 1000)
    val bottom = (normalizedY + radius).coerceIn(0, 1000)
    return buildList {
        for (row in 0 until rows) {
            val cellTop = row * 1000 / rows
            val cellBottom = (row + 1) * 1000 / rows
            if (cellBottom <= top || cellTop >= bottom) continue
            for (column in 0 until columns) {
                val cellLeft = column * 1000 / columns
                val cellRight = (column + 1) * 1000 / columns
                if (cellRight <= left || cellLeft >= right) continue
                add(row * columns + column)
            }
        }
    }
}

/** True when a cell inside the tapped area moved by more than [tolerance] gray levels. */
internal fun agentModeRegionChanged(
    before: IntArray,
    after: IntArray,
    cellIndexes: List<Int>,
    tolerance: Int = 12,
): Boolean {
    if (before.size != after.size || cellIndexes.isEmpty()) return false
    return cellIndexes.any { index ->
        index in before.indices && abs(before[index] - after[index]) > tolerance
    }
}

/** The target text appeared or disappeared. Unchanged surrounding lines do not count. */
internal fun agentModeOcrTargetChanged(
    query: String,
    before: List<String>,
    after: List<String>,
): Boolean {
    val needle = query.trim()
    if (needle.isEmpty()) return false
    fun has(lines: List<String>) = lines.any { agentModeLineMatches(it, needle) }
    return has(before) != has(after)
}

internal fun agentModeTextVisible(text: String, lines: List<String>): Boolean {
    val needle = text.trim()
    if (needle.isEmpty()) return false
    return lines.any { agentModeLineMatches(it, needle) }
}

/**
 * A short label such as 发送 matches the whole line only, so 已发送 is a different label.
 * A longer string may sit inside a longer OCR line.
 */
private fun agentModeLineMatches(line: String, needle: String): Boolean {
    val trimmed = line.trim()
    if (trimmed.equals(needle, ignoreCase = true)) return true
    return needle.length >= 4 && trimmed.contains(needle, ignoreCase = true)
}
