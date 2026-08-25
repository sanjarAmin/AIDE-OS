package com.osamu.aide.engine.deps

import com.osamu.aide.core.common.AppError
import com.osamu.aide.core.common.AppResult
import com.osamu.aide.core.common.DispatcherProvider
import kotlinx.coroutines.withContext
import org.apache.maven.repository.internal.MavenRepositorySystemUtils
import org.eclipse.aether.DefaultRepositorySystemSession
import org.eclipse.aether.RepositorySystem
import org.eclipse.aether.artifact.Artifact
import org.eclipse.aether.artifact.DefaultArtifact
import org.eclipse.aether.collection.CollectRequest
import org.eclipse.aether.graph.Dependency
import org.eclipse.aether.graph.DependencyNode
import org.eclipse.aether.graph.DependencyVisitor
import org.eclipse.aether.repository.LocalRepository
import org.eclipse.aether.repository.RemoteRepository
import org.eclipse.aether.resolution.ArtifactRequest
import org.eclipse.aether.util.graph.transformer.ChainedDependencyGraphTransformer
import org.eclipse.aether.util.graph.transformer.ConflictResolver
import org.eclipse.aether.util.graph.transformer.JavaDependencyContextRefiner
import org.eclipse.aether.util.graph.transformer.JavaScopeDeriver
import org.eclipse.aether.util.graph.transformer.JavaScopeSelector
import org.eclipse.aether.util.graph.transformer.SimpleOptionalitySelector
import java.io.File

/**
 * Turns declared coordinates into files a build can use.
 *
 * Resolution happens in **two phases**, and that is a requirement rather than a
 * structure choice. Maven's model has no concept of an `aar`: a dependency with
 * no explicit `<type>` defaults to `jar`, and AndroidX puts the real packaging
 * in Gradle Module Metadata, which Maven cannot read. So the collector asks for
 * `lifecycle-runtime-2.6.2.jar`, a file that has never existed, and the
 * all-or-nothing `resolveDependencies` call fails the entire graph over it.
 * Collecting first and resolving each node separately -- falling back from
 * `jar` to `aar` -- is what produces files. In a plain `appcompat` graph exactly
 * one artifact of 46 needs the fallback, and one is enough.
 *
 * The whole of `tools/deps/FINDINGS.md` applies here; the three other
 * workarounds live in [AndroidRepositorySystemSupplier] and
 * [HighestVersionSelector].
 *
 * Runs on the IO dispatcher, not the compiler one: this is almost entirely
 * network and disk, and it should not compete with a build for CPU.
 */
