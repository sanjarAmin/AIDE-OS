package com.osamu.aide.engine.fast

import com.osamu.aide.engine.api.Diagnostic
import com.osamu.aide.engine.api.DiagnosticSeverity
import java.io.File
import java.security.MessageDigest

/**
 * What the last successful Java compile left behind, so the next can reuse it.
 *
 * **ECJ was most of an edit once dexing was cached.** A one-class edit to a
 * 3,000-class project rebuilt in 13.4 s, 8.4 s of it compiling 2,999 sources
 * that had not changed (`engine/fast/FINDINGS.md` section 16). The batch
 * compiler has no memory, so this is it: the classes of the last build, and for
 * every source its content hash, the class files it produced and the
 * diagnostics it earned. A changed source's [ClassAbi] is read from its kept
 * class files and compared with its new ones.
 *
 * The rule the stage applies with it is deliberately blunt. A rebuild
 * recompiles only the sources whose bytes changed -- **unless any of them now
 * has a different ABI, or a source was added or removed, and then everything is
 * compiled.** A dependency graph would let an ABI change recompile only the
 * classes that use it, but constants are inlined and leave no trace of where
 * they came from, so that graph cannot be read back out of class files; a wrong
 * graph ships a class holding a stale value, and a full compile is only slow.
 * The case this exists for -- a method body edited -- never changes the ABI.
 *
 * Content hashes and never timestamps, for the reasons section 9 gives.
 */
internal class IncrementalJava(private val cacheDir: File) {

    val classes: File get() = File(cacheDir, "classes")
    private val stateFile: File get() = File(cacheDir, "state")

    /**
     * One source as the last build saw it. [classFiles] are relative to the
     * classes directory. No ABI is kept: it is read from those class files
     * when, and only when, the source changes -- reading all of them after
     * every full compile cost more than the compile.
     */
    data class Source(
        val hash: String,
        val classFiles: List<String>,
        val diagnostics: List<Diagnostic>,
        /** The source's package path and name; see [sourceKey]. */
        val key: String = "",
        /** Size and modification time when [hash] was taken; see [State.trustedHash]. */
        val size: Long = -1,
        val modified: Long = -1,
    )

    data class State(val settings: String, val sources: Map<String, Source>, val savedAt: Long = 0) {
        /**
         * The kept hash of [file], when its size and time say it cannot have
         * changed -- git's index, for the same reason: on `/sdcard` every read
         * goes through FUSE, and reading 3,000 unchanged sources to hash them
         * was 3.2 s of a build that compiled nothing.
         *
         * **Not the timestamp rule section 9 rejects.** A time never decides
         * whether to compile; it only decides whether a content hash taken
         * earlier is still the content's. And a file modified within
         * [RACY_MILLIS] of the state being written is always read again: an
         * edit in the same tick as the last build would otherwise keep that
         * build's size and time, which is exactly the same-second failure
         * section 9 describes.
         */
        fun trustedHash(path: String, file: File): Source? {
            val kept = sources[path] ?: return null
            if (kept.size < 0 || kept.key.isEmpty()) return null
            val modified = file.lastModified()
            return kept.takeIf {
                it.size == file.length() && it.modified == modified && modified < savedAt - RACY_MILLIS
            }
        }
    }

    fun load(): State? = runCatching {
        if (!stateFile.isFile || !classes.isDirectory) return null
        val lines = stateFile.readLines()
        if (lines.firstOrNull() != HEADER) return null
        var settings = ""
        var savedAt = 0L
        val hashes = LinkedHashMap<String, List<String>>()
        val classFiles = HashMap<String, MutableList<String>>()
        val diagnostics = HashMap<String, MutableList<Diagnostic>>()
        lines.drop(1).forEach { line ->
            val fields = line.split('\t')
            when (fields[0]) {
                "settings" -> settings = fields[1]
                "saved" -> savedAt = fields[1].toLong()
                "source" -> hashes[fields[1]] = fields.drop(2)
                "class" -> classFiles.getOrPut(fields[1]) { mutableListOf() } += fields[2]
                "diagnostic" -> diagnostics.getOrPut(fields[1]) { mutableListOf() } += Diagnostic(
                    severity = DiagnosticSeverity.valueOf(fields[2]),
                    line = fields[3].toInt(),
                    column = fields[4].toInt(),
                    file = fields[5].takeIf { it.isNotEmpty() }?.let(::File),
                    message = unescape(fields[6]),
                )
            }
        }
        State(
            settings,
            hashes.mapValues { (path, f) ->
                Source(f[0], classFiles[path].orEmpty(), diagnostics[path].orEmpty(), f[1], f[2].toLong(), f[3].toLong())
            },
            savedAt,
        )
    }.getOrNull()

    /**
     * Written to a sibling and renamed, so a build killed half way leaves the
     * previous state or none -- never a state describing classes that were not
     * all copied.
     */
    fun save(state: State) {
        val text = buildString {
            appendLine(HEADER)
            append("settings\t").appendLine(state.settings)
            append("saved\t").appendLine(System.currentTimeMillis())
            state.sources.forEach { (path, source) ->
                append("source\t").append(path).append('\t').append(source.hash).append('\t').append(source.key)
                    .append('\t').append(source.size).append('\t').appendLine(source.modified)
                source.classFiles.forEach { append("class\t").append(path).append('\t').appendLine(it) }
                source.diagnostics.forEach { d ->
                    append("diagnostic\t").append(path).append('\t').append(d.severity.name).append('\t')
                        .append(d.line).append('\t').append(d.column).append('\t')
                        .append(d.file?.path.orEmpty()).append('\t').appendLine(escape(d.message))
                }
            }
        }
        val partial = File(cacheDir, "state.partial")
        partial.writeText(text)
        check(partial.renameTo(stateFile)) { "could not keep the Java compile state" }
    }

