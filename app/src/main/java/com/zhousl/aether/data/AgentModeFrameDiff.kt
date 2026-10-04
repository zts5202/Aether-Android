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
