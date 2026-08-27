# `:engine:deps` — findings

Spike R4 (`tools/deps/FINDINGS.md`) established that maven-resolver runs on ART
at all, and what four workarounds that took. This file records what was learned
turning it into a resolver an Android build can trust — which is a different
question, and a harder one.

The short version: **`androidx.appcompat` resolves correctly out of the box.
Compose does not.** Everything below came out of getting a Compose graph onto a
device without D8 rejecting it or the app dying on launch.

## 1. AndroidX resolves correctly only under Gradle Module Metadata

Gradle resolves an AndroidX graph using information that is **not in the POM**.
That information is the `.module` file, maven-resolver reads `.pom` only, and
the gap does three separate kinds of damage.

**Variant redirection.** A KMP library publishes a root coordinate
(`androidx.collection:collection`) and one artifact per platform
(`collection-jvm`, `compose.ui:ui-android`). The `.module` file says the root
*redirects* to a platform artifact, so Gradle resolves exactly one. In the POM
world each is an ordinary module with ordinary dependencies, and nothing marks
them as related — so conflict resolution has no reason to object. They are, as
far as it can see, unrelated libraries that happen to contain the same classes.
D8 disagrees: `Type androidx.collection.ArrayMap is defined multiple times`.

Both shapes occur in one Compose graph. *Root beside variant*:
`compose.ui:ui:1.0.1`, which `activity-compose` still names, beside
`ui-android:1.9.0`. *Variant beside variant*: `runtime-annotation-jvm:1.9.2`
beside `runtime-annotation-android:1.12.0`, down different branches.

`withoutDuplicateKmpVariants` keeps one artifact per (group, base name),
preferring `-android`, then `-jvm`, then the root — **by target, not by
version**. This is an Android build, so the Android variant is the right one
even when an older POM pins the root higher.

**Capability conflicts.** When AndroidX folds one module into another, the
absorbing module declares the absorbed one's *capability*; Gradle sees a
capability conflict and keeps one. maven-resolver sees two unrelated modules,
keeps both, and — since nothing in the graph asks for the absorbed module's
newer version — leaves it pinned at the old version that still has the classes.
`lifecycle-common-java8:2.3.0` beside `lifecycle-common-jvm:2.9.4`, both
defining `DefaultLifecycleObserver`.

`withoutAbsorbedModules` is a curated table gated on the *absorbing* module's
version. The gate matters: a project genuinely pinned to lifecycle 2.3
throughout has no other source for those classes, and dropping them there breaks
a build that works.

The table is verifiable rather than folklore. At the absorbing version the
absorbed artifact is published as an **empty jar depending only on its
successor**. Check before adding a row:

```sh
unzip -l lifecycle-common-java8-2.9.4.jar   # 4839 bytes, zero .class entries
unzip -l activity-ktx-1.9.0.aar             # 20 classes at 1.8.0, zero at 1.9.0
```

**Platform constraints (the BOM).** The AndroidX BOM is a Gradle *platform*: it
constrains every module in a group to one version without adding dependencies.
Maven has no equivalent a POM-reading resolver can apply.

**This one is not fixed, and the attempt made things worse.** Synthesising a BOM
as managed dependencies — pinning every `androidx.compose.*` module to the
highest version seen — exploded the graph and OOMed the resolver at ART's
192 MB default heap. Reverted. What stands in its place is a rule on the
*caller*: declare contemporaneous versions. A graph mixing eras keeps whatever
each POM happened to name.

**The fix that does not rot is reading `.module` files.** All three mechanisms
are in there, and the two curated tables above exist only because it is not
written. Nothing else in this file will age as badly as those tables.

## 2. Conflict resolution does not deduplicate a module

The most expensive failure of M6 was ours, not Gradle's.

`compose.ui:ui-android:1.12.0` calls `androidx.compose.runtime.HostDefaultProvider`,
a class that does not exist at 1.9.0. The resolved graph contained
`runtime-android:1.9.0` beside `ui-android:1.12.0` — with `HighestVersionSelector`
installed and working correctly. The app built, installed, launched, and died at
`onAttachedToWindow` with `NoClassDefFoundError`.

