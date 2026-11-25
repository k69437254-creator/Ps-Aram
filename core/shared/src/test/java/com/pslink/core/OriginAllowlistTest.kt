package com.pslink.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OriginAllowlistTest {
    @Test
    fun allowsOriginlessNativeClientsAndConfiguredBrowserOrigins() {
        val policy = OriginAllowlist("https://tv.example, http://192.0.2.10:8080")

        assertTrue(policy.allows(null))
        assertTrue(policy.allows("https://tv.example"))
        assertTrue(policy.allows("https://tv.example/"))
        assertTrue(policy.allows("https://TV.EXAMPLE:443"))
        assertTrue(policy.allows("http://192.0.2.10:8080"))
    }

    @Test
    fun rejectsUnconfiguredOrMalformedOrigins() {
        val policy = OriginAllowlist("https://tv.example")

        assertFalse(policy.allows("https://attacker.example"))
        assertFalse(policy.allows("null"))
        assertFalse(policy.allows("https://tv.example/path"))
        assertFalse(policy.allows("https://user@tv.example"))
        assertFalse(policy.allows("https://tv.example, https://attacker.example"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMalformedAllowlistConfiguration() {
        OriginAllowlist("https://tv.example/path")
    }
}
