# Spike R2b: ECJ, D8 and apksig on ART — result

**Outcome: resolved.** All three run on ART. A Java source containing a lambda
and a string concatenation compiles at source level 11, dexes with the lambda
desugared away, and signs into an archive carrying both v1 and v2 signatures.

Reproduce with `build-platform-stubs.sh`, then
`:spike:jvmtools:connectedDebugAndroidTest`.

## What was produced

| Property | Value |
|---|---|
| ECJ | **3.38.0** — pinned, see below |
| D8 / R8 | 9.3.19 |
| apksig | 9.3.1 |
| Java source level | 11 |
| Packaging | ordinary Gradle dependencies; AGP dexes them like any other library |
| Compile + dex + sign | ~4.1 s total on an x86_64 Android 14 emulator |

Unlike the Kotlin compiler, these needed no dex archive and no custom
classloader. They are dependencies, and the mechanism the spike exercises is
the one `:build:fast` will use.

## Three obstacles, none of them about compiling Java

### 1. One missing enum makes the compiler unloadable

`FileSystem` — the class holding ECJ's entire notion of a classpath — asks in
its static initializer whether it is running on JRE 12 or newer:

```java
try { isJRE12Plus = SourceVersion.valueOf("RELEASE_12") != null; }
catch (IllegalArgumentException e) { }
```

Android has no `javax.lang.model` at all, so the lookup throws
`NoClassDefFoundError`, which walks straight past a handler that only catches
`IllegalArgumentException`. The initializer dies, `FileSystem` becomes
unloadable, and there is no Java compiler on the device — reported as
`NoClassDefFoundError: FileSystem`, naming a class that is present and intact.

This is the same shape as the `Unsafe.copyMemory` failure in
`tools/kotlinc/FINDINGS.md`: a feature probe in a static initializer that cannot
survive its own failure. Expect more of them.

The fix is one file — `javax/lang/model/SourceVersion.java`, in the module's
own source set — whose constants deliberately stop at `RELEASE_11` so the probe
throws `IllegalArgumentException` and the flag comes out **false**. That is the
correct answer, not merely a safe one: the flag gates `getOlderSystemRelease`,
which reads `ct.sym` out of a JDK installation that does not exist here.

**This is a runtime shim, not a compile-time stub.** It is compiled and dexed
into the app, because ECJ itself loads it while running. Do not confuse it with
`platform-stubs.jar` below, which is a file handed to ECJ as classpath and is
never loaded by anything. The two solve opposite halves of the same absence.

ECJ 3.38.0 references 64 `javax.*` types android.jar lacks. All but this one
live in its `apt` and `tool` packages — annotation processing and the JSR-199
API — which the batch compiler never loads. Supporting annotation processing
on-device means porting effectively all of `javax.lang.model`, which is a
different undertaking from this file.

### 2. ECJ 3.38.0 is the ceiling, and it is a hard one

ECJ **3.40.0** added a source-level check built on `java.lang.Runtime.Version`:

```java
if (!options.requestedSourceVersion.isBlank()) {
    Runtime.Version requested = Runtime.Version.parse(...);   // NoClassDefFoundError
```

Android's `Runtime` has no nested `Version`. Unlike every other gap here, this
one **cannot be shimmed**: the platform forbids defining classes in `java.*`,
so there is nowhere to put a replacement. And the guard does not help — the
field is populated from `-source`, so any real invocation reaches it.

That leaves 3.38.0 as the newest usable release. If a later ECJ is ever needed,
the remaining option is rewriting the constant pool the way
`tools/kotlinc/build-kotlinc-dex.py` rewrites `java.lang.management`:
`java/lang/Runtime$Version` and `aideos/jdk/RuntimeVersion` are both 25
characters, so it is a byte-for-byte patch. That trades a version pin for a
build step, and should not be paid until something needs it.

### 3. android.jar is not a sufficient compile-time platform

This is the finding that matters most for M2, because it is not about exotic
code — it rules out **essentially all real Java**.

A lambda or method reference compiles to an `invokedynamic` bootstrapped by
`java.lang.invoke.LambdaMetafactory`. At source level 9 and above, so does
string concatenation, via `StringConcatFactory`. Neither class is in
android.jar, and ECJ refuses:

```
The type java.lang.invoke.LambdaMetafactory cannot be resolved.
It is indirectly referenced from required .class files
```

On a desktop build this never surfaces, because the `java.*` platform comes
from the JDK's own `java.base` and only `android.*` comes from android.jar. On
a device there is no `java.base`.

`platform-stubs.jar` supplies exactly those two classes, for the compiler to
resolve and nothing else — their bodies throw. **Nothing needs them at run
time**, because D8 desugars the `invokedynamic` into a synthesized class at dex
time; the test asserts that `LambdaMetafactory` does not survive into the dex
and that a `D8$$SyntheticClass` appears in its place. That assertion is what
makes the stub honest rather than a crash deferred to whenever the lambda is
first reached.

`verify-platform-stubs.sh` diffs the stubs' public surface against a real JDK's,
because the descriptors go straight into the constant pool of every class
containing a lambda, and D8 desugars by matching the bootstrap method — a
drifted signature would fail there rather than anywhere obvious.

Two option spellings also matter: `-bootclasspath` is rejected at compliance 9
and above, so the platform goes on `-classpath`; and `-proc:none` keeps ECJ away
from the annotation-processing classes it cannot load.

## apksig needs no bundled keystore

The signing key is generated in `AndroidKeyStore` and apksig accepts it, despite
the private key being opaque — `getEncoded()` returns null for it. So the app
does not have to ship a debug keystore the way the desktop tools do: the key is
per device, hardware-backed where the device allows it, and never exists as a
file.

`ApkVerifier` is *not* used in this spike. It reads `AndroidManifest.xml` to
decide which platform versions to check and refuses without one, and producing a
binary manifest needs aapt2. End-to-end verification belongs in `:build:fast`,
against a real APK; here the tests assert the v1 signature files and the v2 APK
Signing Block directly.

## Consequences for `:build:fast`

- **These three are dependencies, not artifacts.** No dex archive, no
  classloader work, no build step — the opposite of the Kotlin compiler.
- **`platform-stubs.jar` is mandatory and belongs on the compile classpath
  only.** Putting it on a runtime classpath would ship classes that throw.
- **android.jar has to be on the device.** It is 43 MB, is not in git, and is
  currently copied out of the SDK by hand. Getting it onto a real device is
  `:toolchain:manager`'s job and is not yet designed.
- **Nothing here needs API 30.** The floor in `tools/aapt2/FINDINGS.md` and
  `tools/kotlinc/FINDINGS.md` comes from aapt2 and the compiler archive; this
  half of the pipeline runs at `minSdk 26`.
- **Annotation processing is out of scope** until someone ports ECJ's `apt`
  package's worth of `javax.lang.model`. Anything relying on generated code —
  Room, Dagger, data binding — is a Gradle-path project for now.
