package com.osamu.aide.engine.api

/**
 * The steps of a build, in the order the fast pipeline runs them.
 *
 * Named after what the user is waiting for rather than after the tool doing it:
 * a build that stalls should say "Compiling Java", not "ECJ". Which stages run
 * depends on the project -- a Java project has no [COMPILE_KOTLIN] stage -- so
 * this is a vocabulary, not a sequence to walk.
 */
enum class BuildStage(val displayName: String) {
    COMPILE_RESOURCES("Compiling resources"),
    LINK_RESOURCES("Linking resources"),
    COMPILE_JAVA("Compiling Java"),
    COMPILE_KOTLIN("Compiling Kotlin"),
    COMPILE_NATIVE("Compiling C/C++"),
    DEX("Converting to Dalvik bytecode"),
    PACKAGE("Packaging"),
    SIGN("Signing"),
}
