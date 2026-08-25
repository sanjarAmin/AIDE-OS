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

            val wanted = collect(system, session, coordinates, onProgress)
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

    /** Phase one: the graph, downloading POMs but no artifacts. */
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
                    node.dependency?.artifact?.let { collected.putIfAbsent(it.key, it) }
                    return true
                }

                override fun visitLeave(node: DependencyNode) = true
            })
        }
        return collected.values.toList()
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

    private val Artifact.key: String get() = "$groupId:$artifactId:$version"
    private val Artifact.label: String get() = "$artifactId-$version"

    private fun Artifact.withExtension(extension: String): Artifact =
        DefaultArtifact(groupId, artifactId, classifier, extension, version)

    companion object {
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