**Aether's conflict id includes the extension**: `group:artifact:classifier:extension`.
AndroidX POMs declare `<type>` on some dependency entries and omit it on others,
so one module arrives as both `aar` and `jar` and forms **two conflict groups
that never compete**. Each elects its own winner. Logging the selector shows it:

```
id=androidx.compose.runtime:runtime-android:jar:1.9.0  items=1.9.0@d4          winner=1.9.0
id=androidx.compose.runtime:runtime-android:aar:1.9.2  items=…15 nodes…        winner=1.12.0
```

Both winners reach the collecting visitor. It kept the first arrival — visit
order — and lost. `keepHigher` keeps the higher version instead, compared with
Aether's `GenericVersionScheme`, **not as strings**: lexically `"1.9.0"` sorts
above `"1.12.0"`, which is the same bug again wearing a different hat.

Generalise: a conflict group is not a module. Deduplicate again where the graph
is flattened.

## 3. One collect request, not one per coordinate

Conflict resolution happens *inside* a collect. Looping `collectDependencies`
per declared coordinate resolves each root against itself and never against its
siblings; merging the results afterwards keeps whatever the first-listed root
wanted — so the resolved classpath depends on the order the user typed their
dependencies.

Not theoretical. `activity-compose` reaches `compose.runtime` at 1.7.0 and
`foundation-android:1.12.0` reaches it far newer. Resolved separately and
merged, 1.7.0 won on listing order — and 1.7.0 still contains the annotations
later versions moved into `runtime-annotation`, so D8 failed on a duplicate
`androidx.compose.runtime.Immutable`.

## 4. Gradle's conflict semantics, not Maven's

`HighestVersionSelector` is hand-written because maven-resolver 1.9 ships only
`NearestVersionSelector`. It is not a preference. AndroidX declares hard version
ranges (`<version>[1.13.0]</version>`), and where the same artifact also arrives
by a path with a soft requirement, nearest-wins refuses to choose at all:
`UnsolvableVersionConflictException`, and resolution stops. Every real AndroidX
graph hits this.

Where nearest-wins *can* choose, it selects older artifacts than the same
project gets from Gradle — which is worse than failing, because it fails later
and somewhere else.

## 5. A library's package comes from its manifest, and a silent parse is fatal

aapt2 generates `R` for the manifest's package and nothing else, but library
code references *its own* `R`. The app's package list therefore has to carry
every AAR's package into `--extra-packages`; `:engine:fast`'s `FINDINGS.md`
section 12 has that half.

The part that belongs here: `AarExtractor` reads the package with a regex over
the AAR's `AndroidManifest.xml`, and **a regex that matches nothing is
indistinguishable, downstream, from a graph containing no Android libraries at
all**. Same empty list, same successful build, same APK, same crash on launch.
One shipped exactly that way — a raw-string escape doubled by the tool that
wrote the file, `\\bpackage` instead of `\bpackage`.

`AarExtractorTest` asserts the parse against a realistic manifest, and
`ComposeRunTest` asserts the resulting list is non-empty before it builds
anything. Neither is redundant: the first localises the fault, the second is the
only one that would notice a break in the plumbing between them.

## 6. Things known missing

- **No `.module` parsing**, per section 1. Two curated tables stand in for it.
- **No snapshots.** `AarExtractor` caches extraction by existence plus mtime,
  which is sound only because a released artifact at a fixed version is
  immutable by contract. A snapshot breaks that assumption.
- **No exclusions, no `dependencyManagement` from the consuming project.** A
  user cannot yet say "exclude this transitive dependency", which is the normal
  escape hatch for exactly the conflicts sections 1 and 2 describe.
- **Resolution is all-or-nothing per artifact and reported, not thrown.** An
  artifact that fails to fetch lands in `ResolvedDependencies.unresolved` and
  the build proceeds with a hole in the classpath. Right for the app — one bad
  POM should not lose the other sixty — and a trap for tests, which must assert
  that list is empty.
