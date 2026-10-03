package com.zhousl.aether.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PiProviderCatalogModelIdTest {

    @Test
    fun deepSeekProviderUsesTheCurrentCanonicalModelId() {
        assertEquals("deepseek-flash", PiProviderCatalog.resolve("deepseek").defaultModelId)
    }

    @Test
    fun relatedProvidersDoNotPointAtRetiredModelIds() {
        assertEquals(
            "accounts/fireworks/models/deepseek-v4p1-flash",
            PiProviderCatalog.resolve("fireworks").defaultModelId,
        )
        assertEquals("deepseek-v4.1-flash", PiProviderCatalog.resolve("opencode-go").defaultModelId)
    }

    @Test
    fun retiredDeepSeekFlashIdsAreRewritten() {
        assertEquals("deepseek-flash", canonicalBuiltinModelId("deepseek", "deepseek-v4-flash"))
        assertEquals(
            "deepseek-flash",
            canonicalBuiltinModelId("deepseek", "deepseek-v4-flash-vision-exp"),
        )
        assertEquals(
            "accounts/fireworks/models/deepseek-v4p1-flash",
            canonicalBuiltinModelId(
                "fireworks",
                "accounts/fireworks/models/deepseek-v4-flash",
            ),
        )
        assertEquals(
            "deepseek-v4.1-flash",
            canonicalBuiltinModelId("opencode-go", "deepseek-v4-flash"),
        )
    }

    @Test
    fun currentAndUnrelatedIdsAreLeftAlone() {
        assertEquals("deepseek-flash", canonicalBuiltinModelId("deepseek", "deepseek-flash"))
        assertEquals("deepseek-v4-pro", canonicalBuiltinModelId("deepseek", "deepseek-v4-pro"))
        assertEquals("gpt-5.4", canonicalBuiltinModelId("openai", "gpt-5.4"))
    }

    @Test
    fun whitespaceIsTrimmedAndBlanksAreIgnored() {
        assertEquals("deepseek-flash", canonicalBuiltinModelId("deepseek", "  deepseek-v4-flash  "))
        assertEquals("", canonicalBuiltinModelId("deepseek", "   "))
    }

    @Test
    fun aliasesAreScopedToTheirOwnProvider() {
        // The same literal id is legitimate elsewhere and must not be rewritten there.
        assertEquals("deepseek-v4-flash", canonicalBuiltinModelId("openai", "deepseek-v4-flash"))
        assertEquals("deepseek-v4-flash", canonicalBuiltinModelId(null, "deepseek-v4-flash"))
    }

    @Test
    fun customProvidersAreNeverRewritten() {
        val custom = PiProviderCatalog.resolve(DefaultPiProviderId)
        assertFalse(custom.isBuiltIn)
        assertEquals("deepseek-v4-flash", custom.canonicalModelId("deepseek-v4-flash"))
    }

    @Test
    fun builtInDefinitionsRewriteTheirOwnAliases() {
        assertEquals(
            "deepseek-flash",
            PiProviderCatalog.resolve("deepseek").canonicalModelId("deepseek-v4-flash"),
        )
    }
}
