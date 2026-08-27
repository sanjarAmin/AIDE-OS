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
import org.eclipse.aether.util.version.GenericVersionScheme
import org.eclipse.aether.version.Version
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

            val wanted = withoutAbsorbedModules(
                withoutDuplicateKmpVariants(
                    withoutSupersededModules(collect(system, session, coordinates, onProgress)),
                ),
            )
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
        onProgress(ResolutionProgress.Collecting(coordinates.joinToString(", ")))

        // **One request for every coordinate, not one request each.**
        //
        // Conflict resolution happens inside a collect, so a loop resolves each
        // declared dependency against itself and never against its siblings.
        // Merging those graphs afterwards keeps whichever version the
        // *first-listed* coordinate happened to want -- making the resolved
        // classpath depend on the order the user typed their dependencies, and
        // silently pinning shared modules to whatever the first one asked for.
        //
        // It is not theoretical. `activity-compose` reaches `compose.runtime`
        // at 1.7.0 and `foundation-android:1.12.0` reaches it far newer;
        // resolved separately and merged, 1.7.0 won on listing order alone, and
        // 1.7.0 still contains the annotations later versions moved into
        // `runtime-annotation` -- so D8 failed on a duplicate
        // `androidx.compose.runtime.Immutable`. FINDINGS section 3.
        //
        // Asked for as aars: AndroidX overwhelmingly is one, and a wrong guess
        // costs only the fallback that phase two performs anyway.
        val roots = coordinates.map {
            Dependency(DefaultArtifact("$it").withExtension("aar"), "compile")
        }

        return collectOnce(system, session, roots)
    }

    private fun collectOnce(
        system: RepositorySystem,
        session: DefaultRepositorySystemSession,
        roots: List<Dependency>,
    ): List<Artifact> {
        val collected = LinkedHashMap<String, Artifact>()
        val request = CollectRequest().setDependencies(roots).setRepositories(repositories)

        system.collectDependencies(session, request).root.accept(
            object : DependencyVisitor {
                override fun visitEnter(node: DependencyNode): Boolean {
                    // A version that lost its conflict is still in the graph.
                    if (node.data[ConflictResolver.NODE_DATA_WINNER] != null) return true

                    node.dependency?.artifact?.let { collected.keepHigher(it) }
                    return true
                }

                override fun visitLeave(node: DependencyNode) = true
            },
        )
        return collected.values.toList()
    }

    /**
     * Keeps one artifact per Kotlin Multiplatform module, preferring Android.
     *
     * **This is the gap between Maven resolution and Gradle Module Metadata.**
     * A KMP library publishes a root coordinate (`androidx.collection:collection`)
     * plus one per platform (`collection-jvm`, `compose.ui:ui-android`), and the
     * `.module` file says the root *redirects* to the platform artifact for a
     * given target -- so Gradle resolves exactly one. maven-resolver reads
     * `.pom` only. There each is an ordinary artifact with ordinary
     * dependencies and nothing marks them as one module, so conflict resolution
     * has no reason to object: they are, as far as it can tell, unrelated.
     *
     * Both shapes of the problem show up in a single Compose graph, and both
     * end the build at D8 with `Type ... is defined multiple times`:
     *
     * - **root beside variant** -- `compose.ui:ui:1.0.1`, which
     *   `activity-compose` still names, beside `ui-android:1.9.0`.
     * - **variant beside variant** -- `runtime-annotation-jvm:1.9.2` beside
     *   `runtime-annotation-android:1.12.0`, reached down different branches.
     *
     * **`-android` wins, then `-jvm`, then the root**, by target rather than by
     * version: this is an Android build, so the Android variant is the one that
     * is right even when an older POM pins the root higher.
     *
     * Narrow by construction: artifacts only compete when they share a group
     * *and* a base name, so an unrelated module that merely ends in `-android`
     * is untouched unless its bare name is genuinely in the graph beside it.
     */
    private fun withoutDuplicateKmpVariants(artifacts: List<Artifact>): List<Artifact> {
        fun module(artifact: Artifact): String {
            val suffix = KMP_PLATFORM_SUFFIXES.firstOrNull { artifact.artifactId.endsWith(it) }
            return "${artifact.groupId}:${artifact.artifactId.removeSuffix(suffix.orEmpty())}"
        }

        // Lower is better; KMP_PLATFORM_SUFFIXES is in preference order and the
        // root, which matches nothing, sorts last.
        fun preference(artifact: Artifact): Int =
            KMP_PLATFORM_SUFFIXES.indexOfFirst { artifact.artifactId.endsWith(it) }
                .takeIf { it >= 0 } ?: KMP_PLATFORM_SUFFIXES.size

        val kept = artifacts.groupBy(::module)
            .mapValues { (_, candidates) -> candidates.minBy(::preference) }

        // Identity, not equality: it selects the one instance chosen above
        // rather than anything that merely compares equal to it.
        return artifacts.filter { kept[module(it)] === it }
    }

    /**
     * Drops an AndroidX module whose classes have since moved into another one.
     *
     * The third thing Gradle Module Metadata does that a POM cannot: newer
     * AndroidX modules declare the *capability* of the module they absorbed, so
     * Gradle sees a capability conflict and keeps one. maven-resolver sees two
     * unrelated modules and keeps both, and because nothing in the graph asks
     * for the absorbed module's newer version, it stays pinned at the old one
     * that still contains the classes -- `lifecycle-common-java8:2.3.0` beside
     * `lifecycle-common-jvm:2.9.4`, both defining `DefaultLifecycleObserver`.
     *
     * Verifiable rather than folklore: at the absorbing version the old
     * artifact is published as an **empty jar** that only depends on its
     * successor. `lifecycle-common-java8:2.9.4` is 4.8 kB and contains zero
     * class files; `savedstate-ktx:1.3.0` is 1.4 kB and the same.
     *
     * Gated on the absorbing module's version for the same reason
     * [withoutSupersededModules] is: a project genuinely pinned to lifecycle
     * 2.3 throughout has no other source for these classes, and dropping them
     * there would break a build that works.
     *
     * A curated table, and it will rot. The fix that does not is reading the
     * `.module` files -- see `FINDINGS.md` sections 1 and 6.
     */
    private fun withoutAbsorbedModules(artifacts: List<Artifact>): List<Artifact> {
        fun absorbingIsPresent(absorption: Absorption): Boolean = artifacts.any { candidate ->
            val module = "${candidate.groupId}:${candidate.artifactId}"
            val matches = module == absorption.absorbing ||
                KMP_PLATFORM_SUFFIXES.any { module == "${absorption.absorbing}$it" }
            matches && candidate.version.isAtLeast(absorption.major, absorption.minor)
        }

        return artifacts.filterNot { artifact ->
            val module = "${artifact.groupId}:${artifact.artifactId}"
            ABSORPTIONS.any { it.absorbed == module && absorbingIsPresent(it) }
        }
    }

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

    /**
     * Records [artifact] under its module, keeping the higher version on a clash.
     *
     * **Conflict resolution does not deduplicate this far.** Aether's conflict
     * id is `group:artifact:classifier:extension`, so a module named once as a
     * `jar` and once as an `aar` -- which AndroidX POMs do constantly, since
     * `<type>` is optional and half of them omit it -- forms *two* conflict
     * groups that never compete. Each elects its own winner and both reach this
     * visitor.
     *
     * Taking the first arrival is then a coin toss decided by visit order, and
     * it lost: `compose.runtime:runtime-android` won its `aar` group at 1.12.0
     * and its `jar` group at 1.9.0, the `jar` one was seen first, and the app
     * built against `compose.ui:1.12.0` died on launch with
     * `NoClassDefFoundError: androidx.compose.runtime.HostDefaultProvider` --
     * a class 1.9.0 does not have. FINDINGS section 2.
     *
     * Compared with Aether's own version scheme rather than as strings, which
     * would put `1.9.0` above `1.12.0`. An unparseable version keeps the
     * incumbent: no answer is better than a wrong one, and both are real
     * coordinates that resolved.
     */
    private fun MutableMap<String, Artifact>.keepHigher(artifact: Artifact) {
        val existing = this[artifact.module]
        if (existing == null) {
            this[artifact.module] = artifact
            return
        }
        val a = parseVersion(artifact.version) ?: return
        val b = parseVersion(existing.version) ?: return
        if (a > b) this[artifact.module] = artifact
    }

    private fun parseVersion(version: String): Version? =
        runCatching { VERSION_SCHEME.parseVersion(version) }.getOrNull()

    /** Identity for deduplication: one version of a module, never two. */
    private val Artifact.module: String get() = "$groupId:$artifactId"
    private val Artifact.label: String get() = "$artifactId-$version"

    private fun Artifact.withExtension(extension: String): Artifact =
        DefaultArtifact(groupId, artifactId, classifier, extension, version)

    companion object {
        private const val KOTLIN_GROUP = "org.jetbrains.kotlin"

        /**
         * Maven's own ordering, where `1.12.0` is above `1.9.0`. The obvious
         * string comparison gets that backwards.
         */
        private val VERSION_SCHEME = GenericVersionScheme()

        /** Folded into `kotlin-stdlib` as of Kotlin 1.8. */
        private val STDLIB_ABSORBED_AT_1_8 = setOf("kotlin-stdlib-jdk7", "kotlin-stdlib-jdk8")

        /**
         * The KMP variant suffixes that can appear in an Android graph, **in
         * preference order**.
         *
         * `-android` first because this is an Android build. `-jvm` second, for
         * modules with no Android target of their own --
         * `androidx.collection:collection-jvm` and
         * `androidx.annotation:annotation-jvm` both arrive that way.
         */
        private val KMP_PLATFORM_SUFFIXES = listOf("-android", "-jvm")

        /** [absorbed] moved into [absorbing] as of [major].[minor]. */
        private data class Absorption(
            val absorbed: String,
            val absorbing: String,
            val major: Int,
            val minor: Int,
        )

        private val ABSORPTIONS = listOf(
            Absorption(
                absorbed = "androidx.lifecycle:lifecycle-common-java8",
                absorbing = "androidx.lifecycle:lifecycle-common",
                major = 2,
                minor = 5,
            ),
            Absorption(
                absorbed = "androidx.activity:activity-ktx",
                absorbing = "androidx.activity:activity",
                major = 1,
                minor = 9,
            ),
            Absorption(
                absorbed = "androidx.savedstate:savedstate-ktx",
                absorbing = "androidx.savedstate:savedstate",
                major = 1,
                minor = 3,
            ),
        )

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
