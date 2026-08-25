# Spike R4: Maven resolution on ART — result

**Outcome: resolved, and nothing about it was free.** `androidx.appcompat:appcompat:1.7.0`
resolves transitively to 44 real files on a device — AARs included. Getting
there took four workarounds, three of which are structural rather than
cosmetic, and one of which rules out half the available versions of the library.

`docs/PLAN.md` lists `maven-resolver` among the "pure JVM, therefore fine on
ART" dependencies. It is pure JVM. It is not fine on ART.

Measured on the `aideos_test` AVD (API 34, x86_64). Reproduce with
`:spike:deps:connectedDebugAndroidTest` and read the numbers out of logcat under
the tag `DepsSpike`.

## What it costs

| | |
|---|---|
| Cold resolve of the appcompat graph | **92 s** — 46 nodes, 44 files, over emulator NAT |
| Warm re-resolve of the same graph | **635 ms** |
| Artifacts needing the AAR fallback | 1 of 46 |

The cold number is entirely first fetch: Maven asks for a checksum beside every
artifact, so 44 files is closer to 90 requests, each paying connection setup.
The warm number is the one that matters for the build loop, and 635 ms to
re-derive a whole AndroidX graph from the local repository is comfortable.

**A first-time dependency add will take a visible minute.** That is a progress
bar, not a defect, but `:engine:deps` should be built expecting it — resolution
belongs off the main thread with reportable progress, the same shape as
`:toolchain:manager`'s downloads.

## 1. Android's regex engine is ICU, and it rejects Maven's

The first thing that happens is a crash in a static initialiser:

```
java.lang.ExceptionInInitializerError
  at org.apache.maven.model.building.DefaultModelBuilderFactory.newModelValidator
Caused by: java.util.regex.PatternSyntaxException: Syntax error in regexp
  pattern near index 10:  \$\{(.+?)}
  at org.apache.maven.model.validation.DefaultModelValidator.<clinit>
```

`DefaultModelValidator` compiles `\$\{(.+?)}` and `\$\{(project.+?)}`. The
closing brace is unescaped. The JDK's engine reads a lone `}` as a literal;
**Android's is ICU, which does not** — it is stricter about what may appear
unescaped, and refuses the pattern outright.

Two consequences worth knowing:

- It throws from `<clinit>`, so the class is poisoned for the life of the
  process. Every later touch reports `NoClassDefFoundError` instead of the
  original `PatternSyntaxException`, which is why the second and third tests in
  this spike originally failed with a different and much less informative error
  than the first.
- It is present unchanged in **every** `maven-model-builder` from 3.8.8 to
  3.9.16, so there is no version to bump to. It is also the *only* occurrence
  across every Maven jar the resolver pulls in, which is what makes it worth
  working around rather than treating as the first of many.

**The way around it is small, because Maven left the seams open.**
`ModelValidator` is a two-method interface,
`DefaultModelBuilderFactory.newModelValidator()` is `protected`, and so is
`RepositorySystemSupplier.getModelBuilder()`. Supply any validator that is not
`DefaultModelValidator` and the class is never loaded.

A permissive validator is defensible *here and only here*: validation exists to
tell an author their own POM is wrong, and we are reading published artifacts
that the tooling which published them already validated. If `:engine:deps` ever
reads a POM a user wrote, this is the first thing to revisit.

## 2. Apache HttpClient cannot work on Android, and cannot be made to

```
java.lang.NoSuchFieldError: No static field INSTANCE of type
  Lorg/apache/http/conn/ssl/AllowAllHostnameVerifier;
  at org.apache.http.conn.ssl.SSLConnectionSocketFactory.<clinit>
```

Android ships its own ancient, stripped `org.apache.http` **on the boot
classpath**, which by definition wins over anything in the APK. Bundling
httpclient 4.5.14 does not help: the app's copy is never the one that loads, so
`SSLConnectionSocketFactory` links against the platform's
`AllowAllHostnameVerifier`, finds it has no `INSTANCE`, and dies.

The conventional escape is to shade `org.apache.http` into another package. The
cheaper one is to notice that `Transporter` is five methods over what is, for a
Maven repository, plain HTTP GET of static files. `HttpURLConnection` is on
every Android and answers the whole SPI in about sixty lines
(`UrlConnectionTransporter` in this spike).

**This also rules out maven-resolver 2.x.** Its transports are
`transport-apache` (the same problem) and `transport-jdk`, which is built on
`java.net.http.HttpClient` — a package Android does not have at any API level.
The 1.9 line is the one to use, and the reason is not conservatism.

One classpath oddity to keep: `maven-resolver-transport-http` stays as a
dependency because `RepositorySystemSupplier.getTransporterFactories` names
`ChecksumExtractor` from that artifact in its own signature. Its
`HttpTransporter` is never constructed.

