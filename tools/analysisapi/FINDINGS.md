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

## 11. ~~Caffeine is where it stops on ART~~ — solved by §12, kept for the diagnosis

Superseded. Kept because the *diagnosis* below is still the reason the shim in
§12 looks the way it does, and because both halves of the table remain true of
the upstream jars.

The session used to reach `StandaloneAnalysisAPISessionBuilder.registerProjectServices`
and die in Caffeine, which the Analysis API caches with and the compiler does
not bundle. **Both versions fail on Android, for unrelated reasons, and neither
is about the relocation:**

| Version | Fails on | Why Android lacks it |
|---|---|---|
| 3.1.8 | `System.getLogger` in `Caffeine.<clinit>` | the Java 9 `System.Logger` API; Android has never had it |
| 2.9.3 | `Thread.threadLocalRandomProbe` via `Unsafe`, in `StripedBuffer` | a JDK-internal field Android's `Thread` does not declare |

Both are thrown from static initialisers, so neither has a fallback path, and
2.9.3's arrives wrapped twice — `IllegalStateException` whose message is only a
class name, over `InvocationTargetException`, over the real
`NoSuchFieldException`. The top frame says `LocalCacheFactory` and nothing about
`Thread`.

`caffeine.jar` is pinned at **2.9.3** because it is the one that can be shimmed:
3.x dies before the session starts, 2.9.3 dies inside it, at a single class.

## 12. It answers on ART, and it took three shims — not one

A standalone session builds on a device and resolves declarations:

```
OK greet(kotlin/String):kotlin/String count(kotlin/collections/List<kotlin/Int>):kotlin/Int
```

`kotlin/String` is the whole claim. The source says `String`; only a resolved
front end says `kotlin/String`, and `kotlin/collections/List<kotlin/Int>`
appears nowhere in the source text at all.

**§11 named one blocker and there were three.** Each was only visible once the
one before it was gone, which is the part worth recording: fixing Caffeine did
not produce a session, it produced the next `NoClassDefFoundError`. Budget for
that shape rather than for a count.

| Shim | Why Android lacks it | What reaches for it |
|---|---|---|
| `com.github.benmanes.caffeine.cache.StripedBuffer` | `Thread.threadLocalRandomProbe` is a JDK-internal field | every Caffeine cache, via `<clinit>` |
| `javax.management.*` — 5 types | Android has no JMX | IntelliJ's `LowMemoryWatcherManager`, via `AppScheduledExecutorService.<init>` |
| `javax.swing.SwingUtilities` | Android has no Swing | `MockApplication.isDispatchThread`, on entry to **every** `analyze {}` |

The last two are stubs. The first is not, and the difference matters:

- **`javax.management` and `javax.swing` are asked a question, not used.**
  `SwingUtilities.isEventDispatchThread()` returns **`false`**, which is not a
  lie to get past a check — it is the truthful answer. There is no Swing event
  dispatch thread in this process, so no analysis can be running on one, and
  `KaBaseAnalysisPermissionChecker.isProhibitedEdtAnalysis` is right to say so.
  A stub that returned `true` would forbid every query.

- **`StripedBuffer` is real code and had to keep working.** It is Caffeine's
  write-buffer striping: it hashes a thread onto a slot, and on contention
  re-hashes and grows. The JDK field it reads is a per-thread random probe,
  which is genuinely absent, so the shim substitutes a `ThreadLocal<int[]>` and
  keeps the xorshift that advances it. **Three members changed; everything else
  is Caffeine 2.9.3's own source**, generated by patching it, so the striping
  behaviour is upstream's rather than a reimplementation.

  The subtle part is seeding. Upstream calls `ThreadLocalRandom.current()` for
  nothing but its side effect — it seeds the `Thread` field. With the field gone
  that call seeds nothing, the probe stays `0` forever, and every thread hashes
  to slot 0: the buffer still *works*, and quietly loses all its striping under
  contention. The shim seeds the `ThreadLocal` explicitly, avoiding 0. **This is
  the kind of shim failure that does not throw.**

  It retains one `Unsafe.compareAndSwapInt`, on `StripedBuffer`'s **own**
  `tableBusy` field. That is fine — the field exists because the class declares
  it. Only the reach into `Thread` was the problem.

