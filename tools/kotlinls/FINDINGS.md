# Spike R12 — Kotlin intelligence on ART

**Status: surveyed, not resolved.** No Kotlin completion has been produced on a
device. What follows is what was established about the routes, cheaply, before
committing a milestone to one of them — which is the point of doing it first.

The gap is real and it is the largest one left in the editor. `:lsp:java`
answers Java and `:lsp:native` answers C and C++, both through
`LanguageService`; `LanguageServices.serviceFor` returns **null** for a `.kt`
file. So an IDE whose flagship build path compiles Kotlin and Compose offers no
completion, no diagnostics-as-you-type and no go-to-definition in a Kotlin file.

---

## 1. The compiler we ship cannot answer these questions

`:engine:fast` drives Kotlin through
`org.jetbrains.kotlin.cli.jvm.K2JVMCompiler.exec(PrintStream, String[])` — the
**command-line entry point** — and recovers diagnostics by parsing what it
prints (`KotlincDiagnostics`). That is the right shape for a build and the wrong
one for an editor: it compiles whole modules, it answers in text, and it has no
notion of a cursor.

Nothing else is available from the archive as it stands. Counting classes in
`kotlin-compiler-embeddable` 2.2.10, which is what
`tools/kotlinc/build-kotlinc-dex.py` dexes:

| Package | Classes | What it would give us |
|---|---|---|
| `**/fir/**` | 4259 | the K2 front end — resolution, diagnostics |
| `KotlinCoreEnvironment` | 6 | the K1 environment and `BindingContext` |
| `**/analysis/api/**` | **0** | the supported API for completion — **absent** |

So the front end is present and the *interface to it* is not.

## 2. The Analysis API is not on Maven Central

The supported way to ask "what can be written at this offset" is the Kotlin
Analysis API. Its standalone artifact is `analysis-api-standalone-for-ide`, and
it is published to JetBrains' own repository
(`packages.jetbrains.team/maven/p/ij/intellij-dependencies`), not to Maven
Central — [KT-56203] has been open about that for some time. Checked directly:
`analysis-api`, `analysis-api-standalone`, `analysis-api-fir` and
`high-level-api-for-ide` all 404 on `repo1.maven.org`.

That is not a blocker on its own — this project already hosts a Kotlin compiler
nobody else ships — but it makes the route the same *kind* of work as M4 was:
fetch from a non-standard repository, pin, verify, dex, host, install.

[KT-56203]: https://youtrack.jetbrains.com/issue/KT-56203/AA-Publish-analysis-api-standalone-and-dependencies-to-Maven-Central

## 3. Three routes, and what each costs

**(a) Ship the Analysis API.** The supported route and the only one that gives
real completion. Costs a second hosted toolchain artifact on top of the 54 MB
compiler, from a repository `fetch-jars.sh` does not currently know about, and
it shadows IntelliJ platform code that would have to survive the same dexing
and classloader treatment the compiler needed — `tools/kotlinc/FINDINGS.md`
records seven startup fixes for that, and there is no reason to expect fewer.

**(b) Drive K1's `KotlinCoreEnvironment` and `BindingContext`.** Present in what
we already ship, so it costs no download. Against it: Kotlin 2.2 defaults to K2,
K1 is on its way out, and building completion on a front end being removed buys
a feature with an expiry date.

**(c) An out-of-process Kotlin LSP.** `:lsp:native` already speaks LSP over
stdio to clangd, so the client side exists and `LanguageService` is the seam.
The problem is the server: the Kotlin language servers that exist are JVM
programs, which means the same JVM the Gradle path uses — now proven to run on
device (M9) — plus their own dependency trees.

**Completion by recompiling is not a route.** `tools/kotlinc/FINDINGS.md`
measures ~11 s for a one-file compile of which nearly all is startup, which is
why the compiler is held warm. Even discounting startup entirely, a whole-module
compile per keystroke is nowhere near M3's 200 ms budget, which `:lsp:java`
meets at 76 ms. This is reasoning from the recorded number rather than a fresh
measurement, and it is the one claim here that should be measured before it is
relied on.

## 4. What this changes about the plan

`:lsp:kotlin` is in the plan's module list and unbuilt, and this is why. The
milestone that builds it should start from route (a), and should budget for a
*second* toolchain component and its distribution rather than for a module.

Route (c) is the cheaper experiment and worth trying first precisely because it
reuses two things that already work — the LSP client and the on-device JVM — and
would answer whether the latency is survivable before anything is hosted.
