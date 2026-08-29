# `:engine:fast` — what building an APK on the device actually costs

Written while assembling the six-stage pipeline (aapt2 compile → aapt2 link →
ECJ → D8 → package → apksig) and getting the platform to install what it
produced. Everything here was learned by hitting it. The toolchain-level
findings live elsewhere and are not repeated: `tools/aapt2/FINDINGS.md` for the
native binary and W^X, `tools/ecj/FINDINGS.md` for the compiler's three
obstacles, `tools/kotlinc/FINDINGS.md` for the Kotlin front end.

---

## 1. `resources.arsc` must be stored uncompressed, and nothing tells you

From API 30 the platform maps the resource table straight out of the APK and
refuses to install a package whose table is deflated. Nothing upstream of
packaging notices: the archive is perfectly well-formed either way, every stage
reports success, and the failure arrives at install time as a bare
`INSTALL_PARSE_FAILED_*` code with no reference to compression.

The packaging stage therefore forces `STORED` for that one entry whatever aapt2
chose, and `FastBuildSystemTest.the_resource_table_is_stored_uncompressed` pins
it. Every other entry keeps aapt2's choice — it already knows which resource
extensions are not worth deflating.

## 2. apksig aligns; do not align before it

`ApkSigner.Builder.setAlignmentPreserved` defaults to **false**, which means
apksig realigns every uncompressed entry as it writes the signed copy. A
zipalign pass in the packaging stage would be undone, not respected. This is why
`PackageStage` is a plain copy-and-add and contains no alignment code at all —
the one thing it must get right is the compression *method*, because that
apksig does preserve.

## 3. apksig reports `isVerifiedUsingV1Scheme = false` on a v1-signed APK

`ApkVerifier` reports which schemes it *used*, not which are present. Handed an
APK whose manifest declares `minSdk 26`, it verifies v2/v3 and never looks at
v1, because no device that new would. A correctly JAR-signed APK therefore comes
back with `isVerifiedUsingV1Scheme = false`, and asserting on that flag fails
against a perfectly good archive.

The v1 signature still has to be written — a project declaring an older
`minSdk` gets no other signature a device that old understands. Assert its
presence by looking for `META-INF/*.SF` in the archive instead.

## 4. `minSdkVersion` has to be read from the manifest, not assumed

D8 desugars against a minimum API level. Guessing it wrong is silent in the
dangerous direction: **too high** and D8 leaves language features in the dex
that an old runtime cannot execute, so the APK builds clean, installs, and
crashes only on the devices the developer promised to support. Too low costs a
little dex size and nothing else.

`ProjectManifest` reads it with DOM — `javax.xml.parsers` exists on both ART and
the JVM, so the same code is unit-testable without Robolectric. A codename
(`TIRAMISU`, `S`) is treated as *unreadable* rather than mapped to a number: the
mapping table goes stale every release, and a stale guess rounds the wrong way.

## 5. `/data/user/0/<pkg>` and `/data/data/<pkg>` are the same directory

Android reaches app storage by two absolute paths; the first is a symlink to the
second. A project handed to a tool as one comes back reported as the other the
moment that tool canonicalises — ECJ does — and a plain string prefix match then
silently fails, leaving every diagnostic pointing at an absolute cache path
instead of a file the user can open.

`ProjectPaths.relativise` canonicalises both sides first. Note that **no JVM
unit test can catch this**: the aliasing only exists on a device. It was found
by the instrumented test and would have shipped otherwise.

## 6. Zip timestamps are wall-clock with no offset

Fixing entry times to a constant makes builds byte-reproducible, but the
constant has to be built in the *default* zone. Zip stores local time with no
offset, so an instant fixed in UTC lands on a different DOS date per device —
and in any zone behind UTC, on a date before 1980, which zip cannot encode and
the writer silently clamps.

## 7. Installing: three separate walls, none of them the APK

`REQUEST_INSTALL_PACKAGES` in the manifest is necessary and not sufficient. It
is a *special* permission — not requested at run time, but toggled by the user
in Settings — and an app installed from a file manager, F-Droid or a direct APK
starts without it. Since those are AIDE-OS's distribution channels (R5 in
`docs/PLAN.md`), the first install a user attempts will be refused. `ApkInstaller`
checks `canRequestPackageInstalls()` and returns an intent to the right Settings
page rather than reporting a failure.

In a test the toggle cannot be granted the usual way; `appops set <pkg>
REQUEST_INSTALL_PACKAGES allow` is how the platform's own tests do it.

