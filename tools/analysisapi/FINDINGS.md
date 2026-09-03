# The Kotlin Analysis API, for code intelligence on device

Spike R12 (`tools/kotlinls/FINDINGS.md`) established the gap: the editor answers
Java and C/C++ and returns nothing for a `.kt` file, and the compiler archive we
already ship has the K2 front end but **zero** Analysis API classes. This is the
toolchain that closes it, and what it cost to establish that the route works.

**Status: inputs pinned and reproducible; nothing dexed or running yet.**
`fetch-jars.sh` rebuilds the set from a clean machine and verifies every jar.
What has *not* happened is the dex build, the relocation §4 requires, or a
single query answered on a device.

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
   present, so this is the cheap route and the one to try first.
2. **Ship the unrelocated compiler and a real intellij-core** beside it. Correct
   by construction, and it means a *second* Kotlin compiler in the APK.
3. **Un-relocate the compiler's copy.** Rewriting the 54 MB artifact everything
   else already depends on, to suit a 7.8 MB one. Recorded to be dismissed.

**This is not the same problem the C++ toolchain had.** Nothing here is about
architecture or libc: every one of these jars is portable JVM bytecode. What
does not line up is a *name*, chosen by whoever shaded the compiler.

## 5. What is still unknown

Honest limits of what has been established. None of this is evidence yet.

- **Nothing has been dexed.** `../kotlinc/FINDINGS.md` records seven startup
  fixes that the compiler needed before it ran on ART, and there is no reason to
  assume this needs zero. The relocation in §4 has to survive d8 as well.
- **Nothing has answered a query.** Feasibility of the *closure* is not
  feasibility of *completion*.
- **No latency number exists.** M3 holds Java completion to a 200 ms budget and
  meets it at 76 ms. Whether the Analysis API can be made to answer in that time
  on a phone is the question the milestone actually turns on, and it is
  unanswered. The 11 s figure in `../kotlinc/FINDINGS.md` is compiler *startup*
  and does not predict this either way.
- **The 2 missing platform classes may or may not matter.** Both sit on paths a
  simple completion request might never take. "Might never" is not a plan.
