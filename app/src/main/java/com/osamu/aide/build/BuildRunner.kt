package com.osamu.aide.build

import com.osamu.aide.core.fs.Project
import com.osamu.aide.engine.api.BuildEvent
import com.osamu.aide.engine.api.DebuggerRequest
import kotlinx.coroutines.flow.Flow

/**
 * Whatever runs a build, wherever it runs.
 *
 * Introduced so the UI does not know whether the compiler is in its own process
 * or beside it. `ProjectBuilder` is the in-process implementation and stays the
 * one the build engines are wired into; [RemoteBuildRunner] is the same thing
 * over a binder.
 *
 * A separate type rather than making `ProjectBuilder` an interface, because the
 * two are not interchangeable in one respect worth naming: `ProjectBuilder`
 * also answers questions about *toolchains* — which platform is missing, which
 * download to offer — and those are cheap, synchronous and wanted on the UI
 * side. Only the expensive part crosses the boundary.
 */
interface BuildRunner {
    /**
     * [debugger] builds a debugger into the app; see [DebuggerRequest].
     *
     * Defaulted, which is why this stopped being a `fun interface`: a
     * functional interface's method may not have a default, and every build
     * that is not a Debug has no debugger to pass.
     */
    fun build(project: Project, debuggable: Boolean, debugger: DebuggerRequest? = null): Flow<BuildEvent>
}