Two dropped things, both deliberate: the `org.checkerframework` `@Nullable`
import (compile-only, and pinning an artifact to erase an annotation is not
worth it), and, from the build, the *originals* of every shimmed class — d8
rejects a duplicate type, and it rejects it several minutes in. `build-dex.sh`
drops by class-name *stem*, so nested and synthetic classes go with their outer
class, and it **exits non-zero if it dropped nothing** — a shim silently not
replacing anything is the failure this whole file is about.

## 13. Latency: the session is the cost, and the query is not

M3's budget is 200 ms, met by Java completion at 76 ms. The Analysis API on the
emulator, one source module, 42 declarations:

| | measured | paid |
|---|---|---|
| build the session | **1808 ms** | once |
| resolve a declaration first time | **4 ms** median of 42 (min 3, max 463) | per new symbol |
| resolve one already resolved | **0 ms** | repeat |

**A query is ~4 ms. The 200 ms budget is not in danger; session construction
is the entire cost, and it is a startup cost.** That settles the question §12
of the old numbering called the one the milestone turns on: the Analysis API
can back on-device Kotlin intelligence, provided the session is **resident** —
built once and held, the way `:lsp:java` holds a warm javac.

Two traps in measuring this, both of which give a confident wrong answer:

- **Timing `describeFunctions` measures construction.** It builds a session per
  call and throws it away, so it reports ~2.2 s and looks like a verdict on
  queries. It is not one.
- **Timing one query repeatedly measures the cache.** The Analysis API caches a
  resolved symbol, so the second ask about the same declaration is free. That
  0 ms is real, and it is not what a completion on an untouched symbol costs.

Hence `AnalysisProbe.timeQueries`, which reports all three separately and takes
**one sample per declaration** for the first-touch median. The `max=463` is the
first declaration in the file, which builds that file's FIR; every one after it
is single-digit.

The instrumented test asserts only the *shape* — that a first-touch query is
well under a quarter of session construction. A millisecond threshold would
fail on other hardware for a reason nobody could act on. The numbers live here.

## 14. The probe is an archive, and it is built by a script now

The Analysis API is loaded by a `PathClassLoader` parented to the boot loader,
so the instrumented test cannot be compiled against it, and driving Kotlin DSL
builders and lambdas-with-receivers by reflection is not a workaround. The real
work is ordinary Kotlin in `probe/AnalysisProbe.kt`, compiled against the
**relocated** jars, dexed, and loaded as a third archive on the same loader; the
test reflects over one method, `String` in and `String` out.

It must be built against the relocated jars, not the upstream ones — it calls
`org.jetbrains.kotlin.com.intellij.openapi.util.Disposer`, which does not exist
before relocation. Building against upstream produces a probe referring to
`com.intellij.*` and the failure arrives on device as a `NoClassDefFoundError`
for a package that was never shipped.

`build-probe.sh` exists because this was done by hand while the session was
still the open question, and every re-measurement meant retyping a command
line. A probe rebuilt slightly differently is a benchmark that silently
measures something else — and §13 is a benchmark.

One flag worth knowing: **`d8` takes one `--classpath` per jar.** It does not
split the argument on `:` the way javac and the JVM do; it treats the whole
colon-joined string as a single filename, and the `NoSuchFileException` names
every jar concatenated, which reads as a corrupted variable rather than a flag
used wrongly.

## 15. The buffer works, and it costs 59 ms

§14 of the old numbering listed "nothing has been edited" as the case an editor
lives in and the one nothing had tested. It works.

A language service is never asked about a file on disk. It is asked about a
buffer that changes on every keystroke and usually does not parse, and it cannot
rebuild the session to answer -- that is the 1808 ms of §13. The Analysis API's
mechanism for this is a **dangling file**: a `KtFile` built in memory whose
`contextModule` points at a real module, resolved against that module's scope
without belonging to it. It is what IntelliJ does for a modified editor buffer,
and it works on ART unchanged.

