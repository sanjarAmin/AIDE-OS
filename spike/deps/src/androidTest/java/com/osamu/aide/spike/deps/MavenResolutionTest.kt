package com.osamu.aide.spike.deps

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.apache.maven.repository.internal.MavenRepositorySystemUtils
import org.eclipse.aether.DefaultRepositorySystemSession
import org.eclipse.aether.RepositorySystem
import org.eclipse.aether.artifact.Artifact
import org.eclipse.aether.artifact.DefaultArtifact
import org.eclipse.aether.collection.CollectRequest
import org.eclipse.aether.graph.Dependency
import org.eclipse.aether.repository.LocalRepository
import org.eclipse.aether.repository.RemoteRepository
import org.eclipse.aether.resolution.ArtifactDescriptorRequest
import org.eclipse.aether.resolution.ArtifactRequest
import org.eclipse.aether.resolution.DependencyRequest
import org.eclipse.aether.util.graph.transformer.ChainedDependencyGraphTransformer
import org.eclipse.aether.util.graph.transformer.ConflictResolver
import org.eclipse.aether.util.graph.transformer.JavaDependencyContextRefiner
import org.eclipse.aether.util.graph.transformer.JavaScopeDeriver
import org.eclipse.aether.util.graph.transformer.JavaScopeSelector
import org.eclipse.aether.util.graph.transformer.SimpleOptionalitySelector
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.system.measureTimeMillis

/**
 * Spike R4: Maven resolution on ART, asked the questions M4 depends on.
 *
 * Each test isolates one layer so a failure names the layer rather than the
 * spike: (1) the object graph can be built at all without Sisu generating
 * bytecode, (2) a POM can be read, which needs Maven's own model builder and a
 * StAX parser, (3) a real transitive graph resolves over the network, and (4)
 * an `.aar` -- a packaging Maven has no concept of -- actually produces a file.
 *
 * Network is required and is the point: this is about whether the thing works
 * on a device, not about hermetic unit testing.
 */
@RunWith(AndroidJUnit4::class)
class MavenResolutionTest {

    private lateinit var localRepo: File

    private val google = RemoteRepository.Builder(
        "google", "default", "https://dl.google.com/dl/android/maven2/",
    ).build()

