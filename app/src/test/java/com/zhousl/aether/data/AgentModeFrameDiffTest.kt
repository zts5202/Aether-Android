package com.zhousl.aether.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentModeFrameDiffTest {
    private val width = 588
    private val height = 1280
    private val white = 0xFFFFFFFF.toInt()
    private val pinkOn = 0xFFFB7299.toInt()
    private val greyOff = 0xFFE3E3E3.toInt()

    private fun frameWithToggle(color: Int): IntArray = IntArray(width * height) { index ->
        val x = index % width
        val y = index / width
        if (x in 522 until 570 && y in 638 until 666) color else white
    }

    private fun grid(pixels: IntArray) = areaAveragedGrayGrid(pixels, width, height, 32, 64)

    @Test
    fun aToggleSwitchFlipIsDetected() {
        val before = grid(frameWithToggle(pinkOn))
        val after = grid(frameWithToggle(greyOff))

        assertTrue(changedGridCellCount(before, after, tolerance = 4) > 0)
        assertTrue(maxGridCellDifference(before, after) > 20)
    }

    @Test
    fun anIdenticalFrameHasNoChangedCells() {
        val before = grid(frameWithToggle(pinkOn))
        val after = grid(frameWithToggle(pinkOn))

        assertArrayEquals(before, after)
        assertEquals(0, changedGridCellCount(before, after, tolerance = 4))
    }

    @Test
    fun everyCellIsTheMeanOfItsPixels() {
        val pixels = IntArray(4) { if (it % 2 == 0) 0xFF000000.toInt() else white }

        assertArrayEquals(intArrayOf(127), areaAveragedGrayGrid(pixels, 2, 2, 1, 1))
        assertArrayEquals(intArrayOf(0, 255, 0, 255), areaAveragedGrayGrid(pixels, 2, 2, 2, 2))
    }
}
