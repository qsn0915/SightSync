package com.sightsync.assistant.diagnostics

import android.util.Log

fun interface DiagnosticLogger {
    fun log(tag: String, message: String)
}

object AndroidDiagnosticLogger : DiagnosticLogger {
    override fun log(tag: String, message: String) {
        runCatching { Log.i(tag, message) }
    }
}

object NoOpDiagnosticLogger : DiagnosticLogger {
    override fun log(tag: String, message: String) = Unit
}
