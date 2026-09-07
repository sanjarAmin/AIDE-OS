# C# on Android — spike R14

M10's second half. `:spike:mono` is the evidence.

## 1. The roadmap's wording does not survive contact with the platform

M10 is written as ".NET SDK (experimental)". **Termux publishes no `dotnet`**,
and Microsoft's builds are glibc, so they cannot start here at all — the same
wall `tools/node` hits with the builds from nodejs.org. What Termux does publish
is `mono` 6.14.1, built against Bionic, so `bin/mono-sgen` is an ordinary
Android ELF with `/system/bin/linker64` as its interpreter.

It is not 9 MB. The base package's `Installed-Size` suggests that; the closure
is fourteen packages and **222 MB packed**, because it drags in the whole class
library, krb5, ncurses, readline and openssl.

Two things about the archive shape every invocation:

- **`bin/mono` is a symlink** to `mono-sgen`, so the linker is given the target.
  Symlinks survive only because the archive moves as a tar and is unpacked on
  the device; `adb push` of a tree drops all of them
  (`tools/clang/FINDINGS.md` §4).
- **`bin/mcs` is not a binary.** It is a shell script that hardcodes
  `/data/data/com.termux/files/usr` three times. The compiler is invoked as what
  it is — an assembly run by the runtime — exactly as npm has to be invoked as
  `npm-cli.js` rather than through `bin/npm`.

## 2. The runtime starts, the compiler runs, and nothing can read a file

```
mono --version -> exit=0 in 23 ms: Mono JIT compiler version 6.14.1
mcs  --version -> exit=0:          Mono C# compiler version 6.14.1.0
```

So the runtime, the JIT and the class library all work. What does not:

```
mcs Hello.cs -> error CS2001: Source file `.../Hello.cs' could not be found
```

for a file that exists, is 152 bytes and is readable — asserted in the test
immediately before the call. Absolute path, relative path and the `/data/data`
spelling behind `/data/user/0` all fail identically, so it is not path
resolution. **`mcs.exe` handed to the compiler as its own source fails the same
way**, and that is the file the runtime had just loaded from the same directory.

The real error is not a compiler diagnostic at all. `csharp.exe` is an assembly
like `mcs.exe`, so it runs the same way and reports the exception instead of
translating it:

```
System.TypeInitializationException: The type initializer for 'Sys' threw an exception.
 ---> System.DllNotFoundException:
      /data/data/com.termux/files/usr/lib/../lib/libmono-native.so
   at Interop+Sys.LChflagsCanSetHiddenFlag()
```

**`System.IO` P/Invokes a library by an absolute path baked in at build time.**
`Interop.Sys`'s static constructor binds
`/data/data/com.termux/files/usr/lib/../lib/libmono-native.so`, a prefix this app
neither has nor can create. The initializer throws, so *every* managed file
operation fails, and `mcs` reports each one as a missing source file. **The
diagnostic points at the user's file and the cause is a missing shared
library** — an hour went into paths before the REPL was asked.

`LD_LIBRARY_PATH` cannot help: what is being resolved is a path, not a soname.

## 3. What was tried, and what is left

`dllmap` is mono's own mechanism for this, the library *is* in the archive at
`lib/libmono-native.so`, and a generated config remapping both the absolute path
and the bare name was pointed at with `MONO_CONFIG`. It changed nothing.

A deliberately malformed `MONO_CONFIG` also drew no complaint — `mono --version`
started and printed normally. That is **suggestive that the variable is not
honoured here, not proof**: `--version` may return before the config is parsed.
Distinguishing the two is the first thing to try next, by running the REPL
rather than `--version` against the broken file.

So this half of M10 is **blocked, not closed**, and the candidates are:

1. establish whether `MONO_CONFIG` is read at all, then whether `dllmap` applies
   to an absolute-path `DllImport` (it may only match module *names*);
2. an assembly-level `<assembly>.dll.config` beside the assembly that declares
   the P/Invoke, which is the other place mono looks;
3. patching the string in the shipped assembly, which is ugly and durable;
4. building mono with a relocatable prefix, which is the clean answer and the
   most work.

Until one of them lands, `:spike:mono` asserts the blocked behaviour rather than
failing — the same way `:spike:rootfs` asserts that a musl binary cannot be
loaded. Invert those tests when the route opens.

**Nothing here is arm64-verified.** x86_64 on the emulator only.
