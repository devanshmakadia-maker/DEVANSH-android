package com.devassistant.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class TaskStatus { RUNNING, COMPLETED, FAILED, NEEDS_USER }

data class LogEntry(
    val timestamp: String = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date()),
    val command: String,
    var status: TaskStatus,
    val steps: MutableList<String> = mutableListOf(),
    var error: String? = null
)

/** Simple in-process log the UI observes. Cleared on user request only. */
object ActivityLogStore {
    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries = _entries.asStateFlow()

    fun start(command: String): LogEntry {
        val entry = LogEntry(command = command, status = TaskStatus.RUNNING)
        _entries.value = _entries.value + entry
        return entry
    }

    fun appendStep(entry: LogEntry, step: String) {
        entry.steps.add(step)
        touch()
    }

    fun finish(entry: LogEntry, status: TaskStatus, error: String? = null) {
        entry.status = status
        entry.error = error
        touch()
    }

    fun clear() {
        _entries.value = emptyList()
    }

    private fun touch() {
        // Force StateFlow emission since LogEntry is mutated in place.
        _entries.value = _entries.value.toList()
    }
}
