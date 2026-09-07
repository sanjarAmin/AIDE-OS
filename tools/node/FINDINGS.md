# Node.js on Android — spike R13

The first question M10 asks, settled before anything is designed around it.
`:spike:nodejs` is the evidence; this is what it established.

## 1. It works, and it is unremarkable

Node **starts from app-private storage and runs JavaScript**, on the emulator
at API 34 / x86_64:

```
node --version   -> exit=0 in 127 ms: v24.18.0
node -e          -> exit=0: 42
a script on disk -> exit=0: platform=android arch=x64
```

The script also wrote a file, so the module loader and the filesystem layer work
under this launch, not merely the engine. 127 ms to start is cheap enough that
nothing has to be kept warm — unlike the Analysis API session, which is the
whole design of `:lsp:kotlin`.

**Termux's build, not nodejs.org's.** The official builds are glibc and cannot
run here at all. Termux builds against Bionic, so `bin/node` is an ordinary
Android ELF whose interpreter is `/system/bin/linker64` — the one shape spike R9
established this app can start, and the same shape as the JDK and clang.
`tools/node/fetch-node.sh` assembles it from the package repo, walking the
dependency closure from two roots — `nodejs-lts` at 24.18.0 and `npm` — and
trims what running JavaScript cannot use: `include/` is 11 MB of C++ headers for
native addons, which would need a compiler and a linker this launch cannot spawn
anyway, and `share/` is man pages. **104 MB packed.**

The rest is not reducible. The runtime binary is 43 MB and `libicudata` is
32 MB, and dropping the latter is not a size decision but a decision to break
`Intl`.

**`LD_LIBRARY_PATH` is not optional.** The binary's `RUNPATH` is
`/data/data/com.termux/files/usr/lib`, a prefix this app neither has nor can
create, so without it every run dies with `library "libicuuc.so.78" not found` —
which reads as a missing file rather than a directory that was never searched.
The same cure as clang and the JDK.

## 2. It **can** spawn children, which clang cannot

This was the open question, and it is the one that matters for M10's shape. M7
was reshaped around clang being unable to spawn anything — a child would have to
`execve` out of app storage — so links are planned with `-###` and run by us. If
Node had the same limit, `npm` would be off the table, because npm is a script
Node runs.

It does not:

```
spawn via linker -> exit=0: child said 42
```

**The first reading of this said the opposite, and was wrong.** Spawning
`process.execPath` fails:

```
Command failed: /apex/com.android.runtime/bin/linker64 -e console.log(1)
error: expected absolute path: "-e"
```

which looks like a refusal and is not one. **`process.execPath` is the linker**,
not `bin/node`:

```
execPath -> /apex/com.android.runtime/bin/linker64
```

Under this launch `/proc/self/exe` is the linker, so Node reports the linker as
its own path and re-invokes it with Node's arguments. The linker ran — the spawn
was *permitted* — and then complained about `-e`, which is not a path. Give the
child the real binary through the linker and it works.

`LinkerLaunch` already names this as one of the two costs of the route, and the
JDK hits the identical wall: its launcher re-execs itself and dies with the same
`expected absolute path`. **Three toolchains have now been misled by
`/proc/self/exe` in the same way**, which is enough to call it the route's
defining property rather than a quirk of any one of them.

So M10 needs Node told where it really is. `execPath` is not writable, but
`--` argv rewriting, a shim script, or setting it before the runtime reads it are
all open; which of them is right is a design question for the milestone, not a
question about whether the platform allows it.

## 3. npm works, and getting it needed two fixes to the fetch

```
npm --version -> exit=0 in 362 ms: 11.19.1
npm install   -> exit=0 in 837 ms: added 1 package in 589ms
```

The second is npm doing real work: resolving a local `file:` dependency and
writing `node_modules`, offline. `bin/npm` is a shell script that execs `node`
off `PATH`, which cannot work here, so npm is invoked the way the app will have
to invoke it -- the runtime, through the linker, on `npm-cli.js`.

