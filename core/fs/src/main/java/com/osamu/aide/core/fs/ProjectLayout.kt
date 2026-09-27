package com.osamu.aide.core.fs

import java.io.File

/**
 * Where a project keeps its sources.
 *
 * This mirrors the conventional Android/Gradle layout rather than inventing a
 * flatter one. The project model is ours, but the directory shape is the one
 * every Android developer already has in their fingers, and it makes importing
 * an existing project mostly a matter of writing an `aide.json` beside sources
 * that are already in the right place.
 */
class ProjectLayout(val root: File) {

    val mainDir: File get() = File(root, "src/main")
    val manifestFile: File get() = File(mainDir, "AndroidManifest.xml")
    val javaDir: File get() = File(mainDir, "java")
    val resourceDir: File get() = File(mainDir, "res")
    val assetsDir: File get() = File(mainDir, "assets")

    /**
     * C and C++ sources. `src/main/cpp`, which is where the NDK's own Gradle
     * plugin puts them, so an imported project needs nothing moved.
     */
    val nativeDir: File get() = File(mainDir, "cpp")

    /** Every `.java` under [javaDir], in a stable order so builds are repeatable. */
    fun javaSources(): List<File> = javaDir
        .walkTopDown()
        .filter { it.isFile && it.extension == "java" }
        .sortedBy { it.invariantSeparatorsPath }
        .toList()

    fun kotlinSources(): List<File> = javaDir
        .walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .sortedBy { it.invariantSeparatorsPath }
        .toList()

    /**
     * Every C and C++ source under [nativeDir], in a stable order.
     *
     * Headers are excluded: they are compiled through the sources that include
     * them, and a header compiled on its own is either an error or a wasted
     * object file.
     */
    fun nativeSources(): List<File> = nativeDir
        .walkTopDown()
        .filter { it.isFile && it.extension.lowercase() in NATIVE_EXTENSIONS }
        .sortedBy { it.invariantSeparatorsPath }
        .toList()

    /** A Node project's entry point, whatever `package.json` calls `main`. */
    val nodeEntryPoint: File
        get() {
            val manifest = File(root, "package.json").takeIf { it.isFile }
                ?: return File(root, "index.js")
            // Deliberately a regex and not a JSON parser: this module has no
            // JSON dependency, and the field is written by the template. A
            // hand-edited `package.json` that this cannot read falls back to
            // the conventional name rather than failing to find anything.
            val main = Regex("\"main\"\\s*:\\s*\"([^\"]+)\"")
                .find(manifest.readText())?.groupValues?.get(1)
            return File(root, main ?: "index.js")
        }

    /**
     * Every `.cs` in the project, in a stable order.
     *
     * From the project root and not `src/main/java`, because a C# project is
     * not an Android one: `dotnet new console` puts `Program.cs` at the root
     * and so does the template here. [BUILD] is skipped so that a rebuild does
     * not try to compile whatever the last one left behind, and so are hidden
     * directories -- a checked-out dependency under `.nuget` is not this
     * project's source.
     */
    fun csharpSources(): List<File> = root
        .walkTopDown()
        .onEnter { it.name != BUILD && !it.name.startsWith(".") }
        .filter { it.isFile && it.extension == "cs" }
        .sortedBy { it.invariantSeparatorsPath }
        .toList()

    /**
     * Where a compiled assembly and mono's rewritten config go.
     *
     * Inside the project for the reason [runHome] is, and hidden for the same
     * one: the file tree does not show it and [ProjectTemplate] tells git to
     * ignore it. A `.exe` in the file tree beside `Program.cs` would invite
     * someone to open it.
     */
    val buildDir: File get() = File(root, BUILD)

    /** The assembly [csharpSources] compiles to. */
    val csharpAssembly: File get() = File(buildDir, "${root.name}.exe")

    /**
     * A directory a running program may treat as `$HOME`, and its scratch
     * space.
     *
     * Inside the project rather than in app storage, so that deleting a project
     * takes its downloaded packages with it and two projects cannot disagree
     * about a dependency's version. Dot-prefixed because the file tree hides
     * those, and named in [writeRunIgnores] -- the two must stay in step, which
     * is why they are spelled once, here.
     *
     * **Named for the job and not for node**, which is what they were called
     * when node was the only language that ran. Python uses the same two, and a
     * project is one language, so there is nothing to collide -- but
     * `pythonRun.home = layout.nodeHome` read like a copy-paste error every
     * time, which is a reason to rename and not a reason to add a third pair.
     */
    val runHome: File get() = File(root, RUN_HOME)

    /** @see runHome */
    val runCache: File get() = File(root, RUN_CACHE)

    /**
     * A Python project's entry point.
     *
     * `main.py` by convention and not by configuration, which is the honest
     * answer for this language: Python has no `package.json` naming an entry
     * point, and the file that would come closest -- `pyproject.toml` -- names
     * a *console script* built by an installer this app does not run. Inventing
     * a field for it would mean inventing a file format, and reading the wrong
     * one would mean claiming to support packaging that does not work here.
     *
     * A project whose entry point is elsewhere still edits, and the run reports
     * the path it looked for rather than a missing module.
     */
    val pythonEntryPoint: File get() = File(root, "main.py")

    /**
     * Every `.py` in the project, in a stable order.
     *
     * [PYTHON_PACKAGES] is skipped along with [BUILD] and hidden directories:
     * installed dependencies are thousands of files that are not this project's
     * source, exactly as `node_modules` is not.
     */
    fun pythonSources(): List<File> = root
        .walkTopDown()
        .onEnter { it.name != BUILD && it.name != PYTHON_PACKAGES && !it.name.startsWith(".") }
        .filter { it.isFile && it.extension == "py" }
        .sortedBy { it.invariantSeparatorsPath }
        .toList()

    /**
     * Where `pip install --target` puts this project's dependencies.
     *
     * Inside the project for the reason [runHome] is: deleting a project takes
     * its downloaded packages with it, and two projects cannot disagree about a
     * version. **Not a virtualenv** -- see `PythonRunSystem.pip` for why one
     * cannot be created here. A run puts this on `PYTHONPATH`.
     */
    val pythonPackages: File get() = File(root, PYTHON_PACKAGES)

    /**
     * True when there is enough here to attempt a build.
     *
     * **An APK build**, which is the only kind this asks about. A Node project
     * has no manifest and is never buildable in this sense; it is *runnable*,
     * which is a different question and deliberately not conflated with it --
     * answering true here would send it into the aapt2 pipeline.
     */
    fun isBuildable(): Boolean = manifestFile.isFile

    companion object {
        const val RUN_HOME = ".aide-home"
        const val RUN_CACHE = ".aide-cache"
        const val BUILD = ".aide-build"
        const val PYTHON_PACKAGES = ".aide-packages"

        private val NATIVE_EXTENSIONS = setOf("c", "cc", "cpp", "cxx")

        fun of(project: Project) = ProjectLayout(project.rootDir)
    }
}
