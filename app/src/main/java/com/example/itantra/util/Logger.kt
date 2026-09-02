package com.example.itantra.util

import android.util.Log
import androidx.compose.runtime.mutableStateListOf

object Logger {
    private const val TAG = "iTantra"
    val logs = mutableStateListOf<LogEntry>()

    data class LogEntry(val message: String, val timestamp: Long = System.currentTimeMillis())

    fun d(message: String) {
        Log.d(TAG, message)
        logs.add(LogEntry(message))
    }

    fun e(message: String, throwable: Throwable? = null) {
        Log.e(TAG, message, throwable)
        logs.add(LogEntry("ERROR: $message ${throwable?.message ?: ""}"))
    }
}