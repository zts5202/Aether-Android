package com.zhousl.aether.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentModeOcrSendTest {
    @Test
    fun elementInsideMergedLineIsTappedInsteadOfTheLine() {
        val line = element(
            "不要这样,今天不吃饭了。 (G发送",
            AgentModeOcrBox(19, 955, 957, 980),
            AgentModeOcrGranularity.LINE,
        )
        val send = element("发送", AgentModeOcrBox(900, 955, 957, 980), AgentModeOcrGranularity.ELEMENT)
        val chosen = selectOcrTapTarget(listOf(line, send), "发送")
        assertEquals(AgentModeOcrGranularity.ELEMENT, chosen?.granularity)
        assertEquals(900, chosen?.boundingBox?.left)
        assertEquals(957, chosen?.boundingBox?.right)
    }

    @Test
    fun trailingSendBeatsACopyInsideTheComposer() {
        val line = element(
            "今天不吃饭了。发送",
            AgentModeOcrBox(19, 955, 957, 980),
            AgentModeOcrGranularity.LINE,
        )
        val inside = element("发送", AgentModeOcrBox(200, 955, 280, 980), AgentModeOcrGranularity.ELEMENT)
        val trailing = element("发送", AgentModeOcrBox(900, 955, 957, 980), AgentModeOcrGranularity.ELEMENT)
        val chosen = selectOcrTapTarget(listOf(line, inside, trailing), "发送")
        assertEquals(900, chosen?.boundingBox?.left)
    }

    @Test
    fun mergedLineWithoutItsOwnWordIsNotTapped() {
        val line = element(
            "不要这样,今天不吃饭了。 (G发送",
            AgentModeOcrBox(19, 955, 957, 980),
            AgentModeOcrGranularity.LINE,
        )
        assertNull(selectOcrTapTarget(listOf(line), "发送"))
    }

    @Test
    fun adjacentSymbolsAssembleTheSendWordAtTheRight() {
        val line = element(
            "不要这样,今天不吃饭了。 (G发送",
            AgentModeOcrBox(19, 955, 957, 980),
            AgentModeOcrGranularity.LINE,
        )
        val fa = element("发", AgentModeOcrBox(900, 955, 928, 980), AgentModeOcrGranularity.SYMBOL)
        val song = element("送", AgentModeOcrBox(928, 955, 957, 980), AgentModeOcrGranularity.SYMBOL)
        val chosen = selectOcrTapTarget(listOf(line, fa, song), "发送")
        assertEquals(AgentModeOcrGranularity.SYMBOL, chosen?.granularity)
        assertEquals(900, chosen?.boundingBox?.left)
        assertEquals(957, chosen?.boundingBox?.right)
    }

    @Test
    fun symbolsInsideTheComposerAreIgnored() {
        val line = element(
            "不要这样,今天不吃饭了。 (G发送",
            AgentModeOcrBox(19, 955, 957, 980),
            AgentModeOcrGranularity.LINE,
        )
        val fa = element("发", AgentModeOcrBox(200, 955, 228, 980), AgentModeOcrGranularity.SYMBOL)
        val song = element("送", AgentModeOcrBox(228, 955, 256, 980), AgentModeOcrGranularity.SYMBOL)
        assertNull(selectOcrTapTarget(listOf(line, fa, song), "发送"))
    }

    @Test
    fun sendIsConfirmedOnlyWhenTheComposerClearsOrABubbleAppears() {
        val before = listOf(AgentModeVisibleLine("今天不吃饭了。", 960))
        val after = listOf(AgentModeVisibleLine("今天不吃饭了。", 700))
        val cleared = agentModeSendCheck("今天不吃饭了。", before, after)
        assertTrue(cleared.confirmed)
        assertEquals("composer_cleared", cleared.reason)

        val same = agentModeSendCheck("今天不吃饭了。", before, before)
        assertFalse(same.confirmed)
        assertEquals(AgentModeReasonUncertain, same.reason)

        val stillTyping = agentModeSendCheck(
            "今天不吃饭了。",
            before,
            listOf(AgentModeVisibleLine("今天不吃饭了。", 960)),
        )
        assertFalse(stillTyping.confirmed)
        assertFalse(agentModeSendCheck("", before, after).confirmed)
    }

    @Test
    fun sendGateBlocksEveryLaterSendUntilAScreenshot() {
        val gate = AgentModeSendGate()
        assertFalse(gate.shouldBlock("发送", null, null))
        gate.record()
        assertTrue(gate.shouldBlock("发送", null, null))
        assertTrue(gate.shouldBlock(null, 912, 968))
        assertFalse(gate.shouldBlock("微信", null, null))
        gate.noteScreenshot()
        assertFalse(gate.shouldBlock("发送", null, null))
        assertFalse(gate.shouldBlock(null, 912, 968))
    }

    @Test
    fun composerTextIgnoresChatHistory() {
        assertTrue(agentModeComposerHasText("我需要你", listOf(AgentModeVisibleLine("我需要你", 960))))
        assertFalse(agentModeComposerHasText("我需要你", listOf(AgentModeVisibleLine("我需要你", 700))))
        assertFalse(agentModeComposerHasText("", listOf(AgentModeVisibleLine("我需要你", 960))))
        assertTrue(agentModeComposerHasText("今天 不吃饭", listOf(AgentModeVisibleLine("今天不吃饭", 900))))
    }

    @Test
    fun composerFocusStaysLeftOfTheSendButton() {
        val field = element("说点什么", AgentModeOcrBox(40, 940, 700, 990), AgentModeOcrGranularity.LINE)
        val send = element("发送", AgentModeOcrBox(874, 960, 951, 977), AgentModeOcrGranularity.ELEMENT)
        val point = agentModeComposerFocusPoint(listOf(field, send), imageWidth = 1000, imageHeight = 1000)
        assertTrue(point.x <= 700)
        assertTrue(point.y >= AgentModeComposerBandTop)

        val onlySend = agentModeComposerFocusPoint(listOf(send), imageWidth = 1000, imageHeight = 1000)
        assertEquals(400, onlySend.x)
        assertEquals(960, onlySend.y)
        val missing = agentModeComposerFocusPoint(emptyList(), imageWidth = 0, imageHeight = 0)
        assertEquals(400, missing.x)
        assertEquals(960, missing.y)
    }

    @Test
    fun mergedOcrLineIsRejectedAndARealMessageIsNot() {
        assertTrue(agentModeInputLooksLikeMergedOcr("不要这样,今天不吃饭了。 (G发送"))
        assertFalse(agentModeInputLooksLikeMergedOcr("今天不吃饭了。"))
        assertFalse(agentModeInputLooksLikeMergedOcr("请发送文件"))
    }

    private fun element(
        text: String,
        box: AgentModeOcrBox,
        granularity: AgentModeOcrGranularity,
    ) = AgentModeTextElement(text, box, granularity)
}
