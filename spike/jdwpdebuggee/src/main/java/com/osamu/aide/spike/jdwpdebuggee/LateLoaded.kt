package com.osamu.aide.spike.jdwpdebuggee

import android.util.Log

/**
 * A class that is not loaded when a debugger attaches.
 *
 * **Loaded by reflection, deliberately.** A deferred breakpoint is only tested
 * if the class really is absent at attach time, and ART's verifier may load a
 * class the moment a method that names it is verified -- which for anything
 * `DebuggeeActivity` referred to directly would be at startup, long before
 * "later". Naming it only as a string keeps it out of the verifier's reach, so
 * the first `ClassPrepare` for it is the one [DebuggeeActivity] triggers on
 * its timer.
 *
 * `Runnable` so the caller can invoke it through a type it already has, again
 * without naming this one.
 */
class LateLoaded : Runnable {

    override fun run() {
        val greeting = "loaded late"
        Log.d(DebuggeeActivity.TAG, greeting)
    }
}
