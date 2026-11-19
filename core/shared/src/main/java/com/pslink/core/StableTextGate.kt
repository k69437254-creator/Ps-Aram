// SPDX-License-Identifier: AGPL-3.0-only
package com.pslink.core

/** Filters repeated OCR observations before they are translated or sent to the TV. */
class StableTextGate(
    private val requiredMatches: Int = 2,
    private val duplicateWindowMs: Long = 3_500L
) {
    init {
        require(requiredMatches >= 1)
        require(duplicateWindowMs > 0)
    }

    private var candidate = ""
    private var matches = 0
    private var lastEmission = ""
    private var lastEmissionAt = 0L

    @Synchronized
    fun offer(rawText: String, nowMs: Long = System.currentTimeMillis()): String? {
        val normalized = rawText.replace(Regex("\\s+"), " ").trim()
        if (normalized.isBlank()) {
            candidate = ""
            matches = 0
            return null
        }

        if (normalized == candidate) matches++ else {
            candidate = normalized
            matches = 1
        }

        if (matches < requiredMatches) return null
        if (normalized == lastEmission && nowMs - lastEmissionAt < duplicateWindowMs) return null

        lastEmission = normalized
        lastEmissionAt = nowMs
        return normalized
    }

    @Synchronized
    fun reset() {
        candidate = ""
        matches = 0
        lastEmission = ""
        lastEmissionAt = 0L
    }
}
