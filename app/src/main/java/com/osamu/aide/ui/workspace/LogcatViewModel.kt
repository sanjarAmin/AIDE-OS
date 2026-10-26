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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
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

/** One line as logcat printed it, and the package whose uid wrote it, when that can be told. */
private class LogLine(val text: String, val owner: String?)

class LogcatViewModel(
    private val dispatchers: DispatcherProvider,
    /**
     * The package a uid belongs to, or null: `PackageManager.getNameForUid` in
     * the app. [matching] says why the filter needs it.
     */
    private val packageOf: (Int) -> String? = { null },
) : ViewModel() {

    private val _state = MutableStateFlow(LogcatUiState())
    val state: StateFlow<LogcatUiState> = _state.asStateFlow()

    private var readJob: Job? = null

    /**
     * Every line read, before filtering, so changing the filter is instant.
     *
     * **Guarded by itself.** The reader appends on an IO thread while the
     * filter, the level and Clear read or empty it on the main one; unguarded,
     * typing in the filter while logcat streamed could throw
     * ConcurrentModificationException on the main thread and take the IDE down.
     */
    private val received = ArrayDeque<LogLine>()

    /** Which read the lines in [received] belong to. Guarded by [received]. */
    private var generation = 0

    fun setFilter(filter: String) {
        _state.update { it.copy(filter = filter, lines = matching(filter, it.minLevel)) }
    }

    fun setLevel(level: LogcatLevel) {
        _state.update { it.copy(minLevel = level, lines = matching(it.filter, level)) }
    }

    fun clear() {
        synchronized(received) { received.clear() }
        _state.update { it.copy(lines = emptyList()) }
    }

    fun start() {
        readJob?.cancel()
        val mine = synchronized(received) {
            received.clear()
            ++generation
        }
        _state.update {
            it.copy(lines = emptyList(), isReading = true, isOwnProcessOnly = false, errorMessage = null)
        }
        readJob = viewModelScope.launch {
            try {
                withContext(dispatchers.io) { read(mine) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                // A replaced read has its pipe closed under it, which can surface
                // here as an IOException: not this read's failure to report.
                if (isCurrent(mine)) {
                    _state.update {
                        it.copy(isReading = false, errorMessage = failure.message ?: "logcat could not be read.")
                    }
                }
            }
        }
    }

    fun stop() {
        readJob?.cancel()
        readJob = null
        synchronized(received) { generation++ }
        _state.update { it.copy(isReading = false) }
    }

    override fun onCleared() {
        stop()
        super.onCleared()
    }

    /**
     * Reads until a newer [start] or a [stop] replaces this read.
     *
     * **Each read checks its own [generation], not [readJob].** It used to check
     * whether `readJob` was active -- but that is the *newest* job, so a read
     * replaced by Ask again or a quick Resume found its replacement active and
     * read on beside it: every line twice, and a logcat process never ended.
     */
    private suspend fun read(mine: Int) {
        val process = ProcessBuilder("logcat", "-v", "uid", "-v", "threadtime")
            .redirectErrorStream(true)
            .start()
        // Ended the moment this read is cancelled, not when it next looks:
        // readLine blocks, cancellation does not interrupt it, and a quiet log
        // could leave a replaced read waiting there indefinitely.
        val ending = currentCoroutineContext().job.invokeOnCompletion { process.destroy() }
        val ownUid = Process.myUid().toString()
        val owners = HashMap<String, String?>()
        var sawAnotherApp = false
        var considered = 0
        try {
            process.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    val uid = uidOf(line)
                    if (!sawAnotherApp && uid != null && uid != ownUid) sawAnotherApp = true
                    considered++

                    // Looked up once per uid: it is a binder call, and a null
                    // answer is as final as a name.
                    val owner = uid?.let {
                        if (it in owners) owners[it] else ownerOf(it).also { name -> owners[it] = name }
                    }
                    if (!appended(mine, LogLine(line, owner))) break
                    val ownOnly = considered >= CONSENT_SAMPLE_LINES && !sawAnotherApp
                    _state.update { it.copy(lines = matching(it.filter, it.minLevel), isOwnProcessOnly = ownOnly) }
                }
            }
        } finally {
            ending.dispose()
            process.destroy()
        }
        if (isCurrent(mine)) _state.update { it.copy(isReading = false) }
    }

    /** Adds [line] unless a newer read or a stop has replaced read [mine]. */
    private fun appended(mine: Int, line: LogLine): Boolean = synchronized(received) {
        if (generation != mine) return false
        received.addLast(line)
        while (received.size > MAX_LINES) received.removeFirst()
        true
    }

    private fun isCurrent(mine: Int): Boolean = synchronized(received) { generation == mine }

    /** A numeric uid's package; a named one (`shell`, `wifi`) is no app's. */
    private fun ownerOf(uid: String): String? =
        uid.toIntOrNull()?.let { runCatching { packageOf(it) }.getOrNull() }

    /**
     * The lines that pass [filter] and [level].
     *
     * **The filter matches the package that wrote a line, too.** It starts as
     * the built app's id, but `-v uid` prints an app's uid as a number --
     * logcat names a uid only when the name fits in five characters, and
     * `u0_a118` does not -- so the app's own `Log.d("MainActivity", ...)` never
     * contained its id, and the default filter hid nearly everything it logged.
     */
    private fun matching(filter: String, level: LogcatLevel): List<String> {
        val snapshot = synchronized(received) { received.toList() }
        return snapshot.asSequence()
            .filter { filter.isBlank() || filter in it.text || it.owner?.contains(filter) == true }
            .filter { level == LogcatLevel.ALL || levelOf(it.text).priority() >= level.priority() }
            .map { it.text }
            .toList()
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
