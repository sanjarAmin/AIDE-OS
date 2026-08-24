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
- **No Kotlin.** Spike R2 proved the compiler and the Compose plugin run on ART;
  neither is wired into this module. `FastBuildSystem` refuses a Kotlin project
  by name rather than building one silently missing every Kotlin class.
- **No dependencies.** No AAR, no Maven resolution, so no AndroidX. The project
  template uses only framework classes for exactly this reason.
