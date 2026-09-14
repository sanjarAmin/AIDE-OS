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

## Not measured

- **A phone.** The emulator has a desktop CPU and more RAM than many phones.
- **An edit on Gradle.** Only the fast engine was measured after a one-class
  edit (section 16 of its FINDINGS).
- **The editor on a multi-module project.** It reads one module's sources and
  `aide.json`'s dependencies, so in a Gradle root every reference into another
  module, and `R`, is unresolved. Building works; completion across modules
  does not.
- **A real app's dependency graph.** These projects have no dependencies;
  resolution and AAR extraction are measured elsewhere.
