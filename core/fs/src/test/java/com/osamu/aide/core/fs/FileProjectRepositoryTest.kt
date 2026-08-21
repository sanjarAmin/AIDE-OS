package com.osamu.aide.core.fs

import com.osamu.aide.core.common.AppResult
import com.osamu.aide.core.common.DispatcherProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class FileProjectRepositoryTest {

    @get:Rule
    val temp = TemporaryFolder()

    private class TestDispatchers(private val dispatcher: CoroutineDispatcher) : DispatcherProvider {
        override val main = dispatcher
        override val default = dispatcher
        override val io = dispatcher
        override val compiler = dispatcher
    }

    /**
     * Built inside the test body so the dispatcher shares [TestScope]'s
     * scheduler; a dispatcher created in `@Before` has its own and the runtime
     * rejects the mix.
     */
    private fun TestScope.newRepository(): FileProjectRepository {
        val workspace = File(temp.root, "workspace").apply { mkdirs() }
        return FileProjectRepository(
            workspaceRoot = workspace,
            dispatchers = TestDispatchers(StandardTestDispatcher(testScheduler)),
        )
    }

    @Test
    fun `a created project round-trips through its descriptor`() = runTest {
        val repository = newRepository()
        val created = repository.createProject(
            name = "My App",
            applicationId = "com.example.myapp",
            language = SourceLanguage.KOTLIN,
            engine = BuildEngine.FAST,
        )
        assertTrue(created is AppResult.Success)

        val listed = repository.listProjects()
        assertTrue(listed is AppResult.Success)

        val project = (listed as AppResult.Success).value.single()
        assertEquals("My App", project.name)
        assertEquals("com.example.myapp", project.applicationId)
        assertEquals(SourceLanguage.KOTLIN, project.language)
        assertEquals(BuildEngine.FAST, project.engine)
    }

    @Test
    fun `project names with spaces and slashes become safe directory names`() = runTest {
        val repository = newRepository()
        val result = repository.createProject(
            name = "My/Weird App!",
            applicationId = "com.example.weird",
            language = SourceLanguage.JAVA,
            engine = BuildEngine.FAST,
        )

        val dirName = (result as AppResult.Success).value.rootDir.name
        assertEquals("My-Weird-App", dirName)
    }

    @Test
    fun `creating a duplicate project fails instead of overwriting`() = runTest {
        val repository = newRepository()
        repository.createProject("Dup", "com.example.dup", SourceLanguage.JAVA, BuildEngine.FAST)

        val second = repository.createProject(
            "Dup", "com.example.dup", SourceLanguage.JAVA, BuildEngine.FAST,
        )

        assertTrue(second is AppResult.Failure)
        // The original must survive the failed second attempt.
        val listed = repository.listProjects() as AppResult.Success
        assertEquals(1, listed.value.size)
    }

    @Test
    fun `directories without a descriptor are not treated as projects`() = runTest {
        val repository = newRepository()
        File(temp.root, "workspace/not-a-project").mkdirs()

        val listed = repository.listProjects() as AppResult.Success
        assertTrue(listed.value.isEmpty())
    }
}
