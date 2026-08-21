package com.osamu.aide.engine.api

import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BuildSystemTest {

    private val success = BuildResult.Success(File("app-debug.apk"), durationMillis = 900)

    @Test
    fun `awaitResult returns the finished result`() = runTest {
        val events = flowOf(
            BuildEvent.StageStarted(BuildStage.COMPILE_JAVA),
            BuildEvent.StageCompleted(BuildStage.COMPILE_JAVA, durationMillis = 400),
            BuildEvent.Finished(success),
        )

        assertEquals(success, events.awaitResult())
    }

    @Test
    fun `awaitResult stops collecting once the build has finished`() = runTest {
        var collectedPastFinish = false
        val events = flow {
            emit(BuildEvent.Finished(success))
            // A real engine's flow would still be holding a compiler process
            // open here. Continuing to collect would keep it alive after the
            // caller already has its answer.
            collectedPastFinish = true
            emit(BuildEvent.StageStarted(BuildStage.SIGN))
        }

        events.awaitResult()

        assertFalse("collection continued past Finished", collectedPastFinish)
    }

    @Test
    fun `a failed build is an ordinary result, not an exception`() = runTest {
        val failure = BuildResult.Failure(
            stage = BuildStage.COMPILE_JAVA,
            message = "1 error",
            durationMillis = 300,
            diagnostics = listOf(Diagnostic(DiagnosticSeverity.ERROR, "cannot find symbol")),
        )

        val result = flowOf(BuildEvent.Finished(failure)).awaitResult()

        assertEquals(failure, result)
        assertFalse(result.succeeded)
        assertTrue(result.diagnostics.hasErrors)
    }

    @Test(expected = IllegalStateException::class)
    fun `a flow that never finishes is a defect and says so`() = runTest {
        // Better to fail loudly here than to hand a caller a null result or
        // hang: an engine that ends its flow without Finished is broken.
        flowOf(BuildEvent.StageStarted(BuildStage.DEX)).awaitResult()
    }
}