| | measured |
|---|---|
| first completion (session build included) | 2369 ms |
| **warm completion from the buffer** | **59 ms** median of 4 (57, 57, 59, 60) |

**59 ms against M3's 200 ms budget, where Java completion sits at 76 ms.**
On-device Kotlin intelligence is not a performance question any more.

What the tests establish, each chosen so a parser or a stale session could not
produce the answer:

- **The buffer resolves.** A local declared only in the buffer, and absent from
  the source root, completes to `kotlin.String`'s members.
- **The buffer beats the session.** Changing that local's type from `String` to
  `Int` and asking again returns `inc`, `shl`, `dec` and **not** `uppercase`. A
  session answering from its own snapshot would pass the first test and fail
  this one, which is why both exist.
- **Diagnostics come from the buffer**: `Initializer type mismatch: expected
  'String', actual 'Int'` — an error a parser cannot find, before any build.
- **Clean code reports nothing.** Without this, a service that reported an error
  on every file would pass the test above and be worse than no diagnostics.

Two things to know before writing `:lsp:kotlin` against this:

- **`markGenerated = false` when creating the file.** `KtPsiFactory`'s default
  marks the file generated, which excludes it from resolution -- and the result
  is an *empty scope*, not an error. Another silent wrong answer.
- **`KaType.scope` is the declared member scope, so extensions are missing.**
  `String` completes to eight members. An IDE offers hundreds, because most of
  what a Kotlin user reaches for -- `uppercase`, `map`, `filter` -- is an
  extension in `kotlin.text` or `kotlin.collections`, not a member. Collecting
  those means walking the file's imported and package scopes and filtering by
  applicable receiver type, and it is not done here.
  `EditingSessionTest.extensions_are_still_missing_from_completion` pins the gap
  and is written to fail when it closes.

## 16. ~~Extensions need an index~~ — solved by §19, kept for the diagnosis

Superseded by §19, which builds the index this section says is needed. Kept
because the diagnosis is why §19 looks the way it does, and because the cost
measurement below is the reason it resolves by prefix first.

§15 pinned extensions as the largest gap between the probe and a usable
`:lsp:kotlin`. This is how far it got, because the obvious approach does not
work and the reason is worth more than the attempt.

**The obvious approach.** Ask the scopes at the cursor for their callables,
keep the extensions, and ask the API whether each applies to the receiver.
Applicability is genuinely the API's to answer -- "is this extension callable
here" involves type parameters, smart casts and the receiver's own generic
arguments -- and `createExtensionCandidateChecker` is what IntelliJ's own
completion uses. That part is right.

**Where it fails: the star-importing scope will not enumerate.** Asked for every
callable it has, `DefaultStarImportingScope` returns 104, of which 76 are
extensions -- and those 76 are overloads of exactly **two names**, `plus` and
`toString`. The default star imports cover `kotlin.text`, `kotlin.collections`,
`kotlin.io` and five more packages holding thousands of callables. The scope is
not filtering them out; it never offers them. It answers for names it has
already been told about, and a completion request after `s.` has no names yet.

So `String` completes to eight members plus nothing, and `uppercase` -- which
almost anyone typing `s.` is reaching for -- is not in the answer.

**Adding the standard library helps, and is not the fix.** The session was
built with no library module at all, which is its own trap (below). Adding
kotlin-stdlib.jar as a `KtLibraryModule` moves the star scope from 30 callables
to 104 and from 4 extension symbols to 76 -- so **the library is read, on ART,
from a plain-bytecode jar** -- and changes the completion result not at all,
because those 76 are still two names.

It does change the cost:

| session | star scope | warm completion |
|---|---|---|
| no library module | n=30, ext=4 | **59 ms** |
| kotlin-stdlib.jar as a library | n=104, ext=76 | **229 ms** |

**Four times the latency for nothing**, spent running applicability checks over
76 overloads that were never going to be useful. That is the finding that
matters for the design: enumerate-then-filter costs in proportion to what the
scope happens to yield, which is unrelated to what the user is asking for. A
real implementation has to go the other way -- take the prefix the user has
typed, get candidate **names** from an index, and only then resolve and check
those. That is what IntelliJ's stub index is for.

