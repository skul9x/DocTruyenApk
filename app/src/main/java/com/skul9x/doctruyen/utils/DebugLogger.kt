package com.skul9x.doctruyen.utils

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.skul9x.doctruyen.BuildConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LogEntry(
    val timestamp: Long,
    val tag: String,
    val message: String,
    val type: LogType
)

enum class LogType {
    INFO, REQUEST, RESPONSE, ERROR, TTS_TIMING
}

const val MAX_LOG_ENTRIES = 500

object DebugLogger {
    private val _logs = MutableLiveData<List<LogEntry>>(emptyList())
    val logs: LiveData<List<LogEntry>> = _logs
    
    private val logBuffer = ArrayDeque<LogEntry>(MAX_LOG_ENTRIES)
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    @Volatile
    var isDebugEnabled: Boolean = BuildConfig.DEBUG

    fun log(tag: String, message: String, type: LogType = LogType.INFO) {
        if (!isDebugEnabled) {
            return
        }
        val entry = LogEntry(System.currentTimeMillis(), tag, message, type)
        synchronized(logBuffer) {
            if (logBuffer.size >= MAX_LOG_ENTRIES) {
                logBuffer.removeFirst()
            }
            logBuffer.addLast(entry)
            if (_logs.hasActiveObservers()) {
                _logs.postValue(ArrayList(logBuffer))
            }
        }
    }

    fun logError(tag: String, message: String, e: Throwable? = null) {
        if (!isDebugEnabled) {
            return
        }
        val extra = e?.let { "\nException: ${it.message}\n${it.stackTraceToString()}" } ?: ""
        log(tag, "$message$extra", LogType.ERROR)
    }

    fun clear() {
        synchronized(logBuffer) {
            logBuffer.clear()
            if (_logs.hasActiveObservers()) {
                _logs.postValue(emptyList())
            }
        }
    }

    fun getFormattedLogs(filterType: LogType? = null): String {
        return synchronized(logBuffer) {
            val filtered = if (filterType != null) {
                logBuffer.filter { it.type == filterType }
            } else {
                logBuffer
            }
            filtered.joinToString("\n\n") { entry ->
                val time = dateFormat.format(Date(entry.timestamp))
                val icon = when (entry.type) {
                    LogType.INFO -> "ℹ️"
                    LogType.REQUEST -> "📤"
                    LogType.RESPONSE -> "📥"
                    LogType.ERROR -> "❌"
                    LogType.TTS_TIMING -> "⏱️"
                }
                "[$time] $icon ${entry.tag}:\n${entry.message}"
            }
        }
    }
}
