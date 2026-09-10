# Debugging another app from an unprivileged app

Spike R15, 2026-09-09. `docs/PLAN.md` lists `:debugger` as "JDWP client for
on-device Java/Kotlin debugging". This asks the permission question before any
protocol code is written, because every milestone here that looked like a
library problem was a platform-permission problem first: clang could not spawn,
Node's `execPath` was the linker, the Logcat tab saw only its own process.

Probed **in an app's own process**, not through `adb shell` or `run-as`.
`tools/clang/FINDINGS.md` §7 records this project trusting a `run-as` probe and
being wrong; `run-as` runs in `runas_app`, which is a different SELinux domain
from `untrusted_app`, and `shell` is different again.

## 1. `@jdwp-control` is not reachable from an app — closed

`/proc/net/unix` shows it bound and listening, so the name is real:

```
0000000000000000: 00000003 00000000 00000000 0005 03 335769 @jdwp-control
```

Connecting from an app gets `IOException: Connection refused`.

Worth reading precisely: **refused, not denied**. An SELinux denial on an
abstract socket surfaces as `EACCES`/`EPERM`; "connection refused" is the
socket declining the connection. Either way the route is shut, and it is shut
without a message that names a permission to ask for — so there is nothing to
put in front of the user.

An app also cannot enumerate what is there. `/proc/net/unix` is unreadable from
an app on this API level: the probe that counted `jdwp` lines returned −1, and
`/proc/net/tcp` likewise shows only the caller's own sockets. So an app cannot
even discover which processes are debuggable.

## 2. adbd over TCP is reachable — on an emulator, and only as far as connect()

An app with `INTERNET` connects to `127.0.0.1:5555` successfully.

**Two caveats, and both matter more than the result.**

*It is an emulator.* `emulator-5554` means console 5554 and **adbd on TCP 5555
by default** — that is how the host talks to it. A physical device has no TCP
listener until the user turns on wireless debugging, and then the port is
randomised and the connection must be paired. Nothing here has been run on a
phone; that is the first thing to repeat when one is available.

*Connecting is not authenticating.* All that was established is that the socket
accepts. adbd then requires an RSA challenge the user authorises on-screen, and
wireless debugging adds TLS pairing with a six-digit code. Whether an app can
complete either is **unknown and is the next question** — it is what decides
whether this route is real. Do not read "CONNECTED" as "debugging works".

*And the first run of this probe was wrong.* It reported
`SocketException: socket failed: EPERM` and looked like the platform refusing
an app access to adbd. The spike module simply had no `INTERNET` permission, so
`socket()` failed before any connection was attempted. A missing permission and
a policy denial are indistinguishable from the message.

## 3. A JVMTI agent attaches to the calling process — open, and it is the answer

`Debug.attachJvmtiAgent` with a path that does not exist fails with:

```
IOException: Unable to dlopen /data/.../no-such-agent.so: library not found
```

It reached `dlopen`. The mechanism is **not** closed off — only the agent was
missing. The process was debuggable (`FLAG_DEBUGGABLE`), which is what a test
APK and a debug build both are.

**And the agent that was missing is already on the device.** ART's apex ships
OpenJDK's own debugger agent and its socket transport:

```
/apex/com.android.art/lib64/libjdwp.so        295 KB
/apex/com.android.art/lib64/libdt_socket.so    20 KB
```

That is the same `libjdwp` Android Studio talks to through adb. It takes the
standard options, and an ordinary app may attach it to itself by bare name:

```kotlin
Debug.attachJvmtiAgent(
    "libjdwp.so",
    "transport=dt_socket,server=y,suspend=n,address=127.0.0.1:8700",
    null,
)
```

`PlatformJdwpAgentTest` does exactly this and then completes a JDWP handshake
against the port. **So the debuggee half of a debugger is four lines of Kotlin,
no native code of ours, and nothing downloaded.**

Three details that are not optional:

- **`suspend=n`.** `suspend=y` blocks in `onCreate` until a client attaches, and
  Android kills an app that does not draw. Stopping before `main` is a separate
  problem; stopping a *running* app is the common case.
