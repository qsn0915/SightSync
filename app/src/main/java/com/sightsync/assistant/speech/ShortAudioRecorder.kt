package com.sightsync.assistant.speech

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder.AudioSource
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

class ShortAudioRecorder(
    @Suppress("UNUSED_PARAMETER")
    context: Context,
    private val sampleRate: Int = 16_000,
    private val minDurationMillis: Long = 800L,
    private val trailingSilenceMillis: Long = 900L,
    private val maxDurationMillis: Long = 8_000L,
) : AudioRecorder {
    private var recorder: AudioRecord? = null

    override suspend fun recordOnce(): RecordedAudio = withContext(Dispatchers.IO) {
        val minBufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBufferSize <= 0) throw IOException("audio recorder buffer is unavailable")
        val frame = ShortArray(minBufferSize / BYTES_PER_SAMPLE)
        val sampleBuffer = PcmSampleBuffer(initialCapacity = sampleRate)
        val activeRecorder = AudioRecord(
            AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBufferSize,
        )
        if (activeRecorder.state != AudioRecord.STATE_INITIALIZED) {
            activeRecorder.release()
            throw IOException("audio recorder failed to initialize")
        }
        recorder = activeRecorder

        try {
            activeRecorder.startRecording()
            val detector = SilenceDetector(
                minDurationMillis = minDurationMillis,
                trailingSilenceMillis = trailingSilenceMillis,
                maxDurationMillis = maxDurationMillis,
            )
            val startedAt = System.currentTimeMillis()
            var peakAmplitude = 0
            var stoppedAt = 0L
            do {
                val read = activeRecorder.read(frame, 0, frame.size)
                val elapsed = System.currentTimeMillis() - startedAt
                stoppedAt = elapsed
                val amplitude = if (read > 0) {
                    sampleBuffer.append(frame, read)
                    PcmFrameEnergy.maxAmplitude(frame, read)
                } else {
                    0
                }
                if (amplitude > peakAmplitude) peakAmplitude = amplitude
            } while (!detector.shouldStop(amplitude, elapsed))
            stopRecorder(activeRecorder)
            debugLog("recorded duration=${stoppedAt}ms peakAmplitude=$peakAmplitude heardSpeech=${detector.heardSpeech}")
            if (!detector.heardSpeech) throw NoSpeechDetectedException()
            val samples = sampleBuffer.toShortArray()
            if (samples.isEmpty()) throw IOException("recorded audio is empty")
            RecordedAudio(
                bytes = WavEncoder.encodePcm16Mono(samples, sampleRate),
                mimeType = "audio/wav",
            )
        } finally {
            releaseRecorder(activeRecorder)
            recorder = null
        }
    }

    override fun cancel() {
        recorder?.let { activeRecorder ->
            runCatching { activeRecorder.stop() }
            releaseRecorder(activeRecorder)
        }
        recorder = null
    }

    private fun stopRecorder(activeRecorder: AudioRecord) {
        try {
            activeRecorder.stop()
        } catch (error: RuntimeException) {
            throw IOException("failed to stop recorder", error)
        }
    }

    private fun releaseRecorder(activeRecorder: AudioRecord) {
        runCatching { activeRecorder.release() }
    }

    private fun debugLog(message: String) {
        runCatching { Log.d(TAG, message) }
    }

    private companion object {
        const val BYTES_PER_SAMPLE = 2
        const val TAG = "SightSyncRecorder"
    }
}