class DependencyResolver(
    /**
     * The local Maven repository. Belongs in cache storage -- every file in it
     * is re-downloadable, and the system should be free to reclaim it.
     */
    private val localRepository: File,
    private val dispatchers: DispatcherProvider,
    private val repositories: List<RemoteRepository> = DEFAULT_REPOSITORIES,
) {

    /**
     * Resolves [coordinates] and everything they depend on.
     *
     * The first call for a given graph is slow in a way no amount of tuning
     * fixes -- 46 artifacts is closer to 90 requests once Maven has asked for a
     * checksum beside each one, measured at 92 s on an emulator. Afterwards the
     * same graph resolves from the local repository in well under a second.
     * [onProgress] exists because of that first call.
     */
    suspend fun resolve(
        coordinates: List<Coordinate>,
        onProgress: (ResolutionProgress) -> Unit = {},
    ): AppResult<ResolvedDependencies> = withContext(dispatchers.io) {
        if (coordinates.isEmpty()) return@withContext AppResult.Success(ResolvedDependencies())

        try {
            val system = AndroidRepositorySystemSupplier().get()
            val session = session(system)

            val wanted = withoutSupersededModules(collect(system, session, coordinates, onProgress))
            val resolved = mutableListOf<ResolvedDependency>()
            val unresolved = mutableListOf<String>()

            wanted.forEachIndexed { index, artifact ->
                onProgress(ResolutionProgress.Downloading(artifact.label, index + 1, wanted.size))
                val file = resolveWithAarFallback(system, session, artifact)
                if (file == null) {
                    unresolved += artifact.label
                    return@forEachIndexed
                }
                val dependency = asDependency(artifact, file, onProgress)
                if (dependency == null) unresolved += artifact.label else resolved += dependency
            }

            AppResult.Success(ResolvedDependencies(resolved, unresolved))
        } catch (failure: Exception) {
            AppResult.Failure(
                AppError("Could not resolve dependencies: ${failure.message}", failure),
            )
        }
    }

    /**
     * Phase one: the graph, downloading POMs but no artifacts.
     *
     * Two things stop the same module appearing twice, and both are needed.
     *
     * Conflict resolution does not *remove* the versions it rejected -- it
     * leaves them in the graph carrying a `conflict.winner` marker pointing at
     * the node that won. A visitor that takes every node it is shown therefore
     * collects both, and D8 then fails the build with "Type ... is defined
     * multiple times". Skipping marked losers is the primary fix.
     *
     * Deduplicating by `group:artifact` rather than by full coordinate is the
     * backstop. Two versions of one module on a classpath is never what anyone
     * wants, and if a marker is ever missed the symptom is a dex failure deep
     * in a build rather than anything pointing here.
     */
    private fun collect(
        system: RepositorySystem,
        session: DefaultRepositorySystemSession,
        coordinates: List<Coordinate>,
        onProgress: (ResolutionProgress) -> Unit,
    ): List<Artifact> {
        val collected = LinkedHashMap<String, Artifact>()

        coordinates.forEach { coordinate ->
            onProgress(ResolutionProgress.Collecting(coordinate.toString()))
            // Asked for as an aar: AndroidX overwhelmingly is one, and a wrong
            // guess costs only the fallback that phase two performs anyway.
            val root = Dependency(DefaultArtifact("$coordinate").withExtension("aar"), "compile")
            val node = system.collectDependencies(session, CollectRequest(root, repositories)).root

            node.accept(object : DependencyVisitor {
                override fun visitEnter(node: DependencyNode): Boolean {
                    // A version that lost its conflict is still in the graph.
                    if (node.data[ConflictResolver.NODE_DATA_WINNER] != null) return true

                    node.dependency?.artifact?.let { collected.putIfAbsent(it.module, it) }
                    return true
                }

                override fun visitLeave(node: DependencyNode) = true
            })
        }
        return collected.values.toList()
    }

    /**
     * Drops modules whose classes another module on the graph now contains.
     *
     * Maven has no notion of *capabilities*, so it cannot express "this
     * artifact replaces that one" -- and when one module absorbs another's
     * classes, both end up on the classpath and D8 refuses the build with
     * "Type ... is defined multiple times". Gradle solves this with capability
     * rules it ships built in; resolving with Maven means applying the same
     * knowledge by hand.
     *
     * Only one such conflict is known to bite, and it bites every Kotlin
     * project that touches AndroidX: Kotlin 1.8 folded `kotlin-stdlib-jdk7` and
     * `kotlin-stdlib-jdk8` into `kotlin-stdlib`. Older AndroidX artifacts still
     * depend on the split ones, so a graph routinely contains
     * `kotlin-stdlib:1.8.x` beside `kotlin-stdlib-jdk7:1.6.x`, and the two
     * genuinely share classes.
     *
     * Deliberately a short, named list rather than a general mechanism. Every
     * entry is a claim about the ecosystem that could stop being true, and a
     * list of three is auditable in a way a heuristic is not.
     */
    private fun withoutSupersededModules(artifacts: List<Artifact>): List<Artifact> {
        val stdlib = artifacts.firstOrNull {
            it.groupId == KOTLIN_GROUP && it.artifactId == "kotlin-stdlib"
        } ?: return artifacts
        if (!stdlib.version.isAtLeast(major = 1, minor = 8)) return artifacts

        return artifacts.filterNot {
            it.groupId == KOTLIN_GROUP && it.artifactId in STDLIB_ABSORBED_AT_1_8
        }
    }

    /** `1.8.22` is at least 1.8; `1.6.21` is not. Non-numeric parts are ignored. */
    private fun String.isAtLeast(major: Int, minor: Int): Boolean {
        val parts = split('.', '-').mapNotNull { it.toIntOrNull() }
        val actualMajor = parts.getOrNull(0) ?: return false
        val actualMinor = parts.getOrNull(1) ?: 0
        return actualMajor > major || (actualMajor == major && actualMinor >= minor)
    }

    /** Phase two, per artifact. See the class comment for why this exists. */
    private fun resolveWithAarFallback(
        system: RepositorySystem,
        session: DefaultRepositorySystemSession,
        artifact: Artifact,
    ): File? {
        runCatching {
            return system.resolveArtifact(session, ArtifactRequest(artifact, repositories, null))
                .artifact.file
        }
        if (artifact.extension == "aar") return null

        return runCatching {
            system.resolveArtifact(
                session,
                ArtifactRequest(artifact.withExtension("aar"), repositories, null),
            ).artifact.file
        }.getOrNull()
    }

    private fun asDependency(
        artifact: Artifact,
        file: File,
        onProgress: (ResolutionProgress) -> Unit,
    ): ResolvedDependency? {
        val coordinate = Coordinate(artifact.groupId, artifact.artifactId, artifact.version)
        return if (file.extension.equals("aar", ignoreCase = true)) {
            onProgress(ResolutionProgress.Extracting(artifact.label))
            AarExtractor.extract(coordinate, file)
        } else {
            ResolvedDependency(coordinate, file)
        }
    }

    private fun session(system: RepositorySystem): DefaultRepositorySystemSession =
        MavenRepositorySystemUtils.newSession().also { session ->
            session.localRepositoryManager =
                system.newLocalRepositoryManager(session, LocalRepository(localRepository))
            // Gradle's conflict semantics, not Maven's. Not optional: AndroidX
            // declares hard version ranges that nearest-wins cannot solve at
            // all, and where it can it picks older artifacts than the same
            // project gets from Gradle.
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

    /** Identity for deduplication: one version of a module, never two. */
    private val Artifact.module: String get() = "$groupId:$artifactId"
    private val Artifact.label: String get() = "$artifactId-$version"

    private fun Artifact.withExtension(extension: String): Artifact =
        DefaultArtifact(groupId, artifactId, classifier, extension, version)

    companion object {
        private const val KOTLIN_GROUP = "org.jetbrains.kotlin"

        /** Folded into `kotlin-stdlib` as of Kotlin 1.8. */
        private val STDLIB_ABSORBED_AT_1_8 = setOf("kotlin-stdlib-jdk7", "kotlin-stdlib-jdk8")

        /**
         * Google first, then Central.
         *
         * Order is not cosmetic: AndroidX lives only on Google's repository, and
         * asking Central for it first spends a 404 on every AndroidX artifact in
         * the graph -- which, on a first resolve over mobile data, is the
         * difference the user feels.
         */
        val DEFAULT_REPOSITORIES: List<RemoteRepository> = listOf(
            RemoteRepository.Builder(
                "google", "default", "https://dl.google.com/dl/android/maven2/",
            ).build(),
            RemoteRepository.Builder(
                "central", "default", "https://repo1.maven.org/maven2/",
            ).build(),
        )
    }
}