**What was ruled out**, and §19 confirmed each on a device:
`KotlinDeclarationProvider` has exactly the right method,
`getTopLevelCallableNamesInPackage`, and it is obtainable
(`project.createDeclarationProvider(scope, module)`). It is PSI-oriented: it
finds `KtNamedFunction`s in Kotlin *source*, so it answers for the project's own
files and returns **nothing** for a library module.

**The library trap, separately, because it is silent.** A session with no
library module still resolves `String` to `kotlin.String` and answers its
members, because those are *builtins* carried by the front end itself. Nothing
errors, nothing is empty, and the standard library appears to be present. It is
not, and the only symptom is that completion is thin -- which looks like the
extension gap above rather than a missing module. Build the session with the
libraries even while only members work.

## 17. The module, and the two shapes it is not

`:lsp:kotlin` is the product form: `KotlinLanguageService` implements the same
`LanguageService` the editor already talks to, and `LanguageServices.serviceFor`
routes `.kt` and `.kts` to it. Seven instrumented tests drive it as the editor
does -- placed diagnostics, prefix-filtered completion, an edit followed, a
function's parameters in the label and not in the buffer, and an unparseable
buffer answered rather than thrown at.

It is a third shape, and neither of the other two:

| | how it runs | how it is called |
|---|---|---|
| `:lsp:java` | warm javac, in this process | directly |
| `:lsp:native` | clangd subprocess | LSP over stdio |
| `:lsp:kotlin` | in this process, **behind a classloader** | reflection into the archive |

The reflection is not a shortcut. Nothing in the app can name a type from the
archive -- it is loaded by a `PathClassLoader` parented to boot -- so the code
that drives the API is written as ordinary Kotlin in `backend/KotlinBackend.kt`,
compiled against the *relocated* jars, dexed, and shipped as a third archive on
the same loader. The app reflects over five methods.

**The wire format is `List<String>`, tab-separated, and failure is a value.**
Only types both sides already agree on can cross that boundary: `String`,
`List`, `int`. A data class declared in the backend would be loaded by the
archive's loader and be a *different class* from an identical one in the app, so
the cast fails with a message naming the same type twice. The same reasoning
rules out throwing: the app cannot catch what it cannot name.

Three things the module has to get right that the spike did not have to:

- **minSdk is 26, and the archives need 30.** Declaring 30 on the library looks
  correct and breaks the build: a library minSdk above the app's fails the
  manifest merge, and `tools:overrideLibrary` would trade a build error for a
  runtime one. `KotlinArchives.isSupported` gates it instead, checked before a
  service is ever constructed -- the rule CLAUDE.md already states for build
  features.
- **The session is resident and closing it is not optional.** ~1.8 s to build,
  ~59 ms to query. `LanguageServices` keeps one per project and closes the old
  one when the project changes, the same as it does for javac -- which holds
  open handles on every jar it resolves against.
- **Queries run on the `compiler` dispatcher, not `io`.** `DispatcherProvider`
  separates them precisely so CPU-bound compiler work cannot starve the
  editor's file reads and autosave. Analysis on `io` would do that on every
  keystroke.

**A green `connectedDebugAndroidTest` does not cover this module.** Its tests
`assumeTrue` on the archives being staged, and a full sweep installs the test
APK -- which wipes the external directory they are staged in -- so they skip.
Skipping reports as `OK (n tests)`, which is exactly how this project lost a
week once before. Run them deliberately, after staging:

```sh
D=/sdcard/Android/data/com.osamu.aide.lsp.kotlin.test/files
adb install -r -t lsp/kotlin/build/outputs/apk/androidTest/debug/kotlin-debug-androidTest.apk
adb push kotlinc-archive.zip          $D/kotlin-compiler-2.2.10.zip
adb push kotlin-analysis-2.2.10.zip   $D/kotlin-analysis-2.2.10.zip
adb shell am instrument -w -r com.osamu.aide.lsp.kotlin.test/androidx.test.runner.AndroidJUnitRunner
```

and check the status codes rather than the summary: `INSTRUMENTATION_STATUS_CODE: 0`
is a pass and `-3` is an assumption skip, and both roll up into `OK`.

