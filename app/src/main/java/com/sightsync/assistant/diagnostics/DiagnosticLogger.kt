package com.sightsync.assistant.diagnostics

import android.util.Log
import com.sightsync.assistant.BuildConfig

fun interface DiagnosticLogger {
    fun log(tag: String, message: String)
}

object AndroidDiagnosticLogger : DiagnosticLogger {
    override fun log(tag: String, message: String) {
        if (BuildConfig.DEBUG) {
            runCatching { Log.i(tag, message) }
        }
    }
}

object NoOpDiagnosticLogger : DiagnosticLogger {
    override fun log(tag: String, message: String) = Unit
}
