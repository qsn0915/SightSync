package com.sightsync.assistant.speech

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SilenceDetectorTest {
    @Test
    fun doesNotStopBeforeMinimumDuration() {
        val detector = SilenceDetector()

        assertFalse(detector.shouldStop(amplitude = 0, elapsedMillis = 300))
    }

    @Test
    fun stopsAfterSpeechThenTrailingSilence() {
        val detector = SilenceDetector(
            minDurationMillis = 800,
            trailingSilenceMillis = 900,
            maxDurationMillis = 8_000,
            speechAmplitudeThreshold = 1_200,
        )

        assertFalse(detector.shouldStop(amplitude = 2_000, elapsedMillis = 900))
        assertFalse(detector.shouldStop(amplitude = 100, elapsedMillis = 1_200))
        assertTrue(detector.shouldStop(amplitude = 100, elapsedMillis = 2_100))
    }

    @Test
    fun stopsAtMaximumDurationEvenWithoutSpeech() {
        val detector = SilenceDetector(maxDurationMillis = 8_000)

        assertTrue(detector.shouldStop(amplitude = 0, elapsedMillis = 8_000))
    }

    @Test
    fun reportsWhetherSpeechWasHeard() {
        val detector = SilenceDetector(speechAmplitudeThreshold = 1_200)

        detector.shouldStop(amplitude = 1_500, elapsedMillis = 900)

        assertTrue(detector.heardSpeech)
    }
}