**`pm install` cannot read the app's own storage.** Staging the APK to
`getExternalFilesDir()` and installing from there fails with an SELinux denial —
`/storage` is FUSE-backed and system_server is denied any read of a fuse file.
The tool says so and names the way round it: copy to `/data/local/tmp` first.
The app cannot write there and shell cannot read the app's private cache, so
the file takes two hops.

**Package visibility hides the result.** From Android 11 an app cannot see
packages it did not install, so `getPackageInfo` on the freshly installed
package throws `NameNotFoundException` in a test that installed it through
shell. The test APK declares `QUERY_ALL_PACKAGES`; the app needs no equivalent,
because installing through `PackageInstaller` grants the installer visibility of
what it installed.

One test-harness trap worth writing down: `UiAutomation.executeShellCommand`
passes the string to `Runtime.exec`, which tokenises on whitespace and does not
honour quotes — so `sh -c '... 2>&1'` arrives as a dozen separate arguments and
silently does nothing useful. It also returns stdout only, and `pm install`
reports every refusal on stderr, so a failure arrives as an empty string.
`executeShellCommandRwe` (API 31+) returns both.

## 8. What the stages cost

Measured on the `aideos_test` AVD (API 34, x86_64), template Java project,
second build in the process so compiler class loading is already paid:

| | |
|---|---|
| Two full builds plus fixture setup | **~4.8 s** |
| M2's budget for one | 10 s |

The budget is an assertion in `FastBuildSystemTest`, not an aspiration. The
number above is an emulator on a desktop and is not a phone; the manual device
matrix in `docs/PLAN.md` is what settles that.

## 9. Timestamp-based incrementality ships a corrupt APK

Reviewed and rejected 2026-08-24, because it is the obvious first design and
someone will propose it again. The shape that fails:

> Skip `compile`+`link` if `res/` has not changed since `linked.apk`; skip
> `javac`+`dex` if `src/` has not changed since the dex.

The two halves are not independent, and `FastBuildSystem` says so where it
orders them: **`R.java` is an output of linking and an input to compiling.**
Change only a resource -- add a string, add a layout -- and `src/` is untouched,
so that rule skips `javac` and `dex` and packages a dex whose `R` constants came
from the *previous* link. Resource IDs move on every link. The APK installs,
runs, and resolves the wrong resources, which is a far worse outcome than the
clean build it saved.

Anything mtime-based has three more problems on top:

- A directory's `lastModified` does not change when a nested file does, so the
  check needs a recursive walk to be even approximately right.
- A deleted `.java` leaves its `.class` in `classes/`, and the dexer takes it.
- Builds here run 2--3 s, inside the resolution of the timestamps being
  compared. Edit within the same second as a build and the tree reads
  up-to-date.

And the workspace lives in `cacheDir` (`ProjectBuilder`), which Android may
evict *partially* at any moment. An up-to-date check that trusts a half-evicted
workspace is exactly the corrupt-reuse case `BuildWorkspace.prepare()` is
written to prevent.

What would work is what `docs/PLAN.md` already asks for: per-stage stamps over a
content hash of that stage's real inputs -- file set and contents, tool
arguments, `minSdk`, `debuggable` -- with res -> `R.java` -> javac modelled as a
dependency edge so a resource change invalidates everything downstream of it,
and `prepare()` staying destructive whenever a stamp is missing or does not
match. That is a milestone's work, not a cleanup.

## 10. Two `javax.lang.model.SourceVersion`s in one APK, and the silence that follows

Android has no `javax.lang.model`, so this module used to carry a hand-written
twelve-line `SourceVersion` enum: ECJ's batch `FileSystem` reads
`SourceVersion.valueOf("RELEASE_12")` in a static initialiser, catching
`IllegalArgumentException`. A *missing class* throws `NoClassDefFoundError`
instead, which walks past that handler and makes the whole batch compiler
unloadable. The shim stopped at `RELEASE_11` deliberately, so the probe threw
the exception ECJ expects and left `isJRE12Plus` false.

That was correct for this module alone and became a bug the moment `:lsp:java`
existed. nb-javac ships the *real* `javax.lang.model`, `SourceVersion`
included — `RELEASE_0` through `RELEASE_17`, plus `isIdentifier`, `isName` and
`latest`. With both modules in one APK, **both classes are dexed**: D8 does not
report a duplicate, because each library is dexed separately and the copies land
in different `classes*.dex`. ART then resolves the name to whichever dex comes
first, which was the shim.

The symptom is worth recording, because nothing about it points at a classpath:

