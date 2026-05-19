package com.sightsync.assistant.speech

class SilenceDetector(
    private val minDurationMillis: Long = 800L,
    private val trailingSilenceMillis: Long = 900L,
    private val maxDurationMillis: Long = 8_000L,
    private val speechAmplitudeThreshold: Int = 1_200,
) {
    var heardSpeech: Boolean = false
        private set

    private var silenceStartedAtMillis: Long? = null

    fun shouldStop(amplitude: Int, elapsedMillis: Long): Boolean {
        if (elapsedMillis >= maxDurationMillis) return true
        if (elapsedMillis < minDurationMillis) return false

        if (amplitude >= speechAmplitudeThreshold) {
            heardSpeech = true
            silenceStartedAtMillis = null
            return false
        }

        if (!heardSpeech) return false

        val silenceStart = silenceStartedAtMillis ?: elapsedMillis.also {
            silenceStartedAtMillis = it
        }
        return elapsedMillis - silenceStart >= trailingSilenceMillis
    }
}
