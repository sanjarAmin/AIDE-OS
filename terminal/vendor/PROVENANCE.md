# Vendored: Termux's terminal emulator

The escape-sequence parser, screen model and key mapping in
`src/main/java/com/termux/terminal/` are **not this project's code**. They come
from Termux.

| | |
|---|---|
| Upstream | https://github.com/termux/termux-app |
| Path | `terminal-emulator/src/main/java/com/termux/terminal/` |
| Commit | `30ebb2dee381d292ade0f2868cfde0f9f20b89fe` |
| Committed | 2026-04-06T23:11:27Z |
| Licence | **Apache License 2.0** |

## Why Apache and not GPLv3

The `termux/termux-app` repository as a whole is GPLv3, and it would be easy to
assume these files are too. They are not: the repository's own `LICENSE.md`
records an exception for `terminal-view` and `terminal-emulator`, because they
derive from [Terminal Emulator for
Android](https://github.com/jackpal/Android-Terminal-Emulator), which is Apache
2.0.

That is worth stating plainly because it changes the obligation. Apache 2.0 is
one-way compatible with this project's GPLv3: the combined work is GPLv3, the
vendored files stay Apache 2.0, and their licence and attribution have to travel
with them — which is what this directory is for.

`LICENSE-Apache-2.0.txt` is the full text. Upstream ships no `NOTICE` file for
this module, so there is none to reproduce.

## What was taken, and what was not

**Taken** — the emulator and nothing else:

```
TerminalEmulator  TerminalBuffer  TerminalRow  TerminalColors
TerminalColorScheme  TextStyle  WcWidth  KeyHandler  TerminalOutput  Logger
```

**Not taken** — Termux's process handling: its `TerminalSession`, `JNI`,
`ByteQueue` and `jni/termux.c`. This project already has that layer in
`com.osamu.aide.terminal`, built on `forkpty` and tested on a device by spike
R7 (`tools/pty/FINDINGS.md`). Running two implementations of the same thing
would be worse than the one file described below.

## Modifications

**None. Every vendored file is byte-identical to upstream**, and
`CHECKSUMS.sha256` records the hashes to prove it:

```sh
cd terminal/src/main/java/com/termux/terminal && sha256sum -c "$OLDPWD/terminal/vendor/CHECKSUMS.sha256"
```

That was not free, and it is the reason one file in that package is ours:

`TerminalSessionClient.java` **replaces** upstream's interface of the same name.
Upstream's declares callbacks that each take a Termux `TerminalSession`, so
compiling the emulator against it would pull in the whole process half. Ours
declares only what the vendored code calls — `TerminalEmulator` touches a client
exactly twice, and `Logger` forwards five log levels — and everything else the
emulator needs to say goes through `TerminalOutput`, which is vendored
unmodified.

It carries a `NOT UPSTREAM` notice at the top. Keeping the substitution in a
file we own is what lets every other file stay untouched, so **an update is a
copy, not a merge**.

## Updating

1. Copy the ten files listed above from the same upstream path.
2. Regenerate `CHECKSUMS.sha256` and update the commit in this file.
3. Rebuild. If `TerminalSessionClient` no longer satisfies the vendored code,
   the compiler says so immediately — that interface is the only seam.