- Completion returned nothing. Diagnostics returned nothing.
- No crash, no log, no exception out of the language service.
- `parse()` worked perfectly — `typeDecls=1`.
- `analyze()` returned zero elements, and `Elements.getTypeElement` then reported
  *"Cannot use Elements.getTypeElement before the TaskEvent.Kind.ENTER finished
  event"* — because ENTER had aborted on a `NoSuchMethodError` for
  `SourceVersion.isIdentifier(CharSequence)`, swallowed inside javac.

A language service that silently answers "no errors" is indistinguishable from a
clean file, which is how this survived a green unit-test suite: `:lsp:java`'s own
instrumented tests pass, because its test APK has no ECJ in it and therefore only
one `SourceVersion`. **Only the app that contains both is broken**, and only an
end-to-end test through the app catches it — `WorkspaceViewModelTest` now has one.

The fix is to have exactly one definition. The shim is deleted and this module
depends on `nb-javac-android` for the real one, which is already in the app for
`:lsp:java`, so nothing grows. Two things make that safe, and both were checked
rather than assumed:

- `isJRE12Plus` becomes **true** with the real enum. It gates only
  `getOlderSystemRelease`, which reads `ct.sym` out of a JDK image that does not
  exist on a device — and that is reached only for `--release`, which
  `JavaCompileStage` does not pass. It uses `-source`/`-target`.
- `:engine:fast`'s instrumented suite still builds and signs a working APK.

Worth noting for the future: this is *why* AndroidIDE relocated
`javax.*` to `jdkx.*` and `com.sun.tools.javac.*` to `openjdk.tools.javac.*` in
their vendored compiler. `tools/javals/FINDINGS.md` recorded that the relocation
was not needed on ART — true of the compiler in isolation, and wrong for an
application that also carries ECJ. A third javax-providing dependency would put
that question back on the table.

## 11. Things known missing

- **The bundled tests stage `android.jar` by hand.** They read a 27 MB copy out
  of `androidTest/assets`, which is not in git and only exists on a machine that
  put it there. `:toolchain:manager` now delivers the real thing, and
  `DownloadedPlatformBuildTest` builds a project with it -- but that test is
  opt-in, so the everyday suite still depends on the staged copy. Keep it in
  step with the pin in `ToolchainComponent.ANDROID_PLATFORM`.
- **No incrementality.** `BuildWorkspace.prepare()` deletes the tree every
  build, deliberately: reusing a workspace from a cancelled build is how you get
  an APK containing the previous run's classes. Incrementality belongs at the
  stage level keyed on input hashes, and is not written. Section 9 records the
  cheaper design that does not work.
- **No `.module` parsing.** Maven resolution reads `.pom` only, and AndroidX's
  graph is correct only under Gradle Module Metadata. Section 12 records the
  three mechanisms that costs us and the narrow rules standing in for them; two
  of those rules are curated tables that will rot.
- **Resources are linked, not merged.** aapt2's `-R` overlay is doing the job
  AGP gives to a resource merger. It is the right semantics for last-one-wins,
  but it is not a merger: nothing reconciles two libraries' `values.xml`, and
  nothing reports when one silently shadows the other.
- **The manifest merger implements the common rules, not the specification.**
  Section 13. `tools:replace`, node selectors and priorities are absent, and a
  library relying on one gets an ordinary merge instead.

## 12. Making a Compose app *run*, not build

Getting a Compose hello-world to run took six fixes across `:engine:deps` and
this module. Every one was invisible to a build-only test: the build reported
success, the APK installed cleanly, and the app crashed on launch or drew
nothing. `ComposeRunTest` exists because of that, and found all six.

Five of the six were resolution, and they are written up where the code is:
**`engine/deps/FINDINGS.md`**. The one-line version is that AndroidX's graph is
correct only under Gradle Module Metadata, and the difference costs duplicate
classes at D8 and missing ones at runtime. That module now reads the `.module`
file rather than approximating it, which is what retired the curated tables the
first version of this section described. Everything below is this module's half.

### Library resources are `-R` overlays, not positional inputs

A positional input joins aapt2's *base* set, where two archives defining one
resource name is a hard error — and matched-version AndroidX libraries do
exactly that: `compose.ui:ui-android` and `compose.foundation:foundation-android`
both ship `string/autofill`. Verified by downloading both AARs, not inferred
from the message. Under `-R` the last one wins, which is the documented
behaviour and the one AGP's resource merger provides.

The first input has to stay positional: `-R` overlays *onto* something, and with
every input an overlay there is no base to overlay onto. Order is the whole
rule — libraries first, the project last — so reversing it lets a library's
`app_name` silently replace the user's.

### `--extra-packages`, or the app dies on launch

aapt2 generates `R` for the manifest's package and nothing else, but a library's
compiled code references *its own*. So `androidx.customview.poolingcontainer.R`
is simply not in the APK, the build succeeds, the APK installs, and the app dies
at `onAttachedToWindow`:

