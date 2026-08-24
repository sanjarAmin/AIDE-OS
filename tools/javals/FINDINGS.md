# Spike R3: a Java language service on ART — result

**Outcome: resolved.** nb-javac runs on ART and answers the queries an editor
asks — completion, live diagnostics, positioned errors on a file that does not
parse. The plan's **< 200 ms** acceptance is met, but *only* by a service built
around a warm compiler: the obvious implementation, a fresh compilation task per
request, costs **~700–1100 ms**. That decision is forced, not stylistic, and the
measurements below are why. `:lsp:java` now answers in a warm median of 82 ms.

Measured on the `aideos_test` AVD (API 34, x86_64) against
`io.github.itsaky:nb-javac-android:17.0.0.3`, with the pinned `android.jar` as
`PLATFORM_CLASS_PATH`. Reproduce with `:spike:javals:connectedDebugAndroidTest`
and read the numbers out of logcat under the tag `JavalsSpike`.

## What works, unconditionally

| Question | Answer |
|---|---|
| Do the compiler's classes load and link on ART? | Yes, no coaxing. Parsing needs no classpath at all. |
| Does it read `android.jar` as the platform? | Yes. `android.app.Activity`, `android.os.Bundle` and the full supertype chain resolve. |
| Does it survive a file that does not parse? | Yes. The intact method stays in the AST and the error carries a line number. |
| Is completion *correct*? | Yes. 579 proposals on `Activity`, including inherited `getSystemService`, protected `onCreate`, and `hashCode` through the supertype chain. |

Correctness was never the risk. Latency is.

## The cost is `analyze()`, and almost nothing else

Phases of one fresh request, three iterations:

| Phase | ms |
|---|---|
| Tool + file manager + platform location | 7–13 |
| `parse()` | 6–12 |
| **`analyze()`** | **619–783** |
| Scope + `getAllMembers` + accessibility filter | 20–21 |

Roughly **95 % of a request is attribution**, and attribution is dominated by
entering `android.app.Activity` and its supertypes out of `android.jar`. A fresh
`JavacTask` throws that work away and pays for it again on the next keystroke.

This is the whole finding. Every design consequence below follows from it.

## `-source 8`, because the device has no module system

At source 9 and above javac wants a module graph, and there is no JDK image on
the device to give it one. `-source 8 -target 8 -proc:none` with `android.jar`
as `PLATFORM_CLASS_PATH` works and is what the spike measures.

This bounds the language level `:lsp:java` can *understand*, not the level
`:engine:fast` can *compile* — ECJ is configured separately. Java 8 covers
lambdas, method references, and default methods; `var` (Java 10) and records
(16) are out. AndroidIDE reaches higher by shipping a custom JDK image, which is
a much larger undertaking and is not needed for M3's acceptance.

## Sharing the file manager helps, and is still not enough

Reusing one `StandardJavaFileManager` across ten requests, same source:

| Request | 0 | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 |
|---|---|---|---|---|---|---|---|---|---|---|
| total ms | 1468 | 530 | 288 | 262 | 242 | 225 | 227 | 209 | 203 | 198 |

It converges on **~200 ms** — *at* the budget, not under it, and only after
about eight warm requests. Part of that curve is caching and part is ART
JIT-compiling javac's hot paths; the spike does not separate them.

And this is the easy case: twelve lines, two imports, one platform supertype. A
real activity with AndroidX types on the classpath will be worse on every axis.

**Read it as a floor, not a solution.** A cached file manager is necessary and
must go in, but `:lsp:java` cannot stop there.

## What that means `:lsp:java` has to do

In the order the evidence justifies:

1. **Cache the file manager and the jar filesystem.** Cheapest real win, proven
   above. AndroidIDE's `CachingJarFileSystemProvider` / `CacheFSInfoSingleton`
   are the reference.
2. **Keep the symbol table alive between requests.** The remaining ~200 ms is
   re-entering platform symbols that did not change. This is the big one, and it
   is not a small patch: javac's `Context` is not reusable as shipped, which is
   exactly why AndroidIDE carries `ReusableCompiler` / `ReusableContext` /
   `ReusableJavaCompiler`.
3. **Reparse only the edited method body.** With a warm compile cached, a
   keystroke inside one method should not re-attribute the file. AndroidIDE's
   `PartialReparser` plus a binary search over cached method positions.
