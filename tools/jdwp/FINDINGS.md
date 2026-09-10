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

## 3. A JVMTI agent attaches to the calling process — open

`Debug.attachJvmtiAgent` with a path that does not exist fails with:

```
IOException: Unable to dlopen /data/.../no-such-agent.so: library not found
```

It reached `dlopen`. The mechanism is **not** closed off — only the agent was
missing. The process was debuggable (`FLAG_DEBUGGABLE`), which is what a test
APK and a debug build both are.

This cannot attach to another app: the agent loads into the caller. But AIDE-OS
controls what it builds, so a stub linked into a debug build could open a
channel from *inside* the debuggee to the IDE.

## What this makes `:debugger`

Routes 1 and 3 together say the module is **not a JDWP client attaching to
arbitrary processes**. That shape is unavailable to an unprivileged app, and
`docs/PLAN.md`'s one-line description assumes otherwise.

What is available is **cooperative debugging of apps AIDE-OS built**: a debug
build carries a stub, the stub speaks to the IDE, and the IDE drives it. That
is narrower than Android Studio and is close to the whole requirement — the
apps a user wants to debug here are the ones they just built.

The open question, before committing to that, is §2's second caveat: if an app
can authenticate to adbd over wireless debugging, the JDWP route reopens for
*any* debuggable app, and the cooperative stub becomes unnecessary. That needs
a physical device, and it is worth an hour before the stub is designed.

Order of work, therefore:

1. On a phone: enable wireless debugging, and find out whether an app can pair
   and authenticate. This is the branch point.
2. If it can — `:debugger` is a JDWP client over adb, and works on any
   debuggable app.
3. If it cannot — `:debugger` is a stub in the build template plus a client,
   and works on apps built here. Say so plainly in the UI, because "debug"
   meaning something narrower than usual is a promise worth keeping honest.