```
java.lang.NoClassDefFoundError: Failed resolution of:
    Landroidx/customview/poolingcontainer/R$id;
```

The flag accumulates, so it is repeated rather than joined. The project's own
package is excluded, or aapt2 emits a second identical `R.java` and the Java
compiler rejects the duplicate. The package names come from each AAR's manifest;
`engine/deps/FINDINGS.md` section 5 records how that parse failed silently.

### The manifest merger, and the shape of the bug it fixed

`-R` is doing the job AGP gives to a resource merger. For a while there was no
equivalent for manifests at all: a library's `<uses-permission>`, `<provider>`
or `<activity>` never reached the app's manifest. `AarExtractor` had unpacked
every library `AndroidManifest.xml` since M4, and nothing read them.

**The failure mode is why this belongs here rather than on a to-do list.**
`androidx.startup` ships a `<provider>` whose only job is to run other
libraries' initialisers, and Compose pulls it in through `emoji2`. Without the
merge the build succeeds, the APK verifies, it installs, it launches, it draws
— and the initialisers never run. No crash, no diagnostic. Text renders
slightly wrong, forever.

`ManifestMerger` closes it; section 13 has the rules. The test that proves it
is in `ComposeRunTest`, and it asks **Android's own `PackageManager`** whether
`com.example.composerun.androidx-startup` resolves after install. Nothing
weaker would do, because the app looks identical either way.

### Instrumented tests need their own `largeHeap`

`androidTest/AndroidManifest.xml` here sets `android:largeHeap="true"`. `:app`
already had it; this module's test manifest did not, and resolving a Compose
graph hit ART's 192 MB default and reported only `Process crashed`. **A
library's instrumented tests run in their own process with their own manifest**,
and a `largeHeap` on the app under test does not reach them.

Also: XML comments cannot contain `--`, and the manifest merger fails the build
with `The string "--" is not permitted within comments` rather than pointing at
the line.

### What the test has to assert

"Builds" and "runs" are different milestones, and the gap between them is where
all six of these lived. `ComposeRunTest` asserts the app *drew* — UiAutomator
waiting on a marker string in another process's accessibility tree — because
every weaker assertion passed while the app was broken:

- the build reported success
- the APK was well-formed and verified
- `pm install` succeeded
- `am start -W` reported `Status: ok`

Only the last of those is even suspicious in hindsight, and only if you know
that `am start` reports the *launch*, not the process surviving it.

## 13. Merging manifests: what the rules are, and what they are not

`ManifestMerger` is not AGP's. AGP implements a specification with node markers,
selectors and priorities; this implements the part every ordinary Android build
depends on, and says so rather than implying more:

- `<uses-permission>` and `<uses-feature>` unioned by `android:name`;
- the `<application>` element's children added when the project does not already
  declare one with the same `android:name`;
- `${applicationId}` substituted.

**The project always wins.** It is the one merge rule a user can reason about
without reading a specification.

### `tools:node="merge"` is not a marker to skip

The first version dropped every element carrying any `tools:` attribute, on the
grounds that this implements none of them and aapt2 refuses an unbound prefix.
That threw away the exact component the merger exists to bring in:

```xml
<provider
    android:name="androidx.startup.InitializationProvider"
    android:authorities="${applicationId}.androidx-startup"
    tools:node="merge" />
```

`merge` is the *default*, spelled out. Only `remove` and `removeAll` mean "do
not include this", and they are the only two honoured. The rest are stripped
from the output rather than passed through, so a marker this does not implement
cannot look as though it had been.

### Keying by `android:name` is what keeps the unioned set safe

An element with no `android:name` is never merged, because there is nothing to
compare it against. That — not its absence from the unioned set, which is the
reason a reader would give — is what actually protects `<uses-sdk>`.

Found by mutation: adding `uses-sdk` to the unioned set fails no test, because
it has no name either way. The property doing the work now has a test of its
own, and reversing it fails.

`<uses-sdk>` genuinely must not be unioned: `androidx.startup` declares
`minSdkVersion="14"`, and an app that took a library's floor would silently
claim to run on devices it cannot.

### A bad library manifest costs that library, not the build

These files come out of archives fetched over the network from a coordinate the
user typed. A malformed one is skipped and the merge continues; a failure of the
merge as a whole falls back to linking the project's own manifest, which is what
this project did until now and still produces a working APK.

### The test helper was the bug, and one test hid it

