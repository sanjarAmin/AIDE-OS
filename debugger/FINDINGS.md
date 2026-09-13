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

## 7. Breakpoints travel under a source key, not a class name

An editor knows a file; a VM knows classes. The only identity they share is
the **package path joined to the file name** — `com/example/Main.kt`. The
editor gets the package from the file's own `package` declaration (not its
directory: Kotlin does not require them to agree); the VM gets it from each
class's signature plus its `SourceFile` attribute. A class name will not do,
because a Kotlin file compiles to `MainKt`, every class it declares, and a
synthetic class per lambda.

`DebugController` places each line into every loaded class whose key matches
(`VirtualMachine.AllClasses`, filtered by package before spending a
`SourceFile` round trip per class), and asks for a held `ClassPrepare` on each
package that has a breakpoint, so later classes are covered before their code
runs. A mismatch between the two keys is silent — the breakpoint is placed
nowhere and never fires — which is why `SourceKeysTest` builds both from the
same file.

## 8. A breakpoint in `onCreate` needs the app to wait, and only when asked

Providers are created before `Application.onCreate`, so the generated agent can
hold startup until the debugger has placed its breakpoints. It must not hold
every launch: a debug build outlives the session, and the agent cannot tell a
launch from the Debug button from a launcher tap an hour later.

So it **asks the IDE**. `DebugHandshakeProvider.call("isDebuggerExpected")`
answers yes only for the calling package, only if the IDE registered it just
before launching, and only once. If yes, the agent waits (bounded, 15 s) for a
static `released` flag the debugger sets over `ClassType.SetValues`. Driven:

```
debugger listening on 127.0.0.1:37465
waiting for the debugger to place its breakpoints
released by the debugger          <- 670 ms later
Paused at MainActivity.java:12 on main
```

and a cold start from the launcher afterwards, with no session: 454 ms, no
wait. On API 30 and up the debug build needs `<queries><provider
android:authorities=…/>` to see the IDE at all; without it the call throws
"Unknown authority", which the agent must read as "nobody is coming", and
startup breakpoints silently stop working. `DebugAgentTest` asserts the entry.

## 9. Android freezes whichever app is not on screen, and both must stay awake

**This is the finding that decides whether on-device debugging works at all.**
Android 14 freezes cached processes (`CachedAppOptimizer`). During a session one
of the two apps is always in the background, and each freezes differently:

- **The debuggee frozen** — the IDE in front. Every JDWP command goes unanswered.
  Found that way: Step pressed, nothing happened, no log, no exception. `jdb` on
  the IDE showed its reader thread idle; the debuggee's main thread showed
  `do_freezer_trap` in `/proc/<pid>/task/<pid>/wchan`, `dumpsys activity
  processes` listed it `cch`, and bringing it forward logged `quick sync
  unfreeze` — after which the Step that had been waiting for a minute completed
  at once. SIGQUIT and `debuggerd -b` both fail on a frozen process, which is a
  clue in itself.
- **The IDE frozen** — the debuggee in front. Every `ClassPrepare` holds the
  loading thread until the IDE resumes it, so the app hangs on its next class
  load, and a breakpoint hit goes unnoticed.

Android Studio never meets this: its debugger is on another machine.

The fix keeps each process referenced by the other, since a process whose
component is held by the app on screen is not cached:

- **The debuggee binds the IDE's `DebugSessionService`** from the agent, during
  the handshake.
- **The IDE holds an unstable `ContentProviderClient` on the agent's provider.**
  Measured: the debuggee sat at `fg … BTOP (provider)` for 25 s while suspended
  in `onCreate`, and Step answered within a second.

Two wrong turns worth recording, because both look right:

- **The IDE binding a service in the debuggee killed it.** `BIND_AUTO_CREATE`
  creates the service on the debuggee's *main thread* — the thread a breakpoint
  in `onCreate` has suspended — so it never finished starting, and twenty
  seconds later: `bg anr: executing service …AideDebugKeepAlive`, killed, and
  the system scheduled a restart of the process for the binding. A provider was
  published before any app code ran and needs nothing from the main thread.
- **A *stable* provider reference** would do the same job and get the *client*
  killed when the provider's process dies. The client is the IDE; the provider's
  process is an app being debugged, which dies constantly.

And one defence regardless: every JDWP request now times out (10 s) as a
`JdwpTimeoutException`, so an unanswered command reports "the app did not
answer" instead of holding the controller's lock — and every later button —
forever.

## 10. The IDE cannot bring itself forward; the app on screen can

A breakpoint is hit while the debuggee covers the IDE, so the IDE should come
back to front. From the IDE this is refused on API 34:

```
Background activity launch blocked [callingPackage: com.osamu.aide; … BAL_BLOCK
```

The debuggee *is* in front, and a breakpoint suspends only its own thread. So
the debugger sets the agent's static `comeForward` flag, and a daemon thread in
the agent launches the IDE:

```
START … cmp=com.osamu.aide/.MainActivity … from uid 10969 (BAL_ALLOW_VISIBLE_WINDOW) result code=2
```

`result code=2` is the existing task brought forward, not a new instance. The
launch flags must be the launcher's (`NEW_TASK | RESET_TASK_IF_NEEDED`): a bare
`am start -n` from the shell stacks a *second* `MainActivity` with its own view
models, which looked exactly like the session having been lost.

## 11. What is not built yet

- **Breakpoints are not persisted.** They live in the Debug view model and are
  gone when the workspace closes.
- **Breakpoints do not follow edits.** A line inserted above one leaves it on
  the old line number. The gutter mark and the debugger agree, because both
  read the same set; the code moved under both.
- **Kotlin inline functions.** A body inlined into its caller is in the
  caller's line table under an SMAP remapping, and lines are matched literally.
- **Inherited fields, arrays' elements, expression evaluation, watchpoints,
  exception breakpoints.** Further JDWP command sets on the same connection.
- **The Gradle engine.** The agent is generated by the fast pipeline, and a
  project built with Gradle is told so rather than debugged without one.