**It is packaged but not published.** `build-component.sh` produces
`kotlin-analysis-<version>.zip` holding `analysis-api.jar` and
`analysis-backend.jar` -- two archives in one component because neither works
alone, the same reason the compiler component ships its stdlib.
`ToolchainComponent.KOTLIN_ANALYSIS_API` pins its sha1 and size.
**The release asset itself has not been uploaded**, so on a real device
`kotlinAnalysisArchives()` returns null and Kotlin routes to nothing. That is
the designed silence -- a device below API 30 can never load these, and most
projects never need them -- but it means the feature is one upload away from
reaching anyone, and until then only the instrumented tests exercise it.

## 18. Driving the app found two bugs no test did

Both were invisible to seven passing instrumented tests, and both are the kind
this project keeps meeting: **the code worked and the answer was wrong.**

**Fifteen errors on a correct project.** Opening the Kotlin template covered it
in red -- every import, `Activity()`, `onCreate`, `setContentView` -- with
`Unresolved reference 'Activity'` in the Problems pane. Nothing had failed. The
session simply had no `android.jar`, so the front end was right that nothing
resolved, and the module's plumbing was right to report it: placed, relative,
tappable diagnostics. Only the answer was useless.

`LanguageServices.forProject` already documents this exact trap for javac --
"a file of red squiggles blaming the user for the toolchain not being installed.
Silence is the better failure" -- and the Kotlin path was written without the
guard. It now takes the platform and the project's dependency jars as library
modules, and returns **null** when the platform is not installed.

The tests could not have caught it. They build a session over a hand-written
fixture that only uses `kotlin.String` and `kotlin.Int`, where builtins are
enough. Nothing in that fixture needs a library, so nothing noticed there was
none. **A fixture that avoids the dependency also avoids the bug.**

The fix incidentally settles an unknown: `android.jar` resolves on ART. Opening
the same file afterwards is clean, and completion answers out of it.

**`KaType.toString()` reached the user.** The completion list offered
`setContentView(android/view/View!)` and `setContentView(kotlin/Int)` --
internal names, slashes and all, because `toString()` on a `KaType` is a debug
rendering. The API has the right thing:

```kotlin
type.render(KaTypeRendererForSource.WITH_SHORT_NAMES, Variance.INVARIANT)
```

which gives `setContentView(View!)` and `setContentView(Int)`. The `!` stays --
it marks a platform type, which is real information about nullability, and
IntelliJ shows it too.

No test caught this either, and the reason is worth more than the fix: the
assertion was `"Int" in subSequence.label`, which `kotlin/Int` satisfies. **A
substring assertion passes on a superstring.** It now asserts the whole label,
`subSequence(Int, Int)`.

**A third thing looked like a bug and was not, and the way it was nearly
recorded is the lesson.** Driving with `adb shell input text 'this.setC'`
produced no completion popup; a second keystroke did. The obvious reading --
"the first completion after opening a file is lost while the session builds" --
is wrong, and it was written down before it was checked. Typing the same text
**one character at a time** pops the list at `this.set`, immediately. The
session is already warm by then: diagnostics run when the file opens and that
is what builds it.

`input text` commits a whole string as one IME edit, which is not a keystroke
and does not trigger the editor's completion the way typing does. **The
instrument was the finding.** Nothing about the product was wrong, and a
plausible mechanism written from one observation would have sent the next person
looking for a race that does not exist.

Two renderings worth noting from the same session, because they are evidence
the front end is doing real work: `setActionBar(Toolbar?)` carries the `?`, and
`setContentView(View!)` carries the `!`. Nullable, platform, and neither guessed
from the source text.

## 19. Extensions work: the API resolves by name, so we supply the names

`String` completes to `uppercase`. §16's wall came down, and not by finding a
better enumeration API -- there is not one -- but by noticing that enumeration
was never the requirement.

**Four routes, measured on a device, three closed.**

