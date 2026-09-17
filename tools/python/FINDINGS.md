# Python on Android — spike R17

What `:engine:python` rests on, settled before anything was designed around it.
`PythonRunSystemTest` is the evidence that survives; this is what it established.

## 1. It works, and it is the least troublesome runtime here

CPython **starts from app-private storage and runs a project**, in the app's own
process, on the emulator at API 34 / x86_64. Eight tests, no skips:

```
a script, two prints          -> exit=0, both lines on stdout in order
import json, sqlite3, ssl     -> exit=0
a raise                       -> exit=1, traceback on stderr
argv and cwd                  -> arguments arrive, a relative write lands in the project
cancel after 2 s of sleep(60) -> process gone
a missing entry point         -> RunResult.Failed naming the path, not an exception
pip --version                 -> exit=0: pip 26.2.1
subprocess, two routes        -> direct refused, through the linker exit=0 (§8)
```

**Termux's build, not python.org's** — python.org publishes no Android build at
all, and every manylinux wheel the ecosystem is made of is glibc. Termux builds
against Bionic, so `bin/python3.14` is an ordinary Android ELF whose interpreter
is `/system/bin/linker64`, the one shape spike R9 established this app can start.
`fetch-python.sh` assembles it from the package repo, walking the closure from
two roots — `python` 3.14.6 and `python-pip` — into eighteen packages.

**13 MB packed, 40 MB installed.** The smallest of the three runtimes by a wide
margin: node is 38/110 and mono is 45/120. §5 is what the trim removes.

## 2. It is **not** misled by the linker, which node is

This is the finding that shaped `PythonToolchain`, and it is the opposite of
what the other runtimes taught us to expect.

Under this launch `/proc/self/exe` is the linker, so anything locating itself
that way is wrong about where it is. Node does exactly that, believes
`process.execPath` is `/system/bin/linker64`, and `NodeToolchain` carries a
paragraph about it. **CPython derives `sys.prefix` from `argv[0]`**, which the
linker sets to the real binary — so:

```
sys.prefix      /data/user/0/<pkg>/files/python
sys.executable  /data/user/0/<pkg>/files/python/bin/python3.14
sys.path[-1]    .../lib/python3.14/site-packages
```

all correct with nothing done about them. **No `PYTHONHOME` is set, and setting
one would be worse than useless**: it would hardcode a path that changes when the
component is reinstalled, to fix a problem that does not exist.

The assertion in the test is an *import*, not a string comparison on
`sys.prefix`. A prefix that looked right while `encodings` failed to load would
pass the comparison and leave every project broken.

## 3. `LD_LIBRARY_PATH` is not optional

The binary's `RUNPATH` is `/data/data/com.termux/files/usr/lib`, a prefix this
app neither has nor can create. Without the variable the launch dies before a
line of Python runs:

```
CANNOT LINK EXECUTABLE ".../bin/python3.14":
library "libandroid-support.so" not found: needed by main executable
```

which reads as a missing file rather than a directory that was never searched.
The same cure as clang, the JDK, node and mono.

`ssl` is the module the test imports to check this rather than `json`, because
it is the one that loads its own shared objects out of the archive: a pure
Python module would import fine from a tree whose `lib/` was never searched.

## 4. `pip install --target`, not a virtualenv

`python -m venv` is the obvious way to give a project its own packages and it is
not the way here, for two independent reasons:

- **Creating one spawns the new interpreter.** venv bootstraps pip by running
  `<venv>/bin/python -m ensurepip`, which in this app's process is an `execve`
  out of app-private storage and is refused. Spike R9's rule, reached from a
  direction nothing else had come from.
- **The archive cannot do it anyway.** `ensurepip`'s bundled wheel lives in
  Termux's separate `python-ensurepip-wheels` package, which this closure does
  not carry, so creation fails even where exec is allowed:
  `FileNotFoundError: .../ensurepip/_bundled/pip-26.1.2-py3-none-any.whl`.

Worth recording precisely, because the *interpreter* part of a venv does work:
given an absolute path, `linker64 <venv>/bin/python3` reports
`sys.prefix=<venv>` and `sys.base_prefix=<install>` correctly. It is the
creation that cannot happen, not the result that is unusable.

So a project's dependencies go in `.aide-packages` via `pip install --target`,
and a run puts that on `PYTHONPATH`. Same isolation, no second interpreter,
nothing to execute — and it is the same shape as `node_modules`, which is what
the file tree and `.gitignore` already understand.

**`--target` goes on `install` and nowhere else.** It is an option of that
subcommand, not of pip, so appending it to every invocation makes
`pip --version` exit 2 with `no such option: --target` — which reads as a broken
install rather than as one line in `PythonRunSystem`. This was shipped that way
for about twenty minutes and `PythonRunSystemTest` caught it.

`bin/pip` is not used either: it is a `#!` script whose shebang names Termux's
prefix, so the kernel refuses it with `ENOENT` naming the interpreter and not the
script. `-m pip` is the same pip and needs no such path — the same shape as npm
having to be run as `npm-cli.js`, arrived at for a different reason.

## 5. What the trim removes, and what it must not

118 MB of closure becomes 40 MB installed:

| Removed | Why |
|---|---|
| `lib/python3.14/test` | CPython's own suite. 25 MB, the single largest item. |
| `include/` | C headers for extension modules — see below. |
| `idlelib` | The Tk IDE; Tk is a package this closure does not carry, so it cannot start. |
| `share/` | Man pages and terminfo for an interactive shell this app does not present. |
| `__pycache__` | **Not portable across the install path.** Shipping them ships a wrong cache; they are rebuilt on first import. |
| `*.a`, `config-*` | For linking against libpython, which is the same dead end as `include/`. |

**A wheel with a C extension cannot be built on device**, and dropping
`include/` is not what makes that true. Building one needs a compiler *and the
ability to spawn a linker*, and clang here can do neither — one job per
invocation, and links planned with `-###` and executed by us
(`tools/clang/FINDINGS.md`). So pure-Python wheels install and anything needing
a build does not, which is why `PythonDependencyDemo` pins `idna` and says so in
its `requirements.txt`.

The rest is not reducible. `libpython3.14.so` is 12 MB and the standard library
is 20 MB, and a Python without its standard library is not Python.

## 6. The archive is reproducible, and no other component's is

`fetch-python.sh` sorts the tar by name, zeroes uid, gid and every mtime, and
gzips with `-n` so neither the original filename nor a timestamp is embedded.
Two runs on the same day produce **identical bytes**, which was checked rather
than assumed:

```
run1=3d87abc1ac4f689b84630498afbeb5abb7c7ae33
run2=3d87abc1ac4f689b84630498afbeb5abb7c7ae33
```

This matters because `ToolchainComponent` pins the published archive by SHA-1.
Without determinism that checksum is a number only its author can verify, and
re-deriving it means trusting whoever ran the script last. The script prints the
pin it just produced, in the form the Kotlin wants, so updating a component is
copying two lines rather than computing them.

The other fetch scripts do not do this yet. They should.

### The pin is recorded; the release is not published yet

`ToolchainComponent.python` names
`.../releases/download/python-3.14.6/python-3.14.6-<arch>.tar.gz`, and **that
tag does not exist at the time of writing**. The two archives are built and
their checksums are what the component records, so publishing them is an upload
and not a rebuild — run `fetch-python.sh` for each arch, upload the two
`.tar.gz` it names, and nothing in the Kotlin has to change.

Until then:

- **`-Ppins=true` fails for this component and only this one**, with a 404. That
  is the network-gated check doing its job, not a wrong pin. Every other suite
  is unaffected — `PinnedReleaseTest` is skipped by default precisely so a suite
  does not fail on an aeroplane.
- **In-app install of Python 404s.** The instrumented tests do not go through
  the installer; they take `python.tar` from `DEVICE_ARCHIVES`
  (`gradle/stage-device-archives.gradle.kts`), which is why
  `:engine:python:connectedDebugAndroidTest` is green regardless.

If the archive is ever rebuilt from a newer Termux index, the version, both
checksums and both byte counts move together — the script prints all of them.

## 7. `bin/python3` is a symlink, so the archive is a tar

To `python3.14`, as `bin/mono` is to `mono-sgen`. `adb push` of a *tree* drops
every symlink (`tools/clang/FINDINGS.md` §4), so the archive moves as a tar and
is unpacked on the device.

Two consequences that are easy to get wrong:

- **The install marker is the versioned binary**, not `bin/python3`. An unpack
  interrupted between the two leaves a link pointing at nothing — and a link
  resolving nowhere is `isFile == false` anyway, so checking the real file is
  both the earlier and the honest test.
- **`PythonToolchain` finds the version by pattern**, not by name, so a Termux
  bump to 3.15 needs no code change. The pattern is `python3\.\d+` and it has to
  be: `python3.*` also matches `python3.14-config`, which is a *shell script*.
  `fetch-python.sh` hit exactly this and failed two steps later with "no
  standard library", naming neither the glob nor the script.

## 8. The shell probe agrees with the app, and that is luck

Every measurement in §2 and §3 reproduces through `adb shell` from
`/data/local/tmp`, which is how they were taken first. **That is not evidence
about the app** — `/data/local/tmp` is exec-allowed and app-private storage is
not, so a shell probe can demonstrate things the app cannot do
(`tools/clang/FINDINGS.md` §7). The numbers above are from
`PythonRunSystemTest`, in the app's own process, which is the only thing that
settles it.

Where the two *do* diverge is subprocess spawning, and this is the case that
shows why the distinction is worth insisting on. From the shell,
`subprocess.run([sys.executable, ...])` works. **In the app it does not**, and
the same program says so in one run:

```
direct  raised PermissionError [Errno 13] Permission denied:
        '/data/user/0/<pkg>/files/python/bin/python3.14'
linker  rc 0  out 42
```

So a Python program *can* start a child, but only through
`/system/bin/linker64`, like everything else here. `sys.executable` is the right
path to hand it — it is the real binary, per §2 — and handing it to `execve`
directly is what fails.

Nothing in `:engine:python` spawns a child today, and this is why `python -m
venv` cannot work (§4). Anything that starts to must plan the launch rather than
let Python exec a path. `whether_a_program_can_spawn_a_child_is_measured_here`
keeps this section honest: it prints both outcomes rather than asserting one, so
a platform change shows up as a changed log line instead of a green suite.
