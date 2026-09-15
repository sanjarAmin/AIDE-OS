# How large a project the app can build

Measured 2026-09-14 on the `aideos_test` emulator (API 34, x86_64, 4 cores,
2.5 GB RAM with ~1.9 GB of swap). An emulator on a desktop CPU, so the absolute
times are not a phone's; the *shape* -- which stage dominates, what grows with
size, what runs out first -- is what carries over. Re-run on hardware before
quoting a number to a user.

## The projects

`generate-project.py` writes a synthetic Android app: *M* modules of *C* Java
classes, each class calling into the previous one and into the previous module,
so no compiler can treat files independently. `--single` puts the same code in
one module laid out like AIDE-OS's template, which is what the fast engine
builds; without it the result is a Gradle root where `:app` depends on a chain
of library modules.

| | Modules × classes | Classes | Java lines |
|---|---|---|---|
| Medium | 8 × 60 | 480 | 34,571 |
| Large | 20 × 150 | 3,000 | 216,011 |

Each reached the app the way a user's would: pushed to `Download/` and brought
in with *Import a project folder*. The large one's 3,000 files imported in 31 s
for the single-module version and 43 s for the Gradle version.

## What it measured

Wall times as the app reports them ("Built in ..."), from tapping Build.

| | Fast engine, cold | Fast engine, warm | Gradle, cold | Gradle, no change |
|---|---|---|---|---|
| Medium | 20.0 s | 13–14 s | 185 s | 26.8 s |
| Large | 82.1 s | 51.7 s | 130 s ¹ | |

¹ After the medium build had downloaded AGP; the medium's 185 s includes that.

"Cold" is the first build after the app's processes start; "warm" is a second
build in the same session, with nothing changed. Peak memory: the fast engine's
build process reached 252 MB (medium) and 302 MB (large). Gradle's JVMs reached
1,073 MB and 1,256 MB -- on a device with 2.5 GB, which is the number that
decides whether a phone can do it at all.

**Both engines built both projects.** Nothing ran out of memory, and nothing
failed that was not a defect found and fixed on the way (below).

## Dexing was most of every fast build

The stage timings for the large project before the cache, warm: Java 8.6 s, **D8 42.1 s** of
51.7. Medium: D8 9–12 s of 13–14. The fast engine emptied its workspace every
build -- deliberately, `engine/fast/FINDINGS.md` section 9 -- so every build
converted every class to dex again, whether or not it had changed. That is what
`DexShards` fixes, and after it the large project rebuilt in **13.4 s after a
one-class edit** and **17.1 s after restarting the app**, against 52 s and 82 s:
`engine/fast/FINDINGS.md` section 16.

## What measuring it found

Getting a multi-module Gradle project into the app and built at all took these:

- **Import and clone took `app/` out of a Gradle root and dropped the rest.**
  The rule was "the folder with a manifest, or its only child with one", so
  an Android Studio project arrived as its app module alone -- libraries and
  build files left behind, set to the fast engine -- and a library with a
  manifest of its own made it refuse as ambiguous. A folder with
  `settings.gradle` is now taken whole, for Gradle, with its application id
  read from the build files. `core/fs` `ProjectDescription`.
- The Gradle engine's JDK, Gradle and build tools could not be installed from
  the app, and were not found once they were: `toolchain/manager/FINDINGS.md`
  section 10.

## The editor in a multi-module project

Once the project built, its editor still could not follow it: a class in `lib1`
calling into `lib0` was `package com.example.large.m0 does not exist`, and an
`androidx.core` import declared only in `app/build.gradle.kts` was unresolved.
The language services read one module's `src/main/java` and the dependencies
listed in `aide.json`, and a Gradle project lists none there.

A build script is a program -- version catalogs, convention plugins, `api`
dependencies of other modules -- so the classpath comes from Gradle, not from
reading the scripts. Every Gradle build carries an init script
(`GradleEditorInputs`) that asks AGP for each debug variant's
`compileClasspath` and writes it to the module's `build/aide/`; AARs arrive
already transformed into jars. The editor reads that, every module's source
folders, and each module's `R` jar, and leaves out the project's own compiled
outputs so no class is both source and binary. Driven on the emulator: before
a build, only the `androidx` import is unresolved; after it, 22 jars and no
problems.

Two defects in the wiring surfaced on the way, and both predate multi-module:

- **Only completion passed the classpath.** Diagnostics, signature hints and
  go-to-definition asked for the service without one, and a service is rebuilt
  whenever the classpath differs -- so in any project with dependencies, each
  analysis discarded completion's warm compiler and the next completion
  discarded analysis's, and analysis ran without the dependencies. The context
  is now held by `LanguageServices`, and every caller gets the service built
  from it. `JavaCompletionSourceTest`.
- **The classpath never reached the editor on open.** `installCompletions`
  waited on the job that reads the project descriptor, and was called before
  that job was created; a coroutine that starts immediately met a null job,
  waited for nothing, found no project, and returned. Nothing said so --
  platform types resolved, only dependencies stayed red.

What is still missing: go-to-definition into a dependency, which has no source;
a Gradle project's generated sources other than `R` (BuildConfig, view
binding); and any of this before the first build.

## Rebuilding after an edit

Measured 2026-09-15, same emulator, after both engines learned to reuse work
(`engine/fast/FINDINGS.md` sections 16 and 17 for the fast engine).

| | Fast engine, large (3,000 classes) | Gradle, medium (480 classes, 9 modules) |
|---|---|---|
| First build | 63.4 s | 188 s, including the JDK, Gradle, build tools and AGP downloads |
| Nothing edited | 5.4 s | 28.3 s |
| One method body edited | **7.2 s** | 26.5 s (library module), 23.4 s (app module) |
| App restarted, nothing edited | 6.8 s | -- |

**Gradle's own incremental compile works on the device**: after the library
edit only `m3/C5.class` was rewritten, and its neighbours kept the previous
build's times. What does not shrink is **about 25 s of fixed cost per build**
-- starting a JVM and configuring nine projects -- because the engine runs
Gradle with `--no-daemon`, and a build that compiles one class pays it in full.
A daemon kept for a few minutes between builds would remove most of it, at the
price of a resident heap of roughly 500 MB on a phone; `GradleBuildSystem` gives
the reasons it was not kept, and that trade is the next decision for this engine,
not a fix to make quietly.

So on this hardware the fast engine rebuilds a project six times the size in a
quarter of the time, and Gradle is the path for what the fast engine cannot
build, as `docs/PLAN.md` intends.

## Not measured

- **A phone.** The emulator has a desktop CPU and more RAM than many phones.
- **A real app's dependency graph.** These projects have no dependencies;
  resolution and AAR extraction are measured elsewhere.
