# `:engine:deps` — findings

Spike R4 (`tools/deps/FINDINGS.md`) established that maven-resolver runs on ART
at all, and what four workarounds that took. This file records what was learned
turning it into a resolver an Android build can trust — which is a different
question, and a harder one.

The short version: **`androidx.appcompat` resolves correctly out of the box.
Compose does not.** Everything below came out of getting a Compose graph onto a
device without D8 rejecting it or the app dying on launch.

## 1. AndroidX resolves correctly only under Gradle Module Metadata — so read it

Gradle resolves an AndroidX graph using information that is **not in the POM**.
That information is the `.module` file beside it, and it does three separate
jobs. This module reads it. It did not always: the first version of `:engine:deps`
guessed at two of the three with curated tables, and this section used to be an
argument for why that was tolerable. It is now a record of why it was not.

**Variant redirection.** A KMP library publishes a root coordinate
(`androidx.collection:collection`) plus one artifact per platform
(`collection-jvm`, `compose.ui:ui-android`). The root's variants declare
`available-at` the platform artifact, so Gradle resolves exactly one. In the
POM world each is an ordinary module with ordinary dependencies and nothing
relates them, so conflict resolution has no reason to object — and D8 ends the
build with `Type androidx.collection.ArrayMap is defined multiple times`.

`withoutDuplicateKmpVariants` now **reads the redirect** and collapses a root
into a variant only when the root's own metadata says that is where it lives.
It used to collapse any two artifacts sharing a group and a base name,
preferring an `-android` suffix — right for AndroidX, an assumption everywhere
else, and silently wrong for two unrelated modules named `foo` and
`foo-android`.

The suffix rule survives as the fallback for *variant beside variant*
(`runtime-annotation-jvm:1.9.2` next to `runtime-annotation-android:1.12.0`),
which no root's metadata describes: neither redirects anywhere, and the only
thing relating them is the name.

**Group alignment.** Every AndroidX module carries a `dependencyConstraints`
block pinning its whole group to its own version — the alignment a BOM would
give, published per module:

```
androidx.activity:activity:1.13.0 constrains
    androidx.activity:activity-compose  requires 1.13.0
    androidx.activity:activity-ktx      requires 1.13.0
```

Without it a graph keeps whatever version each POM happened to name, and a
module that was split or absorbed sits beside its successor at an old version
that still contains the shared classes. `lifecycle-common-java8:2.3.0` beside
`lifecycle-common-jvm:2.9.4`, both defining `DefaultLifecycleObserver`.

**This is what the second curated table was standing in for, and it is gone.**
`withoutAbsorbedModules` and its hand-verified list of absorbed modules have
been deleted. `aligned()` raises each module to the highest floor published for
it, and at the aligned version the absorbed artifact is an empty jar that only
depends on its successor — so there is nothing to drop.

Verified by deleting the table and running M6's end-to-end test: the Compose
app builds, installs and draws.

**Capabilities are not the mechanism.** This was assumed for a while and is
wrong. AndroidX declares no capabilities in its `.module` files at all — a
`capabilities` block appears nowhere in `lifecycle-common`, `lifecycle-common-java8`
or `activity`. The absorption problem is a *version* problem, and alignment is
the whole of the fix.

## 2. Constraints are floors, and floors are roots

`CollectRequest.setManagedDependencies` is the obvious way to express a version
floor to Aether and **it does not work here**. Maven's classic dependency
manager applies the root request's management only from a certain depth, so
`activity-ktx` stayed at the 1.7.0 a transitive POM asked for while the floor
said 1.13.0. The floors were computed correctly and had no effect, which is the
most expensive kind of wrong.

Adding each floor as an **additional root** does work: at depth 0 it enters
conflict resolution like anything else, and this session's
`HighestVersionSelector` takes the highest. It also fits what a floor is —
nothing new reaches the classpath, because only modules already in the graph
are given one.

**One extra pass, not a fixpoint.** Gradle iterates until constraints stop
moving; this collects again exactly once. That bound is deliberate: an earlier
attempt at group alignment pinned every module to the highest version *seen*
rather than to what was published, and the resulting graph exhausted ART's
192 MB heap.

## 3. Alignment must not make a graph unresolvable

A published constraint can name a version that does not exist as a *file*.
`org.jetbrains.kotlin:kotlin-stdlib-common` is the case that found this: at 2.x
it is a metadata-only module for Kotlin Multiplatform, so a constraint raising
it to 2.1.20 produces a coordinate with a POM and no jar. The classpath silently
lost an entry it had before.

Alignment improves a graph that already worked, so it is not allowed to break
one. Where the raised version cannot be fetched, the version the first collect
chose is used instead.

That fallback then caused the *opposite* failure, and the pair is worth keeping
together. `resolveWithAarFallback` fell back only jar → aar, on the assumption
that a wrong guess is always "we said jar and AndroidX publishes an aar". Floor
roots are declared as aars like every other root, and `lifecycle-common-java8`
is a jar — so the aligned coordinate failed to resolve, the fallback reverted to
the *older* version alignment had just corrected, and the duplicate class came
back looking exactly like alignment not working.

Two fixes, both kept: the extension fallback is symmetric, and a floor root
carries the extension its module already resolved as rather than being guessed
at again.

## 4. Read metadata in parallel, and remember what is missing

Reading a `.module` per artifact turned a 635 ms warm resolve into **24 s**. The
budget test in `DependencyResolverTest` is what caught it, which is the argument
for having build-time budgets as assertions rather than aspirations.

Two causes, both fixed:

- **Serial reads.** 43 artifacts at roughly 440 ms each, almost all latency.
  Eight at a time brings the warm cost to 222 ms.
- **Re-asking for files that are not there.** Most of a graph publishes no
  `.module` at all, and without `SimpleResolutionErrorPolicy(true, false)` every
  resolve asks the remote again for each of them.

Measured after: `appcompat` warm **719 ms** against 635 ms before any of this,
and the Compose graph — which raises twenty modules and therefore pays for the
second collect — **2.6 s** warm. Both are guarded by tests now; the second was
added because `appcompat` publishes constraints that raise nothing, so it would
report a comfortable figure however slow alignment became.

## 5. Conflict resolution does not deduplicate a module

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

## 6. One collect request, not one per coordinate

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

## 7. Gradle's conflict semantics, not Maven's

`HighestVersionSelector` is hand-written because maven-resolver 1.9 ships only
`NearestVersionSelector`. It is not a preference. AndroidX declares hard version
ranges (`<version>[1.13.0]</version>`), and where the same artifact also arrives
by a path with a soft requirement, nearest-wins refuses to choose at all:
`UnsolvableVersionConflictException`, and resolution stops. Every real AndroidX
graph hits this.

Where nearest-wins *can* choose, it selects older artifacts than the same
project gets from Gradle — which is worse than failing, because it fails later
and somewhere else.

## 8. A library's package comes from its manifest, and a silent parse is fatal

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

## 9. Things known missing

- **`.module` is read only for redirects and constraints.** Variant
  attributes, capabilities, per-variant dependency lists and file entries are
  all ignored, because honouring them means implementing Gradle's variant-aware
  resolution rather than Maven's. The gap that will bite first is **variant
  selection**: this always prefers `-android` then `-jvm` by name, where Gradle
  matches attributes.
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
