package com.osamu.aide.ai.core

import java.io.File

/**
 * The filesystem, as the assistant is allowed to see it.
 *
 * Every path the model produces passes through [resolve] before anything opens
 * it. That is the whole security posture of the tool layer: the model is
 * choosing paths from text it partly read out of the user's own files, so
 * `../../../../data/data/com.other.app/databases` is a string it can emit as
 * easily as `src/main/java`. Confining it to the project directory is the
 * difference between an assistant that edits code and one that reads the
 * device.
 *
 * The same guard `AarExtractor` needs for Zip Slip, for the same reason and
 * with the same canonical-path check — a relative path only reveals where it
 * really points once resolved.
 *
 * Results are size-capped. A model that asks to read a 40 MB generated file
 * would otherwise blow the context window and the bill in one call, and the
 * failure would look like the model misbehaving rather than a missing limit.
 */
class ProjectFiles(private val root: File) {

    /** What a tool produced, or why it could not. */
    sealed interface Outcome {
        data class Ok(val content: String) : Outcome
        data class Refused(val reason: String) : Outcome
    }

    /**
     * Turns a model-supplied path into a real one inside the project.
     *
     * Null for anything outside, absolute, or otherwise not ours. Canonical on
     * both sides because `a/../../b` is only visibly an escape after
     * resolution — and because `/data/user/0/<pkg>` and `/data/data/<pkg>` are
     * the same directory by two names on Android, which a plain prefix match
     * gets wrong. `:engine:fast` learned that one the hard way.
     */
    fun resolve(path: String): File? {
        if (path.isBlank()) return null

        // Absolute paths are refused rather than reinterpreted. `File(root,
        // "/etc/hosts")` does not escape -- Java joins it to
        // `<project>/etc/hosts` -- so this is not a security hole, it is a
        // clarity one: the model asked for a specific file and would silently
        // get a different one that happens to be inside the project. Saying no
        // teaches it to send a relative path; succeeding wrongly teaches it
        // nothing.
        if (path.startsWith("/") || File(path).isAbsolute) return null

        val candidate = File(root, path)
        val resolved = runCatching { candidate.canonicalFile }.getOrNull() ?: return null
        val base = runCatching { root.canonicalFile }.getOrNull() ?: return null

        val prefix = base.path.trimEnd(File.separatorChar) + File.separator
        return resolved.takeIf { it.path == base.path || it.path.startsWith(prefix) }
    }

    fun read(path: String): Outcome {
        val file = resolve(path) ?: return refuseOutside(path)
        if (!file.isFile) return Outcome.Refused("$path does not exist, or is not a file.")
        if (file.length() > MAX_READ_BYTES) {
            return Outcome.Refused(
                "$path is ${file.length()} bytes; the limit is $MAX_READ_BYTES. " +
                    "Read a smaller file, or grep it instead.",
            )
        }
        return runCatching { Outcome.Ok(file.readText()) }
            .getOrElse { Outcome.Refused("$path could not be read: ${it.message}") }
    }

    /**
     * Replaces a file's contents wholesale.
     *
     * **Mutating.** Nothing calls this without the user having confirmed the
     * specific edit; the tool layer above marks it as such and the UI gates it.
     * Parent directories are created,
     * because an assistant adding a class in a new package should not need a
     * separate "make a directory" round trip.
     */
    fun write(path: String, content: String): Outcome {
        val file = resolve(path) ?: return refuseOutside(path)
        return runCatching {
            file.parentFile?.mkdirs()
            file.writeText(content)
            Outcome.Ok("Wrote ${content.length} characters to $path.")
        }.getOrElse { Outcome.Refused("$path could not be written: ${it.message}") }
    }

    /**
     * The project tree, as relative paths.
     *
     * Build output and version control are skipped. They are enormous, they are
     * derived, and a model that reads `build/` will spend its context on
     * generated code that tells it nothing about what the user wrote.
     */
    fun list(path: String = "", limit: Int = MAX_LIST_ENTRIES): Outcome {
        val directory = resolve(path.ifBlank { "." }) ?: return refuseOutside(path)
        if (!directory.isDirectory) return Outcome.Refused("$path is not a directory.")

        val entries = directory.walkTopDown()
            .onEnter { it.name !in SKIPPED }
            .filter { it.isFile }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .sorted()
            .take(limit + 1)
            .toList()

        val shown = entries.take(limit)
        val suffix = if (entries.size > limit) "\n… more than $limit files; narrow the path." else ""
        return Outcome.Ok(shown.joinToString("\n") + suffix)
    }

    /**
     * Literal substring search across the project.
     *
     * Substring rather than regex on purpose: a model-supplied regex can
     * backtrack catastrophically, and the difference between the two matters
     * far less than a tool call that never returns.
     */
    fun grep(query: String, path: String = "", limit: Int = MAX_MATCHES): Outcome {
        if (query.isBlank()) return Outcome.Refused("A search needs something to look for.")
        val directory = resolve(path.ifBlank { "." }) ?: return refuseOutside(path)

        val matches = mutableListOf<String>()
        directory.walkTopDown()
            .onEnter { it.name !in SKIPPED }
            .filter { it.isFile && it.length() <= MAX_READ_BYTES }
            .forEach { file ->
                if (matches.size >= limit) return@forEach
                val relative = file.relativeTo(root).invariantSeparatorsPath
                runCatching { file.readLines() }.getOrNull()?.forEachIndexed { index, line ->
                    if (matches.size < limit && line.contains(query)) {
                        matches += "$relative:${index + 1}: ${line.trim().take(MAX_LINE_CHARS)}"
                    }
                }
            }

        return if (matches.isEmpty()) {
            Outcome.Ok("No matches for \"$query\".")
        } else {
            Outcome.Ok(matches.joinToString("\n"))
        }
    }

    private fun refuseOutside(path: String) = Outcome.Refused(
        "$path is outside the project. Paths must be relative to the project root.",
    )

    private companion object {
        /** Enough for any hand-written source file, far short of a context window. */
        const val MAX_READ_BYTES = 256L * 1024
        const val MAX_LIST_ENTRIES = 400
        const val MAX_MATCHES = 100
        const val MAX_LINE_CHARS = 200

        /** Derived or private; nothing here tells the model what the user wrote. */
        val SKIPPED = setOf("build", ".git", ".gradle", ".idea", "node_modules")
    }
}
