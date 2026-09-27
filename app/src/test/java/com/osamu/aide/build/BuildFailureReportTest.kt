package com.osamu.aide.build

import com.osamu.aide.engine.api.BuildResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the build process says when a build throws, instead of dying.
 *
 * Every Kotlin build with a downloaded compiler threw a `SecurityException`
 * from the platform, the process died, and the user read that the build "may
 * have run out of memory". The sentence is the whole of the fix's value, so
 * this pins it.
 */
class BuildFailureReportTest {

    @Test
    fun the_report_carries_the_exception_and_its_message() {
        val finished = BuildService.failureFor(
            SecurityException("Writable dex file '/data/kotlinc.jar' is not allowed."),
        )
        val failure = finished.result as BuildResult.Failure
        assertEquals(
            "The build stopped with an error. SecurityException: Writable dex file '/data/kotlinc.jar' is not allowed.",
            failure.message,
        )
    }

    @Test
    fun an_exception_without_a_message_is_still_named() {
        val failure = BuildService.failureFor(IllegalStateException()).result as BuildResult.Failure
        assertTrue(failure.message, failure.message.endsWith("IllegalStateException"))
        assertTrue("says null: ${failure.message}", !failure.message.contains("null"))
    }
}
