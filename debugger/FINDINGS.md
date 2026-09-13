# The debugger

`:debugger` is a JDWP client. It is not what `docs/PLAN.md` described — spike
R15 (`tools/jdwp/FINDINGS.md`) established that an app cannot attach to another
app's JDWP, and that it does not have to: ART ships OpenJDK's `libjdwp.so`, and
a debuggable process can attach it to *itself* and listen on a socket. So the
debuggee half is something the **build** provides, and this module only has to
speak the protocol to it.

Read the spike first for why the module has this shape. What follows is what
building it taught, which is mostly about the pipeline on the other side.

## 1. The whole loop, driven

Against an app produced by this project's own fast pipeline — aapt2, ECJ, D8,
apksig — with `BuildRequest.debugPort` set, installed and started by hand:

```
attached: Dalvik 8
tick line table: [11, 12, 13]
tick locals: [accumulatedTotal:I@0, this:Lcom/example/debugdemo/Ticker;@3, counter:I@4]
  run()V   -> counter:I@0[1+12], this:...@3[0+13]
  tick(I)I -> accumulatedTotal:I@0[2+25], this:...@3[0+27], counter:I@4[0+27]
STOPPED on thread ticker at tick, frames=3, values={this=Reference(tag=76, id=4), counter=Primitive(tag=73, value=69)}
resumed
```

Every one of those is a separate thing that could have failed: the injected
provider ran, the agent bound, an unrelated app connected, the class was found
by signature, the line table survived dexing, a breakpoint suspended a thread,
its frame was readable, and a named local held the value the app's own log was
printing.

## 2. ECJ emits no local variable table unless told, and the failure is quiet

**This is the finding worth carrying.** ECJ's default is `-g:lines,source`.
That is enough for everything a debugger does *except* naming variables, so:

- breakpoints work,
- stepping works,
- stack frames are correct,
- `Method.LineTable` is complete,

and then every local comes back with an **empty name**:

```
tick locals: [this:Lcom/example/debugdemo/Ticker;@2, :I@3]
values={this=Reference(tag=76, id=4), =Primitive(tag=73, value=1098)}
```

The *value* is right. Only the name is missing, and only for the variables a
user came to look at. Nothing in the build says anything.

`JavaCompileStage` now passes `-g` for a debuggable build and `-g:lines,source`
for a release one — line numbers are what make a crash report readable and are
worth their bytes in a shipped app; variable names are not, and are a small
disclosure. `DebugAgentTest` pins both directions against the dex.

D8 was already right: `DexStage` selects `CompilationMode.DEBUG` for debuggable
builds, which preserves what ECJ emits. Both halves had to be correct and only
one was.

## 3. `dexdump` is the arbiter, and it settled this in one command

The symptom above has three candidate causes — the compiler not emitting, the
dexer discarding, or this client mis-parsing — and they are indistinguishable
from the debugger's output. `dexdump -d` prints the debug information that is
actually in the APK:

```
locals :
  0x0002 - 0x001b reg=0 accumulatedTotal I
  0x0000 - 0x001b reg=3 this Lcom/example/debugdemo/Ticker;
  0x0000 - 0x001b reg=4 counter I
```

Present in the dex, absent over JDWP, which pointed the search at the client —
where the answer turned out to be that the *running app* was an older build.
`~/Android/Sdk/build-tools/36.0.0/dexdump`, and it is the first thing to reach
for when a debugger disagrees with a source file.

**The mistake that cost the most here was reinstalling nothing.** The APK was
rebuilt and pulled; the process being debugged was the one from before the fix.
A debugger attached to a stale build reports the stale build's truth, perfectly
plausibly. `adb uninstall` before `install` when the question is whether a
build change took effect.

## 4. `Method.VariableTable` is the reply that is easiest to read wrongly

Five fields per entry, in an order nobody would declare them in — `codeIndex`,
`name`, `signature`, `length`, `slot` — preceded by two adjacent integers,
`argCnt` and `slots`, of which only the second is a count. Read those two in
the wrong order and exactly one entry is lost: the first argument, which is
usually the one being looked for.

A misread here does not throw. It produces entries that look almost right — a
real name beside the wrong slot — and reading a frame with them returns another
variable's value under this one's name, which is worse than having no debugger.
`parseVariableTable` is therefore a plain function with a JVM test whose
fixture is the `dexdump` output above. **It was suspected and was innocent** —
the test passed first time, which is what pointed back at the stale debuggee in
§3.

## 5. A breakpoint in a class that has not loaded is a ClassPrepare, held

Early in an app's life most classes do not exist yet, and `ClassesBySignature`
answers nothing for them. Polling until it does loses a race: by the time a
poll sees the class, the line the breakpoint was for may have run.

`requestClassPrepare` asks to be told when the class prepares, with
**`SuspendPolicy.EVENT_THREAD`, which is the point rather than a default**: the
thread that caused the load is held before any of the class's code runs, so a
breakpoint installed in response is guaranteed to be there first. With `NONE`
it is a race the debugger usually wins on an emulator. The caller must resume
that thread afterwards or the app stays frozen.

`DeferredBreakpointTest` restarts the debuggee, attaches inside a five-second
window, **asserts the class is absent before asking**, and gets:

```
deferred breakpoint hit in com.osamu.aide.spike.jdwpdebuggee.LateLoaded.run at line 22
```

Without the absence check the test would pass on the ordinary path and prove
nothing about this one. Two details the test needed:

- **The late class is loaded by reflection.** ART's verifier may load a class as
  soon as a method that names it is verified, which for anything the Activity
  referred to directly means at startup. `LateLoaded` is named only as a string.
- **It restarts the debuggee on a port of its own** (8701), so the new agent is
  not binding a port the killed process may have left in `TIME_WAIT`, and puts
  a debuggee back on 8700 afterwards for `DebugSessionTest`.

## 6. An object's fields: three round trips, and only its own class's

`fields(objectId)` is `ObjectReference.ReferenceType`, then
`ReferenceType.Fields`, then `ObjectReference.GetValues`. **`Fields` does not
walk superclasses** — an Activity's own state comes back and `Activity`'s does
not — and static fields are left out, because showing a shared value under one
instance invites reading it as that instance's.

The test's assertion is agreement, not plausibility. Stopped on the first line
of `step(counter)`, the object's `ticks` field was set from the value this call
received as `counter`, so the two must be the same number:

```
fields of this: ticks=134 counter=134 label ok
```

A field read at the wrong offset or out of the wrong object cannot match by
accident. The string field takes the other path — its value is an object id,
turned into text by `StringReference.Value`.

## 7. What is not built yet

- **A UI.** Breakpoints in the editor gutter, a stopped-thread view, locals and
  Continue/Step. The library is the whole debugger; nothing in the app calls it.
- **Stopping before `main`.** The agent is attached with `suspend=n`, because
  `suspend=y` blocks in the provider until a client connects and Android kills
  an app that does not draw. Debugging startup needs a different arrangement.
- **Inherited fields, arrays, expression evaluation, watchpoints, exception
  breakpoints.** All are further JDWP commands against the same connection.
- **Kotlin line mapping for inline functions.** A breakpoint in an inlined body
  lives in the caller's line table under an SMAP remapping, and `breakpointAt`
  only matches literal line numbers.
