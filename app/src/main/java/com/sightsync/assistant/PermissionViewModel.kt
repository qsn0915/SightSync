package com.sightsync.assistant

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import com.sightsync.assistant.accessibility.AssistantAccessibilityService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PermissionUiState(
    val accessibilityEnabled: Boolean = false,
    val microphoneGranted: Boolean = false,
    val overlayGranted: Boolean = false,
    val notificationGranted: Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU,
) {
    val allGranted: Boolean
        get() = accessibilityEnabled && microphoneGranted && overlayGranted && notificationGranted
}

class PermissionViewModel : ViewModel() {
    private val _state = MutableStateFlow(PermissionUiState())
    val state: StateFlow<PermissionUiState> = _state.asStateFlow()

    fun refresh(context: Context) {
        _state.value = PermissionUiState(
            accessibilityEnabled = isAccessibilityServiceEnabled(context),
            microphoneGranted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED,
            overlayGranted = Settings.canDrawOverlays(context),
            notificationGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED,
        )
    }

    private fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val expected = "${context.packageName}/${AssistantAccessibilityService::class.java.name}"
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false

        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        return splitter.any { it.equals(expected, ignoreCase = true) }
    }
}
