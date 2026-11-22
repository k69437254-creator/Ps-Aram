package com.pslink.core

import org.junit.Assert.*
import org.junit.Test

class PairingCodeTest {
    @Test fun ascii() {
        assertEquals("001234", PairingCode.normalize("001234"))
    }

    @Test fun arabicDigits() {
        assertEquals("012345", PairingCode.normalize("٠١٢٣٤٥"))
    }

    @Test fun persianDigits() {
        assertEquals("012345", PairingCode.normalize("۰۱۲۳۴۵"))
    }

    @Test fun groupedCode() {
        assertEquals("123456", PairingCode.normalize("123 456"))
        assertEquals("123456", PairingCode.normalize("123-456"))
    }

    @Test fun rejectsInvalidCodes() {
        assertNull(PairingCode.normalize("12345"))
        assertNull(PairingCode.normalize("1234567"))
        assertNull(PairingCode.normalize("12A456"))
    }
}
