package com.zhousl.aether.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProviderModelCapabilitiesTest {

    private val payload = """
        {
          "providers": [
            {
              "id": "deepseek",
              "name": "DeepSeek",
              "models": [
                {"id": "deepseek-flash", "input": ["text", "image"]},
                {"id": "deepseek-v4-pro", "input": ["text"]}
              ]
            },
            {
              "id": "openai",
              "name": "OpenAI",
              "models": [{"id": "gpt-5.4", "input": ["text", "image"]}]
            }
          ]
        }
    """.trimIndent()

    private fun parse(json: String): ProviderModelCapabilities =
        parseProviderModelCapabilities(Json.parseToJsonElement(json).jsonObject)

    @Test
    fun reportsImageInputPerProviderAndModel() {
        val capabilities = parse(payload)

        assertTrue(capabilities.supportsImageInput("deepseek", "deepseek-flash"))
        assertFalse(capabilities.supportsImageInput("deepseek", "deepseek-v4-pro"))
        assertTrue(capabilities.supportsImageInput("openai", "gpt-5.4"))
        assertEquals(3, capabilities.reportedModelCount)
    }

    @Test
    fun unreportedModelsStayPermissive() {
        val capabilities = parse(payload)

        // The kernel enables image input for every model missing from its static catalog, so an
        // absent entry means "not reported" and must never downgrade a model to text-only.
        assertTrue(capabilities.supportsImageInput("openai-compatible", "some-custom-model"))
        assertTrue(capabilities.supportsImageInput("deepseek", "deepseek-v5-unreleased"))
        assertTrue(capabilities.supportsImageInput(null, "deepseek-v4-pro"))
        assertTrue(capabilities.supportsImageInput("deepseek", "   "))
    }

    @Test
    fun isReportedDistinguishesUnreportedFromUnsupported() {
        val capabilities = parse(payload)

        assertTrue(capabilities.isReported("deepseek", "deepseek-v4-pro"))
        assertFalse(capabilities.isReported("deepseek", "deepseek-v5-unreleased"))
    }

    @Test
    fun stringOverloadParsesTheAndroidBridgePayload() {
        val capabilities = parseProviderModelCapabilities(payload)

        assertFalse(capabilities.supportsImageInput("deepseek", "deepseek-v4-pro"))
        assertTrue(capabilities.supportsImageInput("deepseek", "deepseek-flash"))
    }

    @Test
    fun miMoV26FlashStaysVisionCapableWhenTheCatalogSaysTextOnly() {
        val capabilities = parse(
            """
            {
              "providers": [
                {
                  "id": "xiaomi",
                  "models": [
                    {"id": "mimo-v2.6-flash", "input": ["text"]},
                    {"id": "mimo-v2.5", "input": ["text"]}
                  ]
                }
              ]
            }
            """.trimIndent(),
        )

        assertTrue(capabilities.supportsImageInput("xiaomi", "mimo-v2.6-flash"))
        assertTrue(capabilities.supportsImageInput("xiaomi-token-plan-sgp", "Xiaomi/MiMo-V2.6-Flash"))
        assertTrue(capabilities.supportsImageInput("xiaomi", "mimo-v2.6-pro"))
        assertTrue(capabilities.supportsImageInput("xiaomi", "mimo-v2.5"))
    }

    @Test
    fun miMoV25ProStaysTextOnlyWhenUnreportedOrMarkedAsVision() {
        val capabilities = parse(
            """
            {
              "providers": [
                {
                  "id": "xiaomi",
                  "models": [
                    {"id": "mimo-v2.5-pro", "input": ["text", "image"]}
                  ]
                }
              ]
            }
            """.trimIndent(),
        )

        assertFalse(capabilities.supportsImageInput("xiaomi", "mimo-v2.5-pro"))
        assertFalse(capabilities.supportsImageInput("xiaomi-token-plan-cn", "mimo-v2.5-pro-ultraspeed"))
        assertTrue(capabilities.supportsImageInput("openai", "some-custom-model"))
        assertTrue(capabilities.supportsImageInput("xiaomi", "not-a-mimo-model"))
    }

    @Test
    fun malformedPayloadsDegradeToEmpty() {
        assertEquals(0, parseProviderModelCapabilities("not json").reportedModelCount)
        assertEquals(0, parseProviderModelCapabilities("{}").reportedModelCount)
        assertEquals(0, parseProviderModelCapabilities("""{"providers":"nope"}""").reportedModelCount)
        assertEquals(0, ProviderModelCapabilities.Empty.reportedModelCount)
    }

    @Test
    fun modelOptionsCarryTheReportedCapability() {
        val config = LlmProviderConfig(
            id = "deepseek-1",
            providerId = "deepseek",
            name = "DeepSeek",
            piProviderId = "deepseek",
            apiKey = "key",
            baseUrl = "https://api.deepseek.com",
            modelId = "deepseek-flash",
            cachedModels = listOf("deepseek-flash", "deepseek-v4-pro"),
        )

        val options = listOf(config).availableModelOptions(capabilities = parse(payload))

        assertTrue(options.single { it.modelId == "deepseek-flash" }.supportsImageInput)
        assertFalse(options.single { it.modelId == "deepseek-v4-pro" }.supportsImageInput)
    }

    @Test
    fun modelOptionsDefaultToImageInputWithoutACatalog() {
        val config = LlmProviderConfig(
            id = "custom-1",
            providerId = "custom",
            name = "Custom",
            piProviderId = "openai-compatible",
            apiKey = "key",
            baseUrl = "https://example.test/v1",
            modelId = "local-vl",
        )

        assertTrue(listOf(config).availableModelOptions().single().supportsImageInput)
    }
}
