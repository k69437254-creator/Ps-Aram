// SPDX-License-Identifier: AGPL-3.0-only
package com.pslink.mobile.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HlsAacAdtsHeaderTest {
    @Test fun declaresActual44100HzMonoSourceInAdtsHeader() {
        val fixture = javaClass.classLoader!!.getResourceAsStream("fixtures/hls-single-idr.h264")!!.use { it.readBytes() }
        val output = File(requireNotNull(System.getProperty("pslink.hls.test.outputDir")), "dynamic-aac-format")
        output.deleteRecursively()
        assertTrue(output.mkdirs() || output.isDirectory)
        val publisher = HlsTransportStreamPublisher(output, playlistRenamer = ::atomicRenameForJvm)
        publisher.start()
        publisher.writeAccessUnit(fixture, ptsUs = 0L, keyFrame = true)
        publisher.writeAudioAccessUnit(byteArrayOf(0x11, 0x22, 0x33), 20_000L, 44_100, 1)
        publisher.stop()
        val playlist = File(output, "index.m3u8").readText()
        val name = Regex("pslink-segment-[0-9]{6}\\.ts").find(playlist)?.value ?: error("missing TS segment")
        val bytes = File(output, name).readBytes().map { it.toInt() and 0xFF }
        // ADTS header: AAC-LC (profile 1), frequency index 4 (44.1 kHz), channel config 1 (mono).
        assertTrue(bytes.windowed(4).any { it == listOf(0xFF, 0xF1, 0x50, 0x40) })
        assertEquals(0, bytes.size % 188)
    }
}