| route | asked for | result |
|---|---|---|
| `DefaultStarImportingScope.callables { true }` | everything visible | 76 symbols, **two names** |
| `findPackage("kotlin.text").packageScope` | everything in a package | `callables=0` |
| `KotlinDeclarationProvider.getTopLevelCallableNamesInPackage` | names in a package | `KaLibraryModuleImpl=0`, and `KaSourceModuleImpl=1[onDisk]` |
| **`findTopLevelCallables(FqName, Name)`** | **one named callable** | `kotlin.text.uppercase found=4 ext=4 applicableOnString=2` |

The third row is the one that names the rule: the *same provider*, asked the
*same question*, answers for a source module and not for a binary one. And the
fourth shows the library is perfectly reachable -- resolution out of the jar
works, and `createExtensionCandidateChecker` correctly finds two of the four
`uppercase` overloads applicable to a `String`.

**So the API can resolve anything it is named, and will list nothing.** The gap
was never about reach. It was a missing index, and an index over a jar we ship
is our own problem rather than the API's.

`findClass` is worth recording as the near-miss: `kotlin/text/StringsKt.class`
is plainly in the jar and `findClass` returns null for it. That is correct, not
broken -- a file facade is a JVM implementation detail, and in the Kotlin view
`uppercase` is a top-level callable of the package, a member of nothing.

**The index.** `build-name-index.py` reads the facade classes -- `<File>Kt`, and
the `Kt__`-suffixed parts a multifile facade splits into -- and takes their
public static method names: 869 names across 38 packages for kotlin-stdlib. It
parses the class files directly rather than shelling out to `javap` several
hundred times; the parse needed is the constant pool and the method table's
name indices. Built at build time and shipped in the component, for the reason
`jars.lock` exists: it is derived from a pinned artifact, so it should be
produced once and verified, not recomputed on every phone.

**Prefix first, and that ordering is the design.** §16 measured the other order
-- enumerate what is visible, then ask what applies -- at four times the latency
for an answer that did not change, because the cost tracked what the scopes
happened to yield rather than what was asked. Here the index is filtered by the
typed prefix before a single symbol is resolved:

| | warm completion |
|---|---|
| stdlib as a library, no index, enumerate-then-filter (§16) | 229 ms |
| stdlib as a library, **with the index**, prefix-first | **219 ms** |

**The index costs nothing measurable.** The 220 ms is the price of having the
standard library in the session at all, which §16 had already measured before
any of this existed -- not the price of extensions.

That number is over M3's 200 ms budget, and honestly so: Java completion sits
at 76 ms and Kotlin without libraries at 59 ms. The cost is library resolution,
it is the same with or without extensions, and nothing here has tried to reduce
it.

**Nothing until a character is typed.** Right after `.` the prefix is empty and
every top-level callable in every default-imported package would qualify --
hundreds of resolutions for a list nobody can read. Members answer that
keystroke; extensions arrive with the first letter. A deliberate trade, and
`CANDIDATE_LIMIT` caps the rest.

**A test premise that was wrong, and the shape of the mistake.** The negative
test -- "an extension that does not apply must not be offered" -- first used
`mapNotNull`, on the assumption that it is an `Iterable` extension. It is not:
`kotlin.text` defines `CharSequence.mapNotNull`, so it *does* apply to a
`String` and the checker was right to offer it. **`kotlin.text` shadows a great
deal of `kotlin.collections`**, and anything picked for that test has to be
checked against the index rather than against intuition. `getOrPut` is the one
used now -- a `MutableMap` extension with no `kotlin.text` counterpart.

**`@InlineOnly` functions are private JVM methods, and filtering on
`ACC_PUBLIC` silently drops them.** The index first came out at 869 names and
did not contain `println`. It is not missing from the jar: `kotlin.io.ConsoleKt`
declares it, as

```
private static final void println(java.lang.Object);
```

because `println` -- along with `print`, `let`, `also`, `require` and a great
deal of what a Kotlin user actually types -- is declared
`@kotlin.internal.InlineOnly`, which the compiler lowers to `ACC_PRIVATE` so
that nothing can call it from Java. It is not private in Kotlin at all.

The annotation survives into the class file as a `RuntimeInvisibleAnnotations`
attribute on the method, so the scan can recover them: accept a static method if
it is public, **or** private and annotated `Lkotlin/internal/InlineOnly;`. That
took the index from 869 names to **1275**.

