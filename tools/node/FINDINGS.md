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
dependency closure: nine packages, 101 MB packed, `nodejs-lts` at 24.18.0.

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

## 3. What this does not answer

- **Only x86_64 so far**, and only on the emulator. clang and the JDK both
  needed an arm64 run before they were believed; so does this.
- **npm has not been run.** Spawning works and npm is a Node script, so there is
  no known obstacle, but "no known obstacle" is not evidence.
- **Nothing has been done about `execPath`.** The spike records the deception
  and does not correct it.
- **The C# half of M10 is a separate question.** Termux publishes `mono`
  (~9 MB) and no `dotnet`, so ".NET SDK (experimental)" as the roadmap words it
  is not available by this route; mono's `mcs` is what is. Nothing here has
  tried it.
