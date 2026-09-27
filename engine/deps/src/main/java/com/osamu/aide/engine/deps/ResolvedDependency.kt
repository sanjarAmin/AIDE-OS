package com.osamu.aide.engine.deps

import java.io.File

/** A Maven coordinate, in the `group:artifact:version` form a user writes. */
data class Coordinate(
    val group: String,
    val artifact: String,
    val version: String,
) {
    override fun toString(): String = "$group:$artifact:$version"

    companion object {
        /** Null rather than throwing: this parses text a user typed. */
        fun parse(notation: String): Coordinate? {
            val parts = notation.trim().split(':')
            if (parts.size != 3 || parts.any { it.isBlank() }) return null
            return Coordinate(parts[0], parts[1], parts[2])
        }
    }
}

/**
 * One resolved artifact, unpacked into the three things a build needs from it.
 *
 * An AAR is a zip carrying all three; a plain jar is only the first. Keeping
 * them as separate fields rather than handing back the `.aar` means nothing
 * downstream has to know which kind it got -- `:engine:fast` compiles against
 * [classes], aapt2 reads [resources], and the R class comes from [rTxt].
 */
data class ResolvedDependency(
    val coordinate: Coordinate,
    /** Always present: the jar itself, or `classes.jar` out of an AAR. */
    val classes: File,
    /** An AAR's `res/`, when it has one worth compiling. */
    val resources: File? = null,
    /** An AAR's `R.txt`: the resource symbols it declares. */
    val rTxt: File? = null,
    /** An AAR's `AndroidManifest.xml`, which carries its minSdk and permissions. */
    val manifest: File? = null,
    /**
     * The package that manifest declares, and so the package this library's
     * own compiled code looks for its `R` class in. See
     * [ResolvedDependencies.libraryPackages].
     */
    val packageName: String? = null,
    /** The jars in an AAR's `libs` directory, which the library was built with and ships inside itself. */
    val extraJars: List<File> = emptyList(),
    /** An AAR's `jni/`, holding one directory of `.so` files per ABI. */
    val nativeLibraries: File? = null,
    /** An AAR's `assets/`. */
    val assets: File? = null,
) {
    val isAndroidLibrary: Boolean get() = resources != null || rTxt != null
}

/**
 * Everything a project's declared dependencies resolved to.
 *
 * **[dependencies] are weakest first**: each library after everything it
 * depends on, and of two siblings the earlier-declared last. That is overlay
 * order, where the last one wins -- how resources are linked and manifests
 * merged -- and it is why the lists derived for those keep it.
 *
 * [compileClasspath] is the **reverse**, strongest first. Javac and ECJ take the
 * first definition of a duplicated class, so a library must come before the
 * dependencies it may shadow; both orders are correctness properties, and they
 * are opposite ones.
 */
data class ResolvedDependencies(
    val dependencies: List<ResolvedDependency> = emptyList(),
    /** Artifacts that resolved to no file at all; see [ResolutionReport]. */
    val unresolved: List<String> = emptyList(),
) {
    val compileClasspath: List<File>
        get() = dependencies.asReversed().flatMap { listOf(it.classes) + it.extraJars }

    /** Each library's `jni/` directory, which holds one directory per ABI. */
    val nativeLibraryDirectories: List<File> get() = dependencies.mapNotNull { it.nativeLibraries }

    /** Each library's `assets/`, weakest first like resources. */
    val assetDirectories: List<File> get() = dependencies.mapNotNull { it.assets }

    val resourceDirectories: List<File> get() = dependencies.mapNotNull { it.resources }

    /**
     * Every package that needs an `R` class generated for it.
     *
     * A library's compiled code references **its own** `R`, not the app's:
     * `androidx.customview.poolingcontainer.R`, in the class that crashed
     * before this existed. aapt2 generates one R class, for the app's package,
     * so without naming these the classes are simply absent and the app dies on
     * launch with `NoClassDefFoundError` -- after a build that reported success.
     *
     * Only libraries that declare resources are listed: a package with nothing
     * in it produces an R class no one references, and aapt2 is being asked to
     * write a file per entry.
     */
    val libraryPackages: List<String>
        get() = dependencies.filter { it.isAndroidLibrary }.mapNotNull { it.packageName }.distinct()

    /**
     * Each Android library's `AndroidManifest.xml`, weakest first.
     *
     * Extracted since M4 and unread until M8: nothing merged them, so every
     * `<provider>` and `<uses-permission>` a library declared was absent from
     * the built APK. `engine/fast/FINDINGS.md` section 13.
     */
    val libraryManifests: List<File>
        get() = dependencies.mapNotNull { it.manifest }

    val isEmpty: Boolean get() = dependencies.isEmpty()
}

/** Progress, for a first resolve that can legitimately take a minute. */
sealed interface ResolutionProgress {
    data class Collecting(val root: String) : ResolutionProgress
    data class Downloading(val artifact: String, val index: Int, val total: Int) : ResolutionProgress
    data class Extracting(val artifact: String) : ResolutionProgress
}

/** What a resolution did, kept separate from what it produced. */
data class ResolutionReport(
    val resolved: Int,
    val unresolved: List<String>,
    val fellBackToAar: List<String>,
    val millis: Long,
)
