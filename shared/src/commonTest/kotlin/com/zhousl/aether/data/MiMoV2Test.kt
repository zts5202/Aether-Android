package com.zhousl.aether.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MiMoV2Test {
    @Test
    fun chatApiThinkingTypeIsOnlyEnabledOrDisabled() {
        assertEquals("disabled", miMoThinkingType("off"))
        assertEquals("disabled", miMoThinkingType("none"))
        assertEquals("disabled", miMoThinkingType(" "))
        assertEquals("disabled", miMoThinkingType(null))
        assertEquals("enabled", miMoThinkingType("minimal"))
        assertEquals("enabled", miMoThinkingType("low"))
        assertEquals("enabled", miMoThinkingType("medium"))
        assertEquals("enabled", miMoThinkingType("high"))
        assertEquals("enabled", miMoThinkingType("xhigh"))
        assertEquals("enabled", miMoThinkingType("max"))
    }

    @Test
    fun imagePartsUseTheDocumentedDataUrl() {
        assertEquals(
            "data:image/png;base64,aGVsbG8=",
            miMoImageDataUrl(" image/png ", " aGVsbG8= "),
        )
        assertEquals(
            "data:application/octet-stream;base64,abc",
            miMoImageDataUrl("  ", "abc"),
        )
    }

    @Test
    fun flashStaysSelectableOnBuiltinXiaomiProviders() {
        val xiaomi = LlmProviderConfig(
            providerId = "xiaomi",
            name = "Xiaomi",
            piProviderId = "xiaomi",
            apiKey = "test-key",
            baseUrl = "https://api.xiaomimimo.com/v1",
            modelId = "mimo-v2.5-pro",
            manualModelIds = emptyList(),
            cachedModels = listOf("mimo-v2.5-pro"),
            enabledModelIds = listOf("mimo-v2.5-pro"),
        )

        assertEquals(
            listOf("mimo-v2.5-pro", MiMoV26FlashModelId),
            xiaomi.availableModels(),
        )
        assertEquals(
            listOf("mimo-v2.5-pro", MiMoV26FlashModelId),
            xiaomi.enabledModels(),
        )
        assertTrue(
            listOf(xiaomi).availableModelOptions()
                .single { it.modelId == MiMoV26FlashModelId }
                .supportsImageInput,
        )
        assertFalse(
            listOf(xiaomi).availableModelOptions()
                .single { it.modelId == "mimo-v2.5-pro" }
                .supportsImageInput,
        )
    }

    @Test
    fun otherProvidersDoNotGainTheFlashModel() {
        val openai = LlmProviderConfig(
            providerId = "openai",
            name = "OpenAI",
            piProviderId = "openai",
            apiKey = "test-key",
            baseUrl = "https://api.openai.com/v1",
            modelId = "gpt-5",
            manualModelIds = emptyList(),
            cachedModels = listOf("gpt-5"),
            enabledModelIds = listOf("gpt-5"),
        )

        assertEquals(listOf("gpt-5"), openai.availableModels())
        assertEquals(listOf("gpt-5"), openai.enabledModels())
        assertEquals(emptyList(), xiaomiEnsuredModelIds("openai"))
        assertEquals(null, miMoThinkingLevels("openai", MiMoV26FlashModelId))
        assertEquals(MiMoThinkingLevels, miMoThinkingLevels("xiaomi-token-plan-ams", MiMoV26FlashModelId))
    }
}
