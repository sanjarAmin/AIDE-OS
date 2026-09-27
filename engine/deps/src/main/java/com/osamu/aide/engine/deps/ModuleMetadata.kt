package com.osamu.aide.engine.deps

import org.eclipse.aether.RepositorySystem
import org.eclipse.aether.RepositorySystemSession
import org.eclipse.aether.artifact.Artifact
import org.eclipse.aether.artifact.DefaultArtifact
import org.eclipse.aether.repository.RemoteRepository
import org.eclipse.aether.resolution.ArtifactRequest
import org.json.JSONObject
import java.io.File

/**
 * A module's Gradle Module Metadata: the `.module` file beside its POM.
 *
 * **This is the file that makes AndroidX resolve correctly**, and the reason
 * `:engine:deps` needed two curated tables before it was read. `FINDINGS.md`
 * sections 1 to 4 record what those tables cost and what reading this instead
 * costs -- which is mostly latency, and is why the reads are parallel.
 *
 * Two things are taken from it, because they are the two a POM cannot express:
 *
 * - **[redirect]** — a Kotlin Multiplatform module publishes a root coordinate
 *   plus one artifact per platform, and the root's variants say
 *   `available-at` the platform artifact. Gradle follows that and resolves
 *   exactly one; maven-resolver sees two unrelated modules with the same
 *   classes in them and D8 rejects the result.
 * - **[constraints]** — every AndroidX module carries a `dependencyConstraints`
 *   block pinning its whole group to its own version. That is the group
 *   alignment a BOM would give, published per module, and it is what keeps a
 *   module like `lifecycle-common-java8` from being resolved at an old version
 *   that still contains classes its successor now owns.
 *
 * Nothing else in the file is read. Attributes, files, capabilities and the
 * per-variant dependency lists all describe a resolution model this project
 * does not implement, and reading them would imply it does.
 */
internal data class ModuleMetadata(
    /**
     * Where this coordinate's variants actually live, if it is a redirect.
     *
     * Only the platform targets an Android build could use, in preference
     * order. A root that redirects nowhere useful — an iOS-only module — yields
     * an empty list rather than a wrong answer.
     */
    val redirect: Coordinate?,
    /** Version floors this module publishes for other modules. */
    val constraints: List<Coordinate>,
) {

    internal companion object {

        /**
         * Reads the metadata for [artifact], or null when it publishes none.
         *
         * Null is ordinary and not an error: a plain Maven library has no
         * `.module` file at all, and most of the non-AndroidX graph does not.
         * The caller falls back to what a POM can tell it.
         *
         * Resolved through Aether rather than fetched directly, so it lands in
         * the same local repository as everything else and a second build reads
         * it from disk. `tools/deps/FINDINGS.md` §2 is the reason that matters:
         * this module's HTTP transport is hand-written because Android's
         * platform `org.apache.http` shadows the one Maven ships, and a second
         * download path here would be a second thing to get wrong.
         */
        fun read(
            system: RepositorySystem,
            session: RepositorySystemSession,
            repositories: List<RemoteRepository>,
            artifact: Artifact,
        ): ModuleMetadata? {
            val descriptor = DefaultArtifact(
                artifact.groupId,
                artifact.artifactId,
                "",
                EXTENSION,
                artifact.version,
            )
            val file = runCatching {
                system.resolveArtifact(session, ArtifactRequest(descriptor, repositories, null))
                    .artifact
                    ?.file
            }.getOrNull() ?: return null

            return parse(file)
        }

        /** Split out so it can be tested against a file rather than a network. */
        fun parse(file: File): ModuleMetadata? = runCatching {
            val json = JSONObject(file.readText())
            val variants = json.optJSONArray("variants") ?: return null

            val redirects = mutableMapOf<String, Coordinate>()
            val constraints = LinkedHashMap<String, Coordinate>()

            for (index in 0 until variants.length()) {
                val variant = variants.optJSONObject(index) ?: continue

                variant.optJSONObject("available-at")?.let { at ->
                    val module = at.optString("module").orEmptyOrNull() ?: return@let
                    val group = at.optString("group").orEmptyOrNull() ?: return@let
                    val version = at.optString("version").orEmptyOrNull() ?: return@let
                    redirects[module] = Coordinate(group, module, version)
                }

                val declared = variant.optJSONArray("dependencyConstraints") ?: continue
                for (position in 0 until declared.length()) {
                    val constraint = declared.optJSONObject(position) ?: continue
                    val group = constraint.optString("group").orEmptyOrNull() ?: continue
                    val module = constraint.optString("module").orEmptyOrNull() ?: continue
                    // `version` is an object of requires/prefers/strictly. Only
                    // `requires` is read: the others express preferences this
                    // resolver has no way to honour, and guessing at them would
                    // be worse than ignoring them.
                    val version = constraint.optJSONObject("version")
                        ?.optString("requires").orEmptyOrNull() ?: continue
                    constraints["$group:$module"] = Coordinate(group, module, version)
                }
            }

            ModuleMetadata(
                redirect = redirects.pick(),
                constraints = constraints.values.toList(),
            )
        }.getOrNull()

        /**
         * The platform variant an Android build should follow.
         *
         * `-android` first because this is an Android build, `-jvm` second for
         * modules with no Android target of their own. Anything else — the iOS,
         * JS, Wasm and native targets AndroidX also publishes — is not a
         * candidate, and a root offering only those is not a redirect *for us*.
         *
         * The preference itself is unchanged from the suffix rule this
         * replaces. What changes is that it now applies **only where the
         * metadata says the root really is a redirect**, instead of to any two
         * modules whose names happen to share a prefix.
         */
        private fun Map<String, Coordinate>.pick(): Coordinate? {
            for (suffix in PLATFORM_SUFFIXES) {
                entries.firstOrNull { it.key.endsWith(suffix) }?.let { return it.value }
            }
            return null
        }

        private fun String?.orEmptyOrNull(): String? = this?.takeIf { it.isNotBlank() }

        private val PLATFORM_SUFFIXES = listOf("-android", "-jvm")

        /** Gradle's own name for the file; not configurable anywhere. */
        private const val EXTENSION = "module"
    }
}
