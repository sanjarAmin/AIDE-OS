# Spike R2: running kotlinc and the Compose plugin on ART — result

**Outcome: resolved.** The Kotlin compiler runs on ART, the Compose compiler
plugin loads into it, and the bytecode it produces is really transformed —
`@Composable` functions come out with a `Composer` parameter and group calls.
Reproduce with `build-shim.sh` then `build-kotlinc-dex.py`.

## What was produced

| Property | Value |
|---|---|
| Artifact | one `kotlinc.jar` — `classes*.dex` plus resources, laid out like an APK |
| Size | 51 MB |
| Kotlin | 2.2.10 (compiler and Compose plugin both `2.2.10-release-430`) |
| Dexed at | `--min-api 30` |
| Loaded by | `PathClassLoader`, parented to the **boot** loader |
| Compile time | ~11 s for one file, on an x86_64 Android 14 emulator |

The plan assumed the Compose plugin would be a second archive side-loaded into a
running compiler. It is not: dexing the plugin *beside* the compiler puts both in
one classloader, already linked, before the compiler starts. Registering it is a
separate problem — see below.

## Startup is a chain of resource lookups that a dex cannot answer

Nothing here is about compiling Kotlin. Every one of these failed during startup,
each hidden behind the last, and each looked unrelated until traced.

| # | Symptom | Cause |
|---|---|---|
| 1 | `NoSuchMethodException: copyMemory` | ART has no `Unsafe.copyMemory(Object,long,Object,long,long)` |
| 2 | `Resource not found: /…/CompilerSystemProperties.class` | the compiler finds its own install by looking up a class *as a resource* |
| 3 | `NoClassDefFoundError: kotlinx/coroutines/BuildersKt` | a runtime dependency missing from the jar set |
| 4 | `NoClassDefFoundError: javax/xml/stream/XMLStreamException` | Android does not ship StAX |
| 5 | `NullPointerException` in `KotlinCompilerVersion` | `META-INF/compiler.version` read as a resource |
| 6 | `NoClassDefFoundError: org/jetbrains/annotations/NotNull` | another missing runtime dependency |
| 7 | compiles fine, Compose silently does nothing | `-Xplugin` never saw the archive |

### 1. One missing `Unsafe` method stops everything

`com.intellij.util.containers.Unsafe` resolves ten `sun.misc.Unsafe` methods in
its static initializer and lets any failure escape. ART has nine. The tenth,
`copyMemory`, is one ART deliberately drops — an arbitrary object address means
nothing under a moving collector — and it is only ever called by
`ByteBufferUtil`, which the CLI compiler never reaches.

It did not matter that nothing calls it. A failed `<clinit>` stops
`ConcurrentLongObjectHashMap` loading, which stops `CoreProgressManager`, which
stops every compilation before a file is parsed.

The shim (`shim/org/jetbrains/kotlin/…/containers/Unsafe.java`) replaces the
class outright: each handle resolves independently and a failure stores `null`,
so an absent method is only a problem for a caller that wants it. `copyMemory`
is emulated with ART's pinned-array equivalents
(`copyMemoryToPrimitiveArray` / `copyMemoryFromPrimitiveArray`), which cover the
only shape `ByteBufferUtil` ever passes: `(null, address, byte[], offset, len)`.

### 2 & 5. The compiler reads a great deal of itself back as resources

`PathUtil.getResourcePathForClass` asks the classloader for
`CompilerSystemProperties.class` **as a resource** and takes the archive that
answers as the compiler's install directory. A dex has no `.class` entries, so
nothing answers and startup dies registering extension points.

`-kotlin-home` sidesteps one such lookup. It does not sidestep this one, and it
would not have sidestepped `META-INF/compiler.version` either.

Rather than name them one at a time — each is a separate obscure crash — the
build now carries **every non-class entry** from every jar alongside the dex,
which is all a jar was doing for them anyway. `CompilerSystemProperties.class`
is carried as the one deliberate exception: a resource, never loaded from, so
the archive answers the install-directory question with its own path. Which is
the true answer.

### 4. StAX is written out, not extracted

The compiler parses plugin descriptors with a shaded aalto-xml, and aalto is
only the implementation — the `javax.xml.stream` interfaces are expected from
the JDK's `java.xml` module.

Extracting them from the JBR does not work: those class files are version 69 and
d8 accepts nothing past 65. They are hand-written instead
(`shim/javax/xml/stream/`), which also keeps OpenJDK-licensed code out of the
APK. Only six types are needed; nothing references the factory, event or writer
APIs. `verify-stax-shim.sh` diffs every member against a real JDK's, because
aalto links against them by exact descriptor and a mismatch would surface as a
verify error inside XML parsing rather than as a build failure.

