import java.io.File

/**
 * Pushes the large toolchain archives a module's instrumented tests need.
 *
 * **Why this exists.** A full `connectedDebugAndroidTest` sweep reported 552
 * tests and 0 failures, and 43 of its 62 skips were nothing but missing
 * artefacts: `spike:clang` ran 0 of 8, `lsp:native` 0 of 7, `toolchain:native`
 * skipped 8, `engine:gradle` 7. M7 (C/C++ and clangd) and M9 (the Gradle path)
 * are both marked met in `docs/PLAN.md`, and a green sweep proved nothing about
 * either -- which is the worst possible state for the two milestones that rest
 * most directly on R9's `linker64` exec route. A skip reports as OK.
 *
 * `:lsp:kotlin` already solved this for its own archives; this is that task,
 * generalised, so the six modules that need a `.tar` on the device get one.
 *
 * **The archives are not in git and never will be** -- they are 0.3-0.6 GB
 * each. They are built by `tools/clang/fetch-toolchain.sh` and
 * `tools/rootfs/fetch-jvm.sh`, and this looks for them in `DEVICE_ARCHIVES`, a
 * `:`-separated search path. A missing archive **warns and carries on**: a
 * fresh clone must still be able to run every other module's tests, and the
 * tests themselves already skip with the command that builds what they need.
 *
 * **The ABI is the caller's problem.** `toolchain.tar` and `jvm.tar` hold
 * Android ELF binaries, so an aarch64 archive on an x86_64 emulator does not
 * skip -- it fails at `execve`, some way from the cause. Keep one directory per
 * ABI and point `DEVICE_ARCHIVES` at the right one.
 *
 * Applied by a module as:
 *
 *     extra["deviceTestPackage"] = "com.osamu.aide.spike.clang.test"
 *     extra["deviceArchives"] = listOf("toolchain.tar")
 *     apply(from = rootProject.file("gradle/stage-device-archives.gradle.kts"))
 */

// Read here, where `extra` is the *project's*. Inside a task configuration
// block it resolves to the task's own extra properties instead, which do not
// have these.
@Suppress("UNCHECKED_CAST")
val requestedArchives = extra["deviceArchives"] as List<String>
val requestedPackage = extra["deviceTestPackage"] as String

val stageDeviceArchives by tasks.registering {
    // **Everything doLast reads is a local in this block, on purpose.** A `val`
    // at the top level of a script is a *property of the script object*, so
    // reading one from `doLast` captures that object -- and the configuration
    // cache refuses to serialize it: "cannot serialize Gradle script object
    // references". A local is captured by value and serializes fine.
    // `:lsp:kotlin`'s own staging task is written this way for the same reason.
    val archives = requestedArchives
    val devicePackage = requestedPackage

    val home: String = System.getProperty("user.home")
    val searchPath: List<String> = (
        System.getenv("DEVICE_ARCHIVES")
            ?: "$home/aide-os-spikes/m9/stage:$home/aide-os-spikes/clang-x86_64"
        )
        .split(File.pathSeparator)
        .filter { it.isNotBlank() }
    val adb = "${System.getenv("ANDROID_SDK_ROOT") ?: "$home/Android/Sdk"}/platform-tools/adb"
    // An entry is either `name.tar`, or `local.tar=name-on-device.tar` where
    // the two differ. `:engine:gradle` needs the second: the archive that works
    // is the medium trim of build-tools, and the test asks for it as
    // `sdk-extra.tar`.
    val resolved: Map<String, String> = archives.associate { entry ->
        val local = entry.substringBefore('=')
        val onDevice = entry.substringAfter('=', local)
        onDevice to searchPath.map { File(it, local) }
            .firstOrNull { it.isFile }?.absolutePath.orEmpty()
    }
    val searched = searchPath.joinToString(File.pathSeparator)

    description = "Pushes $archives to $devicePackage on the device."
    group = "verification"

    // Deliberately not input/output tracked: the state it changes lives on a
    // device Gradle cannot see, and AGP uninstalls the test APK after a run --
    // taking the pushed archives with it -- so every run must push again.
    outputs.upToDateWhen { false }

    doLast {
        val missing = resolved.filterValues { it.isEmpty() }.keys
        if (!File(adb).isFile || missing.isNotEmpty()) {
            logger.warn(
                "Device archives not staged for $devicePackage -- those tests will SKIP, " +
                    "and a skip reports as OK. Missing: ${missing.joinToString()}. " +
                    "Build them with tools/clang/fetch-toolchain.sh or " +
                    "tools/rootfs/fetch-jvm.sh, or point DEVICE_ARCHIVES at where they are " +
                    "(searched: $searched).",
            )
            return@doLast
        }

        val target = "/sdcard/Android/data/$devicePackage/files"
        for ((name, source) in listOf("mkdir" to "") + resolved.toList()) {
            val command = if (name == "mkdir") {
                listOf(adb, "shell", "mkdir", "-p", target)
            } else {
                listOf(adb, "push", source, "$target/$name")
            }
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            check(process.waitFor() == 0) { "${command.joinToString(" ")} failed: $output" }
        }
        logger.lifecycle("Staged ${resolved.size} archive(s) to $devicePackage.")
    }
}

tasks.matching { it.name == "connectedDebugAndroidTest" }.configureEach {
    dependsOn(stageDeviceArchives)
}
