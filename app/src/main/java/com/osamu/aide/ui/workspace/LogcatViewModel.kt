package com.osamu.aide.ui.workspace

import android.os.Process
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.osamu.aide.core.common.DispatcherProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class LogcatLevel(val char: Char, val label: String) {
    ALL('*', "All"),
    VERBOSE('V', "Verbose"),
    DEBUG('D', "Debug"),
    INFO('I', "Info"),
    WARN('W', "Warn"),
    ERROR('E', "Error");

    fun priority(): Int = when (this) {
        ALL -> 0
        VERBOSE -> 2
        DEBUG -> 3
        INFO -> 4
        WARN -> 5
        ERROR -> 6
    }
}

/** What the Logcat tab is showing. */
data class LogcatUiState(
    /** Newest last, already filtered. Capped at [LogcatViewModel.MAX_LINES]. */
    val lines: List<String> = emptyList(),
    /** Substring every shown line must contain. Empty shows everything. */
    val filter: String = "",
    val minLevel: LogcatLevel = LogcatLevel.ALL,
    val isReading: Boolean = false,
    val isOwnProcessOnly: Boolean = false,
    val errorMessage: String? = null,
)

class LogcatViewModel(
    private val dispatchers: DispatcherProvider,
) : ViewModel() {

    private val _state = MutableStateFlow(LogcatUiState())
    val state: StateFlow<LogcatUiState> = _state.asStateFlow()

    private var readJob: Job? = null

    /** Every line read, before filtering, so changing the filter is instant. */
    private val received = ArrayDeque<String>()

    fun setFilter(filter: String) {
        _state.update { it.copy(filter = filter, lines = matching(filter, it.minLevel)) }
    }

    fun setLevel(level: LogcatLevel) {
        _state.update { it.copy(minLevel = level, lines = matching(it.filter, level)) }
    }

    fun clear() {
        received.clear()
        _state.update { it.copy(lines = emptyList()) }
    }

    fun start() {
        readJob?.cancel()
        received.clear()
        _state.update {
            it.copy(lines = emptyList(), isReading = true, isOwnProcessOnly = false, errorMessage = null)
        }
        readJob = viewModelScope.launch {
            try {
                withContext(dispatchers.io) { read() }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                _state.update {
                    it.copy(isReading = false, errorMessage = failure.message ?: "logcat could not be read.")
                }
            }
        }
    }

    fun stop() {
        readJob?.cancel()
        readJob = null
        _state.update { it.copy(isReading = false) }
    }

    override fun onCleared() {
        stop()
        super.onCleared()
    }

    private suspend fun read() {
        val process = ProcessBuilder("logcat", "-v", "uid", "-v", "threadtime")
            .redirectErrorStream(true)
            .start()
        val ownUid = Process.myUid().toString()
        var sawAnotherApp = false
        var considered = 0
        try {
            process.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (!viewModelScope.isActive || readJob?.isActive != true) break

                    if (!sawAnotherApp && uidOf(line)?.let { it != ownUid } == true) {
                        sawAnotherApp = true
                    }
                    considered++

                    received.addLast(line)
                    while (received.size > MAX_LINES) received.removeFirst()
                    val ownOnly = considered >= CONSENT_SAMPLE_LINES && !sawAnotherApp
                    _state.update { it.copy(lines = matching(it.filter, it.minLevel), isOwnProcessOnly = ownOnly) }
                }
            }
        } finally {
            process.destroy()
        }
        _state.update { it.copy(isReading = false) }
    }

    private fun matching(filter: String, level: LogcatLevel): List<String> {
        val stream = received.asSequence()
        val byText = if (filter.isBlank()) stream else stream.filter { filter in it }
        val byLevel = if (level == LogcatLevel.ALL) byText else byText.filter {
            levelOf(it).priority() >= level.priority()
        }
        return byLevel.toList()
    }

    private companion object {
        const val MAX_LINES = 2_000
        const val CONSENT_SAMPLE_LINES = 40
    }
}

internal fun uidOf(line: String): String? {
    val fields = line.trim().split(Regex("\\s+"))
    if (fields.size < 6) return null
    if (fields[0].toDoubleOrNull() != null || !fields[1].contains(':')) return null
    return fields[2]
}

internal fun levelOf(line: String): LogcatLevel {
    val trimmed = line.trim()
    if (trimmed.startsWith("--------- beginning")) return LogcatLevel.VERBOSE
    val fields = trimmed.split(Regex("\\s+"))
    if (fields.size >= 5 && fields[1].contains(':')) {
        for (idx in listOf(5, 4, 2)) {
            if (idx < fields.size && fields[idx].length == 1) {
                when (fields[idx][0]) {
                    'E', 'F', 'A' -> return LogcatLevel.ERROR
                    'W' -> return LogcatLevel.WARN
                    'I' -> return LogcatLevel.INFO
                    'D' -> return LogcatLevel.DEBUG
                    'V' -> return LogcatLevel.VERBOSE
                }
            }
        }
    }
    val prefix = trimmed.take(50)
    return when {
        " E " in prefix || " F " in prefix -> LogcatLevel.ERROR
        " W " in prefix -> LogcatLevel.WARN
        " I " in prefix -> LogcatLevel.INFO
        " D " in prefix -> LogcatLevel.DEBUG
        " V " in prefix -> LogcatLevel.VERBOSE
        else -> LogcatLevel.VERBOSE
    }
}
