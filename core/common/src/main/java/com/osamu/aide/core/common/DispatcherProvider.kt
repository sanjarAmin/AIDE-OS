package com.osamu.aide.core.common

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Indirection over [Dispatchers] so that compiler, indexing and IO work can be
 * pinned to explicit pools -- and swapped for deterministic ones in tests.
 *
 * [compiler] is deliberately separate from [io]: on-device builds are long and
 * CPU-bound, and must never starve the editor's IO (file reads, autosave).
 */
interface DispatcherProvider {
    val main: CoroutineDispatcher
    val default: CoroutineDispatcher
    val io: CoroutineDispatcher
    val compiler: CoroutineDispatcher
}

class DefaultDispatcherProvider : DispatcherProvider {
    override val main: CoroutineDispatcher get() = Dispatchers.Main
    override val default: CoroutineDispatcher get() = Dispatchers.Default
    override val io: CoroutineDispatcher get() = Dispatchers.IO
    override val compiler: CoroutineDispatcher get() = Dispatchers.Default
}
