package com.sightsync.assistant.speech

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

object PcmFrameEnergy {
    fun maxAmplitude(samples: ShortArray, length: Int): Int {
        var max = 0
        for (index in 0 until length.coerceAtMost(samples.size)) {
            val amplitude = if (samples[index] == Short.MIN_VALUE) {
                32768
            } else {
                abs(samples[index].toInt())
            }
            if (amplitude > max) max = amplitude
        }
        return max
    }
}

object WavEncoder {
    fun encodePcm16Mono(
        samples: ShortArray,
        sampleRate: Int,
    ): ByteArray {
        val dataSize = samples.size * BYTES_PER_SAMPLE
        val output = ByteArrayOutputStream(WAV_HEADER_SIZE + dataSize)
        output.writeAscii("RIFF")
        output.writeIntLe(36 + dataSize)
        output.writeAscii("WAVE")
        output.writeAscii("fmt ")
        output.writeIntLe(16)
        output.writeShortLe(1)
        output.writeShortLe(1)
        output.writeIntLe(sampleRate)
        output.writeIntLe(sampleRate * BYTES_PER_SAMPLE)
        output.writeShortLe(BYTES_PER_SAMPLE)
        output.writeShortLe(16)
        output.writeAscii("data")
        output.writeIntLe(dataSize)
        samples.forEach { sample -> output.writeShortLe(sample.toInt()) }
        return output.toByteArray()
    }

    private fun ByteArrayOutputStream.writeAscii(value: String) {
        write(value.toByteArray(Charsets.US_ASCII))
    }

    private fun ByteArrayOutputStream.writeIntLe(value: Int) {
        write(ByteBuffer.allocate(Int.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array())
    }

    private fun ByteArrayOutputStream.writeShortLe(value: Int) {
        write(ByteBuffer.allocate(Short.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()).array())
    }

    private const val BYTES_PER_SAMPLE = 2
    private const val WAV_HEADER_SIZE = 44
}

class PcmSampleBuffer(initialCapacity: Int = 16_000) {
    private var samples = ShortArray(initialCapacity)
    var size: Int = 0
        private set

    fun append(source: ShortArray, length: Int) {
        ensureCapacity(size + length)
        source.copyInto(samples, destinationOffset = size, startIndex = 0, endIndex = length)
        size += length
    }

    fun toShortArray(): ShortArray = samples.copyOf(size)

    private fun ensureCapacity(required: Int) {
        if (required <= samples.size) return
        var newCapacity = samples.size
        while (newCapacity < required) {
            newCapacity *= 2
        }
        samples = samples.copyOf(newCapacity)
    }
}
