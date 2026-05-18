package com.sightsync.assistant.speech

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

class ShortAudioRecorder(
    private val context: Context,
    private val durationMillis: Long = 4_000L,
) : AudioRecorder {
    private var recorder: MediaRecorder? = null

    override suspend fun recordOnce(): RecordedAudio = withContext(Dispatchers.IO) {
        val outputFile = File.createTempFile("sightsync-utterance-", ".m4a", context.cacheDir)
        val activeRecorder = createRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(16_000)
            setAudioEncodingBitRate(64_000)
            setOutputFile(outputFile.absolutePath)
        }
        recorder = activeRecorder

        try {
            activeRecorder.prepare()
            activeRecorder.start()
            delay(durationMillis)
            stopRecorder(activeRecorder)
            val bytes = outputFile.readBytes()
            if (bytes.isEmpty()) throw IOException("recorded audio is empty")
            RecordedAudio(bytes = bytes, mimeType = "audio/mp4")
        } finally {
            releaseRecorder(activeRecorder)
            recorder = null
            outputFile.delete()
        }
    }

    override fun cancel() {
        recorder?.let { activeRecorder ->
            runCatching { activeRecorder.stop() }
            releaseRecorder(activeRecorder)
        }
        recorder = null
    }

    private fun createRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

    private fun stopRecorder(activeRecorder: MediaRecorder) {
        try {
            activeRecorder.stop()
        } catch (error: RuntimeException) {
            throw IOException("failed to stop recorder", error)
        }
    }

    private fun releaseRecorder(activeRecorder: MediaRecorder) {
        runCatching { activeRecorder.reset() }
        runCatching { activeRecorder.release() }
    }
}
