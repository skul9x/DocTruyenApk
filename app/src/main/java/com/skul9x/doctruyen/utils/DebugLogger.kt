package com.skul9x.doctruyen.utils

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
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
    INFO, REQUEST, RESPONSE, ERROR
}

object DebugLogger {
    private val _logs = MutableLiveData<List<LogEntry>>(emptyList())
    val logs: LiveData<List<LogEntry>> = _logs
    
    private val logList = mutableListOf<LogEntry>()
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun log(tag: String, message: String, type: LogType = LogType.INFO) {
        val entry = LogEntry(System.currentTimeMillis(), tag, message, type)
        synchronized(logList) {
            logList.add(entry)
            _logs.postValue(logList.toList())
        }
    }

    fun logError(tag: String, message: String, e: Throwable? = null) {
        val extra = e?.let { "\nException: ${it.message}\n${it.stackTraceToString()}" } ?: ""
        log(tag, "$message$extra", LogType.ERROR)
    }

    fun clear() {
        synchronized(logList) {
            logList.clear()
            _logs.postValue(emptyList())
        }
    }

    fun getFormattedLogs(): String {
        return synchronized(logList) {
            logList.joinToString("\n\n") { entry ->
                val time = dateFormat.format(Date(entry.timestamp))
                val icon = when (entry.type) {
                    LogType.INFO -> "ℹ️"
                    LogType.REQUEST -> "📤"
                    LogType.RESPONSE -> "📥"
                    LogType.ERROR -> "❌"
                }
                "[$time] $icon ${entry.tag}:\n${entry.message}"
            }
        }
    }
}
