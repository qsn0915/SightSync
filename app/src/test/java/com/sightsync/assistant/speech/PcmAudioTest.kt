package com.sightsync.assistant.speech

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmAudioTest {
    @Test
    fun wavEncoderWritesRiffWaveHeaderAndPcmData() {
        val wav = WavEncoder.encodePcm16Mono(
            samples = shortArrayOf(1, -2),
            sampleRate = 16_000,
        )

        assertArrayEquals(byteArrayOf('R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte()), wav.sliceArray(0..3))
        assertArrayEquals(byteArrayOf('W'.code.toByte(), 'A'.code.toByte(), 'V'.code.toByte(), 'E'.code.toByte()), wav.sliceArray(8..11))
        assertArrayEquals(byteArrayOf('d'.code.toByte(), 'a'.code.toByte(), 't'.code.toByte(), 'a'.code.toByte()), wav.sliceArray(36..39))
        assertEquals(40, wav.littleEndianInt(offset = 4))
        assertEquals(16_000, wav.littleEndianInt(offset = 24))
        assertEquals(4, wav.littleEndianInt(offset = 40))
        assertEquals(1, wav.littleEndianShort(offset = 44).toInt())
        assertEquals(-2, wav.littleEndianShort(offset = 46).toInt())
    }

    @Test
    fun frameEnergyUsesMaximumAbsolutePcmAmplitude() {
        val samples = shortArrayOf(0, -1200, 300, Short.MIN_VALUE)

        assertEquals(32768, PcmFrameEnergy.maxAmplitude(samples, samples.size))
    }

    @Test
    fun recorderSourceUsesAudioRecordAndReturnsWav() {
        val source = java.io.File("src/main/java/com/sightsync/assistant/speech/ShortAudioRecorder.kt").readText()

        assertTrue(source.contains("AudioRecord"))
        assertTrue(source.contains("\"audio/wav\""))
    }

    private fun ByteArray.littleEndianInt(offset: Int): Int =
        ByteBuffer.wrap(this, offset, Int.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).int

    private fun ByteArray.littleEndianShort(offset: Int): Short =
        ByteBuffer.wrap(this, offset, Short.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).short
}
