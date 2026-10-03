package com.zhousl.aether.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlpineGuestResolverTest {

    @Test
    fun guestResolverUsesTheHostResolvers() {
        assertEquals(
            "nameserver 58.240.57.33\n" +
                "nameserver 221.6.4.66\n" +
                "options timeout:2 attempts:2",
            guestResolverContents(listOf("58.240.57.33", "221.6.4.66")),
        )
    }

    @Test
    fun guestResolverPrefersAnActiveVpnResolver() {
        assertEquals(
            "nameserver 172.19.0.2\noptions timeout:2 attempts:2",
            guestResolverContents(listOf("172.19.0.2")),
        )
    }

    @Test
    fun guestResolverFallsBackWhenTheHostReportsNothing() {
        val contents = guestResolverContents(emptyList())

        assertTrue(contents.contains("nameserver 1.1.1.1"))
        assertTrue(contents.contains("nameserver 8.8.8.8"))
    }

    @Test
    fun legacyHardCodedResolverIsAdoptedSoItCanBeReplaced() {
        // Earlier releases wrote these unreachable resolvers. They must not be treated as a
        // deliberate configuration, or guest DNS stays broken on every network that blocks them.
        val legacy = "nameserver 1.1.1.1\n" +
            "nameserver 8.8.8.8\n" +
            "options timeout:2 attempts:2"

        assertTrue(isManagedGuestResolver(legacy))
    }

    @Test
    fun handWrittenResolverConfigurationIsLeftAlone() {
        assertFalse(isManagedGuestResolver("nameserver 10.0.0.1\nsearch internal.example"))
        assertFalse(isManagedGuestResolver("nameserver 10.0.0.1\noptions ndots:5"))
    }
}
