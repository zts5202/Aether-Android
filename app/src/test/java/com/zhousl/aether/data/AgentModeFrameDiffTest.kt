package com.zhousl.aether.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun aChangeBesideTheTapCountsAndADistantCellDoesNot() {
        val columns = 32
        val rows = 64
        val cells = agentModeRegionCellIndexes(915, 966, columns, rows)
        assertTrue(cells.isNotEmpty())
        assertFalse(0 in cells)
        val before = IntArray(columns * rows) { 100 }
        val changedInside = before.copyOf()
        changedInside[cells.first()] = 200
        assertTrue(agentModeRegionChanged(before, changedInside, cells))
        val changedCorner = before.copyOf()
        changedCorner[0] = 200
        assertFalse(agentModeRegionChanged(before, changedCorner, cells))
    }

    @Test
    fun ocrConfirmationFlipsOnlyWhenTheTargetAppearsOrDisappears() {
        assertTrue(agentModeOcrTargetChanged("发送", listOf("发送"), listOf("已发送")))
        assertFalse(agentModeOcrTargetChanged("发送", listOf("发送"), listOf("发送", "更多")))
        assertTrue(agentModeTextVisible("你好", listOf("你好")))
        assertTrue(agentModeTextVisible("你好世界", listOf("前缀你好世界后缀")))
    }
}
