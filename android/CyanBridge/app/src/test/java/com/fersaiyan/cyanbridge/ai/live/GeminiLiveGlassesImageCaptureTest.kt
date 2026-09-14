package com.fersaiyan.cyanbridge.ai.live

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class GeminiLiveGlassesImageCaptureTest {

    @Test
    fun reassembleFragments_skipsPrefixAndDuplicateBlocks() {
        val payloadA = byteArrayOf(
            0x00, 0x01, 0x02, 0x03,
        )
        val jpegHeader = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(),
            0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10,
            0x4A.toByte(), 0x46.toByte(), 0x49.toByte(), 0x46.toByte(),
            0x00, 0x01, 0x01, 0x00, 0x00, 0x01,
        )
        val payloadB = byteArrayOf(
            0x00, 0x02, 0x03, 0x04,
        )
        val payloadC = byteArrayOf(
            0xFF.toByte(), 0xD9.toByte(),
        )

        val fragments = linkedMapOf(
            1 to payloadA,
            2 to jpegHeader + payloadB,
            3 to payloadB,
            4 to payloadC,
        )

        val actual = GeminiLiveGlassesImageCapture().reassembleFragments(fragments)
        val expected = jpegHeader + payloadB + payloadC

        assertArrayEquals(expected, actual)
    }

    @Test
    fun reassembleFragments_ignoresRepeatedPrefixJunkAfterSoI() {
        val jpegHeader = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(),
            0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10,
            0x4A.toByte(), 0x46.toByte(), 0x49.toByte(), 0x46.toByte(),
            0x00, 0x01, 0x01, 0x00, 0x00, 0x01,
        )
        val junk = byteArrayOf(
            0x00, 0x01, 0x02, 0x03,
        )
        val payload = byteArrayOf(
            0x10, 0x20, 0x30,
        )
        val eoi = byteArrayOf(
            0xFF.toByte(), 0xD9.toByte(),
        )

        val fragments = linkedMapOf(
            1 to junk,
            2 to jpegHeader + payload,
            3 to junk,
            4 to payload,
            5 to eoi,
        )

        val actual = GeminiLiveGlassesImageCapture().reassembleFragments(fragments)
        val expected = jpegHeader + payload + eoi

        assertArrayEquals(expected, actual)
    }

    @Test
    fun reassembleFragments_mergesOverlapBetweenAdjacentChunks() {
        val jpegHeader = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(),
            0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10,
            0x4A.toByte(), 0x46.toByte(), 0x49.toByte(), 0x46.toByte(),
        )
        val firstChunk = jpegHeader + byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05)
        val secondChunk = byteArrayOf(0x04, 0x05, 0x06, 0x07, 0xFF.toByte(), 0xD9.toByte())

        val fragments = linkedMapOf(
            1 to firstChunk,
            2 to secondChunk,
        )

        val actual = GeminiLiveGlassesImageCapture().reassembleFragments(fragments)
        val expected = jpegHeader + byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0xFF.toByte(), 0xD9.toByte())

        assertArrayEquals(expected, actual)
    }
}
