package com.osamu.aide.core.common

import android.util.Log

/**
 * Thin logging facade. Exists so build/LSP subprocesses can later be routed to
 * an in-app log pane instead of logcat, which is not readable on-device.
 */
object AideLog {
    private const val TAG = "AIDE"

    fun d(scope: String, message: String) = Log.d(TAG, "[$scope] $message")
    fun i(scope: String, message: String) = Log.i(TAG, "[$scope] $message")
    fun w(scope: String, message: String, t: Throwable? = null) = Log.w(TAG, "[$scope] $message", t)
    fun e(scope: String, message: String, t: Throwable? = null) = Log.e(TAG, "[$scope] $message", t)
}
