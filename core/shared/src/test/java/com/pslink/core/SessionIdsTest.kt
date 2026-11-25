package com.pslink.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionIdsTest {
    @Test
    fun validatesSessionIdLengthBeforeAnyTruncation() {
        assertEquals("a", SessionIds.validate("a"))
        assertEquals("x".repeat(SessionIds.MAX_LENGTH), SessionIds.validate("x".repeat(SessionIds.MAX_LENGTH)))
        assertNull(SessionIds.validate("x".repeat(SessionIds.MAX_LENGTH + 1)))
        assertNull(SessionIds.validate("   "))
    }
}