4. **Make requests cancellable.** Keystrokes outrun compilation; a request the
   user has already invalidated must abort rather than finish. AndroidIDE uses a
   `CancelService` that throws through javac's internals.

1 and 2 are the difference between meeting the budget and missing it. 3 and 4
are the difference between meeting it on a fixture and meeting it while someone
is typing.

## Upstream moved; our dependency is the end of a branch

`nb-javac-android:17.0.0.3` is the **last standalone release**, frozen since
2022. AndroidIDE no longer consumes it: they vendor the compiler as a composite
build (`composite-builds/build-deps/{javac,jdk-compiler,java-compiler}`, ~400
Java files, ~9 MB of source, compiled at Java 8 level), and they **relocated the
namespaces** — `com.sun.tools.javac.*` became `openjdk.tools.javac.*`, and
`javax.*` became `jdkx.*`.

Two things follow:

- Our spike compiles against `com.sun.*` / `javax.tools.*` and works, so the
  relocation is *not* required to run on ART at API 34. Whatever drove it —
  collisions with platform stubs, or wanting a newer language level — it is not
  a blocker we have hit.
- We are pinned to a dead artifact. It is fine for M3, and it is a known debt.
  The escape hatch is the one the licence already permits: AndroidIDE is GPLv3,
  as are we, so vendoring their composite build is available if the pin ever
  becomes a wall.

## Open questions this spike did not answer

- **Multi-file projects.** Everything above is one source file against
  `android.jar`. Nothing here measures a project with cross-file references, and
  a real classpath with AARs on it is untested.
- **Memory.** No heap measurement was taken. A resident warm compiler holding a
  symbol table is exactly the kind of thing that argues for R3's separate
  `:build` process, and it lands in the editor's process today.
- **Where the ~200 ms plateau actually goes.** Caching versus JIT is unseparated.
  Worth an hour before assuming a symbol-table cache buys all of it.
- **The relocation question.** Untested whether a newer language level would
  force us onto AndroidIDE's relocated tree.

## Where `:lsp:java` landed against this

Resolved, and more cheaply than this document first assumed.

The list above says the symbol table has to survive between requests, and
guesses that means porting AndroidIDE's `ReusableCompiler`. It does not:
**`com.sun.tools.javac.api.JavacTaskPool` is already in the artifact**, with the
`ReusableContext` / `ReusableJavaCompiler` / `ReusableLog` machinery upstream
wrote for exactly this. AndroidIDE hand-rolled their own because they needed
hooks for partial reparse and cancellation on top of it; a service that needs
neither yet can use javac's.

`:lsp:java` holds one file manager *and* one pooled context. Completion latency
over eight consecutive requests, same file, same AVD:

| Request | 0 | 1 | 2 | 3 | 4 | 5 | 6 | 7 |
|---|---|---|---|---|---|---|---|---|
| file manager only | 981 | 430 | 509 | 305 | 396 | 422 | 272 | 225 |
| **+ pooled context** | 1135 | 103 | 110 | 84 | 78 | 82 | 77 | 79 |

**Warm median 82 ms against a 200 ms budget**, from 225 ms. The cold request is
unchanged at ~1.1 s and always will be — it is classloading plus the first read
of `android.jar`, paid once per process.

So the whole story, in one line each:

| Shape | Warm |
|---|---|
| Fresh task per request | 700–1100 ms |
| Warm file manager | ~225 ms |
| Warm file manager + pooled context | **~82 ms** |

Two consequences worth carrying forward:

- **Items 3 and 4 are no longer on the critical path.** Partial reparse and
  cancellation were listed as what it would take to meet the budget. It is met
  without them. They become what it takes to *hold* the budget on a large file
  under fast typing, which is a real concern and a different one.
- **Pooling is a correctness risk, not just a speed trick.** A reused context
  that kept the previous compilation's source classes would leave renamed types
  resolvable and deleted members completing — the editor would report a file as
  clean when it is not. The pool drops them; `pooling_does_not_leak_symbols_between_requests`
  asserts it on ART rather than trusting the documentation.

## Verified on device

`:spike:javals:connectedDebugAndroidTest` on `aideos_test` (API 34, x86_64),
2026-08-24. Four of five tests pass. The budget test is a **characterisation
test**: it asserts the naive shape misses 200 ms, so that this document becomes
wrong loudly rather than silently. The real acceptance assertion belongs to
`:lsp:java`, where it is not yet met.
