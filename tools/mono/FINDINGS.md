# C# on Android — spike R14

M10's second half. `:spike:mono` is the evidence.

## 1. The roadmap's wording does not survive contact with the platform

M10 is written as ".NET SDK (experimental)". **Termux publishes no `dotnet`**,
and Microsoft's builds are glibc, so they cannot start here at all — the same
wall `tools/node` hits with the builds from nodejs.org. What Termux does publish
is `mono` 6.14.1, built against Bionic, so `bin/mono-sgen` is an ordinary
Android ELF with `/system/bin/linker64` as its interpreter.

It is not 9 MB. The base package's `Installed-Size` suggests that; the closure
is fourteen packages and unpacks to **227 MB**, because it drags in the whole
class library, krb5, ncurses, readline and openssl. `fetch-mono.sh` trims it to
**117 MB**, which is §5.

Two things about the archive shape every invocation:

- **`bin/mono` is a symlink** to `mono-sgen`, so the linker is given the target.
  Symlinks survive only because the archive moves as a tar and is unpacked on
  the device; `adb push` of a tree drops all of them
  (`tools/clang/FINDINGS.md` §4).
- **`bin/mcs` is not a binary.** It is a shell script that hardcodes
  `/data/data/com.termux/files/usr` three times. The compiler is invoked as what
  it is — an assembly run by the runtime — exactly as npm has to be invoked as
  `npm-cli.js` rather than through `bin/npm`.

## 2. It works, and the fix is one substitution

```
mono --version -> exit=0 in  23 ms: Mono JIT compiler version 6.14.1
mcs  Hello.cs  -> exit=0 in 670 ms
mono Hello.exe -> exit=0 in 160 ms: hello from mono 42
```

M10's acceptance test for this half — a C# console app compiles and runs —
passes on the device. A deliberately broken source is rejected with
`error CS1519: Unexpected symbol` and not with a file error, so the compiler is
genuinely compiling.

**The one thing that has to be done is rewriting `$mono_libdir`.** Mono's
shipped `etc/mono/config` maps managed IO's P/Invoke to

```xml
<dllmap dll="System.Native" target="$mono_libdir/libmono-native.so" os="!windows" />
```

and mono expands that variable to the libdir fixed when Termux built the
package, `/data/data/com.termux/files/usr/lib` — a prefix this app neither has
nor can create. Copy the config, replace the variable with the real directory,
and point `MONO_CONFIG` at the result. Three entries use it; all three are
fixed at once.

`LD_LIBRARY_PATH` cannot do this job, because what is being resolved is a path
rather than a soname.

## 3. The hour it cost, and why

This was recorded as **blocked** first, and the record was wrong. Three things
conspired, and each is worth keeping.

**The diagnostic named the wrong thing.** Every file handed to `mcs` came back
as

```
error CS2001: Source file `.../Hello.cs' could not be found
```

for a file that existed, was 152 bytes and was readable — asserted immediately
before the call. Absolute, relative and the `/data/data` spelling behind
`/data/user/0` all failed identically, so it was not path resolution. What
settled it was handing `mcs.exe` to the compiler as its own source: **the file
the runtime had just loaded** came back "could not be found" too. `mcs`
translates every unreadable file into a missing-source diagnostic, so the
message points at the user's code for a cause that is a missing shared library.

**The compiler could not report its own failure; the REPL could.** `csharp.exe`
is an assembly like `mcs.exe`, so it runs the same way, and it printed the
exception instead of translating it:

```
System.TypeInitializationException: The type initializer for 'Sys' threw an exception.
 ---> System.DllNotFoundException: /data/data/com.termux/files/usr/lib/../lib/libmono-native.so
   at Interop+Sys.LChflagsCanSetHiddenFlag()
```

**And the first fix failed in a way that read as the opposite of the truth.** A
dllmap for the absolute path was *appended* to the shipped config, nothing
changed, and a malformed `MONO_CONFIG` drew no complaint from `mono --version` —
which together said "the variable is ignored". It is not. Running the same three
configurations through the REPL rather than `--version` showed the failing name
changing:

| `MONO_CONFIG` | fails on |
|---|---|
| absent | `System.Native` |
| malformed | `System.Native` |
| appended dllmap | `/data/data/com.termux/...` |

The appended entry was being read and losing to the shipped one above it, and
the changing name is what proved the file was parsed at all. `--version` returns
before the config matters, so it can say nothing about this — **the probe has to
be something that reaches the code under test.**

## 4. The spike has retired into a class

`MonoToolchain` in `:toolchain:native` holds the substitution, so no caller has
to know why it is needed: `configFor` copies the shipped config with
`$mono_libdir` rewritten to the real lib directory, and `plan` sets `MONO_PATH`
and `MONO_CONFIG` from the installation it was given. It also runs `mono-sgen`
rather than the `bin/mono` symlink, and the compiler as the assembly it is
rather than through the `bin/mcs` shell script.

`MonoToolchainOnDeviceTest` drives it through the real `NativeToolRunner`:

```
compile -> Success(exitCode=0)
run     -> Success(exitCode=0): hello 42
```

and asserts the generated config **directly** — that it no longer contains the
variable, and does contain the installed path — because that one substitution is
the whole difference between a working toolchain and a compiler that reports
every file as missing.

One thing the test got wrong first, worth keeping because it is about this
project's own contract rather than about mono: a broken source made
`NativeToolRunner` return `Success(ToolResult(exitCode=1))`, and the assertion
expected `AppResult.Failure`. **A non-zero exit is a successful run.** `Failure`
means the tool could not be started; a compiler that rejected its input did
exactly what it was asked to.

## 5. Trimmed to half, and the half that cannot go

227 MB unpacked is too much to ask for C# support. `lib/mono/*-api` is
**109 MB** of reference assemblies for *targeting* older frameworks — a dozen
profiles, none of them used to compile or run against 4.5 — and `include`,
`share` and `var` are another 8 MB of headers and documentation. Dropping those
gives **117 MB**, with `:toolchain:native`'s tests still compiling and running a
console app, and `:spike:mono`'s too.

**`lib/mono/gac` looks like more of the same and is not.** It is 56 MB, and the
obvious reading is that it duplicates the profile beside it. It does the
opposite: `lib/mono/4.5` is largely a farm of **symlinks into the GAC**, so
removing the GAC leaves the profile full of dangling links and mcs dies with

```
Could not load file or assembly 'System.Core, Version=4.0.0.0, ...'
```

which reads as a missing dependency rather than a deleted one. That was tried
first, and the error names the assembly rather than the directory that was
removed — so the trim list is short on purpose, and this is why.

The trim is in `fetch-mono.sh` rather than left to whoever builds the component,
because a size reduction nobody can reproduce is a number in a document.

## 6. What this does not answer

- **x86_64 on the emulator only.** clang, the JDK and Node all needed an arm64
  run before they were believed; so does this.
- **Nothing but `mcs` has been run.** `xbuild`, NuGet and anything that spawns
  are untried, and `bin/mcs` being a shell script hardcoding Termux's prefix
  suggests every other wrapper in `bin/` is too.
- **117 MB is still large** for a component users download, and the remaining
  bulk is the GAC and the 4.5 profile, which §5 establishes cannot simply be
  deleted. Whether a *narrower* set of assemblies — the ones a console app
  actually binds — can be selected has not been explored.
- **`bin/mono` is a symlink** to `mono-sgen`, so the linker is handed the target
  directly. Symlinks survive only because the archive moves as a tar and is
  unpacked on the device; `adb push` of a tree drops all of them
  (`tools/clang/FINDINGS.md` §4).