**`nodejs-lts` does not contain npm.** Termux ships the runtime and `corepack`
in one package and npm in another, so a closure walked from the runtime alone
gives a `bin/` holding exactly `node` and `corepack`. Two roots, then.

**And that is where the closure walker broke.** npm declares
`Depends: nodejs | nodejs-lts`; the walker takes the first alternative, so
asking for the LTS fetched `nodejs` 26.4.0 as well -- a package `nodejs-lts`
names in `Conflicts`, so the two can never be installed together -- and
whichever extracted last became `bin/node`. The archive said LTS and held
**v26.4.0**. Nothing noticed, because the test asserted only that a version came
back at all.

Three changes, and the third is the one that generalises:

1. an alternative resolves in favour of a package already asked for, so
   `nodejs | nodejs-lts` picks the root the caller named;
2. the closure is rejected outright if it contains two packages that declare
   each other in `Conflicts` -- **unversioned conflicts only**, since npm's
   `Conflicts: nodejs-lts (<= 24.13.0)` is a lower bound the repo already
   satisfies and treating it as absolute rejects a sound closure;
3. the test asserts the **line**, not merely that a version exists. A pin that
   nothing checks is a pin that drifts, which is the same lesson
   `toolchain/manager/FINDINGS.md` learned from a component that could not be
   installed at all.

`tools/rootfs/fetch-jvm.sh` shares the first-alternative walker. It has no `|`
clause that matters today, so it was left alone; whoever generalises these
scripts should take the fixed walker as the starting point.

## 4. The spike has retired into a class

`NodeToolchain` in `:toolchain:native` is where the two facts above now live, so
that nothing above it has to know them: it supplies `LD_LIBRARY_PATH` from the
installation it was given, exposes `runtime` as the thing a spawned child must
be handed, and runs npm as `npm-cli.js` rather than through the shell wrapper
that cannot work here. It also answers `hasNpm`, because an archive assembled
from `nodejs-lts` alone has none and the failure otherwise arrives later as
`Cannot find module`.

`NodeToolchainOnDeviceTest` drives it through the real `NativeToolRunner`, which
is the distinction worth having: the spike sets its own environment by hand, and
the whole point of the class is that callers do not.

```
node -e        -> Success: 42
script         -> Success: platform=android
child          -> Success: child said 42
npm --version  -> Success: 11.19.1
```

What was still missing before M10 could ship this was a `ToolchainComponent`,
and that needed `node.tar` **published** — the JDK and clang components point at
this project's own releases, per architecture. Both exist now
(`ToolchainComponent.node`, per ABI, pinned and checked by `PinnedReleaseTest`),
and `:engine:node` drives the result. Nothing here invents a pin;
`toolchain/manager/FINDINGS.md` records what happens when one does not match.

## 5. What this does not answer

- ~~**Only x86_64 so far**, and only on the emulator.~~ **Answered
  2026-09-07**: the aarch64 archive runs on real hardware. `:spike:nodejs` 8/8
  and `:engine:node` 5/5 on an NX809J (Android 16, arm64-v8a), staged from
  `node-aarch64/node.tar`. Nothing differed from the emulator — no ABI-specific
  finding on this page needed changing.
- ~~**npm is proven but not offered.**~~ The workspace has an "Install
  dependencies" action as of 2026-09-07: `NodeRunSystem.npm` runs `npm-cli.js`
  through the same linker plan a run uses, into the same panel, and the file
  tree is read again afterwards so the lock file appears. It is a separate
  action and not something ▶ does on a failed `require`, because an install
  reaches the network and writes hundreds of megabytes.

- **Nothing has been done about `execPath`.** The spike records the deception
  and does not correct it.
- ~~**The C# half of M10 is a separate question.**~~ Answered by spike R14 and
  `:engine:mono`: Termux publishes `mono` (~9 MB) and no `dotnet`, so ".NET SDK
  (experimental)" as the roadmap words it is not available by this route, and
  mono's `mcs` is what is. `tools/mono/FINDINGS.md`.
