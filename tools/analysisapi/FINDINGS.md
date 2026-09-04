# The Kotlin Analysis API, for code intelligence on device

Spike R12 (`tools/kotlinls/FINDINGS.md`) established the gap: the editor answers
Java and C/C++ and returns nothing for a `.kt` file, and the compiler archive we
already ship has the K2 front end but **zero** Analysis API classes. This is the
toolchain that closes it, and what it cost to establish that the route works.

**Status: it works on a desktop JVM — a session opens and answers. On ART it
stops in Caffeine.** §§10-11 are the two findings that got it working and the
one thing still in the way.

**Status: it loads and resolves on a device.** Spike `:spike:kotlinls` runs the
whole arrangement on the `aideos_test` emulator: the archives load, the
relocated references resolve, and the descriptors read back correct. What has
*not* happened is a session being opened or a query answered — §7.

**The toolchain builds.** `fetch-jars.sh`
rebuilds the set from a clean machine and verifies every jar, `relocate.sh`
rewrites it onto the compiler's namespace and refuses to leave a partial
result, and `build-dex.sh` produces a **1.9 MB archive holding a 6.9 MB
`classes.dex` of 7310 classes**, byte-identical between runs. What has *not*
happened is loading it on ART or answering a single query.

---

## 1. It is not on Maven Central, and the POMs actively mislead