## 3. AndroidX uses hard version ranges, which Maven cannot solve

```
UnsolvableVersionConflictException: Could not resolve version conflict among
  [androidx.appcompat:appcompat:aar:1.7.0
     -> androidx.activity:activity:aar:1.7.0
     -> androidx.lifecycle:lifecycle-...
```

`appcompat:1.7.0` declares a **hard range** on `appcompat-resources:[1.7.0]`.
When a soft version for the same artifact arrives by another path, Maven's
default *nearest-wins* selector has a hard requirement and a nearer soft one and
refuses to choose. Every real AndroidX graph hits this.

Gradle picks the **highest** version, and every Android project in existence is
written expecting that. So highest-wins is not merely a way around the
exception — nearest-wins would silently select *older* AndroidX artifacts than
the same project gets from Gradle, which is a worse outcome than failing loudly.

maven-resolver 1.9 ships only `NearestVersionSelector`, so the selector is
written rather than configured. It is about twenty lines against
`ConflictResolver.VersionSelector`.

## 4. Maven has no concept of an AAR

Maven's model knows `jar`, `war`, `pom` and friends. A dependency declared
without an explicit `<type>` defaults to `jar`. AndroidX POMs mark *some* of
their dependencies `aar` and leave others bare, because the real packaging
information lives in Gradle Module Metadata — which Maven cannot read.

So the collector asks for `androidx.lifecycle:lifecycle-runtime:jar:2.6.2`,
which has never existed, and the entire resolution fails on an artifact that is
sitting in the repository as an `.aar`.

Resolving in **two phases** is what gets files on disk:

1. `collectDependencies` to build the graph, downloading nothing.
2. Walk the nodes and resolve each artifact individually, falling back from
   `jar` to `aar` on failure.

In the appcompat graph exactly **one of 46** needed the fallback. One is enough
to fail the whole resolve, and a single-artifact failure is why the
all-or-nothing `resolveDependencies` call cannot be used as-is. `:engine:deps`
will need something of this shape whatever else it does.

## 5. The app has no INTERNET permission

Not a discovery about ART, but it will stop M4 on the first run:
`app/src/main/AndroidManifest.xml` declares no permissions at all, and the
failure surfaces as `SecurityException: Permission denied (missing INTERNET
permission?)` from deep inside a DNS lookup. `:toolchain:manager` downloads
today only because its own tests run in a test APK that has it.

## Open questions this spike did not answer

- **Two of the 46 collected nodes resolved to nothing**, and this spike does not
  say which or why. Likely pom-only or platform-BOM entries with no artifact,
  which would be correct behaviour — but it is an assumption, not a result.
- **Extracting `classes.jar` from an AAR** is untouched. Resolution produces
  `.aar` files; the compiler needs the jar inside them, plus `res/` for aapt2
  and `R.txt` for the R class.
- **No caching policy.** The local repository here is a temp directory with
  default settings. Snapshot handling, offline mode, and eviction are all
  unconsidered.
- **Nothing was measured on a phone.** 92 s cold over emulator NAT says little
  about mobile data.

## Verified on device

`:spike:deps:connectedDebugAndroidTest` on `aideos_test` (API 34, x86_64),
2026-08-25. Three of three tests pass: the repository system constructs, a POM
parses to 17 dependencies, and the graph resolves to 44 files with the AAR
fallback exercised.

## What `:engine:deps` does with all this

The module built from this spike keeps all four workarounds and adds the piece
the spike left out: unpacking. `androidx.appcompat:appcompat:1.7.0` resolves to
**44 dependencies, none unresolved**, each handed over as `classes.jar` plus
`res/`, `R.txt` and the manifest where the artifact is an AAR — so nothing
downstream has to know which kind it got.

| | |
|---|---|
| Cold resolve, `:engine:deps` | **122 s** — includes unpacking every AAR |
| Warm resolve of the same graph | **2.3 s** |

Warmer than the spike's 635 ms is expected and correct: the spike stopped at
files on disk, while this also unzips each AAR. Extraction is cached against the
archive's mtime, which is sound only because an artifact at a fixed version is
immutable in a Maven repository. **Snapshots would break that assumption**, and
are unsupported for exactly that reason.

Two things the spike did not turn up:

- **Android's `java.util.zip` refuses a `../` entry on read as well as write.**
  A forged AAR throws `ZipException: restricted zip entry name` out of
  `ZipFile`, before the extractor's own Zip Slip check can run. Welcome, but it
  is the platform's guarantee rather than this module's, so the check stays —
  and the extractor now treats a hostile or truncated archive as one bad
  dependency instead of letting the exception fail the whole resolution.
- **`runTest` defaults to a one-minute timeout**, which a cold AndroidX resolve
  does not fit inside. A suite run against an empty cache fails on the clock
  while saying nothing about the code.

