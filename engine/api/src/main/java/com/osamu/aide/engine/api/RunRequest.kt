package com.osamu.aide.engine.api

import java.io.File

/**
 * What to run.
 *
 * [entryPoint] is resolved by the caller rather than discovered here, because
 * where it comes from is language-specific -- for Node it is `main` in
 * `package.json`, which `ProjectLayout.nodeEntryPoint` reads -- and an engine
 * that guessed would be wrong in a way that names the wrong file.
 *
 * [arguments] go to the program, never to the runtime: an engine that let a
 * caller pass `--inspect` through this would be letting it reconfigure the
 * runtime by accident.
 */
data class RunRequest(
    val projectDir: File,
    val entryPoint: File,
    val arguments: List<String> = emptyList(),
    /** Added to the environment the engine builds, never replacing it. */
    val environment: Map<String, String> = emptyMap(),
)
