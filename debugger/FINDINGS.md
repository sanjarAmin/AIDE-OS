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

## 5. What is not built yet

- **Deferred breakpoints.** `classesBySignature` returns nothing for a class
  that has not loaded, which is the normal state early in an app's life. Real
  use needs a `CLASS_PREPARE` request and a set of breakpoints to install when
  it fires.
- **Stopping before `main`.** The agent is attached with `suspend=n`, because
  `suspend=y` blocks in the provider until a client connects and Android kills
  an app that does not draw. Debugging startup needs a different arrangement.
- **Expression evaluation, watchpoints, exception breakpoints.** All are
  further JDWP command sets against the same connection.
- **Object inspection beyond identity.** A `JdwpValue.Reference` carries an id;
  reading fields out of it is `ObjectReference.GetValues`, not yet written.
  `stringValue` is the one exception, because a string with no text is useless.
