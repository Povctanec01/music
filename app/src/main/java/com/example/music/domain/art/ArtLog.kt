package com.example.music.domain.art

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Logcat tag for cover-art download/load diagnostics. Filter: `adb logcat -s MusicArt` */
object ArtLog {
    private const val TAG = "MusicArt"
    private const val MAX_UI_LINES = 50

    private val _recentLines = MutableStateFlow<List<String>>(emptyList())
    val recentLines: StateFlow<List<String>> = _recentLines.asStateFlow()

    fun d(step: String, detail: String = "") {
        val msg = if (detail.isEmpty()) step else "$step | $detail"
        Log.d(TAG, msg)
        push("D $msg")
    }

    fun w(step: String, detail: String = "", error: Throwable? = null) {
        val msg = if (detail.isEmpty()) step else "$step | $detail"
        if (error != null) Log.w(TAG, msg, error) else Log.w(TAG, msg)
        push("W $msg")
    }

    fun e(step: String, detail: String = "", error: Throwable? = null) {
        val msg = if (detail.isEmpty()) step else "$step | $detail"
        if (error != null) Log.e(TAG, msg, error) else Log.e(TAG, msg)
        push("E $msg")
    }

    private fun push(line: String) {
        _recentLines.value = (_recentLines.value + line).takeLast(MAX_UI_LINES)
    }

    fun clearUi() {
        _recentLines.value = emptyList()
    }
}