`analysis-api-standalone-for-ide` and its siblings are published only to
JetBrains' own repository, `packages.jetbrains.team/maven/p/ij/intellij-
dependencies`. [KT-56203] has tracked publishing them to Central for years.
Checked directly: `analysis-api`, `analysis-api-standalone`, `analysis-api-fir`
and `high-level-api-for-ide` all 404 on `repo1.maven.org`.

**Worse, the POMs cannot be resolved.** Every `-for-ide` artifact declares
dependencies on `analysis-api-standalone-base`, `analysis-api-fir-standalone-
base` and `analysis-api-standalone` — and all three 404 **from the repository
that serves the POM**, because they are shadowed *into* the `-for-ide` jar. A
Maven or Gradle resolution therefore fails on a dependency that does not exist
and never will.

So the jars are fetched **by name**, and `fetch-jars.sh` ignores POMs entirely.
That is not a shortcut taken to save effort; it is the only route that works.

[KT-56203]: https://youtrack.jetbrains.com/issue/KT-56203/AA-Publish-analysis-api-standalone-and-dependencies-to-Maven-Central

## 2. The set is seven jars and 7.8 MB

Much smaller than the 54 MB compiler it sits beside:

| Jar | Size |
|---|---|
| `analysis-api-k2-for-ide` | 3.7 MB |
| `low-level-api-fir-for-ide` | 1.5 MB |
| `analysis-api-for-ide` | 0.9 MB |
| `symbol-light-classes-for-ide` | 0.8 MB |
| `analysis-api-impl-base-for-ide` | 0.4 MB |
| `analysis-api-platform-interface-for-ide` | 0.2 MB |
| `analysis-api-standalone-for-ide` | 0.2 MB |

Pinned at **2.2.10**, matching `kotlin-compiler-embeddable` in
`../kotlinc/jars.lock`. The Analysis API binds to compiler internals; the two
move together, and a mismatched pair is not a combination anyone supports.

## 3. Almost all of what it needs, we already ship

Measured with `jdeps` against the existing jar set, then resolved by hand
against the relocated namespace (§4). Of **298** classes `jdeps` reports as
missing:

- **274 are satisfied** by the platform already inside
  `kotlin-compiler-embeddable`;
- **24 are not**, and they divide cleanly:
  - **18** are Kotlin *scripting* (`kotlin.script.experimental.*`,
    `org.jetbrains.kotlin.scripting.*`) — needed for `.kts`, not for editing
    ordinary `.kt` source;
  - **3** are the *assignment* compiler plugin — optional;
  - **2** are genuine platform classes:
    `com.intellij.openapi.util.Iconable$IconFlags`, referenced by
    `symbol-light-classes`, and `com.intellij.openapi.vfs.VirtualFileUtil`,
    referenced by `analysis-api-k2`.

At package level the fit is exact: all **58** distinct `com.intellij` packages
the Analysis API needs are among the **402** the compiler jar already carries.

Of the third-party libraries it wants, **guava (656 classes), opentelemetry
(108) and picocontainer (4) are already there**, relocated. **Caffeine is the
only genuine addition** — 0.9 MB, and the one row in `jars.lock` whose version
is our choice rather than JetBrains', because no `-for-ide` POM declares it:
these artifacts expect a running IntelliJ platform to provide it.

## 4. The namespaces do not line up, and that is the real work

`kotlin-compiler-embeddable` **relocates** the IntelliJ platform to
`org/jetbrains/kotlin/com/intellij/…`. The Analysis API is built against the
**unrelocated** platform and references plain `com/intellij/…` — verified by
scanning bytecode rather than inferred: of 640 classes in `analysis-api-for-ide`,
27 reference `com/intellij/psi` and **none** reference the relocated form.

So the two cannot simply be put on one classpath. Three ways out:

1. **Relocate the Analysis API's references** to match what we already ship —
   rewrite `com/intellij/` → `org/jetbrains/kotlin/com/intellij/`, and the same
   for guava, opentelemetry and picocontainer. §3 says the targets are all
   present. **This is what `relocate.sh` does, and it works**: 8877 references
   rewritten across 4327 classes, none left behind. §5 is what it took.
2. **Ship the unrelocated compiler and a real intellij-core** beside it. Correct
   by construction, and it means a *second* Kotlin compiler in the APK.
3. **Un-relocate the compiler's copy.** Rewriting the 54 MB artifact everything
   else already depends on, to suit a 7.8 MB one. Recorded to be dismissed.

**This is not the same problem the C++ toolchain had.** Nothing here is about
architecture or libc: every one of these jars is portable JVM bytecode. What
does not line up is a *name*, chosen by whoever shaded the compiler.

## 5. A relocation is four rewrites, and three of them are invisible

`Relocate.java` uses the ASM already inside `kotlin-compiler-embeddable`
(`ClassRemapper` included), so it adds no dependency. The byte patching next
door in `../kotlinc/build-kotlinc-dex.py` could not be used at all: it asserts
every rewrite is the same length, because a constant-pool name is
length-prefixed UTF-8, and these prefixes grow by 21 characters.

`ClassRemapper` alone gets the constant pool right and **leaves three other
carriers of class names untouched**. Each was found by counting what remained,
and each fails in its own way and at its own distance from the cause:

| Carrier | Found via | What a stale name does |
|---|---|---|
| Constant pool | `ClassRemapper` | nothing — this part is correct |
| `@kotlin.Metadata` `d2` | 83 left after the first pass | jars **run** fine; Kotlin code *compiled against them* resolves parameters to classes that are not on the classpath |
| `SourceDebugExtension` — the attribute **and** the `@kotlin.jvm.internal.SourceDebugExtension` annotation that duplicates it | 61, then 12 | an inlined stack frame is attributed to a class that does not exist |
| Signature strings in `LDC` constants | the last 12 | a Kotlin callable reference (`Symbol::psi`) is compiled as `PropertyReference1Impl` holding its signature **as a string**; reflection then looks up a member by a descriptor naming a class that is not there, and it fails when the reference is *used*, not when it is made |

Two of those are worth dwelling on.

`d1` in `@Metadata` is protobuf whose class references are **indices into
`d2`**, so `d2` is the string table and rewriting it is complete rather than
approximate — there is no second place to fix.

The `SourceDebugExtension` duplication is the trap that most looks like a bug in
the fix. Handling the class attribute halves the residue and leaves the
annotation copy behind, so the count drops and does not reach zero, which reads
exactly like the rewrite not working.

The signature-string rewrite is the one guarded heuristic here: it fires only on
strings that name a shaded class **in descriptor position** (`Lcom/intellij/…`)
*and* look like a signature. Prose mentioning the package is left alone. That
guard is a judgement, and it is the line most worth re-reading if something
reflective misbehaves later.

## 6. Dexing was the easy part, which was not expected

The compiler needed seven startup fixes before it ran on ART, so the working
assumption was that this would need its own crop. It did not.

`d8` accepted the relocated set at `--min-api 30` — the same floor as the
compiler archive and aapt2 — with no shim, no class replaced and no rewrite.
The reason is visible in what the Analysis API reaches for: **no
`java.lang.management`, no AWT, and three classes touching `javax.swing`**,
against a compiler that needed `java.lang.management` rewritten wholesale. This
is a library that reads code, not one that runs a build.

Two things the archive gets right that are easy to miss:

- **The compiler jars are `--classpath`, not input.** They are already dexed in
  the kotlinc archive; dexing them again would put two copies of the platform on
  one device and leave the loader's parent ambiguous.
- **`META-INF/analysis-api/*.xml` ships beside the dex.** Those descriptors are
  how the API registers its services, and they are taken from the *relocated*
  jars so the class names in them match the dex next to them. An archive with
  the dex and without them loads every class and provides nothing.

The archive is **reproducible**: `d8` is deterministic, so with timestamps
normalised two builds are byte-identical. That is what lets it be pinned the way
`jars.lock` pins its inputs, rather than merely published.

## 7. It loads on ART, and the fixes it needed were not code

The prediction in §6 was that dexing cleanly would not mean running. It was
right to be suspicious and wrong about where the cost would fall: **the Analysis
API needed no shim, no replaced class and no rewrite to load.** The compiler
needed seven. What it needed instead was two facts about staging, and both
present as the archive being broken.

`AnalysisApiLoadTest` asserts, on device:

- the compiler archive loads on its own — the control, so a staging mistake
  cannot be mistaken for a relocation one;
- `org.jetbrains.kotlin.com.intellij.psi.PsiElement` loads **under its relocated
  name**, which is the premise everything else rests on;
- `KaSession`, `KaCompletionCandidateChecker` and
  `StandaloneAnalysisAPISessionBuilder` all load;
- **the relocated references resolve**, not merely spell correctly — reading
  `KaSession`'s method signatures forces 643 types across 252 methods to
  resolve, and none of them lands on an unshaded `com.intellij` name. Loading a
  class only reads its own bytes; this is what would have caught a rewrite that
  pointed at something absent;
- the registration descriptors read back through the loader with the class
  relocated and the extension namespace intact.

### The two traps, both of which look like a corrupt archive

**A dex file the app can write to will not load.** Since API 29:

```
java.lang.SecurityException: Writable dex file
  '/data/user/0/…/kotlin-compiler-2.2.10.zip' is not allowed.
```

Thrown by the `PathClassLoader` **constructor**, not by the first `loadClass`,
so it reads as the archive being unopenable rather than as a permission on it.
Anything the app downloads and then loads has to be made read-only first.

**The published compiler component is a zip of two jars, not a dex archive.**
`kotlinc.jar` is the loadable one, carrying six dex files; `kotlin-stdlib.jar`
beside it is ordinary JVM bytecode the compiler reads as its `kotlin-home`, and
`PathClassLoader` cannot open it at all. Handing the outer zip to the loader
fails with `Entry not found`, which names neither the zip nor the jar. Anything
loading this component must reach inside it first.

## 8. ~~A session will not build~~ — solved by §10, kept for the diagnosis

**This finding's conclusion is superseded.** A session does build; §10 has the
three fixes. What is kept is the reasoning, because the *elimination* it
records is what made §10 findable — and because "it fails identically on a
desktop JVM" turned out to be the most useful single fact in the whole spike.

`AnalysisProbe` — real code compiled against the relocated jars, shipped dexed
beside them, so the API's Kotlin DSLs are driven as code rather than by
reflection — gets as far as `buildStandaloneAnalysisAPISession` and stops:

```
RuntimeException: Cannot resolve /META-INF/analysis-api/analysis-api-fir.xml
  (dataLoader=resources data loader)
    at …ide.plugins.XmlReader.readInclude:908
    at …ide.plugins.PluginXmlPathResolver.resolvePath:105
```

**The single most useful fact was: the same probe, on the same relocated jars,
failed identically on a desktop JVM.** So it was not ART, not dex, not
`PathClassLoader` and not the relocation's linking — all of which the loading
tests already show working. The remaining work is aligning the IntelliJ
platform, which is a different and much better-understood kind of problem than
"does this run on Android".

Ruled out, each at the cost of a run:

- **Resource visibility.** The loader reads
  `META-INF/analysis-api/analysis-api-fir.xml` with *and* without a leading
  slash (`the_loader_can_read_the_descriptors_as_resources` asserts it). A
  slash-tolerant classloader on the desktop changes nothing.
- **The classloader arrangement**, which was a real bug and is fixed — §9.
- **The relocation being incomplete**: the probe *compiles* against these jars
  with the Kotlin compiler, which reads `@Metadata` to reconstruct their
  declarations. A stale `d2` would have failed that compile, so §5's hardest
  rewrite is independently confirmed.

What is left is the platform. `kotlin-compiler-embeddable` shades a *trimmed*
`intellij-core`, and its `PluginXmlPathResolver` resolves includes out of jar
files through a `ZipFilePool` with the resource loader only as a fallback. The
Analysis API expects a fuller platform than the compiler carries. The next thing
to try is supplying the platform's plugin-descriptor machinery properly rather
than relying on the compiler's copy of it.

`AnalysisSessionTest.a_session_still_cannot_be_built` pins this, and is written
to **fail when the blocker goes**, with the two real assertions in its message.

## 9. Two archives, one classloader — chaining does not work

The obvious arrangement is a chain, and it is wrong:

```
boot → kotlinc archive → analysis-api archive     ✗
boot → [kotlinc : analysis-api : probe]           ✓
```

Chained, the API's classes load fine and then service registration fails with a
`ClassNotFoundException` for
`PluginStructureProvider$PluginDesignation` — **a class plainly present in the
dex**, which reads as a corrupt archive.

The cause is that IntelliJ resolves plugin classes through
`MockComponentManager.loadClass`, which uses `Class.forName` and therefore *its
own* loader. That code lives in the compiler archive, which is the **parent**,
and a parent cannot see into a child. Nothing about the error says so.

Flat and parented to the boot loader is the arrangement, for the reason
`../kotlinc/FINDINGS.md` already gives about not parenting to the app's loader:
the app ships its own kotlin-stdlib and d8 synthesises helpers independently for
each.

Setting the thread context classloader was tried first and is *not* the fix —
`Class.forName` in this path does not consult it — though it is set anyway,
since other IntelliJ paths do.

## 10. It answers — and three things had to be right first

On a desktop JVM, against the relocated archive, `AnalysisProbe` opens a
standalone session and answers:

```
OK greet(kotlin/String):kotlin/String
   count(kotlin/collections/List<kotlin/Int>):kotlin/Int
```

`String` renders as `kotlin/String` and `List<Int>` as
`kotlin/collections/List<kotlin/Int>`; neither appears in the source text, so
that is resolution, not parsing. Three fixes, in the order they were needed.

### The descriptors have to be flattened

`xi:include` resolution in the shaded IntelliJ resolves `analysis-api-fir.xml →
impl-base → compiler.xml → platform-interface` and then **silently stops**.
Logging every resource lookup shows `low-level-api-fir.xml` and
`symbol-light-classes.xml` are *never requested* — the two that live in a
different jar from the file including them. It does not ask and get refused; it
does not ask.

And the error names the wrong file:

```
Cannot resolve /META-INF/analysis-api/analysis-api-fir.xml
```

which is the file being *read*, not the include that failed — so it sends you to
inspect something that loaded perfectly. `flatten-descriptors.py` substitutes
every include for its content at build time, and the question stops existing.

### kotlinx-serialization is genuinely missing

The shaded platform reads plugin XML through
`com.intellij.util.xml.dom.XmlElement`, which needs `KSerializer`, and no copy
is bundled. The compiler never takes that path itself, which is why its archive
has always been fine without it.

### kotlinx-collections-immutable was never a missing dependency

The one that cost most, and the trap is worth stating in full. `jdeps` reports
its classes unresolved **exactly as it does for a genuinely absent library**, so
it was added as a dependency. The error went away.

What that actually did was put a second, *unshaded* copy beside the one the
compiler already shades. Nothing failed to link. It surfaced much later, from
inside analysis, as:

```
NoSuchMethodError: FirSupertypeResolverVisitor.<init>(FirSession,
  SupertypeComputationSession, ScopeSession,
  kotlinx.collections.immutable.PersistentList, …)
```

against a compiler whose constructor is identical **but for that one parameter**
being `org.jetbrains.kotlin.kotlinx.collections.immutable.PersistentList`. It
belongs in `Relocate.java`'s `SHADED` list, and adding it there took the
relocated reference count from 8877 to 9204.

The general rule this produces: **a library the compiler shades and we also
supply does not fail to link — it fails to *match*, later, somewhere else.**
Before adding any dependency `jdeps` reports missing, check whether the compiler
already shades it.

---

## 11. Caffeine is where it stops on ART, and both versions fail

Everything above works on ART too — the archives load, the session gets as far
as `StandaloneAnalysisAPISessionBuilder.registerProjectServices` — and then
Caffeine, which the Analysis API caches with and the compiler does not bundle.
**Both versions fail on Android, for unrelated reasons, and neither is about the
relocation:**

| Version | Fails on | Why Android lacks it |
|---|---|---|
| 3.1.8 | `System.getLogger` in `Caffeine.<clinit>` | the Java 9 `System.Logger` API; Android has never had it |
| 2.9.3 | `Thread.threadLocalRandomProbe` via `Unsafe`, in `StripedBuffer` | a JDK-internal field Android's `Thread` does not declare |

Both are thrown from static initialisers, so neither has a fallback path, and
2.9.3's arrives wrapped twice — `IllegalStateException` whose message is only a
class name, over `InvocationTargetException`, over the real
`NoSuchFieldException`. The top frame says `LocalCacheFactory` and nothing about
`Thread`.

`caffeine.jar` is pinned at **2.9.3** because it gets further: 3.x dies before
the session starts, 2.9.3 dies inside it.

**This is shimmable, and the repo already has the pattern.**
`../kotlinc/build-kotlinc-dex.py` replaces four compiler classes outright with
sources under `--shim`, for exactly this class of problem — "classes that cannot
be rescued by renaming". `StripedBuffer` is the one on the failing path. That is
the next piece of work and it is bounded.

## 12. What is still unknown

Honest limits of what has been established. None of this is evidence yet.

- **No query has been answered *on ART*** — §11. The desktop answers, so what
  remains is Android-specific and named.
- **The two platform classes §3 lists have still not been reached**, so whether
  they matter is unknown.
- **Latency is still the milestone's real question.** M3 holds Java completion
  to 200 ms and meets it at 76 ms. Nothing measured so far predicts what the
  Analysis API costs to *answer*, only what it costs to load — which was 0.4 s
  for the API archive on the emulator, against the compiler archive's eleven
  seconds of startup.
- **No latency number exists.** M3 holds Java completion to a 200 ms budget and
  meets it at 76 ms. Whether the Analysis API can be made to answer in that time
  on a phone is the question the milestone actually turns on, and it is
  unanswered. The 11 s figure in `../kotlinc/FINDINGS.md` is compiler *startup*
  and does not predict this either way.
- **The 2 missing platform classes may or may not matter.** Both sit on paths a
  simple completion request might never take. "Might never" is not a plan.
- **`kotlinx-collections-immutable` was invisible until the relocation worked.**
  Before it, `jdeps` reported every reference as unresolved and this one was
  lost among 298; afterwards it stood out as the only non-optional gap besides
  the two platform classes. Anything else hiding the same way will only appear
  once the dex build runs.
