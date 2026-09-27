package com.osamu.aide.engine.deps

import com.osamu.aide.core.common.AppError
import com.osamu.aide.core.common.AppResult
import com.osamu.aide.core.common.DispatcherProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
import org.eclipse.aether.util.repository.SimpleResolutionErrorPolicy
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
 * Resolution happens in **phases**, and the shape is forced rather than chosen.
 *
 * *Collect.* Maven's model has no concept of an `aar`: a dependency with no
 * explicit `<type>` defaults to `jar`, and AndroidX puts the real packaging in
 * Gradle Module Metadata. So the collector asks for
 * `lifecycle-runtime-2.6.2.jar`, a file that has never existed, and the
 * all-or-nothing `resolveDependencies` call fails the whole graph over it.
 * Collecting first and resolving each node separately -- falling back between
 * `jar` and `aar` -- is what produces files. In a plain `appcompat` graph
 * exactly one artifact of 46 needs the fallback, and one is enough.
 *
 * *Read metadata.* maven-resolver cannot use a `.module` file, but this can
 * read one. [ModuleMetadata] takes two things from it that no POM can express:
 * where a Kotlin Multiplatform root really lives, and the version floors a
 * module publishes for its own group. Without them an AndroidX graph resolves
 * to something that compiles and then fails at D8 or at launch. `FINDINGS.md`
 * sections 1 to 4.
 *
 * *Align, filter, resolve.* The floors are applied by collecting once more, the
 * duplicates a POM cannot see are dropped, and each surviving artifact is
 * fetched.
 *
 * The whole of `tools/deps/FINDINGS.md` applies here; the other workarounds
 * live in [AndroidRepositorySystemSupplier] and [HighestVersionSelector].
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

            val collected = collect(system, session, coordinates, onProgress)
            // Read once and passed down, because every step below asks the same
            // files the same questions and each read is a network round trip on
            // a cold repository.
            val metadata = metadataFor(system, session, collected, onProgress)

            val wanted = withoutDuplicateKmpVariants(
                withoutSupersededModules(
                    aligned(system, session, coordinates, collected, metadata, onProgress),
                ),
                metadata,
            )
            val resolved = mutableListOf<ResolvedDependency>()
            val unresolved = mutableListOf<String>()

            // What each module resolved to *before* alignment, so a raised
            // version that turns out not to exist can fall back to one that
            // does. See [resolveAligned].
            val beforeAlignment = collected.associateBy { it.module }

            wanted.forEachIndexed { index, artifact ->
                onProgress(ResolutionProgress.Downloading(artifact.label, index + 1, wanted.size))
                val (chosen, file) = resolveAligned(system, session, artifact, beforeAlignment)
                if (file == null) {
                    unresolved += artifact.label
                    return@forEachIndexed
                }
                val dependency = asDependency(chosen, file, onProgress)
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
        // `androidx.compose.runtime.Immutable`. FINDINGS section 6.
        return collectOnce(system, session, rootsFor(coordinates))
    }

    /**
     * The declared coordinates as Aether roots.
     *
     * Asked for as aars: AndroidX overwhelmingly is one, and a wrong guess
     * costs only the fallback that phase two performs anyway.
     */
    private fun rootsFor(coordinates: List<Coordinate>): List<Dependency> = coordinates.map {
        Dependency(DefaultArtifact("$it").withExtension("aar"), "compile")
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
     * Reads every collected artifact's `.module` file, keyed by `group:artifact`.
     *
     * Most of them have none, and that is ordinary: a plain Maven library
     * publishes only a POM. The map is therefore sparse, and every caller has
     * to work without an entry.
     *
     * One pass, before anything else looks at the graph. On a cold local
     * repository this is one request per artifact, which is why it reports
     * progress; on a warm one it is a file read.
     */
    private suspend fun metadataFor(
        system: RepositorySystem,
        session: DefaultRepositorySystemSession,
        artifacts: List<Artifact>,
        onProgress: (ResolutionProgress) -> Unit,
    ): Map<String, ModuleMetadata> = coroutineScope {
        val done = java.util.concurrent.atomic.AtomicInteger()
        val gate = Semaphore(METADATA_CONCURRENCY)

        // **In parallel, and that is not an optimisation.** Serially this took
        // 19 s on a *warm* local repository against a 635 ms collect -- one
        // round trip per artifact, most of them latency. It made a resolve
        // that fits in a build loop into one that does not, and the budget test
        // in DependencyResolverTest is what caught it.
        val results = artifacts.map { artifact ->
            async {
                gate.withPermit {
                    val metadata = ModuleMetadata.read(system, session, repositories, artifact)
                    onProgress(
                        ResolutionProgress.Collecting(
                            "metadata ${done.incrementAndGet()}/${artifacts.size}",
                        ),
                    )
                    artifact.module to metadata
                }
            }
        }.awaitAll()

        results.mapNotNull { (module, metadata) -> metadata?.let { module to it } }.toMap()
    }

    /**
     * Applies the version floors AndroidX publishes for its own group.
     *
     * **The third thing Gradle Module Metadata does that a POM cannot**, and
     * the one that had no fix at all until now. Every AndroidX module carries a
     * `dependencyConstraints` block pinning its whole group to its own version
     * -- the alignment a BOM would give, published per module. Without it a
     * graph keeps whatever version each POM happened to name, and modules that
     * were split or absorbed end up beside each other at incompatible versions.
     *
     * **One extra pass, not a fixpoint.** Gradle iterates until the constraints
     * stop moving; this collects again exactly once with the floors it found.
     * That is a deliberate bound: an earlier attempt at group-wide alignment
     * pinned every module to the highest version *seen* rather than to what was
     * published, and the resulting graph exhausted ART's 192 MB heap.
     * `FINDINGS.md` section 1.
     *
     * Only modules **already in the graph** are managed, for the same reason.
     * A constraint naming `lifecycle-reactivestreams` does not put it on the
     * classpath; it only decides which version is used if something else asks
     * for it.
     */
    private fun aligned(
        system: RepositorySystem,
        session: DefaultRepositorySystemSession,
        declared: List<Coordinate>,
        collected: List<Artifact>,
        metadata: Map<String, ModuleMetadata>,
        onProgress: (ResolutionProgress) -> Unit,
    ): List<Artifact> {
        val present = collected.associateBy { it.module }
        val floors = LinkedHashMap<String, Coordinate>()

        metadata.values.asSequence().flatMap { it.constraints }.forEach { constraint ->
            val module = "${constraint.group}:${constraint.artifact}"
            val current = present[module] ?: return@forEach
            val raises = parseVersion(constraint.version)?.let { wanted ->
                parseVersion(current.version)?.let { have -> wanted > have }
            } == true
            if (!raises) return@forEach
            // Two modules may both constrain a third. The higher floor wins,
            // which is what a set of constraints means.
            val standing = floors[module]?.version?.let(::parseVersion)
            val candidate = parseVersion(constraint.version)
            if (standing == null || (candidate != null && candidate > standing)) {
                floors[module] = constraint
            }
        }

        if (floors.isEmpty()) return collected

        onProgress(ResolutionProgress.Collecting("aligning ${floors.size} module(s)"))
        return collectManaged(system, session, declared, collected, floors.values.toList())
    }

    /**
     * Collects again with [floors] added as roots.
     *
     * **Roots, not managed dependencies**, and that distinction cost an
     * afternoon. `CollectRequest.setManagedDependencies` is the obvious way to
     * express a version floor and it does not reach these: Aether's classic
     * dependency manager applies the root request's management only from a
     * certain depth, so `activity-ktx` stayed at the 1.7.0 a transitive POM
     * asked for while the floor said 1.13.0.
     *
     * As a root it is depth 0, and this session's [HighestVersionSelector]
     * takes the highest of any conflict -- so the floor wins for exactly the
     * reason every other version does. It also fits what a floor *is*: nothing
     * new is put on the classpath, because only modules already in the graph
     * are given one.
     *
     * The user's declared coordinates stay roots too. Re-rooting at every
     * collected artifact would ask for a different graph than the one being
     * aligned.
     *
     * A failure here returns the unaligned graph rather than failing the build.
     * Alignment makes a correct graph out of a working one; if the second
     * collect cannot be done, the first still builds something.
     */
    private fun collectManaged(
        system: RepositorySystem,
        session: DefaultRepositorySystemSession,
        declared: List<Coordinate>,
        collected: List<Artifact>,
        floors: List<Coordinate>,
    ): List<Artifact> = runCatching {
        // The extension each floor's module already resolved as, rather than
        // the aar every declared root is guessed to be: the graph has already
        // proved what these are, and guessing again would only be wrong.
        val known = collected.associate { it.module to it.extension }
        val roots = rootsFor(declared) + floors.map { floor ->
            val extension = known["${floor.group}:${floor.artifact}"] ?: "aar"
            Dependency(DefaultArtifact("$floor").withExtension(extension), "compile")
        }
        val request = CollectRequest()
            .setDependencies(roots)
            .setRepositories(repositories)

        val aligned = LinkedHashMap<String, Artifact>()
        system.collectDependencies(session, request).root.accept(
            object : DependencyVisitor {
                override fun visitEnter(node: DependencyNode): Boolean {
                    if (node.data[ConflictResolver.NODE_DATA_WINNER] != null) return true
                    node.dependency?.artifact?.let { aligned.keepHigher(it) }
                    return true
                }

                override fun visitLeave(node: DependencyNode) = true
            },
        )
        aligned.values.toList().takeIf { it.isNotEmpty() } ?: collected
    }.getOrDefault(collected)

    /**
     * Keeps one artifact per Kotlin Multiplatform module.
     *
     * **The gap between Maven resolution and Gradle Module Metadata.** A KMP
     * library publishes a root coordinate (`androidx.collection:collection`)
     * plus one artifact per platform (`collection-jvm`,
     * `compose.ui:ui-android`), and the root's `.module` file says its variants
     * are *`available-at`* the platform artifact. Gradle follows that and
     * resolves exactly one. maven-resolver reads `.pom` only, where each is an
     * ordinary artifact with ordinary dependencies and nothing marks them as
     * one module -- so conflict resolution has no reason to object, and D8 ends
     * the build with `Type ... is defined multiple times`.
     *
     * Both shapes occur in a single Compose graph:
     *
     * - **root beside variant** -- `compose.ui:ui:1.0.1`, which
     *   `activity-compose` still names, beside `ui-android:1.9.0`.
     * - **variant beside variant** -- `runtime-annotation-jvm:1.9.2` beside
     *   `runtime-annotation-android:1.12.0`, down different branches.
     *
     * **The redirect is read rather than guessed.** This used to collapse any
     * two artifacts sharing a group and a base name, preferring an `-android`
     * suffix -- which is the right answer for AndroidX and an assumption
     * everywhere else: two unrelated modules that happen to be named `foo` and
     * `foo-android` would have had one silently dropped. Now a root is only
     * collapsed into a variant when its own metadata says that variant is where
     * it lives.
     *
     * The suffix rule survives as the fallback for the variant-beside-variant
     * case, which no root's metadata describes: neither `-jvm` nor `-android`
     * redirects anywhere, and the only thing relating them is the name.
     */
    private fun withoutDuplicateKmpVariants(
        artifacts: List<Artifact>,
        metadata: Map<String, ModuleMetadata>,
    ): List<Artifact> {
        val present = artifacts.associateBy { it.module }

        // A root whose metadata redirects to a variant that is also in the
        // graph is a duplicate of it, and the variant is the one with the
        // classes an Android build wants.
        val redirected = artifacts.filter { artifact ->
            val target = metadata[artifact.module]?.redirect ?: return@filter false
            present.containsKey("${target.group}:${target.artifact}")
        }.map { it.module }.toSet()

        fun base(artifact: Artifact): String {
            val suffix = KMP_PLATFORM_SUFFIXES.firstOrNull { artifact.artifactId.endsWith(it) }
            return "${artifact.groupId}:${artifact.artifactId.removeSuffix(suffix.orEmpty())}"
        }

        // Lower is better; KMP_PLATFORM_SUFFIXES is in preference order and a
        // bare name, matching nothing, sorts last.
        fun preference(artifact: Artifact): Int =
            KMP_PLATFORM_SUFFIXES.indexOfFirst { artifact.artifactId.endsWith(it) }
                .takeIf { it >= 0 } ?: KMP_PLATFORM_SUFFIXES.size

        val remaining = artifacts.filterNot { it.module in redirected }
        val kept = remaining.groupBy(::base)
            .mapValues { (_, candidates) -> candidates.minBy(::preference) }

        // Identity, not equality: this selects the instance chosen above rather
        // than anything that merely compares equal to it.
        return remaining.filter { kept[base(it)] === it }
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

    /**
     * Resolves [artifact], falling back to the version it had before alignment.
     *
     * **A published constraint can name a version that does not exist as a
     * file.** `org.jetbrains.kotlin:kotlin-stdlib-common` is the case that
     * found this: at 2.x it is a metadata-only module for Kotlin
     * Multiplatform, so a constraint raising it to `2.1.20` produces a
     * coordinate with a POM and no jar, and the classpath silently loses an
     * entry it had before.
     *
     * Alignment is an improvement to a graph that already worked, so it is not
     * allowed to make one unresolvable. Where the raised version cannot be
     * fetched, the version the first collect chose is used instead and the
     * build carries on.
     *
     * Returns the artifact actually used, which is not always the one asked
     * for -- the caller needs that to record the right coordinate.
     */
    private fun resolveAligned(
        system: RepositorySystem,
        session: DefaultRepositorySystemSession,
        artifact: Artifact,
        beforeAlignment: Map<String, Artifact>,
    ): Pair<Artifact, File?> {
        val file = resolveWithAarFallback(system, session, artifact)
        if (file != null) return artifact to file

        val original = beforeAlignment[artifact.module]
        if (original == null || original.version == artifact.version) return artifact to null

        val fallback = resolveWithAarFallback(system, session, original)
        return if (fallback != null) original to fallback else artifact to null
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

        // **Both directions.** This used to fall back only jar -> aar, on the
        // assumption that a wrong guess is always "we said jar and AndroidX
        // publishes an aar". The version floors added for alignment are
        // declared as aars like every other root, and `lifecycle-common-java8`
        // is a jar -- so the aligned coordinate failed to resolve, the caller
        // fell back to the *older* version it was aligning away from, and the
        // duplicate class it exists to prevent came back.
        val other = if (artifact.extension == "aar") "jar" else "aar"
        return runCatching {
            system.resolveArtifact(
                session,
                ArtifactRequest(artifact.withExtension(other), repositories, null),
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
            // **Remember which artifacts do not exist.** Most of a graph
            // publishes no `.module` file at all, and without this every
            // resolve asks the remote again for each of them -- which is most
            // of what made reading metadata cost 19 s on a warm repository.
            session.resolutionErrorPolicy = SimpleResolutionErrorPolicy(true, false)
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
     * a class 1.9.0 does not have. FINDINGS section 5.
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
         * How many `.module` files to ask for at once.
         *
         * Enough to hide per-request latency, few enough not to look like an
         * attack to a repository that rate-limits. The same shape the artifact
         * downloads should eventually take, and do not yet.
         */
        private const val METADATA_CONCURRENCY = 8

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
