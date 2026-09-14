package com.osamu.aide.ui.benchmark

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.osamu.aide.core.common.DispatcherProvider
import com.osamu.aide.toolchain.manager.InstallProgress
import com.osamu.aide.toolchain.manager.ToolchainComponent
import com.osamu.aide.toolchain.manager.ToolchainManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ModelRow(
    val component: ToolchainComponent,
    val installed: Boolean,
    val progress: InstallProgress? = null,
) {
    val isDownloading: Boolean
        get() = progress is InstallProgress.Downloading || progress is InstallProgress.Verifying ||
            progress is InstallProgress.Extracting
}

data class BenchmarkUiState(
    /** Null on a device this build of llama.cpp does not exist for. */
    val engine: ModelRow? = null,
    val models: List<ModelRow> = emptyList(),
    val totalRamBytes: Long = 0,
    /** The model being measured, while one is. */
    val running: ToolchainComponent? = null,
    val step: String? = null,
    val report: BenchmarkReport? = null,
    val notice: String? = null,
) {
    val isBusy: Boolean get() = running != null || engine?.isDownloading == true || models.any { it.isDownloading }
}

/**
 * The benchmark screen: download the engine and a model, run, show the numbers.
 *
 * Downloads and runs are collected on this view model's scope, so they stop
 * when the screen is left. A 4.7 GB download surviving the user walking away
 * would be a background job this app has no notification for; the screen says
 * to keep it open instead.
 */
class BenchmarkViewModel(
    private val context: Context,
    private val toolchain: ToolchainManager,
    private val dispatchers: DispatcherProvider,
) : ViewModel() {

    private val abi = Build.SUPPORTED_ABIS.first()
    private val engineComponent = ToolchainComponent.llamaCpp(abi)

    private val _state = MutableStateFlow(BenchmarkUiState())
    val state: StateFlow<BenchmarkUiState> = _state.asStateFlow()

    private val jobs = mutableMapOf<String, Job>()
    private var benchmark: LocalModelBenchmark? = null

    init {
        refresh()
    }

    private fun refresh() {
        val memory = ActivityManager.MemoryInfo()
            .also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        _state.update { current ->
            current.copy(
                engine = engineComponent?.let { component ->
                    ModelRow(component, toolchain.installedFile(component) != null, current.engine?.progress)
                },
                models = ToolchainComponent.LOCAL_MODELS.map { component ->
                    ModelRow(
                        component,
                        toolchain.installedFile(component) != null,
                        current.models.firstOrNull { it.component.id == component.id }?.progress,
                    )
                },
                totalRamBytes = memory.totalMem,
            )
        }
    }

    fun download(component: ToolchainComponent) {
        if (jobs[component.id]?.isActive == true) return
        jobs[component.id] = viewModelScope.launch {
            toolchain.install(component).collect { progress ->
                setProgress(component, progress)
                if (progress is InstallProgress.Failed) {
                    _state.update { it.copy(notice = "${component.displayName}: ${progress.message}") }
                }
            }
            setProgress(component, null)
            refresh()
        }
    }

    fun cancel(component: ToolchainComponent) {
        jobs.remove(component.id)?.cancel()
        setProgress(component, null)
    }

    fun delete(component: ToolchainComponent) {
        cancel(component)
        toolchain.remove(component)
        refresh()
    }

    fun run(component: ToolchainComponent) {
        val engine = engineComponent?.let(toolchain::installedFile) ?: return
        val modelFile = toolchain.installedFile(component) ?: return
        if (_state.value.running != null) return
        // bin/llama-server's install directory, two levels above the marker.
        val llamaRoot = engine.parentFile?.parentFile ?: return
        _state.update { it.copy(running = component, step = "Starting…", report = null, notice = null) }
        jobs["run"] = viewModelScope.launch {
            val runner = LocalModelBenchmark(context, llamaRoot, modelFile, component.displayName)
            benchmark = runner
            val report = withContext(dispatchers.io) {
                runner.run { step -> _state.update { it.copy(step = step) } }
            }
            benchmark = null
            _state.update { it.copy(running = null, step = null, report = report) }
        }
    }

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    private fun setProgress(component: ToolchainComponent, progress: InstallProgress?) {
        _state.update { current ->
            if (current.engine?.component?.id == component.id) {
                current.copy(engine = current.engine.copy(progress = progress))
            } else {
                current.copy(
                    models = current.models.map {
                        if (it.component.id == component.id) it.copy(progress = progress) else it
                    },
                )
            }
        }
    }

    override fun onCleared() {
        // A server left running holds gigabytes of RAM for nothing.
        benchmark?.stop()
    }
}