    private val central = RemoteRepository.Builder(
        "central", "default", "https://repo1.maven.org/maven2/",
    ).build()

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        localRepo = File(context.cacheDir, "m2").apply { mkdirs() }
    }

    private fun system(): RepositorySystem = AndroidRepositorySystemSupplier().get()

    private fun session(system: RepositorySystem): DefaultRepositorySystemSession =
        MavenRepositorySystemUtils.newSession().also { session ->
            session.localRepositoryManager =
                system.newLocalRepositoryManager(session, LocalRepository(localRepo))
            // Gradle semantics, not Maven's; see HighestVersionSelector.
            session.dependencyGraphTransformer = ChainedDependencyGraphTransformer(
                ConflictResolver(
                    HighestVersionSelector(),
                    JavaScopeSelector(),
                    SimpleOptionalitySelector(),
                    JavaScopeDeriver(),
                ),
                JavaDependencyContextRefiner(),
            )
        }

    /** Question 1: does the object graph build without Sisu? */
    @Test
    fun the_repository_system_can_be_constructed_on_art() {
        val system = system()
        Log.i(TAG, "RepositorySystem = ${system.javaClass.name}")
        assertTrue("no RepositorySystem", true)
    }

    /** Question 2: can a POM be parsed? This is where the model builder lands. */
    @Test
    fun a_pom_can_be_read() {
        val system = system()
        val session = session(system)

        val request = ArtifactDescriptorRequest(
            DefaultArtifact("androidx.appcompat:appcompat:1.7.0"),
            listOf(google, central),
            null,
        )
        val descriptor = system.readArtifactDescriptor(session, request)

        Log.i(TAG, "appcompat declares ${descriptor.dependencies.size} dependencies")
        descriptor.dependencies.take(8).forEach { Log.i(TAG, "  $it") }
        assertTrue("a POM with no dependencies is not appcompat's", descriptor.dependencies.isNotEmpty())
    }

    /**
     * Questions 3 and 4: a real transitive graph, ending in files on disk.
     *
     * Resolved in two phases, and the second phase is the finding. Maven's
     * model has no concept of `aar`: a dependency declared without an explicit
     * `<type>` defaults to `jar`, and while AndroidX POMs mark *some* of theirs
     * as `aar`, plenty do not -- they rely on Gradle Module Metadata, which
     * Maven cannot read. So the collector asks for
     * `lifecycle-runtime-2.6.2.jar`, which has never existed, and the whole
     * resolution fails on an artifact that is sitting right there as an `.aar`.
     *
     * Collecting the graph first and resolving each node individually, falling
     * back from `jar` to `aar`, is what gets files on disk. `:engine:deps` will
     * have to do something of this shape whatever else it does.
     */
    @Test
    fun appcompat_resolves_transitively_to_files() {
        val system = system()
        val session = session(system)
        val repositories = listOf(google, central)

        val collect = CollectRequest(
            Dependency(DefaultArtifact("androidx.appcompat:appcompat:aar:1.7.0"), "compile"),
            repositories,
        )

        val wanted = mutableListOf<Artifact>()
        val resolved = mutableListOf<File>()
        val fellBackToAar = mutableListOf<String>()

        val millis = measureTimeMillis {
            val root = system.collectDependencies(session, collect).root
            root.accept(
                object : org.eclipse.aether.graph.DependencyVisitor {
                    override fun visitEnter(node: org.eclipse.aether.graph.DependencyNode): Boolean {
                        node.dependency?.artifact?.let(wanted::add)
                        return true
                    }
                    override fun visitLeave(node: org.eclipse.aether.graph.DependencyNode) = true
                },
            )

            for (artifact in wanted.distinctBy { "${it.groupId}:${it.artifactId}:${it.version}" }) {
                val file = resolveWithAarFallback(system, session, repositories, artifact) { name ->
                    fellBackToAar += name
                }
                if (file != null) resolved += file
            }
        }

        // The number that decides whether this is usable. The first pass is
        // network-bound and pays for every artifact plus its checksum; the
        // second reads the same graph out of the local repository, which is
        // what every build after the first one does.
        val warmMillis = measureTimeMillis {
            val root = system.collectDependencies(session, collect).root
            val again = mutableListOf<Artifact>()
            root.accept(
                object : org.eclipse.aether.graph.DependencyVisitor {
                    override fun visitEnter(node: org.eclipse.aether.graph.DependencyNode): Boolean {
                        node.dependency?.artifact?.let(again::add)
                        return true
                    }
                    override fun visitLeave(node: org.eclipse.aether.graph.DependencyNode) = true
                },
            )
            again.distinctBy { "${it.groupId}:${it.artifactId}:${it.version}" }.forEach { artifact ->
                resolveWithAarFallback(system, session, repositories, artifact) {}
            }
        }

        Log.i(TAG, "collected ${wanted.size} nodes, resolved ${resolved.size} files in $millis ms")
        Log.i(TAG, "warm re-resolution of the same graph: $warmMillis ms")
        Log.i(TAG, "fell back from jar to aar for ${fellBackToAar.size}: ${fellBackToAar.take(6)}")
        resolved.take(10).forEach { Log.i(TAG, "  ${it.name} (${it.length()} bytes)") }

        assertTrue("nothing resolved", resolved.isNotEmpty())
        assertTrue(
            "expected the appcompat aar itself, got ${resolved.map { it.name }.take(10)}",
            resolved.any { it.name.startsWith("appcompat-1.7.0") && it.extension == "aar" },
        )
        assertTrue("every resolved artifact should exist on disk", resolved.all { it.isFile })
        assertTrue(
            "if nothing needed the aar fallback then Maven read AndroidX correctly, " +
                "and this spike's central claim is wrong",
            fellBackToAar.isNotEmpty(),
        )
    }

    private fun resolveWithAarFallback(
        system: RepositorySystem,
        session: DefaultRepositorySystemSession,
        repositories: List<RemoteRepository>,
        artifact: Artifact,
        onFallback: (String) -> Unit,
    ): File? {
        runCatching {
            return system.resolveArtifact(session, ArtifactRequest(artifact, repositories, null))
                .artifact.file
        }
        if (artifact.extension != "jar") return null

        val asAar = DefaultArtifact(
            artifact.groupId, artifact.artifactId, artifact.classifier, "aar", artifact.version,
        )
        return runCatching {
            system.resolveArtifact(session, ArtifactRequest(asAar, repositories, null))
                .artifact.file.also { onFallback("${artifact.artifactId}:${artifact.version}") }
        }.getOrNull()
    }

    private companion object {
        const val TAG = "DepsSpike"
    }
}