### 3 & 6. Two runtime dependencies are easy to miss

`kotlin-compiler-embeddable`'s POM lists `kotlinx-coroutines-core-jvm` as a
`runtime` dependency, and nothing lists `org.jetbrains:annotations` at all —
the backend needs it to emit nullability annotations. Both are invisible until
the compiler is most of the way through startup. The full set is:

    kotlin-compiler-embeddable          kotlin-stdlib
    kotlin-compose-compiler-plugin-embeddable   kotlin-reflect
    kotlin-daemon-embeddable            kotlin-script-runtime
    kotlinx-coroutines-core-jvm 1.8.0   annotations 23.0.0

`compose-runtime.jar` is deliberately *not* dexed — it is a compile classpath
entry for user code, not part of the compiler.

### 7. The plugin is registered by filename, not by classloader

This one produced no error at all: the compiler ran, emitted valid bytecode, and
simply did not transform anything.

Plugin discovery avoids the classloader on purpose. `ServiceLoaderLite` opens
each `-Xplugin` path and reads `META-INF/services` out of the file itself — so a
plugin whose classes are already loaded and linked is still invisible until its
file is named. The archive therefore has to be passed to the compiler that is
already running from it.

**And it must be named `.jar`.** `ServiceLoaderLite` lowercases the file
extension, compares it to `"jar"`, and returns an empty set for anything else —
no warning, no diagnostic. Named `kotlinc.zip` the plugin is never found; the
same bytes named `kotlinc.jar` work. This cost more time than any other item
here, because a silent empty result looks exactly like a plugin that ran and
decided to do nothing.

## Verified on device

Confirmed on an Android 14 x86_64 emulator by
`spike/kotlinc/src/androidTest/.../KotlincOnDeviceTest.kt` (2 tests):

- plain Kotlin compiles to `.class` files;
- a `@Composable` function compiles **and is transformed** — the test greps the
  output bytecode for `Composer` and for `startRestartGroup`/`startReplaceGroup`,
  because a plugin that silently failed to load still produces a clean compile,
  and checking the exit code alone cannot tell the two apart.

## Consequences for the app

- **API 30 is the floor, again.** The archive is dexed at `--min-api 30`, which
  matches where `tools/aapt2/FINDINGS.md` puts aapt2. `:build:fast` gates on one
  version check, not two.
- **`largeHeap` is required.** The front end holds a large object graph and the
  default per-app heap is not generous enough to compile with confidence.
- **Keep the compiler loaded.** ~11 s per one-file compile is nearly all startup:
  building the application environment, registering extension points, reading
  builtins. It is paid per `PathClassLoader`, so `:build:fast` should create one
  and hold it for the life of the process, never per build.
- **Parent the loader to the boot classloader, not the app's.** The archive
  carries its own kotlin-stdlib and so does the app, and d8 synthesises helper
  classes independently for each. Parent-first delegation otherwise hands the
  archive the app's synthetics and linking fails with `NoSuchMethodError` deep
  inside IntelliJ's message bus.

## The input jars, and how much of them can be rebuilt

`build-kotlinc-dex.py --jars` consumes a set of nine jars that nothing in this
repository produced. They were assembled by hand into
`~/aide-os-spikes/kotlinc/jars` and existed on exactly one machine: 64 MB of
single-machine dependency standing in front of M4, and losing the machine lost
the inputs.

`jars.lock` now records all nine by sha256, and `fetch-jars.sh` rebuilds every
row that carries a Maven coordinate, verifying each download against the lock so
a mirror serving something else fails loudly rather than quietly.

**Seven of the nine are byte-identical to Maven Central** and were checked that
way rather than assumed — six Kotlin artifacts at 2.2.10 plus
`org.jetbrains:annotations:23.0.0`. That is 62 of the 64 MB, reproducible from
nothing:

    tools/kotlinc/fetch-jars.sh [target-dir]

Two are not, and both are repackaged rather than published as-is:

| Jar | Why it resists pinning |
|---|---|
| `compose-runtime.jar` | an AAR's `classes.jar`, extracted — `androidx.compose.runtime` ships no plain jar |
| `kotlinx-coroutines-core-jvm.jar` | matches no released artifact byte for byte; 1.8.1 is closest by size and still differs |

Their checksums are in the lock, so a copy can be **verified** even though it
cannot yet be **rebuilt**. Closing the last two means either extracting the AAR
as a build step or finding what transformed the coroutines jar; until then the
script says so and exits non-zero rather than pretending the set is complete.

