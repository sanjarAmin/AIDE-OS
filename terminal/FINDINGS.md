# `:terminal` — findings

Spike R7 (`tools/pty/FINDINGS.md`) answered the platform questions and this
module inherited its tests. Nothing here is a workaround for Android; the
platform turned out to be fine. What follows is design, and the reasoning
behind the parts that look like shortcuts and are not.

## 1. The emulator is vendored, and the seam is one file

The escape-sequence parser, screen model and key mapping are Termux's, taken
unmodified. `vendor/PROVENANCE.md` has the commit, the licence and the
verification command; the short version is that they are **Apache 2.0**, not
GPLv3 as the enclosing repository would suggest, because they descend from
jackpal's Android-Terminal-Emulator.

Only the emulator was taken. Termux's own `TerminalSession`, `JNI`, `ByteQueue`
and `termux.c` were left behind, because this module already had that layer
from spike R7 and running two would be worse.

**Every vendored file is byte-identical to upstream**, which cost exactly one
file of ours: `com.termux.terminal.TerminalSessionClient`. Upstream's interface
of that name takes a Termux `TerminalSession` in every callback, which would
have dragged in the whole process half. Ours declares only what the vendored
code calls — `TerminalEmulator` touches a client **twice**, and `Logger`
forwards five log levels — because everything else the emulator needs to say
goes through `TerminalOutput`, which is vendored and which we implement.

That is what makes an update a copy rather than a merge, and it is worth
protecting: if a future version needs more from the client, the compiler says
so at that one seam.

## 2. What vendoring bought, in one assertion

The previous implementation **stripped** escape sequences, because without an
emulator there is nothing to interpret them into. The difference is not a matter
of polish:

```
printf 'AAAA\rBB\n'
```

An emulator leaves `BBAA` on one line — the carriage return moves the cursor to
column zero and the following characters overwrite in place. A stripper turns
`\r` into a newline, because with no cursor that is the only sane thing to do,
and produces two lines. `EmulatedTerminalTest` asserts the first, and the old
code could not have passed it.

`clear` is the same story: an erase sequence a stripper can only discard.

`AnsiText` and `TerminalScrollback` are **deleted**. They were the honest way to
show terminal output without an emulator, and they are dead the moment there is
one.

## 3. The session emits bytes; only the view decodes

`TerminalSession.output` is a flow of `ByteArray`, not `String`.

A multi-byte character can straddle two reads, and holding the partial sequence
is the decoder's job. A session that decoded eagerly would corrupt every UTF-8
character landing on a read boundary — and do it rarely enough to look like a
font problem rather than a bug.

That seam was designed for an emulator before there was one, and it paid: the
byte flow goes into `TerminalEmulator.append` unchanged, and the emulator holds
the partial sequence itself.

## 4. Everything emulator-related happens on one thread

`TerminalEmulator` is not thread-safe — it is a parser with a mutable screen
behind it — and bytes arrive on an IO thread while the UI reads on the main one.

It is confined to a single dispatcher, and what the UI gets is an immutable
[TerminalScreen] snapshot. That is what makes it safe without a lock the
renderer would have to hold, and it means the UI can never see a half-updated
screen.

The screen snapshot **trims trailing blank lines**. The emulator's screen is a
fixed `rows` tall and pads the unused part, so a fresh shell is one prompt and
twenty-three empty lines; a view that scrolls to the bottom of that shows the
padding and looks like a terminal that printed nothing. That is precisely how it
first appeared in the running app.

## 5. Input must be serialised, or it arrives out of order

The view model sends every keystroke through **one channel with one consumer**.

Launching a coroutine per key looks equivalent and is not: typing `xy`, pressing
Left and typing `Z` produced `xyZ` instead of `xZy`, because the arrow key
arrived after the letter it was meant to precede. A terminal that reorders input
is not subtly wrong, it is unusable — and the reordering is invisible until
something depends on sequence, which is why a test caught it and typing at it
would not have.

The channel is **per shell**, replaced on restart. Reusing it left the old
consumer running against a closed terminal and competing for events with the
new one, so after a restart about half of what was typed vanished into a dead
shell.

## 6. Output drops the oldest rather than back-pressuring

The shared flow buffers a bounded number of chunks and drops the oldest on
overflow.

A terminal that made its shell wait for the UI would hang the shell whenever
the UI fell behind, and `cat` of a large file is the ordinary case rather than
an edge one. A scrollback missing its oldest lines is the right thing to lose.

The emulator keeps its own bounded transcript (2000 lines, Termux's default), so
nothing above it needs to.

## 7. Stopping the reader is not stopping the shell

`TerminalSession.start` takes a scope that owns the reading coroutine;
`close()` kills the shell. Cancelling the scope stops the pump and leaves the
shell running.

Deliberate: a screen going away is not a reason to kill a build running in the
terminal. Spike R7 measured that a shell survives the app being backgrounded
unthrottled, so this is a lifetime the platform will actually honour.

`TerminalViewModel.shutdown()` is public rather than left to `onCleared`, which
is `protected` and cannot be called deterministically. The view model owns a
child process, and a test that cannot stop one leaks a shell per test.

## 8. Things known missing

- **Attributes are not rendered.** The emulator tracks colour, bold, inverse and
  underline per cell in `TerminalBuffer`; the view draws every character in one
  style. That is the largest remaining gap and it is *rendering* work, not
  emulation — the information is already there.
- **No custom `InputConnection`.** Characters reach the shell as the platform
  delivers them to a text field, which works for typing but means a soft
  keyboard's backspace does not become `^?`. The on-screen key row covers Esc,
  Tab, Ctrl and the arrows; Termux solves the general case with its
  `terminal-view`, which was not vendored.
- **No selection, no copy or paste.** `TerminalBuffer.getSelectedText` exists
  and nothing calls it.
- **No mouse reporting.** The emulator supports it; there is no view to
  originate the events.
- **One session per project, and it is not persisted.** Leaving the workspace
  kills the shell. Spike R7 showed a backgrounded shell survives, so a session
  that outlives the screen is possible and is not built.
