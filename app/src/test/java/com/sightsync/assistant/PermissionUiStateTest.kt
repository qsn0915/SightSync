package com.sightsync.assistant

import org.junit.Assert.assertEquals
import org.junit.Test

class PermissionUiStateTest {
    @Test
    fun allGrantedIsTrueOnlyWhenEveryRequiredPermissionIsReady() {
        val getter = PermissionUiState::class.java.getMethod("getAllGranted")

        assertEquals(
            false,
            getter.invoke(
                PermissionUiState(
                    accessibilityEnabled = true,
                    microphoneGranted = true,
                    overlayGranted = false,
                    notificationGranted = true,
                ),
            ),
        )
        assertEquals(
            false,
            getter.invoke(
                PermissionUiState(
                    accessibilityEnabled = true,
                    microphoneGranted = true,
                    overlayGranted = true,
                    notificationGranted = false,
                ),
            ),
        )
        assertEquals(
            true,
            getter.invoke(
                PermissionUiState(
                    accessibilityEnabled = true,
                    microphoneGranted = true,
                    overlayGranted = true,
                    notificationGranted = true,
                ),
            ),
        )
    }
}