Worth recording because it will happen again. Kotlin's `trimIndent()` runs
*after* interpolation, so building a fixture as one raw string with a multi-line
`$body` drops the common indent to zero and leaves the XML declaration indented
— which is not a well-formed document. Every library manifest failed to parse,
the merger skipped them exactly as designed, and four tests failed pointing
squarely at the code under test.

The fifth passed. `a_library_uses_sdk_is_left_alone` asserts that a library's
`minSdkVersion` does *not* appear, and nothing being merged satisfies that
perfectly. **A test whose assertion is an absence passes when the fixture is
broken**, which is the most expensive kind of green.

## 14. An input wired only into a test is not wired

`DependencyInputs` gained `libraryPackages` in M6 and `libraryManifests` in M8.
Both were passed by `:engine:fast`'s own instrumented tests, which is how each
was proved to work. Neither was passed by `ProjectDependencies.inputsFor`, which
is the only path a project built **through the app** takes.

So for the whole of M6 the engine was correct and the app was not. A user
opening an AndroidX project and tapping Build got a successful build, a valid
APK, a clean install, and a crash on launch — `NoClassDefFoundError` on a
library's own `R` class, the exact failure `--extra-packages` was added to
prevent. The test that proved the fix could not see the app, and the app had no
test that looked at what it handed the engine.

**A data class with defaulted fields compiles either way.** That is what makes
this shape of bug survivable: adding a field breaks no call site, so the one
that matters silently keeps the default. Every existing test still passed.

`ProjectDependenciesTest` now asserts the shape of the object the app hands the
engine, rather than trusting a call site. It is a seam test and it is worth its
run time: two of the four fields were missing when it was written, and both
failures happen *after* a successful build.

The rule this leaves behind: **when a stage gains an input, the test that proves
the stage is not enough.** Something has to assert that the caller supplies it,
and the caller is usually in another module.


## 15. The native stage, and the two shapes it cannot take

M7 added C and C++ to the pipeline. The compiler mechanics are in
`tools/clang/FINDINGS.md`; what belongs here is what the *engine* had to do
differently because of them.

**One clang invocation per source, then a separate link.** Not a batching
choice that could be revisited for speed. Two jobs in one invocation make the
driver spawn `cc1` through `/proc/self/exe`, which under the linker launch is
the linker, and it dies on `expected absolute path: "-cc1"`. The link is worse:
the driver always `execve`s `ld.lld`, out of app storage, which nothing may
execute — so `ClangToolchain` asks it to *plan* the link with `-###` and runs
the linker itself. The stage's job is only to hand it one job at a time.

**A C++ project must be linked by the C++ driver**, even though its objects are
already compiled. `clang` and `clang++` are the same binary and the name decides
what gets linked in; linking C++ objects with the C driver omits libc++ and
fails on every symbol the standard library owns.

**`libc++_shared.so` goes into the APK.** It is part of the toolchain, not part
of Android, and the driver plans `-lc++_shared` into every C++ link. Nothing on
the device resolves it, so an APK without it installs cleanly and dies at
`System.loadLibrary` — which is the NDK Gradle plugin's reason for doing the
same copy.

### Native libraries are packaged compressed

`lib/<abi>/*.so` entries are `DEFLATED`, so the platform extracts them at
install. The alternative — storing them uncompressed and mapping them in place,
which is what modern AGP does — requires every such entry to be page-aligned,
and `apksig` aligns uncompressed entries to 4 bytes, not to a page. A library
stored and misaligned produces an APK that installs and then fails to load, and
only on devices whose page size is larger than the alignment it got. Compressed
costs install-time disk and nothing else.

### One ABI: the device's own

Built for `Build.SUPPORTED_ABIS.first()` and no other. An on-device IDE's output
is nearly always installed on the device that built it, and each additional ABI
means another 551 MB toolchain to hold a compiler that produces libraries this
device cannot run. Worth revisiting only when this engine is asked to produce an
APK for somewhere else.

### The refusal has to come first

A project with `src/main/cpp` on a device with no toolchain is refused before
any stage starts, naming the toolchain. Letting it build would produce an APK
with no library in it — no error anywhere in the build — that installs and dies
at `System.loadLibrary` on the user's device, with nothing pointing back at a
missing download. This is section 14's lesson again: the failure that costs is
the one that produces a plausible artefact.

### clang's diagnostics are not aapt2's

Close enough to look reusable and not reusable. clang prefixes its own failures
with the driver's name, so `clang-21: error: unable to execute command` parses
under aapt2's rules as a file named `clang-21` — putting a tappable link to a
nonexistent file in front of the user, for the one error class they can do
nothing about. `ClangDiagnostics` checks for a tool prefix first, and keeps
`note:` lines, which for a template error are usually the useful half.