- **The debuggee must declare `INTERNET`.** `dt_socket` is the only transport
  `libjdwp` has here, so the JDWP server is a TCP listener, and a process
  without `INTERNET` cannot call `socket()`. **The agent does not report this.**
  It writes to a stdout nobody reads and calls `exit(2)`, so the app vanishes
  between `nativeloader` and its own first log line — `reason=1 (EXIT_SELF)
  status=2` in `dumpsys activity exit-info`, no exception, no tombstone. This
  spike lost twenty minutes to it, having already recorded the same missing
  permission in §2.
- **Only a debuggable process.** The platform refuses `attachJvmtiAgent`
  otherwise, which is the right gate: a release build cannot be opened this way.

## 4. One app can debug another over loopback — proved, with a second app

The finding above was one process talking to itself, which settles nothing:
the IDE and the app under debug are different packages with different UIDs.

`:spike:jdwpdebuggee` is that second app — a separate package that attaches the
agent as above and then runs a loop with a method worth breakpointing.
`JdwpAcrossAppsTest`, running in `:spike:jdwp`, connects to it and speaks the
protocol. It does not stop at the handshake, because a TCP accept proves a
listener and not a debugger; it sends `VirtualMachine.Version` and
`VirtualMachine.IDSizes` and parses both replies:

```
description=Java Debug Wire Protocol (Reference Implementation) version 1.8
            JVM Debug Interface version 1.2
            JVM version 8 (Dalvik, )
jdwp=1.8  vmVersion=8  vmName=Dalvik
idSizes(field=8 method=8 object=8 refType=8 frame=8)
```

**An unprivileged app debugged another app. No root, no adb, no Shizuku, no
pairing.** Android does not isolate loopback TCP between apps, and the platform
agent does the rest.

Two facts from that reply that any client must carry:

- **Every ID is 8 bytes on ART.** They are variable-width by spec and the VM
  decides, so `IDSizes` has to be the first thing after the handshake or every
  later packet is parsed at the wrong offsets.
- **The packet header is 11 bytes and the length field includes it.** Reading
  `length` bytes of body leaves the next header in the stream, and everything
  after that is garbage that looks like a protocol bug.

## 5. What this makes `:debugger`

Routes 1 and 3 together say the module is **not** a JDWP client attaching to
arbitrary processes — that shape is unavailable to an unprivileged app, and
`docs/PLAN.md`'s one-line description assumes it is. But §3 and §4 say
something better than the fallback this spike expected:

**`:debugger` is a JDWP client, and the debuggee is our own build template.**

1. A debug build produced by AIDE-OS attaches `libjdwp.so` to itself on start
   and listens on loopback. Four lines, plus `INTERNET` in the debug manifest.
2. `:debugger` connects and speaks JDWP: handshake, `IDSizes`, class and method
   lookup, `EventRequest.Set` for breakpoints, `StackFrame.GetValues` for
   locals, `ThreadReference.Resume`.

No privileged helper, no user setup, no pairing flow. It debugs apps AIDE-OS
built, which for this product is close to the whole requirement.

**CodeOnTheGo took the other route**, and it is worth being precise about why
rather than filing it as a difference of taste. From their Show HN thread, they
*"attach the JDWP agent to the target process at launch and route its output to
our debugger over a local socket"*, using *"a scoped adaptation of the
[Shizuku](https://shizuku.rikka.app/) project to get the necessary system access
without requiring root."* Shizuku runs a helper as `shell`, started by the user
pairing over wireless debugging on the device itself. From `shell` you get
`am set-debug-app`, `/proc`, and JDWP for **any** debuggable app on the phone —
including ones the IDE did not build. That is a strictly larger capability, and
it costs a pairing flow that has to be redone after a reboot.

So the two are not competing answers to one question; they answer different
ones. The permission is only needed for the app AIDE-OS did *not* build.

## Order of work

1. **Build the client.** `:debugger`, with the wire layer under unit tests and
   an instrumented test against `:spike:jdwpdebuggee`. Nothing below needs a
   phone or a decision.
2. **Put the stub in the build template**, gated on `BuildRequest.debuggable`,
   and add `INTERNET` to the debug manifest only. Injecting a permission into a
   user's app is a decision that has to be visible in the UI — a release build
   must carry neither.
3. **Then, and only if it is wanted:** the Shizuku route, for attaching to apps
   AIDE-OS did not build. Measure the pairing flow on a phone first — how many
   steps, and whether it survives a reboot — because that cost is the feature's
   real price and cannot be judged from an emulator.