This was found by writing a test for `println` and checking the index before
running it. It is also the sharpest argument for §20's alternative: reading
`@kotlin.Metadata` records the *Kotlin* declaration rather than its JVM
lowering, and would need no special case for this at all.

**Confirmed in the editor**, not only through `LanguageService`: typing `s.upp`
on a `String` in a running project offers `uppercase()` and
`uppercase(Locale)` -- exactly the two overloads the probe predicted as
applicable, out of four. Incidentally the buffer at that moment contained an
error on the line above (`val s: String = x`, `x` unresolved), and `s` still
resolved to `String`. Error-tolerant resolution is not something these tests
assert and it is what an editor lives on.

**What the index does not cover.** Only the jars the build indexes, which today
is kotlin-stdlib. A project's own AARs and any other Kotlin library contribute
no extensions, and `android.jar` needs none because Java has no top-level
functions. The index is also version-coupled to the stdlib the compiler
component ships -- both are pinned to the same Kotlin version, and an index
naming callables a different stdlib lacks would offer completions that vanish
when chosen.

## 20. Someone else solved this, differently — CodeOnTheGo

`appdevforall/CodeOnTheGo` is an actively maintained fork of AndroidIDE (the
file headers and `com.itsaky.androidide` coordinates are still there), GPLv3
like this repo, with commits through August 2026. It ships Kotlin intelligence
built on the same Analysis API, and reading it is worth more than any amount of
guessing about alternatives. Everything below was read from that tree, not
inferred.

**They do not relocate; they fork the compiler.** `:subprojects:kotlin-analysis-api`
is a single downloaded jar,
`analysis-api-standalone-embeddable-for-ide-2.3.255-SNAPSHOT.jar`, built from
`github.com/appdevforall/kotlin-android` and pinned by sha256. `2.3.255` is not
a Kotlin release: it is their own build. The word that matters is
**embeddable** — §4 of this file exists because JetBrains publishes the
Analysis API only in the `-for-ide` form, against the *unshaded* IntelliJ
namespace, while `kotlin-compiler-embeddable` shades it. They closed that gap
by producing the embeddable artifact JetBrains does not publish.

| | this repo | CodeOnTheGo |
|---|---|---|
| namespace mismatch | ASM relocation, 9204 references, four rewrites (§5) | fork the compiler, build an embeddable jar |
| shipping | dex archive, downloaded, own classloader | ordinary `api` dependency in the APK |
| calling it | reflection over a backend inside the archive (§17) | direct calls, compiled against it |
| ongoing cost | a relocator, and the carriers it must not miss | a Kotlin compiler fork, tracked against master |

Neither is obviously right. Ours needs no fork and no build of Kotlin, and the
relocation is the price; theirs needs no relocation and no classloader
gymnastics, and a compiler fork is the price. **What is worth knowing is that
the fork route exists**, because §4 reads as though relocation were the only
way, and it is not.

**Their symbol index reads `@kotlin.Metadata`, and that is strictly better than
§19's.** `lsp/jvm-symbol-index` has three scanners: `JarSymbolScanner` (ASM over
class files), `KotlinMetadataScanner` (the `@Metadata` annotation, decoded with
`kotlin.metadata.jvm.KotlinClassMetadata`), and `CombinedJarScanner`. The
comment on the first states §19's problem from the other side: *"For Kotlin
class files, use KotlinMetadataScanner ... ASM cannot see Kotlin-specific
semantics like extensions, suspend, or nullable types."*

That annotation carries the protobuf the compiler wrote, so it yields what a
facade's method table cannot: which callables are **extensions**, and their
receivers, plus suspend, inline, operator, infix, visibility and nullability.
And they run it **at runtime, per jar, streamed as a `Flow`** into a
SQLite-backed index, so it covers a project's own AARs.

§19's index is a build-time scan of the stdlib's facade classes for public
static *names*, with no semantics and no coverage of anything the build did not
see. That was enough to make extensions work, and it is the smaller idea. The
upgrade path is now known and does not need designing: read `@Metadata`, which
`kotlin-compiler-embeddable` can already decode, over the jars `:engine:deps`
unpacks. §21 lists that as the open item it is.

