// SPDX-License-Identifier: AGPL-3.0-only
package com.pslink.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StableTextGateTest {
    @Test
    fun emitsOnlyAfterStableRepeatedObservation() {
        val gate = StableTextGate(requiredMatches = 2)
        assertNull(gate.offer("  Press   start ", 100L))
        assertEquals("Press start", gate.offer("Press start", 200L))
    }

    @Test
    fun suppressesDuplicatesInsideWindowAndAllowsThemAfterWindow() {
        val gate = StableTextGate(requiredMatches = 1, duplicateWindowMs = 3_500L)
        assertEquals("Continue", gate.offer("Continue", 1_000L))
        assertNull(gate.offer("Continue", 2_000L))
        assertEquals("Continue", gate.offer("Continue", 4_501L))
    }

    @Test
    fun blankObservationResetsCandidateAndNewTextStartsAgain() {
        val gate = StableTextGate(requiredMatches = 2)
        assertNull(gate.offer("A", 1L))
        assertNull(gate.offer("   ", 2L))
        assertNull(gate.offer("B", 3L))
        assertEquals("B", gate.offer("B", 4L))
    }
}
