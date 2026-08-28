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
`:terminal:connectedDebugAndroidTest` and read the answers out of logcat under
the tag `Terminal`. Eight tests, six consecutive clean runs of the seven core ones and three of the background one.

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
| A shell running while the app is backgrounded | yes, unthrottled — but see section 8 |

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

## 8. A backgrounded app's shell keeps running, at full speed

This was the biggest open question, and it has an answer. A shell appending a
line a second, with the app sent to the home screen:

```
shell produced 2 lines while in the foreground
after 45s in the background: 46 lines (+44 while away)
```

44 lines in 45 seconds, repeated three times identically. Not merely alive:
**unthrottled**. Neither the freezer nor a process-group kill touched it.

Asked with a real `Activity` in a real task, because the instrumentation process
is never *cached* and a shell forked from it would never meet the treatment
Android gives a backgrounded app.

**Two caveats, and the second is the load-bearing one.**

- This is an emulator on API 34. Process lifetime is the area where OEM
  behaviour diverges most, and a device with an aggressive vendor policy is a
  different measurement.
- **A process under instrumentation may not be frozen at all.** The app is
  being debugged, in the platform's terms, and that is exactly the state in
  which Android relaxes cached-app handling. So the honest reading is: *nothing
  in the PTY or the process handling stops a shell running in the background* —
  not *the platform will let a released app do this indefinitely*.

`:terminal` should therefore still plan for a foreground service if a shell is
meant to outlive the user switching away, the same shape `:toolchain:manager`
already uses for downloads. What this rules out is the worse possibility: that
a PTY child is killed immediately and the design is impossible.

## 9. Not answered
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

## 10. Reading a terminal in a test: the echo is not the answer

Learned twice, the second time expensively. The terminal echoes what was typed,
so a test that waits for a marker literally present in the command it sent
matches the **echo** and returns before the shell has run anything.

The spike's own `a_shell_runs_and_answers` asserted its marker appeared "at
least once" and would have passed against a shell that never started. It was
only caught when `TerminalSessionTest`'s resize test waited the same way and
then asserted on output that had not arrived yet.

The fix is to split the marker with shell quoting: `AIDE-OS-PTY'-WORKS'` is
echoed **with** the quotes and printed **without** them, so the unquoted form
appears only in the shell's answer. Both forms are now checked in that test --
the quoted one proves the fd echoes, which is itself evidence it is a terminal
and not a pipe.

## What M8's terminal half should take from this

1. **No further platform spike is owed.** The PTY, the shell, job control and
   background survival all work. `:terminal` is built on `forkpty`.
2. **The emulator is the work**, not the process handling. Evaluate vendoring
   Termux's before writing one.
3. **Plan for a foreground service anyway**, per section 8's second caveat: the
   background result was measured under instrumentation, which is the state in
   which the platform relaxes cached-app handling.
4. **Design around not being able to list `/system/bin`.**

`:spike:pty` is **gone**: `:terminal` carries these assertions now, in
`PtyOnDeviceTest` and `BackgroundSurvivalTest`, which is what the spike existed
to establish. This file stays, because the answers are why the module is shaped
the way it is.