**Two of their architecture decisions are directly about traps this code can
still fall into**, and both are documented as ADRs in `docs/adr/`:

- **One live `KtFile` per open path** (ADR 0015). If a declaration provider can
  answer with a *different* `KtFile` instance than the analysis is holding, FIR
  sees every top-level declaration twice and reports the file as conflicting
  with itself — "Redeclaration" and "Conflicting overloads" underlining correct
  code end to end. They enforce one instance through the type system after a
  runtime mechanism was silently dropped by a later refactor. **This backend
  mints a fresh `KtFile` per query**, which is exactly the vulnerable shape; the
  dangling file's context module appears to save it, and
  `a_buffer_that_duplicates_a_file_on_disk_reports_no_redeclaration` pins that
  rather than trusting it.
- **Navigation resolves through the Analysis API, never the index** (ADR 0010).
  The index stores names and no source offsets, so a hit narrows to a file at
  best; and navigation must answer by *resolution* — which of seven overloads
  this call site binds to — where an index answers by name. The recipe is
  `reference.mainReference.resolveToSymbols()`, symbol to PSI declaration, then
  file plus name-identifier range, with `resolveToCall()` as the fallback for
  convention references (`a + b`, `a[i]`, `by lazy`, destructuring, `for`) that
  have no name reference at all. `KotlinLanguageService.definition` returns null
  today; that is how to fill it in, and the convention-reference fallback is the
  part that would not have been guessed.

## 21. What is still unknown

Honest limits of what has been established. None of this is evidence yet.

- **The name index covers kotlin-stdlib and nothing else.** §19. A project's
  AARs and any other Kotlin dependency contribute no extension proposals. §20
  shows the upgrade that closes it and removes the build-time coupling at the
  same time: decode `@kotlin.Metadata` rather than scanning facade method
  tables, at runtime, over the jars `:engine:deps` unpacks.
- **Kotlin completion is ~220 ms against a 200 ms budget** once the standard
  library is in the session, where Java is 76 ms. The cost is library
  resolution, not extensions (§19), and no attempt has been made to reduce it.
- **AARs and cross-module references are still untried.** `android.jar` works
  (§18) and so does kotlin-stdlib; a real dependency graph, with AARs unpacked
  by `:engine:deps`, is wired through `LanguageServices` but has never been
  exercised.
- **Session build time with `android.jar` is unmeasured.** §13's 1808 ms is a
  trivial module with no libraries. With the platform it is visibly several
  seconds. It is paid when the file opens -- diagnostics run then, and that is
  what builds the session -- so it lands before the first answer rather than
  before the first keystroke. §18 on why that distinction was nearly recorded
  backwards.
- **The component exists; the release asset does not.** §17. Everything on this
  side is done -- packaging, checksum, component, installer path, routing -- and
  the archive has not been uploaded to the release URL it pins. Until it is, no
  device installs this by itself: the instrumented tests stage it, and driving
  the app meant placing the four jars into `files/toolchains/` as root -- which
  needs `chown` to the app's uid **and** `chcon` to the parent's *full* label,
  because `restorecon` restores `app_data_file:s0` without the per-app category
  set. §7 is the older half of that trap.
- **Only one file, in one project, has been driven by hand.** §18 is what that
  found. Nothing has opened a second Kotlin file, switched between them, or
  changed projects while a session was warm -- and `LanguageServices` closes and
  rebuilds the service on both.
- **The 1808 ms build is on an emulator, with a trivial module.** It will grow
  with the project, and it sits on the path to first completion after opening
  one. Whether it can be moved off that path -- built ahead of time, or in the
  background -- is a design question nobody has asked yet.
- **The two platform classes §3 lists still have not been reached**, so whether
  they matter is still unknown. Sessions build, queries resolve, buffers
  complete, all without them.
- **`kotlinx-collections-immutable` was invisible until the relocation worked.**
  Before it, `jdeps` reported every reference as unresolved and this one was
  lost among 298; afterwards it stood out as the only non-optional gap besides
  the two platform classes. Anything else hiding the same way will only appear
  once the dex build runs.
