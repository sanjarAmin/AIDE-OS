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

/** What the Logcat tab is showing. */
data class LogcatUiState(
    /** Newest last, already filtered. Capped at [LogcatViewModel.MAX_LINES]. */
    val lines: List<String> = emptyList(),
    /** Substring every shown line must contain. Empty shows everything. */
    val filter: String = "",
    val isReading: Boolean = false,
    /**
     * True once a read has come back holding nothing but this process.
     *
     * Not the same as "the permission is missing": the grant is one-time and
     * the user may simply have said no this once, so the panel offers to ask
     * again rather than sending anyone to Settings. `tools/logcat/FINDINGS.md`.
     */
    val isOwnProcessOnly: Boolean = false,
    val errorMessage: String? = null,
)

/**
 * The device log, for the app the user just built.
 *
 * **Why this can exist at all** is `tools/logcat/FINDINGS.md`: an app sees only
 * its own lines until `READ_LOGS` is declared, and once it is, Android itself
 * asks the user -- no root, no adb, no desktop. The permission was left out of
 * the manifest for a milestone because it is a trust decision about the
 * product rather than a technical question; it is in now, deliberately.
 *
 * **The consent is one-time and this expects to lose it.** The dialog offers
 * "Allow one-time access" and nothing else, so a grant is per read and the
 * panel has to handle the refusal as an ordinary outcome -- hence
 * [LogcatUiState.isOwnProcessOnly] and [start] being callable again rather
 * than a one-shot at construction.
 *
 * **Filtering is a text match, not `--pid`.** An unprivileged app cannot
 * resolve another package's pid -- `/proc` is hidden -- so the filter is a
 * substring over the stream, defaulted by the caller to the project's
 * application id. A crash carries the process name in `AndroidRuntime`'s
 * message, which is the case this tab exists for.
 */
class LogcatViewModel(
    private val dispatchers: DispatcherProvider,
) : ViewModel() {

    private val _state = MutableStateFlow(LogcatUiState())
    val state: StateFlow<LogcatUiState> = _state.asStateFlow()

    private var readJob: Job? = null

    /** Every line read, before filtering, so changing the filter is instant. */
    private val received = ArrayDeque<String>()

    fun setFilter(filter: String) {
        _state.update { it.copy(filter = filter, lines = matching(filter)) }
    }

    fun clear() {
        received.clear()
        _state.update { it.copy(lines = emptyList()) }
    }

    /**
     * Starts reading, replacing any read already running.
     *
     * Safe to call again: that is how the panel re-asks for consent after the
     * user has declined it once.
     */
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
        // `-v threadtime` is the format every Android developer can already
        // read, and the one whose pid column this parses to tell a granted
        // read from a refused one.
        // `-v uid` as well as `-v threadtime`: **the uid column is the whole
        // point.** The obvious signal, a line from a pid that is not ours, is
        // wrong -- the log buffer holds our own earlier processes, so a
        // restarted app sees a dozen foreign pids that are all itself, and
        // decides it has access when it has none. A uid is per app.
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

                    // **Whether the grant happened, decided from the data.**
                    // There is no API that answers it: `checkSelfPermission`
                    // reports the *declaration*, and a refused consent still
                    // leaves the app reading its own lines happily. The only
                    // signal is a line from a pid that is not ours.
                    if (!sawAnotherApp && uidOf(line)?.let { it != ownUid } == true) {
                        sawAnotherApp = true
                    }
                    considered++

                    received.addLast(line)
                    while (received.size > MAX_LINES) received.removeFirst()
                    // **Re-decided on every line, not once at line forty.** The
                    // first version tested the flag only as the sample closed,
                    // so a read that opened with a burst of our own lines --
                    // which is what opening a panel produces -- latched the
                    // wrong answer and never revisited it. Driving it showed a
                    // pane full of nothing but AIDE-OS and no explanation.
                    val ownOnly = considered >= CONSENT_SAMPLE_LINES && !sawAnotherApp
                    _state.update { it.copy(lines = matching(it.filter), isOwnProcessOnly = ownOnly) }
                }
            }
        } finally {
            process.destroy()
        }
        _state.update { it.copy(isReading = false) }
    }

    private fun matching(filter: String): List<String> =
        if (filter.isBlank()) received.toList() else received.filter { filter in it }

    private companion object {
        /**
         * Enough to see the whole of a stack trace and a few seconds either
         * side, and few enough that the list stays cheap to filter on every
         * arriving line.
         */
        const val MAX_LINES = 2_000

        /**
         * How many lines to look at before deciding a read is our own process
         * only. A device that is quiet apart from us produces our lines first,
         * so one line is not evidence; a few dozen is.
         */
        const val CONSENT_SAMPLE_LINES = 40
    }
}

/**
 * The uid column of a `-v uid -v threadtime` line, or null for anything else.
 *
 * `09-09 00:09:17.732 10118   740   740 D StatusBarIconController: ...` --
 * date, time, **uid**, pid, tid, level, tag. Returned as the raw token because
 * the column is not always a number: the platform prints a name for uids it
 * knows (`wifi`, `system`), and those are foreign either way.
 *
 * Internal so a test can pin the parse without going near a real log, which is
 * the half of this that has already been wrong once.
 */
internal fun uidOf(line: String): String? {
    val fields = line.trim().split(Regex("\\s+"))
    if (fields.size < 6) return null
    // A separator line ("--------- beginning of main") has no timestamp, and a
    // date that does not parse means this is not a log line at all.
    if (fields[0].toDoubleOrNull() != null || !fields[1].contains(':')) return null
    return fields[2]
}
