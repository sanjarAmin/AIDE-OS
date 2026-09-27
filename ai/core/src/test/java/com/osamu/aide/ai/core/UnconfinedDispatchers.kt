package com.osamu.aide.ai.core

import com.osamu.aide.core.common.DispatcherProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Every dispatcher inline, so a test's coroutines run where it can see them.
 * One copy, rather than the one per test file there used to be.
 */
internal val unconfinedDispatchers: DispatcherProvider = object : DispatcherProvider {
    override val main: CoroutineDispatcher get() = Dispatchers.Unconfined
    override val default: CoroutineDispatcher get() = Dispatchers.Unconfined
    override val io: CoroutineDispatcher get() = Dispatchers.Unconfined
    override val compiler: CoroutineDispatcher get() = Dispatchers.Unconfined
}