    /** Forgets everything, so the next build compiles in full. */
    fun clear() {
        cacheDir.deleteRecursively()
    }

    companion object {
        private const val HEADER = "aide-incremental-java 3"

        /** Two seconds: coarser than any filesystem this runs on records a modification time. */
        const val RACY_MILLIS = 2_000L

        fun hash(file: File): String = hash(file.readBytes())

        fun hash(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        fun hash(text: String): String = hash(text.toByteArray())

        /**
         * Everything besides the sources that decides what ECJ emits: its
         * arguments, and the jars it compiles against. A jar is taken by path,
         * size and time -- they are Maven artifacts and SDK files, replaced
         * rather than edited, and hashing every AndroidX jar on every build
         * would cost more than it could save.
         */
        fun settings(arguments: List<String>, classpath: List<File>): String = hash(
            buildString {
                arguments.forEach { append(it).append('\n') }
                classpath.forEach { append(it.absolutePath).append(':').append(it.length()).append(':').append(it.lastModified()).append('\n') }
            },
        )

        /**
         * Groups compiled classes by the source they came from: the package
         * path of the class and its `SourceFile`, which for Java is the file's
         * name in the directory of its package declaration.
         */
        fun sourceKey(abi: ClassAbi): String? = abi.sourceFile?.let { "${abi.packagePath}/$it" }

        /** The same key for a source file, from its `package` declaration. */
        fun sourceKey(source: File): String = sourceKey(source.name, source.readText())

        fun sourceKey(fileName: String, text: String): String {
            val code = text.replace(BLOCK_COMMENT, " ").replace(LINE_COMMENT, "")
            val packagePath = PACKAGE.find(code)?.groupValues?.get(1).orEmpty().replace('.', '/')
            return "$packagePath/$fileName"
        }

        /**
         * Which source each class file under [dir] came from, by source key.
         *
         * By name first: `p/Outer$Inner.class` is from `p/Outer.java` whenever
         * such a source exists, which it does for everything but a second
         * top-level class in a file named for another. Only those are opened,
         * for their `SourceFile` attribute.
         */
        fun classFilesBySource(dir: File, keys: Set<String>, excluding: Set<String> = emptySet()): Map<String, List<String>> =
            dir.walkTopDown()
                .filter { it.isFile && it.extension == "class" }
                .mapNotNull { file ->
                    val relative = file.relativeTo(dir).invariantSeparatorsPath
                    if (relative in excluding) return@mapNotNull null
                    val pkg = relative.substringBeforeLast('/', "")
                    val top = relative.substringAfterLast('/').removeSuffix(".class").substringBefore('$')
                    // `Main.java`, or for Kotlin `Main.kt` -- which also
                    // compiles its top-level declarations into `MainKt`.
                    val byName = listOf("$top.java", "$top.kt", "${top.removeSuffix("Kt")}.kt")
                        .map { "$pkg/$it" }
                        .firstOrNull { it in keys }
                    val key = byName ?: runCatching { sourceKey(ClassAbi.read(file)) }.getOrNull()
                    key?.let { it to relative }
                }
                .groupBy({ it.first }, { it.second })

        /**
         * [to] as a hard link to [from], or a copy when the filesystem will not
         * link. **Safe only because nothing writes a class file in place**: a
         * rebuild deletes a changed source's classes before ECJ writes new
         * ones, and kotlinc, which would write into the same directory, turns
         * incremental compilation off. Copying 3,000 class files took 4 s of a
         * build that compiled nothing.
         */
        fun link(from: File, to: File) {
            to.parentFile?.mkdirs()
            to.delete()
            runCatching { java.nio.file.Files.createLink(to.toPath(), from.toPath()) }
                .onFailure { from.copyTo(to, overwrite = true) }
        }

        /** The ABI of a source: its visible classes' ABIs, in a stable order. */
        fun abiOf(classes: List<ClassAbi>): String =
            hash(classes.filterNot { it.isLocal }.sortedBy { it.name }.joinToString("") { it.abi })

        private fun escape(text: String) = text.replace("\\", "\\\\").replace("\n", "\\n").replace("\t", "\\t")

        private fun unescape(text: String): String {
            val out = StringBuilder()
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (c == '\\' && i + 1 < text.length) {
                    out.append(
                        when (text[i + 1]) {
                            'n' -> '\n'
                            't' -> '\t'
                            else -> text[i + 1]
                        },
                    )
                    i += 2
                } else {
                    out.append(c)
                    i++
                }
            }
            return out.toString()
        }

        private val BLOCK_COMMENT = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
        private val LINE_COMMENT = Regex("""//[^\n]*""")
        // The semicolon is Java's; Kotlin has none.
        private val PACKAGE = Regex("""^\s*package\s+([\w.]+)\s*;?""", RegexOption.MULTILINE)
    }
}
