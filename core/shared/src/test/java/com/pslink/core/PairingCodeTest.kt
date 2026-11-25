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

    @Test fun websocketEndpointSupportsTrustedTlsAndRejectsUnsafeAuthority() {
        assertEquals("ws://192.0.2.1:8765/subtitle", PsLinkProtocol.socketUrl("192.0.2.1"))
        assertEquals("wss://tv.example/subtitle", PsLinkProtocol.socketUrl("https://tv.example"))
        assertEquals("wss://tv.example/subtitle", PsLinkProtocol.socketUrl("wss://tv.example"))
        assertEquals("wss://tv.example:9443/subtitle", PsLinkProtocol.socketUrl("wss://tv.example:9443/subtitle"))
        assertNull(PsLinkProtocol.socketUrl("wss://user@tv.example/subtitle"))
        assertNull(PsLinkProtocol.socketUrl("wss://tv.example/other"))
        assertNull(PsLinkProtocol.socketUrl("wss://tv.example/subtitle?token=x"))
        assertNull(PsLinkProtocol.socketUrl("javascript:alert(1)"))
    }
}
