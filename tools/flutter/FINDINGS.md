# Flutter on Android — why there is no chip for it

There is no Flutter template, no `:engine:flutter` and no toolchain component,
and this file is here so the next person to ask does not have to re-derive the
answer. **Nothing below is a measurement.** It is a reading of what Flutter
requires against constraints this project has already established, and it says
what a spike would have to settle. That distinction matters: every other
FINDINGS in this repo records what was run, and this one records what was not.

## The short version

Flutter is not a language, and that is the whole problem. Adding Dart *the
language* to the editor is an afternoon; adding Flutter means `flutter build
apk` working on the phone, and that is a toolchain milestone with at least three
unsettled questions in it — Phase E of `docs/PLAN.md`, where it already sits.

Shipping a Flutter entry in the picker before then would put a starter in front
of the user that nothing on the device can build. `ProjectTemplate`'s contract
is that every template in `ALL` builds or runs today; a Flutter one would break
it, and would teach the user that the ▶ button is unreliable — a far more
expensive lesson than a shorter list. It is the same rule that keeps a `.csproj`
out of the C# template: **do not write a file that lies about how the project is
built.**

## What would have to be true

### 1. A Dart SDK that starts here

Every runtime this app runs — node, mono, CPython, the JDK, clang — works for
one reason: **Termux publishes a Bionic build of it**, so the binary is an
ordinary Android ELF whose interpreter is `/system/bin/linker64`, the one shape
spike R9 established this app can start. The glibc builds the vendors publish
cannot run here at all.

`termux-main` publishes no `dart`. What exists is in TUR, Termux's *user*
repository, which is a different trust and stability proposition from the main
repo every current component comes from — and pinning a component means pinning
a URL and a checksum, so where it is published is a decision and not a detail.

**This is the first thing a spike should settle**, and it is settled the same
way §1 of `tools/python/FINDINGS.md` was: assemble a closure, push it, and try
to start it in the app's own process. Nothing further is worth designing until
`dart --version` answers.

### 2. `gen_snapshot`, which is a cross-compiler

A release Flutter build AOT-compiles Dart to machine code for the target with
`gen_snapshot`. Flutter ships it as a **host** binary per platform, and the
hosts are linux-x64, macOS and Windows. A phone building for itself is a case
the SDK does not ship a binary for — the host and the target are the same
device, which is not a configuration the artifact naming has a slot for.

Debug builds sidestep this: they ship a Dart *kernel* and the JIT runtime, so no
`gen_snapshot` runs. So a plausible first milestone is debug-only, and that
should be stated in the UI rather than discovered.

### 3. `flutter build apk` is a Gradle build, and it spawns

The Flutter tool orchestrates: it runs the Dart front end, then Gradle, and
Gradle runs AGP. Both halves exist here — `:engine:gradle` drives a real Gradle
on the device's own JVM — so this is the *least* unknown of the three. But the
`flutter` tool is itself a Dart program that spawns child processes freely, and
**a spawned child has to `execve` out of app-private storage**, which is refused.

`tools/python/FINDINGS.md` §8 measured exactly this and found the shape of the
answer: a child works when it goes through `/system/bin/linker64` and is refused
when it does not. So the question is not "can it spawn" but "will the `flutter`
tool let us interpose on how it spawns" — and the honest expectation is that it
will not without patching, because nothing in its design anticipates a platform
where `execve` of your own files is forbidden.

Flutter also downloads its engine artifacts on first run, keyed by host
platform, which is the same host/target assumption as §2 in a different place.

## What was done instead

`SourceLanguage.PYTHON` and `:engine:python`, which needed none of the above:
Termux publishes CPython in the main repo, it runs a project with no
cross-compilation, and `tools/python/FINDINGS.md` records eight passing tests in
the app's own process. It is the third sibling of `:engine:node` and
`:engine:mono` and it fits the architecture that already existed.

That is the shape a language addition takes here when the platform allows it,
and the contrast is the useful part of this document: **Python was a module,
Flutter is a milestone.**

## If you are here to edit a Flutter project

The editor is not the blocker and never was. Nothing stops `:editor` gaining a
Dart grammar — `com.itsaky.androidide.treesitter` does not publish one, so it
would be built by `tools/treesitter/build-grammars.sh` the way JavaScript's is —
which would give syntax highlighting for a project synced from a desktop. That
is a genuinely smaller piece of work than anything above, it does not require a
single sentence on this page to be resolved, and it should not be confused with
supporting Flutter.
