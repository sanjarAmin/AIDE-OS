# Spike R7: a PTY and a shell on an unprivileged device — result

**Outcome: it works, job control included.** An ordinary Android app can call
`forkpty`, exec `/system/bin/sh` on the slave, and interrupt a foreground
command exactly the way Ctrl-C does. Nothing about it needed root, a rootfs,
`PRoot`, or any of the exec gymnastics that `tools/aapt2/FINDINGS.md` records.

This is the first risk in this project that was **not** "does this JVM library
run on ART". There is no PTY API in Java at all, so the terminal is the first
thing here that cannot be attempted without native code, and the questions were
about platform policy rather than about a library.

Measured on the `aideos_test` AVD (API 34, x86_64), NDK 28.2. Reproduce with
`:spike:pty:connectedDebugAndroidTest` and read the answers out of logcat under
the tag `PtySpike`. Seven tests, six consecutive clean runs.

## What works

| | |
|---|---|
| `forkpty` from an `untrusted_app` domain | works, no SELinux refusal |
| `exec` of `/system/bin/sh` | works |
| Shell is session leader and owns the terminal | `tcgetpgrp(fd) == shell pid` |
| A foreground command gets its own process group | yes — this is job control |
| `SIGINT` to that group returns the terminal | yes |
| `TIOCSWINSZ`, and the child seeing it | yes — `stty size` reports what was set |
| Exit status through `waitpid` | yes |

## 1. `forkpty` is permitted, and that was the real question

A PTY is allocated through `/dev/ptmx`, and whether an `untrusted_app` SELinux
domain may open one is policy, not libc. It may. `forkpty` is declared from API
23, comfortably under this project's `minSdk` of 26, and the declaration turns
out to match reality.

`forkpty` does `openpty` + `fork` + `setsid` + `TIOCSCTTY` in one call, which is
exactly the sequence job control depends on. The spike uses it rather than
hand-rolling the sequence **on purpose**: a mistake in that ordering looks
identical to a platform restriction, and telling those two apart afterwards is
expensive.

## 2. Job control works, and it is worth being precise about what that means

Two separate facts, asserted separately because either can be true without the
other:

- while `sleep 30` runs, `tcgetpgrp` reports a group that is **not** the
  shell's — the shell forked the command into its own group and handed the
  terminal over;
- signalling **that group** returns the terminal to the shell, which then
  accepts a new command.

Measured: shell at pid 21878, foreground group 21879 while sleeping.

**Signal the foreground group, not the shell.** Signalling the shell is the
mistake that makes an interrupt look like it works: the shell survives SIGINT,
nothing visibly breaks, and the running command never dies.

## 3. The child must have its signal dispositions reset

Signal handlers are not inherited across `exec`, but *ignored* dispositions and
the blocked-signal mask are. The JVM ignores and blocks a good deal, so a shell
started from an Android app can begin life with `SIGINT` ignored — and a shell
that cannot be interrupted looks exactly like a platform that does not support
job control.

So the child resets `SIGINT`, `SIGQUIT`, `SIGTERM` and `SIGCHLD` to `SIG_DFL`
and clears the signal mask before `execl`. This is the kind of line that looks
like superstition and is not.

The child also does nothing else between `fork` and `exec` that touches the
JVM: it is a forked copy of a multi-threaded process, so only
async-signal-safe work is legal until `execve` replaces the image.

## 4. An app can exec `/system/bin`, but cannot list it

```
PATH=/product/bin:/apex/com.android.runtime/bin:/apex/com.android.art/bin:
     /system_ext/bin:/system/bin:/system/xbin:/odm/bin:/vendor/bin:/vendor/xbin
$ ls /system/bin | wc -l
ls: /system/bin: Permission denied
0
```

`PATH` is populated and commands on it run, but the directory cannot be read.
That is a real constraint on the terminal's design rather than a curiosity:

- **no completion by directory listing.** Tab-completing a command name the way
  a desktop shell does is not available. Completion has to come from a
  built-in list, or from `PATH` entries that *are* readable, or not at all.
- **"what can I run here?" has no cheap answer.** Anything that wants to show
  the user their available commands has to probe them one at a time.

The shell itself is toybox-backed, so the built-ins and toybox applets are the
practical command set. `stty` is present, which is how the size test reads the
window back.

## 5. `/system/bin/sh`, and there is no `/bin`

The same fact `tools/git/FINDINGS.md` finding 1 records from the other side:
JGit's `FS.detect()` looks for `/bin/sh` to read a umask and finds nothing.
Android has no `/bin` at all. Anything in this project that wants a shell must
name `/system/bin/sh` explicitly.

## 6. Wrapping the master fd needs reflection

There is no public way to build a `java.io.FileDescriptor` around an integer fd,
so the private `descriptor` field is set by reflection. The alternative is a
second JNI entry point doing the same thing from the other side, which is not an
improvement. This is what every terminal app on the platform does, and the field
has been `int descriptor` on Android since the beginning.

## 7. Reading a terminal is not reading a file

Three things that cost time in the tests, and will cost the same time again in
`:terminal`:

- **The terminal echoes what was typed**, so the first occurrence of any string
  in the output is usually the echo rather than the answer. Every read has to be
  anchored on a marker the reader chose, never on a newline.
- **There is no EOF** until the shell exits, so a read loop needs its own
  deadline.
- **A shell abandons the rest of a command list when the foreground job is
  interrupted.** `sleep 30; echo BACK` is the obvious interrupt test and it is
  wrong — the `echo` never runs, correctly. What proves an interrupt worked is
  that the terminal came *back* to the shell and it accepts a new command.

## 8. Not answered

- **Lifetime when the app is backgrounded.** Android kills process groups under
  memory pressure and freezes backgrounded apps; what happens to a shell
  mid-command decides whether the terminal can be a tab you leave open. **This
  was not tested**, and it is the biggest remaining unknown.
- **Throughput.** Nothing here measures how fast output can be pumped through
  the master fd, and a terminal that stalls the UI on `cat` of a large file is a
  real failure mode.
- **Terminal emulation.** There is none. `TERM` is set to `dumb` deliberately,
  because claiming `xterm` would invite escape sequences nothing here can read.
  A real terminal needs a full emulator — state machine, scrollback, character
  attributes, alternate screen — and that is by far the larger half of the work.
  Termux's `terminal-emulator` is GPLv3 and so is this project, which is one of
  the reasons that licence was chosen; vendoring it should be evaluated before
  writing one.
- **arm64 in practice.** The library builds for `arm64-v8a` and `x86_64`, and
  only `x86_64` has been run. Nothing here is architecture-sensitive, but that
  is a reasoned expectation rather than a measurement.

## What M8's terminal half should take from this

1. **No further platform spike is owed.** The PTY, the shell, and job control
   all work. Build `:terminal` on `forkpty`.
2. **The emulator is the work**, not the process handling. Evaluate vendoring
   Termux's before writing one.
3. **Test lifetime early.** Whether a shell survives backgrounding shapes the
   feature and is not yet known.
4. **Design around not being able to list `/system/bin`.**

Delete `:spike:pty` once `:terminal` answers the same questions under its own
tests. Keep this file.
